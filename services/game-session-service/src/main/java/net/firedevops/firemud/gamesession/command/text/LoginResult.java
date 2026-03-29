package net.firedevops.firemud.gamesession.command.text;

/** Minimal data needed from a successful login to bind a session context. */
public record LoginResult(
    long accountId,
    long tenantId,
    long characterId,
    long gameInstanceId,
    String roomInstanceId,
    String jwt) {}
