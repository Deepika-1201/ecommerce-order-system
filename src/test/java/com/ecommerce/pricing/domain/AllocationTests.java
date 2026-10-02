package com.ecommerce.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AllocationTests {

    @Test
    void leftoverPaiseGoToTheLargestFractions() {
        // Exact shares 19,399.64, 26,127.54 and 4,472.82 (LLD §4.7, worked example 1).
        assertThat(Allocation.largestRemainder(50_000, new long[] {259_800, 349_900, 59_900}))
                .containsExactly(19_400, 26_127, 4_473);
    }

    @Test
    void tiesGoToEarlierLines() {
        assertThat(Allocation.largestRemainder(2, new long[] {100, 100, 100})).containsExactly(1, 1, 0);
        assertThat(Allocation.largestRemainder(1, new long[] {3, 3})).containsExactly(1, 0);
    }

    @Test
    void edgesAreExact() {
        assertThat(Allocation.largestRemainder(0, new long[] {7, 9})).containsExactly(0, 0);
        assertThat(Allocation.largestRemainder(16, new long[] {7, 9})).containsExactly(7, 9);
        assertThat(Allocation.largestRemainder(5, new long[] {1_000})).containsExactly(5);
    }

    @Test
    void hugeAmountsDoNotOverflow() {
        long crore = 1_000_000_000L;
        long[] weights = {10 * crore, 10 * crore, 10 * crore};
        assertThat(Allocation.largestRemainder(30 * crore - 100, weights))
                .containsExactly(10 * crore - 33, 10 * crore - 33, 10 * crore - 34);
    }
}
