package net.firedevops.firemud.accountservice.dto;

/** Durable explicit-JOIN outcome; a failure is returned so its receipt can commit first. */
public record JoinPublicProductionResult(
    boolean success,
    String outcomeCode,
    long accountId,
    long tenantId,
    long membershipId,
    long membershipVersion,
    long membershipAuthorityGeneration,
    boolean replayed) {}
