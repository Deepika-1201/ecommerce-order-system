package com.ecommerce.catalog.domain;

import com.ecommerce.platform.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/**
 * The rules for option dimensions and the values a variant names (LLD §3.5): up to 3 dimensions of up to 30 values;
 * dimensions are fixed once variants exist, values can only be added; each variant names every dimension once.
 */
final class ProductOptions {

    static final int MAX_DIMENSIONS = 3;
    static final int MAX_VALUES = 30;
    static final int MAX_VALUE_LENGTH = 40;

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,29}");

    private ProductOptions() {
    }

    /** Trims values and checks names, counts and uniqueness (values compare case-insensitively). */
    static List<ProductOption> normalize(List<ProductOption> options) {
        if (options == null) {
            return List.of();
        }
        if (options.size() > MAX_DIMENSIONS) {
            throw invalid("A product has at most " + MAX_DIMENSIONS + " option dimensions.");
        }
        Set<String> names = new HashSet<>();
        List<ProductOption> normalized = new ArrayList<>();
        for (ProductOption option : options) {
            if (option.name() == null || !NAME.matcher(option.name()).matches()) {
                throw invalid("Option names are lower case letters, digits and underscores, starting with a letter.");
            }
            if (!names.add(option.name())) {
                throw invalid("Option " + option.name() + " appears twice.");
            }
            if (option.values().isEmpty() || option.values().size() > MAX_VALUES) {
                throw invalid("Option " + option.name() + " needs 1 to " + MAX_VALUES + " values.");
            }
            Set<String> seen = new HashSet<>();
            List<String> values = new ArrayList<>();
            for (String value : option.values()) {
                String trimmed = value == null ? "" : value.strip();
                if (trimmed.isEmpty() || trimmed.length() > MAX_VALUE_LENGTH) {
                    throw invalid("Option values are 1 to " + MAX_VALUE_LENGTH + " characters.");
                }
                if (!seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                    throw invalid("Option " + option.name() + " has the value " + trimmed + " twice.");
                }
                values.add(trimmed);
            }
            normalized.add(new ProductOption(option.name(), values));
        }
        return List.copyOf(normalized);
    }

    /** Once a product has variants, its dimensions stay and their values can only be appended. */
    static void checkChangeAllowed(List<ProductOption> current, List<ProductOption> proposed) {
        boolean sameDimensions = current.size() == proposed.size();
        for (int i = 0; sameDimensions && i < current.size(); i++) {
            ProductOption before = current.get(i);
            ProductOption after = proposed.get(i);
            sameDimensions = before.name().equals(after.name())
                    && after.values().size() >= before.values().size()
                    && after.values().subList(0, before.values().size()).equals(before.values());
        }
        if (!sameDimensions) {
            throw new ApiException(HttpStatus.CONFLICT, "options_locked",
                    "The product has variants: its option dimensions are fixed, and values can only be added.");
        }
    }

    /** The variant's values in dimension order, spelled as the product spells them. */
    static Map<String, String> resolve(List<ProductOption> options, Map<String, String> requested) {
        Map<String, String> given = requested == null ? Map.of() : requested;
        Set<String> dimensions = options.stream().map(ProductOption::name).collect(Collectors.toSet());
        if (!given.keySet().equals(dimensions)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_option_values",
                    "A variant names exactly the product's options: " + dimensions + ".");
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (ProductOption option : options) {
            String value = given.get(option.name());
            String match = option.values().stream()
                    .filter(allowed -> value != null && allowed.equalsIgnoreCase(value.strip()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "invalid_option_values",
                            "Option " + option.name() + " has no value " + value + "."));
            resolved.put(option.name(), match);
        }
        return resolved;
    }

    /** {@code color=red;size=m}: the same combination always gives the same key, whatever the case or order. */
    static String combinationKey(Map<String, String> resolvedValues) {
        return resolvedValues.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(";"));
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_options", detail);
    }
}
