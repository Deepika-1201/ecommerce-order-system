package com.ecommerce.fulfillment.carrier;

/** The carrier decided against the request: {@code unserviceable}, {@code invalid_address} or {@code picked_up}. */
public class CarrierRefusedException extends RuntimeException {

    private final String code;

    public CarrierRefusedException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
