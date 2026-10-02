package com.ecommerce.shared;

import java.util.Optional;
import java.util.regex.Pattern;

/** Indian mobile numbers, stored as {@code +91} followed by 10 digits starting with 6 to 9. */
public final class MobileNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern MOBILE = Pattern.compile("[6-9][0-9]{9}");

    private MobileNumbers() {
    }

    /** Accepts {@code 98765 43210}, {@code 09876543210}, {@code +91-98765-43210} and the like. */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String digits = SEPARATORS.matcher(input.trim()).replaceAll("");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return MOBILE.matcher(digits).matches() ? Optional.of("+91" + digits) : Optional.empty();
    }
}
