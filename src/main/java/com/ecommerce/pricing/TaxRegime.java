package com.ecommerce.pricing;

import com.ecommerce.shared.IndianState;

/** GST place of supply: the warehouse's state against the delivery state (ADR-018). */
public enum TaxRegime {
    INTRA_STATE,
    INTER_STATE;

    public static TaxRegime of(IndianState supplyState, IndianState deliveryState) {
        return supplyState == deliveryState ? INTRA_STATE : INTER_STATE;
    }
}
