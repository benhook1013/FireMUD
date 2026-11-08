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
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.entitymanagement.v1.EntityRuntimeFamilyCatalogReadServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.gamesession.client.EntityRuntimeFamilyCatalogClient;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Physical transport with controlled local PKI/owner bytes, not a live SQL-to-mTLS composition. */
@Timeout(60)
class EntityRuntimeFamilyCatalogClientMtlsTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private static final String NAMESPACE = "entity-catalog-test";

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Entity catalog test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "client", uri("game-session-service", NAMESPACE), false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "entity", uri("entity-management-service", NAMESPACE), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-role", uri("world-management-service", NAMESPACE), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign", uri("entity-management-service", "other"), true));
  }

  @Test
  void actualClientPreservesTypedBytesCorrelationIdentityAndDeadline() throws Exception {
    var request = new Request(UUID.randomUUID(), NAMESPACE);
    var expected = evidence(request);
    var observed = new AtomicReference<Request>();
    var peer = new AtomicReference<String>();
    var remaining = new AtomicLong();
    Server server =
        server(
            "entity",
            new EntityRuntimeFamilyCatalogReadServiceGrpc
                .EntityRuntimeFamilyCatalogReadServiceImplBase() {
              @Override
              public void readEntityRuntimeFamilyCatalog(
                  ReadEntityRuntimeFamilyCatalogRequest wire,
                  StreamObserver<ReadEntityRuntimeFamilyCatalogResponse> observer) {
                peer.set(GrpcPeerIdentity.current().uri());
                remaining.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                observed.set(EntityRuntimeFamilyCatalogCodec.fromRequest(wire));
                observer.onNext(EntityRuntimeFamilyCatalogCodec.toResponse(request, expected));
                observer.onCompleted();
              }
            });
    try (var client = client(server)) {
      assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
      client.init();
      var actual = client.read(request);
      assertThat(actual).isEqualTo(expected);
      assertThat(actual.digest()).isEqualTo(expected.digest());
      assertThat(actual.catalog().exactBytes()).isEqualTo(expected.catalog().exactBytes());
      assertThat(observed.get()).isEqualTo(request);
      assertThat(peer.get()).isEqualTo(uri("game-session-service", NAMESPACE));
      assertThat(remaining.get()).isBetween(1L, 5000L);
    } finally {
      stop(server);
    }
  }

  @Test
  void sameCaWrongEntityRoleOrNamespaceRefusesBeforeHandler() throws Exception {
    for (String identity : List.of("wrong-role", "foreign")) {
      var calls = new AtomicInteger();
      Server server =
          server(
              identity,
              new EntityRuntimeFamilyCatalogReadServiceGrpc
                  .EntityRuntimeFamilyCatalogReadServiceImplBase() {
                @Override
                public void readEntityRuntimeFamilyCatalog(
                    ReadEntityRuntimeFamilyCatalogRequest wire,
                    StreamObserver<ReadEntityRuntimeFamilyCatalogResponse> observer) {
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
  void wrongCorrelationEmptyDuplicateAndOversizedResponsesNeverBecomeEvidence() throws Exception {
    var request = new Request(UUID.randomUUID(), NAMESPACE);
    var other = new Request(UUID.randomUUID(), NAMESPACE);
    var valid = EntityRuntimeFamilyCatalogCodec.toResponse(request, evidence(request));
    for (var response :
        List.of(
            EntityRuntimeFamilyCatalogCodec.toResponse(other, evidence(other)),
            ReadEntityRuntimeFamilyCatalogResponse.getDefaultInstance(),
            valid.toBuilder().addCanonicalResponseBytes(valid.getCanonicalResponseBytes(0)).build(),
            valid.toBuilder()
                .setCanonicalResponseBytes(
                    0,
                    com.google.protobuf.ByteString.copyFrom(
                        new byte[EntityRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES + 1]))
                .build())) {
      Server server =
          server(
              "entity",
              new EntityRuntimeFamilyCatalogReadServiceGrpc
                  .EntityRuntimeFamilyCatalogReadServiceImplBase() {
                @Override
                public void readEntityRuntimeFamilyCatalog(
                    ReadEntityRuntimeFamilyCatalogRequest wire,
                    StreamObserver<ReadEntityRuntimeFamilyCatalogResponse> observer) {
                  observer.onNext(response);
                  observer.onCompleted();
                }
              });
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
  void refusalWrongLocalNamespaceAmbientSqlAndPlaintextAreNotEmptySuccess() throws Exception {
    var calls = new AtomicInteger();
    Server server =
        server(
            "entity",
            new EntityRuntimeFamilyCatalogReadServiceGrpc
                .EntityRuntimeFamilyCatalogReadServiceImplBase() {
              @Override
              public void readEntityRuntimeFamilyCatalog(
                  ReadEntityRuntimeFamilyCatalogRequest wire,
                  StreamObserver<ReadEntityRuntimeFamilyCatalogResponse> observer) {
                calls.incrementAndGet();
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
              }
            });
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), "other")))
          .isInstanceOf(IllegalArgumentException.class);
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
                new EntityRuntimeFamilyCatalogClient(
                    new ServiceEndpointsProperties(), tls, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonrespondingOwnerIsBoundedByActualFiveSecondRpcDeadline() throws Exception {
    Server server =
        server(
            "entity",
            new EntityRuntimeFamilyCatalogReadServiceGrpc
                .EntityRuntimeFamilyCatalogReadServiceImplBase() {
              @Override
              public void readEntityRuntimeFamilyCatalog(
                  ReadEntityRuntimeFamilyCatalogRequest wire,
                  StreamObserver<ReadEntityRuntimeFamilyCatalogResponse> observer) {
                /* deliberately no response */
              }
            });
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    } finally {
      stop(server);
    }
  }

  private static EntityRuntimeFamilyCatalogClient client(Server server) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + server.getPort());
    return new EntityRuntimeFamilyCatalogClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("client")),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static Server server(
      String identity,
      EntityRuntimeFamilyCatalogReadServiceGrpc.EntityRuntimeFamilyCatalogReadServiceImplBase
          service)
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

  private static Evidence evidence(Request request) {
    String json =
        """
        {"schema":"entity-runtime-family-catalog/v1","owner":"entity-management-service","catalogEpoch":"1",
         "families":[{"familyId":"actor_resource_states","relation":"actor_resource_states","component":"",
          "role":"RUNTIME_PAYLOAD","stateClass":"CONDITIONAL_S1_S2","freshDisposition":"BLOCKING_NAMESPACE_REMAP"}],
         "schemaFacts":[{"kind":"TABLE","identity":"actor_resource_states",
          "definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
        """;
    return new Evidence(
        request,
        UUID.randomUUID(),
        EntityRuntimeFamilyCatalog.fromStored(json.getBytes(StandardCharsets.UTF_8)));
  }

  private static String uri(String service, String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }
}
