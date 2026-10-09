package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
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
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structural transport units; the original Account/World inputs below are test-only stipulations.
 */
class GameDesignPublicationTerminalReadGrpcCodecTest {
  @Test
  void preservesCompleteOriginalTerminalBytesAndExactRetryForBothOutcomes() throws Exception {
    var request = request();
    assertThat(
            GameDesignPublicationTerminalReadGrpcCodec.fromRequest(
                GameDesignPublicationTerminalReadGrpcCodec.toRequest(request)))
        .isEqualTo(request);
    for (Outcome outcome : Outcome.values()) {
      var terminal = terminal(request.operation(), outcome);
      var response = GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, terminal);
      var first = GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, response);
      var retry = GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, response);
      assertThat(first.request()).isEqualTo(request);
      assertThat(first.terminalEvidence().outcome()).isEqualTo(outcome);
      assertThat(first.terminalEvidence().canonicalBytes())
          .containsExactly(terminal.canonicalBytes());
      assertThat(retry.terminalEvidence().canonicalBytes())
          .containsExactly(terminal.canonicalBytes());
    }
  }

  @Test
  void rejectsUnknownUnsupportedMalformedAndNoncanonicalRequests() throws Exception {
    var wire = GameDesignPublicationTerminalReadGrpcCodec.toRequest(request());
    for (ReadPublicationTerminalRequest invalid :
        List.of(
            wire.toBuilder().setUnknownFields(unknown()).build(),
            wire.toBuilder().setSchemaVersion(2).build(),
            wire.toBuilder().setTargetNamespace("Test").build(),
            wire.toBuilder().setReadRequestId("00000000-0000-0000-0000-000000000000").build(),
            wire.toBuilder().setReadRequestId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").build(),
            wire.toBuilder().setReadRequestId("1-1-1-1-1").build(),
            wire.toBuilder().setOriginalOperation(ByteString.EMPTY).build(),
            wire.toBuilder().setOriginalOperation(ByteString.copyFrom(new byte[] {1})).build(),
            wire.toBuilder()
                .setOriginalOperation(
                    wire.getOriginalOperation().concat(ByteString.copyFrom(new byte[] {0})))
                .build())) {
      assertThatThrownBy(() -> GameDesignPublicationTerminalReadGrpcCodec.fromRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsEveryEchoSubstitutionUnknownAndMissingMalformedOrChangedTerminal() throws Exception {
    var request = request();
    var terminal = terminal(request.operation(), Outcome.NO_PUBLICATION);
    var response = GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, terminal);
    var changed = terminal(operation(), Outcome.NO_PUBLICATION);
    for (var invalid :
        List.of(
            response.toBuilder().setSchemaVersion(2).build(),
            response.toBuilder().setTargetNamespace("other").build(),
            response.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build(),
            response.toBuilder()
                .setOriginalOperation(ByteString.copyFrom(changed.operationBytes()))
                .build(),
            response.toBuilder().setUnknownFields(unknown()).build(),
            response.toBuilder().clearTerminalEvidence().build(),
            response.toBuilder().setTerminalEvidence(ByteString.copyFrom(new byte[] {1})).build(),
            response.toBuilder()
                .setTerminalEvidence(ByteString.copyFrom(changed.canonicalBytes()))
                .build(),
            response.toBuilder()
                .setTerminalEvidence(
                    response.getTerminalEvidence().concat(ByteString.copyFrom(new byte[] {0})))
                .build())) {
      assertThatThrownBy(
              () -> GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, changed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  static GameDesignPublicationTerminalReadEvidence.Request request() throws Exception {
    return GameDesignPublicationTerminalReadEvidence.Request.create("test", operation());
  }

  static GameDesignPublicationOperationBinding operation() throws Exception {
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
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
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
                u ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        u.owner().name(),
                        u.aggregateType(),
                        u.aggregateId(),
                        u.scopeType(),
                        u.scopeId(),
                        u.expectedEpoch()))
            .toList();
    String workflow =
        PublicationDigestRequestBinding.full(
                target.canonicalTenantId().toString(),
                Long.toString(target.gameDesignVersionRowId()),
                intent.publishRequestId())
            .derivedWorkflowIdentity();
    var result = JsonMapper.builder().build().readTree(applied.result());
    var appliedOperation =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.FrameReader(
            Base64.getDecoder().decode(result.get("operationBytesBase64").textValue()));
    // Retain the actual intake identity at frame 13 of the existing APPLIED operation.
    appliedOperation.expect("world-draft-terminal-operation/v1");
    for (int frame = 1; frame < 13; frame++) {
      appliedOperation.bytes();
    }
    UUID intakeRequestId = UUID.fromString(appliedOperation.text());
    var worldRequest =
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
            3,
            tuples);
    var world =
        new WorldPublishedStartLocationEvidence(
            worldRequest,
            Base64.getDecoder().decode(result.get("startLocationReceiptBase64").textValue()),
            applied.fullBinding(),
            applied.result());
    return new GameDesignPublicationOperationBinding(
        account,
        world,
        net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures.stipulated(
            account, world));
  }

  static GameDesignPublicationTerminalEvidence terminal(
      GameDesignPublicationOperationBinding operation, Outcome outcome) {
    if (outcome == Outcome.NO_PUBLICATION) {
      return new GameDesignPublicationTerminalEvidence(
          operation.canonicalBytes(), outcome, null, null);
    }
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
                        "WORLD_MANAGEMENT".equals(owner) ? world.contentDigest() : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null,
                        null,
                        null))
            .toList();
    var release =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            selection.intent().canonicalTenantId(),
            selection.intent().canonicalVersionId(),
            "fixture-bundle",
            1,
            "v2",
            world.publishWorkflowId(),
            "sha256:" + "a".repeat(64),
            1,
            List.of(),
            List.of(),
            participants,
            List.of(),
            "generation-1",
            operation.world());
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(), outcome, release, 6L);
  }
}
