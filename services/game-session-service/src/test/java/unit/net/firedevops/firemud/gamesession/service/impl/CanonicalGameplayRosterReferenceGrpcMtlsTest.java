package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayRosterOwnerReadGrpcAdapter;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Actual GS authenticated handler on loopback TLS; the durable owner is a labeled source double.
 */
class CanonicalGameplayRosterReferenceGrpcMtlsTest {
  private static final String NAMESPACE = "gameplay";
  private static final String GS_URI = "spiffe://firemud/ns/gameplay/sa/game-session-service";
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private final CanonicalGameplayRosterReferenceOwner owner =
      mock(CanonicalGameplayRosterReferenceOwner.class);
  private Server server;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "roster original owner test CA",
            new IdentitySpec("server", GS_URI, true),
            new IdentitySpec(
                "entity", "spiffe://firemud/ns/gameplay/sa/entity-management-service", false),
            new IdentitySpec(
                "foreign", "spiffe://firemud/ns/other/sa/entity-management-service", false),
            new IdentitySpec(
                "wrong", "spiffe://firemud/ns/gameplay/sa/world-management-service", false),
            new IdentitySpec(
                "foreign-server", "spiffe://firemud/ns/other/sa/game-session-service", true));
  }

  @AfterEach
  void stop() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2L, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void exactEntityCertificateReadsOnlyPersistedOriginalAndMissingOperationsStayUnavailable()
      throws Exception {
    var request = request();
    when(owner.read(request))
        .thenReturn(
            CanonicalGameplayRosterReferenceEvidence.response(
                request,
                1L,
                java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
                null));
    start("server");
    try (var channel = channel("entity")) {
      var actual = stub(channel.channel()).getCanonicalGameplayRosterReference(request);
      assertThat(
              CanonicalGameplayRosterReferenceEvidence.requireResponse(request, actual)
                  .getRequest())
          .isEqualTo(request);
      verify(owner).read(request);
      when(owner.read(any()))
          .thenThrow(
              new net.firedevops.firemud.gamesession.repository
                  .CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException());
      assertThatThrownBy(() -> stub(channel.channel()).getCanonicalGameplayRosterReference(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAVAILABLE));
    }
  }

  @Test
  void authenticatedOwnerFailuresPreserveStaleAbsenceStorageAndUnexpectedStatusCategories()
      throws Exception {
    start("server");
    var request = request();
    var cases =
        List.of(
            java.util.Map.entry(
                new CanonicalPublishedPlayerRouteReadService.StaleAuthorityException(
                    "target superseded"),
                Status.Code.FAILED_PRECONDITION),
            java.util.Map.entry(
                new CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException(
                    "invalid current authority"),
                Status.Code.FAILED_PRECONDITION),
            java.util.Map.entry(
                new IllegalArgumentException("invalid retained owner proof"),
                Status.Code.FAILED_PRECONDITION),
            java.util.Map.entry(
                new net.firedevops.firemud.gamesession.repository
                    .CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException(),
                Status.Code.UNAVAILABLE),
            java.util.Map.entry(
                new CanonicalPublishedPlayerRouteReadService.StorageUnavailableException(
                    "source storage unavailable"),
                Status.Code.UNAVAILABLE),
            java.util.Map.entry(
                new CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException(
                    "policy source unavailable", null),
                Status.Code.UNAVAILABLE),
            java.util.Map.entry(
                new net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService
                    .ReadUnavailableException("route read unavailable"),
                Status.Code.UNAVAILABLE),
            java.util.Map.entry(
                new org.jooq.exception.DataAccessException("database unavailable"),
                Status.Code.UNAVAILABLE),
            java.util.Map.entry(
                new org.springframework.dao.TransientDataAccessResourceException(
                    "storage unavailable"),
                Status.Code.UNAVAILABLE),
            java.util.Map.entry(
                new IllegalStateException("unexpected retained fence invariant"),
                Status.Code.INTERNAL));
    try (var channel = channel("entity")) {
      for (var failure : cases) {
        doThrow(failure.getKey()).when(owner).read(request);
        assertThatThrownBy(
                () -> stub(channel.channel()).getCanonicalGameplayRosterReference(request))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                result -> assertThat(result.getStatus().getCode()).isEqualTo(failure.getValue()));
      }
    }
    verify(owner, times(cases.size())).read(request);
  }

  @Test
  void authenticatedInvalidOwnerResponseDiffersFromMalformedCallerRequest() throws Exception {
    var request = request();
    when(owner.read(request))
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse
                .getDefaultInstance());
    start("server");
    try (var channel = channel("entity")) {
      assertThatThrownBy(() -> stub(channel.channel()).getCanonicalGameplayRosterReference(request))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
      assertThatThrownBy(
              () ->
                  stub(channel.channel())
                      .getCanonicalGameplayRosterReference(
                          request.toBuilder().setSchemaVersion(2).build()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode())
                      .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }
    verify(owner, times(1)).read(any());
  }

  @Test
  void authenticatedMissingProviderRemainsUnavailableBeforeOwnerAccess() throws Exception {
    start("server", false);
    try (var channel = channel("entity")) {
      assertThatThrownBy(
              () -> stub(channel.channel()).getCanonicalGameplayRosterReference(request()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
    }
    verifyNoInteractions(owner);
  }

  @Test
  void foreignNamespaceAndWrongServiceCertificatesCannotReachOriginalOwner() throws Exception {
    start("server");
    for (String identity : List.of("foreign", "wrong")) {
      try (var channel = channel(identity)) {
        assertThatThrownBy(
                () -> stub(channel.channel()).getCanonicalGameplayRosterReference(request()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
      }
    }
    verifyNoInteractions(owner);
  }

  @Test
  void foreignGameSessionServerCertificateIsRejectedBeforeOriginalOwnerAccess() throws Exception {
    start("foreign-server");
    try (var channel = channel("entity")) {
      assertThatThrownBy(
              () -> stub(channel.channel()).getCanonicalGameplayRosterReference(request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
    }
    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedRetirementAckAndConfirmationUseExactPeerAndValidateResponse() throws Exception {
    var request = retirementRequest();
    var ack = CanonicalGameplayRosterRetirementEvidence.response(request, UUID.randomUUID(), 10L);
    var confirmation = CanonicalGameplayRosterRetirementEvidence.confirmation(request, ack);
    var confirmed = CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation);
    String principal = "spiffe://firemud/ns/gameplay/sa/entity-management-service";
    when(owner.acknowledgeRetirement(request, principal)).thenReturn(ack);
    when(owner.confirmRetirementAcknowledgement(confirmation, principal)).thenReturn(confirmed);
    start("server");
    try (var channel = channel("entity")) {
      assertThat(stub(channel.channel()).acknowledgeCanonicalGameplayRosterRetirement(request))
          .isEqualTo(ack);
      assertThat(
              stub(channel.channel())
                  .confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation))
          .isEqualTo(confirmed);
      verify(owner).acknowledgeRetirement(request, principal);
      verify(owner).confirmRetirementAcknowledgement(confirmation, principal);
      when(owner.acknowledgeRetirement(request, principal))
          .thenReturn(ack.toBuilder().setObservationUuid(UUID.randomUUID().toString()).build());
      assertThatThrownBy(
              () -> stub(channel.channel()).acknowledgeCanonicalGameplayRosterRetirement(request))
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
      assertThatThrownBy(
              () ->
                  stub(channel.channel())
                      .acknowledgeCanonicalGameplayRosterRetirement(
                          request.toBuilder().setSchemaVersion(2).build()))
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.INVALID_ARGUMENT));
      assertThatThrownBy(
              () ->
                  stub(channel.channel())
                      .confirmCanonicalGameplayRosterRetirementAcknowledgement(
                          confirmation.toBuilder().setSchemaVersion(2).build()))
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.INVALID_ARGUMENT));
      when(owner.confirmRetirementAcknowledgement(confirmation, principal))
          .thenReturn(confirmed.toBuilder().setRequestDigest("f".repeat(64)).build());
      assertThatThrownBy(
              () ->
                  stub(channel.channel())
                      .confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation))
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
      var cases =
          List.of(
              java.util.Map.entry(
                  new IllegalArgumentException("changed owner fence"),
                  Status.Code.FAILED_PRECONDITION),
              java.util.Map.entry(
                  new net.firedevops.firemud.gamesession.repository
                      .CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException(),
                  Status.Code.UNAVAILABLE),
              java.util.Map.entry(
                  new org.jooq.exception.DataAccessException("storage unavailable"),
                  Status.Code.UNAVAILABLE),
              java.util.Map.entry(
                  new IllegalStateException("unexpected owner failure"), Status.Code.INTERNAL));
      for (var failure : cases) {
        doThrow(failure.getKey()).when(owner).acknowledgeRetirement(request, principal);
        doThrow(failure.getKey())
            .when(owner)
            .confirmRetirementAcknowledgement(confirmation, principal);
        assertThatThrownBy(
                () -> stub(channel.channel()).acknowledgeCanonicalGameplayRosterRetirement(request))
            .satisfies(
                result ->
                    assertThat(Status.fromThrowable(result).getCode())
                        .isEqualTo(failure.getValue()));
        assertThatThrownBy(
                () ->
                    stub(channel.channel())
                        .confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation))
            .satisfies(
                result ->
                    assertThat(Status.fromThrowable(result).getCode())
                        .isEqualTo(failure.getValue()));
      }
    }
  }

  @Test
  void retirementTransfersDenyWrongWorkloadAndNamespaceBeforeOwner() throws Exception {
    var request = retirementRequest();
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            request,
            CanonicalGameplayRosterRetirementEvidence.response(request, UUID.randomUUID(), 1L));
    start("server");
    for (String identity : List.of("wrong", "foreign")) {
      try (var channel = channel(identity)) {
        assertThatThrownBy(
                () -> stub(channel.channel()).acknowledgeCanonicalGameplayRosterRetirement(request))
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        assertThatThrownBy(
                () ->
                    stub(channel.channel())
                        .confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation))
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
      }
    }
    verifyNoInteractions(owner);
  }

  @Test
  void missingTransferOwnerStaysUnavailableForBothMethods() throws Exception {
    var request = retirementRequest();
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            request,
            CanonicalGameplayRosterRetirementEvidence.response(request, UUID.randomUUID(), 1L));
    start("server", false);
    try (var channel = channel("entity")) {
      assertThatThrownBy(
              () -> stub(channel.channel()).acknowledgeCanonicalGameplayRosterRetirement(request))
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAVAILABLE));
      assertThatThrownBy(
              () ->
                  stub(channel.channel())
                      .confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation))
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAVAILABLE));
    }
    verifyNoInteractions(owner);
  }

  private static net.firedevops.firemud.gamesession.v1
          .CanonicalGameplayRosterRetirementAcknowledgementRequest
      retirementRequest() {
    var original = request();
    var target = CanonicalGameplayRosterReferenceEvidence.target(original);
    var retired =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            CanonicalGameplayRosterReferenceEvidence.response(original, 1L, UUID.randomUUID(), null)
                .toBuilder()
                .setState(
                    net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
                .setTerminalOutcome("DENIED")
                .setRetirementUuid(UUID.randomUUID().toString())
                .setSupersedingPointerDigest("e".repeat(64))
                .setSupersedingPointerVersion(target.getPointerVersion() + 1)
                .setSupersedingCatalogRevision(target.getCatalogRevision())
                .setSupersedingWorldEpoch(target.getActiveWorldEpoch())
                .build());
    return CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
        net.firedevops.firemud.gamesession.v1
            .CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setObservedRetirement(retired)
            .setObservationUuid(UUID.randomUUID().toString())
            .setAdmissionUuid(UUID.randomUUID().toString())
            .setMinimumRetentionMillis(1L)
            .build());
  }

  private void start(String identity) throws Exception {
    start(identity, true);
  }

  private void start(String identity, boolean ownerPresent) throws Exception {
    @SuppressWarnings("unchecked")
    ObjectProvider<CanonicalPublishedPlayerRouteReadService> route = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<CanonicalGameplayRosterReferenceOwner> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(ownerPresent ? owner : null);
    var serverIdentity = pki.identity(identity);
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    new CanonicalGameplayRosterOwnerReadGrpcAdapter(route, NAMESPACE, provider),
                    new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private Channel channel(String alias) throws Exception {
    var identity = pki.identity(alias);
    return new Channel(
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .sslContext(
                GrpcSslContexts.configure(SslContextBuilder.forClient())
                    .trustManager(pki.caCertificate())
                    .keyManager(identity.privateKey(), identity.certificate())
                    .build())
            .build());
  }

  private static CanonicalGameplayRosterOwnerReadServiceGrpc
          .CanonicalGameplayRosterOwnerReadServiceBlockingStub
      stub(ManagedChannel channel) {
    return CanonicalGameplayRosterOwnerReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(
            new net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials(GS_URI))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(GS_URI))
        .withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  private record Channel(ManagedChannel channel) implements AutoCloseable {
    @Override
    public void close() throws Exception {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(2L, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static GetCanonicalGameplayRosterReferenceRequest request() {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setWorldSlug("earth")
            .setRealmSlug("main")
            .setCatalogRevision(7L)
            .setPointerVersion(11L)
            .setActiveWorldEpoch(13L)
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedReleaseBundleRef("release:exact")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return CanonicalGameplayRosterReferenceEvidence.request(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NAMESPACE, target);
  }
}
