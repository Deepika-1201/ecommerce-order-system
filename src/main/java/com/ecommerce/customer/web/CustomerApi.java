package com.ecommerce.customer.web;

import com.ecommerce.customer.domain.Address;
import com.ecommerce.customer.domain.AddressDetails;
import com.ecommerce.customer.domain.Customer;
import com.ecommerce.shared.GstStateCode;
import com.ecommerce.shared.IndianState;
import com.ecommerce.shared.MobileNumbers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the customer API (LLD §3.4). */
final class CustomerApi {

    private CustomerApi() {
    }

    record ProfileResponse(String email, String name, String phone) {

        static ProfileResponse of(Customer customer) {
            return new ProfileResponse(customer.email(), customer.name(), customer.phone());
        }
    }

    /** Fields left out are unchanged. */
    record ProfileUpdate(@Size(min = 1, max = 100) String name, @IndianMobile String phone) {

        String normalizedPhone() {
            return phone == null ? null : MobileNumbers.normalize(phone).orElseThrow();
        }
    }

    record AddressRequest(
            @NotBlank @Size(max = 100) String recipientName,
            @NotBlank @IndianMobile String phone,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @Size(max = 100) String landmark,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @GstStateCode String stateCode,
            @NotBlank @Pattern(regexp = "[1-9][0-9]{5}", message = "must be six digits, not starting with 0")
            String pinCode) {

        AddressDetails details() {
            return new AddressDetails(recipientName.strip(), MobileNumbers.normalize(phone).orElseThrow(),
                    line1.strip(), blankToNull(line2), blankToNull(landmark), city.strip(),
                    IndianState.fromCode(stateCode).orElseThrow(), pinCode);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    record AddressResponse(
            UUID id,
            String recipientName,
            String phone,
            String line1,
            String line2,
            String landmark,
            String city,
            String stateCode,
            String stateName,
            String pinCode,
            boolean isDefault) {

        static AddressResponse of(Address address) {
            return new AddressResponse(address.id(), address.recipientName(), address.phone(), address.line1(),
                    address.line2(), address.landmark(), address.city(), address.state().code(),
                    address.state().displayName(), address.pinCode(), address.isDefault());
        }
    }

    record AddressList(List<AddressResponse> items) {
    }

    record StateResponse(String code, String name) {
    }

    record StateList(List<StateResponse> items) {
    }
}
