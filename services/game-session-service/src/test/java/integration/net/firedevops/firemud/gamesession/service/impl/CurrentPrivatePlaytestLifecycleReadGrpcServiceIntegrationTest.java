package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.ReadState;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec.Request;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.CatalogConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.InvalidCatalogEvidenceException;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.Current;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.InitializationMode;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Preparation;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.SourceReference;
import net.firedevops.firemud.gamesession.security.CurrentPrivatePlaytestLifecycleReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.CurrentPrivatePlaytestLifecycleReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** In-process proof of the standalone, manually composed Account-to-Game-Session source read. */
class CurrentPrivatePlaytestLifecycleReadGrpcServiceIntegrationTest {
  private static final String NAMESPACE = "current-private-read-test";
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void grpcReadReturnsExactCurrentSnapshotAndExplicitAbsence() throws Exception {
    GameSessionCurrentPrivatePlaytestRepository repository = mock();
    Current current = current();
    Scope scope =
        new Scope(NAMESPACE, current.selection().scope().tenantId(), "world-one", "realm-one");
    when(repository.readCurrent(NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug()))
        .thenReturn(Optional.of(current), Optional.empty());
    var guard = new CurrentPrivatePlaytestLifecycleReadWorkloadGuard(NAMESPACE);
    var service =
        new CurrentPrivatePlaytestLifecycleReadGrpcService(
            repository, passThroughTransaction(), NAMESPACE);
    ServerInterceptor peerIdentity = peerIdentityInterceptor(NAMESPACE);
    String serverName = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.interceptForward(service, peerIdentity, guard))
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    try {
      var stub =
          CurrentPrivatePlaytestLifecycleReadServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(5, TimeUnit.SECONDS);
      Request first = new Request(UUID.randomUUID(), scope);
      var firstResponse =
          stub.readCurrentPrivatePlaytestLifecycle(
              CurrentPrivatePlaytestLifecycleReadGrpcCodec.toRequest(first));
      var evidence =
          CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(first, firstResponse);
      assertThat(evidence.current()).isPresent();
      assertThat(evidence.current().orElseThrow().scope()).isEqualTo(scope);
      assertThat(evidence.current().orElseThrow().playtestLifecycleId())
          .isEqualTo(current.selectedPrepSnapshot().preparation().playtestLifecycleId());
      assertThat(evidence.current().orElseThrow().readState()).isEqualTo(ReadState.INCOMPLETE);
      assertThat(evidence.current().orElseThrow().privatePolicyId())
          .isEqualTo(
              current.selectedPrepSnapshot().preparation().source().targetPolicy().policyId());

      Request second = new Request(UUID.randomUUID(), scope);
      var secondResponse =
          stub.readCurrentPrivatePlaytestLifecycle(
              CurrentPrivatePlaytestLifecycleReadGrpcCodec.toRequest(second));
      assertThat(
              CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(second, secondResponse)
                  .current())
          .isEmpty();
      verify(repository, times(2))
          .readCurrent(NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug());
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void missingAccountPeerIsRejectedBeforeRequestParsingOrOwnerRead() {
    GameSessionCurrentPrivatePlaytestRepository repository = mock();
    var service =
        new CurrentPrivatePlaytestLifecycleReadGrpcService(
            repository, passThroughTransaction(), NAMESPACE);
    StreamObserver<ReadCurrentPrivatePlaytestLifecycleResponse> observer = mock();

    service.readCurrentPrivatePlaytestLifecycle(null, observer);

    ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
    org.mockito.Mockito.verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(repository);
  }

  @Test
  void authenticatedPlayerContextIsRejectedBeforeOwnerRead() {
    GameSessionCurrentPrivatePlaytestRepository repository = mock();
    var service =
        new CurrentPrivatePlaytestLifecycleReadGrpcService(
            repository, passThroughTransaction(), NAMESPACE);
    StreamObserver<ReadCurrentPrivatePlaytestLifecycleResponse> observer = mock();
    var peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service")
            .orElseThrow();
    SessionContext.setContext("player-context", List.of(), Map.of());

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.readCurrentPrivatePlaytestLifecycle(null, observer));

    ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
    org.mockito.Mockito.verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void accountSelectorMustNameThisGameSessionNamespace() {
    GameSessionCurrentPrivatePlaytestRepository repository = mock();
    var service =
        new CurrentPrivatePlaytestLifecycleReadGrpcService(
            repository, passThroughTransaction(), NAMESPACE);
    StreamObserver<ReadCurrentPrivatePlaytestLifecycleResponse> observer = mock();
    var peer = accountPeer(NAMESPACE);
    Request selector =
        new Request(
            UUID.randomUUID(),
            new Scope(
                "another-game-session-namespace", UUID.randomUUID(), "world-one", "realm-one"));

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(
            () ->
                service.readCurrentPrivatePlaytestLifecycle(
                    CurrentPrivatePlaytestLifecycleReadGrpcCodec.toRequest(selector), observer));

    ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
    org.mockito.Mockito.verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void ambientTransactionIsRejectedBeforeOwnerRead() {
    GameSessionCurrentPrivatePlaytestRepository repository = mock();
    var service =
        new CurrentPrivatePlaytestLifecycleReadGrpcService(
            repository, passThroughTransaction(), NAMESPACE);
    StreamObserver<ReadCurrentPrivatePlaytestLifecycleResponse> observer = mock();
    Request selector =
        new Request(
            UUID.randomUUID(), new Scope(NAMESPACE, UUID.randomUUID(), "world-one", "realm-one"));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      Context.current()
          .withValue(GrpcPeerIdentity.CONTEXT_KEY, accountPeer(NAMESPACE))
          .run(
              () ->
                  service.readCurrentPrivatePlaytestLifecycle(
                      CurrentPrivatePlaytestLifecycleReadGrpcCodec.toRequest(selector), observer));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
    org.mockito.Mockito.verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @MethodSource("retainedCatalogFailures")
  void retainedCatalogEvidenceFailuresReturnFailedPrecondition(RuntimeException catalogFailure)
      throws Exception {
    GameSessionCurrentPrivatePlaytestRepository repository = mock();
    Scope scope = new Scope(NAMESPACE, UUID.randomUUID(), "world-one", "realm-one");
    when(repository.readCurrent(NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug()))
        .thenThrow(catalogFailure);
    var service =
        new CurrentPrivatePlaytestLifecycleReadGrpcService(
            repository, passThroughTransaction(), NAMESPACE);
    var guard = new CurrentPrivatePlaytestLifecycleReadWorkloadGuard(NAMESPACE);
    String serverName = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.interceptForward(
                    service, peerIdentityInterceptor(NAMESPACE), guard))
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    try {
      var stub =
          CurrentPrivatePlaytestLifecycleReadServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(5, TimeUnit.SECONDS);
      Request selector = new Request(UUID.randomUUID(), scope);
      assertThatThrownBy(
              () ->
                  stub.readCurrentPrivatePlaytestLifecycle(
                      CurrentPrivatePlaytestLifecycleReadGrpcCodec.toRequest(selector)))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure -> {
                assertThat(failure.getStatus().getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION);
                assertThat(failure.getStatus().getDescription())
                    .isEqualTo(
                        "Current private-playtest source does not match its retained evidence");
              });
      verify(repository)
          .readCurrent(NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug());
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static Stream<RuntimeException> retainedCatalogFailures() {
    return Stream.of(
        new CatalogConflictException("private retained catalog identity conflict"),
        new InvalidCatalogEvidenceException("private retained catalog evidence mismatch"));
  }

  private static TransactionOperations passThroughTransaction() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
        return action.doInTransaction(new SimpleTransactionStatus());
      }
    };
  }

  private static ServerInterceptor peerIdentityInterceptor(String namespace) {
    var peer = accountPeer(namespace);
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
          io.grpc.ServerCall<ReqT, RespT> call,
          io.grpc.Metadata headers,
          io.grpc.ServerCallHandler<ReqT, RespT> next) {
        return Contexts.interceptCall(
            Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer), call, headers, next);
      }
    };
  }

  private static GrpcPeerIdentity accountPeer(String namespace) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/account-service")
        .orElseThrow();
  }

  private static Current current() {
    UUID tenantId = UUID.randomUUID();
    UUID selectionRequestId = UUID.randomUUID();
    UUID preparationRequestId = UUID.randomUUID();
    UUID lifecycleId = UUID.randomUUID();
    UUID playableNamespaceId = UUID.randomUUID();
    UUID catalogRealmId = UUID.randomUUID();
    UUID catalogRequestId = UUID.randomUUID();
    UUID sourceIntakeId = UUID.randomUUID();
    UUID canonicalVersionId = UUID.randomUUID();
    Instant selectedAt = Instant.parse("2026-11-03T00:00:00Z");
    Instant observedAt = selectedAt.plusSeconds(1);
    Instant forkExpiresAt = selectedAt.plusSeconds(3600);
    GameSessionCurrentPrivatePlaytestRepository.Scope scope =
        new GameSessionCurrentPrivatePlaytestRepository.Scope(
            NAMESPACE, tenantId, "world-one", "realm-one");
    GameSessionCurrentPrivatePlaytestRepository.Request selectionRequest =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            selectionRequestId, scope, preparationRequestId, lifecycleId, null, null);
    GameSessionCurrentPrivatePlaytestRepository.SelectionReceipt selection =
        new GameSessionCurrentPrivatePlaytestRepository.SelectionReceipt(
            selectionRequest, 1L, selectionRequest.requestDigest(), selectedAt);
    GameSessionInitialPlaytestLifecycleRepository.Request preparationRequest =
        new GameSessionInitialPlaytestLifecycleRepository.Request(
            preparationRequestId,
            NAMESPACE,
            tenantId,
            "world-one",
            "realm-one",
            catalogRealmId,
            catalogRequestId,
            1L,
            sourceIntakeId,
            canonicalVersionId,
            "release-bundle:private-playtest",
            DIGEST,
            InitializationMode.FRESH,
            forkExpiresAt);
    RealmEntryPolicy policy = privatePolicy();
    UUID policyId = UUID.randomUUID();
    var policySource =
        new RealmPolicySource.Policy(
            UUID.randomUUID(), UUID.randomUUID(), "private-policy-revision", policy);
    var publishedPolicy = new RealmPolicyPublishedEvidence.Policy(policyId, policySource, DIGEST);
    SourceReference source =
        new SourceReference(
            catalogRealmId,
            catalogRequestId,
            1L,
            DIGEST,
            sourceIntakeId,
            DIGEST,
            UUID.randomUUID(),
            DIGEST,
            canonicalVersionId,
            preparationRequest.publishedReleaseBundleRef(),
            preparationRequest.publishedReleaseBundleDigest(),
            DIGEST,
            DIGEST,
            publishedPolicy);
    Preparation preparation =
        new Preparation(
            preparationRequest,
            lifecycleId,
            playableNamespaceId,
            1L,
            "PREPARING",
            source,
            preparationRequest.requestDigest(),
            selectedAt);
    GameSessionInitialPlaytestLifecycleRepository.Snapshot preparationSnapshot =
        new GameSessionInitialPlaytestLifecycleRepository.Snapshot(
            preparation,
            GameSessionInitialPlaytestLifecycleRepository.ReadState.INCOMPLETE,
            observedAt);
    return new Current(selection, preparationSnapshot);
  }

  private static RealmEntryPolicy privatePolicy() {
    try {
      String json =
          new String(
              Rfc8785CanonicalJson.canonicalizeUtf8(
                  "{\"entryPolicy\":\"PRESEEDED_ONLY\",\"realmDisplayName\":\"Private Realm\",\"realmSlug\":\"realm-one\",\"publicProduction\":false,\"schemaVersion\":1,\"stateScope\":\"ISOLATED\",\"visible\":true,\"worldDisplayName\":\"World One\",\"worldSlug\":\"world-one\"}"),
              java.nio.charset.StandardCharsets.UTF_8);
      return RealmEntryPolicy.parseCanonical(json, JsonMapper.builder().build());
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("Controlled private policy fixture is invalid", invalid);
    }
  }
}
