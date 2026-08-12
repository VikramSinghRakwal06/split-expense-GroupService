package com.payflow.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.payflow.wallet.entity.LedgerEntry;
import com.payflow.wallet.entity.LedgerEntryType;
import com.payflow.wallet.entity.Wallet;
import com.payflow.wallet.entity.WalletStatus;
import com.payflow.wallet.exception.DuplicateResourceException;
import com.payflow.wallet.exception.InsufficientFundsException;
import com.payflow.wallet.exception.InvalidAmountException;
import com.payflow.wallet.exception.ResourceNotFoundException;
import com.payflow.wallet.exception.WalletNotActiveException;
import com.payflow.wallet.repository.LedgerEntryRepository;
import com.payflow.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for the money rules, with the repositories mocked.
 *
 * <p>Every {@code BigDecimal} here is built from a String literal, and every comparison
 * uses {@code compareTo}. {@code new BigDecimal(0.1)} would not be 0.1, and
 * {@code new BigDecimal("100.00").equals(new BigDecimal("100.0000"))} is false — a test
 * that got either wrong would pass against broken code or fail against correct code.
 */
@ExtendWith(MockitoExtension.class)
class WalletTransactionServiceTest {

    private static final UUID WALLET_ID = UUID.randomUUID();

    @Mock private WalletRepository walletRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;

    @InjectMocks private WalletTransactionService service;

    private static Wallet wallet(String balance, WalletStatus status) {
        return Wallet.builder()
                .id(WALLET_ID)
                .userId(UUID.randomUUID())
                .balance(new BigDecimal(balance))
                .currency("INR")
                .status(status)
                .build();
    }

    private static Wallet activeWallet(String balance) {
        return wallet(balance, WalletStatus.ACTIVE);
    }

    private LedgerEntry captureSavedEntry() {
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("credit")
    class Credit {

        @Test
        @DisplayName("adds to the balance and records a matching ledger entry")
        void creditIncreasesBalanceAndWritesLedgerEntry() {
            Wallet target = activeWallet("100.0000");
            when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(target));

            Wallet result = service.credit(
                    WALLET_ID, new BigDecimal("25.5000"), "ref-1", "Top-up");

            assertThat(result.getBalance())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(new BigDecimal("125.50"));

            LedgerEntry entry = captureSavedEntry();
            assertThat(entry.getWalletId()).isEqualTo(WALLET_ID);
            assertThat(entry.getType()).isEqualTo(LedgerEntryType.CREDIT);
            assertThat(entry.getAmount())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(new BigDecimal("25.50"));
            assertThat(entry.getBalanceAfter())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(new BigDecimal("125.50"));
            assertThat(entry.getReferenceId()).isEqualTo("ref-1");
        }

        @Test
        @DisplayName("relies on dirty checking, never calling save on the wallet")
        void creditDoesNotExplicitlySaveTheWallet() {
            when(walletRepository.findById(WALLET_ID))
                    .thenReturn(Optional.of(activeWallet("10.0000")));

            service.credit(WALLET_ID, new BigDecimal("1.0000"), "ref", null);

            // The whole optimistic-locking design depends on Hibernate emitting the
            // versioned UPDATE at flush. An explicit save() here would be redundant, and
            // saveAndFlush() would move where the conflict is detected.
            verify(walletRepository, never()).save(any());
            verify(walletRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("is rejected when the wallet does not exist")
        void creditUnknownWallet() {
            when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.credit(WALLET_ID, new BigDecimal("5.00"), "ref", null))
                    .isInstanceOf(ResourceNotFoundException.class);

            verifyNoInteractions(ledgerEntryRepository);
        }
    }

    @Nested
    @DisplayName("debit")
    class Debit {

        @Test
        @DisplayName("subtracts from the balance and records a matching ledger entry")
        void debitDecreasesBalanceAndWritesLedgerEntry() {
            when(walletRepository.findById(WALLET_ID))
                    .thenReturn(Optional.of(activeWallet("100.0000")));

            Wallet result = service.debit(
                    WALLET_ID, new BigDecimal("30.2500"), "pay-9", "Order");

            assertThat(result.getBalance())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(new BigDecimal("69.75"));

            LedgerEntry entry = captureSavedEntry();
            assertThat(entry.getType()).isEqualTo(LedgerEntryType.DEBIT);
            assertThat(entry.getBalanceAfter())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(new BigDecimal("69.75"));
        }

        @Test
        @DisplayName("is refused when the balance is smaller than the amount")
        void debitBeyondBalanceIsRefused() {
            when(walletRepository.findById(WALLET_ID))
                    .thenReturn(Optional.of(activeWallet("10.0000")));

            assertThatThrownBy(() ->
                    service.debit(WALLET_ID, new BigDecimal("10.0001"), "pay-1", null))
                    .isInstanceOf(InsufficientFundsException.class);

            // No entry may be written for a movement that did not happen.
            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        @DisplayName("may take the balance to exactly zero")
        void debitOfWholeBalanceIsAllowed() {
            when(walletRepository.findById(WALLET_ID))
                    .thenReturn(Optional.of(activeWallet("42.5000")));

            Wallet result = service.debit(WALLET_ID, new BigDecimal("42.50"), "pay-2", null);

            assertThat(result.getBalance())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("compares balance to amount by value, not by scale")
        void debitComparesByValueNotScale() {
            // 100.00 and 100.0000 are equal in value and unequal under equals(). A funds
            // check written with equals() would wrongly refuse this debit.
            when(walletRepository.findById(WALLET_ID))
                    .thenReturn(Optional.of(activeWallet("100.00")));

            Wallet result = service.debit(WALLET_ID, new BigDecimal("100.0000"), "pay-3", null);

            assertThat(result.getBalance())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("wallet status")
    class Status {

        @ParameterizedTest
        @EnumSource(value = WalletStatus.class, names = {"FROZEN", "CLOSED"})
        @DisplayName("blocks credits on a wallet that is not ACTIVE")
        void creditRequiresActiveWallet(WalletStatus status) {
            when(walletRepository.findById(WALLET_ID))
                    .thenReturn(Optional.of(wallet("100.0000", status)));

            assertThatThrownBy(() ->
                    service.credit(WALLET_ID, new BigDecimal("1.00"), "ref", null))
                    .isInstanceOf(WalletNotActiveException.class)
                    .hasMessageContaining(status.name());

            verifyNoInteractions(ledgerEntryRepository);
        }

        @ParameterizedTest
        @EnumSource(value = WalletStatus.class, names = {"FROZEN", "CLOSED"})
        @DisplayName("blocks debits on a wallet that is not ACTIVE")
        void debitRequiresActiveWallet(WalletStatus status) {
            when(walletRepository.findById(WALLET_ID))
                    .thenReturn(Optional.of(wallet("100.0000", status)));

            assertThatThrownBy(() ->
                    service.debit(WALLET_ID, new BigDecimal("1.00"), "ref", null))
                    .isInstanceOf(WalletNotActiveException.class);

            verifyNoInteractions(ledgerEntryRepository);
        }
    }

    @Nested
    @DisplayName("amount validation")
    class Amounts {

        @ParameterizedTest
        @ValueSource(strings = {"-1", "-0.0001", "0", "0.00", "0.0000"})
        @DisplayName("refuses any amount that is not strictly positive")
        void refusesNonPositiveAmounts(String amount) {
            assertThatThrownBy(() ->
                    service.credit(WALLET_ID, new BigDecimal(amount), "ref", null))
                    .isInstanceOf(InvalidAmountException.class);

            // Rejected before the wallet is even read.
            verifyNoInteractions(walletRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("refuses a null amount")
        void refusesNullAmount() {
            assertThatThrownBy(() -> service.debit(WALLET_ID, null, "ref", null))
                    .isInstanceOf(InvalidAmountException.class);

            verifyNoInteractions(walletRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("refuses an amount finer than the ledger can store")
        void refusesExcessivePrecision() {
            // NUMERIC(19,4) would round this silently; losing fractions of a currency unit
            // is how money quietly goes missing.
            assertThatThrownBy(() ->
                    service.credit(WALLET_ID, new BigDecimal("1.23456"), "ref", null))
                    .isInstanceOf(InvalidAmountException.class);

            verifyNoInteractions(walletRepository, ledgerEntryRepository);
        }
    }

    @Nested
    @DisplayName("createWallet")
    class Create {

        @Test
        @DisplayName("rejects a second wallet for the same user")
        void rejectsDuplicateWallet() {
            UUID userId = UUID.randomUUID();
            when(walletRepository.existsByUserId(userId)).thenReturn(true);

            assertThatThrownBy(() -> service.createWallet(userId))
                    .isInstanceOf(DuplicateResourceException.class);

            verify(walletRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("turns a lost creation race into a conflict, not a 500")
        void translatesUniqueConstraintViolation() {
            UUID userId = UUID.randomUUID();
            when(walletRepository.existsByUserId(userId)).thenReturn(false);
            when(walletRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("uq_wallets_user_id"));

            // Two simultaneous first-time requests both pass the exists() check; the unique
            // constraint is what actually stops the second, and the caller must still see
            // a 409 rather than an unhandled database error.
            assertThatThrownBy(() -> service.createWallet(userId))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("opens a wallet at a zero balance")
        void createsWalletAtZero() {
            UUID userId = UUID.randomUUID();
            when(walletRepository.existsByUserId(userId)).thenReturn(false);
            when(walletRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

            Wallet created = service.createWallet(userId);

            assertThat(created.getUserId()).isEqualTo(userId);
            assertThat(created.getStatus()).isEqualTo(WalletStatus.ACTIVE);
            assertThat(created.getCurrency()).isEqualTo("INR");
            assertThat(created.getBalance())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(BigDecimal.ZERO);
        }
    }
}
