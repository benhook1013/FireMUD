package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountCanonicalRuntimeMembershipSnapshotService;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Physical proof for the unregistered Account-owned current membership snapshot reader. */
class AccountCanonicalRuntimeMembershipSnapshotPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "canonical_membership_snapshot_";
  private static final String TEST_NAMESPACE = "canonical-membership-snapshot-proof";
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
  void readsCommittedFirstJoinFromActualOwnersWithoutChangingRowsOrXmins() {
    Fixture fixture = newFixture();
    fixture.commitFirstJoin();
    List<String> before = fixture.schemaImage();

    var snapshot =
        fixture.snapshots.readCurrent(fixture.account.getAccountUuid(), fixture.tenantUuid);

    assertThat(RuntimeMembershipAuthorityEvidenceValidator.validate(snapshot)).isPresent();
    assertThat(snapshot.accountId()).isEqualTo(fixture.account.getAccountUuid().toString());
    assertThat(snapshot.tenantId()).isEqualTo(fixture.tenantUuid.toString());
    assertThat(snapshot.membershipExists()).isTrue();
    assertThat(snapshot.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(snapshot.gameplayAdmissionAllowed()).isTrue();
    assertThat(snapshot.roles()).containsExactly("player");
    assertThat(snapshot.membershipVersion()).containsEntry(fixture.tenantUuid.toString(), "2");
    assertThat(snapshot.checkpoints()).hasSize(4);
    assertThat(snapshot.sourceEvidence()).hasSize(2);
    assertThat(snapshot.checkpoints())
        .anySatisfy(
            checkpoint -> {
              assertThat(checkpoint.outboxStreamKey()).isEqualTo(fixture.membershipStream());
              assertThat(checkpoint.outboxSequence()).isEqualTo("1");
            });
    assertThat(snapshot.authorityTuple().tenantBillingCutoff())
        .hasValueSatisfying(
            cutoffs -> assertThat(cutoffs).containsKey(fixture.tenantUuid.toString()));
    assertThat(fixture.schemaImage()).isEqualTo(before);
  }

  @Test
  void emitsOnlyTheProvenNonadmittingMissingBaselineForAnEnrolledFreshPair() {
    Fixture fixture = newFixture();
    fixture.enrollAbsenceBaseline();
    List<String> before = fixture.schemaImage();

    var snapshot =
        fixture.snapshots.readCurrent(fixture.account.getAccountUuid(), fixture.tenantUuid);

    assertThat(RuntimeMembershipAuthorityEvidenceValidator.validate(snapshot)).isEmpty();
    assertThat(snapshot.membershipExists()).isFalse();
    assertThat(snapshot.membershipLifecycleState()).isEqualTo("MISSING");
    assertThat(snapshot.gameplayAdmissionAllowed()).isFalse();
    assertThat(snapshot.roles()).isEmpty();
    assertThat(snapshot.membershipVersion()).containsEntry(fixture.tenantUuid.toString(), "1");
    assertThat(snapshot.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(snapshot.checkpoints())
        .anySatisfy(
            checkpoint -> {
              assertThat(checkpoint.outboxStreamKey()).isEqualTo(fixture.membershipStream());
              assertThat(checkpoint.outboxSequence()).isEqualTo("0");
            });
    assertThat(snapshot.sourceEvidence())
        .noneMatch(source -> source.outboxStreamKey().equals(fixture.membershipStream()));
    assertThat(fixture.schemaImage()).isEqualTo(before);
  }

  @Test
  void rejectsASeparateAccountUuidBeforeReturningTenantMembershipEvidence() {
    Fixture fixture = newFixture();
    fixture.commitFirstJoin();
    List<String> before = fixture.schemaImage();

    assertThatThrownBy(() -> fixture.snapshots.readCurrent(UUID.randomUUID(), fixture.tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact Account row");

    assertThat(fixture.schemaImage()).isEqualTo(before);
  }

  @Test
  void missingCommittedRoleSnapshotFailsClosed() {
    Fixture fixture = newFixture();
    fixture.commitFirstJoin();
    long membershipId = fixture.membershipId();
    fixture.inTransaction(
        () -> {
          fixture.dsl.execute(
              "DELETE FROM account_tenant_membership_role_snapshot_roles WHERE membership_id = ?",
              membershipId);
          fixture.dsl.execute(
              "DELETE FROM account_tenant_membership_role_snapshots WHERE membership_id = ?",
              membershipId);
          return null;
        });

    assertThatThrownBy(
            () ->
                fixture.snapshots.readCurrent(fixture.account.getAccountUuid(), fixture.tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Committed current membership role snapshot is absent");
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
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    return new TestContext(dsl, new TransactionTemplate(transactionManager), transactionManager);
  }

  private static final class Fixture {
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final PlatformTransactionManager transactionManager;
    private final AccountRepository accounts;
    private final FreshTenantIdentityAssociationRepository freshTenants;
    private final AccountMembershipPairAuthorityRepository pairs;
    private final AccountTenantMembershipRepository memberships;
    private final AccountTenantMembershipRoleSnapshotRepository roles;
    private final AccountAuthorityGenerationRepository generations;
    private final AccountAuthorityOutboxRepository outbox;
    private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
    private final AccountTenantAuthorityEventRepository tenantAuthorityEvents;
    private final AccountDemoTenantEntitlementRepository demoEntitlements;
    private final AccountJoinOperationRepository operations;
    private final AccountCanonicalFirstJoinTerminalCoordinator terminalCoordinator;
    private final AccountCanonicalRuntimeMembershipSnapshotService snapshots;
    private final Account account;
    private final UUID tenantUuid = UUID.randomUUID();
    private final FreshTenantCreationEvidence tenantEvidence;
    private final VerifiedTenantProvenance provenance;
    private final CanonicalJoinScopeV2 scope;
    private final String requestId = UUID.randomUUID().toString();
    private final String callerBinding = "snapshot-owner-caller-" + UUID.randomUUID();

    private Fixture(TestContext context) {
      this.dsl = context.dsl();
      this.transaction = context.transaction();
      this.transactionManager = context.transactionManager();
      this.accounts = new AccountRepository(dsl);
      this.freshTenants = new FreshTenantIdentityAssociationRepository(dsl, TEST_NAMESPACE);
      this.pairs = new AccountMembershipPairAuthorityRepository(dsl);
      this.memberships = new AccountTenantMembershipRepository(dsl, accounts, freshTenants, pairs);
      this.roles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
      this.generations = new AccountAuthorityGenerationRepository(dsl);
      this.outbox = new AccountAuthorityOutboxRepository(dsl);
      this.sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
      var billingOutbox = new AccountTenantEntitlementOutboxRepository(dsl);
      this.tenantAuthorityEvents =
          new AccountTenantAuthorityEventRepository(
              dsl, outbox, billingOutbox, freshTenants, generations);
      this.demoEntitlements =
          new AccountDemoTenantEntitlementRepository(
              dsl, freshTenants, generations, billingOutbox, tenantAuthorityEvents);
      var legacyAssociationRepository =
          org.mockito.Mockito.mock(ApprovedLegacyTenantAssociationRepository.class);
      var retainedIdentityResolver =
          new AccountTenantIdentityResolver(legacyAssociationRepository, TEST_NAMESPACE);
      var connectScopes =
          new AccountConnectScopeRepository(dsl, retainedIdentityResolver, freshTenants);
      this.operations = new AccountJoinOperationRepository(dsl, connectScopes);
      var producer =
          new AccountMembershipAuthorityEventProducer(
              operations,
              accounts,
              pairs,
              memberships,
              roles,
              generations,
              outbox,
              sourceEvidence,
              tenantAuthorityEvents,
              demoEntitlements);
      this.terminalCoordinator =
          new AccountCanonicalFirstJoinTerminalCoordinator(
              accounts,
              operations,
              memberships,
              roles,
              outbox,
              pairs,
              new AccountAuditOutboxRepository(dsl),
              producer);
      var snapshotRepository =
          new AccountCanonicalRuntimeMembershipSnapshotRepository(
              accounts,
              generations,
              sourceEvidence,
              tenantAuthorityEvents,
              freshTenants,
              memberships,
              pairs,
              roles,
              outbox);
      this.snapshots =
          new AccountCanonicalRuntimeMembershipSnapshotService(
              snapshotRepository, transactionManager);
      this.account = createAccount();
      this.tenantEvidence = freshTenantEvidence(tenantUuid);
      this.provenance =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenantEvidence.operationId(),
              tenantEvidence.evidenceDigest());
      this.scope = canonicalScope(account.getAccountUuid(), tenantUuid);
      seedOwnerRowsAndOperation(connectScopes);
    }

    private Account createAccount() {
      Account input = new Account();
      String suffix = UUID.randomUUID().toString().replace("-", "");
      input.setUsername("snapshot-account-" + suffix);
      input.setEmail("snapshot-account-" + suffix + "@example.test");
      input.setPasswordHash("integration-fixture-hash");
      input.setRole("player");
      return inTransaction(() -> accounts.save(input));
    }

    private void seedOwnerRowsAndOperation(AccountConnectScopeRepository connectScopes) {
      inTransaction(
          () -> {
            freshTenants.importVerified(tenantEvidence);
            sourceEvidence.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
            generations.initializeTenantIfAbsent(tenantUuid);
            generations.initialize(
                AccountAuthorityGenerationRepository.AuthorityScope.membership(
                    account.getAccountUuid(), tenantUuid));
            connectScopes.insertCanonical(account.getId(), scope, provenance);
            operations.insertCanonicalIntent(requestId, scope, callerBinding);
            return null;
          });
      DemoTenantEntitlementSnapshot entitlement =
          inTransaction(
              () ->
                  demoEntitlements.provision(
                      entitlementRequest(UUID.randomUUID()), tenantEvidence));
      inTransaction(
          () -> {
            operations.bindCanonicalPolicyEvidence(
                requestId,
                scope,
                callerBinding,
                entitlement.allowPublicJoin(),
                entitlement.entitlementVersion());
            return null;
          });
    }

    private void enrollAbsenceBaseline() {
      inTransaction(
          () -> {
            accounts
                .findByAccountUuidForUpdate(account.getAccountUuid())
                .orElseThrow(() -> new IllegalStateException("Account owner row is absent"));
            if (!tenantEvidence.equals(freshTenants.read(tenantUuid).orElseThrow())
                || memberships
                    .findFreshMembership(account.getAccountUuid(), tenantUuid)
                    .isPresent()) {
              throw new IllegalStateException("Absence fixture is not the exact fresh empty pair");
            }
            pairs.enrollAbsence(account.getAccountUuid(), tenantUuid, provenance);
            return null;
          });
    }

    private AccountJoinOperationRepository.CanonicalJoinTerminalProof commitFirstJoin() {
      return inTransaction(
          () -> terminalCoordinator.commitCanonicalFirstJoin(scope, requestId, callerBinding));
    }

    private long membershipId() {
      return inTransaction(
          () ->
              memberships
                  .findFreshMembership(account.getAccountUuid(), tenantUuid)
                  .orElseThrow()
                  .getId());
    }

    private DemoTenantEntitlementRequest entitlementRequest(UUID entitlementRequestId) {
      return new DemoTenantEntitlementRequest(
          entitlementRequestId,
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
    }

    private String membershipStream() {
      return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + account.getAccountUuid()
          + "/"
          + tenantUuid;
    }

    private List<String> schemaImage() {
      List<String> image = new ArrayList<>();
      for (Record tableRecord :
          dsl.fetch(
              "SELECT tablename FROM pg_tables WHERE schemaname = current_schema() ORDER BY tablename")) {
        String table = tableRecord.get("tablename", String.class);
        String safeTable = table.replace("\"", "\"\"");
        Record state =
            dsl.fetchOne(
                "SELECT count(*)::bigint, "
                    + "COALESCE(string_agg(xmin::text, ',' ORDER BY xmin::text), '') "
                    + "FROM \""
                    + safeTable
                    + "\"");
        image.add(
            table
                + ":"
                + Objects.requireNonNull(state).get(0, Long.class)
                + ":"
                + state.get(1, String.class));
      }
      return List.copyOf(image);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> callback) {
      return transaction.execute(status -> callback.get());
    }
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(UUID tenantUuid) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey =
        "fresh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, requestId, sourceTenantKey, "Membership snapshot tenant", null);
    long sourceGameRowId = positiveRandomLong();
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
        "snapshot-scope-" + UUID.randomUUID(),
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
        "2026-10-14T00:00:00Z",
        "2026-10-14T00:02:00Z");
  }

  private static long positiveRandomLong() {
    long value = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return value == 0L ? 1L : value;
  }

  private record TestContext(
      DSLContext dsl,
      TransactionTemplate transaction,
      PlatformTransactionManager transactionManager) {}
}
