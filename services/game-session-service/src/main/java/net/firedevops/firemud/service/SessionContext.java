package net.firedevops.firemud.service;

/** Represents persisted login context stored in Redis for a session. */
public record SessionContext(
    long sessionId, long tenantId, long accountId, long playerId, long gameInstanceId, String jwt) {}
