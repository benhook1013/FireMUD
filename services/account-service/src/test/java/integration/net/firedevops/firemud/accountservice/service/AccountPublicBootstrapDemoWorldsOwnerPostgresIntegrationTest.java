package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real pre-V1028 Account SQL, demo mutations, source-head selection and discovery composition.
 * Bootstrap JWT signing uses a local test key and the actual caller verifier/persisted Account; the
 * active-session registry and complete Game Session collection are controlled inputs. Fresh Game
 * Design evidence is imported as a fixture, not obtained from an authenticated owner call. The
 * canonical public-scope cases additionally use real membership/pair, V2 scope persistence and
 * retained-selector reads. Expected reader Account UUIDs are comparison inputs from this fixture,
 * not caller authentication performed by the reader. This proves neither paid hosting, issuer
 * custody, workload provenance, a World hold, private support nor gameplay admission; a retained
 * selector is not PLAYER entitlement or JOIN permission.
 */
class AccountPublicBootstrapDemoWorldsOwnerPostgresIntegrationTest {
  private static final String NAMESPACE = "demo-worlds-proof";
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private static final List<String> OBSERVED_TABLES =
      List.of(
          "accounts",
          "account_tenant_membership",
          "account_membership_pair_authority",
          "account_connect_scope_records",
          "account_join_operations",
          "account_audit_outbox",
          "account_authority_generations",
          "account_authority_issuance_fences",
          "account_authority_outbox_streams",
          "account_authority_outbox_events",
          "account_tenant_authority_source_records",
          "account_tenant_entitlement_outbox_streams",
          "account_tenant_entitlement_outbox_events",
          "account_demo_tenant_entitlement_operations",
          "account_demo_tenant_entitlements");
  private final Set<String> ownedSchemas = new LinkedHashSet<>();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterEach
  void removeOwnedSchemas() {
    SessionContext.clear();
    JdbcTemplate jdbc = new JdbcTemplate(POSTGRES.dataSource());
    for (String schema : ownedSchemas) {
      jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    ownedSchemas.clear();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void actualDemoHeadsProduceSortedPublicWorldsWithoutMembershipJoinOrAnyReadMutation() {
    Fixture f = fixture();
    DemoTenant zulu = f.demo("zulu", true);
    DemoTenant alpha = f.demo("alpha", true);
    DemoTenant denied = f.demo("closed", false);
    f.targetReads(
        List.of(zulu.target(), denied.target(), alpha.target()),
        List.of(alpha.target(), zulu.target(), denied.target()));
    Map<String, String> before = f.snapshot();

    assertThat(f.worlds.listWorlds(f.token))
        .containsExactly(
            new BootstrapWorldDto(alpha.target().tenantSlug(), "alpha", "World alpha"),
            new BootstrapWorldDto(zulu.target().tenantSlug(), "zulu", "World zulu"));
    assertThat(f.worlds.listWorlds(f.token))
        .containsExactly(
            new BootstrapWorldDto(alpha.target().tenantSlug(), "alpha", "World alpha"),
            new BootstrapWorldDto(zulu.target().tenantSlug(), "zulu", "World zulu"));

    assertThat(f.snapshot()).isEqualTo(before);
    assertThat(f.inTransaction(() -> f.sources.readCurrentSourceSelection(alpha.tenantId()).kind()))
        .isEqualTo(SourceKind.NON_PAID_DEMO);
    assertThat(alpha.snapshot().subscriptionStatus()).isNull();
    assertThat(alpha.snapshot().paid()).isFalse();
    assertThat(alpha.snapshot().allowPublicJoin()).isFalse();
    f.assertNoAdmissionRowsOrPaidReads();
    verify(f.targets, times(4)).listVisiblePublicProductionAdmissionTargets(any());
    verify(f.sessions, times(4)).isAccountSessionActive(f.account.getId(), f.token);
  }

  @Test
  void missingDemoAndSourceHeadRefuseTheWholeListWithoutPaidOrFreeFallback() {
    Fixture f = fixture();
    DemoTenant available = f.demo("available", true);
    FreshTenantCreationEvidence missing = f.enrollTenant();
    Target missingTarget = target(missing.canonicalTenantId(), "missing", 1, f.clock.instant());
    f.targetReads(
        List.of(available.target(), missingTarget), List.of(available.target(), missingTarget));
    Map<String, String> before = f.snapshot();

    assertCode("ENTITLEMENT_UNAVAILABLE", () -> f.worlds.listWorlds(f.token));

    assertThat(f.snapshot()).isEqualTo(before);
    f.assertNoAdmissionRowsOrPaidReads();
    verify(f.targets).listVisiblePublicProductionAdmissionTargets(any());
  }

  @Test
  void realDemoMutationBetweenSourceTransactionsInvalidatesTheOriginalSelectedHead() {
    Fixture f = fixture();
    DemoTenant tenant = f.demo("changing", true);
    f.targetReads(List.of(tenant.target()), List.of(tenant.target()));
    AtomicReference<Map<String, String>> afterMutation = new AtomicReference<>();
    f.clock.afterNextObservation.set(
        () -> {
          DemoTenantEntitlementSnapshot updated = f.update(tenant, false);
          assertThat(updated.entitlementVersion()).isEqualTo(2L);
          afterMutation.set(f.snapshot());
        });

    assertCode("ENTITLEMENT_UNAVAILABLE", () -> f.worlds.listWorlds(f.token));

    assertThat(afterMutation.get()).isNotNull();
    assertThat(f.snapshot()).isEqualTo(afterMutation.get());
    assertThat(f.inTransaction(() -> f.demos.readCurrent(tenant.tenantId())).gameplayAvailable())
        .isFalse();
    f.assertNoAdmissionRowsOrPaidReads();
  }

  @Test
  void originalDemoObservationExpiresDuringFinalCollectionReadWithoutRestamping() {
    Fixture f = fixture();
    DemoTenant tenant = f.demo("expiring", true);
    f.targetReads(List.of(tenant.target()), List.of(tenant.target()));
    f.onSecondTargetRead = () -> f.clock.offset.set(Duration.ofSeconds(16));
    Map<String, String> before = f.snapshot();

    assertCode("ENTITLEMENT_UNAVAILABLE", () -> f.worlds.listWorlds(f.token));

    assertThat(f.snapshot()).isEqualTo(before);
    f.assertNoAdmissionRowsOrPaidReads();
    verify(f.targets, times(2)).listVisiblePublicProductionAdmissionTargets(any());
  }

  @Test
  void ambiguousWorldSlugAcrossTwoRealDemoTenantsRefusesInsteadOfChoosingOne() {
    Fixture f = fixture();
    DemoTenant first = f.demo("same-world", true);
    DemoTenant second = f.demo("same-world", true);
    f.targetReads(
        List.of(first.target(), second.target()), List.of(second.target(), first.target()));
    Map<String, String> before = f.snapshot();

    assertCode("ADMISSION_POINTER_UNAVAILABLE", () -> f.worlds.listWorlds(f.token));

    assertThat(f.snapshot()).isEqualTo(before);
    f.assertNoAdmissionRowsOrPaidReads();
  }

  @Test
  void changedCompleteTargetCollectionRefusesWithRealDemoAuthorityUntouched() {
    Fixture f = fixture();
    DemoTenant tenant = f.demo("moving", true);
    Target changed = target(tenant.tenantId(), "moving", 2, f.clock.instant());
    f.targetReads(List.of(tenant.target()), List.of(changed));
    Map<String, String> before = f.snapshot();

    assertCode("ADMISSION_POINTER_UNAVAILABLE", () -> f.worlds.listWorlds(f.token));

    assertThat(f.snapshot()).isEqualTo(before);
    f.assertNoAdmissionRowsOrPaidReads();
  }

  @Test
  void lostBootstrapRegistryCurrentnessRefusesAfterActualPersistedAccountReverification() {
    Fixture f = fixture();
    DemoTenant tenant = f.demo("caller-loss", true);
    f.targetReads(List.of(tenant.target()), List.of(tenant.target()));
    f.onSecondTargetRead = () -> f.sessionActive.set(false);
    Map<String, String> before = f.snapshot();

    assertCode("CONNECT_CONTEXT_INVALID", () -> f.worlds.listWorlds(f.token));

    assertThat(f.snapshot()).isEqualTo(before);
    assertThat(f.accounts.findByAccountUuid(f.account.getAccountUuid())).isPresent();
    f.assertNoAdmissionRowsOrPaidReads();
    verify(f.sessions, times(2)).isAccountSessionActive(f.account.getId(), f.token);
  }

  @Test
  void absentMembershipAndClosedEnrollmentPersistOnlyTheExactCanonicalPublicSelectorScope() {
    Fixture f = fixture();
    DemoTenant tenant = f.demo("scope-proof", true);
    f.targetReads(List.of(tenant.target()), List.of(tenant.target()));
    Map<String, String> before = f.snapshot();

    var result =
        f.realmScopes
            .listRealmScopes(f.token, tenant.target().tenantSlug(), tenant.target().worldSlug())
            .getFirst();

    var scope = result.scope();
    assertThat(result.target()).isEqualTo(tenant.target());
    assertThat(result.membership().accountId()).isEqualTo(f.account.getAccountUuid());
    assertThat(result.membership().tenantId()).isEqualTo(tenant.tenantId());
    assertThat(result.membership().current()).isEmpty();
    assertThat(result.entitlement().sourceKind()).isEqualTo(SourceKind.NON_PAID_DEMO);
    assertThat(result.entitlement().gameplayAvailable()).isTrue();
    assertThat(result.entitlement().allowPublicJoin()).isFalse();
    assertThat(result.entitlement().tenantBillingSequence())
        .isEqualTo(tenant.snapshot().tenantBillingSequence());
    assertThat(scope.accountId()).isEqualTo(f.account.getAccountUuid());
    assertThat(scope.tenantId()).isEqualTo(tenant.tenantId());
    assertThat(scope.realmId()).isEqualTo(tenant.target().realmId());
    assertThat(scope.playableStateNamespaceId())
        .isEqualTo(tenant.target().playableStateNamespaceId());
    assertThat(scope.gameInstanceId()).isEqualTo(tenant.target().canonicalGameInstanceId());
    assertThat(scope.playableStateScope()).isEqualTo("SHARED");
    var signed = f.jwt.parseToken(scope.connectScopeId()).getPayload();
    assertThat(signed.getSubject()).isEqualTo(f.account.getAccountUuid().toString());
    assertThat(signed.get("accountId")).isEqualTo(f.account.getAccountUuid().toString());
    assertThat(JwtClaims.requireText(signed.get("aud"), "aud"))
        .isEqualTo("bootstrap-connect-scope");
    assertThat(signed.get("targetClass")).isEqualTo("PUBLIC_PRODUCTION");
    assertThat(JwtClaims.requireLong(signed.get("scopeDigestVersion"), "scopeDigestVersion", false))
        .isEqualTo(2L);
    assertThat(signed.get("tenantId")).isEqualTo(tenant.tenantId().toString());
    assertThat(signed.get("gameInstanceId"))
        .isEqualTo(tenant.target().canonicalGameInstanceId().toString());
    assertThat(signed.get("evaluatedAt")).isEqualTo(scope.evaluatedAt());
    assertThat(signed.get("connectScopeExpiresAt")).isEqualTo(scope.connectScopeExpiresAt());
    assertThat(signed.getExpiration().toInstant())
        .isEqualTo(Instant.parse(scope.connectScopeExpiresAt()));
    assertThat(signed.getExpiration().toInstant())
        .isBeforeOrEqualTo(f.jwt.parseToken(f.token).getPayload().getExpiration().toInstant());
    assertThat(f.inTransaction(() -> f.scopes.findCanonical(scope.connectScopeId()).orElseThrow()))
        .isEqualTo(scope);
    String tokenHash = AccountJoinDigest.tokenHash(scope.connectScopeId());
    var evidence =
        f.inTransaction(() -> f.scopes.findCanonicalEvidenceByTokenHash(tokenHash).orElseThrow());
    assertThat(evidence.scopeTokenHash()).isEqualTo(tokenHash);
    assertThat(evidence.privateAccountId()).isEqualTo(f.account.getId());
    assertThat(evidence.scopeDigestVersion()).isEqualTo(2);
    assertThat(evidence.scopeDigest()).isEqualTo(AccountJoinDigest.scopeV2(scope));
    assertThat(evidence.tenantProvenance().kind())
        .isEqualTo(AccountMembershipPairAuthorityRepository.TenantProvenanceKind.FRESH_GAME_DESIGN);
    assertThat(evidence.tenantProvenance().legacyTenantId()).isNull();
    assertThat(evidence.tenantProvenance().sourceOperationId())
        .isEqualTo(tenant.source().operationId());
    assertThat(evidence.tenantProvenance().digest()).isEqualTo(tenant.source().evidenceDigest());
    var row =
        Objects.requireNonNull(
            f.dsl.fetchOne(
                "SELECT * FROM account_connect_scope_records WHERE scope_token_hash = ?",
                tokenHash));
    assertThat(row.get("account_uuid", UUID.class)).isEqualTo(f.account.getAccountUuid());
    assertThat(row.get("tenant_uuid", UUID.class)).isEqualTo(tenant.tenantId());
    assertThat(row.get("game_instance_uuid", UUID.class))
        .isEqualTo(tenant.target().canonicalGameInstanceId());
    assertThat(row.get("tenant_id")).isNull();
    assertThat(row.get("game_instance_id")).isNull();
    assertThat(row.get("playable_state_namespace_id")).isNull();
    assertThat(row.get("snapshot_digest", String.class))
        .isEqualTo(AccountJoinDigest.scopeV2(scope));
    Map<String, String> after = f.snapshot();
    before.forEach(
        (table, original) -> {
          if (!table.equals("account_connect_scope_records")) {
            assertThat(after.get(table)).as("unchanged %s", table).isEqualTo(original);
          }
        });
    assertThat(
            Objects.requireNonNull(
                    f.dsl.fetchOne("SELECT COUNT(*) FROM account_connect_scope_records"))
                .get(0, Long.class))
        .isEqualTo(1L);
    f.assertNoMembershipPairJoinOrPaidReads();
    SessionContext.setContext(
        f.account.getAccountUuid().toString(),
        List.of("player"),
        Map.of(tenant.tenantId().toString(), List.of("player")));

    assertThat(f.scopeReader.read(f.account.getAccountUuid(), scope.connectScopeId()))
        .isEqualTo(scope);
    assertThat(f.scopeReader.read(f.account.getAccountUuid(), scope.connectScopeId()))
        .isEqualTo(scope);

    assertThat(SessionContext.getAccountId()).isEqualTo(f.account.getAccountUuid().toString());
    assertThat(SessionContext.getScopedRoles(tenant.tenantId().toString()))
        .containsExactly("player");
    assertThat(f.snapshot()).isEqualTo(after);
    assertThat(
            f.inTransaction(
                () -> f.scopes.findCanonicalEvidenceByTokenHash(tokenHash).orElseThrow()))
        .isEqualTo(evidence);
    f.assertNoMembershipPairJoinOrPaidReads();
    verify(f.targets, times(2)).listVisiblePublicProductionAdmissionTargets(any());
  }

  @Test
  void actualRetainedScopeRefusesAnotherPersistedExpectedAccountWithoutChangingCallerContext() {
    Fixture f = fixture();
    CanonicalJoinScopeV2 scope = f.issueScope("scope-wrong-account");
    Account other = new Account();
    other.setUsername("other-scope-reader");
    other.setEmail("other-scope-reader@example.test");
    other.setPasswordHash("controlled-unused-password-verifier");
    other.setRole("player");
    Account expected = f.inTransaction(() -> f.accounts.save(other));
    SessionContext.setContext(expected.getAccountUuid().toString(), List.of("player"), Map.of());
    Map<String, String> before = f.snapshot();

    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> f.scopeReader.read(expected.getAccountUuid(), scope.connectScopeId()));

    assertThat(SessionContext.getAccountId()).isEqualTo(expected.getAccountUuid().toString());
    assertThat(f.scopeReader.read(f.account.getAccountUuid(), scope.connectScopeId()))
        .isEqualTo(scope);
    // The UUID argument is comparison data supplied by the outer owner; ambient roles/identity
    // are neither accepted as source authority nor cleared to make this read succeed.
    assertThat(SessionContext.getAccountId()).isEqualTo(expected.getAccountUuid().toString());
    assertThat(f.snapshot()).isEqualTo(before);
    f.assertNoMembershipPairJoinOrPaidReads();
    verify(f.targets, times(2)).listVisiblePublicProductionAdmissionTargets(any());
  }

  @Test
  void actualIssuedScopeExpiryRefusesWithoutRestampingItsRetainedSelector() {
    Fixture f = fixture();
    CanonicalJoinScopeV2 scope = f.issueScope("scope-reader-expiry");
    Map<String, String> before = f.snapshot();
    f.clock.offset.set(
        Duration.between(f.clock.instant(), Instant.parse(scope.connectScopeExpiresAt()))
            .plusSeconds(1));

    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> f.scopeReader.read(f.account.getAccountUuid(), scope.connectScopeId()));

    assertThat(f.snapshot()).isEqualTo(before);
    assertThat(f.inTransaction(() -> f.scopes.findCanonical(scope.connectScopeId()).orElseThrow()))
        .isEqualTo(scope);
    f.assertNoMembershipPairJoinOrPaidReads();
    verify(f.targets, times(2)).listVisiblePublicProductionAdmissionTargets(any());
  }

  @Test
  void actualImmutableScopeGuardRejectsEvidenceMutationAndReaderReturnsOriginalPreimage() {
    Fixture f = fixture();
    CanonicalJoinScopeV2 scope = f.issueScope("scope-immutable");
    Map<String, String> before = f.snapshot();
    String tokenHash = AccountJoinDigest.tokenHash(scope.connectScopeId());

    org.jooq.exception.DataAccessException failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.jooq.exception.DataAccessException.class,
            () ->
                f.dsl.execute(
                    "UPDATE account_connect_scope_records SET world_slug = ? WHERE scope_token_hash = ?",
                    "different-world",
                    tokenHash));

    SQLException sql = Objects.requireNonNull(sqlFailure(failure));
    assertThat(sql.getSQLState()).isEqualTo("23514");
    assertThat(sql.getMessage()).contains("Canonical Account connect scope evidence is immutable");
    assertThat(f.scopeReader.read(f.account.getAccountUuid(), scope.connectScopeId()))
        .isEqualTo(scope);
    assertThat(f.snapshot()).isEqualTo(before);
    f.assertNoMembershipPairJoinOrPaidReads();
  }

  @Test
  void actualAccountLockTimeoutMakesScopeReadUnavailableAndFreshReadRecoversUnchanged()
      throws SQLException {
    Fixture f = fixture();
    CanonicalJoinScopeV2 scope = f.issueScope("scope-lock-timeout");
    Map<String, String> before = f.snapshot();
    // Bound only this fixture DataSource's future physical connections. The reader still uses
    // its real DataSourceTransactionManager, writable SERIALIZABLE transaction and repositories.
    Properties connectionProperties = new Properties();
    connectionProperties.setProperty("connectTimeout", "10");
    connectionProperties.setProperty("socketTimeout", "60");
    connectionProperties.setProperty("options", "-c lock_timeout=150ms");
    f.dataSource.setConnectionProperties(connectionProperties);
    try (Connection holder = f.dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try {
        try (var statement =
            holder.prepareStatement("SELECT id FROM accounts WHERE account_uuid = ? FOR UPDATE")) {
          statement.setObject(1, f.account.getAccountUuid());
          try (var locked = statement.executeQuery()) {
            assertThat(locked.next()).isTrue();
          }
        }

        AuthenticationException failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                AuthenticationException.class,
                () -> f.scopeReader.read(f.account.getAccountUuid(), scope.connectScopeId()));

        assertThat(failure.getCode()).isEqualTo("AUTH_UNAVAILABLE");
        SQLException sql = Objects.requireNonNull(sqlFailure(failure));
        assertThat(sql.getSQLState()).isEqualTo("55P03");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      } finally {
        holder.rollback();
      }
    }

    assertThat(f.scopeReader.read(f.account.getAccountUuid(), scope.connectScopeId()))
        .isEqualTo(scope);
    assertThat(f.snapshot()).isEqualTo(before);
    f.assertNoMembershipPairJoinOrPaidReads();
    verify(f.targets, times(2)).listVisiblePublicProductionAdmissionTargets(any());
  }

  @Test
  void actualDemoMutationDuringScopeTargetRereadRefusesBeforeAnySelectorWrite() {
    Fixture f = fixture();
    DemoTenant tenant = f.demo("scope-head-race", true);
    f.targetReads(List.of(tenant.target()), List.of(tenant.target()));
    AtomicReference<Map<String, String>> afterMutation = new AtomicReference<>();
    f.onSecondTargetRead =
        () -> {
          DemoTenantEntitlementSnapshot updated = f.update(tenant, false);
          assertThat(updated.tenantBillingSequence())
              .isEqualTo(tenant.snapshot().tenantBillingSequence() + 1L);
          afterMutation.set(f.snapshot());
        };

    assertCode(
        "ENTITLEMENT_UNAVAILABLE",
        () ->
            f.realmScopes.listRealmScopes(
                f.token, tenant.target().tenantSlug(), tenant.target().worldSlug()));

    assertThat(afterMutation.get()).isNotNull();
    assertThat(f.snapshot()).isEqualTo(afterMutation.get());
    f.assertNoAdmissionRowsOrPaidReads();
    f.assertNoMembershipPairJoinOrPaidReads();
    verify(f.targets, times(2)).listVisiblePublicProductionAdmissionTargets(any());
  }

  @Test
  void actualCanonicalScopeInsertFailureRollsBackWithoutScopeOrOtherAuthorityMutation() {
    Fixture f = fixture();
    DemoTenant tenant = f.demo("scope-insert-failure", true);
    f.targetReads(List.of(tenant.target()), List.of(tenant.target()));
    // The function and trigger belong only to this test's disposable schema. AFTER INSERT
    // ensures PostgreSQL staged the actual row before aborting the same physical transaction.
    f.dsl.execute(
        "CREATE FUNCTION reject_test_canonical_scope_insert() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.scope_digest_version = 2 THEN "
            + "RAISE EXCEPTION 'controlled canonical scope insert refusal' USING ERRCODE = '23514'; "
            + "END IF; RETURN NEW; END; $$");
    f.dsl.execute(
        "CREATE TRIGGER reject_test_canonical_scope_insert AFTER INSERT ON account_connect_scope_records "
            + "FOR EACH ROW EXECUTE FUNCTION reject_test_canonical_scope_insert()");
    Map<String, String> before = f.snapshot();

    AuthenticationException failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            AuthenticationException.class,
            () ->
                f.realmScopes.listRealmScopes(
                    f.token, tenant.target().tenantSlug(), tenant.target().worldSlug()));

    assertThat(failure.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    SQLException sql =
        Objects.requireNonNull(sqlFailure(failure), "Expected actual PostgreSQL refusal");
    assertThat(sql.getSQLState()).isEqualTo("23514");
    assertThat(sql.getMessage()).contains("controlled canonical scope insert refusal");
    assertThat(f.snapshot()).isEqualTo(before);
    f.assertNoAdmissionRowsOrPaidReads();
    f.assertNoMembershipPairJoinOrPaidReads();
    // A fresh transaction remains usable after the failed owner transaction was rolled back.
    assertThat(f.inTransaction(() -> f.demos.readCurrent(tenant.tenantId())))
        .isEqualTo(tenant.snapshot());
  }

  private static SQLException sqlFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql) return sql;
    }
    return null;
  }

  private Fixture fixture() {
    String schema = "demo_worlds_" + UUID.randomUUID().toString().replace("-", "");
    ownedSchemas.add(schema);
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA \"" + schema + "\"");
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target("1027.2")
        .load()
        .migrate();
    return new Fixture(dataSource);
  }

  private static void assertCode(String code, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(AuthenticationException.class)
        .extracting(failure -> ((AuthenticationException) failure).getCode())
        .isEqualTo(code);
  }

  private static final class Fixture {
    private final DriverManagerDataSource dataSource;
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final AccountRepository accounts;
    private final AccountConnectScopeRepository scopes;
    private final FreshTenantIdentityAssociationRepository tenants;
    private final AccountAuthorityGenerationRepository generations;
    private final AccountTenantAuthorityEventRepository sources;
    private final AccountDemoTenantEntitlementRepository demos;
    private final AccountHostingSubscriptionLifecycleService hosting =
        mock(AccountHostingSubscriptionLifecycleService.class);
    private final CanonicalGameplayAdmissionDecisionReadClient targets =
        mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AtomicBoolean sessionActive = new AtomicBoolean(true);
    private final DatabaseClock clock;
    private final Account account;
    private final JwtUtil jwt;
    private final String token;
    private final AccountPublicBootstrapWorldsOwner worlds;
    private final AccountCanonicalPublicRealmScopeOwner realmScopes;
    private final AccountCanonicalConnectScopeReader scopeReader;
    private final AtomicInteger targetReadCount = new AtomicInteger();
    private long sourceRowId;
    private Runnable onSecondTargetRead = () -> {};

    private Fixture(DriverManagerDataSource dataSource) {
      this.dataSource = dataSource;
      dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      var transactions = new DataSourceTransactionManager(dataSource);
      transaction = new TransactionTemplate(transactions);
      transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      accounts = new AccountRepository(dsl);
      tenants = new FreshTenantIdentityAssociationRepository(dsl, NAMESPACE);
      generations = new AccountAuthorityGenerationRepository(dsl);
      var authorityOutbox = new AccountAuthorityOutboxRepository(dsl);
      var billing = new AccountTenantEntitlementOutboxRepository(dsl);
      sources =
          new AccountTenantAuthorityEventRepository(
              dsl, authorityOutbox, billing, tenants, generations);
      demos =
          new AccountDemoTenantEntitlementRepository(dsl, tenants, generations, billing, sources);
      clock = new DatabaseClock(new JdbcTemplate(dataSource));
      Account fresh = new Account();
      fresh.setUsername("demo-worlds-reader");
      fresh.setEmail("demo-worlds-reader@example.test");
      fresh.setPasswordHash("controlled-unused-password-verifier");
      fresh.setRole("player");
      account = inTransaction(() -> accounts.save(fresh));
      jwt = new JwtUtil("controlled-local-bootstrap-key-01234567890123456789", 300000L);
      token =
          jwt.generateToken(
              account.getAccountUuid().toString(),
              Map.of(
                  "aud",
                  "player-bootstrap",
                  "accountId",
                  account.getAccountUuid().toString(),
                  "jti",
                  UUID.randomUUID().toString()));
      when(sessions.isAccountSessionActive(account.getId(), token))
          .thenAnswer(invocation -> sessionActive.get());
      var caller = new AccountPlayerBootstrapCallerVerifier(jwt, accounts, sessions);
      var entitlement =
          new AccountPublicBootstrapEntitlementOwner(sources, hosting, demos, transactions, clock);
      worlds = new AccountPublicBootstrapWorldsOwner(caller, targets, entitlement, clock);
      var pairs = new AccountMembershipPairAuthorityRepository(dsl);
      var memberships = new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);
      var retainedIdentities =
          new AccountTenantIdentityResolver(
              new ApprovedLegacyTenantAssociationRepository(
                  dsl, new LegacyTenantSourceEvidence(dsl), NAMESPACE),
              NAMESPACE);
      scopes = new AccountConnectScopeRepository(dsl, retainedIdentities, tenants);
      scopeReader = new AccountCanonicalConnectScopeReader(jwt, scopes, transactions, clock);
      var tokenProperties = new AccountTokenProperties();
      tokenProperties.setConnectScopeExpirationMs(120000L);
      realmScopes =
          new AccountCanonicalPublicRealmScopeOwner(
              caller,
              targets,
              entitlement,
              accounts,
              memberships,
              tenants,
              sources,
              scopes,
              jwt,
              tokenProperties,
              clock,
              transactions,
              NAMESPACE);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne("SELECT to_regclass('subscription_lifecycle_receipts')"))
                  .get(0))
          .isNull();
    }

    private FreshTenantCreationEvidence enrollTenant() {
      UUID tenantId = UUID.randomUUID();
      UUID requestId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();
      String key = "demo" + tenantId.toString().replace("-", "");
      String digest =
          GameTenantCreationDigest.requestDigest(NAMESPACE, requestId, key, "Demo", null);
      long rowId = ++sourceRowId;
      var source =
          new FreshTenantCreationEvidence(
              1,
              NAMESPACE,
              requestId,
              operationId,
              digest,
              tenantId,
              rowId,
              key,
              "NEW_GAME_ROW",
              GameTenantCreationDigest.evidenceDigest(
                  NAMESPACE, requestId, operationId, digest, tenantId, rowId, key, "NEW_GAME_ROW"));
      return inTransaction(
          () -> {
            tenants.importVerified(source);
            generations.initializeTenantIfAbsent(tenantId);
            return source;
          });
    }

    private CanonicalJoinScopeV2 issueScope(String worldSlug) {
      DemoTenant tenant = demo(worldSlug, true);
      targetReads(List.of(tenant.target()), List.of(tenant.target()));
      return realmScopes
          .listRealmScopes(token, tenant.target().tenantSlug(), tenant.target().worldSlug())
          .getFirst()
          .scope();
    }

    private DemoTenant demo(String worldSlug, boolean available) {
      FreshTenantCreationEvidence source = enrollTenant();
      DemoTenantEntitlementSnapshot snapshot =
          inTransaction(() -> demos.provision(request(source, null, available), source));
      return new DemoTenant(
          source, snapshot, target(source.canonicalTenantId(), worldSlug, 1, clock.instant()));
    }

    private DemoTenantEntitlementSnapshot update(DemoTenant tenant, boolean available) {
      return inTransaction(
          () ->
              demos.provision(
                  request(tenant.source(), tenant.snapshot(), available), tenant.source()));
    }

    private <T> T inTransaction(Supplier<T> operation) {
      return Objects.requireNonNull(transaction.execute(status -> operation.get()));
    }

    private void targetReads(List<Target> first, List<Target> second) {
      when(targets.listVisiblePublicProductionAdmissionTargets(any()))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                int read = targetReadCount.incrementAndGet();
                if (read == 2) onSecondTargetRead.run();
                List<Target> values = read == 1 ? first : second;
                DiscoveryRequest request =
                    new DiscoveryRequest(invocation.getArgument(0, UUID.class), NAMESPACE);
                return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromDiscoveryResponse(
                    request,
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(
                        request, values));
              });
    }

    private Map<String, String> snapshot() {
      Map<String, String> result = new LinkedHashMap<>();
      for (String table : OBSERVED_TABLES) {
        result.put(
            table,
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text, '[]') FROM "
                            + table
                            + " t"))
                .get(0, String.class));
      }
      return result;
    }

    private void assertNoAdmissionRowsOrPaidReads() {
      for (String table :
          List.of(
              "account_tenant_membership",
              "account_connect_scope_records",
              "account_join_operations")) {
        assertThat(
                Objects.requireNonNull(dsl.fetchOne("SELECT COUNT(*) FROM " + table))
                    .get(0, Long.class))
            .isZero();
      }
      verifyNoInteractions(hosting);
    }

    private void assertNoMembershipPairJoinOrPaidReads() {
      for (String table :
          List.of(
              "account_tenant_membership",
              "account_membership_pair_authority",
              "account_join_operations")) {
        assertThat(
                Objects.requireNonNull(dsl.fetchOne("SELECT COUNT(*) FROM " + table))
                    .get(0, Long.class))
            .isZero();
      }
      verifyNoInteractions(hosting);
    }
  }

  private static DemoTenantEntitlementRequest request(
      FreshTenantCreationEvidence source, DemoTenantEntitlementSnapshot prior, boolean available) {
    return new DemoTenantEntitlementRequest(
        UUID.randomUUID(),
        source.canonicalTenantId(),
        source.creationRequestId(),
        source.requestDigest(),
        prior == null ? null : prior.entitlementVersion(),
        prior == null ? null : prior.tenantAuthorityGeneration(),
        prior == null ? null : prior.tenantAuthoritySourceVersion(),
        available,
        false,
        false,
        false,
        new DemoTenantEntitlementRequest.Quotas(3, 2, 4096));
  }

  private record DemoTenant(
      FreshTenantCreationEvidence source, DemoTenantEntitlementSnapshot snapshot, Target target) {
    private UUID tenantId() {
      return source.canonicalTenantId();
    }
  }

  /**
   * DB time is real; the explicit offset and commit hook exercise expiry and a genuine head race.
   */
  private static final class DatabaseClock extends Clock {
    private final JdbcTemplate jdbc;
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
    private final AtomicReference<Runnable> afterNextObservation = new AtomicReference<>();

    private DatabaseClock(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      Objects.requireNonNull(zone);
      Clock source = this;
      return new Clock() {
        @Override
        public ZoneId getZone() {
          return zone;
        }

        @Override
        public Clock withZone(ZoneId nextZone) {
          return source.withZone(nextZone);
        }

        @Override
        public Instant instant() {
          return source.instant();
        }
      };
    }

    @Override
    public Instant instant() {
      Instant time =
          Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
              .toInstant();
      Runnable mutation = afterNextObservation.getAndSet(null);
      if (mutation != null) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                mutation.run();
              }
            });
      }
      return time.plus(offset.get());
    }
  }

  /**
   * Complete structurally validated GS input only; no World hold or owner provenance is claimed.
   */
  private static Target target(
      UUID tenantId, String worldSlug, long pointerVersion, Instant observedAt) {
    UUID realm = UUID.randomUUID();
    UUID playableNamespace = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID holdId = UUID.randomUUID();
    UUID holdFence = UUID.randomUUID();
    String initialRequest = UUID.randomUUID().toString();
    String initialDigest = "a".repeat(64);
    var request =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            tenantId,
            worldSlug,
            realm,
            playableNamespace,
            "SHARED",
            instance,
            version,
            1,
            initialRequest,
            initialDigest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1,
            null);
    var hold = new WorldCanonicalInitialAdmissionHold.HoldIdentity(request, holdId, holdFence);
    return new Target(
        NAMESPACE,
        "tenant-" + tenantId,
        tenantId,
        worldSlug,
        "World " + worldSlug,
        realm,
        "main",
        "Main",
        1,
        2,
        playableNamespace,
        "SHARED",
        instance,
        version,
        3,
        1,
        pointerVersion,
        "b".repeat(64),
        1,
        initialRequest,
        initialDigest,
        OriginKind.NO_PRIOR_POINTER,
        null,
        holdId,
        holdFence,
        hold.holdBindingDigest(),
        1,
        "sha256:" + "c".repeat(64),
        OwnerProofOutcome.COMMITTED,
        false,
        observedAt,
        "PRESEEDED_ONLY");
  }
}
