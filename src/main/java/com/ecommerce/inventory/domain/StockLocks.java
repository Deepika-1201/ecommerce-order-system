package com.ecommerce.inventory.domain;

import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactions that change stock wait at most the lock timeout for a row lock (LLD §5.7). A longer wait fails with
 * {@link CannotAcquireLockException}, and the transaction rolls back.
 */
@Component
class StockLocks {

    // Spring does not translate lock_not_available to a specific exception.
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;
    private final TransactionTemplate newTransaction;
    private final InventoryProperties properties;

    StockLocks(JdbcClient jdbc, PlatformTransactionManager transactionManager, InventoryProperties properties) {
        this.jdbc = jdbc;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
    }

    /** Runs {@code work} in a transaction of its own, apart from any the caller has. */
    <T> T inNewTransaction(TransactionCallback<T> work) {
        return translated(() -> newTransaction.execute(status -> {
            limitWaits();
            return work.doInTransaction(status);
        }));
    }

    void runInNewTransaction(Consumer<TransactionStatus> work) {
        inNewTransaction(status -> {
            work.accept(status);
            return null;
        });
    }

    /** Caps lock waits for the rest of the current transaction. */
    void limitWaits() {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", properties.lockTimeout().toMillis() + "ms")
                .query(String.class)
                .single();
    }

    static <T> T translated(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                throw new CannotAcquireLockException("A stock row stayed locked longer than the lock timeout", e);
            }
            throw e;
        }
    }
}
