package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import org.junit.jupiter.api.Test;

/** Canonical fixtures stipulate upstream authority; these are not authenticated owner proof. */
class SelectedDraftGameLogicReceiptTest {
  @Test
  void retainsExactSelectionIntakeAndReceiptOutsideAuthoredBytes() {
    var receipt = fixture();
    byte[] before = receipt.selection().selectedCommit().canonicalBytes();
    var recovered =
        new SelectedDraftGameLogicReceipt(
            AuthoredDraftPublishSelection.fromStored(
                receipt.selection().canonicalJson(), receipt.selection().digest()),
            GameLogicIntakeAuthorizationBinding.fromStored(
                receipt.authorization().canonicalBytes()),
            AccountGameLogicIntakeSettlementEvidence.fromStored(
                receipt.receipt().canonicalBytes()));
    recovered.requireExactRequest(receipt.selection(), receipt.authorization());
    assertThat(recovered.receipt().canonicalBytes())
        .containsExactly(receipt.receipt().canonicalBytes());
    assertThat(recovered.selection().selectedCommit().canonicalBytes()).containsExactly(before);
    assertThat(recovered.workflowIdentity())
        .isEqualTo("publish:" + receipt.authorization().tenantId() + ":publish-request:publish-1");
  }

  @Test
  void rejectsAbortedDifferentSelectionAndChangedAuthorizationRetry() {
    var value = fixture();
    var abort =
        GameLogicGameplayRuleIntakeTerminal.aborted(
            new GameLogicGameplayRuleIntakeOperation("test", value.authorization()));
    assertThatThrownBy(
            () ->
                new SelectedDraftGameLogicReceipt(
                    value.selection(),
                    value.authorization(),
                    new AccountGameLogicIntakeSettlementEvidence(abort)))
        .isInstanceOf(IllegalArgumentException.class);
    var other = fixture();
    assertThatThrownBy(
            () ->
                SelectedDraftGameLogicReceipt.requireExactSelection(
                    value.selection(), other.authorization()))
        .isInstanceOf(IllegalArgumentException.class);
    var changed =
        new GameLogicIntakeAuthorizationBinding(
            value.authorization().operationId(),
            UUID.randomUUID(),
            value.authorization().intakeRequestId(),
            value.authorization().actorAccountId(),
            value.authorization().source(),
            value.authorization().sources());
    assertThatThrownBy(() -> value.requireExactRequest(value.selection(), changed))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> new SelectedDraftGameLogicReceipt(value.selection(), changed, value.receipt()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedAuthoredInputWithSameTenantVersionRequestAndCommitIds() {
    var value = fixture();
    var original = value.authorization().source().binding();
    var revision = original.revisions().getFirst();
    var changedBinding =
        DraftCommitBinding.create(
            original.target(),
            original.requestId(),
            original.commitId(),
            original.baseCommitId(),
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    revision.revisionOrder(),
                    revision.revisionId(),
                    revision.owner(),
                    "{\"changed\":true}")),
            original.affectedUnits());
    var changedSource =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema",
                    "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson",
                    changedBinding.canonicalJson(),
                    "bindingDigest",
                    changedBinding.digest(),
                    "sourceEpoch",
                    "1",
                    "inheritedCommitId",
                    "",
                    "genesisReceiptId",
                    GameplayRuleManifest.tree(value.authorization().source().snapshotJson())
                        .path("genesisReceiptId")
                        .textValue(),
                    "manifestJson",
                    GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries",
                    List.of())));
    var changed =
        new GameLogicIntakeAuthorizationBinding(
            value.authorization().operationId(),
            value.authorization().fenceId(),
            value.authorization().intakeRequestId(),
            value.authorization().actorAccountId(),
            changedSource,
            value.authorization().sources());
    assertThatThrownBy(
            () -> SelectedDraftGameLogicReceipt.requireExactSelection(value.selection(), changed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete selected Draft binding");
  }

  static SelectedDraftGameLogicReceipt fixture() {
    UUID actor = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema",
                    "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson",
                    binding.canonicalJson(),
                    "bindingDigest",
                    binding.digest(),
                    "sourceEpoch",
                    "1",
                    "inheritedCommitId",
                    "",
                    "genesisReceiptId",
                    UUID.randomUUID().toString(),
                    "manifestJson",
                    GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries",
                    List.of())));
    var auth =
        new GameLogicIntakeAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            actor,
            source,
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    actor.toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                "publish-1",
                "1",
                "",
                binding.requestId(),
                binding.commitId(),
                binding.digest()),
            target,
            binding,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                binding.requestId(),
                binding.commitId(),
                binding.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-08T00:00:00Z")));
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            new GameLogicGameplayRuleIntakeOperation("test", auth),
            source.canonicalBytes(),
            source.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    return new SelectedDraftGameLogicReceipt(
        AuthoredDraftPublishSelection.fromStored(selected.canonicalJson(), selected.digest()),
        auth,
        new AccountGameLogicIntakeSettlementEvidence(terminal));
  }
}
