package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

public record RuntimeEntitlementsDto(
    UUID tenantId,
    boolean gameplayAvailable,
    boolean allowPublicJoin,
    long entitlementVersion,
    long tenantBillingSequence,
    String evaluatedAt) {}
