package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/** Public realm selector evidence; its retained scope grants neither entry nor character access. */
public record BootstrapRealmDto(
    String worldSlug,
    String realmSlug,
    UUID realmId,
    String displayName,
    UUID tenantId,
    UUID gameInstanceId,
    long pointerVersion,
    long catalogRevision,
    UUID playableStateNamespaceId,
    String playableStateScope,
    String stateScope,
    String characterCreationPolicy,
    String evaluatedAt,
    String connectScopeExpiresAt,
    String connectScopeId) {
  @Override
  public String toString() {
    return "BootstrapRealmDto[nonadmitting, connectScopeId=<redacted>]";
  }
}
