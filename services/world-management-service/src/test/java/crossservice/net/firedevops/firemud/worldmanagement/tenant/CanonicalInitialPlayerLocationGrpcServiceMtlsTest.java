package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Outcome;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Result;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled inbound socket proof; it does not establish a genuine World owner transaction. */
@Timeout(60)
class CanonicalInitialPlayerLocationGrpcServiceMtlsTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID GAME_INSTANCE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID VERSION = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID PLAYABLE_NAMESPACE = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID OPERATION = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @AfterEach
  void clearRequestContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void bothRpcMethodsRequireTheExactSameNamespaceGameSessionCertificate(@TempDir Path temporary)
      throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            temporary,
            "World initial location owner boundary CA",
            identity("world", "firemud", "world-management-service", true),
            identity("wrong-service", "firemud", "account-service", false),
            identity("wrong-namespace", "other", "game-session-service", false),
            identity("game-session", "firemud", "game-session-service", false));
    var service = mock(CanonicalInitialPlayerLocationService.class);
    var retained = retainedConflict();
    Metadata.Key<String> secretKey =
        Metadata.Key.of("upstream-secret", Metadata.ASCII_STRING_MARSHALLER);
    Metadata trailers = new Metadata();
    trailers.put(secretKey, "secret owner trailer");
    var upstreamFailure =
        Status.UNAVAILABLE
            .withDescription("secret owner detail")
            .withCause(new IllegalStateException("secret owner cause"))
            .asRuntimeException(trailers);
    when(service.place(any()))
        .thenReturn(retained)
        .thenThrow(upstreamFailure)
        .thenThrow(Status.NOT_FOUND.withDescription("secret Entity absence").asRuntimeException());
    when(service.read(any())).thenReturn(Optional.of(retained), Optional.empty());
    Server server = startServer(pki, service);
    try {
      ManagedChannel channel = channel(pki, "game-session", server.getPort());
      try {
        var stub =
            CanonicalInitialPlayerLocationServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);
        var request = request();
        var placed = stub.placeInitialPlayer(request);
        assertThat(placed.getOutcome())
            .isEqualTo(
                CanonicalInitialPlayerLocationOutcome
                    .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_CONFLICT);
        assertThat(placed.getConflictCode()).isEqualTo("CHARACTER_ALREADY_PLACED");
        var read = stub.readInitialPlayerLocation(request);
        assertThat(read).isEqualTo(placed);
        assertThatThrownBy(() -> stub.readInitialPlayerLocation(request))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.NOT_FOUND));
        assertThatThrownBy(() -> stub.placeInitialPlayer(request))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure -> {
                  var status = Status.fromThrowable(failure);
                  assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
                  assertThat(status.getDescription())
                      .isEqualTo("World initial-player placement is unavailable");
                  assertThat(status.getCause()).isNull();
                  Metadata responseTrailers = ((StatusRuntimeException) failure).getTrailers();
                  assertThat(responseTrailers == null || !responseTrailers.containsKey(secretKey))
                      .isTrue();
                });
        assertThatThrownBy(() -> stub.placeInitialPlayer(request))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure -> {
                  var status = Status.fromThrowable(failure);
                  assertThat(status.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
                  assertThat(status.getDescription())
                      .isEqualTo("Exact World initial-placement owner evidence is unavailable");
                  assertThat(status.getCause()).isNull();
                  Metadata responseTrailers = ((StatusRuntimeException) failure).getTrailers();
                  assertThat(responseTrailers == null || !responseTrailers.containsKey(secretKey))
                      .isTrue();
                });
        verify(service, times(3))
            .place(CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request));
        verify(service, times(2))
            .read(CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request));

        assertThatThrownBy(
                () ->
                    stub.placeInitialPlayer(
                        CanonicalInitialPlayerLocationRequest.getDefaultInstance()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
      } finally {
        stopChannel(channel);
      }

      for (String clientAlias : new String[] {"wrong-service", "wrong-namespace"}) {
        ManagedChannel rejectedPeerChannel = channel(pki, clientAlias, server.getPort());
        try {
          var stub =
              CanonicalInitialPlayerLocationServiceGrpc.newBlockingStub(rejectedPeerChannel)
                  .withDeadlineAfter(10, TimeUnit.SECONDS);
          for (boolean read : new boolean[] {false, true}) {
            assertThatThrownBy(
                    () -> {
                      if (read) stub.readInitialPlayerLocation(request());
                      else stub.placeInitialPlayer(request());
                    })
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(
                    failure ->
                        assertThat(Status.fromThrowable(failure).getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));
          }
        } finally {
          stopChannel(rejectedPeerChannel);
        }
      }
    } finally {
      stopServer(server);
    }
    verifyNoMoreInteractions(service);
  }

  private static Server startServer(
      LoopbackMtlsTestPki.Pki pki, CanonicalInitialPlayerLocationService owner) throws Exception {
    var identity = pki.identity("world");
    var grpcService = new CanonicalInitialPlayerLocationGrpcService(NAMESPACE, owner);
    var interceptor = new CanonicalInitialPlayerLocationPeerInterceptor(NAMESPACE);
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            pki.keyManagerFactory(identity),
            pki.trustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    factory.addService(ServerInterceptors.intercept(grpcService, interceptor));
    return factory.createServer().start();
  }

  private static ManagedChannel channel(LoopbackMtlsTestPki.Pki pki, String alias, int port)
      throws Exception {
    var identity = pki.identity(alias);
    return NettyChannelBuilder.forAddress("localhost", port)
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forClient()
                        .trustManager(pki.caCertificate())
                        .keyManager(identity.privateKey(), identity.certificate()))
                .build())
        .build();
  }

  private static CanonicalInitialPlayerLocationRequest request() {
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(OPERATION.toString())
        .setExpectedTarget(
            PreseededActorAssignmentExpectedTarget.newBuilder()
                .setCanonicalTenantUuid(TENANT.toString())
                .setRealmUuid(REALM.toString())
                .setWorldSlug("green-hollow")
                .setRealmSlug("sunvale")
                .setGameInstanceId(GAME_INSTANCE.toString())
                .setCatalogRevision(17L)
                .setCanonicalVersionUuid(VERSION.toString())
                .setFrozenPolicyDigest("d".repeat(64))
                .setPlayableStateNamespaceUuid(PLAYABLE_NAMESPACE.toString())
                .setPublishedReleaseBundleRef("published/green-hollow/42")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .build())
        .setCanonicalAccountUuid(uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString())
        .setCharacterUuid(uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc").toString())
        .setAssignmentUuid(uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString())
        .setAssignmentIntentDigest("a".repeat(64))
        .setInitialAdmissionHoldUuid(uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee").toString())
        .setInitialAdmissionHoldFence(uuid("ffffffff-ffff-4fff-8fff-ffffffffffff").toString())
        .setInitialAdmissionRequestId("initial-admission-41")
        .setInitialAdmissionRequestDigest("b".repeat(64))
        .setActiveLifecycleEpoch(7L)
        .setTerminalProofDigest("sha256:" + "c".repeat(64))
        .setCommittedPointerVersion(1L)
        .setAuditEventId(41L)
        .build();
  }

  private static Result retainedConflict() {
    return new Result(
        Outcome.CONFLICT,
        "CHARACTER_ALREADY_PLACED",
        null,
        null,
        new byte[] {1},
        "sha256:" + "a".repeat(64),
        new byte[] {2},
        "sha256:" + "b".repeat(64),
        true);
  }

  private static IdentitySpec identity(
      String alias, String namespace, String service, boolean server) {
    return new IdentitySpec(alias, "spiffe://firemud/ns/" + namespace + "/sa/" + service, server);
  }

  private static void stopServer(Server server) throws Exception {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static void stopChannel(ManagedChannel channel) throws Exception {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
