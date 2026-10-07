package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationService;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationTerminalReadGrpcService;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalRequest;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalResponse;
import org.junit.jupiter.api.Test;

/** Adapter proof only: owner source and peer identity are labeled isolated doubles. */
class GameDesignPublicationTerminalReadGrpcServiceTest {
  @Test
  void rejectsMissingWrongServiceNamespaceAndEndUserBeforeDecodeOrOwnerRead() {
    var owner = mock(GameDesignPublicationOperationService.class);
    var handler = new GameDesignPublicationTerminalReadGrpcService(owner, "test");
    var malformed =
        ReadGameDesignPublicationTerminalRequest.newBuilder()
            .setCanonicalRequestBytes(ByteString.copyFromUtf8("malformed"))
            .build();
    assertThat(call(handler, malformed, null).error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(handler, malformed, peer("game-session-service", "test")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(handler, malformed, peer("account-service", "other")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    try {
      assertThat(call(handler, malformed, peer("account-service", "test")).error)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(owner);
  }

  @Test
  void malformedAuthenticatedInputCannotReadOwnerStorage() {
    var owner = mock(GameDesignPublicationOperationService.class);
    var handler = new GameDesignPublicationTerminalReadGrpcService(owner, "test");
    assertThat(
            call(
                    handler,
                    ReadGameDesignPublicationTerminalRequest.getDefaultInstance(),
                    peer("world-management-service", "test"))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void exactAllowedPeersReceiveOnlyUnknownPendingOrUnchangedStoredNoPublication() throws Exception {
    var operation = operation();
    var request =
        GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
            "test", operation.canonicalBytes());
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION,
            null,
            null);
    for (Optional<GameDesignPublicationOperationRepository.Readback> stored :
        List.of(
            Optional.<GameDesignPublicationOperationRepository.Readback>empty(),
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(operation, "PENDING", null)),
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    operation, "NO_PUBLICATION", new byte[] {1}, terminal.canonicalBytes())))) {
      var owner = mock(GameDesignPublicationOperationService.class);
      when(owner.readExact(any())).thenReturn(stored);
      var handler = new GameDesignPublicationTerminalReadGrpcService(owner, "test");
      for (String service : List.of("account-service", "world-management-service")) {
        var response =
            call(
                handler,
                GameDesignPublicationTerminalReadGrpcCodec.toRequest(request),
                peer(service, "test"));
        assertThat(response.error).isNull();
        var result =
            GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, response.response);
        if (stored.isEmpty() || stored.orElseThrow().outcome().equals("PENDING")) {
          assertThat(result.status())
              .isEqualTo(GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN);
          assertThat(result.terminalEvidence()).isEmpty();
        } else
          assertThat(result.terminalEvidence().orElseThrow().canonicalBytes())
              .isEqualTo(terminal.canonicalBytes());
      }
    }
  }

  @Test
  void unavailableCorruptAndHistoricalOwnerReadCannotInventUnknownOrTerminalEvidence()
      throws Exception {
    var operation = operation();
    var request =
        GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
            "test", operation.canonicalBytes());
    var owner = mock(GameDesignPublicationOperationService.class);
    var handler = new GameDesignPublicationTerminalReadGrpcService(owner, "test");
    when(owner.readExact(any()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    operation, "NO_PUBLICATION", new byte[] {1})));
    var historical =
        call(
            handler,
            GameDesignPublicationTerminalReadGrpcCodec.toRequest(request),
            peer("account-service", "test"));
    assertThat(historical.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(historical.response).isNull();
    doThrow(new org.jooq.exception.DataAccessException("ISOLATED unavailable"))
        .when(owner)
        .readExact(any());
    assertThat(
            call(
                    handler,
                    GameDesignPublicationTerminalReadGrpcCodec.toRequest(request),
                    peer("account-service", "test"))
                .error)
        .isEqualTo(Status.Code.UNAVAILABLE);
    doThrow(new IllegalStateException("ISOLATED conflicting evidence"))
        .when(owner)
        .readExact(any());
    assertThat(
            call(
                    handler,
                    GameDesignPublicationTerminalReadGrpcCodec.toRequest(request),
                    peer("account-service", "test"))
                .error)
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  static GameDesignPublicationOperation operation() throws Exception {
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

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static Collector call(
      GameDesignPublicationTerminalReadGrpcService handler,
      ReadGameDesignPublicationTerminalRequest request,
      GrpcPeerIdentity peer) {
    var collector = new Collector();
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var previous = context.attach();
    try {
      handler.readGameDesignPublicationTerminal(request, collector);
    } finally {
      context.detach(previous);
    }
    return collector;
  }

  private static final class Collector
      implements StreamObserver<ReadGameDesignPublicationTerminalResponse> {
    private ReadGameDesignPublicationTerminalResponse response;
    private Status.Code error;

    @Override
    public void onNext(ReadGameDesignPublicationTerminalResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {}
  }
}
