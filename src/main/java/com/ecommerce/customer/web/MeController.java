package com.ecommerce.customer.web;

import com.ecommerce.customer.domain.CustomerService;
import com.ecommerce.customer.web.CustomerApi.AddressList;
import com.ecommerce.customer.web.CustomerApi.AddressRequest;
import com.ecommerce.customer.web.CustomerApi.AddressResponse;
import com.ecommerce.customer.web.CustomerApi.ProfileResponse;
import com.ecommerce.customer.web.CustomerApi.ProfileUpdate;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The calling customer's profile and addresses; nothing here takes a customer id (ADR-017). */
@ApiController
@RequestMapping("/v1/me")
@Tag(name = "Me", description = "The calling customer's profile and addresses. Another customer's address is 404.")
@SecurityRequirement(name = ApiController.BEARER_AUTH)
class MeController {

    private final CustomerService customers;

    MeController(CustomerService customers) {
        this.customers = customers;
    }

    @Operation(summary = "Read the profile; the first call creates it from the token")
    @GetMapping
    ProfileResponse getProfile(Caller caller) {
        return ProfileResponse.of(customers.profile(caller));
    }

    @Operation(summary = "Change the name or phone")
    @PatchMapping
    ProfileResponse updateProfile(Caller caller, @Valid @RequestBody ProfileUpdate update) {
        return ProfileResponse.of(customers.updateProfile(caller, update.name(), update.normalizedPhone()));
    }

    @Operation(summary = "List addresses, the default first")
    @GetMapping("/addresses")
    AddressList listAddresses(Caller caller) {
        return new AddressList(customers.addresses(caller).stream().map(AddressResponse::of).toList());
    }

    @Operation(summary = "Add an address (at most 10); the first becomes the default")
    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<AddressResponse> addAddress(Caller caller, @Valid @RequestBody AddressRequest request) {
        AddressResponse address = AddressResponse.of(customers.addAddress(caller, request.details()));
        return ResponseEntity.created(URI.create("/v1/me/addresses/" + address.id())).body(address);
    }

    @Operation(summary = "Read an address")
    @GetMapping("/addresses/{id}")
    AddressResponse getAddress(Caller caller, @PathVariable UUID id) {
        return AddressResponse.of(customers.address(caller, id));
    }

    @Operation(summary = "Replace an address; fields left out are removed")
    @PutMapping("/addresses/{id}")
    AddressResponse replaceAddress(Caller caller, @PathVariable UUID id,
            @Valid @RequestBody AddressRequest request) {
        return AddressResponse.of(customers.replaceAddress(caller, id, request.details()));
    }

    @Operation(summary = "Delete an address; deleting the default promotes the newest remaining one")
    @DeleteMapping("/addresses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> deleteAddress(Caller caller, @PathVariable UUID id) {
        customers.deleteAddress(caller, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Make an address the default")
    @PostMapping("/addresses/{id}/default")
    AddressResponse makeDefault(Caller caller, @PathVariable UUID id) {
        return AddressResponse.of(customers.makeDefault(caller, id));
    }
}
