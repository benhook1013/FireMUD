package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;

/**
 * Exact source-material binding tests; these fixtures do not authenticate Account authorization.
 */
class GameDesignDraftTerminalOperationTest {
  @Test
  void retainsTheOriginalAccountBytesAndCompleteCanonicalGameDesignBinding() {
    DraftCommitBinding gameDesign = gameDesignBinding();
    DraftAuthorizationFenceBinding account = accountBinding(gameDesign, "source-checkpoint-a");

    GameDesignDraftTerminalOperation operation =
        new GameDesignDraftTerminalOperation(account, gameDesign);

    assertThat(operation.accountBindingBytes()).containsExactly(account.canonicalBytes());
    assertThat(operation.accountBindingDigest())
        .isEqualTo(GameDesignDraftTerminalOperation.sha256(account.canonicalBytes()));
    assertThat(operation.gameDesignBinding().canonicalBytes())
        .containsExactly(gameDesign.canonicalBytes());
    assertThat(operation.exactlyMatches(new GameDesignDraftTerminalOperation(account))).isTrue();
  }

  @Test
  void operationCannotPairDifferentCompleteGameDesignBytes() {
    DraftCommitBinding gameDesign = gameDesignBinding();
    DraftAuthorizationFenceBinding account = accountBinding(gameDesign, "source-checkpoint-a");
    DraftCommitBinding changed =
        DraftCommitBinding.create(
            gameDesign.target(),
            gameDesign.requestId(),
            gameDesign.commitId(),
            gameDesign.baseCommitId(),
            List.of(
                new RevisionPayload(
                    "0", UUID.randomUUID(), Owner.WORLD_MANAGEMENT, "changed payload"),
                gameDesign.revisions().get(1)),
            gameDesign.affectedUnits());

    assertThatThrownBy(() -> new GameDesignDraftTerminalOperation(account, changed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete Game Design binding");
  }

  @Test
  void differentOriginalAccountSourceBytesDoNotAliasTheSameOperation() {
    DraftCommitBinding gameDesign = gameDesignBinding();
    UUID operationId = UUID.randomUUID();
    UUID fenceId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    DraftAuthorizationFenceBinding original =
        accountBinding(gameDesign, operationId, fenceId, actorId, "source-checkpoint-a");
    DraftAuthorizationFenceBinding changed =
        accountBinding(gameDesign, operationId, fenceId, actorId, "source-checkpoint-b");

    GameDesignDraftTerminalOperation first =
        new GameDesignDraftTerminalOperation(original, gameDesign);
    GameDesignDraftTerminalOperation second =
        new GameDesignDraftTerminalOperation(changed, gameDesign);

    assertThat(original.operationId()).isEqualTo(changed.operationId());
    assertThat(first.exactlyMatches(second)).isFalse();
    assertThat(first.accountBindingDigest()).isNotEqualTo(second.accountBindingDigest());
  }

  private static DraftCommitBinding gameDesignBinding() {
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    TargetProof target =
        new TargetProof(
            tenantId, versionId, 20L, "tenant-key-20", 10L, "tenant-key-20", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-7",
        List.of(
            new RevisionPayload("0", UUID.randomUUID(), Owner.WORLD_MANAGEMENT, "world change"),
            new RevisionPayload("1", UUID.randomUUID(), Owner.ENTITY_MANAGEMENT, "entity change")),
        List.of(
            new AffectedUnit(Owner.WORLD_MANAGEMENT, "WORLD", "world-1", "ROOM", "room-1", "0"),
            new AffectedUnit(
                Owner.ENTITY_MANAGEMENT, "ENTITY", "entity-1", "ACTOR", "actor-1", "0")));
  }

  private static DraftAuthorizationFenceBinding accountBinding(
      DraftCommitBinding gameDesign, String sourceCheckpoint) {
    return accountBinding(
        gameDesign, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sourceCheckpoint);
  }

  private static DraftAuthorizationFenceBinding accountBinding(
      DraftCommitBinding gameDesign,
      UUID operationId,
      UUID fenceId,
      UUID actorId,
      String sourceCheckpoint) {
    return new DraftAuthorizationFenceBinding(
        operationId,
        gameDesign.requestId(),
        gameDesign.commitId(),
        fenceId,
        actorId,
        gameDesign.target().canonicalTenantId(),
        gameDesign.target().canonicalVersionId(),
        gameDesign.baseCommitId(),
        "7",
        gameDesign.canonicalBytes(),
        gameDesign.canonicalBytes(),
        gameDesign.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.TENANT,
                gameDesign.target().canonicalTenantId().toString(),
                null,
                "1",
                null,
                null,
                sourceCheckpoint.getBytes(StandardCharsets.UTF_8))));
  }
}
