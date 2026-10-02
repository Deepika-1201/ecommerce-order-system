package com.ecommerce.pricing.domain;

/** GST amounts of one line or charge, in paise. Intra-state supplies have CGST and SGST; inter-state ones IGST. */
public record Tax(long cgstPaise, long sgstPaise, long igstPaise) {

    static final Tax NONE = new Tax(0, 0, 0);

    public long totalPaise() {
        return cgstPaise + sgstPaise + igstPaise;
    }

    Tax plus(Tax other) {
        return new Tax(cgstPaise + other.cgstPaise, sgstPaise + other.sgstPaise, igstPaise + other.igstPaise);
    }
}
