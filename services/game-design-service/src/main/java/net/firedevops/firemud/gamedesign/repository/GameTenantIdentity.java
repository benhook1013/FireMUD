package net.firedevops.firemud.gamedesign.repository;

import java.util.UUID;

/** Game Design's canonical tenant identity and exact provenance in its owning game row. */
public record GameTenantIdentity(
    UUID canonicalTenantId,
    ProvenanceKind provenanceKind,
    Long sourceGameId,
    String sourceLegacyTenantId) {
  public enum ProvenanceKind {
    RETAINED_GAME_V29,
    NEW_GAME_ROW
  }
}
