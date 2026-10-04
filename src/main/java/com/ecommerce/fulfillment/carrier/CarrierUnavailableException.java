package com.ecommerce.fulfillment.carrier;

/** The carrier did not answer: a timeout or an outage. Worth retrying with the same reference. */
public class CarrierUnavailableException extends RuntimeException {

    public CarrierUnavailableException(String message) {
        super(message);
    }
}
