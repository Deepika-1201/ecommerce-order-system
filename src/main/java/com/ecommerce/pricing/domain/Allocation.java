package com.ecommerce.pricing.domain;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/** Splits an amount in proportion to weights, by the largest-remainder method (ADR-019). */
final class Allocation {

    private Allocation() {
    }

    /**
     * Each share is the floor of its exact share, and the leftover paise go one each to the largest fractions,
     * earlier positions first on ties. The shares add up to {@code total}, each within one paisa of exact, and
     * none exceeds its weight when {@code total} does not exceed the sum of the weights.
     */
    static long[] largestRemainder(long total, long[] weights) {
        BigInteger sum = BigInteger.valueOf(Arrays.stream(weights).sum());
        BigInteger amount = BigInteger.valueOf(total);
        long[] shares = new long[weights.length];
        BigInteger[] remainders = new BigInteger[weights.length];
        long allocated = 0;
        for (int i = 0; i < weights.length; i++) {
            BigInteger[] division = amount.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(sum);
            shares[i] = division[0].longValueExact();
            remainders[i] = division[1];
            allocated += shares[i];
        }
        int[] byRemainder = IntStream.range(0, weights.length).boxed()
                .sorted(Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .mapToInt(Integer::intValue)
                .toArray();
        for (int k = 0; k < total - allocated; k++) {
            shares[byRemainder[k]]++;
        }
        return shares;
    }
}
