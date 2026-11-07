package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginResult;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.service.impl.AccountPlayerAdmissionEntitlementGrpcService;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.ContextClaims;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.AccountPlayerAdmissionEntitlementClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual committed LOGIN recovery/currentness, PostgreSQL DEMO and membership owners, Account
 * receiver and Game Session consumer. Test signer/JWKS custody, initial response delivery and
 * protected published Game Session/World inputs remain controlled. This does not prove public
 * ingress, the default PLAY route, production custody, admission or activation.
 */
class AccountPlayerAdmissionCommittedLoginCompositionIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final String PEER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final Clock CLOCK = Clock.systemUTC();
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture(true);
  private final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest fixtures =
      AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.withPostgres(POSTGRES);
  @TempDir Path directory;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropSchemas() {
    fixtures.dropRunOwnedSchemas();
  }

  @Test
  void actualRecoveredLoginFlowsThroughPlayerAdmissionOwnerAndConsumer() throws Exception {
    fixtures.temporaryDirectory = directory;
    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Actual LOGIN player entitlement composition",
            new LoopbackMtlsTestPki.IdentitySpec("client", PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "account", "spiffe://firemud/ns/test/sa/account-service", true));
    try (var redis =
        AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture
            .start()) {
      var f = fixtures.newPlayerEntitlementFixture(redis.client());
      var identity = f.committedCandidate.identity();
      var initial =
          InitialGameplayLoginResult.fromRecoveredCredential(
              f.responseRecoveryOwner.recoverInitialLoginResponse(
                  identity.requestId(),
                  identity.accountId(),
                  new CallerIdentity(identity.callerWorkload(), identity.callerContextId())));
      var trusted = f.signerFixture.trustedJwksSource();
      var jwks =
          new AccountPublicJwksCache(
              trusted, trusted.sourceIdentity(), CLOCK, Duration.ofSeconds(30));
      var request =
          AuthenticateRequest.newBuilder()
              .setRequestId(initial.requestId().toString())
              .setEmail("controlled-delivery@example.test")
              .setPassword("controlled-input")
              .setSourceContext(
                  GameplayLoginSourceContext.newBuilder()
                      .setCallerContextId(initial.callerContextId().toString())
                      .setCanonicalClientAddress("203.0.113.10")
                      .setTransportClass(
                          GameplayLoginTransportClass
                              .GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
              .build();
      try (var login =
          CanonicalGameplayLoginResult.fromAuthenticatedResponse(
              projectRecoveredInitial(initial),
              request,
              new AccountAsymmetricJwtVerifier(jwks, CLOCK),
              CLOCK)) {
        var scope = f.playerEntitlementScope();
        var protectedTargets = mock(CanonicalGameplayAdmissionDecisionReadClient.class);
        controlledTarget(protectedTargets);
        var owner = f.playerEntitlementOwner(protectedTargets);
        var selector =
            new CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector(
                UUID.randomUUID(),
                scope.accountId(),
                NAMESPACE,
                scope.tenantId(),
                scope.tenantSlug(),
                scope.worldSlug(),
                scope.realmId(),
                scope.realmSlug(),
                scope.playableStateNamespaceId(),
                scope.playableStateScope(),
                scope.gameInstanceId(),
                scope.catalogRevision(),
                scope.pointerVersion());
        var ownerRequest =
            new net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner
                .Request(selector, scope.connectScopeId(), PEER);
        byte[] compact = login.compactJwtBytes();
        net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence
            expected;
        try {
          expected = owner.evaluate(ownerRequest, new String(compact, StandardCharsets.US_ASCII));
        } finally {
          Arrays.fill(compact, (byte) 0);
        }
        var targetFields = (Map<?, ?>) expected.fields().get("target");
        var sources = (Map<?, ?>) expected.fields().get("sourceReferences");
        var target = mock(CanonicalPlayableTarget.class);
        when(target.targetNamespace()).thenReturn(NAMESPACE);
        when(target.canonicalTenantId()).thenReturn(scope.tenantId());
        when(target.tenantSlug()).thenReturn(scope.tenantSlug());
        when(target.worldSlug()).thenReturn(scope.worldSlug());
        when(target.realmId()).thenReturn(scope.realmId());
        when(target.realmSlug()).thenReturn(scope.realmSlug());
        when(target.playableStateNamespaceId()).thenReturn(scope.playableStateNamespaceId());
        when(target.playableStateScope()).thenReturn(scope.playableStateScope());
        when(target.characterCreationPolicy()).thenReturn("PRESEEDED_ONLY");
        when(target.canonicalGameInstanceId()).thenReturn(scope.gameInstanceId());
        when(target.canonicalVersionId())
            .thenReturn(UUID.fromString((String) targetFields.get("canonicalVersionId")));
        when(target.catalogRevision()).thenReturn(scope.catalogRevision());
        when(target.pointerVersion()).thenReturn(scope.pointerVersion());
        when(target.activeWorldEpoch())
            .thenReturn(Long.parseLong((String) targetFields.get("activeWorldEpoch")));
        when(target.admissionPointerSnapshotDigest())
            .thenReturn((String) sources.get("admissionPointerSnapshotDigest"));
        when(target.ownerProofDigest())
            .thenReturn((String) sources.get("gameSessionOwnerProofDigest"));
        var claims = mock(ContextClaims.class);
        when(claims.expiresAtMillis())
            .thenReturn(Instant.parse(scope.connectScopeExpiresAt()).toEpochMilli());
        var context = mock(FirstPartyConnectContext.class);
        when(context.hasCompleteRoutingScope()).thenReturn(true);
        when(context.claims()).thenReturn(claims);
        when(context.accountId()).thenReturn(scope.accountId().toString());
        when(context.connectScopeId()).thenReturn(scope.connectScopeId());
        when(context.resolvedTarget()).thenReturn(target);
        // This per-fixture spy counts entry into the real receiver; it replaces no authority.
        var receiver = spy(new AccountPlayerAdmissionEntitlementGrpcService(owner, NAMESPACE));
        Server server =
            receiver.createUnregisteredServer(
                "127.0.0.1:0",
                pki.keyManagerFactory(pki.identity("account")),
                pki.trustManagerFactory());
        server.start();
        var endpoints = new ServiceEndpointsProperties();
        endpoints.setAccountService("localhost:" + server.getPort());
        try (var client =
            new AccountPlayerAdmissionEntitlementClient(
                endpoints,
                pki.clientProperties(directory, pki.identity("client")),
                new GrpcChannelFactory(),
                NAMESPACE,
                CLOCK)) {
          client.init();
          var result = client.read(context, selector.readRequestId(), login, () -> true);
          assertThat(result.fields())
              .containsEntry("variant", "PLAYER_ADMISSION")
              .containsEntry("sourceKind", "NON_PAID_DEMO");
          assertThat(result.membershipSource().membershipExists()).isTrue();
          assertThat(result.membershipSource().gameplayAdmissionAllowed()).isTrue();
          var receivedSources = (Map<?, ?>) result.fields().get("sourceReferences");
          assertThat(receivedSources.get("callerTokenSha256")).isEqualTo(initial.tokenSha256());
          assertThat(receivedSources.get("callerTokenJti"))
              .isEqualTo(initial.tokenJti().toString());
          verify(receiver).readPlayerAdmissionEntitlement(any(), any());
          assertThatThrownBy(() -> client.read(context, UUID.randomUUID(), login, () -> false))
              .isExactlyInstanceOf(StatusRuntimeException.class)
              .hasMessage("CANCELLED: Account player-admission entitlement read did not complete")
              .satisfies(
                  failure ->
                      assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                          .isEqualTo(Status.Code.CANCELLED));
          verify(receiver).readPlayerAdmissionEntitlement(any(), any());
          // Retaining valid signature bytes and local custody is insufficient after Account moves.
          // The actual caller verifier and receiver classify this currentness loss as UNAVAILABLE.
          f.beginPlayerEntitlementLoginRevocation();
          assertThatThrownBy(() -> client.read(context, UUID.randomUUID(), login, () -> true))
              .isExactlyInstanceOf(StatusRuntimeException.class)
              .hasMessage("UNAVAILABLE: Account player-admission entitlement read did not complete")
              .satisfies(
                  failure ->
                      assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                          .isEqualTo(Status.Code.UNAVAILABLE));
          verify(receiver, times(2)).readPlayerAdmissionEntitlement(any(), any());
          login.close();
          assertThatThrownBy(() -> client.read(context, UUID.randomUUID(), login, () -> true))
              .isExactlyInstanceOf(IllegalStateException.class)
              .hasMessage("Canonical LOGIN private result is closed");
          verify(receiver, times(2)).readPlayerAdmissionEntitlement(any(), any());
        } finally {
          server.shutdownNow();
          assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
      }
    }
  }

  /** Controlled protected-client double: no physical GS/World source provenance is claimed. */
  private static void controlledTarget(CanonicalGameplayAdmissionDecisionReadClient targets) {
    when(targets.resolvePublishedRealmAdmissionTarget(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var selector =
                  invocation.getArgument(
                      0,
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector.class);
              var target = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target.class);
              when(target.targetNamespace()).thenReturn(selector.targetNamespace());
              when(target.canonicalTenantId()).thenReturn(selector.tenantId());
              when(target.tenantSlug()).thenReturn(selector.tenantSlug());
              when(target.worldSlug()).thenReturn(selector.worldSlug());
              when(target.realmId()).thenReturn(selector.realmId());
              when(target.realmSlug()).thenReturn(selector.realmSlug());
              when(target.playableStateNamespaceId())
                  .thenReturn(selector.playableStateNamespaceId());
              when(target.playableStateScope()).thenReturn(selector.playableStateScope());
              when(target.canonicalGameInstanceId()).thenReturn(selector.gameInstanceId());
              when(target.catalogRevision()).thenReturn(selector.catalogRevision());
              when(target.pointerVersion()).thenReturn(selector.pointerVersion());
              when(target.characterCreationPolicy()).thenReturn("PRESEEDED_ONLY");
              when(target.ownerProofOutcome())
                  .thenReturn(
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome.COMMITTED);
              UUID version = UUID.fromString("33333333-3333-4333-8333-333333333333");
              when(target.canonicalVersionId()).thenReturn(version);
              when(target.activeWorldEpoch()).thenReturn(5L);
              var strict =
                  new CanonicalGameplayRosterOwnerReadEvidence.Request(
                      selector.readRequestId(),
                      selector.accountId(),
                      selector.targetNamespace(),
                      selector.tenantId(),
                      selector.worldSlug(),
                      selector.realmId(),
                      selector.realmSlug(),
                      selector.playableStateNamespaceId(),
                      selector.playableStateScope(),
                      selector.gameInstanceId(),
                      version,
                      selector.catalogRevision(),
                      selector.pointerVersion(),
                      5L);
              var resolved =
                  mock(
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.ResolvedPublishedTarget
                          .class);
              when(resolved.selector()).thenReturn(selector);
              when(resolved.currentTarget()).thenReturn(target);
              when(resolved.strictSelector()).thenReturn(strict);
              var read =
                  mock(
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence.class);
              when(read.selector()).thenReturn(strict);
              when(read.currentTarget()).thenReturn(target);
              var source = mock(CanonicalGameplayRosterOwnerReadEvidence.class);
              when(source.admissionPointerSnapshotDigest()).thenReturn("a".repeat(64));
              var policies = mock(PublishedRealmEntryPolicySetEvidence.class);
              when(policies.policySetDigest()).thenReturn("sha256:" + "b".repeat(64));
              when(source.publishedPolicySetEvidence()).thenReturn(policies);
              var ownerProof =
                  mock(
                      net.firedevops.firemud.common.world
                          .GameSessionCanonicalInitialAdmissionOwnerProof.class);
              when(ownerProof.proofDigest()).thenReturn("sha256:" + "c".repeat(64));
              when(source.gameSessionOwnerProof()).thenReturn(ownerProof);
              when(read.sourceEvidence()).thenReturn(source);
              var world = mock(WorldCanonicalInstanceLifecycleEvidence.class);
              when(world.canonicalBytes())
                  .thenReturn(
                      "controlled-complete-world-source"
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
              when(world.captureId())
                  .thenReturn(UUID.fromString("44444444-4444-4444-8444-444444444444"));
              when(world.rowVersion()).thenReturn(2L);
              when(read.worldLifecycleEvidence()).thenReturn(world);
              when(targets.readPublishedRealmAdmissionOwner(strict))
                  .thenAnswer(
                      readCall -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                        return read;
                      });
              return resolved;
            });
  }

  private static AuthenticateResponse projectRecoveredInitial(InitialGameplayLoginResult result) {
    byte[] token = result.compactJwtBytes();
    try {
      return AuthenticateResponse.newBuilder()
          .setAuthToken(new String(token, StandardCharsets.US_ASCII))
          .setAccountId(result.accountId().toString())
          .setRequestId(result.requestId().toString())
          .setCallerContextId(result.callerContextId().toString())
          .setTokenJti(result.tokenJti().toString())
          .setTokenSha256(result.tokenSha256())
          .setTokenProfile(result.profile())
          .setIssuedAtEpochSeconds(result.issuedAtEpochSecond())
          .setNotBeforeEpochSeconds(result.notBeforeEpochSecond())
          .setExpiresAtEpochSeconds(result.expiresAtEpochSecond())
          .setTokenGeneration(result.tokenGeneration())
          .setIssuanceFence(result.issuanceFence())
          .setAuthorityTupleCanonicalJson(ByteString.copyFrom(result.authorityTupleCanonicalJson()))
          .setOutboxCheckpointsCanonicalJson(
              ByteString.copyFrom(result.outboxCheckpointsCanonicalJson()))
          .setOutboxSourceEventEvidenceCanonicalJson(
              ByteString.copyFrom(result.outboxSourceEventEvidenceCanonicalJson()))
          .build();
    } finally {
      Arrays.fill(token, (byte) 0);
    }
  }
}
