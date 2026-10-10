package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalRequest;
import net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalResponse;
import org.junit.jupiter.api.Test;

/** Stipulated peer context and mocked owner units; not TLS or physical PostgreSQL proof. */
class GameDesignPublicationTerminalReadGrpcServiceTest {
  private final GameDesignPublicationTerminalReadService owner =
      mock(GameDesignPublicationTerminalReadService.class);
  private final GameDesignPublicationTerminalReadGrpcService service =
      new GameDesignPublicationTerminalReadGrpcService(owner, "test");

  @Test
  void authenticatesBeforeMalformedRequestOrOwnerLookup() {
    assertThat(call(null, null).code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    for (String peer :
        List.of(
            "spiffe://firemud/ns/test/sa/game-session-service",
            "spiffe://firemud/ns/test/sa/game-design-service",
            "spiffe://firemud/ns/other/sa/account-service",
            "spiffe://firemud/ns/other/sa/world-management-service")) {
      assertThat(call(peer, null).code()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    verifyNoInteractions(owner);
  }

  @Test
  void malformedUnknownAndWrongNamespaceNeverReachOwner() throws Exception {
    var request = request(operation());
    for (var malformed :
        List.of(
            ReadPublicationTerminalRequest.getDefaultInstance(),
            request.toBuilder().setSchemaVersion(2).build(),
            request.toBuilder().setReadRequestId("00000000-0000-0000-0000-000000000000").build(),
            request.toBuilder().clearOriginalOperation().build(),
            request.toBuilder()
                .setOriginalOperation(com.google.protobuf.ByteString.copyFrom(new byte[] {1}))
                .build(),
            request.toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                        .build())
                .build())) {
      assertThat(call(account(), malformed).code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    assertThat(call(account(), request.toBuilder().setTargetNamespace("other").build()).code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void bothOwnerPeersReceiveExactOriginalCompleteTerminalAndRetry() throws Exception {
    var operation = operation();
    var request = request(operation);
    for (Outcome outcome : Outcome.values()) {
      var terminal = terminal(operation, outcome);
      when(owner.read(eq("test"), any())).thenReturn(terminal);
      for (String peer :
          List.of(account(), "spiffe://firemud/ns/test/sa/world-management-service")) {
        var first = call(peer, request);
        var retry = call(peer, request);
        assertThat(first.failure).isNull();
        assertThat(first.completed).isTrue();
        assertThat(first.response)
            .isEqualTo(
                GameDesignPublicationTerminalReadGrpcCodec.toResponse(
                    GameDesignPublicationTerminalReadGrpcCodec.fromRequest(request), terminal));
        assertThat(retry.response).isEqualTo(first.response);
        assertThat(first.response.getOriginalOperation().toByteArray())
            .containsExactly(operation.canonicalBytes());
        assertThat(first.response.getTerminalEvidence().toByteArray())
            .containsExactly(terminal.canonicalBytes());
      }
    }
  }

  @Test
  void ownerDenialsUnavailableMissingAndSubstitutedEvidenceCannotSucceed() throws Exception {
    var operation = operation();
    var request = request(operation);
    for (Status status :
        List.of(Status.NOT_FOUND, Status.FAILED_PRECONDITION, Status.UNAVAILABLE)) {
      doThrow(status.withDescription("private storage details").asRuntimeException())
          .when(owner)
          .read(eq("test"), any());
      var result = call(account(), request);
      assertThat(result.code()).isEqualTo(status.getCode());
      assertThat(result.failure.getDescription()).doesNotContain("private storage details");
      assertThat(result.response).isNull();
      assertThat(result.completed).isFalse();
    }
    doReturn(null).when(owner).read(eq("test"), any());
    assertThat(call(account(), request).code()).isEqualTo(Status.Code.UNAVAILABLE);
    doReturn(terminal(operation(), Outcome.NO_PUBLICATION)).when(owner).read(eq("test"), any());
    assertThat(call(account(), request).code()).isEqualTo(Status.Code.UNAVAILABLE);
  }

  private static String account() {
    return "spiffe://firemud/ns/test/sa/account-service";
  }

  private static ReadPublicationTerminalRequest request(GameDesignPublicationOperation operation) {
    return GameDesignPublicationTerminalReadGrpcCodec.toRequest(
        new GameDesignPublicationTerminalReadEvidence.Request(
            1, "test", UUID.randomUUID(), operation.canonicalBytes()));
  }

  private Capture call(String peerUri, ReadPublicationTerminalRequest request) {
    var capture = new Capture();
    Context context =
        peerUri == null
            ? Context.current()
            : Context.current()
                .withValue(
                    GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(peerUri).orElseThrow());
    Context prior = context.attach();
    try {
      service.readPublicationTerminal(request, capture);
      return capture;
    } finally {
      context.detach(prior);
    }
  }

  private static final class Capture implements StreamObserver<ReadPublicationTerminalResponse> {
    private ReadPublicationTerminalResponse response;
    private Status failure;
    private boolean completed;

    @Override
    public void onNext(ReadPublicationTerminalResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable value) {
      failure = Status.fromThrowable(value).withCause(null);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    private Status.Code code() {
      return failure.getCode();
    }
  }

  private static GameDesignPublicationOperation operation() throws Exception {
    return IsolatedPublicationOperationFixtures.fresh(
        new TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            19L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW"));
  }

  private static GameDesignPublicationTerminalEvidence terminal(
      GameDesignPublicationOperation operation, Outcome outcome) {
    if (outcome == Outcome.NO_PUBLICATION) {
      return new GameDesignPublicationTerminalEvidence(
          operation.canonicalBytes(), outcome, null, null);
    }
    var participants =
        PublishedWorldSelectorFixtures.participants(operation.versionId(), operation.world())
            .stream()
            .map(
                p ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        p.participantKey(),
                        p.scopeValue(),
                        p.baseVersionId(),
                        p.appliedCommitId(),
                        p.contentDigest(),
                        p.digestSchemaVersion(),
                        p.abilitySchemaDigest(),
                        p.errorCode(),
                        p.errorMessage()))
            .toList();
    var selection = operation.account().input().selection();
    var release =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            selection.intent().canonicalTenantId(),
            selection.intent().canonicalVersionId(),
            "retained-bundle",
            1,
            "v2",
            operation.workflowId(),
            "sha256:" + "a".repeat(64),
            1,
            List.of(),
            List.of(),
            participants,
            List.of(),
            "generation-1",
            operation.world());
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(),
        outcome,
        release,
        Math.addExact(operation.world().request().versionStateEpoch(), 1L));
  }
}
