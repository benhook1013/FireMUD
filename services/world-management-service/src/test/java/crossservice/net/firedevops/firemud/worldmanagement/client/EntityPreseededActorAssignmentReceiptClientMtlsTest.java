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
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentReceiptStatus;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controlled Entity socket proof; it does not establish an actual Entity database receipt. */
class EntityPreseededActorAssignmentReceiptClientMtlsTest {
  private static final String NAMESPACE = "firemud";
  private static final String EXPECTED_WORLD_URI =
      "spiffe://firemud/ns/firemud/sa/world-management-service";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID GAME_INSTANCE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID VERSION = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID PLAYABLE_NAMESPACE = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID OPERATION = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ACCOUNT = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID CHARACTER = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID ASSIGNMENT = uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

  @Test
  void authenticatesExactEntityServerAndPreservesReceiptFields(@TempDir Path temporary)
      throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            temporary,
            "World Entity receipt client test CA",
            identity("entity", "firemud", "entity-management-service", true),
            identity("wrong-server", "other", "entity-management-service", true),
            identity("world", "firemud", "world-management-service", false),
            identity("wrong-client", "firemud", "account-service", false),
            identity("wrong-namespace-client", "other", "world-management-service", false));
    var expected = new AtomicReference<>(receipt(request()));
    var calls = new AtomicInteger();
    Server server = startServer(pki, "entity", expected, calls);
    try {
      try (var client = client(pki, temporary, "world", server.getPort())) {
        client.init();
        assertThat(client.read(request())).isEqualTo(expected.get());
        assertThat(calls).hasValue(1);

        expected.set(
            expected.get().toBuilder()
                .setCharacterUuid(uuid("12121212-1212-4212-8212-121212121212").toString())
                .build());
        assertThatThrownBy(() -> client.read(request()))
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
  void rejectsWrongEntityServerAndWrongWorldClientCertificate(@TempDir Path temporary)
      throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            temporary,
            "World Entity receipt peer test CA",
            identity("entity", "firemud", "entity-management-service", true),
            identity("wrong-server", "other", "entity-management-service", true),
            identity("world", "firemud", "world-management-service", false),
            identity("wrong-client", "firemud", "account-service", false),
            identity("wrong-namespace-client", "other", "world-management-service", false));
    var expected = new AtomicReference<>(receipt(request()));
    var calls = new AtomicInteger();

    Server wrongServer = startServer(pki, "wrong-server", expected, calls);
    try {
      try (var client = client(pki, temporary, "world", wrongServer.getPort())) {
        client.init();
        assertThatThrownBy(() -> client.read(request()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
      }
    } finally {
      stopServer(wrongServer);
    }

    assertThatThrownBy(() -> client(pki, temporary, "wrong-client", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact same-namespace World workload certificate");
    assertThatThrownBy(() -> client(pki, temporary, "wrong-namespace-client", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact same-namespace World workload certificate");
    assertThat(calls).hasValue(0);
  }

  private static Server startServer(
      LoopbackMtlsTestPki.Pki pki,
      String serverAlias,
      AtomicReference<ReadPreseededActorAssignmentReceiptResponse> response,
      AtomicInteger calls)
      throws Exception {
    var serverIdentity = pki.identity(serverAlias);
    var service =
        new CanonicalPreseededActorAssignmentServiceGrpc
            .CanonicalPreseededActorAssignmentServiceImplBase() {
          @Override
          public void readPreseededActorAssignmentReceipt(
              ReadPreseededActorAssignmentReceiptRequest request,
              StreamObserver<ReadPreseededActorAssignmentReceiptResponse> observer) {
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
            var current = response.get();
            if (current == null) {
              observer.onError(
                  Status.FAILED_PRECONDITION
                      .withDescription("Controlled Entity owner read unavailable")
                      .asRuntimeException());
              return;
            }
            observer.onNext(current);
            observer.onCompleted();
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

  private static EntityPreseededActorAssignmentReceiptClient client(
      LoopbackMtlsTestPki.Pki pki, Path temporary, String alias, int port) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + port);
    return new EntityPreseededActorAssignmentReceiptClient(
        endpoints,
        pki.clientProperties(temporary, pki.identity(alias)),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static ReadPreseededActorAssignmentReceiptRequest request() {
    return ReadPreseededActorAssignmentReceiptRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(OPERATION.toString())
        .setAssignmentUuid(ASSIGNMENT.toString())
        .setCanonicalAccountUuid(ACCOUNT.toString())
        .setCharacterUuid(CHARACTER.toString())
        .setIntentDigest("a".repeat(64))
        .setExpectedTarget(target())
        .build();
  }

  private static ReadPreseededActorAssignmentReceiptResponse receipt(
      ReadPreseededActorAssignmentReceiptRequest request) {
    return ReadPreseededActorAssignmentReceiptResponse.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(request.getPlacementOperationUuid())
        .setAssignmentUuid(request.getAssignmentUuid())
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setCharacterUuid(request.getCharacterUuid())
        .setIntentDigest(request.getIntentDigest())
        .setExpectedTarget(request.getExpectedTarget())
        .setCorePayload(
            PreseededActorAssignmentCorePayload.newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Test Player")
                .build())
        .setStatus(
            PreseededActorAssignmentReceiptStatus
                .PRESEEDED_ACTOR_ASSIGNMENT_RECEIPT_STATUS_ASSIGNED)
        .build();
  }

  private static PreseededActorAssignmentExpectedTarget target() {
    return PreseededActorAssignmentExpectedTarget.newBuilder()
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
        .build();
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
