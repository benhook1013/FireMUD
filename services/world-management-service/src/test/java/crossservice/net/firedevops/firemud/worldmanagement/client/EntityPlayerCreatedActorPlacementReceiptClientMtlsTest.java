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
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRuntimeReferenceServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorOrigin;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controlled Entity socket proof; it does not establish an actual Entity database receipt. */
class EntityPlayerCreatedActorPlacementReceiptClientMtlsTest {
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
        assertThat(client.read(request()).response()).isEqualTo(expected.get());
        assertThat(calls).hasValue(1);

        expected.set(
            expected.get().toBuilder()
                .setSelector(request().toBuilder().setAccountUuid(uuid("12121212-1212-4212-8212-121212121212").toString()))
                .build());
        assertThatThrownBy(() -> client.read(request()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
        var exact = receipt(request());
        for (var malformed : java.util.List.of(
            exact.toBuilder().setSelector(request().toBuilder().setGameInstanceUuid(ACCOUNT.toString())).build(),
            exact.toBuilder().setSelector(request().toBuilder().setOrigin(request().getOrigin().toBuilder().setMutationDigest("f".repeat(64)))).build(),
            exact.toBuilder().setProjectionFence(0L).build(),
            exact.toBuilder().mergeUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder()
                .addField(99, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1L).build()).build()).build())) {
          expected.set(malformed);
          assertThatThrownBy(() -> client.read(request())).isInstanceOf(StatusRuntimeException.class)
              .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode())
                  .isEqualTo(Status.Code.FAILED_PRECONDITION));
        }
        int beforeInvalid = calls.get();
        assertThatThrownBy(() -> client.read(request().toBuilder().setSchemaVersion(2).build()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.read(request().toBuilder().setOrigin(
            request().getOrigin().toBuilder().setTargetNamespace("other")).build()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(beforeInvalid);
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
      AtomicReference<PlayerCreatedActorRuntimeReferenceResponse> response,
      AtomicInteger calls)
      throws Exception {
    var serverIdentity = pki.identity(serverAlias);
    var service =
        new CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
            .CanonicalPlayerCreatedRuntimeReferenceServiceImplBase() {
          @Override
          public void readPlayerCreatedActorPlacementReceipt(
              PlayerCreatedActorRuntimeReferenceRequest request,
              StreamObserver<PlayerCreatedActorRuntimeReferenceResponse> observer) {
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

  private static EntityPlayerCreatedActorPlacementReceiptClient client(
      LoopbackMtlsTestPki.Pki pki, Path temporary, String alias, int port) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + port);
    return new EntityPlayerCreatedActorPlacementReceiptClient(
        endpoints,
        pki.clientProperties(temporary, pki.identity(alias)),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static PlayerCreatedActorRuntimeReferenceRequest request() {
    return PlayerCreatedActorRuntimeReferenceRequest.newBuilder()
        .setSchemaVersion(1)
        .setOrigin(PlayerCreatedActorOrigin.newBuilder().setTargetNamespace(NAMESPACE)
            .setCreationOperationUuid(OPERATION.toString()).setMutationDigest("a".repeat(64))
            .setTerminalUuid(ASSIGNMENT.toString()).setTerminalSha256("b".repeat(64)))
        .setTenantUuid(TENANT.toString()).setAccountUuid(ACCOUNT.toString())
        .setPlayableStateNamespaceUuid(PLAYABLE_NAMESPACE.toString())
        .setGameInstanceUuid(GAME_INSTANCE.toString()).setCharacterUuid(CHARACTER.toString())
        .setCanonicalVersionUuid(VERSION.toString()).setRealmUuid(REALM.toString())
        .setWorldSlug("green-hollow").setRealmSlug("sunvale").build();
  }

  private static PlayerCreatedActorRuntimeReferenceResponse receipt(
      PlayerCreatedActorRuntimeReferenceRequest request) {
    return PlayerCreatedActorRuntimeReferenceResponse.newBuilder()
        .setSchemaVersion(1).setSelector(request)
        .setRuntimeEntityId(23L).setProjectionGeneration(1L).setProjectionFence(1L)
        .setOriginalCatalogRevision(3L).setOriginalPointerVersion(1L).setOriginalActiveLifecycleEpoch(2L)
        .setOriginalDescriptorDigest("sha256:" + "c".repeat(64))
        .setOriginalAuthoredBindingDigest("sha256:" + "d".repeat(64))
        .setOriginalPolicyContentDigest("sha256:" + "e".repeat(64)).build();
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
