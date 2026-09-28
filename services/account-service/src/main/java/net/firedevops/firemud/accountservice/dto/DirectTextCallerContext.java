package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/** Parsed scope data from a verified Game Session mTLS call, never an independent capability. */
public record DirectTextCallerContext(
    long accountId,
    long tenantId,
    UUID realmId,
    String playableStateNamespaceId,
    String playableStateScope,
    long gameInstanceId,
    String sessionId,
    String requestId) {}
