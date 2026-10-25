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
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentOutcome;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Physical loopback mTLS proof for the manually composed preseeded assignment client. */
@Timeout(60)
class CanonicalPreseededActorAssignmentClientMtlsTest {
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
                true));
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

  private static void exercise(String identity, boolean accepted) throws Exception {
    var request = request();
    var response = assignedResponse(request);
    var handlerInvocations = new AtomicInteger();
    var observedRequest = new AtomicReference<AssignPreseededActorRequest>();
    var observedPeer = new AtomicReference<String>();
    var service =
        new CanonicalPreseededActorAssignmentServiceGrpc
            .CanonicalPreseededActorAssignmentServiceImplBase() {
          @Override
          public void assignPreseededActor(
              AssignPreseededActorRequest request,
              StreamObserver<AssignPreseededActorResponse> observer) {
            handlerInvocations.incrementAndGet();
            observedRequest.set(request);
            var peer = GrpcPeerIdentity.current();
            observedPeer.set(peer == null ? null : peer.uri());
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    Server server = server(identity, service);
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + server.getPort());
    try (var client =
        new CanonicalPreseededActorAssignmentClient(
            endpoints,
            pki.clientProperties(directory, pki.identity("client")),
            new GrpcChannelFactory(),
            NAMESPACE)) {
      client.init();
      if (accepted) {
        assertThat(client.assign(request)).isEqualTo(response);
        assertThat(handlerInvocations).hasValue(1);
        assertThat(observedRequest.get()).isEqualTo(request);
        assertThat(observedPeer.get()).isEqualTo(GAME_SESSION_PEER);
      } else {
        assertThatThrownBy(() -> client.assign(request))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure -> {
                  assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                });
        assertThat(handlerInvocations).hasValue(0);
        assertThat(observedRequest.get()).isNull();
        assertThat(observedPeer.get()).isNull();
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

  private static AssignPreseededActorRequest request() {
    return AssignPreseededActorRequest.newBuilder()
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
        .setCorePayload(
            PreseededActorAssignmentCorePayload.newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Pilot One"))
        .setMutationIntentDigest(INTENT_DIGEST)
        .build();
  }

  private static AssignPreseededActorResponse assignedResponse(
      AssignPreseededActorRequest request) {
    return AssignPreseededActorResponse.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(request.getAssignmentUuid())
        .setOutcome(PreseededActorAssignmentOutcome.PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_ASSIGNED)
        .setCharacterUuid(CHARACTER_UUID)
        .setIntentDigest(request.getMutationIntentDigest())
        .build();
  }
}
