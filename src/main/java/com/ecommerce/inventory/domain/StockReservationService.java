package com.ecommerce.inventory.domain;

import com.ecommerce.inventory.CommitResult;
import com.ecommerce.inventory.StockReservation;
import com.ecommerce.inventory.StockReservations;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.util.Assert;

/**
 * Reservations, one per order, all lines or none (LLD §5.4–§5.6, ADR-009). A transaction that changes stock locks the
 * order's reservation first, then the stock rows in lock order, and does nothing else while it holds them (LLD §5.7).
 */
@Service
class StockReservationService implements StockReservations {

    private static final Duration MAX_HOLD = Duration.ofDays(1);

    private final ReservationRepository reservations;
    private final StockRepository stock;
    private final HoldExpiry expiry;
    private final StockLocks locks;
    private final Clock clock;

    StockReservationService(ReservationRepository reservations, StockRepository stock, HoldExpiry expiry,
            StockLocks locks, Clock clock) {
        this.reservations = reservations;
        this.stock = stock;
        this.expiry = expiry;
        this.locks = locks;
        this.clock = clock;
    }

    @Override
    public StockReservation reserve(UUID orderId, List<Line> lines, Duration hold) {
        Assert.notNull(orderId, "orderId must not be null");
        Assert.notEmpty(lines, "An order reserves at least one line");
        Assert.isTrue(hold.isPositive() && hold.compareTo(MAX_HOLD) <= 0, "hold must be positive and at most a day");
        List<ReservedLine> wanted = inLockOrder(lines);
        try {
            Optional<StockReservation> recorded = recordedOutcome(orderId, wanted);
            if (recorded.isPresent()) {
                return recorded.get();
            }
            StockReservation outcome = tryToHold(orderId, wanted, hold);
            if (outcome instanceof StockReservation.Rejected) {
                reclaimExpiredHolds(wanted);
                outcome = tryToHold(orderId, wanted, hold);
            }
            if (outcome instanceof StockReservation.Rejected rejected) {
                locks.runInNewTransaction(status -> reservations.insert(Ids.newId(), orderId,
                        ReservationStatus.REJECTED, null, rejected.sku(), rejected.available(), wanted,
                        clock.instant()));
            }
            return outcome;
        } catch (DuplicateKeyException concurrentRepeat) {
            // The same order was reserved at the same time, and its outcome was recorded first.
            return recordedOutcome(orderId, wanted).orElseThrow();
        }
    }

    @Override
    public CommitResult commit(UUID orderId) {
        Optional<CommitResult> result = locks.inNewTransaction(status -> commitOnce(orderId, status));
        if (result.isEmpty()) {
            reclaimExpiredHolds(reservations.find(orderId).orElseThrow().lines());
            result = locks.inNewTransaction(status -> commitOnce(orderId, status));
        }
        return result.orElse(CommitResult.LOST);
    }

    @Override
    public void release(UUID orderId) {
        locks.runInNewTransaction(status -> reservations.lock(orderId).ifPresent(reservation -> {
            switch (reservation.status()) {
                case HELD, COMMITTED -> {
                    Instant now = clock.instant();
                    reservation.lines().forEach(line -> stock.giveBack(line, now));
                    reservations.setStatus(reservation.id(), ReservationStatus.RELEASED, now);
                }
                case RELEASED, EXPIRED, REJECTED -> {
                    // Holds nothing.
                }
                case FULFILLED, RETURNED -> throw new IllegalStateException("Order " + orderId
                        + " cannot release its stock: it was handed over");
            }
        }));
    }

    @Override
    public void fulfill(UUID orderId) {
        locks.runInNewTransaction(status -> {
            Reservation reservation = existing(orderId);
            switch (reservation.status()) {
                case COMMITTED -> {
                    Instant now = clock.instant();
                    for (ReservedLine line : reservation.lines()) {
                        long onHand = stock.handOver(line, now);
                        stock.insertMovement(line.sku(), line.locationCode(), MovementKind.HANDOVER, -line.quantity(),
                                null, null, null, orderId, onHand, null, now);
                    }
                    reservations.setStatus(reservation.id(), ReservationStatus.FULFILLED, now);
                }
                case FULFILLED, RETURNED -> {
                    // Already handed over.
                }
                default -> throw wrongStatus(reservation, "fulfil");
            }
        });
    }

    @Override
    public void restockReturn(UUID orderId) {
        locks.runInNewTransaction(status -> {
            Reservation reservation = existing(orderId);
            switch (reservation.status()) {
                case FULFILLED -> {
                    Instant now = clock.instant();
                    for (ReservedLine line : reservation.lines()) {
                        long onHand = stock.restock(line, now);
                        stock.insertMovement(line.sku(), line.locationCode(), MovementKind.RETURN, line.quantity(),
                                null, null, null, orderId, onHand, null, now);
                    }
                    reservations.setStatus(reservation.id(), ReservationStatus.RETURNED, now);
                }
                case RETURNED -> {
                    // Already back on hand.
                }
                default -> throw wrongStatus(reservation, "restock a return of");
            }
        });
    }

    /** Held if every line was taken; otherwise rolled back, and Rejected with the first short line. */
    private StockReservation tryToHold(UUID orderId, List<ReservedLine> lines, Duration hold) {
        return locks.inNewTransaction(status -> {
            Instant now = clock.instant();
            UUID id = Ids.newId();
            Instant expiresAt = now.plus(hold);
            reservations.insert(id, orderId, ReservationStatus.HELD, expiresAt, null, null, lines, now);
            Optional<ReservedLine> shortLine = takeAll(lines, now, status);
            return shortLine.<StockReservation>map(line -> new StockReservation.Rejected(line.sku(),
                    stock.available(line))).orElseGet(() -> new StockReservation.Held(id, expiresAt));
        });
    }

    /** Empty when an expired reservation's lines cannot all be taken again; that transaction then rolls back. */
    private Optional<CommitResult> commitOnce(UUID orderId, TransactionStatus status) {
        Reservation reservation = existing(orderId);
        Instant now = clock.instant();
        return switch (reservation.status()) {
            case HELD -> {
                reservations.setStatus(reservation.id(), ReservationStatus.COMMITTED, now);
                yield Optional.of(CommitResult.COMMITTED);
            }
            case EXPIRED -> {
                if (takeAll(reservation.lines(), now, status).isPresent()) {
                    yield Optional.empty();
                }
                reservations.setStatus(reservation.id(), ReservationStatus.COMMITTED, now);
                yield Optional.of(CommitResult.COMMITTED);
            }
            case COMMITTED, FULFILLED, RETURNED -> Optional.of(CommitResult.COMMITTED);
            case RELEASED -> Optional.of(CommitResult.LOST);
            case REJECTED -> throw wrongStatus(reservation, "commit");
        };
    }

    /** Takes every line, in lock order; at the first that is short, marks the transaction for rollback. */
    private Optional<ReservedLine> takeAll(List<ReservedLine> lines, Instant now, TransactionStatus status) {
        for (ReservedLine line : lines) {
            if (!stock.take(line, now)) {
                status.setRollbackOnly();
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    /**
     * Expires the expired holds on these lines' SKUs, in a transaction of its own (amends ADR-009): an expired hold can
     * include SKUs these lines do not, which the caller could not lock without breaking the lock order.
     */
    private void reclaimExpiredHolds(List<ReservedLine> lines) {
        Set<String> skus = new HashSet<>();
        lines.forEach(line -> skus.add(line.sku()));
        expiry.reclaim(skus, Locations.BENGALURU);
    }

    /** The outcome recorded for this order, if any: Held if it was ever held, else Rejected as recorded. */
    private Optional<StockReservation> recordedOutcome(UUID orderId, List<ReservedLine> lines) {
        return reservations.find(orderId).map(reservation -> {
            if (!reservation.lines().equals(lines)) {
                throw new IllegalArgumentException("Order " + orderId + " already reserved other lines");
            }
            return reservation.status() == ReservationStatus.REJECTED
                    ? new StockReservation.Rejected(reservation.shortSku(), reservation.shortAvailable())
                    : new StockReservation.Held(reservation.id(), reservation.expiresAt());
        });
    }

    private Reservation existing(UUID orderId) {
        return reservations.lock(orderId).orElseThrow(() -> new IllegalStateException("Order " + orderId
                + " has no reservation"));
    }

    private static List<ReservedLine> inLockOrder(List<Line> lines) {
        List<ReservedLine> sorted = lines.stream()
                .map(line -> new ReservedLine(line.sku(), Locations.BENGALURU, line.quantity()))
                .sorted(ReservedLine.LOCK_ORDER)
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (ReservedLine.LOCK_ORDER.compare(sorted.get(i - 1), sorted.get(i)) == 0) {
                throw new IllegalArgumentException("SKU " + sorted.get(i).sku() + " is on more than one line");
            }
        }
        return sorted;
    }

    private static IllegalStateException wrongStatus(Reservation reservation, String action) {
        return new IllegalStateException("Cannot " + action + " the " + reservation.status() + " reservation of order "
                + reservation.orderId());
    }
}
