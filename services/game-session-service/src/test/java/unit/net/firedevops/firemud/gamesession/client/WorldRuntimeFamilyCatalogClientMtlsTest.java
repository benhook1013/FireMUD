package unit.net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.gamesession.client.WorldRuntimeFamilyCatalogClient;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldRuntimeFamilyCatalogReadServiceGrpc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Physical mTLS client/codec proof with controlled source bytes and local test trust. The handler
 * does not establish an installed owner catalog, production PKI, global enrollment or FRESH state.
 */
@Timeout(60)
class WorldRuntimeFamilyCatalogClientMtlsTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private static final String NAMESPACE = "catalog-test";

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Runtime catalog test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "client", uri("game-session-service", NAMESPACE), false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "world", uri("world-management-service", NAMESPACE), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-role", uri("entity-management-service", NAMESPACE), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign-namespace", uri("world-management-service", "other"), true));
  }

  @Test
  void actualPeerQualifiedClientPreservesExactEvidenceCorrelationAndFiniteDeadline()
      throws Exception {
    Request request = new Request(UUID.randomUUID(), NAMESPACE);
    Evidence expected = evidence(request);
    var observed = new AtomicReference<Request>();
    var peer = new AtomicReference<String>();
    var remaining = new AtomicLong();
    var service =
        new WorldRuntimeFamilyCatalogReadServiceGrpc
            .WorldRuntimeFamilyCatalogReadServiceImplBase() {
          @Override
          public void readWorldRuntimeFamilyCatalog(
              ReadWorldRuntimeFamilyCatalogRequest wire,
              StreamObserver<ReadWorldRuntimeFamilyCatalogResponse> observer) {
            peer.set(GrpcPeerIdentity.current().uri());
            remaining.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
            observed.set(WorldRuntimeFamilyCatalogCodec.fromRequest(wire));
            observer.onNext(WorldRuntimeFamilyCatalogCodec.toResponse(request, expected));
            observer.onCompleted();
          }
        };
    Server server = server("world", service);
    try (var client = client(server)) {
      assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
      client.init();
      Evidence result = client.read(request);
      assertThat(result).isEqualTo(expected);
      assertThat(result.catalog().exactBytes()).isEqualTo(expected.catalog().exactBytes());
      assertThat(result.digest()).isEqualTo(expected.digest());
      assertThat(observed.get()).isEqualTo(request);
      assertThat(peer.get()).isEqualTo(uri("game-session-service", NAMESPACE));
      assertThat(remaining.get()).isBetween(1L, 5000L);
    } finally {
      stop(server);
    }
  }

  @Test
  void sameCaWrongWorldRoleAndForeignNamespaceRefuseBeforeHandler() throws Exception {
    for (String identity : List.of("wrong-role", "foreign-namespace")) {
      var calls = new AtomicInteger();
      Server server =
          server(
              identity,
              new WorldRuntimeFamilyCatalogReadServiceGrpc
                  .WorldRuntimeFamilyCatalogReadServiceImplBase() {
                @Override
                public void readWorldRuntimeFamilyCatalog(
                    ReadWorldRuntimeFamilyCatalogRequest request,
                    StreamObserver<ReadWorldRuntimeFamilyCatalogResponse> observer) {
                  calls.incrementAndGet();
                  observer.onError(Status.INTERNAL.asRuntimeException());
                }
              });
      try (var client = client(server)) {
        client.init();
        assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                error ->
                    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(calls).hasValue(0);
      } finally {
        stop(server);
      }
    }
  }

  @Test
  void changedCorrelationDuplicateIncompleteAndOversizedEvidenceNeverBecomeSourceInput()
      throws Exception {
    Request request = new Request(UUID.randomUUID(), NAMESPACE);
    // Build a response with one internally consistent, but different, request.
    Request other = new Request(UUID.randomUUID(), NAMESPACE);
    var wrong = WorldRuntimeFamilyCatalogCodec.toResponse(other, evidence(other));
    var valid = WorldRuntimeFamilyCatalogCodec.toResponse(request, evidence(request));
    var duplicate =
        valid.toBuilder().addCanonicalResponseBytes(valid.getCanonicalResponseBytes(0)).build();
    var oversized =
        valid.toBuilder()
            .setCanonicalResponseBytes(
                0,
                com.google.protobuf.ByteString.copyFrom(
                    new byte[WorldRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES + 1]))
            .build();
    for (ReadWorldRuntimeFamilyCatalogResponse response :
        List.of(
            wrong,
            ReadWorldRuntimeFamilyCatalogResponse.getDefaultInstance(),
            duplicate,
            oversized)) {
      Server server = server("world", returning(response));
      try (var client = client(server)) {
        client.init();
        assertThatThrownBy(() -> client.read(request))
            .isInstanceOfAny(IllegalArgumentException.class, StatusRuntimeException.class);
      } finally {
        stop(server);
      }
    }
  }

  @Test
  void unavailableAndWrongLocalNamespaceAreRefusalNotEmptyCatalog() throws Exception {
    var calls = new AtomicInteger();
    Server server =
        server(
            "world",
            new WorldRuntimeFamilyCatalogReadServiceGrpc
                .WorldRuntimeFamilyCatalogReadServiceImplBase() {
              @Override
              public void readWorldRuntimeFamilyCatalog(
                  ReadWorldRuntimeFamilyCatalogRequest request,
                  StreamObserver<ReadWorldRuntimeFamilyCatalogResponse> observer) {
                calls.incrementAndGet();
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
              }
            });
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), "other")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(calls).hasValue(0);
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      assertThat(calls).hasValue(0);
      assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
      assertThat(calls).hasValue(1);
    } finally {
      stop(server);
    }
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldRuntimeFamilyCatalogClient(
                    new ServiceEndpointsProperties(), tls, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldRuntimeFamilyCatalogClient client(Server server) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("localhost:" + server.getPort());
    return new WorldRuntimeFamilyCatalogClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("client")),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static Server server(
      String identity,
      WorldRuntimeFamilyCatalogReadServiceGrpc.WorldRuntimeFamilyCatalogReadServiceImplBase service)
      throws Exception {
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            pki.keyManagerFactory(pki.identity(identity)),
            pki.trustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    factory.addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()));
    return factory.createServer().start();
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static WorldRuntimeFamilyCatalogReadServiceGrpc
          .WorldRuntimeFamilyCatalogReadServiceImplBase
      returning(ReadWorldRuntimeFamilyCatalogResponse response) {
    return new WorldRuntimeFamilyCatalogReadServiceGrpc
        .WorldRuntimeFamilyCatalogReadServiceImplBase() {
      @Override
      public void readWorldRuntimeFamilyCatalog(
          ReadWorldRuntimeFamilyCatalogRequest request,
          StreamObserver<ReadWorldRuntimeFamilyCatalogResponse> observer) {
        observer.onNext(response);
        observer.onCompleted();
      }
    };
  }

  private static Evidence evidence(Request request) {
    String json =
        """
        {"schema":"world-runtime-family-catalog/v1","owner":"world-management-service","catalogEpoch":"1",
         "families":[{"familyId":"world_instance","relation":"world_instance","component":"",
          "role":"LIFECYCLE","stateClass":"S3","freshDisposition":"REQUIRED_LIFECYCLE"}],
         "schemaFacts":[{"kind":"TABLE","identity":"world_instance",
          "definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
        """;
    return new Evidence(
        request,
        UUID.randomUUID(),
        WorldRuntimeFamilyCatalog.fromStored(json.getBytes(StandardCharsets.UTF_8)));
  }

  private static String uri(String service, String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }
}
