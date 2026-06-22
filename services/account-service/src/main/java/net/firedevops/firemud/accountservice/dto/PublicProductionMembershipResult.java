package net.firedevops.firemud.accountservice.dto;

/** Result of ensuring public-production gameplay membership for first admission. */
public record PublicProductionMembershipResult(
    long accountId,
    long tenantId,
    String worldSlug,
    String realmSlug,
    long membershipVersion,
    boolean created,
    String requestId,
    String evaluatedAt,
    boolean replayed) {}
