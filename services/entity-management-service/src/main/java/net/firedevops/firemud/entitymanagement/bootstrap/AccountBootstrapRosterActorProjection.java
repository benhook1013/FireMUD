package net.firedevops.firemud.entitymanagement.bootstrap;

import java.util.UUID;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;

/** Exact same-row persisted discovery fields; zero is a legitimate stored level. */
public record AccountBootstrapRosterActorProjection(
    UUID characterUuid, String displayName, int level) {
  public AccountBootstrapRosterActorProjection {
    new CanonicalGameplayRosterActor(characterUuid, displayName);
    if (level < 0) throw new IllegalArgumentException("Persisted actor level is invalid");
  }
}
