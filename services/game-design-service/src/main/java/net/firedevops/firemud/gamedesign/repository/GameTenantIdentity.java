package net.firedevops.firemud.gamedesign.repository;

import java.util.UUID;

/** Game Design's provenance-bearing canonical tenant identity sourced from its game row. */
public record GameTenantIdentity(
    UUID canonicalTenantId,
    ProvenanceKind provenanceKind,
    Long sourceGameId,
    String sourceLegacyTenantId) {
  public enum ProvenanceKind {
    RETAINED_GAME_V30,
    NEW_GAME_ROW
  }
}
