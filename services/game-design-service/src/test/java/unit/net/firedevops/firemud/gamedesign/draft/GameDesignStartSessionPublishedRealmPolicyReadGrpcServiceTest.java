package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.StartSessionPublishedRealmPolicyReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.publication.StartSessionPublishedRealmPolicyReadService;
import net.firedevops.firemud.gamedesign.publication.StartSessionTemplateAssociationReadService;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionPublishedRealmPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionPublishedRealmPolicyResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Transport denial and ordering proofs; no mocked success is treated as owner producer evidence.
 */
class GameDesignStartSessionPublishedRealmPolicyReadGrpcServiceTest {
  private static final String NAMESPACE = "test";

  private final StartSessionTemplateAssociationReadService associationReader =
      mock(StartSessionTemplateAssociationReadService.class);
  private final StartSessionPublishedRealmPolicyReadService publishedPolicyReader =
      mock(StartSessionPublishedRealmPolicyReadService.class);
  private final GameDesignStartSessionPublishedRealmPolicyReadGrpcService service =
      new GameDesignStartSessionPublishedRealmPolicyReadGrpcService(
          associationReader, publishedPolicyReader, NAMESPACE);

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void authenticatesExactSameNamespacePeerBeforeDecoding() {
    var observer = new Collector();

    try (MockedStatic<StartSessionPublishedRealmPolicyReadGrpcCodec> codec =
        Mockito.mockStatic(StartSessionPublishedRealmPolicyReadGrpcCodec.class)) {
      service.readStartSessionPublishedRealmPolicy(
          ReadStartSessionPublishedRealmPolicyRequest.getDefaultInstance(), observer);
      codec.verifyNoInteractions();
    }

    assertThat(observer.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(associationReader, publishedPolicyReader);
  }

  @Test
  void rejectsOtherWorkloadBeforeDecodingOrOwnerReads() {
    var observer =
        invokeAs(
            ReadStartSessionPublishedRealmPolicyRequest.getDefaultInstance(), "account-service");

    assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(observer.value).isNull();
    verifyNoInteractions(associationReader, publishedPolicyReader);
  }

  @Test
  void rejectsAuthenticatedUserContextBeforeDecodingOrOwnerReads() {
    SessionContext.setContext("123", java.util.List.of(), java.util.Map.of());

    Collector observer;
    try (MockedStatic<StartSessionPublishedRealmPolicyReadGrpcCodec> codec =
        Mockito.mockStatic(StartSessionPublishedRealmPolicyReadGrpcCodec.class)) {
      observer =
          invokeAs(
              ReadStartSessionPublishedRealmPolicyRequest.getDefaultInstance(),
              "game-session-service");
      codec.verifyNoInteractions();
    }

    assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(observer.value).isNull();
    verifyNoInteractions(associationReader, publishedPolicyReader);
  }

  @Test
  void AccountDenialReturnsNoContentAndMakesZeroPublishedSourceOrPolicyReads() {
    var request =
        ReadStartSessionPublishedRealmPolicyRequest.newBuilder()
            .setTargetNamespace(NAMESPACE)
            .build();
    var authorizationRequest = mock(StartSessionTemplateAssociationReadEvidence.Request.class);
    var wireRequest =
        new StartSessionPublishedRealmPolicyReadGrpcCodec.WireRequest(
            request,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            authorizationRequest,
            new DraftCommitBinding.TargetProof(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                17L,
                "tenant-key",
                42L,
                "tenant-key",
                "NEW_GAME_ROW"));
    when(associationReader.read(authorizationRequest))
        .thenThrow(Status.PERMISSION_DENIED.withDescription("denied").asRuntimeException());

    try (MockedStatic<StartSessionPublishedRealmPolicyReadGrpcCodec> codec =
        Mockito.mockStatic(StartSessionPublishedRealmPolicyReadGrpcCodec.class)) {
      codec
          .when(() -> StartSessionPublishedRealmPolicyReadGrpcCodec.fromRequest(request))
          .thenReturn(wireRequest);

      var observer = invokeAs(request, "game-session-service");

      assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
      assertThat(observer.value).isNull();
      assertThat(observer.completed).isFalse();
      Mockito.verify(associationReader).read(authorizationRequest);
      verifyNoInteractions(publishedPolicyReader);
    }
  }

  @Test
  void AccountUnavailableReturnsNoContentAndMakesZeroPublishedSourceOrPolicyReads() {
    var request =
        ReadStartSessionPublishedRealmPolicyRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .build();
    var authorizationRequest = mock(StartSessionTemplateAssociationReadEvidence.Request.class);
    var wireRequest = wireRequest(request, authorizationRequest);
    when(associationReader.read(authorizationRequest))
        .thenThrow(Status.UNAVAILABLE.withDescription("Account unavailable").asRuntimeException());

    try (MockedStatic<StartSessionPublishedRealmPolicyReadGrpcCodec> codec =
        Mockito.mockStatic(StartSessionPublishedRealmPolicyReadGrpcCodec.class)) {
      codec
          .when(() -> StartSessionPublishedRealmPolicyReadGrpcCodec.fromRequest(request))
          .thenReturn(wireRequest);

      var observer = invokeAs(request, "game-session-service");

      assertThat(observer.error).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(observer.value).isNull();
      assertThat(observer.completed).isFalse();
      Mockito.verify(associationReader).read(authorizationRequest);
      verifyNoInteractions(publishedPolicyReader);
    }
  }

  @Test
  void mismatchedAccountConfirmedOriginalRequestMakesZeroPublishedSourceOrPolicyReads() {
    var request =
        ReadStartSessionPublishedRealmPolicyRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .build();
    var authorizationRequest = mock(StartSessionTemplateAssociationReadEvidence.Request.class);
    var wireRequest = wireRequest(request, authorizationRequest);
    var mismatchedRequest = mock(StartSessionTemplateAssociationReadEvidence.Request.class);
    var authorizationResult = mock(StartSessionTemplateAssociationReadEvidence.Result.class);
    when(authorizationResult.request()).thenReturn(mismatchedRequest);
    when(associationReader.read(authorizationRequest)).thenReturn(authorizationResult);

    try (MockedStatic<StartSessionPublishedRealmPolicyReadGrpcCodec> codec =
        Mockito.mockStatic(StartSessionPublishedRealmPolicyReadGrpcCodec.class)) {
      codec
          .when(() -> StartSessionPublishedRealmPolicyReadGrpcCodec.fromRequest(request))
          .thenReturn(wireRequest);

      var observer = invokeAs(request, "game-session-service");

      assertThat(observer.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(observer.value).isNull();
      assertThat(observer.completed).isFalse();
      Mockito.verify(associationReader).read(authorizationRequest);
      verifyNoInteractions(publishedPolicyReader);
    }
  }

  private static StartSessionPublishedRealmPolicyReadGrpcCodec.WireRequest wireRequest(
      ReadStartSessionPublishedRealmPolicyRequest request,
      StartSessionTemplateAssociationReadEvidence.Request authorizationRequest) {
    return new StartSessionPublishedRealmPolicyReadGrpcCodec.WireRequest(
        request,
        UUID.fromString("77777777-7777-4777-8777-777777777777"),
        authorizationRequest,
        new DraftCommitBinding.TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            17L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW"));
  }

  private Collector invokeAs(ReadStartSessionPublishedRealmPolicyRequest request, String workload) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + workload, NAMESPACE, workload);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      var observer = new Collector();
      service.readStartSessionPublishedRealmPolicy(request, observer);
      return observer;
    } finally {
      context.detach(previous);
    }
  }

  private static final class Collector
      implements StreamObserver<ReadStartSessionPublishedRealmPolicyResponse> {
    private ReadStartSessionPublishedRealmPolicyResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadStartSessionPublishedRealmPolicyResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
