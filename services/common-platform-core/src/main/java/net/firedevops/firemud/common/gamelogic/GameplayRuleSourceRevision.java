package net.firedevops.firemud.common.gamelogic;

import java.util.Map;

/** Existing canonical GD gameplay-rule UPSERT payload, shared with its provenance reader. */
public final class GameplayRuleSourceRevision {
  private GameplayRuleSourceRevision() {}

  public static String upsertPayload(GameplayRuleManifest.Definition definition) {
    return GameplayRuleManifest.canonical(
        Map.of(
            "schemaVersion",
            1,
            "revisionKind",
            "GAMEPLAY_RULE",
            "operation",
            "UPSERT",
            "family",
            definition.family().name(),
            "definitionJson",
            GameplayRuleManifest.canonical(definition)));
  }
}
