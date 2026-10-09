package integration.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountActorStagingEligibilityRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountActorStagingEligibilityService;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Purpose;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
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
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL regression proof for current, non-admitting Account actor-staging evidence. */
class AccountActorStagingEligibilityPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "actor_staging_eligibility_";
  private static final String TEST_NAMESPACE = "actor-staging-eligibility-proof";
  private static final String ACCOUNT_ISSUER = "firemud-account-service";

  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();
  private final Set<String> runOwnedSchemas = ConcurrentHashMap.newKeySet();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @AfterEach
  void dropRunOwnedSchemas() {
    JdbcTemplate rootJdbc = new JdbcTemplate(postgres.dataSource());
    for (String schema : runOwnedSchemas) {
      if (!schema.startsWith(SCHEMA_PREFIX) || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalStateException("Refusing to clean an unowned PostgreSQL schema");
      }
      rootJdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    runOwnedSchemas.clear();
  }

  @Test
  void requiresCurrentMembershipEventAndReturnsBoundStagingOnlyEvidence() {
    Fixture fixture = newFixture();
    UUID stagingRequestId = UUID.randomUUID();

    assertThatThrownBy(() -> fixture.resolveStaging(stagingRequestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Current positive Account membership is absent");
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(fixture.retainedMembershipEventCount()).isZero();

    fixture.createAndPublishMembershipEvent();
    AccountActorStagingEligibilityEvidence evidence = fixture.resolveStaging(stagingRequestId);
    PairAuthority pair = fixture.readPositivePair();
    AccountAuthorityOutboxRepository.Event event = fixture.readCurrentMembershipEvent(pair);
    MembershipEvent decoded =
        MembershipAuthorityEventV1Codec.verify(new String(event.payload(), StandardCharsets.UTF_8));
    DemoTenantEntitlementSnapshot entitlement = fixture.readCurrentEntitlement();

    assertThat(entitlement.status()).isEqualTo("ACTIVE");
    assertThat(entitlement.gameplayAvailable()).isTrue();
    assertThat(entitlement.allowPublicJoin()).isTrue();
    assertThat(entitlement.tenantBillingSequence()).isPositive();
    assertThat(entitlement.tenantAuthorityOutboxSequence()).isPositive();

    assertThat(evidence.schemaVersion())
        .isEqualTo(AccountActorStagingEligibilityEvidence.SCHEMA_VERSION);
    assertThat(evidence.targetNamespace()).isEqualTo(TEST_NAMESPACE);
    assertThat(evidence.requestId()).isEqualTo(stagingRequestId);
    assertThat(evidence.canonicalAccountId()).isEqualTo(fixture.account.getAccountUuid());
    assertThat(evidence.canonicalTenantId()).isEqualTo(fixture.tenantUuid);
    assertThat(evidence.purpose()).isEqualTo(Purpose.PUBLIC_PRODUCTION_STAGING_ONLY);
    assertThat(evidence.currentness())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Currentness.CURRENT_AT_REVALIDATION);
    assertThat(evidence.decision())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Decision.STAGING_ELIGIBLE);
    assertThat(evidence.observedAt()).isNotNull();
    assertThat(evidence.accountUuidProvenance())
        .isEqualTo(fixture.account.getAccountUuidProvenance().name());
    assertThat(evidence.accountLifecycleState()).isEqualTo("ACTIVE");
    assertThat(evidence.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(evidence.gameplayAdmissionAllowed()).isTrue();
    assertThat(evidence.membershipAuthorityProvenance()).isEqualTo("EXPLICIT_JOIN");
    assertThat(evidence.membershipVersion()).isEqualTo(pair.membershipVersion());
    assertThat(evidence.membershipAuthorityGeneration())
        .isEqualTo(pair.membershipAuthorityGeneration());
    assertThat(evidence.membershipEventSequence()).isEqualTo(pair.eventSequence());
    assertThat(evidence.membershipEventId().toString()).isEqualTo(pair.eventId());
    assertThat(evidence.membershipEventDigest()).isEqualTo(pair.eventDigest());
    assertThat(evidence.lastTransitionInvalidated()).isFalse();
    assertThat(evidence.tenantProvenanceKind()).isEqualTo("FRESH_GAME_DESIGN");
    assertThat(evidence.tenantSourceOperationId()).isEqualTo(fixture.tenantEvidence.operationId());
    assertThat(evidence.tenantProvenanceDigest())
        .isEqualTo(fixture.tenantEvidence.evidenceDigest());
    assertThat(event.outboxStreamKey()).isEqualTo(fixture.membershipStream());
    assertThat(event.requestId()).isEqualTo(fixture.joinRequestId);
    assertThat(decoded.requestId()).isEqualTo(fixture.joinRequestId);
    assertThat(decoded.accountId()).isEqualTo(fixture.account.getAccountUuid().toString());
    assertThat(decoded.tenantId()).isEqualTo(fixture.tenantUuid.toString());
    assertThat(decoded.membershipVersion()).containsEntry(fixture.tenantUuid.toString(), "2");
    assertThat(decoded.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(decoded.eventId()).isEqualTo(pair.eventId());
    assertThat(decoded.eventDigest()).isEqualTo(pair.eventDigest());

    AccountActorStagingEligibilityEvidence secondRequestEvidence =
        fixture.resolveStaging(UUID.randomUUID());
    assertThat(secondRequestEvidence.requestId()).isNotEqualTo(evidence.requestId());
    assertThat(fixture.readCurrentEntitlement()).isEqualTo(entitlement);
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isEqualTo(1L);
    assertThat(
            fixture.count(
                "account_authority_outbox_events", "outbox_stream_key", fixture.membershipStream()))
        .isEqualTo(1L);
    assertThat(fixture.retainedMembershipEventCount()).isEqualTo(1L);
    assertThat(fixture.currentMembershipCheckpointSequence()).isEqualTo(pair.eventSequence());
    assertThat(fixture.maximumRetainedMembershipEventSequence()).isEqualTo(pair.eventSequence());
    assertThat(fixture.joinOperationStatus()).isEqualTo("PENDING");
  }

  private Fixture newFixture() {
    return new Fixture(newTestContext());
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    runOwnedSchemas.add(schema);
    DriverManagerDataSource dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    TransactionTemplate readOnlySnapshot = new TransactionTemplate(transactionManager);
    readOnlySnapshot.setReadOnly(true);
    readOnlySnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return new TestContext(dsl, transaction, readOnlySnapshot);
  }

  private static final class Fixture {
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlySnapshot;
    private final AccountAuthorityGenerationRepository generations;
    private final AccountAuthorityOutboxRepository outbox;
    private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
    private final FreshTenantIdentityAssociationRepository freshTenants;
    private final AccountDemoTenantEntitlementRepository entitlements;
    private final AccountRepository accounts;
    private final AccountMembershipPairAuthorityRepository pairs;
    private final AccountTenantMembershipRepository memberships;
    private final AccountTenantMembershipRoleSnapshotRepository roles;
    private final AccountConnectScopeRepository connectScopes;
    private final AccountJoinOperationRepository joinOperations;
    private final AccountMembershipAuthorityEventProducer membershipEvents;
    private final Account account;
    private final UUID tenantUuid = UUID.randomUUID();
    private final FreshTenantCreationEvidence tenantEvidence;
    private final VerifiedTenantProvenance tenantProvenance;
    private final CanonicalJoinScopeV2 scope;
    private final String joinRequestId = UUID.randomUUID().toString();
    private final String callerBinding = "actor-staging-caller-" + UUID.randomUUID();

    private Fixture(TestContext context) {
      this.dsl = context.dsl();
      this.transaction = context.transaction();
      this.readOnlySnapshot = context.readOnlySnapshot();
      this.generations = new AccountAuthorityGenerationRepository(dsl);
      this.outbox = new AccountAuthorityOutboxRepository(dsl);
      this.sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
      this.freshTenants = new FreshTenantIdentityAssociationRepository(dsl, TEST_NAMESPACE);
      var billingOutbox = new AccountTenantEntitlementOutboxRepository(dsl);
      var tenantEvents =
          new AccountTenantAuthorityEventRepository(
              dsl, outbox, billingOutbox, freshTenants, generations);
      this.entitlements =
          new AccountDemoTenantEntitlementRepository(
              dsl, freshTenants, generations, billingOutbox, tenantEvents);
      this.accounts = new AccountRepository(dsl, sourceEvidence);
      this.pairs = new AccountMembershipPairAuthorityRepository(dsl);
      this.memberships = new AccountTenantMembershipRepository(dsl, accounts, freshTenants, pairs);
      this.roles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
      this.tenantEvidence = freshTenantEvidence(tenantUuid);
      this.tenantProvenance =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenantEvidence.operationId(),
              tenantEvidence.evidenceDigest());
      seedTenantAndActiveEntitlement();
      this.account = createAccount();
      this.scope = canonicalScope(account.getAccountUuid(), tenantUuid);

      var retainedIdentityResolver =
          new AccountTenantIdentityResolver(
              new ApprovedLegacyTenantAssociationRepository(
                  dsl, new LegacyTenantSourceEvidence(dsl), TEST_NAMESPACE),
              TEST_NAMESPACE);
      this.connectScopes =
          new AccountConnectScopeRepository(dsl, retainedIdentityResolver, freshTenants);
      this.joinOperations = new AccountJoinOperationRepository(dsl, connectScopes);
      this.membershipEvents =
          new AccountMembershipAuthorityEventProducer(
              joinOperations,
              accounts,
              pairs,
              memberships,
              roles,
              generations,
              outbox,
              sourceEvidence,
              tenantEvents,
              entitlements);
      seedMembershipOperation();
    }

    private void seedTenantAndActiveEntitlement() {
      transaction.executeWithoutResult(
          status -> {
            freshTenants.importVerified(tenantEvidence);
            sourceEvidence.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
            generations.initializeTenantIfAbsent(tenantUuid);
          });
      DemoTenantEntitlementRequest request =
          new DemoTenantEntitlementRequest(
              UUID.randomUUID(),
              tenantUuid,
              tenantEvidence.creationRequestId(),
              tenantEvidence.requestDigest(),
              null,
              null,
              null,
              true,
              true,
              true,
              true,
              new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L));
      transaction.executeWithoutResult(status -> entitlements.provision(request, tenantEvidence));
    }

    private Account createAccount() {
      Account input = new Account();
      String suffix = UUID.randomUUID().toString().replace("-", "");
      input.setUsername("actor-staging-" + suffix);
      input.setEmail("actor-staging-" + suffix + "@example.test");
      input.setPasswordHash("integration-fixture-hash");
      input.setRole("player");
      return transaction.execute(status -> accounts.save(input));
    }

    private void seedMembershipOperation() {
      transaction.executeWithoutResult(
          status -> {
            generations.initialize(
                AccountAuthorityGenerationRepository.AuthorityScope.membership(
                    account.getAccountUuid(), tenantUuid));
            connectScopes.insertCanonical(account.getId(), scope, tenantProvenance);
            joinOperations.insertCanonicalIntent(joinRequestId, scope, callerBinding);
            DemoTenantEntitlementSnapshot currentEntitlement = entitlements.readCurrent(tenantUuid);
            joinOperations.bindCanonicalPolicyEvidence(
                joinRequestId,
                scope,
                callerBinding,
                currentEntitlement.allowPublicJoin(),
                currentEntitlement.entitlementVersion());
          });
    }

    private void createAndPublishMembershipEvent() {
      transaction.executeWithoutResult(
          status -> {
            var membership =
                memberships.createFreshMembershipForJoin(account.getAccountUuid(), tenantUuid);
            roles.replaceCanonical(
                membership,
                account.getAccountUuid(),
                tenantUuid,
                tenantProvenance,
                2L,
                java.util.List.of("player"));
            membershipEvents.publishCanonicalFirstJoinMembershipChange(
                scope, joinRequestId, callerBinding);
          });
    }

    private AccountActorStagingEligibilityEvidence resolveStaging(UUID requestId) {
      AccountActorStagingEligibilityRepository sourceRepository =
          new AccountActorStagingEligibilityRepository(
              dsl, accounts, freshTenants, memberships, pairs);
      AccountActorStagingEligibilityService service =
          new AccountActorStagingEligibilityService(sourceRepository, TEST_NAMESPACE);
      return readOnlySnapshot.execute(
          status ->
              service.resolve(
                  AccountActorStagingEligibilityEvidence.SCHEMA_VERSION,
                  TEST_NAMESPACE,
                  requestId,
                  account.getAccountUuid(),
                  tenantUuid,
                  Purpose.PUBLIC_PRODUCTION_STAGING_ONLY));
    }

    private PairAuthority readPositivePair() {
      return transaction.execute(
          status -> pairs.readPositive(account.getAccountUuid(), tenantUuid).orElseThrow());
    }

    private AccountAuthorityOutboxRepository.Event readCurrentMembershipEvent(PairAuthority pair) {
      String stream = membershipStream();
      return transaction.execute(
          status -> outbox.findEvent(stream, pair.eventSequence()).orElseThrow());
    }

    private DemoTenantEntitlementSnapshot readCurrentEntitlement() {
      return transaction.execute(status -> entitlements.readCurrent(tenantUuid));
    }

    private long retainedMembershipEventCount() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT count(*) FROM account_authority_outbox_events "
                      + "WHERE outbox_stream_key = ?",
                  membershipStream()))
          .get(0, Long.class);
    }

    private long currentMembershipCheckpointSequence() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT last_sequence FROM account_authority_outbox_streams "
                      + "WHERE outbox_stream_key = ?",
                  membershipStream()))
          .get(0, Long.class);
    }

    private long maximumRetainedMembershipEventSequence() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT max(outbox_sequence) FROM account_authority_outbox_events "
                      + "WHERE outbox_stream_key = ?",
                  membershipStream()))
          .get(0, Long.class);
    }

    private String joinOperationStatus() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT status FROM account_join_operations WHERE request_id = ?", joinRequestId))
          .get(0, String.class);
    }

    private long count(String table, String keyColumn, Object value) {
      return Objects.requireNonNull(
              dsl.fetchOne("SELECT count(*) FROM " + table + " WHERE " + keyColumn + " = ?", value))
          .get(0, Long.class);
    }

    private String membershipStream() {
      return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + account.getAccountUuid()
          + "/"
          + tenantUuid;
    }
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(UUID tenantUuid) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey =
        "actor-stage-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, requestId, sourceTenantKey, "Actor staging eligibility test", null);
    long sourceGameRowId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    if (sourceGameRowId == 0L) {
      sourceGameRowId = 1L;
    }
    String provenanceKind = "NEW_GAME_ROW";
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        requestDigest,
        tenantUuid,
        sourceGameRowId,
        sourceTenantKey,
        provenanceKind,
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceTenantKey,
            provenanceKind));
  }

  private static CanonicalJoinScopeV2 canonicalScope(UUID accountUuid, UUID tenantUuid) {
    return new CanonicalJoinScopeV2(
        "actor-staging-scope-" + UUID.randomUUID().toString().replace("-", ""),
        accountUuid,
        tenantUuid,
        UUID.randomUUID(),
        "tenant",
        "world",
        "production",
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        7L,
        3L,
        java.time.Instant.now().toString(),
        java.time.Instant.now().plusSeconds(120L).toString());
  }

  private record TestContext(
      DSLContext dsl, TransactionTemplate transaction, TransactionTemplate readOnlySnapshot) {}
}
