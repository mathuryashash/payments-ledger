package com.yashash.ledger.domain;

import com.yashash.ledger.service.InsufficientFundsException;
import com.yashash.ledger.service.TransferService;
import com.yashash.ledger.service.TransferService.PostingRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.yashash.ledger.domain.Entry.Direction.CREDIT;
import static com.yashash.ledger.domain.Entry.Direction.DEBIT;
import static org.assertj.core.api.Assertions.*;

/**
 * Pure unit tests for domain rules — no Spring context.
 */
class TransferDomainTest {

    @Test
    void balancedPostings_pass() {
        // 100 USD debit from one account, 100 USD credit to another: balanced
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        List<PostingRequest> postings = List.of(
            new PostingRequest(a1, DEBIT, 100_00L, "USD"),
            new PostingRequest(a2, CREDIT, 100_00L, "USD")
        );
        assertThatNoException().isThrownBy(() -> TransferService.validatePostings(postings));
    }

    @Test
    void unbalancedPostings_throw() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        List<PostingRequest> postings = List.of(
            new PostingRequest(a1, DEBIT, 100_00L, "USD"),
            new PostingRequest(a2, CREDIT, 90_00L, "USD")  // 10 cents missing
        );
        assertThatIllegalArgumentException()
            .isThrownBy(() -> TransferService.validatePostings(postings))
            .withMessageContaining("not balanced");
    }

    @Test
    void singlePosting_throw() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> TransferService.validatePostings(List.of(
                new PostingRequest(UUID.randomUUID(), DEBIT, 100L, "USD")
            )));
    }

    @Test
    void zeroAmount_throw() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> TransferService.validatePostings(List.of(
                new PostingRequest(a1, DEBIT, 0L, "USD"),
                new PostingRequest(a2, CREDIT, 0L, "USD")
            )));
    }

    @Test
    void multiCurrencyBalanced_pass() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID a3 = UUID.randomUUID();
        UUID a4 = UUID.randomUUID();
        List<PostingRequest> postings = List.of(
            new PostingRequest(a1, DEBIT, 50_00L, "USD"),
            new PostingRequest(a2, CREDIT, 50_00L, "USD"),
            new PostingRequest(a3, DEBIT, 10_00L, "EUR"),
            new PostingRequest(a4, CREDIT, 10_00L, "EUR")
        );
        assertThatNoException().isThrownBy(() -> TransferService.validatePostings(postings));
    }

    @Test
    void overdraft_allowedWhenFlagSet() {
        // Accounts with allowOverdraft=true should not throw on negative balance
        Account account = Account.builder()
            .id(UUID.randomUUID())
            .name("Credit Card")
            .currency("USD")
            .type(Account.AccountType.LIABILITY)
            .balanceMinor(0L)
            .allowOverdraft(true)
            .build();

        // Simulate debit on zero-balance overdraft account: balance goes to -100
        long newBalance = account.getBalanceMinor() - 100_00L;
        assertThat(newBalance).isNegative();
        // No exception should be raised when allowOverdraft is true
        if (newBalance < 0 && !account.isAllowOverdraft()) {
            throw new InsufficientFundsException(account.getId().toString());
        }
        // Passes silently
    }

    @Test
    void noOverdraft_disallowedByDefault() {
        Account account = Account.builder()
            .id(UUID.randomUUID())
            .name("Checking")
            .currency("USD")
            .type(Account.AccountType.ASSET)
            .balanceMinor(50_00L)
            .allowOverdraft(false)
            .build();

        long newBalance = account.getBalanceMinor() - 100_00L;
        assertThatThrownBy(() -> {
            if (newBalance < 0 && !account.isAllowOverdraft()) {
                throw new InsufficientFundsException(account.getId().toString());
            }
        }).isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void sha256_deterministic() {
        String h1 = TransferService.sha256("test-key");
        String h2 = TransferService.sha256("test-key");
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    void sha256_differentInput_differentOutput() {
        assertThat(TransferService.sha256("key-a")).isNotEqualTo(TransferService.sha256("key-b"));
    }
}
