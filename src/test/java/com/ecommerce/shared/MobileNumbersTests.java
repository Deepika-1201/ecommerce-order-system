package com.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MobileNumbersTests {

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "98765 43210", "+919876543210", "+91 98765 43210", "+91-98765-43210",
        "919876543210", "09876543210", " 9876543210 "})
    void normalizesCommonNotations(String input) {
        assertThat(MobileNumbers.normalize(input)).contains("+919876543210");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5876543210", "987654321", "98765432101", "+449876543210", "98765o4321", "", "+91"})
    void refusesAnythingElse(String input) {
        assertThat(MobileNumbers.normalize(input)).isEmpty();
    }
}
