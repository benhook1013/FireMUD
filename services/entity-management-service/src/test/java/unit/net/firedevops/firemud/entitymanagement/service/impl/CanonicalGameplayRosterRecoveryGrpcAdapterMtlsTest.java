package net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.security.CanonicalGameplayRosterPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRecoveryService;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native loopback mTLS of the unregistered recovery adapter; owner/storage proof is separate. */
class CanonicalGameplayRosterRecoveryGrpcAdapterMtlsTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private final CanonicalGameplayRosterRecoveryService recovery =
      mock(CanonicalGameplayRosterRecoveryService.class);
  private Server server;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Entity roster recovery CA",
            new IdentitySpec(
                "entity", "spiffe://firemud/ns/gameplay/sa/entity-management-service", true),
            new IdentitySpec("gs", "spiffe://firemud/ns/gameplay/sa/game-session-service", false),
            new IdentitySpec("foreign", "spiffe://firemud/ns/other/sa/game-session-service", false),
            new IdentitySpec("account", "spiffe://firemud/ns/gameplay/sa/account-service", false));
  }

  @AfterEach
  void stop() throws Exception {
    SessionContext.clear();
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void exactGameSessionCertificateCarriesExactRecoveryRequestAndOriginalResponse()
      throws Exception {
    var request = request();
    var expected =
        CanonicalGameplayRosterRecoveryEvidence.response(
            request, UUID.randomUUID(), 1, UUID.randomUUID(), null);
    when(recovery.recover(request))
        .thenAnswer(
            ignored -> {
              assertThat(CanonicalGameplayRosterPeerInterceptor.currentVerifiedPeer()).isNotNull();
              assertThat(
                      CanonicalGameplayRosterPeerInterceptor.currentVerifiedPeer()
                          .isService("game-session-service"))
                  .isTrue();
              assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
              return expected;
            });
    start();
    var channel = channel("gs");
    try {
      assertThat(
              CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
                  .withDeadlineAfter(5, TimeUnit.SECONDS)
                  .recoverOriginalSelection(request))
          .isEqualTo(expected);
    } finally {
      close(channel);
    }
  }

  @Test
  void wrongNamespaceAndServiceCannotReachRecoveryHandler() throws Exception {
    start();
    for (String alias : new String[] {"foreign", "account"}) {
      var channel = channel(alias);
      try {
        assertThatThrownBy(
                () ->
                    CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .recoverOriginalSelection(request()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
      } finally {
        close(channel);
      }
    }
    verifyNoInteractions(recovery);
  }

  @Test
  void localConflictReturnsBoundedFailureWithoutTransportSuccess() throws Exception {
    when(recovery.recover(any()))
        .thenThrow(new IllegalStateException("CANONICAL_ROSTER_RECOVERY_CONFLICT"));
    start();
    var channel = channel("gs");
    try {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .recoverOriginalSelection(request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
    } finally {
      close(channel);
    }
  }

  private void start() throws Exception {
    var identity = pki.identity("entity");
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    new CanonicalGameplayRosterRecoveryGrpcAdapter(recovery),
                    new CanonicalGameplayRosterPeerInterceptor("gameplay")))
            .build()
            .start();
  }

  private ManagedChannel channel(String alias) throws Exception {
    var identity = pki.identity(alias);
    return NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(pki.caCertificate())
                .keyManager(identity.privateKey(), identity.certificate())
                .build())
        .build();
  }

  private static void close(ManagedChannel channel) throws Exception {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static CanonicalGameplayRosterRecoveryRequest request() {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setWorldSlug("world")
            .setRealmSlug("realm")
            .setCatalogRevision(1)
            .setPointerVersion(1)
            .setActiveWorldEpoch(1)
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedReleaseBundleRef("release:exact")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return CanonicalGameplayRosterRecoveryRequest.newBuilder()
        .setSchemaVersion(1)
        .setRecoveryUuid(UUID.randomUUID().toString())
        .setOriginalSelection(
            CanonicalGameplayRosterReferenceEvidence.request(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "gameplay", target))
        .build();
  }
}
