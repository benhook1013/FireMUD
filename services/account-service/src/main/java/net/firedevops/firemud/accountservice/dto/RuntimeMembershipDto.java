package net.firedevops.firemud.accountservice.dto;

public record RuntimeMembershipDto(
    Long accountId,
    Long tenantId,
    boolean membershipExists,
    boolean gameplayAdmissionAllowed,
    long membershipVersion,
    String evaluatedAt) {}
