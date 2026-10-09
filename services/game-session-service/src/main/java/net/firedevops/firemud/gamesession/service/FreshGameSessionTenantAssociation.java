package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;

/** Immutable Game Session-local mapping to exact authenticated fresh Game Design identity. */
public record FreshGameSessionTenantAssociation(
    UUID associationOperationId,
    long legacyGameSessionTenantId,
    RuntimeTenantIdentityEvidence sourceEvidence) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public FreshGameSessionTenantAssociation {
    if (associationOperationId == null || NIL_UUID.equals(associationOperationId)) {
      throw new IllegalArgumentException("associationOperationId must be a non-nil UUID");
    }
    if (legacyGameSessionTenantId <= 0) {
      throw new IllegalArgumentException("legacyGameSessionTenantId must be positive");
    }
    Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    if (!"NEW_GAME_ROW".equals(sourceEvidence.provenanceKind())) {
      throw new IllegalArgumentException("Fresh association requires NEW_GAME_ROW provenance");
    }
  }
}
