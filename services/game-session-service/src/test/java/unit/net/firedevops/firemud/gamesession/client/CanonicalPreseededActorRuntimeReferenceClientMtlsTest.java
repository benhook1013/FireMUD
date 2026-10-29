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
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Controlled loopback source and test PKI: no actual Entity SQL or production custody proof. */
@Timeout(60)
class CanonicalPreseededActorRuntimeReferenceClientMtlsTest {
  private static final String NAMESPACE = "game-session-test";
  private static final String GAME_SESSION_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String CHARACTER_UUID = "22222222-2222-4222-8222-222222222222";
  private static final String INTENT_DIGEST = "a".repeat(64);

  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Preseeded actor assignment test CA",
            new LoopbackMtlsTestPki.IdentitySpec("client", GAME_SESSION_PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "entity-server",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/entity-management-service",
                true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-role",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign-namespace",
                "spiffe://firemud/ns/other/sa/entity-management-service",
                true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-client",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign-client", "spiffe://firemud/ns/other/sa/game-session-service", false));
  }

  @Test
  void initializedClientReturnsClosedResponseFromSameNamespaceEntityPeer() throws Exception {
    exercise("entity-server", true);
  }

  @Test
  void sameCaWrongServerRoleAndForeignNamespaceAreRejectedBeforeHandler() throws Exception {
    for (String identity : List.of("wrong-role", "foreign-namespace")) {
      exercise(identity, false);
    }
  }

  @Test
  void controlledReceiverRejectsWrongCallerPurposeAndNamespace() throws Exception {
    exercise("entity-server", false, "wrong-client");
    exercise("entity-server", false, "foreign-client");
  }

  private static void exercise(String identity, boolean accepted) throws Exception {
    exercise(identity, accepted, "client");
  }

  private static void exercise(String identity, boolean accepted, String caller) throws Exception {
    var request = request();
    var response = assignedResponse(request);
    var handlerInvocations = new AtomicInteger();
    var observedRequest = new AtomicReference<ReadPreseededActorRuntimeReferenceRequest>();
    var observedPeer = new AtomicReference<String>();
    var service =
        new CanonicalPreseededActorAssignmentServiceGrpc
            .CanonicalPreseededActorAssignmentServiceImplBase() {
          @Override
          public void readPreseededActorRuntimeReference(
              ReadPreseededActorRuntimeReferenceRequest request,
              StreamObserver<ReadPreseededActorRuntimeReferenceResponse> observer) {
            handlerInvocations.incrementAndGet();
            observedRequest.set(request);
            var peer = GrpcPeerIdentity.current();
            observedPeer.set(peer == null ? null : peer.uri());
            if (peer == null || !GAME_SESSION_PEER.equals(peer.uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    Server server = server(identity, service);
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + server.getPort());
    try (var client =
        new CanonicalPreseededActorRuntimeReferenceClient(
            endpoints,
            pki.clientProperties(directory, pki.identity(caller)),
            new GrpcChannelFactory(),
            NAMESPACE)) {
      client.init();
      if (accepted) {
        var reference = client.read(request);
        assertThat(reference.runtimeEntityId().value()).isEqualTo(response.getRuntimeEntityId());
        assertThat(reference.runtimeEntityId().tenantId().toString())
            .isEqualTo(request.getExpectedTarget().getCanonicalTenantUuid());
        assertThat(reference.runtimeEntityId().gameInstanceId())
            .isEqualTo(request.getExpectedTarget().getGameInstanceId());
        assertThat(reference.characterUuid().toString()).isEqualTo(request.getCharacterUuid());
        assertThat(reference.assignmentUuid().toString()).isEqualTo(request.getAssignmentUuid());
        assertThat(reference.target()).isEqualTo(request.getExpectedTarget());
        assertThat(reference.projectionGeneration()).isEqualTo(1);
        assertThat(reference.projectionFence()).isEqualTo(1);
        assertThat(reference.publishedOwnerProofDigest())
            .isEqualTo(response.getPublishedOwnerProofDigest());
        assertThat(handlerInvocations).hasValue(1);
        assertThat(observedRequest.get()).isEqualTo(request);
        assertThat(observedPeer.get()).isEqualTo(GAME_SESSION_PEER);
      } else {
        assertThatThrownBy(() -> client.read(request))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure -> {
                  assertThat(failure.getStatus().getCode())
                      .isEqualTo(
                          caller.equals("client")
                              ? Status.Code.UNAUTHENTICATED
                              : Status.Code.PERMISSION_DENIED);
                });
        if (caller.equals("client")) {
          assertThat(handlerInvocations).hasValue(0);
          assertThat(observedRequest.get()).isNull();
          assertThat(observedPeer.get()).isNull();
        } else {
          assertThat(handlerInvocations).hasValue(1);
        }
      }
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static Server server(
      String identity,
      CanonicalPreseededActorAssignmentServiceGrpc.CanonicalPreseededActorAssignmentServiceImplBase
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

  private static ReadPreseededActorRuntimeReferenceRequest request() {
    return ReadPreseededActorRuntimeReferenceRequest.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid("11111111-1111-4111-8111-111111111111")
        .setCanonicalAccountUuid("33333333-3333-4333-8333-333333333333")
        .setExpectedTarget(
            PreseededActorAssignmentExpectedTarget.newBuilder()
                .setCanonicalTenantUuid("44444444-4444-4444-8444-444444444444")
                .setRealmUuid("55555555-5555-4555-8555-555555555555")
                .setWorldSlug("outer-reach")
                .setRealmSlug("green-march")
                .setGameInstanceId("opaque-instance:preserved")
                .setCatalogRevision(7L)
                .setCanonicalVersionUuid("66666666-6666-4666-8666-666666666666")
                .setFrozenPolicyDigest("b".repeat(64))
                .setPlayableStateNamespaceUuid("77777777-7777-4777-8777-777777777777")
                .setPublishedReleaseBundleRef("release:bounded/v1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .setCharacterUuid(CHARACTER_UUID)
        .setIntentDigest(INTENT_DIGEST)
        .build();
  }

  private static ReadPreseededActorRuntimeReferenceResponse assignedResponse(
      ReadPreseededActorRuntimeReferenceRequest request) {
    return ReadPreseededActorRuntimeReferenceResponse.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(request.getAssignmentUuid())
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setCharacterUuid(request.getCharacterUuid())
        .setIntentDigest(request.getIntentDigest())
        .setExpectedTarget(request.getExpectedTarget())
        .setPublishedOwnerProofDigest("c".repeat(64))
        .setRuntimeEntityId(7301L)
        .setProjectionGeneration(1)
        .setProjectionFence(1)
        .setCorePayload(
            PreseededActorAssignmentCorePayload.newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Pilot One"))
        .build();
  }
}
