package com.ecommerce.pricing.domain;

import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.HandlesMessage;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import com.ecommerce.pricing.CouponMessages.CommitCoupon;
import com.ecommerce.pricing.CouponMessages.CouponReserved;
import com.ecommerce.pricing.CouponMessages.CouponUnavailable;
import com.ecommerce.pricing.CouponMessages.ReleaseCoupon;
import com.ecommerce.pricing.CouponMessages.ReserveCoupon;
import com.ecommerce.pricing.CouponRedemptions;
import com.ecommerce.pricing.CouponReservation;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The saga's commands to Pricing (LLD §6.8), in the delivery transaction. An exhausted coupon is not recorded, so a
 * command sent again can succeed later; the saga releases such a late use.
 */
@Component
class CouponCommandHandlers {

    static final String REPLY_AGGREGATE = "coupon_redemption";

    private final CouponRedemptions redemptions;
    private final Messages messages;

    CouponCommandHandlers(CouponRedemptions redemptions, Messages messages) {
        this.redemptions = redemptions;
        this.messages = messages;
    }

    @HandlesMessage(consumer = "pricing.reserve-coupon")
    void reserveCoupon(IncomingMessage<ReserveCoupon> message) {
        ReserveCoupon command = message.payload();
        UUID orderId = command.orderId();
        Object reply = switch (redemptions.reserve(orderId, command.couponId(), command.customerId())) {
            case CouponReservation.Held _ -> new CouponReserved(orderId);
            case CouponReservation.Unavailable unavailable -> new CouponUnavailable(orderId, unavailable.reason());
        };
        messages.publish(reply, new Origin(REPLY_AGGREGATE, orderId, 0), Correlation.causedBy(message));
    }

    @HandlesMessage(consumer = "pricing.commit-coupon")
    void commitCoupon(IncomingMessage<CommitCoupon> message) {
        redemptions.commit(message.payload().orderId());
    }

    @HandlesMessage(consumer = "pricing.release-coupon")
    void releaseCoupon(IncomingMessage<ReleaseCoupon> message) {
        redemptions.release(message.payload().orderId());
    }
}
