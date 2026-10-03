package com.ecommerce.payments.web;

import com.ecommerce.payments.gateway.GatewayEvents;
import com.ecommerce.payments.gateway.GatewayEvents.MalformedEventException;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The gateway's webhooks (LLD §7.7). No end-user authentication: the signature authenticates the body, which is
 * stored in the inbox byte for byte and acknowledged only after that commit.
 */
@ApiController
@RequestMapping(GatewayWebhookController.PATH)
@Tag(name = "Webhooks", description = "Events from the Payment Gateway, signed with PG-Signature")
class GatewayWebhookController {

    static final String PATH = "/v1/webhooks/payment-gateway";
    static final int MAX_BODY_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(GatewayWebhookController.class);

    private final WebhookSignature signature;
    private final GatewayEvents events;

    GatewayWebhookController(WebhookSignature signature, GatewayEvents events) {
        this.signature = signature;
        this.events = events;
    }

    @Operation(summary = "Receive a payment or refund event from the gateway; a known event id changes nothing",
            requestBody = @RequestBody(required = true, description = "The gateway's event, as it signed it",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))))
    @ApiResponse(responseCode = "200", description = "Stored, or already known")
    @PostMapping
    ResponseEntity<Void> receiveGatewayEvent(
            @Parameter(required = true,
                    description = "t=<unix seconds>,v1=<hex HMAC-SHA256 of t.body>; one v1 per valid secret")
            @RequestHeader(name = "PG-Signature", required = false) String pgSignature,
            HttpServletRequest request) throws IOException {
        byte[] body = read(request);
        if (!signature.verify(pgSignature, body)) {
            log.warn("Refused a gateway webhook with an invalid or expired signature (event {})",
                    request.getHeader("PG-Event-Id"));
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

    /** At most 64 KB: a larger body is refused before the rest is read. */
    private static byte[] read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw tooLarge();
            }
            return body;
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large",
                "A webhook body may have at most " + MAX_BODY_BYTES + " bytes");
    }
}
