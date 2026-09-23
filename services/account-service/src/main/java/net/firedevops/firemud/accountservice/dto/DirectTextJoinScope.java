package net.firedevops.firemud.accountservice.dto;

/**
 * Opaque, short-lived Account-issued target scope retained only by Game Session transport state.
 */
public record DirectTextJoinScope(String connectScopeId, String connectScopeExpiresAt) {}
