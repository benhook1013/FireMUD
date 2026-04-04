package net.firedevops.firemud.accountservice.dto;

/** Result of issuing a short-lived first-party bootstrap token. */
public record PlayerBootstrapResult(
    long accountId, String bootstrapToken, String issuedAt, String expiresAt) {}
