package unit.net.firedevops.firemud.entitymanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual file-backed Entity client transport; owner responses are controlled source evidence. */
class GameSessionCanonicalGameplayRosterOwnerReadClientMtlsTest {
  private static final String NAMESPACE = "gameplay";
  private static final String ENTITY_URI =
      "spiffe://firemud/ns/gameplay/sa/entity-management-service";
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private final AtomicInteger receivedCalls = new AtomicInteger();
  private final AtomicInteger handlerReads = new AtomicInteger();
  private final AtomicReference<String> callerUri = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<GetCanonicalGameplayRosterReferenceRequest> observedRequest =
      new AtomicReference<>();
  private Server server;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Entity roster owner client test CA",
            new IdentitySpec(
                "server", "spiffe://firemud/ns/gameplay/sa/game-session-service", true),
            new IdentitySpec(
                "foreign-server", "spiffe://firemud/ns/other/sa/game-session-service", true),
            new IdentitySpec("entity", ENTITY_URI, false));
  }

  @AfterEach
  void stop() throws Exception {
    SessionContext.clear();
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(5L, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void exactServerReceivesOriginalRequestAndEntityCertificateWithoutAmbientClaims()
      throws Exception {
    start("server");
    var request = referenceRequest();
    SessionContext.setContext(
        UUID.randomUUID().toString(),
        List.of("platformAdmin"),
        Map.of(),
        true,
        "account-service",
        "ambient-instance");
    try (var client = client()) {
      client.init();
      var response = client.getCanonicalGameplayRosterReference(request);
      assertThat(response.getRequest()).isEqualTo(request);
      assertThat(observedRequest.get()).isEqualTo(request);
      assertThat(handlerReads.get()).isEqualTo(1);
      assertThat(receivedCalls.get()).isEqualTo(1);
      assertThat(callerUri.get()).isEqualTo(ENTITY_URI);
      assertThat(authorization.get()).isNull();
      assertThat(SessionContext.getServiceName()).isEqualTo("account-service");
    } finally {
      SessionContext.clear();
    }
  }

  @Test
  void foreignNamespaceServerCannotReceiveEitherOwnerReadRequest() throws Exception {
    start("foreign-server");
    try (var client = client()) {
      client.init();
      assertThatThrownBy(() -> client.getCanonicalGameplayRosterReference(referenceRequest()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThatThrownBy(() -> client.getCanonicalGameplayRosterOwnerRead(ownerRequest()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(handlerReads.get()).isZero();
      assertThat(receivedCalls.get()).isZero();
      assertThat(observedRequest.get()).isNull();
      assertThat(callerUri.get()).isNull();
    }
  }

  private void start(String alias) throws Exception {
    var identity = pki.identity(alias);
    var service =
        new CanonicalGameplayRosterOwnerReadServiceGrpc
            .CanonicalGameplayRosterOwnerReadServiceImplBase() {
          @Override
          public void getCanonicalGameplayRosterReference(
              GetCanonicalGameplayRosterReferenceRequest request,
              StreamObserver<GetCanonicalGameplayRosterReferenceResponse> responseObserver) {
            handlerReads.incrementAndGet();
            observedRequest.set(request);
            callerUri.set(GrpcPeerIdentity.current().uri());
            responseObserver.onNext(
                CanonicalGameplayRosterReferenceEvidence.response(
                    request, 1L, UUID.fromString("12121212-1212-4212-8212-121212121212"), null));
            responseObserver.onCompleted();
          }

          @Override
          public void getCanonicalGameplayRosterOwnerRead(
              GetCanonicalGameplayRosterOwnerReadRequest request,
              StreamObserver<GetCanonicalGameplayRosterOwnerReadResponse> responseObserver) {
            handlerReads.incrementAndGet();
            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
          }
        };
    ServerInterceptor captureHeaders =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            receivedCalls.incrementAndGet();
            authorization.set(
                headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
            return next.startCall(call, headers);
          }
        };
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
                    service, captureHeaders, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GameSessionCanonicalGameplayRosterOwnerReadClient client() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("127.0.0.1:" + server.getPort());
    return new GameSessionCanonicalGameplayRosterOwnerReadClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("entity")),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static GetCanonicalGameplayRosterReferenceRequest referenceRequest() {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setWorldSlug("earth")
            .setRealmSlug("main")
            .setCatalogRevision(7L)
            .setPointerVersion(11L)
            .setActiveWorldEpoch(13L)
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedReleaseBundleRef("release:exact")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return CanonicalGameplayRosterReferenceEvidence.request(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NAMESPACE, target);
  }

  private static CanonicalGameplayRosterOwnerReadEvidence.Request ownerRequest() {
    var reference = referenceRequest();
    var target = CanonicalGameplayRosterReferenceEvidence.target(reference);
    return new CanonicalGameplayRosterOwnerReadEvidence.Request(
        UUID.fromString(reference.getOperationUuid()),
        UUID.fromString(reference.getCanonicalAccountUuid()),
        NAMESPACE,
        UUID.fromString(target.getTenantUuid()),
        target.getWorldSlug(),
        UUID.fromString(target.getRealmUuid()),
        target.getRealmSlug(),
        UUID.fromString(target.getPlayableStateNamespaceUuid()),
        "SHARED",
        UUID.fromString(target.getGameInstanceUuid()),
        UUID.fromString(target.getCanonicalVersionUuid()),
        target.getCatalogRevision(),
        target.getPointerVersion(),
        target.getActiveWorldEpoch());
  }
}
