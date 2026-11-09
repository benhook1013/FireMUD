package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/**
 * Durable membership-operation outcome. Success preserves the exact membership admission flag; it
 * is not a gameplay admission grant. A failure is returned so its receipt can commit first.
 */
public record JoinPublicProductionResult(
    boolean success,
    String outcomeCode,
    String accountId,
    UUID tenantId,
    long membershipId,
    long membershipVersion,
    long membershipAuthorityGeneration,
    boolean gameplayAdmissionAllowed,
    boolean replayed) {}
