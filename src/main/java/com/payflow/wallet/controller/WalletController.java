package com.payflow.wallet.controller;

import com.payflow.wallet.dto.request.MoneyMovementRequest;
import com.payflow.wallet.dto.request.TopUpRequest;
import com.payflow.wallet.dto.response.ErrorResponse;
import com.payflow.wallet.dto.response.LedgerEntryResponse;
import com.payflow.wallet.dto.response.WalletResponse;
import com.payflow.wallet.security.AuthenticatedUser;
import com.payflow.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for wallets.
 *
 * <p>Holds no business logic: each method resolves the caller, delegates to
 * {@link WalletService}, and returns a DTO. Entities are never returned — see
 * {@code WalletMapper} for why.
 *
 * <h2>Two families of endpoint</h2>
 *
 * <p>The {@code /me} endpoints belong to the authenticated person, and take the account
 * identifier <strong>only</strong> from the verified JWT. None of them accepts a user id or
 * wallet id in the path, the query string, or the body — there is deliberately no parameter
 * through which one user could name another user's wallet, so the entire class of
 * broken-object-level-authorisation bug is absent by construction rather than by check.
 *
 * <p>The {@code /{walletId}} endpoints are internal, called by payment-service, and do name
 * an arbitrary wallet. They are restricted to {@code ROLE_ADMIN} in
 * {@code SecurityConfig}, because a caller who may credit any wallet by id could otherwise
 * credit their own without limit.
 */
@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "Wallets", description = "Wallet balances, top-ups and statements")
@SecurityRequirement(name = "bearerAuth")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * Opens a wallet for the authenticated user.
     *
     * @param caller the verified caller
     * @return 201 with the new wallet
     */
    @PostMapping
    @Operation(summary = "Create the authenticated user's wallet")
    @ApiResponse(responseCode = "201", description = "Wallet created")
    @ApiResponse(responseCode = "409", description = "This user already has a wallet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<WalletResponse> createWallet(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        WalletResponse wallet = walletService.createWallet(caller.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(wallet);
    }

    /**
     * Returns the authenticated user's wallet and balance.
     *
     * @param caller the verified caller
     * @return 200 with the wallet
     */
    @GetMapping("/me")
    @Operation(summary = "Read the authenticated user's wallet and balance")
    @ApiResponse(responseCode = "200", description = "The caller's wallet")
    @ApiResponse(responseCode = "404", description = "The caller has no wallet yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<WalletResponse> myWallet(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(walletService.getWalletForUser(caller.id()));
    }

    /**
     * Adds money to the authenticated user's own wallet.
     *
     * @param caller  the verified caller
     * @param request amount and optional reference
     * @return 200 with the updated wallet
     */
    @PostMapping("/me/topup")
    @Operation(summary = "Add money to the authenticated user's wallet")
    @ApiResponse(responseCode = "200", description = "Wallet credited")
    @ApiResponse(responseCode = "400", description = "Amount failed validation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Wallet is not active, or too contended",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<WalletResponse> topUp(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody TopUpRequest request) {

        return ResponseEntity.ok(
                walletService.topUp(caller.id(), request.amount(), request.reference()));
    }

    /**
     * Returns one page of the authenticated user's statement, newest entry first.
     *
     * <p>{@code @PageableDefault} supplies the contract's defaults when the client sends no
     * paging parameters. The sort is stated here rather than left to the client so the
     * query keeps matching {@code idx_ledger_entries_wallet_created}; a client may still
     * override it with {@code ?sort=}.
     *
     * @param caller   the verified caller
     * @param pageable paging and sort, defaulted to 20 per page by {@code createdAt} desc
     * @return 200 with a page of ledger entries
     */
    @GetMapping("/me/ledger")
    @Operation(summary = "Page through the authenticated user's statement")
    @ApiResponse(responseCode = "200", description = "A page of ledger entries")
    @ApiResponse(responseCode = "404", description = "The caller has no wallet yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Page<LedgerEntryResponse>> myLedger(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ResponseEntity.ok(walletService.getLedgerForUser(caller.id(), pageable));
    }

    /**
     * INTERNAL. Credits an arbitrary wallet on payment-service's instruction.
     *
     * @param walletId wallet to credit
     * @param request  amount, mandatory reference and optional description
     * @return 200 with the updated wallet
     */
    @PostMapping("/{walletId}/credit")
    @Operation(summary = "INTERNAL: credit a wallet",
            description = "Called by payment-service. Requires ROLE_ADMIN.")
    @ApiResponse(responseCode = "200", description = "Wallet credited")
    @ApiResponse(responseCode = "403", description = "Caller is not an internal service",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such wallet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<WalletResponse> credit(
            @PathVariable UUID walletId,
            @Valid @RequestBody MoneyMovementRequest request) {

        return ResponseEntity.ok(walletService.credit(
                walletId, request.amount(), request.reference(), request.description()));
    }

    /**
     * INTERNAL. Debits an arbitrary wallet on payment-service's instruction.
     *
     * @param walletId wallet to debit
     * @param request  amount, mandatory reference and optional description
     * @return 200 with the updated wallet
     */
    @PostMapping("/{walletId}/debit")
    @Operation(summary = "INTERNAL: debit a wallet",
            description = "Called by payment-service. Requires ROLE_ADMIN.")
    @ApiResponse(responseCode = "200", description = "Wallet debited")
    @ApiResponse(responseCode = "403", description = "Caller is not an internal service",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "Insufficient funds",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<WalletResponse> debit(
            @PathVariable UUID walletId,
            @Valid @RequestBody MoneyMovementRequest request) {

        return ResponseEntity.ok(walletService.debit(
                walletId, request.amount(), request.reference(), request.description()));
    }
}
