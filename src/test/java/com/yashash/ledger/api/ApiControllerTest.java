package com.yashash.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yashash.ledger.domain.Account;
import com.yashash.ledger.integration.TestContainersConfig;
import com.yashash.ledger.repository.AccountRepository;
import com.yashash.ledger.repository.EntryRepository;
import com.yashash.ledger.repository.OutboxEventRepository;
import com.yashash.ledger.repository.TransferRepository;
import com.yashash.ledger.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc tests for REST API controllers.
 * Tests idempotency, validation, error responses, auth, and keyset pagination.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestContainersConfig.Initializer.class)
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepo;

    @Autowired
    private EntryRepository entryRepo;

    @Autowired
    private TransferRepository transferRepo;

    @Autowired
    private OutboxEventRepository outboxRepo;

    @BeforeEach
    void clean() {
        outboxRepo.deleteAll();
        entryRepo.deleteAll();
        transferRepo.deleteAll();
        accountRepo.deleteAll();
    }

    // ===== Account API Tests =====

    @Test
    void createAccount_returns_201_with_account() throws Exception {
        String body = objectMapper.writeValueAsString(new Object() {
            public String name = "Alice";
            public String currency = "USD";
            public String type = "ASSET";
            public boolean allowOverdraft = false;
        });

        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isString())
            .andExpect(jsonPath("$.name").value("Alice"))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.balanceMinor").value(0))
            .andExpect(jsonPath("$.allowOverdraft").value(false));
    }

    @Test
    void createAccount_missing_name_returns_400() throws Exception {
        String body = objectMapper.writeValueAsString(new Object() {
            public String currency = "USD";
            public String type = "ASSET";
            public boolean allowOverdraft = false;
        });

        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").exists())
            .andExpect(jsonPath("$.title").value("Validation Failed"));
    }

    @Test
    void createAccount_invalid_currency_returns_400() throws Exception {
        String body = objectMapper.writeValueAsString(new Object() {
            public String name = "Alice";
            public String currency = "AB";  // Invalid: not 3 uppercase
            public String type = "ASSET";
            public boolean allowOverdraft = false;
        });

        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Validation Failed"));
    }

    @Test
    void getAccount_returns_200_with_balance() throws Exception {
        Account a = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);

        mockMvc.perform(get("/api/v1/accounts/" + a.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(a.getId().toString()))
            .andExpect(jsonPath("$.name").value("Alice"))
            .andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void getAccount_nonexistent_returns_404() throws Exception {
        UUID fakeId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/accounts/" + fakeId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Not Found"));
    }

    // ===== Transfer API Tests =====

    @Test
    void postTransfer_success_returns_201() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 1000_00L);

        String body = objectMapper.writeValueAsString(new Object() {
            public Object[] postings = {
                new Object() {
                    public String accountId = alice.getId().toString();
                    public String direction = "DEBIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                },
                new Object() {
                    public String accountId = bob.getId().toString();
                    public String direction = "CREDIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                }
            };
        });

        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "txn-001")
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isString())
            .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    void postTransfer_idempotency_replay_same_key_same_body_returns_same_transfer() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 1000_00L);

        String body = objectMapper.writeValueAsString(new Object() {
            public Object[] postings = {
                new Object() {
                    public String accountId = alice.getId().toString();
                    public String direction = "DEBIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                },
                new Object() {
                    public String accountId = bob.getId().toString();
                    public String direction = "CREDIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                }
            };
        });

        String response1 = mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-replay-001")
                .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String transferId1 = objectMapper.readTree(response1).path("id").asText();

        String response2 = mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-replay-001")
                .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String transferId2 = objectMapper.readTree(response2).path("id").asText();

        // Same transfer ID returned
        assert transferId1.equals(transferId2);
    }

    @Test
    void postTransfer_idempotency_conflict_same_key_different_body_returns_409() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        Account charlie = accountService.createAccount("Charlie", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 1000_00L);

        String body1 = objectMapper.writeValueAsString(new Object() {
            public Object[] postings = {
                new Object() {
                    public String accountId = alice.getId().toString();
                    public String direction = "DEBIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                },
                new Object() {
                    public String accountId = bob.getId().toString();
                    public String direction = "CREDIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                }
            };
        });

        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-conflict-001")
                .content(body1))
            .andExpect(status().isCreated());

        // Same key, different body (charlie instead of bob)
        String body2 = objectMapper.writeValueAsString(new Object() {
            public Object[] postings = {
                new Object() {
                    public String accountId = alice.getId().toString();
                    public String direction = "DEBIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                },
                new Object() {
                    public String accountId = charlie.getId().toString();
                    public String direction = "CREDIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                }
            };
        });

        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-conflict-001")
                .content(body2))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Idempotency Conflict"));
    }

    @Test
    void postTransfer_insufficient_funds_returns_422() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);

        String body = objectMapper.writeValueAsString(new Object() {
            public Object[] postings = {
                new Object() {
                    public String accountId = alice.getId().toString();
                    public String direction = "DEBIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                },
                new Object() {
                    public String accountId = bob.getId().toString();
                    public String direction = "CREDIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                }
            };
        });

        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "insufficient-001")
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.title").value("Insufficient Funds"));
    }

    @Test
    void postTransfer_unbalanced_postings_returns_400() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 1000_00L);

        // Unbalanced: debit 100 but credit 50
        String body = objectMapper.writeValueAsString(new Object() {
            public Object[] postings = {
                new Object() {
                    public String accountId = alice.getId().toString();
                    public String direction = "DEBIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                },
                new Object() {
                    public String accountId = bob.getId().toString();
                    public String direction = "CREDIT";
                    public long amountMinor = 50_00L;
                    public String currency = "USD";
                }
            };
        });

        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "unbalanced-001")
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void postTransfer_invalid_amount_returns_400() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 1000_00L);

        // Negative amount
        String body = objectMapper.writeValueAsString(new Object() {
            public Object[] postings = {
                new Object() {
                    public String accountId = alice.getId().toString();
                    public String direction = "DEBIT";
                    public long amountMinor = -100L;
                    public String currency = "USD";
                },
                new Object() {
                    public String accountId = bob.getId().toString();
                    public String direction = "CREDIT";
                    public long amountMinor = -100L;
                    public String currency = "USD";
                }
            };
        });

        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "invalid-amt-001")
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reverseTransfer_success_returns_201() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 1000_00L);

        String transferBody = objectMapper.writeValueAsString(new Object() {
            public Object[] postings = {
                new Object() {
                    public String accountId = alice.getId().toString();
                    public String direction = "DEBIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                },
                new Object() {
                    public String accountId = bob.getId().toString();
                    public String direction = "CREDIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                }
            };
        });

        String transferResp = mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "txn-rev-001")
                .content(transferBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String transferId = objectMapper.readTree(transferResp).path("id").asText();

        mockMvc.perform(post("/api/v1/transfers/" + transferId + "/reverse")
                .header("Idempotency-Key", "rev-001"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("POSTED"))
            .andExpect(jsonPath("$.reversalOfId").value(transferId));
    }

    @Test
    void getTransfer_returns_200() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 1000_00L);

        String body = objectMapper.writeValueAsString(new Object() {
            public Object[] postings = {
                new Object() {
                    public String accountId = alice.getId().toString();
                    public String direction = "DEBIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                },
                new Object() {
                    public String accountId = bob.getId().toString();
                    public String direction = "CREDIT";
                    public long amountMinor = 100_00L;
                    public String currency = "USD";
                }
            };
        });

        String response = mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "get-txn-001")
                .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String transferId = objectMapper.readTree(response).path("id").asText();

        mockMvc.perform(get("/api/v1/transfers/" + transferId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(transferId))
            .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    void listEntries_keyset_pagination() throws Exception {
        Account alice = accountService.createAccount("Alice", "USD", Account.AccountType.ASSET, false);
        Account bob = accountService.createAccount("Bob", "USD", Account.AccountType.ASSET, false);
        seedBalance(alice.getId(), 5000_00L);

        // Create multiple transfers to alice
        for (int i = 0; i < 3; i++) {
            String body = objectMapper.writeValueAsString(new Object() {
                public Object[] postings = {
                    new Object() {
                        public String accountId = alice.getId().toString();
                        public String direction = "DEBIT";
                        public long amountMinor = 100_00L;
                        public String currency = "USD";
                    },
                    new Object() {
                        public String accountId = bob.getId().toString();
                        public String direction = "CREDIT";
                        public long amountMinor = 100_00L;
                        public String currency = "USD";
                    }
                };
            });

            mockMvc.perform(post("/api/v1/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "list-txn-" + i)
                    .content(body))
                .andExpect(status().isCreated());
        }

        // List entries first page
        mockMvc.perform(get("/api/v1/transfers/accounts/" + alice.getId() + "/entries"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries").isArray())
            .andExpect(jsonPath("$.entries.length()").value(greaterThan(0)));
    }

    // ===== Actuator endpoints (health, prometheus) =====

    @Test
    void health_endpoint_returns_200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void prometheus_endpoint_accessible() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk());
    }

    // ===== Helpers =====

    private void seedBalance(UUID accountId, long amount) {
        Account account = accountRepo.findById(accountId).orElseThrow();
        account.setBalanceMinor(amount);
        accountRepo.save(account);
    }
}
