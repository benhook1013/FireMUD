package unit.net.firedevops.firemud.gamesession.client;

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
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.gamesession.client.CanonicalInitialPlayerLocationClient;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRoom;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationServiceGrpc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Physical loopback mTLS proof for the manually composed initial-location client. The handler's
 * controlled response does not prove World placement, retained custody, occupancy, or activation.
 */
@Timeout(60)
class CanonicalInitialPlayerLocationClientMtlsTest {
  private static final String NAMESPACE = "game-session-test";
  private static final String GAME_SESSION_PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String CHARACTER_UUID = "33333333-3333-4333-8333-333333333333";
  private static final String ASSIGNMENT_UUID = "44444444-4444-4444-8444-444444444444";
  private static final String INTENT_DIGEST = "a".repeat(64);
  private static final String WORLD_REQUEST_DIGEST = "sha256:" + "d".repeat(64);
  private static final String WORLD_RESULT_DIGEST = "sha256:" + "e".repeat(64);

  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Canonical initial player location test CA",
            new LoopbackMtlsTestPki.IdentitySpec("client", GAME_SESSION_PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "world-server",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-role",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/entity-management-service",
                true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign-namespace",
                "spiffe://firemud/ns/other/sa/world-management-service",
                true));
  }

  @Test
  void initializedClientAcceptsExactSameNamespaceWorldPeer() throws Exception {
    exercise("world-server", true);
  }

  @Test
  void sameCaWrongServerRoleAndForeignNamespaceAreRejectedBeforeHandler() throws Exception {
    for (String identity : List.of("wrong-role", "foreign-namespace")) {
      exercise(identity, false);
    }
  }

  private static void exercise(String identity, boolean accepted) throws Exception {
    var request = request();
    var response = response(request);
    var handlerInvocations = new AtomicInteger();
    var observedRequest = new AtomicReference<CanonicalInitialPlayerLocationRequest>();
    var observedPeer = new AtomicReference<String>();
    var service =
        new CanonicalInitialPlayerLocationServiceGrpc
            .CanonicalInitialPlayerLocationServiceImplBase() {
          @Override
          public void placeInitialPlayer(
              CanonicalInitialPlayerLocationRequest request,
              StreamObserver<CanonicalInitialPlayerLocationResponse> observer) {
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
    endpoints.setWorldManagementService("localhost:" + server.getPort());
    try (var client =
        new CanonicalInitialPlayerLocationClient(
            endpoints,
            pki.clientProperties(directory, pki.identity("client")),
            new GrpcChannelFactory(),
            NAMESPACE)) {
      client.init();
      if (accepted) {
        assertThat(client.placeInitialPlayer(request)).isEqualTo(response);
        assertThat(handlerInvocations).hasValue(1);
        assertThat(observedRequest.get()).isEqualTo(request);
        assertThat(observedPeer.get()).isEqualTo(GAME_SESSION_PEER);
      } else {
        assertThatThrownBy(() -> client.placeInitialPlayer(request))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure ->
                    assertThat(failure.getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
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
      CanonicalInitialPlayerLocationServiceGrpc.CanonicalInitialPlayerLocationServiceImplBase
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

  private static CanonicalInitialPlayerLocationRequest request() {
    return CanonicalInitialPlayerLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid("11111111-1111-4111-8111-111111111111")
        .setExpectedTarget(
            PreseededActorAssignmentExpectedTarget.newBuilder()
                .setCanonicalTenantUuid("55555555-5555-4555-8555-555555555555")
                .setRealmUuid("66666666-6666-4666-8666-666666666666")
                .setWorldSlug("outer-reach")
                .setRealmSlug("green-march")
                .setGameInstanceId("99999999-9999-4999-8999-999999999999")
                .setCatalogRevision(7L)
                .setCanonicalVersionUuid("77777777-7777-4777-8777-777777777777")
                .setFrozenPolicyDigest("8".repeat(64))
                .setPlayableStateNamespaceUuid("88888888-8888-4888-8888-888888888888")
                .setPublishedReleaseBundleRef("release:bounded/v1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .setCanonicalAccountUuid("22222222-2222-4222-8222-222222222222")
        .setCharacterUuid(CHARACTER_UUID)
        .setAssignmentUuid(ASSIGNMENT_UUID)
        .setAssignmentIntentDigest(INTENT_DIGEST)
        .setInitialAdmissionHoldUuid("99999999-9999-4999-8999-999999999999")
        .setInitialAdmissionHoldFence("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .setInitialAdmissionRequestId("initial-admission-attempt-7")
        .setInitialAdmissionRequestDigest("b".repeat(64))
        .setActiveLifecycleEpoch(12L)
        .setTerminalProofDigest("sha256:" + "c".repeat(64))
        .setCommittedPointerVersion(1L)
        .setAuditEventId(101L)
        .build();
  }

  private static CanonicalInitialPlayerLocationResponse response(
      CanonicalInitialPlayerLocationRequest request) {
    return CanonicalInitialPlayerLocationResponse.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid(request.getPlacementOperationUuid())
        .setRequestDigest(WORLD_REQUEST_DIGEST)
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setCharacterUuid(request.getCharacterUuid())
        .setAssignmentUuid(request.getAssignmentUuid())
        .setAssignmentIntentDigest(request.getAssignmentIntentDigest())
        .setResultDigest(WORLD_RESULT_DIGEST)
        .setOutcome(
            CanonicalInitialPlayerLocationOutcome.CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED)
        .setStartLocation(
            CanonicalInitialPlayerLocationRoom.newBuilder()
                .setCanonicalTenantUuid(request.getExpectedTarget().getCanonicalTenantUuid())
                .setCanonicalVersionUuid(request.getExpectedTarget().getCanonicalVersionUuid())
                .setRoomTemplateUuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
        .setRuntimeRoomInstanceId("9182")
        .build();
  }
}
