package com.ecommerce.customer.web;

import com.ecommerce.customer.web.CustomerApi.StateList;
import com.ecommerce.customer.web.CustomerApi.StateResponse;
import com.ecommerce.platform.ApiController;
import com.ecommerce.shared.IndianState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

/** Reference data for address forms: states and union territories with their GST codes. */
@ApiController
@Tag(name = "Reference data")
class StatesController {

    private static final StateList STATES = new StateList(Arrays.stream(IndianState.values())
            .map(state -> new StateResponse(state.code(), state.displayName()))
            .toList());

    @Operation(summary = "States and union territories with their GST state codes")
    @GetMapping("/v1/states")
    ResponseEntity<StateList> listStates() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic()).body(STATES);
    }
}
