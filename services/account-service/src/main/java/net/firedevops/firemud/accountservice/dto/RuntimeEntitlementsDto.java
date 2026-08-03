package net.firedevops.firemud.accountservice.dto;

public record RuntimeEntitlementsDto(
    Long tenantId,
    boolean gameplayAvailable,
    boolean allowPublicJoin,
    long entitlementVersion,
    long tenantBillingSequence,
    String evaluatedAt) {}
