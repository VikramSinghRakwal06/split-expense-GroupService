package com.payflow.wallet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payflow.wallet.dto.response.WalletResponse;
import com.payflow.wallet.entity.WalletStatus;
import com.payflow.wallet.exception.ConcurrentUpdateException;
import com.payflow.wallet.exception.InsufficientFundsException;
import com.payflow.wallet.exception.ResourceNotFoundException;
import com.payflow.wallet.exception.WalletNotActiveException;
import com.payflow.wallet.security.AuthenticatedUser;
import com.payflow.wallet.security.JwtTokenValidator;
import com.payflow.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test: request mapping, payload validation, status codes, the error
 * contract, and — most importantly — that the {@code /me} endpoints take their identity
 * from the security context rather than the request.
 *
 * <p>The service is mocked and the security filters are switched off, so a failure here is
 * a controller failure. The real filter chain is exercised separately against a running
 * instance.
 */
@WebMvcTest(WalletController.class)
@AutoConfigureMockMvc(addFilters = false)
class WalletControllerTest {

    private static final String BASE = "/api/v1/wallets";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private WalletService walletService;

    // The slice instantiates Filter beans even with the chain disabled, so
    // JwtAuthenticationFilter's collaborator must be supplied.
    @MockitoBean private JwtTokenValidator jwtTokenValidator;

    private final UUID callerId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        AuthenticatedUser caller = new AuthenticatedUser(callerId, "ada@payflow.io", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private WalletResponse walletWith(String balance) {
        return new WalletResponse(
                walletId,
                callerId,
                new BigDecimal(balance),
                "INR",
                WalletStatus.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    @DisplayName("POST / creates a wallet for the caller and answers 201")
    void createWalletReturnsCreated() throws Exception {
        when(walletService.createWallet(callerId)).thenReturn(walletWith("0.0000"));

        mockMvc.perform(post(BASE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(walletId.toString()))
                .andExpect(jsonPath("$.balance").value(0.0000))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    @DisplayName("GET /me resolves the wallet from the token, not from any request input")
    void meUsesAuthenticatedPrincipal() throws Exception {
        when(walletService.getWalletForUser(callerId)).thenReturn(walletWith("500.2500"));

        // A user id is smuggled in as a query parameter; it must be ignored entirely.
        UUID someoneElse = UUID.randomUUID();
        mockMvc.perform(get(BASE + "/me").param("userId", someoneElse.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(callerId.toString()))
                .andExpect(jsonPath("$.balance").value(500.2500));

        verify(walletService).getWalletForUser(callerId);
        verify(walletService, never()).getWalletForUser(someoneElse);
    }

    @Test
    @DisplayName("POST /me/topup passes the amount through as an exact decimal")
    void topUpPreservesDecimalAmount() throws Exception {
        when(walletService.topUp(eq(callerId), any(), any())).thenReturn(walletWith("100.1000"));

        mockMvc.perform(post(BASE + "/me/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 0.1, "reference": "upi-1"}"""))
                .andExpect(status().isOk());

        // 0.1 has no exact binary representation. If the amount were ever routed through a
        // double it would arrive as 0.1000000000000000055511151231257827; asserting the
        // exact BigDecimal is what proves it was not.
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(walletService).topUp(eq(callerId), amount.capture(), eq("upi-1"));
        assertThat(amount.getValue()).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(amount.getValue()).isEqualTo(new BigDecimal("0.1"));
    }

    @Test
    @DisplayName("POST /me/topup rejects a non-positive amount with a field-level 400")
    void topUpRejectsNonPositiveAmount() throws Exception {
        mockMvc.perform(post(BASE + "/me/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 0, "reference": "x"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.amount").exists())
                .andExpect(jsonPath("$.path").value(BASE + "/me/topup"));

        verify(walletService, never()).topUp(any(), any(), any());
    }

    @Test
    @DisplayName("GET /me/ledger defaults to 20 entries sorted by createdAt descending")
    void ledgerAppliesDefaultPaging() throws Exception {
        when(walletService.getLedgerForUser(eq(callerId), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get(BASE + "/me/ledger")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(walletService).getLedgerForUser(eq(callerId), pageable.capture());

        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("POST /{walletId}/debit reports insufficient funds as 422")
    void debitInsufficientFundsIsUnprocessable() throws Exception {
        when(walletService.debit(eq(walletId), any(), any(), any()))
                .thenThrow(new InsufficientFundsException("Wallet has insufficient funds"));

        mockMvc.perform(post(BASE + "/" + walletId + "/debit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000.00, "reference": "pay-1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("POST /{walletId}/credit requires a reference")
    void internalCreditRequiresReference() throws Exception {
        mockMvc.perform(post(BASE + "/" + walletId + "/credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10.00}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.reference").exists());

        verify(walletService, never()).credit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a frozen wallet is a 409")
    void frozenWalletIsConflict() throws Exception {
        when(walletService.credit(eq(walletId), any(), any(), any()))
                .thenThrow(new WalletNotActiveException("Wallet is FROZEN"));

        mockMvc.perform(post(BASE + "/" + walletId + "/credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10.00, "reference": "r"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("exhausted contention is a 409 that says no money moved")
    void contentionIsConflict() throws Exception {
        when(walletService.credit(eq(walletId), any(), any(), any()))
                .thenThrow(new ConcurrentUpdateException(
                        "This wallet is being updated concurrently. No money moved; please retry.",
                        null));

        mockMvc.perform(post(BASE + "/" + walletId + "/credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10.00, "reference": "r"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("No money moved")));
    }

    @Test
    @DisplayName("an unknown wallet is a 404")
    void unknownWalletIsNotFound() throws Exception {
        when(walletService.getWalletForUser(callerId))
                .thenThrow(new ResourceNotFoundException("No wallet exists for this user."));

        mockMvc.perform(get(BASE + "/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("a malformed wallet id is a 400, not a 500")
    void malformedWalletIdIsBadRequest() throws Exception {
        mockMvc.perform(post(BASE + "/not-a-uuid/credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10.00, "reference": "r"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the ledger page is serialised as DTOs, never entities")
    void ledgerReturnsDtoShape() throws Exception {
        Page<com.payflow.wallet.dto.response.LedgerEntryResponse> page = new PageImpl<>(
                List.of(new com.payflow.wallet.dto.response.LedgerEntryResponse(
                        UUID.randomUUID(),
                        walletId,
                        new BigDecimal("25.5000"),
                        com.payflow.wallet.entity.LedgerEntryType.CREDIT,
                        new BigDecimal("125.5000"),
                        "ref-1",
                        "Top-up",
                        Instant.parse("2026-01-01T00:00:00Z"))));
        when(walletService.getLedgerForUser(eq(callerId), any())).thenReturn(page);

        mockMvc.perform(get(BASE + "/me/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].amount").value(25.5000))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(125.5000))
                // A leaked entity would carry the optimistic-locking counter.
                .andExpect(jsonPath("$.content[0].version").doesNotExist());
    }
}
