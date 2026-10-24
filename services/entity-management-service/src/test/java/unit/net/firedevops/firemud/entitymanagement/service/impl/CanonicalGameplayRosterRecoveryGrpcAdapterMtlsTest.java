package net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository.RecoveryConflictException;
import net.firedevops.firemud.entitymanagement.security.CanonicalGameplayRosterPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRecoveryService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native loopback mTLS of the unregistered recovery adapter; owner/storage proof is separate. */
class CanonicalGameplayRosterRecoveryGrpcAdapterMtlsTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private final CanonicalGameplayRosterRecoveryService recovery =
      mock(CanonicalGameplayRosterRecoveryService.class);
  private Server server;
  private Server ownerServer;
  private final AtomicInteger ownerReads = new AtomicInteger();

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Entity roster recovery CA",
            new IdentitySpec(
                "entity", "spiffe://firemud/ns/gameplay/sa/entity-management-service", true),
            new IdentitySpec("gs", "spiffe://firemud/ns/gameplay/sa/game-session-service", false),
            new IdentitySpec(
                "gs-owner", "spiffe://firemud/ns/gameplay/sa/game-session-service", true),
            new IdentitySpec(
                "entity-owner-client",
                "spiffe://firemud/ns/gameplay/sa/entity-management-service",
                false),
            new IdentitySpec("foreign", "spiffe://firemud/ns/other/sa/game-session-service", false),
            new IdentitySpec("account", "spiffe://firemud/ns/gameplay/sa/account-service", false));
  }

  @AfterEach
  void stop() throws Exception {
    SessionContext.clear();
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    if (ownerServer != null) {
      ownerServer.shutdownNow();
      assertThat(ownerServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void exactGameSessionCertificateCarriesExactRecoveryRequestAndOriginalResponse()
      throws Exception {
    var request = request();
    var expected =
        CanonicalGameplayRosterRecoveryEvidence.response(
            request, UUID.randomUUID(), 1, UUID.randomUUID(), null);
    when(recovery.recover(request))
        .thenAnswer(
            ignored -> {
              assertThat(CanonicalGameplayRosterPeerInterceptor.currentVerifiedPeer()).isNotNull();
              assertThat(
                      CanonicalGameplayRosterPeerInterceptor.currentVerifiedPeer()
                          .isService("game-session-service"))
                  .isTrue();
              assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
              return expected;
            });
    start();
    var channel = channel("gs");
    try {
      assertThat(
              CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
                  .withDeadlineAfter(5, TimeUnit.SECONDS)
                  .recoverOriginalSelection(request))
          .isEqualTo(expected);
    } finally {
      close(channel);
    }
  }

  @Test
  void wrongNamespaceAndServiceCannotReachRecoveryHandler() throws Exception {
    start();
    for (String alias : new String[] {"foreign", "account"}) {
      var channel = channel(alias);
      try {
        assertThatThrownBy(
                () ->
                    CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .recoverOriginalSelection(request()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
      } finally {
        close(channel);
      }
    }
    verifyNoInteractions(recovery);
  }

  @Test
  void localConflictReturnsBoundedFailureWithoutTransportSuccess() throws Exception {
    when(recovery.recover(any()))
        .thenThrow(new RecoveryConflictException("CANONICAL_ROSTER_RECOVERY_CONFLICT"));
    start();
    var channel = channel("gs");
    try {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .recoverOriginalSelection(request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
    } finally {
      close(channel);
    }
  }

  @Test
  void invalidPersistedRecoveryStateReturnsUnavailableToAuthenticatedGameSession()
      throws Exception {
    when(recovery.recover(any()))
        .thenThrow(
            new IllegalStateException(
                "CANONICAL_ROSTER_RECOVERY_RECEIPT_INVALID",
                new IllegalArgumentException("Stored response shape is invalid")));
    start();
    var channel = channel("gs");
    try {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .recoverOriginalSelection(request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> {
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE);
                assertThat(Status.fromThrowable(failure).getDescription())
                    .isEqualTo("Original selection recovery unavailable");
              });
    } finally {
      close(channel);
    }
  }

  @Test
  void malformedCallerRequestStillReturnsInvalidArgumentOverAuthenticatedTransport()
      throws Exception {
    var malformed = request().toBuilder().setSchemaVersion(0).build();
    when(recovery.recover(malformed))
        .thenThrow(new IllegalArgumentException("Invalid request schema"));
    start();
    var channel = channel("gs");
    try {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .recoverOriginalSelection(malformed))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.INVALID_ARGUMENT));
    } finally {
      close(channel);
    }
  }

  @Test
  void malformedAuthenticatedOwnerUsesActualClientAndReturnsUnavailableBeforeSql()
      throws Exception {
    var request = request();
    startOwner(original -> owner(request).toBuilder().setOwnerFence(0).build());
    var repository = mock(CanonicalGameplayRosterRetentionRepository.class);
    var transactions = mock(TransactionOperations.class);
    try (var ownerClient = ownerClient()) {
      ownerClient.init();
      start(
          new CanonicalGameplayRosterRecoveryService(
              repository, ownerClient, transactions, policy()));
      assertRecoveryStatus(request, Status.Code.UNAVAILABLE);
      assertThat(ownerReads.get()).isEqualTo(1);
      verifyNoInteractions(repository, transactions);
    }
  }

  @Test
  void authenticatedOwnerRecoveryMismatchUsesActualClientAndReturnsConflictBeforeSql()
      throws Exception {
    var request = request();
    startOwner(
        original ->
            owner(request).toBuilder().setRecoveryUuid(UUID.randomUUID().toString()).build());
    var repository = mock(CanonicalGameplayRosterRetentionRepository.class);
    var transactions = mock(TransactionOperations.class);
    try (var ownerClient = ownerClient()) {
      ownerClient.init();
      start(
          new CanonicalGameplayRosterRecoveryService(
              repository, ownerClient, transactions, policy()));
      assertRecoveryStatus(request, Status.Code.FAILED_PRECONDITION);
      assertThat(ownerReads.get()).isEqualTo(1);
      verifyNoInteractions(repository, transactions);
    }
  }

  @Test
  void postValidationPersistedArgumentFailureReturnsUnavailableThroughActualOwnerClient()
      throws Exception {
    var request = request();
    var owner = owner(request);
    startOwner(original -> owner);
    // SQL is a controlled double here; the authenticated owner/client/service/adapter chain is
    // real.
    var repository = mock(CanonicalGameplayRosterRetentionRepository.class);
    when(repository.closeAdmission(request, owner, policy()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
              throw new IllegalArgumentException("Invalid retained original roster receipt");
            });
    try (var ownerClient = ownerClient()) {
      ownerClient.init();
      start(
          new CanonicalGameplayRosterRecoveryService(
              repository, ownerClient, localTransactions(), policy()));
      assertRecoveryStatus(request, Status.Code.UNAVAILABLE);
      assertThat(ownerReads.get()).isEqualTo(1);
      verify(repository).closeAdmission(request, owner, policy());
    }
  }

  @Test
  void malformedCallerAndForeignRequestNamespaceRemainInvalidBeforeActualOwnerTransport()
      throws Exception {
    var request = request();
    startOwner(original -> owner(request));
    var repository = mock(CanonicalGameplayRosterRetentionRepository.class);
    var transactions = mock(TransactionOperations.class);
    try (var ownerClient = ownerClient()) {
      ownerClient.init();
      start(
          new CanonicalGameplayRosterRecoveryService(
              repository, ownerClient, transactions, policy()));
      assertRecoveryStatus(
          request.toBuilder().setSchemaVersion(0).build(), Status.Code.INVALID_ARGUMENT);
      var foreign =
          request.toBuilder()
              .setOriginalSelection(
                  request.getOriginalSelection().toBuilder().setTargetNamespace("other"))
              .build();
      assertRecoveryStatus(foreign, Status.Code.INVALID_ARGUMENT);
      assertThat(ownerReads.get()).isZero();
      verifyNoInteractions(repository, transactions);
    }
  }

  private void assertRecoveryStatus(
      CanonicalGameplayRosterRecoveryRequest request, Status.Code expected) throws Exception {
    var channel = channel("gs");
    try {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .recoverOriginalSelection(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
    } finally {
      close(channel);
    }
  }

  private void startOwner(
      Function<
              GetCanonicalGameplayRosterReferenceRequest,
              GetCanonicalGameplayRosterReferenceResponse>
          response)
      throws Exception {
    var identity = pki.identity("gs-owner");
    var source =
        new CanonicalGameplayRosterOwnerReadServiceGrpc
            .CanonicalGameplayRosterOwnerReadServiceImplBase() {
          @Override
          public void getCanonicalGameplayRosterReference(
              GetCanonicalGameplayRosterReferenceRequest request,
              StreamObserver<GetCanonicalGameplayRosterReferenceResponse> observer) {
            assertThat(GrpcPeerIdentity.current().uri())
                .isEqualTo("spiffe://firemud/ns/gameplay/sa/entity-management-service");
            ownerReads.incrementAndGet();
            observer.onNext(response.apply(request));
            observer.onCompleted();
          }
        };
    ownerServer =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(source, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GameSessionCanonicalGameplayRosterOwnerReadClient ownerClient() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("127.0.0.1:" + ownerServer.getPort());
    return new GameSessionCanonicalGameplayRosterOwnerReadClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("entity-owner-client")),
        new GrpcChannelFactory(),
        "gameplay");
  }

  private static GetCanonicalGameplayRosterReferenceResponse owner(
      CanonicalGameplayRosterRecoveryRequest request) {
    return CanonicalGameplayRosterReferenceEvidence.response(
            request.getOriginalSelection(),
            1,
            UUID.fromString("12121212-1212-4212-8212-121212121212"),
            null)
        .toBuilder()
        .setRecoveryUuid(request.getRecoveryUuid())
        .build();
  }

  private static CanonicalGameplayRosterRetentionPolicy policy() {
    return new CanonicalGameplayRosterRetentionPolicy(
        Duration.ofHours(2), Duration.ofHours(1), Duration.ofHours(2), Duration.ofMinutes(1), 2);
  }

  private static TransactionOperations localTransactions() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(TransactionCallback<T> callback) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
          return callback.doInTransaction(new SimpleTransactionStatus());
        } finally {
          TransactionSynchronizationManager.clear();
        }
      }
    };
  }

  private void start() throws Exception {
    start(recovery);
  }

  private void start(CanonicalGameplayRosterRecoveryService service) throws Exception {
    var identity = pki.identity("entity");
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    new CanonicalGameplayRosterRecoveryGrpcAdapter(service),
                    new CanonicalGameplayRosterPeerInterceptor("gameplay")))
            .build()
            .start();
  }

  private ManagedChannel channel(String alias) throws Exception {
    var identity = pki.identity(alias);
    return NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(pki.caCertificate())
                .keyManager(identity.privateKey(), identity.certificate())
                .build())
        .build();
  }

  private static void close(ManagedChannel channel) throws Exception {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static CanonicalGameplayRosterRecoveryRequest request() {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setWorldSlug("world")
            .setRealmSlug("realm")
            .setCatalogRevision(1)
            .setPointerVersion(1)
            .setActiveWorldEpoch(1)
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedReleaseBundleRef("release:exact")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return CanonicalGameplayRosterRecoveryRequest.newBuilder()
        .setSchemaVersion(1)
        .setRecoveryUuid(UUID.randomUUID().toString())
        .setOriginalSelection(
            CanonicalGameplayRosterReferenceEvidence.request(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "gameplay", target))
        .build();
  }
}
