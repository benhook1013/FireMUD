package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner.Request;
import net.firedevops.firemud.accountservice.service.impl.AccountPlayerAdmissionEntitlementGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier.VerifiedCaller;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner.AuthenticatedSignerCorrespondence;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Evidence;
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
      when(claims.expiresAtMillis()).thenReturn(f.baseTime.plusSeconds(120).toEpochMilli());
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
                  f.baseTime.minusSeconds(10).getEpochSecond(),
                  f.baseTime.minusSeconds(10).getEpochSecond(),
                  f.baseTime.plusSeconds(120).getEpochSecond(),
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
              Clock.fixed(expected.evaluatedAt(), ZoneOffset.UTC))) {
        client.init();
        var received = client.read(context, request.requestId(), login, () -> true);
        assertThat(received.fields().get("snapshotIdentity"))
            .isEqualTo(expected.fields().get("snapshotIdentity"));
        assertThat(
                f.dsl
                    .fetchSingle(
                        "SELECT canonical_evidence FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
                        received.fields().get("evaluationIdentity"))
                    .get(0, byte[].class))
            .containsExactly(received.canonicalBytes());
        assertThat(received.membershipSource().membershipExists()).isEqualTo(joined);
        assertThat(received.membershipSource().gameplayAdmissionAllowed()).isEqualTo(joined);
        assertThat(new String(received.canonicalBytes(), StandardCharsets.UTF_8))
            .doesNotContain(CONTROLLED_COMPACT_TOKEN);
        assertThatThrownBy(() -> client.read(context, UUID.randomUUID(), login, () -> false))
            .isExactlyInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
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
              f.dsl.connection(
                  connection -> {
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
            Clock.fixed(f.baseTime, ZoneOffset.UTC),
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
        .anySatisfy(
            checkpoint -> {
              assertThat(checkpoint.outboxStreamKey()).contains("membership/");
              assertThat(checkpoint.outboxSequence()).isEqualTo("0");
            });
    assertThat(value.membershipSource().sourceEvidence()).isNotEmpty();
  }

  @Test
  void capacityEvaluationUsesDatabaseTimeWhenApplicationClockIsOneSecondAhead() {
    Clock aheadOfDatabase = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(1));
    Fixture f = fixture(false, aheadOfDatabase);
    assertThat(aheadOfDatabase.instant()).isAfter(databaseNow(f.dsl));

    var value = f.owner.evaluate(f.request(), CONTROLLED_COMPACT_TOKEN);
    Instant databaseAfterEvaluation = databaseNow(f.dsl);
    assertThat(value.evaluatedAt()).isBeforeOrEqualTo(databaseAfterEvaluation);

    var target = (Map<?, ?>) value.fields().get("target");
    var sourceReferences = (Map<?, ?>) value.fields().get("sourceReferences");
    assertThat(target.get("evaluatedAt")).isEqualTo(f.scope.evaluatedAt());
    assertThat(target.get("connectScopeExpiresAt")).isEqualTo(f.scope.connectScopeExpiresAt());
    assertThat(sourceReferences.get("scopeDigest")).isEqualTo(AccountJoinDigest.scopeV2(f.scope));

    var retained =
        f.dsl.fetchOne(
            "SELECT canonical_evidence, evaluated_at, expires_at "
                + "FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
            value.fields().get("evaluationIdentity"));
    assertThat(retained).isNotNull();
    assertThat(retained.get("canonical_evidence", byte[].class))
        .containsExactly(value.canonicalBytes());
    assertThat(AccountTenantCapacityRepository.time(retained, "evaluated_at"))
        .isEqualTo(value.evaluatedAt());
    assertThat(AccountTenantCapacityRepository.time(retained, "expires_at"))
        .isEqualTo(
            value.validUntil().isBefore(value.evaluatedAt().plusSeconds(15))
                ? value.validUntil()
                : value.evaluatedAt().plusSeconds(15));

    var capacity = new AccountTenantCapacityRepository(f.dsl);
    f.transaction.executeWithoutResult(status -> capacity.requireFresh(Evidence.player(value)));
  }

  @Test
  void capacityEvaluationUsesDatabaseTimeWhenApplicationClockTrailsByOneSecond() {
    Clock behindDatabase = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(-1));
    Fixture f = fixture(false, behindDatabase);
    assertThat(behindDatabase.instant()).isBefore(databaseNow(f.dsl));

    var value = f.owner.evaluate(f.request(), CONTROLLED_COMPACT_TOKEN);
    Instant databaseAfterEvaluation = databaseNow(f.dsl);
    assertThat(value.evaluatedAt()).isBeforeOrEqualTo(databaseAfterEvaluation);

    var retained =
        f.dsl.fetchOne(
            "SELECT canonical_evidence, evaluated_at, expires_at "
                + "FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
            value.fields().get("evaluationIdentity"));
    assertThat(retained).isNotNull();
    assertThat(retained.get("canonical_evidence", byte[].class))
        .containsExactly(value.canonicalBytes());
    assertThat(AccountTenantCapacityRepository.time(retained, "evaluated_at"))
        .isEqualTo(value.evaluatedAt());

    var capacity = new AccountTenantCapacityRepository(f.dsl);
    f.transaction.executeWithoutResult(status -> capacity.requireFresh(Evidence.player(value)));
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
            "SELECT canonical_evidence, snapshot_identity FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
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
            .satisfies(
                failure -> {
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
    return fixture(join, Clock.systemUTC());
  }

  private Fixture fixture(boolean join, Clock applicationClock) {
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
    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    Instant baseTime = databaseNow(setupDsl).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    return new Fixture(dataSource, join, baseTime, applicationClock);
  }

  private static Instant databaseNow(DSLContext dsl) {
    return AccountTenantCapacityRepository.time(
        dsl.fetchSingle("SELECT clock_timestamp() AS observed_at"), "observed_at");
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

  private static final class Fixture {
    private final DriverManagerDataSource dataSource;
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final AccountRepository accounts;
    private final AccountTenantMembershipRepository memberships;
    private final AccountDemoTenantEntitlementRepository demos;
    private final Account account;
    private final Instant baseTime;
    private final UUID tenantId = UUID.randomUUID();
    private final CanonicalJoinScopeV2 scope;
    private final AccountPlayerAdmissionEntitlementOwner owner;

    private Fixture(
        DriverManagerDataSource dataSource,
        boolean join,
        Instant baseTime,
        Clock applicationClock) {
      this.dataSource = dataSource;
      this.baseTime = baseTime;
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
              baseTime.minusSeconds(20).toString(),
              baseTime.plusSeconds(120).toString());
      String joinRequestId = UUID.randomUUID().toString();
      String callerBinding = "controlled-join-caller";
      transaction.execute(
          status -> {
            tenants.importVerified(tenantSource);
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
          transaction.execute(
              status ->
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
      transaction.execute(
          status -> {
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
              baseTime.minusSeconds(10).getEpochSecond(),
              baseTime.minusSeconds(10).getEpochSecond(),
              baseTime.plusSeconds(120).getEpochSecond());
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
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                    .isFalse();
                dsl.connection(
                    connection ->
                        assertThat(connection.getTransactionIsolation())
                            .isEqualTo(Connection.TRANSACTION_SERIALIZABLE));
                return Optional.of(candidate);
              });
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
              applicationClock,
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
