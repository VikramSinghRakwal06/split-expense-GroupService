package com.splitexpense.group.exception;

import com.splitexpense.group.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates every exception escaping a controller into the one {@link ErrorResponse}
 * shape.
 *
 * <p>Covers the controller side only. Authentication and authorisation failures raised
 * inside the security filter chain never reach a controller, and are handled by
 * {@code JwtAuthenticationEntryPoint} and {@code RestAccessDeniedHandler}, which produce
 * an identical body.
 *
 * <h2>Status codes for domain failures</h2>
 *
 * <p>The distinctions are deliberate and a client can act on them:
 * <ul>
 *   <li><strong>400</strong> — the request was wrong. Fix it and resend.</li>
 *   <li><strong>403</strong> — the caller is in the group but lacks the authority. No
 *       resend will help until somebody grants it.</li>
 *   <li><strong>404</strong> — no such group, <em>or</em> the caller is not a member of it.
 *       The two are deliberately indistinguishable: confirming that a group id exists is
 *       already more than an outsider should learn.</li>
 *   <li><strong>422</strong> — the request was right, but the group cannot satisfy it (a
 *       delta naming somebody who is not a member). Adding them makes it work.</li>
 *   <li><strong>409</strong> — the request was right, but the group's state or a concurrent
 *       writer got in the way. Resending unchanged may well work.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Bean validation failure on a request body: reports every rejected field at once
     * rather than making the client fix them one round trip at a time.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // merge keeps the first message when one field breaks several rules, so the
            // response stays one line per field.
            fieldErrors.merge(
                    error.getField(),
                    error.getDefaultMessage(),
                    (existing, ignored) -> existing);
        }

        return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, fieldErrors);
    }

    /**
     * Body that could not be parsed at all — malformed JSON, or a value of the wrong type.
     * The underlying message can quote the payload, so it is not passed through.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", request, null);
    }

    /** A path variable that could not be converted — most often a malformed group UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "'%s' is not a valid value for %s".formatted(ex.getValue(), ex.getName()),
                request,
                null);
    }

    /** An amount that is null, non-positive, or finer than the ledger can record. */
    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAmount(
            InvalidAmountException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    /** A delta that is not a debt at all — most often somebody owing themselves. */
    @ExceptionHandler(InvalidBalanceDeltaException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDelta(
            InvalidBalanceDeltaException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    /**
     * A delta named somebody who is not in the group.
     *
     * <p>422, not 400: nothing about the request is malformed, and adding that person to the
     * group makes the identical request valid. Logged at info because expense-service racing
     * a membership change is a normal outcome, not a fault.
     */
    @ExceptionHandler(NotAGroupMemberException.class)
    public ResponseEntity<ErrorResponse> handleNotAMember(
            NotAGroupMemberException ex, HttpServletRequest request) {
        log.info("Apply rejected — non-member named in a delta on {}", request.getRequestURI());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, null);
    }

    /**
     * An ordinary member attempted something only the group's owner may do.
     *
     * <p>403 rather than 404 here, unlike the non-member case: the caller has already proved
     * they belong to the group, so its existence is not a secret from them.
     */
    @ExceptionHandler(NotGroupOwnerException.class)
    public ResponseEntity<ErrorResponse> handleNotOwner(
            NotGroupOwnerException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request, null);
    }

    /** Balances asked to change in a group that has been archived. */
    @ExceptionHandler(GroupNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleGroupNotActive(
            GroupNotActiveException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    /** A member cannot leave while they still owe somebody, or are owed. */
    @ExceptionHandler(OutstandingBalanceException.class)
    public ResponseEntity<ErrorResponse> handleOutstandingBalance(
            OutstandingBalanceException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(
            DuplicateResourceException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    /**
     * Contention that survived every retry, reported by the {@code @Recover} method.
     *
     * <p>The message tells the caller no money moved, which is what makes an unconditional
     * client-side retry safe.
     */
    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(
            ConcurrentUpdateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    /**
     * A raw optimistic-lock failure that no retry loop absorbed.
     *
     * <p>Distinct from {@link ConcurrentUpdateException} and more noteworthy: the apply path
     * retries these, so one arriving here came from somewhere that does not — group creation,
     * for instance. Logged at warn for that reason. Still a 409, and still safe to retry,
     * because the transaction rolled back.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Unretried optimistic-lock failure on {} {}",
                request.getMethod(), request.getRequestURI());
        return build(
                HttpStatus.CONFLICT,
                "This resource was updated concurrently. No change was applied; please retry.",
                request,
                null);
    }

    /**
     * Authorisation failure raised by method security, after a controller was selected.
     * The filter-chain equivalent is {@code RestAccessDeniedHandler}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return build(
                HttpStatus.FORBIDDEN,
                "You do not have permission to access this resource.",
                request,
                null);
    }

    /**
     * A URL that maps to no handler. Declared explicitly because otherwise the catch-all
     * below would turn every mistyped path into a 500 and log a stack trace for it.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "No endpoint for this path", request, null);
    }

    /** A known path called with the wrong HTTP method. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return build(
                HttpStatus.METHOD_NOT_ALLOWED,
                "%s is not supported for this endpoint".formatted(ex.getMethod()),
                request,
                null);
    }

    /**
     * Last resort. The stack trace goes to the log, where operators can find it; the caller
     * gets a fixed sentence, because exception messages routinely contain SQL, table names,
     * parameter values and — in this service — user ids and balances.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}",
                request.getMethod(), request.getRequestURI(), ex);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.",
                request,
                null);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> validationErrors) {

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .validationErrors(validationErrors)
                .build();

        return ResponseEntity.status(status).body(body);
    }
}
