package com.yashash.ledger.domain;

import com.yashash.ledger.service.TransferService;
import com.yashash.ledger.service.TransferService.PostingRequest;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Positive;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.yashash.ledger.domain.Entry.Direction.CREDIT;
import static com.yashash.ledger.domain.Entry.Direction.DEBIT;
import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for the posting-balance invariant.
 * Uses jqwik to generate random but balanced posting sets.
 */
class BalancingPropertyTest {

    /**
     * Any set of balanced postings must pass validation.
     */
    @Property(tries = 200)
    void balancedPostings_alwaysPass(
        @ForAll @IntRange(min = 1, max = 5) int pairs,
        @ForAll @Positive long baseAmount
    ) {
        List<PostingRequest> postings = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            UUID src = UUID.randomUUID();
            UUID dst = UUID.randomUUID();
            postings.add(new PostingRequest(src, DEBIT, baseAmount, "USD"));
            postings.add(new PostingRequest(dst, CREDIT, baseAmount, "USD"));
        }
        assertThatNoException().isThrownBy(() -> TransferService.validatePostings(postings));
    }

    /**
     * Any unbalanced posting set (net != 0) must fail validation.
     */
    @Property(tries = 200)
    void unbalancedPostings_alwaysFail(
        @ForAll @Positive long debitAmount,
        @ForAll @Positive long creditAmount
    ) {
        // Skip if accidentally balanced
        Assume.that(debitAmount != creditAmount);

        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        List<PostingRequest> postings = List.of(
            new PostingRequest(a1, DEBIT, debitAmount, "USD"),
            new PostingRequest(a2, CREDIT, creditAmount, "USD")
        );
        assertThatIllegalArgumentException()
            .isThrownBy(() -> TransferService.validatePostings(postings));
    }
}
