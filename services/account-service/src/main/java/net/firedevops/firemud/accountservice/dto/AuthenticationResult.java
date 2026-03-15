package net.firedevops.firemud.accountservice.dto;

/** Result of a successful authentication attempt. */
public record AuthenticationResult(long accountId, String authToken) {}
