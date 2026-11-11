package com.yashash.ledger.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Simple API-key authentication filter.
 * Keys are stored as SHA-256 hashes in app config (never plaintext in DB or logs).
 *
 * Trade-off: simple but not revocable per-key without redeploy. Real systems use
 * OAuth2/mTLS with key rotation and per-key rate limiting.
 *
 * Pass key in header: Authorization: ApiKey <key>
 */
@Component
@Slf4j
public class ApiKeyAuthFilter implements Filter {

    private final Set<String> allowedKeyHashes;

    public ApiKeyAuthFilter(@Value("${ledger.api-keys:}") String rawKeys) {
        // rawKeys is a comma-separated list of plain keys from config (env var in production)
        // We hash them at startup
        this.allowedKeyHashes = Arrays.stream(rawKeys.split(","))
            .map(String::trim)
            .filter(k -> !k.isBlank())
            .map(this::hash)
            .collect(Collectors.toSet());
        log.info("ApiKeyAuthFilter initialized with {} key(s)", allowedKeyHashes.size());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        // Skip actuator and OpenAPI endpoints
        String path = req.getRequestURI();
        if (path.startsWith("/actuator") || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")) {
            chain.doFilter(request, response);
            return;
        }

        // If no keys configured, allow all (dev mode)
        if (allowedKeyHashes.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("ApiKey ")) {
            sendUnauthorized(resp, "Missing Authorization: ApiKey <key> header");
            return;
        }

        String key = header.substring("ApiKey ".length()).trim();
        if (!allowedKeyHashes.contains(hash(key))) {
            sendUnauthorized(resp, "Invalid API key");
            return;
        }

        chain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse resp, String message) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/problem+json");
        resp.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"" + message + "\"}");
    }

    private String hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
