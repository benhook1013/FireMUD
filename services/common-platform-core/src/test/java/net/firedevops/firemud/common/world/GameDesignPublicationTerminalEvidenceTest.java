package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Participant;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.ReleaseContent;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import org.junit.jupiter.api.Test;

/** Fixed isolated inputs prove codec integrity, never upstream creator or owner authentication. */
class GameDesignPublicationTerminalEvidenceTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void goldenContentFramesAndDigestBindCompleteBundleWithoutLaunchMetadata() throws Exception {
    var operation = operation(9007199254740993L);
    var content = content(operation, List.of("LOOK", "é"), "generation-1");
    // Independent reference vector: every field and optional presence is explicit in owner order.
    String expected =
        frame("game-design-published-release-bundle/v1")
            + frame(content.canonicalTenantId().toString())
            + frame(content.canonicalVersionId().toString())
            + frame("bundle-fixed")
            + frame("1")
            + frame("v2")
            + frame(content.publishWorkflowId())
            + frame(DIGEST)
            + frame("1")
            + frame("1")
            + frame(
                frame("asset-é")
                    + frame("FILE")
                    + frame("artifacts/sha256/" + "a".repeat(64))
                    + frame(DIGEST)
                    + frame("text/plain")
                    + frame("1"))
            + frame("1")
            + frame("asset-é")
            + frame("5");
    var expectedBytes = new StringBuilder(expected);
    for (var p : content.participantDigests()) {
      expectedBytes.append(
          frame(
              frame(p.participantKey())
                  + frame(p.scopeValue())
                  + frame("false")
                  + frame("")
                  + frame(p.appliedCommitId())
                  + frame(p.contentDigest())
                  + frame(Integer.toString(p.digestSchemaVersion()))
                  + frame(p.abilitySchemaDigest() == null ? "false" : "true")
                  + frame(p.abilitySchemaDigest() == null ? "" : p.abilitySchemaDigest())
                  + frame("false")
                  + frame("")
                  + frame("false")
                  + frame("")));
    }
    expectedBytes.append(
        frame("2")
            + frame("LOOK")
            + frame("é")
            + frame("generation-1")
            + frame(new String(operation.world().canonicalBytes(), StandardCharsets.UTF_8))
            + frame("false")
            + frame("false")
            + frame(""));
    byte[] golden = expectedBytes.toString().getBytes(StandardCharsets.UTF_8);
    assertThat(content.canonicalBytes()).isEqualTo(golden);
    assertThat(content.contentDigest())
        .isEqualTo(
            "sha256:"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(golden)));
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(), Outcome.PUBLISHED, content, 9007199254740994L);
    var expectedTerminal = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(expectedTerminal, "game-design-publication-terminal/v1");
    DraftAuthorizationFenceBinding.frame(expectedTerminal, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(expectedTerminal, "PUBLISHED");
    DraftAuthorizationFenceBinding.frame(expectedTerminal, golden);
    DraftAuthorizationFenceBinding.frame(expectedTerminal, "9007199254740994");
    assertThat(terminal.canonicalBytes()).isEqualTo(expectedTerminal.toByteArray());
    var decoded = GameDesignPublicationTerminalEvidence.fromStored(terminal.canonicalBytes());
    assertThat(decoded.canonicalBytes()).isEqualTo(terminal.canonicalBytes());
    assertThat(decoded.operationBytes()).isEqualTo(operation.canonicalBytes());
    assertThat(decoded.publicationVersionStateEpoch()).isEqualTo(9007199254740994L);
  }

  @Test
  void changedContentAndOptionalPresenceChangeDistinctContentDigest() throws Exception {
    var operation = operation(5);
    var original = content(operation, List.of("LOOK"), "generation-1");
    assertThat(content(operation, List.of("LOOK", "PLAY"), "generation-1").contentDigest())
        .isNotEqualTo(original.contentDigest());
    assertThat(content(operation, List.of("LOOK"), "generation-2").contentDigest())
        .isNotEqualTo(original.contentDigest());
    var changedManifest =
        new ReleaseContent(
            original.canonicalTenantId(),
            original.canonicalVersionId(),
            "changed-ref",
            2,
            original.attestationSchemaVersion(),
            original.publishWorkflowId(),
            "sha256:" + "d".repeat(64),
            original.manifestSchemaVersion(),
            original.artifactDigests(),
            original.requiredManifestAssetKeys(),
            original.participantDigests(),
            original.commandDefinitions(),
            original.generationConfigRevision(),
            original.worldStartLocationEvidence());
    assertThat(changedManifest.contentDigest()).isNotEqualTo(original.contentDigest());
    var changedArtifact =
        List.of(
            new AuthoredWorldReleaseAttestationEvidence.Artifact(
                "asset-é",
                "TEXT",
                "artifacts/sha256/" + "d".repeat(64),
                "sha256:" + "d".repeat(64),
                "text/other",
                1));
    assertThat(
            copy(
                    original,
                    original.publishWorkflowId(),
                    original.participantDigests(),
                    changedArtifact)
                .contentDigest())
        .isNotEqualTo(original.contentDigest());
    var participants = new ArrayList<>(original.participantDigests());
    var p = participants.getFirst();
    participants.set(
        0,
        new Participant(
            p.participantKey(),
            p.scopeValue(),
            null,
            p.appliedCommitId(),
            p.contentDigest(),
            p.digestSchemaVersion(),
            null,
            "",
            ""));
    assertThat(
            copy(original, original.publishWorkflowId(), participants, original.artifactDigests())
                .contentDigest())
        .isNotEqualTo(original.contentDigest());
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(), Outcome.PUBLISHED, original, 6L);
    byte[] bytes = terminal.canonicalBytes();
    bytes[0] = 99;
    assertThat(terminal.canonicalBytes()).isNotEqualTo(bytes);
  }

  @Test
  void rejectsWrongOperationWorkflowFreezeCommitRequiredParticipantAndArtifact() throws Exception {
    var operation = operation(5);
    var content = content(operation, List.of(), "generation-1");
    assertThatThrownBy(
            () ->
                copy(
                    content,
                    "changed-workflow",
                    content.participantDigests(),
                    content.artifactDigests()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                copy(
                    content,
                    content.publishWorkflowId(),
                    content.participantDigests().subList(0, 4),
                    content.artifactDigests()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                copy(content, content.publishWorkflowId(), content.participantDigests(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    var changed = new ArrayList<>(content.participantDigests());
    var p = changed.getFirst();
    changed.set(
        0,
        new Participant(
            p.participantKey(),
            p.scopeValue(),
            null,
            "changed-commit",
            p.contentDigest(),
            p.digestSchemaVersion(),
            null,
            null,
            null));
    assertThatThrownBy(
            () -> copy(content, content.publishWorkflowId(), changed, content.artifactDigests()))
        .isInstanceOf(IllegalArgumentException.class);
    var missingAbility = new ArrayList<>(content.participantDigests());
    var logic = missingAbility.get(2);
    missingAbility.set(
        2,
        new Participant(
            logic.participantKey(),
            logic.scopeValue(),
            null,
            logic.appliedCommitId(),
            logic.contentDigest(),
            logic.digestSchemaVersion(),
            null,
            null,
            null));
    assertThatThrownBy(
            () ->
                copy(
                    content,
                    content.publishWorkflowId(),
                    missingAbility,
                    content.artifactDigests()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AuthoredWorldReleaseAttestationEvidence.Artifact(
                    "asset-é", "FILE", "wrong-object", DIGEST, "text/plain", 1))
        .isInstanceOf(IllegalArgumentException.class);
    var reordered = new ArrayList<>(content.participantDigests());
    java.util.Collections.swap(reordered, 0, 1);
    assertThatThrownBy(
            () -> copy(content, content.publishWorkflowId(), reordered, content.artifactDigests()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalEvidence(
                    operation.canonicalBytes(), Outcome.PUBLISHED, content, 7L))
        .isInstanceOf(IllegalArgumentException.class);
    var account = operation.account();
    var allocatedDifferently =
        new GameDesignPublicationOperationBinding(
            new AccountPublicationAuthorizationBinding(
                UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                account.fenceId(),
                account.input(),
                account.sources()),
            operation.world());
    var first =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(), Outcome.PUBLISHED, content, 6L);
    var second =
        new GameDesignPublicationTerminalEvidence(
            allocatedDifferently.canonicalBytes(), Outcome.PUBLISHED, content, 6L);
    assertThat(second.canonicalBytes()).isNotEqualTo(first.canonicalBytes());
    var world = operation.world();
    var r = world.request();
    var changedFreeze =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                r.targetNamespace(),
                r.canonicalTenantId(),
                r.canonicalVersionId(),
                r.intakeRequestId(),
                UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                r.publicationRequestId(),
                r.requestDigest(),
                r.versionStateEpoch(),
                r.publishWorkflowId(),
                r.appliedCommitId(),
                r.contentDigest(),
                r.digestSchemaVersion(),
                r.worldAffectedTuples()),
            world.selectorReceiptBytes(),
            world.originalAccountBindingBytes(),
            world.appliedResultBytes());
    var changedOperation = new GameDesignPublicationOperationBinding(account, changedFreeze);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalEvidence(
                    changedOperation.canonicalBytes(), Outcome.PUBLISHED, content, 6L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noPublicationHasNoReleaseAndRejectsMalformedTrailingAndNoncanonicalFrames()
      throws Exception {
    var operation = operation(5);
    var no =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(), Outcome.NO_PUBLICATION, null, null);
    assertThat(GameDesignPublicationTerminalEvidence.fromStored(no.canonicalBytes()).outcome())
        .isEqualTo(Outcome.NO_PUBLICATION);
    assertThatThrownBy(no::publishedReleaseBundleDigest).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(no::publishedReleaseBundleRef).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(no::publicationVersionStateEpoch).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalEvidence(
                    operation.canonicalBytes(),
                    Outcome.NO_PUBLICATION,
                    content(operation, List.of(), "g"),
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalEvidence.fromStored(
                    Arrays.copyOf(no.canonicalBytes(), no.canonicalBytes().length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    var content = content(operation, List.of(), "g");
    var canonical = content.canonicalBytes();
    var noncanonical =
        ("0" + new String(canonical, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> ReleaseContent.fromStored(noncanonical))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ReleaseContent.fromStored(Arrays.copyOf(canonical, canonical.length - 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ReleaseContent.fromStored(Arrays.copyOf(canonical, canonical.length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> GameDesignPublicationTerminalEvidence.fromStored(operation.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ReleaseContent content(
      GameDesignPublicationOperationBinding operation, List<String> commands, String generation) {
    var r = operation.world().request();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new Participant(
                        owner,
                        Long.toString(
                            operation
                                .account()
                                .input()
                                .selection()
                                .target()
                                .gameDesignVersionRowId()),
                        null,
                        r.appliedCommitId(),
                        owner.equals("WORLD_MANAGEMENT") ? r.contentDigest() : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        owner.equals("GAME_LOGIC") ? DIGEST : null,
                        null,
                        null))
            .toList();
    return new ReleaseContent(
        r.canonicalTenantId(),
        r.canonicalVersionId(),
        "bundle-fixed",
        1,
        "v2",
        r.publishWorkflowId(),
        DIGEST,
        1,
        List.of(
            new AuthoredWorldReleaseAttestationEvidence.Artifact(
                "asset-é", "FILE", "artifacts/sha256/" + "a".repeat(64), DIGEST, "text/plain", 1)),
        List.of("asset-é"),
        participants,
        commands,
        generation,
        operation.world());
  }

  private static ReleaseContent copy(
      ReleaseContent c,
      String workflow,
      List<Participant> participants,
      List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifacts) {
    return new ReleaseContent(
        c.canonicalTenantId(),
        c.canonicalVersionId(),
        c.publishedReleaseBundleRef(),
        c.versionNumber(),
        c.attestationSchemaVersion(),
        workflow,
        c.manifestHash(),
        c.manifestSchemaVersion(),
        artifacts,
        c.requiredManifestAssetKeys(),
        participants,
        c.commandDefinitions(),
        c.generationConfigRevision(),
        c.worldStartLocationEvidence());
  }

  private static GameDesignPublicationOperationBinding operation(long epoch) throws Exception {
    var seed = WorldPublishedStartLocationGrpcCodecTest.evidence();
    var original = DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
    var draft =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
            original.inputDigest());
    var target = draft.target();
    String request = "publication-request";
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                request,
                Long.toString(epoch),
                "fixed-notes",
                draft.requestId(),
                draft.commitId(),
                draft.digest()),
            target,
            draft,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                draft.requestId(),
                draft.commitId(),
                draft.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-07T00:00:00Z")));
    var account =
        new AccountPublicationAuthorizationBinding(
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                original.actorAccountId(), selected),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    original.actorAccountId().toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    var r = seed.request();
    var world =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                r.targetNamespace(),
                r.canonicalTenantId(),
                r.canonicalVersionId(),
                r.intakeRequestId(),
                r.publicationFence(),
                request,
                selected.digest().substring(7),
                epoch,
                PublicationDigestRequestBinding.full(
                        target.canonicalTenantId().toString(),
                        Long.toString(target.gameDesignVersionRowId()),
                        request)
                    .derivedWorkflowIdentity(),
                r.appliedCommitId(),
                r.contentDigest(),
                r.digestSchemaVersion(),
                r.worldAffectedTuples()),
            seed.selectorReceiptBytes(),
            seed.originalAccountBindingBytes(),
            seed.appliedResultBytes());
    return new GameDesignPublicationOperationBinding(account, world);
  }

  private static String frame(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length + ":" + s;
  }
}
