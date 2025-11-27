package net.firedevops.firemud.command.text;

/** Minimal data needed from a successful login to bind a session context. */
public record LoginResult(
    long accountId, long tenantId, long playerId, long gameInstanceId, String jwt) {}
