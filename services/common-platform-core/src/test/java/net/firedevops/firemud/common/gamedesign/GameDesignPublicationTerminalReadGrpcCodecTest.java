package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadResult;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.Status;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalRequest;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalResponse;
import org.junit.jupiter.api.Test;

class GameDesignPublicationTerminalReadGrpcCodecTest {
  @Test
  void exactRequestAndTypedUnknownAndTerminalRoundTripWithoutInventingPublication()
      throws Exception {
    var request = request();
    assertThat(
            GameDesignPublicationTerminalReadGrpcCodec.fromRequest(
                    GameDesignPublicationTerminalReadGrpcCodec.toRequest(request))
                .canonicalBytes())
        .isEqualTo(request.canonicalBytes());
    for (ReadResult result :
        List.of(
            new ReadResult(request, Status.UNKNOWN, Optional.empty()), noPublication(request))) {
      var restored =
          GameDesignPublicationTerminalReadGrpcCodec.fromResponse(
              request, GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, result));
      assertThat(restored.canonicalBytes()).isEqualTo(result.canonicalBytes());
      assertThat(restored.terminalEvidence().isEmpty())
          .isEqualTo(result.status() == Status.UNKNOWN);
    }
  }

  @Test
  void rejectsUnknownTrailingMalformedNoncanonicalMixedOutcomeAndRequestSubstitution()
      throws Exception {
    var request = request();
    var terminal = noPublication(request);
    var unsupported =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalReadGrpcCodec.fromRequest(
                    GameDesignPublicationTerminalReadGrpcCodec.toRequest(request).toBuilder()
                        .setUnknownFields(unsupported)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalReadGrpcCodec.fromResponse(
                    request,
                    GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, terminal)
                        .toBuilder()
                        .setUnknownFields(unsupported)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ReadRequest.fromStored(
                    Arrays.copyOf(request.canonicalBytes(), request.canonicalBytes().length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ReadResult.fromStored(
                    Arrays.copyOf(terminal.canonicalBytes(), terminal.canonicalBytes().length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ReadRequest(2, "test", UUID.randomUUID(), request.operationBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadRequest(1, "test", new UUID(0, 0), request.operationBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ReadRequest(1, "other", UUID.randomUUID(), request.operationBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalReadGrpcCodec.fromRequest(
                    ReadGameDesignPublicationTerminalRequest.newBuilder()
                        .setCanonicalRequestBytes(ByteString.copyFrom(new byte[] {1}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadResult(request, Status.UNKNOWN, terminal.terminalEvidence()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadResult(request, Status.PUBLISHED, terminal.terminalEvidence()))
        .isInstanceOf(IllegalArgumentException.class);
    var changed =
        new ReadRequest(1, request.targetNamespace(), UUID.randomUUID(), request.operationBytes());
    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalReadGrpcCodec.fromResponse(
                    request,
                    GameDesignPublicationTerminalReadGrpcCodec.toResponse(
                        changed, noPublication(changed))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalReadGrpcCodec.fromResponse(
                    request, ReadGameDesignPublicationTerminalResponse.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] noncanonical = request.canonicalBytes();
    noncanonical[0] = (byte) 0xff;
    assertThatThrownBy(() -> ReadRequest.fromStored(noncanonical))
        .isInstanceOf(IllegalArgumentException.class);
    var op = GameDesignPublicationOperationBinding.fromStored(request.operationBytes());
    var a = op.account();
    var changedOp =
        new GameDesignPublicationOperationBinding(
            new AccountPublicationAuthorizationBinding(
                UUID.randomUUID(), a.fenceId(), a.input(), a.sources()),
            op.world());
    var changedRequest = new ReadRequest(1, "test", UUID.randomUUID(), changedOp.canonicalBytes());
    assertThatThrownBy(
            () ->
                new ReadResult(changedRequest, Status.NO_PUBLICATION, terminal.terminalEvidence()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static ReadRequest request() throws Exception {
    var seed = AuthoredWorldReleaseAttestationSelectorTest.selectorEvidence();
    var original = DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
    var draft =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
            original.inputDigest());
    var target = draft.target();
    String request = "publication-terminal-test";
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                request,
                "5",
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
                5,
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
    return new ReadRequest(
        1,
        "test",
        UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
        new GameDesignPublicationOperationBinding(account, world).canonicalBytes());
  }

  static ReadResult noPublication(ReadRequest request) {
    return new ReadResult(
        request,
        Status.NO_PUBLICATION,
        Optional.of(
            new GameDesignPublicationTerminalEvidence(
                request.operationBytes(),
                GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION,
                null,
                null)));
  }
}
