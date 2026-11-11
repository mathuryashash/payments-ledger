package com.yashash.ledger.api.dto;

import com.yashash.ledger.domain.Account;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AccountDtos {

    public record CreateAccountRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "Currency must be 3 uppercase letters") String currency,
        @NotNull Account.AccountType type,
        boolean allowOverdraft
    ) {}

    public record AccountResponse(
        String id,
        String name,
        String currency,
        String type,
        long balanceMinor,
        boolean allowOverdraft,
        String createdAt
    ) {
        public static AccountResponse from(Account a) {
            return new AccountResponse(
                a.getId().toString(),
                a.getName(),
                a.getCurrency(),
                a.getType().name(),
                a.getBalanceMinor(),
                a.isAllowOverdraft(),
                a.getCreatedAt().toString()
            );
        }
    }
}
