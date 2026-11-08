package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryPolicyServiceGrpc;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.repository.CanonicalPublishedAdmissionActualOwnerCompositionFixture;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.jooq.DSLContext;

/**
 * Real GS OPEN/current publication and World ACTIVE reads over authenticated sockets, followed by
 * actual Account LOGIN, absent-player entitlement, owner-local first JOIN and positive source
 * reread. Game Design publication, DEMO policy, PKI, caller coordinates and custody callbacks are
 * controlled. No public ingress, World hold across JOIN, protected receiving custody or activation
 * is proved.
 */
final class WorldPublishedAdmissionSourceComposition {
  private WorldPublishedAdmissionSourceComposition() {}

  static void exercise(
      Path root,
      DSLContext gs,
      org.springframework.transaction.PlatformTransactionManager gsTransactions,
      String redisHost,
      int redisPort,
      CanonicalPlayableTarget target,
      UUID account,
      net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture
          originalAccount,
      ServiceEndpointsProperties worldEndpoints,
      CommonGrpcClientProperties worldTls)
      throws Exception {
    assertThat(target.characterCreationPolicy()).isEqualTo("PRESEEDED_ONLY");
    var publication =
        RealmPolicyPublishedEvidenceCodec.decode(
            gs.fetchSingle(
                    "SELECT complete_policy_set_evidence FROM game_session_canonical_realm_catalog WHERE realm_id=?",
                    target.realmId())
                .get(0, byte[].class));
    String namespace = target.targetNamespace();
    Path directory = Files.createDirectories(root.resolve("published-admission-source"));
    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Controlled published source composition",
            new LoopbackMtlsTestPki.IdentitySpec(
                "gs-server", peer(namespace, "game-session-service"), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "gs-client", peer(namespace, "game-session-service"), false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "account", peer(namespace, "account-service"), false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "design", peer(namespace, "game-design-service"), true));
    var policies =
        new PublishedRealmEntryPolicySetEvidence(
            publication.target(),
            publication.versionNumber(),
            publication.sourceCommitId(),
            publication.sourceEpoch(),
            publication.publishedReleaseBundleRef(),
            publication.publishedReleaseBundleDigest(),
            publication.publishWorkflowId(),
            publication.manifestHash(),
            publication.publicationVersionStateEpoch(),
            publication.operationBytes(),
            publication.captureBytes(),
            publication.terminalEvidenceBytes(),
            publication.policyCount(),
            publication.policySetDigest(),
            publication.policies().stream()
                .map(
                    p ->
                        new PublishedRealmEntryPolicyEvidence(
                            p.policyId(),
                            p.source().commitId(),
                            p.source().revisionId(),
                            p.source().logicalRevisionId(),
                            p.source().policy(),
                            p.policyDigest()))
                .toList());
    // This is the sole stipulated source: the exact published set already used by the real
    // preparation/OPEN owners. No Game Design issuing-owner proof is claimed here.
    var policySourcePeerFailure = new AtomicReference<String>();
    var policySource =
        new PublishedRealmEntryPolicyServiceGrpc.PublishedRealmEntryPolicyServiceImplBase() {
          @Override
          public void listPublishedRealmEntryPolicies(
              ListPublishedRealmEntryPoliciesRequest request,
              StreamObserver<ListPublishedRealmEntryPoliciesResponse> observer) {
            var authenticatedPeer = GrpcPeerIdentity.current();
            String expectedPeer = peer(namespace, "game-session-service");
            if (authenticatedPeer == null || !expectedPeer.equals(authenticatedPeer.uri())) {
              policySourcePeerFailure.compareAndSet(
                  null,
                  "Expected authenticated peer "
                      + expectedPeer
                      + " but was "
                      + (authenticatedPeer == null ? "missing" : authenticatedPeer.uri()));
            }
            observer.onNext(
                PublishedRealmEntryPolicyReadGrpcCodec.toResponse(
                    PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(request), policies));
            observer.onCompleted();
          }
        };
    Server design = server(pki, "design", policySource);
    // Reverse close order retains shutdown and peer diagnostics as suppressed failures.
    try (var peerCheck = checkPeerOnClose(policySourcePeerFailure);
        var designLifetime = stopOnClose(design)) {
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameDesignService("localhost:" + design.getPort());
      var channels = new GrpcChannelFactory();
      try (var policyClient =
              new GameDesignPublishedRealmPolicyClient(
                  endpoints,
                  pki.clientProperties(directory, pki.identity("gs-client")),
                  channels,
                  namespace);
          var worldClient =
              new WorldCanonicalInstanceLifecycleClient(
                  worldEndpoints, worldTls, channels, namespace)) {
        policyClient.init();
        worldClient.init();
        Server receiver =
            server(
                pki,
                "gs-server",
                CanonicalPublishedAdmissionActualOwnerCompositionFixture.receiver(
                    gs, namespace, policyClient, worldClient));
        try (var receiverLifetime = stopOnClose(receiver)) {
          endpoints.setGameSessionService("localhost:" + receiver.getPort());
          try (var accountClient =
              new CanonicalGameplayAdmissionDecisionReadClient(
                  endpoints,
                  pki.clientProperties(directory, pki.identity("account")),
                  channels,
                  namespace)) {
            accountClient.init();
            var selector =
                new RetainedTargetSelector(
                    UUID.randomUUID(),
                    account,
                    namespace,
                    target.canonicalTenantId(),
                    target.tenantSlug(),
                    target.worldSlug(),
                    target.realmId(),
                    target.realmSlug(),
                    target.playableStateNamespaceId(),
                    target.playableStateScope(),
                    target.canonicalGameInstanceId(),
                    target.catalogRevision(),
                    target.pointerVersion());
            var resolved = accountClient.resolvePublishedRealmAdmissionTarget(selector);
            var read = accountClient.readPublishedRealmAdmissionOwner(resolved.strictSelector());
            assertThat(read.selector()).isEqualTo(resolved.strictSelector());
            assertThat(read.currentTarget().canonicalGameInstanceId())
                .isEqualTo(target.canonicalGameInstanceId());
            assertThat(read.sourceEvidence().admissionPointerSnapshotDigest())
                .isEqualTo(target.admissionPointerSnapshotDigest());
            assertThat(read.sourceEvidence().gameSessionOwnerProof().proofDigest())
                .isEqualTo(target.ownerProofDigest());
            assertThat(read.sourceEvidence().publishedPolicySetEvidence().canonicalBytes())
                .containsExactly(policies.canonicalBytes());
            assertThat(read.worldLifecycleEvidence().lifecycleStatus()).isEqualTo("ACTIVE");
            assertThat(read.worldLifecycleEvidence().lifecycleEpoch())
                .isEqualTo(target.activeWorldEpoch());
            var wrong =
                new RetainedTargetSelector(
                    UUID.randomUUID(),
                    account,
                    namespace,
                    target.canonicalTenantId(),
                    target.tenantSlug(),
                    target.worldSlug(),
                    target.realmId(),
                    target.realmSlug(),
                    target.playableStateNamespaceId(),
                    target.playableStateScope(),
                    UUID.randomUUID(),
                    target.catalogRevision(),
                    target.pointerVersion());
            assertThatThrownBy(() -> accountClient.resolvePublishedRealmAdmissionTarget(wrong))
                .isExactlyInstanceOf(StatusRuntimeException.class)
                .satisfies(
                    failure -> {
                      var status = ((StatusRuntimeException) failure).getStatus();
                      assertThat(status.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
                      assertThat(status.getDescription())
                          .isEqualTo("Canonical Game Session admission decision is unavailable");
                    });
            var afterRefusal = accountClient.resolvePublishedRealmAdmissionTarget(selector);
            assertThat(afterRefusal.selector()).isEqualTo(resolved.selector());
            assertThat(afterRefusal.currentTarget()).isEqualTo(resolved.currentTarget());
            WorldPlayerAdmissionEntitlementComposition.exercise(
                root,
                originalAccount,
                accountClient,
                target,
                gs,
                gsTransactions,
                redisHost,
                redisPort);
          }
        }
      }
    }
  }

  static AutoCloseable checkPeerOnClose(AtomicReference<String> peerFailure) {
    return () -> assertThat(peerFailure.get()).as("published policy source peer").isNull();
  }

  static AutoCloseable stopOnClose(Server server) {
    return () -> stop(server);
  }

  private static String peer(String namespace, String service) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }

  private static Server server(
      LoopbackMtlsTestPki.Pki pki, String identity, BindableService receiver) throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder.forServer(
                        pki.keyManagerFactory(pki.identity(identity))))
                .trustManager(pki.trustManagerFactory())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(receiver, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }
}
