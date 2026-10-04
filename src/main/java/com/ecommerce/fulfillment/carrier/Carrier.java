package com.ecommerce.fulfillment.carrier;

import com.ecommerce.fulfillment.ShipmentMessages.Line;
import java.util.List;
import java.util.UUID;

/**
 * The carrier, as Fulfillment uses it (LLD §8.3). Calls happen outside transactions. {@link CarrierRefusedException}
 * is the carrier's decision; {@link CarrierUnavailableException} is worth retrying with the same reference.
 */
public interface Carrier {

    /** The carrier's name, as orders show it. */
    String name();

    /** Whether the carrier delivers to the PIN code, from a list kept locally: no call (ADR-026). */
    boolean serviceable(String pinCode);

    /** Books the parcel and returns its AWB; a known reference answers with the AWB it already has. */
    String book(Parcel parcel);

    /** Cancels the booking; refused as {@code picked_up} once the carrier has the parcel. */
    void cancel(String awb);

    /** What the carrier needs to book: this system's reference, where to deliver, and what is inside. */
    record Parcel(UUID reference, Address deliveryAddress, List<Line> lines) {

        public Parcel {
            lines = List.copyOf(lines);
        }
    }

    record Address(String recipientName, String phone, String line1, String line2, String landmark, String city,
                   String stateCode, String pinCode) {
    }
}
