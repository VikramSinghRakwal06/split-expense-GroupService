package com.payflow.wallet.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;

/**
 * The single error shape returned by every failing endpoint.
 *
 * <p>Produced from two places, which between them cover the whole request path:
 * {@code GlobalExceptionHandler} for anything a controller or service throws, and
 * {@code JwtAuthenticationEntryPoint} / {@code RestAccessDeniedHandler} for failures
 * raised inside the security filter chain, before a controller is ever selected.
 * Identical here so a client never has to parse two error formats.
 *
 * <p>Deliberately matches auth-service's {@code ErrorResponse} field for field: a PayFlow
 * client talks to several services and should not need per-service error handling.
 *
 * <p>{@code validationErrors} is null, and so omitted from the JSON, for everything
 * except a request body that failed bean validation.
 *
 * @param timestamp        when the failure was handled
 * @param status           HTTP status code
 * @param error            HTTP reason phrase, e.g. {@code Conflict}
 * @param message          human-readable summary, safe to show a caller
 * @param path             request URI that failed
 * @param validationErrors field name to rejection reason, only for 400s
 */
@Builder
@Schema(description = "Standard error payload")
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> validationErrors) {
}
