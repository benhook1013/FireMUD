package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodecTest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GameDesignPublicationTerminalReadEvidenceTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void requestAndTerminalRetainDefensiveExactOriginalBytes() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    byte[] bytes = operation.canonicalBytes();
    var request =
        new GameDesignPublicationTerminalReadEvidence.Request(1, "test", UUID.randomUUID(), bytes);
    bytes[0] = 0;
    request.originalOperation()[0] = 0;
    assertThat(request.originalOperation()).containsExactly(operation.canonicalBytes());
    var terminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.NO_PUBLICATION);
    byte[] terminalBytes = terminal.canonicalBytes();
    var evidence = new GameDesignPublicationTerminalReadEvidence(request, terminalBytes);
    terminalBytes[0] = 0;
    evidence.terminalEvidence().canonicalBytes()[0] = 0;
    assertThat(evidence.terminalEvidence().canonicalBytes())
        .containsExactly(terminal.canonicalBytes());
  }

  @Test
  void requestRejectsUnsupportedVersionInvalidNamespaceNilIdentityAndAbsentOperation()
      throws Exception {
    byte[] bytes = GameDesignPublicationTerminalReadGrpcCodecTest.operation().canonicalBytes();
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadEvidence.Request(
                    2, "test", UUID.randomUUID(), bytes))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadEvidence.Request(
                    1, "Test", UUID.randomUUID(), bytes))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadEvidence.Request(
                    1, "test", new UUID(0L, 0L), bytes))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalReadEvidence.Request(
                    1, "test", UUID.randomUUID(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void selectedFullBundleV4RetainsItsExactWorldBindingAndClosedParticipantMatrix()
      throws Exception {
    var operation = operationWithWorldSchema4();
    var release = releaseContent(operation, 6, operation.world().request().contentDigest());
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            Outcome.PUBLISHED,
            release,
            operation.world().request().versionStateEpoch() + 1L);
    var readback = GameDesignPublicationTerminalEvidence.fromStored(terminal.canonicalBytes());

    assertThat(readback.releaseContent().attestationSchemaVersion()).isEqualTo("v4");
    assertThat(readback.releaseContent().participantDigests())
        .extracting(GameDesignPublicationTerminalEvidence.Participant::digestSchemaVersion)
        .containsExactly(4, 3, 1, 6, 2);
    assertThat(readback.releaseContent().worldStartLocationEvidence().canonicalBytes())
        .containsExactly(operation.world().canonicalBytes());
    assertThat(readback.canonicalBytes()).containsExactly(terminal.canonicalBytes());

    assertThatThrownBy(
            () -> releaseContent(operation, 5, operation.world().request().contentDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Incomplete or changed required participant proof");
    assertThatThrownBy(() -> releaseContent(operation, 6, "f".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("World participant differs from frozen checkpoint");
  }

  private static GameDesignPublicationOperationBinding operationWithWorldSchema4()
      throws Exception {
    var upstream =
        WorldDraftTerminalReadGrpcCodecTest.freshGraphRequestForStartLocationEvidenceTest();
    var applied =
        WorldDraftTerminalReadGrpcCodecTest.committedFreshGraphReadbackForStartLocationEvidenceTest(
            upstream);
    var original = upstream.accountBinding();
    var draft =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
            original.inputDigest());
    var target = draft.target();
    var intent =
        new PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            "publication-request",
            "5",
            "fixture",
            draft.requestId(),
            draft.commitId(),
            draft.digest());
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            intent,
            target,
            draft,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                draft.requestId(),
                draft.commitId(),
                draft.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    // Distinct publication-order source stipulation, not the upstream Draft COMMIT_ORDER vector.
    // This structural fixture proves neither current actor authority nor an actual source hold.
    var publicationSources =
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                original.actorAccountId().toString(),
                "1",
                "1",
                null,
                null,
                "stipulated-publication-account-source".getBytes(StandardCharsets.UTF_8)));
    var account =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                original.actorAccountId(), selection),
            publicationSources);
    var tuples =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    String workflow =
        PublicationDigestRequestBinding.full(
                target.canonicalTenantId().toString(),
                Long.toString(target.gameDesignVersionRowId()),
                intent.publishRequestId())
            .derivedWorkflowIdentity();
    var result = JSON.readTree(applied.result());
    var appliedOperation =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.FrameReader(
            Base64.getDecoder().decode(result.get("operationBytesBase64").textValue()));
    // Retain the actual intake identity at frame 13 of the existing APPLIED operation.
    appliedOperation.expect("world-draft-terminal-operation/v1");
    for (int frame = 1; frame < 13; frame++) {
      appliedOperation.bytes();
    }
    UUID intakeRequestId = UUID.fromString(appliedOperation.text());
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            "test",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            intakeRequestId,
            UUID.randomUUID(),
            intent.publishRequestId(),
            selection.digest().substring(7),
            5L,
            workflow,
            draft.commitId().toString(),
            "b".repeat(64),
            4,
            tuples);
    var world =
        new WorldPublishedStartLocationEvidence(
            request,
            Base64.getDecoder().decode(result.get("startLocationReceiptBase64").textValue()),
            applied.fullBinding(),
            applied.result());
    return new GameDesignPublicationOperationBinding(
        account,
        world,
        net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures.stipulated(
            account, world));
  }

  private static GameDesignPublicationTerminalEvidence.ReleaseContent releaseContent(
      GameDesignPublicationOperationBinding operation,
      int automationDigestSchemaVersion,
      String worldContentDigest) {
    var selection = operation.account().input().selection();
    var world = operation.world().request();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        owner,
                        Long.toString(selection.target().gameDesignVersionRowId()),
                        null,
                        world.appliedCommitId(),
                        "WORLD_MANAGEMENT".equals(owner) ? worldContentDigest : "c".repeat(64),
                        "AUTOMATION_SCRIPTING".equals(owner)
                            ? automationDigestSchemaVersion
                            : AuthoredWorldReleaseAttestationEvidence
                                .supportedParticipantDigestSchema(
                                    owner,
                                    AuthoredWorldReleaseAttestationEvidence
                                        .SELECTED_FULL_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null,
                        null,
                        null))
            .toList();
    return new GameDesignPublicationTerminalEvidence.ReleaseContent(
        selection.intent().canonicalTenantId(),
        selection.intent().canonicalVersionId(),
        "fixture-bundle",
        1,
        "v4",
        world.publishWorkflowId(),
        "sha256:" + "a".repeat(64),
        1,
        List.of(),
        List.of(),
        participants,
        List.of(),
        "generation-1",
        operation.world());
  }
}
