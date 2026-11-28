package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import integration.net.firedevops.firemud.accountservice.AccountTenantCreatorBootstrapIntegrationFixture;
import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountCapacityRefusalBudgetRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBinding;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.CapacityException;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner.Request;
import net.firedevops.firemud.accountservice.service.impl.AccountPlayerAdmissionEntitlementGrpcService;
import net.firedevops.firemud.accountservice.service.impl.AccountTenantCapacityGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier.VerifiedCaller;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner.AuthenticatedSignerCorrespondence;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.ContextClaims;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.AccountPlayerAdmissionEntitlementClient;
import net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Physical canonical Account/membership/scope/demo composition and lock/commit proof definitions.
 * JWT/JWKS/Redis caller verification and current committed issuance are controlled doubles here;
 * the protected GS/World client is also controlled. This does not prove custody, actual issuance,
 * paid composition or physical published routing. The explicit loopback composition additionally
 * uses the actual Account receiver and Game Session client over mTLS; its LOGIN and target inputs
 * remain controlled doubles, not genuine custody or production activation.
 */
class AccountPlayerAdmissionEntitlementOwnerPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "player_entitlement_";
  // Syntax-only credential for the explicitly mocked caller verifier, not a signed LOGIN.
  private static final String CONTROLLED_COMPACT_TOKEN = "e30.e30.c2lnbmF0dXJl";
  private static final String NAMESPACE = "player-entitlement-proof";
  private static final String ISSUER = "firemud-account-service";
  private static final String PEER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  @TempDir Path certificateDirectory;
  private final Set<String> schemas = ConcurrentHashMap.newKeySet();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropOwnedSchemas() {
    JdbcTemplate jdbc = new JdbcTemplate(POSTGRES.dataSource());
    for (String schema : schemas) {
      if (!schema.startsWith(SCHEMA_PREFIX) || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalStateException("Refusing cleanup of an unowned schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    schemas.clear();
  }

  @Test
  void controlledOwnerReceiverAndGameSessionClientComposeWithoutGrantingAdmission()
      throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            certificateDirectory,
            "Controlled player admission composition",
            new LoopbackMtlsTestPki.IdentitySpec("client", PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "account", "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service", true));
    for (boolean joined : new boolean[] {false, true}) {
      Fixture f = fixture(joined);
      var request = f.request();
      var expected = f.owner.evaluate(request, CONTROLLED_COMPACT_TOKEN);
      var targetFields = (Map<?, ?>) expected.fields().get("target");
      var sources = (Map<?, ?>) expected.fields().get("sourceReferences");
      // Only the upstream target/custody are doubles. No owner, receiver or consumer is mocked.
      var target = mock(CanonicalPlayableTarget.class);
      when(target.targetNamespace()).thenReturn(NAMESPACE);
      when(target.canonicalTenantId()).thenReturn(f.tenantId);
      when(target.tenantSlug()).thenReturn(f.scope.tenantSlug());
      when(target.worldSlug()).thenReturn(f.scope.worldSlug());
      when(target.realmId()).thenReturn(f.scope.realmId());
      when(target.realmSlug()).thenReturn(f.scope.realmSlug());
      when(target.playableStateNamespaceId()).thenReturn(f.scope.playableStateNamespaceId());
      when(target.playableStateScope()).thenReturn("SHARED");
      when(target.characterCreationPolicy()).thenReturn("PRESEEDED_ONLY");
      when(target.canonicalGameInstanceId()).thenReturn(f.scope.gameInstanceId());
      when(target.canonicalVersionId())
          .thenReturn(UUID.fromString((String) targetFields.get("canonicalVersionId")));
      when(target.catalogRevision()).thenReturn(f.scope.catalogRevision());
      when(target.pointerVersion()).thenReturn(f.scope.pointerVersion());
      when(target.activeWorldEpoch())
          .thenReturn(Long.parseLong((String) targetFields.get("activeWorldEpoch")));
      when(target.admissionPointerSnapshotDigest())
          .thenReturn((String) sources.get("admissionPointerSnapshotDigest"));
      when(target.ownerProofDigest())
          .thenReturn((String) sources.get("gameSessionOwnerProofDigest"));
      var claims = mock(ContextClaims.class);
      when(claims.expiresAtMillis()).thenReturn(NOW.plusSeconds(120).toEpochMilli());
      var context = mock(FirstPartyConnectContext.class);
      when(context.hasCompleteRoutingScope()).thenReturn(true);
      when(context.claims()).thenReturn(claims);
      when(context.accountId()).thenReturn(f.account.getAccountUuid().toString());
      when(context.connectScopeId()).thenReturn(f.scope.connectScopeId());
      when(context.resolvedTarget()).thenReturn(target);
      var login = mock(CanonicalGameplayLoginResult.class);
      when(login.compactJwtBytes())
          .thenAnswer(invocation -> CONTROLLED_COMPACT_TOKEN.getBytes(StandardCharsets.US_ASCII));
      when(login.evidence())
          .thenReturn(
              new CanonicalGameplayLoginResult.NonSecretEvidence(
                  f.account.getAccountUuid(),
                  UUID.randomUUID(),
                  UUID.fromString((String) sources.get("callerContextId")),
                  UUID.fromString((String) sources.get("callerTokenJti")),
                  controlledTokenDigest(),
                  GameSessionAccountDelegationProfile.PROFILE,
                  NOW.minusSeconds(10).getEpochSecond(),
                  NOW.minusSeconds(10).getEpochSecond(),
                  NOW.plusSeconds(120).getEpochSecond(),
                  "1",
                  "1",
                  ByteString.EMPTY,
                  ByteString.EMPTY,
                  ByteString.EMPTY));
      var receiver = new AccountPlayerAdmissionEntitlementGrpcService(f.owner, NAMESPACE);
      var server =
          receiver.createUnregisteredServer(
              "127.0.0.1:0",
              pki.keyManagerFactory(pki.identity("account")),
              pki.trustManagerFactory());
      server.start();
      long membershipsBefore = f.dsl.fetchCount(DSL.table("account_tenant_membership"));
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("localhost:" + server.getPort());
      try (var client =
          new AccountPlayerAdmissionEntitlementClient(
              endpoints,
              pki.clientProperties(certificateDirectory, pki.identity("client")),
              new GrpcChannelFactory(),
              NAMESPACE,
              Clock.fixed(NOW, ZoneOffset.UTC))) {
        client.init();
        var received = client.read(context, request.requestId(), login, () -> true);
        assertThat(received.canonicalBytes()).isEqualTo(expected.canonicalBytes());
        assertThat(received.membershipSource().membershipExists()).isEqualTo(joined);
        assertThat(received.membershipSource().gameplayAdmissionAllowed()).isEqualTo(joined);
        assertThat(new String(received.canonicalBytes(), StandardCharsets.UTF_8))
            .doesNotContain(CONTROLLED_COMPACT_TOKEN);
        assertThatThrownBy(() -> client.read(context, UUID.randomUUID(), login, () -> false))
            .isExactlyInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(failure ->
                assertThat(((io.grpc.StatusRuntimeException) failure).getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.CANCELLED));
        assertThat(f.dsl.fetchCount(DSL.table("account_tenant_membership")))
            .isEqualTo(membershipsBefore);
      } finally {
        server.shutdownNow();
        assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static String controlledTokenDigest() {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(CONTROLLED_COMPACT_TOKEN.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  @Test
  void tenantOperationDemoCapturesCurrentSourceOnActualWritableSerializableConnection() {
    Fixture f = fixture(false);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              f.dsl.connection(connection -> {
                assertThat(connection.getTransactionIsolation())
                    .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
                assertThat(connection.isReadOnly()).isFalse();
              });
              return invocation.callRealMethod();
            })
        .when(f.demos)
        .readCurrent(f.tenantId);
    var owner =
        new AccountTenantOperationEntitlementOwner(
            mock(AccountHostingSubscriptionLifecycleService.class),
            f.demos,
            new DataSourceTransactionManager(f.dataSource),
            Clock.fixed(NOW, ZoneOffset.UTC),
            NAMESPACE);
    var request =
        new AccountTenantOperationEntitlementOwner.Request(
            f.tenantId,
            UUID.randomUUID(),
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service");

    var evidence = owner.evaluateDemo(request);

    assertThat(evidence.fields())
        .containsEntry("variant", "TENANT_OPERATION")
        .containsEntry("sourceKind", "NON_PAID_DEMO")
        .containsEntry("tenantId", f.tenantId.toString())
        .containsEntry("requestId", request.requestId().toString());
  }

  @Test
  void actualAbsenceBaselineReturnsNonadmittingPlayerEvidenceWithoutCreatingMembership() {
    Fixture f = fixture(false);
    long before = f.dsl.fetchCount(DSL.table("account_tenant_membership"));
    var value = f.owner.evaluate(f.request(), CONTROLLED_COMPACT_TOKEN);
    assertThat(value.fields()).containsEntry("variant", "PLAYER_ADMISSION");
    assertThat(value.membershipSource().accountId())
        .isEqualTo(f.account.getAccountUuid().toString());
    assertThat(value.membershipSource().tenantId()).isEqualTo(f.tenantId.toString());
    assertThat(value.membershipSource().membershipExists()).isFalse();
    assertThat(value.membershipSource().membershipLifecycleState()).isEqualTo("MISSING");
    assertThat(value.membershipSource().gameplayAdmissionAllowed()).isFalse();
    assertThat(value.fields()).containsEntry("sourceKind", "NON_PAID_DEMO");
    assertThat(f.dsl.fetchCount(DSL.table("account_tenant_membership"))).isEqualTo(before);
    var missing =
        f.transaction.execute(
            status -> f.memberships.findFreshMembership(f.account.getAccountUuid(), f.tenantId));
    assertThat(missing).isEmpty();
    assertThat(value.membershipSource().checkpoints())
        .anySatisfy(checkpoint -> {
          assertThat(checkpoint.outboxStreamKey()).contains("membership/");
          assertThat(checkpoint.outboxSequence()).isEqualTo("0");
        });
    assertThat(value.membershipSource().sourceEvidence()).isNotEmpty();
  }

  @Test
  void actualFirstJoinRetainsItsPositiveMembershipAndExactScopeBinding() {
    Fixture f = fixture(true);
    var value = f.owner.evaluate(f.request(), CONTROLLED_COMPACT_TOKEN);
    assertThat(value.membershipSource().membershipExists()).isTrue();
    assertThat(value.membershipSource().membershipVersion())
        .containsExactlyEntriesOf(Map.of(f.tenantId.toString(), "2"));
    assertThat(value.membershipSource().membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(value.membershipSource().gameplayAdmissionAllowed()).isTrue();
    var retained =
        f.dsl.fetchOne(
            "SELECT canonical_evidence, snapshot_identity FROM account_tenant_capacity_evaluations"
                + " WHERE evaluation_identity = ?",
            value.fields().get("evaluationIdentity"));
    assertThat(retained).isNotNull();
    byte[] retainedEvidence = retained.get("canonical_evidence", byte[].class);
    assertThat(
            new String(retainedEvidence, StandardCharsets.UTF_8).contains(f.scope.connectScopeId()))
        .isFalse();
    assertThat(retainedEvidence).containsExactly(value.canonicalBytes());
    var decoded = RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(retainedEvidence);
    assertThat(decoded.verifiedConnectScopeTokenHash())
        .isEqualTo(AccountJoinDigest.tokenHash(f.scope.connectScopeId()));
    assertThat(decoded.fields()).doesNotContainKey("verifiedConnectScopeId");
    assertThat(retained.get("snapshot_identity", String.class))
        .isEqualTo(value.fields().get("snapshotIdentity"));
    assertThat(f.dsl.fetchCount(DSL.table("account_tenant_capacity_reservations"))).isZero();
    // This old fixture tenant was imported before creator completion. Eligibility evaluation
    // never invents an empty capacity baseline for it.
    assertThat(f.dsl.fetchCount(DSL.table("account_tenant_capacity_usage"))).isZero();
    var binding = (Map<?, ?>) value.fields().get("target");
    assertThat(binding.get("realmId")).isEqualTo(f.scope.realmId().toString());
    assertThat(binding.get("playableStateNamespaceId"))
        .isEqualTo(f.scope.playableStateNamespaceId().toString());
    assertThat(binding.get("gameInstanceId")).isEqualTo(f.scope.gameInstanceId().toString());
    assertThat(binding.get("catalogRevision")).isEqualTo("7");
    assertThat(binding.get("pointerVersion")).isEqualTo("3");
    assertThat(value.toString()).doesNotContain(f.scope.connectScopeId(), CONTROLLED_COMPACT_TOKEN);
  }

  @Test
  void exactCapacityRefusalRemainsReadableAfterHeadroomReturnsAndEvaluationExpires()
      throws Exception {
    Fixture f = fixture(true, true);
    var request = capacityRequest(f);
    long quota = request.evidence().quota();
    assertThat(quota).isBetween(1L, 10L);
    TenantCapacityAdmission.Request firstHold = null;
    for (long used = 0; used < quota; used++) {
      var occupying = capacityRequest(f);
      var reserved = f.capacityOwner.commit(occupying, PEER);
      assertThat(reserved.state()).isEqualTo(TenantCapacityAdmission.State.RESERVED);
      var retained = f.capacityOwner.readRetainedAdmissionOutcome(occupying, PEER);
      assertThat(retained.reservation()).isEqualTo(reserved);
      assertThat(retained.denial()).isNull();
      if (firstHold == null) {
        firstHold = occupying;
      }
    }
    String readUsage =
        "SELECT held_units, used_units, usage_revision FROM account_tenant_capacity_usage"
            + " WHERE tenant_uuid=? AND owner_service='game-session-service'";
    var usageBeforeDenial = f.dsl.fetchSingle(readUsage, f.tenantId).intoMap();
    assertThat(usageBeforeDenial)
        .containsEntry("held_units", quota)
        .containsEntry("used_units", 0L);
    UUID requestId = request.identity().requestId();
    var decision = new java.util.concurrent.atomic.AtomicReference<UUID>();
    assertThatThrownBy(() -> f.capacityOwner.commit(request, PEER))
        .isInstanceOfSatisfying(
            CapacityException.class,
            error -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(error.code()).isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
              assertThat(error.durableDenialId()).isNotNull();
              decision.set(error.durableDenialId());
            });
    assertThat(f.dsl.fetchSingle(readUsage, f.tenantId).intoMap()).isEqualTo(usageBeforeDenial);
    String readDenial =
        "SELECT *, xmin::text AS row_version FROM account_capacity_origin_decisions"
            + " WHERE tenant_uuid=? AND request_id=? AND admission_id=?";
    Object[] identity = {f.tenantId, requestId, request.identity().admissionId()};
    var retained = f.dsl.fetchSingle(readDenial, identity).intoMap();
    assertThat(retained)
        .containsEntry("decision", "NO_ACCEPT")
        .containsEntry("refusal_code", "CAPACITY_QUOTA_EXCEEDED")
        .containsEntry("decision_id", decision.get());
    String countDeniedReservations =
        "SELECT count(*) AS n FROM account_tenant_capacity_reservations"
            + " WHERE tenant_uuid=? AND request_id=? AND admission_id=?";
    assertThat(f.dsl.fetchSingle(countDeniedReservations, identity).get("n", Long.class)).isZero();

    // Recovery claims are checked against live PostgreSQL time, not the frozen evaluation clock.
    Clock recoveryClock = mock(Clock.class);
    when(recoveryClock.instant())
        .thenAnswer(invocation ->
            f.dsl.fetchSingle("SELECT clock_timestamp()").get(0, OffsetDateTime.class).toInstant());
    // The external no-execution observation is controlled; all Account storage mutations are real.
    var recovery =
        new AccountTenantCapacityRecoveryWorker(
            f.capacityOwner,
            f.dsl,
            (occupying, reservation, deadline) ->
                new AccountTenantCapacityRecoveryWorker.Observation(
                    PEER,
                    reservation,
                    net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest.preimage(
                        occupying),
                    AccountTenantCapacityRecoveryWorker.Disposition
                        .NOT_EXECUTED_AFTER_AUTHORIZATION,
                    occupying.admissionAttemptId(),
                    7,
                    "sha256:" + "e".repeat(64),
                    "controlled-owner-no-execution".getBytes(StandardCharsets.US_ASCII)),
            recoveryClock);
    assertThat(
            recovery
                .reconcile(
                    java.util.Objects.requireNonNull(firstHold),
                    AccountTenantCapacityRecoveryWorker.NoCommitAction.RELEASE)
                .state())
        .isEqualTo(TenantCapacityAdmission.State.RELEASED);
    var usageBeforeReplay = f.dsl.fetchSingle(readUsage, f.tenantId).intoMap();
    assertThat(usageBeforeReplay)
        .containsEntry("held_units", quota - 1)
        .containsEntry("used_units", 0L);
    // The exact durable refusal must win over both returned quota headroom and expired evidence.
    Instant evaluationExpiresAt = request.evidence().expiresAt();
    assertThat(f.clock.instant()).isBefore(evaluationExpiresAt);
    f.clock.setInstant(evaluationExpiresAt.plus(1, ChronoUnit.MICROS));
    assertThat(f.clock.instant()).isAfter(evaluationExpiresAt);
    awaitDatabaseExpiry(f, evaluationExpiresAt);
    var retainedReservations =
        f.dsl
            .fetch(
                "SELECT *, xmin::text AS row_version FROM account_tenant_capacity_reservations "
                    + "ORDER BY reservation_id")
            .intoMaps();
    var retainedSlots =
        f.dsl
            .fetch(
                "SELECT tenant_uuid, slot, decision_id "
                    + "FROM account_capacity_origin_refusal_slots WHERE tenant_uuid = ?",
                f.tenantId)
            .intoMaps();
    assertThat(retainedSlots).hasSize(1);
    assertThat(retainedSlots.get(0)).containsEntry("decision_id", decision.get());
    var pki =
        LoopbackMtlsTestPki.create(
            certificateDirectory,
            "Retained quota denial read",
            new LoopbackMtlsTestPki.IdentitySpec("client", PEER, false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "account", "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service", true));
    var server =
        new AccountTenantCapacityGrpcService(f.capacityOwner, NAMESPACE)
            .createUnregisteredServer(
                "127.0.0.1:0",
                pki.keyManagerFactory(pki.identity("account")),
                pki.trustManagerFactory())
            .start();
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    try (var recovered =
        new AccountTenantCapacityClient(
            endpoints,
            pki.clientProperties(certificateDirectory, pki.identity("client")),
            new GrpcChannelFactory(),
            NAMESPACE,
            f.clock)) {
      recovered.init();
      var refusal = recovered.readRetainedAdmissionDenial(request, f.clock.millis() + 5_000L);
      assertThat(refusal.code()).isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
      assertThat(refusal.durableDenialId()).isEqualTo(decision.get());
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThatThrownBy(() -> f.capacityOwner.commit(request, PEER))
        .isInstanceOfSatisfying(
            CapacityException.class,
            error -> {
              assertThat(error.code()).isEqualTo(Failure.CAPACITY_QUOTA_EXCEEDED);
              assertThat(error.durableDenialId()).isEqualTo(decision.get());
            });
    assertThat(f.dsl.fetchSingle(readUsage, f.tenantId).intoMap()).isEqualTo(usageBeforeReplay);
    assertThat(f.dsl.fetchSingle(readDenial, identity).intoMap())
        .usingRecursiveComparison()
        .isEqualTo(retained);
    assertThat(
            f.dsl
                .fetch(
                    "SELECT *, xmin::text AS row_version FROM account_tenant_capacity_reservations "
                        + "ORDER BY reservation_id")
                .intoMaps())
        .usingRecursiveComparison()
        .isEqualTo(retainedReservations);
    assertThat(
            f.dsl
                .fetch(
                    "SELECT tenant_uuid, slot, decision_id "
                        + "FROM account_capacity_origin_refusal_slots WHERE tenant_uuid = ?",
                    f.tenantId)
                .intoMaps())
        .isEqualTo(retainedSlots);
    assertThat(f.dsl.fetchCount(DSL.table("account_capacity_origin_decisions")))
        .isEqualTo(Math.toIntExact(quota + 1));
    assertThat(f.dsl.fetchSingle(countDeniedReservations, identity).get("n", Long.class)).isZero();
  }

  @Test
  void expiredOriginalEvaluationRetainsOnlyTheExactDenial() {
    Fixture f = fixture(true, true);
    var request = capacityRequest(f);
    var expiry = request.evidence().expiresAt();
    awaitDatabaseExpiry(f, expiry);
    String usage =
        "SELECT held_units, used_units, usage_revision FROM account_tenant_capacity_usage"
            + " WHERE tenant_uuid=? AND owner_service='game-session-service'";
    var originalUsage = f.dsl.fetchSingle(usage, f.tenantId).intoMap();
    f.clock.setInstant(expiry.minus(1, ChronoUnit.MICROS));
    assertThatThrownBy(() -> f.capacityOwner.commit(request, PEER))
        .isInstanceOfSatisfying(
            CapacityException.class,
            error -> {
              assertThat(error.code()).isEqualTo(Failure.CAPACITY_ADMISSION_STALE);
              assertThat(error.durableDenialId()).isNull();
            });
    assertThat(f.dsl.fetchSingle(usage, f.tenantId).intoMap()).isEqualTo(originalUsage);
    assertThat(f.dsl.fetchCount(DSL.table("account_tenant_capacity_reservations"))).isZero();
    assertThat(f.dsl.fetchCount(DSL.table("account_capacity_origin_decisions"))).isZero();
    assertThat(f.dsl.fetchCount(DSL.table("account_capacity_origin_refusal_slots"))).isZero();

    f.clock.setInstant(expiry.plus(1, ChronoUnit.MICROS));
    var decisionId = new AtomicReference<UUID>();
    assertThatThrownBy(() -> f.capacityOwner.commit(request, PEER))
        .isInstanceOfSatisfying(
            CapacityException.class,
            error -> {
              assertThat(error.code()).isEqualTo(Failure.CAPACITY_EVALUATION_EXPIRED);
              assertThat(error.durableDenialId()).isNotNull();
              decisionId.set(error.durableDenialId());
            });

    var retained = f.capacityOwner.readRetainedAdmissionOutcome(request, PEER).denial();
    assertThat(retained.code()).isEqualTo(Failure.CAPACITY_EVALUATION_EXPIRED);
    assertThat(retained.durableDenialId()).isEqualTo(decisionId.get());
    assertThatThrownBy(() -> f.capacityOwner.commit(request, PEER))
        .isInstanceOfSatisfying(
            CapacityException.class,
            error -> {
              assertThat(error.code()).isEqualTo(Failure.CAPACITY_EVALUATION_EXPIRED);
              assertThat(error.durableDenialId()).isEqualTo(decisionId.get());
            });
    assertThat(f.dsl.fetchSingle(usage, f.tenantId).intoMap()).isEqualTo(originalUsage);
    assertThat(f.dsl.fetchCount(DSL.table("account_tenant_capacity_reservations"))).isZero();
    assertThat(f.dsl.fetchCount(DSL.table("account_capacity_origin_refusal_slots"))).isOne();
  }

  private static void awaitDatabaseExpiry(Fixture f, Instant expiry) {
    org.awaitility.Awaitility.await("PostgreSQL reaches the retained evaluation expiry")
        .atMost(java.time.Duration.ofSeconds(20))
        .until(() ->
            !f.dsl
                .fetchSingle("SELECT clock_timestamp()")
                .get(0, OffsetDateTime.class)
                .toInstant()
                .isBefore(expiry));
  }

  private static TenantCapacityAdmission.Request capacityRequest(Fixture f) {
    var entitlementRequest = f.request();
    var entitlement = f.owner.evaluate(entitlementRequest, CONTROLLED_COMPACT_TOKEN);
    var evidence =
        TenantCapacityAdmission.Evidence.parse(
            entitlement.canonicalBytes(), TenantCapacityAdmission.Owner.GAME_SESSION);
    UUID requestId = entitlementRequest.requestId();
    return new TenantCapacityAdmission.Request(
            new TenantCapacityAdmission.Identity(f.tenantId, requestId, UUID.randomUUID()),
            UUID.randomUUID(),
            1,
            NAMESPACE,
            new TenantCapacityAdmission.PlayerContext(
                f.account.getAccountUuid(),
                f.tenantId,
                f.scope.playableStateNamespaceId(),
                f.scope.gameInstanceId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                f.scope.realmId(),
                requestId,
                f.scope.playableStateScope()),
            TenantCapacityAdmission.Operation.CAPACITY_CREATING,
            new TenantCapacityAdmission.Shape(0, 0, 0),
            new TenantCapacityAdmission.Shape(1, 0, 0),
            evidence,
            1L,
            null)
        .withDigest();
  }

  @Test
  void blockedAccountSourceReadIsCancelledWithinOwnerTransactionBudget() throws Exception {
    Fixture f = fixture(false);
    try (Connection blocker = f.dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (var lock =
          blocker.prepareStatement(
              "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE")) {
        lock.setObject(1, f.account.getAccountUuid());
        lock.executeQuery().close();
        // No database-side lock/statement timeout is installed. The actual transaction-aware
        // jOOQ connection must propagate the owner's five-second JDBC query timeout.
        assertThatThrownBy(() -> f.owner.evaluate(f.request(), CONTROLLED_COMPACT_TOKEN))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .satisfies(failure -> {
              Throwable cause = failure;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) cause).getSQLState()).isEqualTo("57014");
            });
      } finally {
        blocker.rollback();
      }
    }
    // Cancellation rolls back the owner transaction and does not damage a later current read.
    assertThat(f.owner.evaluate(f.request(), CONTROLLED_COMPACT_TOKEN).fields())
        .containsEntry("variant", "PLAYER_ADMISSION");
  }

  @Test
  void captureHoldsActualAccountWriterLockAndFailureBeforeCommitExposesNoValue() {
    Fixture f = fixture(false);
    AtomicBoolean competingWriterRefused = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Object snapshot = invocation.callRealMethod();
              try (Connection contender = f.dataSource.getConnection()) {
                contender.setAutoCommit(false);
                try (var statement = contender.createStatement()) {
                  statement.execute("SET LOCAL lock_timeout = '150ms'");
                  try (var select =
                      contender.prepareStatement(
                          "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE")) {
                    select.setObject(1, f.account.getAccountUuid());
                    try {
                      select.executeQuery().close();
                      throw new AssertionError(
                          "Account source lock did not exclude a competing writer");
                    } catch (SQLException expectedLockTimeout) {
                      assertThat(expectedLockTimeout.getSQLState()).isEqualTo("55P03");
                      competingWriterRefused.set(true);
                    }
                  } finally {
                    contender.rollback();
                  }
                }
              }
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                      throw new IllegalStateException("controlled final source commit refusal");
                    }
                  });
              return snapshot;
            })
        .when(f.demos)
        .readCurrent(f.tenantId);
    assertThatThrownBy(() -> f.owner.evaluate(f.request(), CONTROLLED_COMPACT_TOKEN))
        .hasMessageContaining("controlled final source commit refusal");
    assertThat(competingWriterRefused.get()).isTrue();
    assertThat(f.dsl.fetchCount(DSL.table("account_tenant_capacity_evaluations"))).isZero();
    var unlocked =
        f.transaction.execute(
            status -> f.accounts.findByAccountUuidForUpdate(f.account.getAccountUuid()));
    assertThat(unlocked).isPresent();
  }

  private Fixture fixture(boolean join) {
    return fixture(join, false);
  }

  private Fixture fixture(boolean join, boolean initializeCapacity) {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    return new Fixture(dataSource, join, initializeCapacity);
  }

  /** Controlled protected-client double: no physical GS/World source provenance is claimed. */
  private static void controlledTarget(CanonicalGameplayAdmissionDecisionReadClient targets) {
    when(targets.resolvePublishedRealmAdmissionTarget(any()))
        .thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          var selector =
              invocation.getArgument(
                  0, CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector.class);
          var target = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target.class);
          when(target.targetNamespace()).thenReturn(selector.targetNamespace());
          when(target.canonicalTenantId()).thenReturn(selector.tenantId());
          when(target.tenantSlug()).thenReturn(selector.tenantSlug());
          when(target.worldSlug()).thenReturn(selector.worldSlug());
          when(target.realmId()).thenReturn(selector.realmId());
          when(target.realmSlug()).thenReturn(selector.realmSlug());
          when(target.playableStateNamespaceId()).thenReturn(selector.playableStateNamespaceId());
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
              mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.ResolvedPublishedTarget.class);
          when(resolved.selector()).thenReturn(selector);
          when(resolved.currentTarget()).thenReturn(target);
          when(resolved.strictSelector()).thenReturn(strict);
          var read =
              mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence.class);
          when(read.selector()).thenReturn(strict);
          when(read.currentTarget()).thenReturn(target);
          var source = mock(CanonicalGameplayRosterOwnerReadEvidence.class);
          when(source.admissionPointerSnapshotDigest()).thenReturn("a".repeat(64));
          var policies = mock(PublishedRealmEntryPolicySetEvidence.class);
          when(policies.policySetDigest()).thenReturn("sha256:" + "b".repeat(64));
          when(source.publishedPolicySetEvidence()).thenReturn(policies);
          var ownerProof =
              mock(
                  net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof
                      .class);
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
              .thenAnswer(readCall -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return read;
              });
          return resolved;
        });
  }

  private static final class Fixture {
    private final DriverManagerDataSource dataSource;
    private final DSLContext dsl;
    private final Instant now;
    private final MutableClock clock;
    private final TransactionTemplate transaction;
    private final AccountRepository accounts;
    private final AccountTenantMembershipRepository memberships;
    private final AccountDemoTenantEntitlementRepository demos;
    private final Account account;
    private final UUID tenantId = UUID.randomUUID();
    private final CanonicalJoinScopeV2 scope;
    private final AccountPlayerAdmissionEntitlementOwner owner;
    private final AccountTenantCapacityOwner capacityOwner;

    private Fixture(DriverManagerDataSource dataSource, boolean join, boolean initializeCapacity) {
      this.dataSource = dataSource;
      now = initializeCapacity ? Instant.now().truncatedTo(ChronoUnit.MICROS) : NOW;
      clock = new MutableClock(now, ZoneOffset.UTC);
      dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      var transactions = new DataSourceTransactionManager(dataSource);
      transaction = new TransactionTemplate(transactions);
      accounts = new AccountRepository(dsl);
      var tenants = new FreshTenantIdentityAssociationRepository(dsl, NAMESPACE);
      var pairs = new AccountMembershipPairAuthorityRepository(dsl);
      memberships = new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);
      var roles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
      var generations = new AccountAuthorityGenerationRepository(dsl);
      var outbox = new AccountAuthorityOutboxRepository(dsl);
      var sources = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
      var billing = new AccountTenantEntitlementOutboxRepository(dsl);
      var tenantEvents =
          new AccountTenantAuthorityEventRepository(dsl, outbox, billing, tenants, generations);
      demos =
          spy(
              new AccountDemoTenantEntitlementRepository(
                  dsl, tenants, generations, billing, tenantEvents));
      var scopes =
          new AccountConnectScopeRepository(
              dsl,
              new AccountTenantIdentityResolver(
                  mock(ApprovedLegacyTenantAssociationRepository.class), NAMESPACE),
              tenants);
      var operations = new AccountJoinOperationRepository(dsl, scopes);
      var membershipProducer =
          new AccountMembershipAuthorityEventProducer(
              operations,
              accounts,
              pairs,
              memberships,
              roles,
              generations,
              outbox,
              sources,
              tenantEvents,
              demos);
      var terminal =
          new AccountCanonicalFirstJoinTerminalCoordinator(
              accounts,
              operations,
              memberships,
              roles,
              outbox,
              pairs,
              new AccountAuditOutboxRepository(dsl),
              membershipProducer);
      var snapshots =
          new AccountCanonicalRuntimeMembershipSnapshotRepository(
              accounts,
              generations,
              sources,
              tenantEvents,
              tenants,
              memberships,
              pairs,
              roles,
              outbox);
      Account input = new Account();
      String suffix = UUID.randomUUID().toString().replace("-", "");
      input.setUsername("player-" + suffix);
      input.setEmail("player-entitlement-" + suffix + "@example.test");
      input.setPasswordHash("controlled-password-hash");
      // Gameplay player roles belong to the canonical tenant membership.
      account = transaction.execute(status -> accounts.save(input));
      if (account == null) throw new IllegalStateException("Account insertion is absent");
      FreshTenantCreationEvidence tenantSource = tenantSource(tenantId);
      var provenance =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenantSource.operationId(),
              tenantSource.evidenceDigest());
      scope =
          new CanonicalJoinScopeV2(
              "scope-" + UUID.randomUUID(),
              account.getAccountUuid(),
              tenantId,
              UUID.randomUUID(),
              "tenant",
              "world",
              "production",
              UUID.randomUUID(),
              "SHARED",
              UUID.randomUUID(),
              7,
              3,
              now.minusSeconds(20).toString(),
              now.plusSeconds(120).toString());
      String joinRequestId = UUID.randomUUID().toString();
      String callerBinding = "controlled-join-caller";
      transaction.execute(status -> {
        tenants.importVerified(tenantSource);
        if (initializeCapacity) {
          var bootstrap = new AccountTenantCreationBootstrapOperationRepository(dsl);
          var claim =
              AccountTenantCreatorBootstrapIntegrationFixture.claimBootstrap(
                  bootstrap, account, tenantSource);
          bootstrap.complete(
              claim.requestId(), AccountTenantCreatorBootstrapIntegrationFixture.completion(claim));
          new AccountCapacityRefusalBudgetRepository(dsl).provision(tenantId, 1);
        }
        sources.initializeIssuerIfAbsent(ISSUER);
        generations.initializeTenantIfAbsent(tenantId);
        generations.initialize(
            AccountAuthorityGenerationRepository.AuthorityScope.membership(
                account.getAccountUuid(), tenantId));
        scopes.insertCanonical(account.getId(), scope, provenance);
        operations.insertCanonicalIntent(joinRequestId, scope, callerBinding);
        return null;
      });
      var entitlement =
          transaction.execute(status ->
              demos.provision(
                  new DemoTenantEntitlementRequest(
                      UUID.randomUUID(),
                      tenantId,
                      tenantSource.creationRequestId(),
                      tenantSource.requestDigest(),
                      null,
                      null,
                      null,
                      true,
                      true,
                      true,
                      true,
                      new DemoTenantEntitlementRequest.Quotas(3, 2, 4096)),
                  tenantSource));
      if (entitlement == null) throw new IllegalStateException("Demo source is absent");
      transaction.execute(status -> {
        operations.bindCanonicalPolicyEvidence(
            joinRequestId,
            scope,
            callerBinding,
            entitlement.allowPublicJoin(),
            entitlement.entitlementVersion());
        if (join) {
          terminal.commitCanonicalFirstJoin(scope, joinRequestId, callerBinding);
        } else {
          accounts.findByAccountUuidForUpdate(account.getAccountUuid()).orElseThrow();
          memberships.prepareFreshCreatorAbsence(account.getAccountUuid(), tenantId);
        }
        return null;
      });
      var issuances = mock(AccountGameplayDelegationIssuanceRepository.class);
      var callers = mock(AccountGameplayAdmissionCallerVerifier.class);
      var identity =
          new AccountGameplayDelegationPendingIdentity(
              UUID.randomUUID(),
              UUID.randomUUID(),
              account.getAccountUuid(),
              PEER,
              UUID.randomUUID(),
              mock(AccountGameplayCredentialRequestBinding.class),
              "a".repeat(64),
              UUID.randomUUID(),
              now.minusSeconds(10).getEpochSecond(),
              now.minusSeconds(10).getEpochSecond(),
              now.plusSeconds(120).getEpochSecond());
      var candidate = mock(CommittedCandidateVerificationData.class);
      when(candidate.identity()).thenReturn(identity);
      when(candidate.authoritySnapshot())
          .thenReturn(
              new AccountAuthoritySnapshot(
                  account.getAccountUuid(), 1, 1, 1, 1, 1, 1, Optional.empty()));
      when(candidate.evidenceBundleReference()).thenReturn(mock(EvidenceBundleReference.class));
      when(candidate.signerCorrespondence())
          .thenReturn(mock(AuthenticatedSignerCorrespondence.class));
      when(candidate.tokenSha256()).thenReturn(controlledTokenDigest());
      when(candidate.signerKid()).thenReturn("controlled-kid");
      when(candidate.signerGeneration()).thenReturn("1");
      when(candidate.canonicalRegistryRecordSha256()).thenReturn("b".repeat(64));
      when(candidate.commitProofSha256()).thenReturn("c".repeat(64));
      when(candidate.envelopeKeyId()).thenReturn("controlled-key");
      when(candidate.envelopeSha256()).thenReturn("d".repeat(64));
      var caller = mock(VerifiedCaller.class);
      when(caller.identity()).thenReturn(identity);
      when(caller.currentCandidate()).thenReturn(candidate);
      when(callers.verifyPresentedCompactToken(CONTROLLED_COMPACT_TOKEN)).thenReturn(caller);
      when(issuances.readCurrentCommittedCandidateByJti(identity.tokenJti()))
          .thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            dsl.connection(connection ->
                assertThat(connection.getTransactionIsolation())
                    .isEqualTo(Connection.TRANSACTION_SERIALIZABLE));
            return Optional.of(candidate);
          });
      capacityOwner =
          new AccountTenantCapacityOwner(
              dsl, accounts, snapshots, issuances, scopes, transactions, clock, NAMESPACE);
      var targets = mock(CanonicalGameplayAdmissionDecisionReadClient.class);
      controlledTarget(targets);
      owner =
          new AccountPlayerAdmissionEntitlementOwner(
              dsl,
              accounts,
              callers,
              issuances,
              snapshots,
              scopes,
              mock(AccountHostingSubscriptionLifecycleRepository.class),
              mock(StripeHostingSubscriptionObserver.class),
              demos,
              tenantEvents,
              targets,
              transactions,
              clock,
              NAMESPACE);
    }

    private Request request() {
      return new Request(
          new CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector(
              UUID.randomUUID(),
              account.getAccountUuid(),
              NAMESPACE,
              tenantId,
              scope.tenantSlug(),
              scope.worldSlug(),
              scope.realmId(),
              scope.realmSlug(),
              scope.playableStateNamespaceId(),
              scope.playableStateScope(),
              scope.gameInstanceId(),
              scope.catalogRevision(),
              scope.pointerVersion()),
          scope.connectScopeId(),
          PEER);
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    private MutableClock(Instant instant, ZoneId zone) {
      this(new AtomicReference<>(instant), zone);
    }

    private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
      this.current = current;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
      return zone.equals(newZone) ? this : new MutableClock(current, newZone);
    }

    @Override
    public Instant instant() {
      return current.get();
    }

    private void setInstant(Instant instant) {
      current.set(instant);
    }
  }

  private static FreshTenantCreationEvidence tenantSource(UUID tenantId) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceKey = "w-" + UUID.randomUUID().toString().replace("-", "");
    String requestDigest =
        GameTenantCreationDigest.requestDigest(NAMESPACE, requestId, sourceKey, "World", null);
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        requestId,
        operationId,
        requestDigest,
        tenantId,
        17,
        sourceKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            requestId,
            operationId,
            requestDigest,
            tenantId,
            17,
            sourceKey,
            "NEW_GAME_ROW"));
  }
}
