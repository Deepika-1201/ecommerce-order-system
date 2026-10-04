package com.ecommerce.fulfillment.web;

import com.ecommerce.fulfillment.carrier.CarrierEvents;
import com.ecommerce.fulfillment.carrier.CarrierEvents.MalformedEventException;
import com.ecommerce.fulfillment.domain.FulfillmentProperties;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.SignedWebhooks;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The carrier's tracking webhook (LLD §8.8). No end-user authentication: the signature authenticates the body, which is
 * stored in the inbox byte for byte and acknowledged only after that commit.
 */
@ApiController
@RequestMapping(CarrierWebhookController.PATH)
@Tag(name = "Carrier webhooks", description = "Tracking scans from the carrier, signed with Carrier-Signature")
class CarrierWebhookController {

    static final String PATH = "/v1/webhooks/carrier";

    private static final Logger log = LoggerFactory.getLogger(CarrierWebhookController.class);

    private final SignedWebhooks signature;
    private final CarrierEvents events;

    CarrierWebhookController(FulfillmentProperties properties, CarrierEvents events, Clock clock) {
        this.signature = new SignedWebhooks(properties.webhookSecrets(), properties.webhookTolerance(), clock);
        this.events = events;
    }

    @Operation(summary = "Receive a tracking scan from the carrier; a known event id changes nothing",
            requestBody = @RequestBody(required = true, description = "The carrier's event, as it signed it",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))))
    @ApiResponse(responseCode = "200", description = "Stored, or already known")
    @PostMapping
    ResponseEntity<Void> receiveCarrierEvent(
            @Parameter(required = true,
                    description = "t=<unix seconds>,v1=<hex HMAC-SHA256 of t.body>; one v1 per valid secret")
            @RequestHeader(name = "Carrier-Signature", required = false) String carrierSignature,
            HttpServletRequest request) throws IOException {
        byte[] body = SignedWebhooks.read(request);
        if (!signature.verify(carrierSignature, body)) {
            log.warn("Refused a carrier webhook with an invalid or expired signature");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_signature",
                    "The webhook is not signed with a current secret, or was signed too long ago");
        }
        try {
            events.receive(new String(body, StandardCharsets.UTF_8));
        } catch (MalformedEventException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "malformed_request", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
