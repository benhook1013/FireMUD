package net.firedevops.firemud.worldmanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalInitialAdmissionOwnerGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controlled Game Session socket proof; it does not claim a genuine owner database outcome. */
class GrpcGameSessionCanonicalInitialAdmissionOwnerClientMtlsTest {
  private static final String NAMESPACE = "firemud";
  private static final String EXPECTED_WORLD_URI =
      "spiffe://firemud/ns/firemud/sa/world-management-service";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final String REQUEST_DIGEST = "a".repeat(64);

  @Test
  void authenticatesExactGameSessionPeerAndPreservesTransportOrRejectsChangedResponse(
      @TempDir Path temporary) throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            temporary,
            "Canonical initial-admission owner test CA",
            identity("gs", "firemud", "game-session-service", true),
            identity("wrong-service", "firemud", "account-service", true),
            identity("wrong-namespace", "other", "game-session-service", true),
            identity("world", "firemud", "world-management-service", false),
            identity("wrong-client", "firemud", "account-service", false));
    HoldIdentity expectedIdentity = holdIdentity();
    var response = new AtomicReference<>(pendingResponse(expectedIdentity));
    var calls = new AtomicInteger();
    Server server = startServer(pki, "gs", response, calls);
    try {
      try (var client = client(pki, temporary, "world", server.getPort())) {
        client.init();
        var proof = client.read(expectedIdentity);
        assertThat(proof.outcome())
            .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
        assertThat(calls).hasValue(1);

        response.set(
            response.get().toBuilder()
                .setHoldFence(uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc").toString())
                .build());
        assertThatThrownBy(() -> client.read(expectedIdentity))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid canonical initial-admission owner proof");

        response.set(null);
        assertThatThrownBy(() -> client.read(expectedIdentity))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
      }
    } finally {
      stopServer(server);
    }
  }

  @Test
  void rejectsWrongGameSessionWorkloadServiceAndNamespace(@TempDir Path temporary)
      throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            temporary,
            "Canonical initial-admission peer test CA",
            identity("gs", "firemud", "game-session-service", true),
            identity("wrong-service", "firemud", "account-service", true),
            identity("wrong-namespace", "other", "game-session-service", true),
            identity("world", "firemud", "world-management-service", false));
    HoldIdentity identity = holdIdentity();
    var response = new AtomicReference<>(pendingResponse(identity));
    var calls = new AtomicInteger();

    for (String serverAlias : new String[] {"wrong-service", "wrong-namespace"}) {
      Server server = startServer(pki, serverAlias, response, calls);
      try {
        try (var client = client(pki, temporary, "world", server.getPort())) {
          client.init();
          assertThatThrownBy(() -> client.read(identity))
              .isInstanceOf(StatusRuntimeException.class)
              .satisfies(
                  failure ->
                      assertThat(Status.fromThrowable(failure).getCode())
                          .isEqualTo(Status.Code.UNAUTHENTICATED));
        }
      } finally {
        stopServer(server);
      }
    }
    assertThat(calls).hasValue(0);
  }

  @Test
  void preservesSameNamespaceWorldWorkloadAuthenticationAtGameSession(@TempDir Path temporary)
      throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            temporary,
            "Canonical initial-admission caller test CA",
            identity("gs", "firemud", "game-session-service", true),
            identity("world", "firemud", "world-management-service", false),
            identity("wrong-client", "firemud", "account-service", false));
    HoldIdentity identity = holdIdentity();
    var response = new AtomicReference<>(pendingResponse(identity));
    var calls = new AtomicInteger();
    Server server = startServer(pki, "gs", response, calls);
    try {
      try (var client = client(pki, temporary, "wrong-client", server.getPort())) {
        client.init();
        assertThatThrownBy(() -> client.read(identity))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
      }
      assertThat(calls).hasValue(1);
    } finally {
      stopServer(server);
    }
  }

  private static Server startServer(
      LoopbackMtlsTestPki.Pki pki,
      String serverAlias,
      AtomicReference<GetCanonicalInitialAdmissionOwnerProofResponse> response,
      AtomicInteger calls)
      throws Exception {
    var serverIdentity = pki.identity(serverAlias);
    var service =
        new CanonicalInitialAdmissionOwnerProofReadServiceGrpc
            .CanonicalInitialAdmissionOwnerProofReadServiceImplBase() {
          @Override
          public void getCanonicalInitialAdmissionOwnerProof(
              GetCanonicalInitialAdmissionOwnerProofRequest request,
              StreamObserver<GetCanonicalInitialAdmissionOwnerProofResponse> observer) {
            calls.incrementAndGet();
            var peer = GrpcPeerIdentity.current();
            if (peer == null
                || !"world-management-service".equals(peer.service())
                || !NAMESPACE.equals(peer.namespace())
                || !EXPECTED_WORLD_URI.equals(peer.uri())) {
              observer.onError(
                  Status.PERMISSION_DENIED
                      .withDescription("Exact same-namespace World caller required")
                      .asRuntimeException());
              return;
            }
            try {
              assertThat(peer.uri()).isEqualTo(EXPECTED_WORLD_URI);
              var identity = CanonicalInitialAdmissionOwnerGrpcCodec.parseRequest(request);
              var readback = response.get();
              if (readback == null) {
                observer.onError(
                    Status.FAILED_PRECONDITION
                        .withDescription("Controlled owner read unavailable")
                        .asRuntimeException());
              } else {
                assertThat(identity).isEqualTo(holdIdentity());
                observer.onNext(readback);
                observer.onCompleted();
              }
            } catch (RuntimeException invalid) {
              observer.onError(Status.INTERNAL.withCause(invalid).asRuntimeException());
            }
          }
        };
    return NettyServerBuilder.forPort(0)
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        serverIdentity.privateKey(), serverIdentity.certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static GrpcGameSessionCanonicalInitialAdmissionOwnerClient client(
      LoopbackMtlsTestPki.Pki pki, Path temporary, String clientAlias, int port) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("localhost:" + port);
    return new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
        endpoints,
        pki.clientProperties(temporary, pki.identity(clientAlias)),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static GetCanonicalInitialAdmissionOwnerProofResponse pendingResponse(
      HoldIdentity identity) {
    return CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(
        identity,
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
            null,
            null,
            null,
            false,
            null));
  }

  private static HoldIdentity holdIdentity() {
    return new HoldIdentity(
        new Request(
            NAMESPACE,
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null),
        HOLD_ID,
        HOLD_FENCE);
  }

  private static IdentitySpec identity(
      String alias, String namespace, String service, boolean server) {
    return new IdentitySpec(alias, "spiffe://firemud/ns/" + namespace + "/sa/" + service, server);
  }

  private static void stopServer(Server server) throws Exception {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
