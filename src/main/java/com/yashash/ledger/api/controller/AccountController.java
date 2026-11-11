package com.yashash.ledger.api.controller;

import com.yashash.ledger.api.dto.AccountDtos.*;
import com.yashash.ledger.domain.Account;
import com.yashash.ledger.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Create and query accounts")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new account")
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest req) {
        Account account = accountService.createAccount(
            req.name(), req.currency(), req.type(), req.allowOverdraft());
        return AccountResponse.from(account);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get account with current balance")
    public AccountResponse getAccount(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getAccount(id));
    }
}
