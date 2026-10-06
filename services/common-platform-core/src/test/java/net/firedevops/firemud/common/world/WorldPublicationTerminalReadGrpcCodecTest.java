package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadEvidence.ReadResult;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadEvidence.Request;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadEvidence.Status;
import org.junit.jupiter.api.Test;

/** Stipulated GD terminal inputs test carrier integrity, never committed World owner state. */
class WorldPublicationTerminalReadGrpcCodecTest {
  @Test
  void preservesExactExpectedEvidenceAndRequestForUnknownPublishedAndAborted() throws Exception {
    for (boolean published : List.of(true, false)) {
      var request = request(published);
      assertThat(
              WorldPublicationTerminalReadGrpcCodec.fromRequest(
                      WorldPublicationTerminalReadGrpcCodec.toRequest(request))
                  .canonicalBytes())
          .isEqualTo(request.canonicalBytes());
      assertThat(request.terminalEvidence().canonicalBytes())
          .isEqualTo(request.expectedTerminalEvidenceBytes());
      for (var result :
          List.of(new ReadResult(request, Status.UNKNOWN, Optional.empty()), definitive(request))) {
        var decoded =
            WorldPublicationTerminalReadGrpcCodec.fromResponse(
                request, WorldPublicationTerminalReadGrpcCodec.toResponse(request, result));
        assertThat(decoded.canonicalBytes()).isEqualTo(result.canonicalBytes());
        assertThat(decoded.status()).isEqualTo(result.status());
      }
      byte[] escaped = request.expectedTerminalEvidenceBytes();
      escaped[0] = 99;
      assertThat(request.expectedTerminalEvidenceBytes()).isNotEqualTo(escaped);
    }
  }

  @Test
  void rejectsUnsupportedTrailingMixedOutcomeChangedTerminalAndEchoIdentity() throws Exception {
    var request = request(false);
    var result = definitive(request);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                WorldPublicationTerminalReadGrpcCodec.fromRequest(
                    WorldPublicationTerminalReadGrpcCodec.toRequest(request).toBuilder()
                        .setUnknownFields(unknown)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublicationTerminalReadGrpcCodec.fromResponse(
                    request,
                    WorldPublicationTerminalReadGrpcCodec.toResponse(request, result).toBuilder()
                        .setUnknownFields(unknown)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Request.fromStored(
                    Arrays.copyOf(request.canonicalBytes(), request.canonicalBytes().length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ReadResult.fromStored(
                    Arrays.copyOf(result.canonicalBytes(), result.canonicalBytes().length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Request(2, "test", UUID.randomUUID(), request.expectedTerminalEvidenceBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Request(1, "test", new UUID(0, 0), request.expectedTerminalEvidenceBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Request(1, "other", UUID.randomUUID(), request.expectedTerminalEvidenceBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Request(
                    1,
                    "test",
                    request.terminalEvidence().worldEvidence().request().publicationFence(),
                    request.expectedTerminalEvidenceBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadResult(request, Status.UNKNOWN, result.terminalEvidence()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadResult(request, Status.PUBLISHED, result.terminalEvidence()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadResult(request, Status.ABORTED, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ReadResult(request, Status.ABORTED, Optional.of(changedTerminal(request))))
        .isInstanceOf(IllegalArgumentException.class);
    var changedRead =
        new Request(1, "test", UUID.randomUUID(), request.expectedTerminalEvidenceBytes());
    assertThatThrownBy(
            () ->
                WorldPublicationTerminalReadGrpcCodec.fromResponse(
                    request,
                    WorldPublicationTerminalReadGrpcCodec.toResponse(
                        changedRead, definitive(changedRead))))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] malformed = request.canonicalBytes();
    malformed[0] = (byte) 0xff;
    assertThatThrownBy(() -> Request.fromStored(malformed))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Request.fromStored(request.terminalEvidence().canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static ReadResult definitive(Request request) {
    return new ReadResult(
        request,
        request.terminalEvidence().outcome()
                == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
            ? Status.PUBLISHED
            : Status.ABORTED,
        Optional.of(request.terminalEvidence()));
  }

  static GameDesignPublicationTerminalEvidence changedTerminal(Request request) {
    var operation =
        GameDesignPublicationOperationBinding.fromStored(
            request.terminalEvidence().operationBytes());
    var account = operation.account();
    var changed =
        new GameDesignPublicationOperationBinding(
            new AccountPublicationAuthorizationBinding(
                UUID.randomUUID(), account.fenceId(), account.input(), account.sources()),
            operation.world());
    return new GameDesignPublicationTerminalEvidence(
        changed.canonicalBytes(),
        GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION,
        null,
        null);
  }

  static Request request(boolean published) throws Exception {
    var seed = WorldPublishedStartLocationGrpcCodecTest.evidence();
    var original = DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
    var draft =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
            original.inputDigest());
    var target = draft.target();
    String requestId = "world-publication-terminal-test";
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                requestId,
                "5",
                "ISOLATED fixed notes",
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
                requestId,
                selected.digest().substring(7),
                5,
                PublicationDigestRequestBinding.full(
                        target.canonicalTenantId().toString(),
                        Long.toString(target.gameDesignVersionRowId()),
                        requestId)
                    .derivedWorkflowIdentity(),
                r.appliedCommitId(),
                r.contentDigest(),
                r.digestSchemaVersion(),
                r.worldAffectedTuples()),
            seed.selectorReceiptBytes(),
            seed.originalAccountBindingBytes(),
            seed.appliedResultBytes());
    var operation = new GameDesignPublicationOperationBinding(account, world);
    GameDesignPublicationTerminalEvidence.ReleaseContent content = null;
    if (published) {
      String digest = "sha256:" + "a".repeat(64);
      var participants =
          AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
              .map(
                  owner ->
                      new GameDesignPublicationTerminalEvidence.Participant(
                          owner,
                          Long.toString(target.gameDesignVersionRowId()),
                          null,
                          r.appliedCommitId(),
                          owner.equals("WORLD_MANAGEMENT") ? r.contentDigest() : "c".repeat(64),
                          AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                              owner),
                          owner.equals("GAME_LOGIC") ? digest : null,
                          null,
                          null))
              .toList();
      content =
          new GameDesignPublicationTerminalEvidence.ReleaseContent(
              target.canonicalTenantId(),
              target.canonicalVersionId(),
              "ISOLATED-bundle",
              1,
              "v2",
              world.request().publishWorkflowId(),
              digest,
              1,
              List.of(),
              List.of(),
              participants,
              List.of("LOOK"),
              "generation-1",
              world);
    }
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            published
                ? GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
                : GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION,
            content,
            published ? 6L : null);
    return new Request(
        1,
        "test",
        UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
        terminal.canonicalBytes());
  }
}
