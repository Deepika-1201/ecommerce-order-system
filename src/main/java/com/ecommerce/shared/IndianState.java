package com.ecommerce.shared;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The 36 states and union territories, by GST state code: GST place of supply is what a delivery state is for
 * (LLD §3.4). Codes 25 (Daman and Diu, merged into 26 in 2020) and 28 (Andhra Pradesh before 2014) are retired.
 */
public enum IndianState {
    JAMMU_AND_KASHMIR("01", "Jammu and Kashmir"),
    HIMACHAL_PRADESH("02", "Himachal Pradesh"),
    PUNJAB("03", "Punjab"),
    CHANDIGARH("04", "Chandigarh"),
    UTTARAKHAND("05", "Uttarakhand"),
    HARYANA("06", "Haryana"),
    DELHI("07", "Delhi"),
    RAJASTHAN("08", "Rajasthan"),
    UTTAR_PRADESH("09", "Uttar Pradesh"),
    BIHAR("10", "Bihar"),
    SIKKIM("11", "Sikkim"),
    ARUNACHAL_PRADESH("12", "Arunachal Pradesh"),
    NAGALAND("13", "Nagaland"),
    MANIPUR("14", "Manipur"),
    MIZORAM("15", "Mizoram"),
    TRIPURA("16", "Tripura"),
    MEGHALAYA("17", "Meghalaya"),
    ASSAM("18", "Assam"),
    WEST_BENGAL("19", "West Bengal"),
    JHARKHAND("20", "Jharkhand"),
    ODISHA("21", "Odisha"),
    CHHATTISGARH("22", "Chhattisgarh"),
    MADHYA_PRADESH("23", "Madhya Pradesh"),
    GUJARAT("24", "Gujarat"),
    DADRA_NAGAR_HAVELI_DAMAN_DIU("26", "Dadra and Nagar Haveli and Daman and Diu"),
    MAHARASHTRA("27", "Maharashtra"),
    KARNATAKA("29", "Karnataka"),
    GOA("30", "Goa"),
    LAKSHADWEEP("31", "Lakshadweep"),
    KERALA("32", "Kerala"),
    TAMIL_NADU("33", "Tamil Nadu"),
    PUDUCHERRY("34", "Puducherry"),
    ANDAMAN_AND_NICOBAR_ISLANDS("35", "Andaman and Nicobar Islands"),
    TELANGANA("36", "Telangana"),
    ANDHRA_PRADESH("37", "Andhra Pradesh"),
    LADAKH("38", "Ladakh");

    private static final Map<String, IndianState> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(IndianState::code, Function.identity()));

    private final String code;
    private final String displayName;

    IndianState(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<IndianState> fromCode(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code));
    }
}
