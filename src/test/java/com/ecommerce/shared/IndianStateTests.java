package com.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IndianStateTests {

    @Test
    void listsTheTwentyEightStatesAndEightUnionTerritoriesOnce() {
        assertThat(IndianState.values()).hasSize(36);
        assertThat(Arrays.stream(IndianState.values()).map(IndianState::code).distinct()).hasSize(36);
    }

    @ParameterizedTest
    @CsvSource({"29, KARNATAKA", "27, MAHARASHTRA", "07, DELHI", "36, TELANGANA", "37, ANDHRA_PRADESH", "38, LADAKH",
        "26, DADRA_NAGAR_HAVELI_DAMAN_DIU"})
    void findsAStateByItsGstCode(String code, IndianState state) {
        assertThat(IndianState.fromCode(code)).contains(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"25", "28", "00", "39", "97", "99", "KA", "29 ", ""})
    void refusesRetiredOrUnknownCodes(String code) {
        assertThat(IndianState.fromCode(code)).isEmpty();
    }
}
