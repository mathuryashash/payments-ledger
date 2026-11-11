package com.yashash.ledger.service;

import com.yashash.ledger.domain.Account;
import com.yashash.ledger.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepo;

    @Transactional
    public Account createAccount(String name, String currency, Account.AccountType type, boolean allowOverdraft) {
        Account account = Account.builder()
            .id(UUID.randomUUID())
            .name(name)
            .currency(currency.toUpperCase())
            .type(type)
            .balanceMinor(0L)
            .allowOverdraft(allowOverdraft)
            .createdAt(Instant.now())
            .build();
        return accountRepo.save(account);
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID id) {
        return accountRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Account not found: " + id));
    }
}
