package com.ecommerce.customer.web;

import com.ecommerce.customer.domain.CustomerService;
import com.ecommerce.customer.web.CustomerApi.AddressList;
import com.ecommerce.customer.web.CustomerApi.AddressRequest;
import com.ecommerce.customer.web.CustomerApi.AddressResponse;
import com.ecommerce.customer.web.CustomerApi.ProfileResponse;
import com.ecommerce.customer.web.CustomerApi.ProfileUpdate;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.Caller;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** The calling customer's profile and addresses; nothing here takes a customer id (ADR-017). */
@ApiController
@RequestMapping("/v1/me")
class MeController {

    private final CustomerService customers;

    MeController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    ProfileResponse profile(Caller caller) {
        return ProfileResponse.of(customers.profile(caller));
    }

    @PatchMapping
    ProfileResponse updateProfile(Caller caller, @Valid @RequestBody ProfileUpdate update) {
        return ProfileResponse.of(customers.updateProfile(caller, update.name(), update.normalizedPhone()));
    }

    @GetMapping("/addresses")
    AddressList addresses(Caller caller) {
        return new AddressList(customers.addresses(caller).stream().map(AddressResponse::of).toList());
    }

    @PostMapping("/addresses")
    ResponseEntity<AddressResponse> addAddress(Caller caller, @Valid @RequestBody AddressRequest request) {
        AddressResponse address = AddressResponse.of(customers.addAddress(caller, request.details()));
        return ResponseEntity.created(URI.create("/v1/me/addresses/" + address.id())).body(address);
    }

    @GetMapping("/addresses/{id}")
    AddressResponse address(Caller caller, @PathVariable UUID id) {
        return AddressResponse.of(customers.address(caller, id));
    }

    @PutMapping("/addresses/{id}")
    AddressResponse replaceAddress(Caller caller, @PathVariable UUID id,
            @Valid @RequestBody AddressRequest request) {
        return AddressResponse.of(customers.replaceAddress(caller, id, request.details()));
    }

    @DeleteMapping("/addresses/{id}")
    ResponseEntity<Void> deleteAddress(Caller caller, @PathVariable UUID id) {
        customers.deleteAddress(caller, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/addresses/{id}/default")
    AddressResponse makeDefault(Caller caller, @PathVariable UUID id) {
        return AddressResponse.of(customers.makeDefault(caller, id));
    }
}
