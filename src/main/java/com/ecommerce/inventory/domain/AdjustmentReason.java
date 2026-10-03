package com.ecommerce.inventory.domain;

/** Why a count was corrected; each reason allows one sign of change (LLD §5.8). */
public enum AdjustmentReason {
    DAMAGED,
    LOST,
    FOUND,
    COUNT_CORRECTION;

    boolean allows(int quantityChange) {
        return switch (this) {
            case DAMAGED, LOST -> quantityChange < 0;
            case FOUND -> quantityChange > 0;
            case COUNT_CORRECTION -> quantityChange != 0;
        };
    }
}
