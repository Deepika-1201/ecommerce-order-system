package com.ecommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * How MVC errors are rendered, through a fixture controller only. Security is left out of this slice; its errors are
 * covered by the token tests.
 */
@WebMvcTest(controllers = ProblemDetailsTests.ProblemFixtureController.class, properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration,"
        + "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web."
        + "OAuth2ResourceServerWebSecurityAutoConfiguration")
@Import(ProblemDetailsTests.ProblemFixtureController.class)
class ProblemDetailsTests {

    private static final String GENERATED_ID = "req_[0-9a-f]{32}";

    @Autowired
    private MockMvc mvc;

    @Test
    void unknownRouteIsANotFoundProblemCarryingTheGeneratedRequestId() throws Exception {
        MvcResult result = mvc.perform(get("/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.request_id").value(matchesPattern(GENERATED_ID)))
                .andReturn();

        String headerId = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(result.getResponse().getContentAsString()).contains("\"request_id\":\"" + headerId + "\"");
    }

    @Test
    void keepsAWellFormedClientRequestId() throws Exception {
        mvc.perform(get("/test/problems/ok").header(RequestIdFilter.HEADER, "checkout-42.retry_1"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, "checkout-42.retry_1"));
    }

    @Test
    void replacesAMalformedClientRequestId() throws Exception {
        mvc.perform(get("/test/problems/ok").header(RequestIdFilter.HEADER, "not ok; drop table"))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(GENERATED_ID)));
    }

    @Test
    void validationFailureListsEveryFieldInSnakeCase() throws Exception {
        mvc.perform(post("/test/problems/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin_code\": \"\", \"quantity\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("pin_code", "quantity")));
    }

    @Test
    void unreadableBodyIsAMalformedRequest() throws Exception {
        mvc.perform(post("/test/problems/validated").contentType(MediaType.APPLICATION_JSON).content("{\"pin_code\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"));
    }

    @Test
    void wrongContentTypeIsUnsupported() throws Exception {
        mvc.perform(post("/test/problems/validated").contentType(MediaType.TEXT_PLAIN).content("pin_code=1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_media_type"));
    }

    @Test
    void wrongMethodIsNotAllowed() throws Exception {
        mvc.perform(delete("/test/problems/ok"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method_not_allowed"));
    }

    @Test
    void moduleErrorKeepsItsStatusCodeAndDetail() throws Exception {
        mvc.perform(get("/test/problems/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order_invalid_state"))
                .andExpect(jsonPath("$.detail").value("The order cannot be cancelled after handover."));
    }

    @Test
    void retryableErrorTellsTheClientWhenToRetry() throws Exception {
        mvc.perform(get("/test/problems/busy"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                .andExpect(jsonPath("$.code").value("still_busy"));
    }

    @Test
    void unexpectedErrorHidesItsMessage() throws Exception {
        MvcResult result = mvc.perform(get("/test/problems/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("hunter2");
    }

    @TestComponent
    @ApiController
    static class ProblemFixtureController {

        @GetMapping("/test/problems/ok")
        Map<String, Object> ok() {
            return Map.of("ok", true);
        }

        @PostMapping("/test/problems/validated")
        Map<String, Object> validated(@Valid @RequestBody ValidatedRequest request) {
            return Map.of("pin_code", request.pinCode());
        }

        @GetMapping("/test/problems/conflict")
        Map<String, Object> conflict() {
            throw new ApiException(HttpStatus.CONFLICT, "order_invalid_state",
                    "The order cannot be cancelled after handover.");
        }

        @GetMapping("/test/problems/busy")
        Map<String, Object> busy() {
            throw new ApiException(HttpStatus.CONFLICT, "still_busy", "Try again shortly.", Duration.ofMillis(1500));
        }

        @GetMapping("/test/problems/boom")
        Map<String, Object> boom() {
            throw new IllegalStateException("database password is hunter2");
        }
    }

    record ValidatedRequest(@NotBlank String pinCode, @Min(1) int quantity) {
    }
}
