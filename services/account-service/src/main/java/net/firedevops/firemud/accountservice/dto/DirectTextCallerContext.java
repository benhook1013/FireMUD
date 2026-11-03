package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/** Parsed scope data from a verified Game Session mTLS call, never an independent capability. */
public record DirectTextCallerContext(
    UUID accountId,
    UUID tenantId,
    UUID realmId,
    UUID playableStateNamespaceId,
    String playableStateScope,
    UUID gameInstanceId,
    String sessionId,
    String requestId) {}
