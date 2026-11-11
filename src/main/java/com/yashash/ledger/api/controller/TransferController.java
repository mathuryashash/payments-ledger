package com.yashash.ledger.api.controller;

import com.yashash.ledger.api.dto.TransferDtos.*;
import com.yashash.ledger.domain.Entry;
import com.yashash.ledger.domain.Entry.Direction;
import com.yashash.ledger.domain.Transfer;
import com.yashash.ledger.repository.EntryRepository;
import com.yashash.ledger.service.TransferService;
import com.yashash.ledger.service.TransferService.PostingRequest;
import com.yashash.ledger.service.TransferService.TransferRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "Post and reverse transfers")
public class TransferController {

    private final TransferService transferService;
    private final EntryRepository entryRepo;

    private static final int PAGE_SIZE = 50;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Post a balanced transfer. Idempotency-Key header is required.")
    public TransferResponse postTransfer(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody CreateTransferRequest req,
        HttpServletRequest httpReq
    ) {
        // Hash the full request body for conflict detection
        String bodyHash = TransferService.sha256(serializeForHash(req));

        List<PostingRequest> postings = req.postings().stream()
            .map(p -> new PostingRequest(
                UUID.fromString(p.accountId()),
                p.direction(),
                p.amountMinor(),
                p.currency()))
            .toList();

        TransferRequest transferReq = new TransferRequest(idempotencyKey, bodyHash, req.reference(), postings);
        Transfer transfer = transferService.postTransfer(transferReq);
        return TransferResponse.from(transfer);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a transfer")
    public TransferResponse getTransfer(@PathVariable UUID id) {
        Transfer t = transferService.getTransferById(id);
        return TransferResponse.from(t);
    }

    @PostMapping("/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reverse a transfer with a compensating entry")
    public TransferResponse reverseTransfer(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        Transfer reversal = transferService.reverseTransfer(id, idempotencyKey);
        return TransferResponse.from(reversal);
    }

    @GetMapping("/accounts/{accountId}/entries")
    @Operation(summary = "List entries for an account with keyset pagination. Pass cursor from previous response.")
    public PagedEntriesResponse listEntries(
        @PathVariable UUID accountId,
        @RequestParam(required = false) String cursor
    ) {
        List<Entry> entries;
        if (cursor == null) {
            entries = entryRepo.findByAccountIdFirstPage(accountId, PageRequest.of(0, PAGE_SIZE + 1));
        } else {
            CursorData cd = decodeCursor(cursor);
            entries = entryRepo.findByAccountIdAfterCursor(accountId, cd.ts(), cd.id(), PageRequest.of(0, PAGE_SIZE + 1));
        }

        boolean hasMore = entries.size() > PAGE_SIZE;
        List<Entry> page = hasMore ? entries.subList(0, PAGE_SIZE) : entries;
        String nextCursor = hasMore ? encodeCursor(page.getLast()) : null;

        return new PagedEntriesResponse(
            page.stream().map(EntryResponse::from).toList(),
            nextCursor
        );
    }

    private String serializeForHash(CreateTransferRequest req) {
        // Deterministic serialization of the request fields for idempotency body-hash
        StringBuilder sb = new StringBuilder();
        sb.append(req.reference()).append('|');
        req.postings().stream()
            .sorted((a, b) -> a.accountId().compareTo(b.accountId()))
            .forEach(p -> sb.append(p.accountId()).append(':')
                .append(p.direction()).append(':')
                .append(p.amountMinor()).append(':')
                .append(p.currency()).append(';'));
        return sb.toString();
    }

    record CursorData(Instant ts, UUID id) {}

    private String encodeCursor(Entry e) {
        String raw = e.getCreatedAt().toString() + "," + e.getId().toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    private CursorData decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor));
            String[] parts = raw.split(",", 2);
            return new CursorData(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor: " + cursor);
        }
    }
}
