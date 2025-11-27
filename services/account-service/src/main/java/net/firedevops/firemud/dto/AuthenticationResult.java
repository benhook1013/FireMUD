package net.firedevops.firemud.dto;

/** Result of a successful authentication attempt. */
public record AuthenticationResult(long accountId, String token) {}
