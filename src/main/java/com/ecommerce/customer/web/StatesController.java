package com.ecommerce.customer.web;

import com.ecommerce.customer.web.CustomerApi.StateList;
import com.ecommerce.customer.web.CustomerApi.StateResponse;
import com.ecommerce.platform.ApiController;
import com.ecommerce.shared.IndianState;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

/** Reference data for address forms: states and union territories with their GST codes. */
@ApiController
class StatesController {

    private static final StateList STATES = new StateList(Arrays.stream(IndianState.values())
            .map(state -> new StateResponse(state.code(), state.displayName()))
            .toList());

    @GetMapping("/v1/states")
    ResponseEntity<StateList> states() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic()).body(STATES);
    }
}
