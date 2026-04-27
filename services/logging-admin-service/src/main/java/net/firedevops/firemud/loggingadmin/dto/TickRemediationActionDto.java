package net.firedevops.firemud.loggingadmin.dto;

public record TickRemediationActionDto(
    long tenantId,
    String scopeType,
    String scopeId,
    String action,
    String actorPrincipal,
    String reason) {}
