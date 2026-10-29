package net.firedevops.firemud.accountservice.dto;

/** Result of issuing a short-lived first-party bootstrap token. */
public record PlayerBootstrapResult(
    String accountId, String bootstrapToken, String issuedAt, String expiresAt) {}
