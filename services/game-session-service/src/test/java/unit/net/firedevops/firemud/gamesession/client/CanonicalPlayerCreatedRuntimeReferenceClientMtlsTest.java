package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRuntimeReferenceServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorOrigin;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Controlled loopback transport proof; it does not establish Entity SQL ownership or custody. */
@Timeout(60)
class CanonicalPlayerCreatedRuntimeReferenceClientMtlsTest {
  private static final String NAMESPACE = "game-session-test";
  private static final String GAME_SESSION_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String ENTITY_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/entity-management-service";

  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "PLAYER_CREATED runtime reference test CA",
            new LoopbackMtlsTestPki.IdentitySpec("client", GAME_SESSION_PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec("entity-server", ENTITY_PEER, true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-role",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                true));
  }

  @Test
  void authenticatedExactResponseProducesReferenceAndMatchesStableSharedWorldSelectors()
      throws Exception {
    var request = request();
    var response = response(request);
    var invocations = new AtomicInteger();
    var service = service(invocations, response);
    Server server = server("entity-server", service);
    var endpoints = endpoints(server);
    try (var client = client(endpoints)) {
      client.init();
      var reference = client.read(request);
      assertThat(reference.workloadNamespace()).isEqualTo(NAMESPACE);
      assertThat(reference.runtimeEntityId().tenantId().toString()).isEqualTo(request.getTenantUuid());
      assertThat(reference.runtimeEntityId().gameInstanceId()).isEqualTo(request.getGameInstanceUuid());
      assertThat(reference.runtimeEntityId().value()).isEqualTo(response.getRuntimeEntityId());
      assertThat(reference.canonicalAccountUuid().toString()).isEqualTo(request.getAccountUuid());
      assertThat(reference.characterUuid().toString()).isEqualTo(request.getCharacterUuid());
      assertThat(reference.playableStateNamespaceId().toString())
          .isEqualTo(request.getPlayableStateNamespaceUuid());
      assertThat(reference.projectionGeneration()).isEqualTo(4L);
      assertThat(reference.projectionFence()).isEqualTo(9L);
      assertThat(reference.origin()).isEqualTo(new CanonicalGameplayRuntimeActorReference.CreationOrigin(request.getOrigin()));
      assertThat(reference.canonicalEvidenceBytes()).containsExactly(response.toByteArray());
      assertThat(reference.matchesWorldRequest(worldRequest(request))).isTrue();
      assertThat(reference.matchesWorldRequest(worldRequest(request).toBuilder().setPlayerCreatedOrigin(
          request.getOrigin().toBuilder().setMutationDigest("f".repeat(64))).build())).isFalse();
      assertThat(reference.matchesWorldRequest(worldRequest(request).toBuilder().setExpectedTarget(
          worldRequest(request).getExpectedTarget().toBuilder().setCanonicalVersionUuid(
              "88888888-8888-4888-8888-888888888888")).build())).isFalse();
      assertThat(invocations).hasValue(1);
    } finally {
      stop(server);
    }
  }

  @Test
  void changedOriginOrStableTargetInEntityResponseFailsClosed() throws Exception {
    for (boolean changeOrigin : List.of(true, false)) {
      var request = request();
      var changedSelector = request.toBuilder();
      if (changeOrigin) {
        changedSelector.setOrigin(
            request.getOrigin().toBuilder().setTerminalUuid("99999999-9999-4999-8999-999999999999"));
      } else {
        changedSelector.setCanonicalVersionUuid("88888888-8888-4888-8888-888888888888");
      }
      var response = response(request).toBuilder().setSelector(changedSelector).build();
      Server server = server("entity-server", service(new AtomicInteger(), response));
      try (var client = client(endpoints(server))) {
        client.init();
        assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
      } finally {
        stop(server);
      }
    }
  }

  @Test
  void wrongServerUriIdentityIsRejectedBeforeEntityHandler() throws Exception {
    var invocations = new AtomicInteger();
    Server server = server("wrong-role", service(invocations, response(request())));
    try (var client = client(endpoints(server))) {
      client.init();
      assertThatThrownBy(() -> client.read(request()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(invocations).hasValue(0);
    } finally {
      stop(server);
    }
  }

  @Test
  void requestOriginNamespaceMustMatchFixedWorkloadNamespaceBeforeRpc() throws Exception {
    var invocations = new AtomicInteger();
    Server server = server("entity-server", service(invocations, response(request())));
    var wrongNamespace = request().toBuilder().setOrigin(request().getOrigin().toBuilder().setTargetNamespace("other")).build();
    try (var client = client(endpoints(server))) {
      client.init();
      assertThatThrownBy(() -> client.read(wrongNamespace)).isInstanceOf(IllegalArgumentException.class);
      assertThat(invocations).hasValue(0);
    } finally {
      stop(server);
    }
  }

  private static CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
          .CanonicalPlayerCreatedRuntimeReferenceServiceImplBase
      service(AtomicInteger invocations, PlayerCreatedActorRuntimeReferenceResponse response) {
    return new CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
        .CanonicalPlayerCreatedRuntimeReferenceServiceImplBase() {
      @Override
      public void readPlayerCreatedActorRuntimeReference(
          PlayerCreatedActorRuntimeReferenceRequest request,
          StreamObserver<PlayerCreatedActorRuntimeReferenceResponse> observer) {
        invocations.incrementAndGet();
        var peer = GrpcPeerIdentity.current();
        if (peer == null || !GAME_SESSION_PEER.equals(peer.uri())) {
          observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
          return;
        }
        observer.onNext(response);
        observer.onCompleted();
      }
    };
  }

  private static Server server(
      String identity,
      CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
              .CanonicalPlayerCreatedRuntimeReferenceServiceImplBase
          service)
      throws Exception {
    var serverIdentity = pki.identity(identity);
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            pki.keyManagerFactory(serverIdentity),
            pki.trustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    factory.addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()));
    return factory.createServer().start();
  }

  private static CanonicalPlayerCreatedRuntimeReferenceClient client(
      ServiceEndpointsProperties endpoints) {
    return new CanonicalPlayerCreatedRuntimeReferenceClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("client")),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static ServiceEndpointsProperties endpoints(Server server) {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + server.getPort());
    return endpoints;
  }

  private static void stop(Server server) throws Exception {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static PlayerCreatedActorRuntimeReferenceRequest request() {
    return PlayerCreatedActorRuntimeReferenceRequest.newBuilder()
        .setSchemaVersion(1)
        .setOrigin(origin())
        .setTenantUuid("11111111-1111-4111-8111-111111111111")
        .setAccountUuid("22222222-2222-4222-8222-222222222222")
        .setPlayableStateNamespaceUuid("33333333-3333-4333-8333-333333333333")
        .setGameInstanceUuid("44444444-4444-4444-8444-444444444444")
        .setCharacterUuid("55555555-5555-4555-8555-555555555555")
        .setCanonicalVersionUuid("66666666-6666-4666-8666-666666666666")
        .setRealmUuid("77777777-7777-4777-8777-777777777777")
        .setWorldSlug("outer-reach")
        .setRealmSlug("green-march")
        .build();
  }

  private static PlayerCreatedActorOrigin origin() {
    return PlayerCreatedActorOrigin.newBuilder()
        .setTargetNamespace(NAMESPACE)
        .setCreationOperationUuid("88888888-8888-4888-8888-888888888888")
        .setMutationDigest("a".repeat(64))
        .setTerminalUuid("99999999-9999-4999-8999-999999999999")
        .setTerminalSha256("b".repeat(64))
        .build();
  }

  private static PlayerCreatedActorRuntimeReferenceResponse response(
      PlayerCreatedActorRuntimeReferenceRequest request) {
    return PlayerCreatedActorRuntimeReferenceResponse.newBuilder()
        .setSchemaVersion(1)
        .setSelector(request)
        .setRuntimeEntityId(7402L)
        .setProjectionGeneration(4L)
        .setProjectionFence(9L)
        .setOriginalCatalogRevision(7L)
        .setOriginalPointerVersion(3L)
        .setOriginalActiveLifecycleEpoch(5L)
        .setOriginalDescriptorDigest("sha256:" + "c".repeat(64))
        .setOriginalAuthoredBindingDigest("sha256:" + "d".repeat(64))
        .setOriginalPolicyContentDigest("sha256:" + "e".repeat(64))
        .build();
  }

  private static CanonicalInitialPlayerLocationRequest worldRequest(
      PlayerCreatedActorRuntimeReferenceRequest request) {
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(2)
        .setCanonicalAccountUuid(request.getAccountUuid())
        .setCharacterUuid(request.getCharacterUuid())
        .setPlayerCreatedOrigin(request.getOrigin())
        .setExpectedTarget(
            PreseededActorAssignmentExpectedTarget.newBuilder()
                .setCanonicalTenantUuid(request.getTenantUuid())
                .setPlayableStateNamespaceUuid(request.getPlayableStateNamespaceUuid())
                .setGameInstanceId(request.getGameInstanceUuid())
                .setCanonicalVersionUuid(request.getCanonicalVersionUuid())
                .setRealmUuid(request.getRealmUuid())
                .setWorldSlug(request.getWorldSlug())
                .setRealmSlug(request.getRealmSlug())
                .setCatalogRevision(99L)
                .setFrozenPolicyDigest("f".repeat(64))
                .setPublishedReleaseBundleRef("current-release:route")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .build();
  }
}
