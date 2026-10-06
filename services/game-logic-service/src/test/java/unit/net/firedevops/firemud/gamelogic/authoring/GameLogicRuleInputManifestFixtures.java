package net.firedevops.firemud.gamelogic.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;

/** Test-only builder for complete five-owner bindings with explicitly synthetic peer payloads. */
final class GameLogicRuleInputManifestFixtures {
  private GameLogicRuleInputManifestFixtures() {}

  static DraftCommitBinding fullBinding() {
    TargetProof target = target();
    return fullBinding(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit",
        GameLogicRuleInputManifest.emptyIntentJson(),
        1,
        List.of(gameLogicUnit(target)));
  }

  static DraftCommitBinding fullBinding(
      TargetProof target,
      UUID requestId,
      UUID commitId,
      String baseCommitId,
      String gameLogicPayload,
      int gameLogicRevisionCount,
      List<AffectedUnit> gameLogicUnits) {
    List<RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> affectedUnits = new ArrayList<>();
    int revisionOrder = 0;
    String versionId = target.canonicalVersionId().toString();
    for (Owner owner : Owner.values()) {
      int ownerRevisionCount = owner == Owner.GAME_LOGIC ? gameLogicRevisionCount : 1;
      for (int index = 0; index < ownerRevisionCount; index++) {
        String payload =
            owner == Owner.GAME_LOGIC
                ? gameLogicPayload
                : "{\"syntheticStorageTestParticipant\":\"" + owner.name() + "\"}";
        revisions.add(
            new RevisionPayload(
                Integer.toString(revisionOrder++), UUID.randomUUID(), owner, payload));
      }
      if (owner != Owner.GAME_LOGIC) {
        affectedUnits.add(
            new AffectedUnit(owner, "SYNTHETIC_TEST_OWNER", versionId, "VERSION", versionId, "0"));
      }
    }
    affectedUnits.addAll(gameLogicUnits);
    return DraftCommitBinding.create(
        target, requestId, commitId, baseCommitId, revisions, affectedUnits);
  }

  static TargetProof target() {
    String privateTenantKey = UUID.randomUUID().toString();
    return new TargetProof(
        UUID.randomUUID(),
        UUID.randomUUID(),
        101L,
        privateTenantKey,
        202L,
        privateTenantKey,
        "NEW_GAME_ROW");
  }

  static TargetProof targetWithSameCanonicalVersionAndDifferentSource(TargetProof original) {
    String privateTenantKey = UUID.randomUUID().toString();
    return new TargetProof(
        original.canonicalTenantId(),
        original.canonicalVersionId(),
        original.gameDesignVersionRowId() + 1L,
        privateTenantKey,
        original.sourceGameRowId() + 1L,
        privateTenantKey,
        "RETAINED_GAME_V30");
  }

  static AffectedUnit gameLogicUnit(TargetProof target) {
    String versionId = target.canonicalVersionId().toString();
    return new AffectedUnit(
        Owner.GAME_LOGIC,
        GameLogicRuleInputManifest.AGGREGATE_TYPE,
        versionId,
        GameLogicRuleInputManifest.SCOPE_TYPE,
        versionId,
        "0");
  }
}
