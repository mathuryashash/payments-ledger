package com.yashash.ledger.api.dto;

import com.yashash.ledger.domain.Entry;
import com.yashash.ledger.domain.Transfer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public class TransferDtos {

    public record PostingRequest(
        @NotNull String accountId,
        @NotNull Entry.Direction direction,
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency
    ) {}

    public record CreateTransferRequest(
        @Size(max = 255) String reference,
        @NotEmpty @Valid List<PostingRequest> postings
    ) {}

    public record TransferResponse(
        String id,
        String reference,
        String status,
        String reversalOfId,
        String createdAt
    ) {
        public static TransferResponse from(Transfer t) {
            // Strip internal hash prefix from reference
            String ref = t.getReference();
            if (ref != null && ref.startsWith("hash:")) {
                int pipe = ref.indexOf('|');
                ref = pipe >= 0 ? ref.substring(pipe + 1) : "";
            }
            return new TransferResponse(
                t.getId().toString(),
                ref,
                t.getStatus().name(),
                t.getReversalOfId() != null ? t.getReversalOfId().toString() : null,
                t.getCreatedAt().toString()
            );
        }
    }

    public record EntryResponse(
        String id,
        String transferId,
        String accountId,
        String currency,
        long amountMinor,
        String direction,
        String createdAt
    ) {
        public static EntryResponse from(Entry e) {
            return new EntryResponse(
                e.getId().toString(),
                e.getTransferId().toString(),
                e.getAccountId().toString(),
                e.getCurrency(),
                e.getAmountMinor(),
                e.getDirection().name(),
                e.getCreatedAt().toString()
            );
        }
    }

    public record PagedEntriesResponse(List<EntryResponse> entries, String nextCursor) {}

    public record ReverseTransferRequest(@Size(max = 255) String reference) {}
}
