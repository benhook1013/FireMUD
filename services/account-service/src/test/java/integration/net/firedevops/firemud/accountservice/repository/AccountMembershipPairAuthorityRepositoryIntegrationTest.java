package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.ProvenPositiveCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountMembershipPairAuthorityRepositoryIntegrationTest {
  private static final String SCHEMA_PREFIX = "pair_authority_proof_";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void immutableValueTypesRejectContradictoryAuthorityEvidence() {
    VerifiedTenantProvenance provenance = provenance(null, TenantProvenanceKind.FRESH_GAME_DESIGN);
    UUID accountUuid = UUID.randomUUID();
    UUID tenantUuid = UUID.randomUUID();

    PairAuthority baseline =
        new PairAuthority(
            accountUuid, tenantUuid, provenance, false, 1L, 1L, 0L, null, null, false);
    assertThat(baseline)
        .isEqualTo(
            new PairAuthority(
                accountUuid, tenantUuid, provenance, false, 1L, 1L, 0L, null, null, false));

    assertThatThrownBy(
            () ->
                new PairAuthority(
                    accountUuid, tenantUuid, provenance, true, 1L, 1L, 0L, null, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Sequence-zero");
    assertThatThrownBy(
            () ->
                new PairAuthority(
                    accountUuid, tenantUuid, provenance, true, 1L, 1L, 1L, null, digest(1), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("event evidence");
    assertThatThrownBy(
            () ->
                new VerifiedTenantProvenance(
                    null,
                    TenantProvenanceKind.FRESH_GAME_DESIGN,
                    UUID.randomUUID(),
                    "SHA256:" + "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical lowercase SHA-256");
    assertThatThrownBy(
            () ->
                new VerifiedTenantProvenance(
                    701L, TenantProvenanceKind.FRESH_GAME_DESIGN, UUID.randomUUID(), digest(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry a legacy");
    assertThatThrownBy(
            () ->
                new VerifiedTenantProvenance(
                    null, TenantProvenanceKind.APPROVED_RETAINED, UUID.randomUUID(), digest(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive legacy tenant ID");
    assertThatThrownBy(() -> new ProvenPositiveCheckpoint(1L, 1L, 0L, "event", digest(1), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incomplete");
  }

  @Test
  void freshPairsUseUuidIdentityWithoutLegacyAliasesAndRequireTheOwnerTransaction() {
    TestContext context = newTestContext();
    UUID accountUuid = insertAccount(context.setupDsl());
    UUID firstTenantUuid = UUID.randomUUID();
    UUID secondTenantUuid = UUID.randomUUID();
    VerifiedTenantProvenance firstFresh = provenance(null, TenantProvenanceKind.FRESH_GAME_DESIGN);
    VerifiedTenantProvenance secondFresh = provenance(null, TenantProvenanceKind.FRESH_GAME_DESIGN);

    assertThatThrownBy(() -> context.repository().readForUpdate(accountUuid, firstTenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");

    PairAuthority first =
        inTransaction(
            context.transaction(),
            () -> context.repository().enrollAbsence(accountUuid, firstTenantUuid, firstFresh));
    PairAuthority second =
        inTransaction(
            context.transaction(),
            () -> context.repository().enrollAbsence(accountUuid, secondTenantUuid, secondFresh));
    assertThat(first.provenance().legacyTenantId()).isNull();
    assertThat(second.provenance().legacyTenantId()).isNull();
    assertThat(
            Objects.requireNonNull(
                    context
                        .setupDsl()
                        .fetchOne(
                            "SELECT count(*) FROM account_membership_pair_authority "
                                + "WHERE account_uuid = ? "
                                + "AND tenant_provenance_kind = 'FRESH_GAME_DESIGN' "
                                + "AND legacy_tenant_id IS NULL",
                            accountUuid),
                    "Fresh pair count query must return a row")
                .get(0, Long.class))
        .isEqualTo(2L);

    VerifiedTenantProvenance retained = provenance(705L, TenantProvenanceKind.APPROVED_RETAINED);
    UUID retainedTenantUuid = UUID.randomUUID();
    inTransaction(
        context.transaction(),
        () -> context.repository().enrollAbsence(accountUuid, retainedTenantUuid, retained));
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .enrollAbsence(
                                accountUuid,
                                UUID.randomUUID(),
                                provenance(705L, TenantProvenanceKind.APPROVED_RETAINED))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with an existing pair authority");

    UUID rolledBackTenantUuid = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status -> {
                          context
                              .repository()
                              .enrollAbsence(accountUuid, rolledBackTenantUuid, firstFresh);
                          throw new IllegalStateException("rollback pair enrollment");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rollback pair enrollment");
    assertThat(
            Objects.requireNonNull(
                    context
                        .setupDsl()
                        .fetchOne(
                            "SELECT count(*) FROM account_membership_pair_authority "
                                + "WHERE account_uuid = ? AND tenant_uuid = ?",
                            accountUuid,
                            rolledBackTenantUuid),
                    "Rolled-back pair count query must return a row")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void databaseRejectsRetainedNullAndFreshNumericLegacyKeys() {
    TestContext context = newTestContext();
    UUID accountUuid = insertAccount(context.setupDsl());

    assertThatThrownBy(
            () ->
                insertPair(
                    context.setupDsl(),
                    accountUuid,
                    UUID.randomUUID(),
                    null,
                    TenantProvenanceKind.APPROVED_RETAINED))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertPair(
                    context.setupDsl(),
                    accountUuid,
                    UUID.randomUUID(),
                    706L,
                    TenantProvenanceKind.FRESH_GAME_DESIGN))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void v39PreservesRetainedPairHistoryAndEnforcesUuidOnlyFreshRows() {
    TestContext context = newTestContext(newSchema(), "38");
    UUID accountUuid = insertAccount(context.setupDsl());
    UUID retainedTenantUuid = UUID.randomUUID();
    VerifiedTenantProvenance retained = provenance(707L, TenantProvenanceKind.APPROVED_RETAINED);
    insertPair(
        context.setupDsl(),
        accountUuid,
        retainedTenantUuid,
        retained.legacyTenantId(),
        retained.kind(),
        retained.sourceOperationId(),
        retained.digest());
    org.jooq.Record retainedBefore =
        context
            .setupDsl()
            .fetchOne(
                "SELECT * FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid,
                retainedTenantUuid);

    UUID advancedTenantUuid = UUID.randomUUID();
    VerifiedTenantProvenance advancedRetained =
        provenance(709L, TenantProvenanceKind.APPROVED_RETAINED);
    ProvenPositiveCheckpoint advancedCheckpoint =
        new ProvenPositiveCheckpoint(8L, 3L, 11L, "retained-event-11", digest(9), true);
    PairAuthority advancedAuthority =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .enrollProvenPositive(
                        accountUuid, advancedTenantUuid, advancedRetained, advancedCheckpoint));
    org.jooq.Record advancedBefore =
        context
            .setupDsl()
            .fetchOne(
                "SELECT * FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid,
                advancedTenantUuid);

    flyway(context.dataSource(), context.schema(), null).migrate();
    org.jooq.Record retainedAfter =
        context
            .setupDsl()
            .fetchOne(
                "SELECT * FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid,
                retainedTenantUuid);
    org.jooq.Record advancedAfter =
        context
            .setupDsl()
            .fetchOne(
                "SELECT * FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid,
                advancedTenantUuid);
    assertThat(retainedAfter).isEqualTo(retainedBefore);
    assertThat(advancedAfter).isEqualTo(advancedBefore);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readForUpdate(accountUuid, retainedTenantUuid)))
        .contains(
            new PairAuthority(
                accountUuid, retainedTenantUuid, retained, false, 1L, 1L, 0L, null, null, false));
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readForUpdate(accountUuid, advancedTenantUuid)))
        .contains(advancedAuthority);

    PairAuthority firstFresh =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .enrollAbsence(
                        accountUuid,
                        UUID.randomUUID(),
                        provenance(null, TenantProvenanceKind.FRESH_GAME_DESIGN)));
    PairAuthority secondFresh =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .enrollAbsence(
                        accountUuid,
                        UUID.randomUUID(),
                        provenance(null, TenantProvenanceKind.FRESH_GAME_DESIGN)));
    assertThat(firstFresh.tenantUuid()).isNotEqualTo(secondFresh.tenantUuid());
    assertThat(firstFresh.provenance().legacyTenantId()).isNull();
    assertThat(secondFresh.provenance().legacyTenantId()).isNull();
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .enrollAbsence(
                                accountUuid,
                                UUID.randomUUID(),
                                provenance(707L, TenantProvenanceKind.APPROVED_RETAINED))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with an existing pair authority");
  }

  @Test
  void v39RefusesToRewriteAnExistingFreshNumericPair() {
    TestContext context = newTestContext(newSchema(), "38");
    UUID accountUuid = insertAccount(context.setupDsl());
    UUID tenantUuid = UUID.randomUUID();
    insertPair(
        context.setupDsl(), accountUuid, tenantUuid, 708L, TenantProvenanceKind.FRESH_GAME_DESIGN);

    assertThatThrownBy(() -> flyway(context.dataSource(), context.schema(), null).migrate())
        .isInstanceOf(FlywayException.class);
    assertThat(
            Objects.requireNonNull(
                    context
                        .setupDsl()
                        .fetchOne(
                            "SELECT legacy_tenant_id FROM account_membership_pair_authority "
                                + "WHERE account_uuid = ? AND tenant_uuid = ?",
                            accountUuid,
                            tenantUuid),
                    "Rejected migration must leave the existing pair row")
                .get(0, Long.class))
        .isEqualTo(708L);
  }

  @Test
  void enrollmentAndTransitionsPreserveExactAuthorityAndProvenance() {
    TestContext context = newTestContext();
    UUID accountUuid = insertAccount(context.setupDsl());
    UUID tenantUuid = UUID.randomUUID();
    VerifiedTenantProvenance fresh = provenance(null, TenantProvenanceKind.FRESH_GAME_DESIGN);

    PairAuthority baseline =
        inTransaction(
            context.transaction(),
            () -> context.repository().enrollAbsence(accountUuid, tenantUuid, fresh));
    assertThat(baseline.membershipExists()).isFalse();
    assertThat(baseline.membershipVersion()).isEqualTo(1L);
    assertThat(baseline.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(baseline.lastEventSequence()).isZero();
    assertThat(baseline.lastEventId()).isNull();
    assertThat(baseline.lastEventDigest()).isNull();

    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().enrollAbsence(accountUuid, tenantUuid, fresh)))
        .isEqualTo(baseline);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readForUpdate(accountUuid, tenantUuid)))
        .contains(baseline);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .commitTransition(
                                new PairAuthority(
                                    accountUuid,
                                    tenantUuid,
                                    fresh,
                                    false,
                                    2L,
                                    1L,
                                    0L,
                                    null,
                                    null,
                                    false),
                                new PairTransition(
                                    true, 1L, "join-event-stale-version", digest(2), false))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Stale or contradictory");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .commitTransition(
                                baseline,
                                new PairTransition(
                                    true, 2L, "join-event-wrong-sequence", digest(2), false))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("advance by exactly one");

    PairAuthority firstJoin =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .commitTransition(
                        baseline, new PairTransition(true, 1L, "join-event-1", digest(2), false)));
    assertThat(firstJoin.membershipVersion()).isEqualTo(2L);
    assertThat(firstJoin.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(firstJoin.lastEventSequence()).isEqualTo(1L);
    assertThat(firstJoin.lastTransitionInvalidated()).isFalse();

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().enrollAbsence(accountUuid, tenantUuid, fresh)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact absence state");
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readForUpdate(accountUuid, tenantUuid)))
        .contains(firstJoin);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .commitTransition(
                                baseline,
                                new PairTransition(true, 1L, "join-event-1", digest(2), false))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Stale or contradictory");

    PairAuthority secondTransition =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .commitTransition(
                        firstJoin,
                        new PairTransition(false, 2L, "leave-event-2", digest(3), true)));
    assertThat(secondTransition.membershipVersion()).isEqualTo(3L);
    assertThat(secondTransition.membershipAuthorityGeneration()).isEqualTo(2L);
    assertThat(secondTransition.lastEventSequence()).isEqualTo(2L);
    assertThat(secondTransition.lastTransitionInvalidated()).isTrue();

    VerifiedTenantProvenance wrongProvenance =
        new VerifiedTenantProvenance(
            null, TenantProvenanceKind.FRESH_GAME_DESIGN, UUID.randomUUID(), digest(4));
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .enrollAbsence(accountUuid, tenantUuid, wrongProvenance)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with verified association provenance");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .commitTransition(
                                new PairAuthority(
                                    firstJoin.accountUuid(),
                                    firstJoin.tenantUuid(),
                                    firstJoin.provenance(),
                                    firstJoin.membershipExists(),
                                    firstJoin.membershipVersion(),
                                    firstJoin.membershipAuthorityGeneration() + 1L,
                                    firstJoin.lastEventSequence(),
                                    firstJoin.lastEventId(),
                                    firstJoin.lastEventDigest(),
                                    firstJoin.lastTransitionInvalidated()),
                                new PairTransition(false, 2L, "leave-event-2", digest(3), true))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Stale or contradictory");

    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "UPDATE account_membership_pair_authority SET tenant_provenance_digest = ? "
                            + "WHERE account_uuid = ? AND tenant_uuid = ?",
                        digest(5),
                        accountUuid,
                        tenantUuid))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_membership_pair_authority "
                            + "WHERE account_uuid = ? AND tenant_uuid = ?",
                        accountUuid,
                        tenantUuid))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readForUpdate(accountUuid, tenantUuid)))
        .contains(secondTransition);
  }

  @Test
  void positiveRetainedEnrollmentUsesSuppliedCountersAndRejectsSeqZeroOrChangedEvidence() {
    TestContext context = newTestContext();
    UUID accountUuid = insertAccount(context.setupDsl());
    UUID tenantUuid = UUID.randomUUID();
    VerifiedTenantProvenance retained = provenance(703L, TenantProvenanceKind.APPROVED_RETAINED);
    ProvenPositiveCheckpoint checkpoint =
        new ProvenPositiveCheckpoint(8L, 3L, 11L, "retained-event-11", digest(6), true);

    PairAuthority enrolled =
        inTransaction(
            context.transaction(),
            () ->
                context
                    .repository()
                    .enrollProvenPositive(accountUuid, tenantUuid, retained, checkpoint));
    assertThat(enrolled.membershipExists()).isTrue();
    assertThat(enrolled.membershipVersion()).isEqualTo(8L);
    assertThat(enrolled.membershipAuthorityGeneration()).isEqualTo(3L);
    assertThat(enrolled.lastEventSequence()).isEqualTo(11L);
    assertThat(enrolled.lastTransitionInvalidated()).isTrue();
    assertThat(
            inTransaction(
                context.transaction(),
                () ->
                    context
                        .repository()
                        .enrollProvenPositive(accountUuid, tenantUuid, retained, checkpoint)))
        .isEqualTo(enrolled);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> context.repository().enrollAbsence(accountUuid, tenantUuid, retained)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact absence state");
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readForUpdate(accountUuid, tenantUuid)))
        .contains(enrolled);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .enrollProvenPositive(
                                accountUuid,
                                tenantUuid,
                                retained,
                                new ProvenPositiveCheckpoint(
                                    8L, 3L, 11L, "different-event", digest(6), true))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from verified positive current evidence");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () ->
                        context
                            .repository()
                            .enrollProvenPositive(
                                accountUuid,
                                tenantUuid,
                                provenance(null, TenantProvenanceKind.FRESH_GAME_DESIGN),
                                checkpoint)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("approved retained tenant provenance");
  }

  @Test
  void databaseRejectsPositiveSequenceWithoutEventDigest() {
    TestContext context = newTestContext();
    UUID accountUuid = insertAccount(context.setupDsl());
    UUID tenantUuid = UUID.randomUUID();
    VerifiedTenantProvenance retained = provenance(704L, TenantProvenanceKind.APPROVED_RETAINED);

    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "INSERT INTO account_membership_pair_authority "
                            + "(account_uuid, tenant_uuid, legacy_tenant_id, "
                            + "tenant_provenance_kind, tenant_source_operation_id, "
                            + "tenant_provenance_digest, membership_exists, membership_version, "
                            + "membership_authority_generation, last_event_sequence, "
                            + "last_event_id, last_event_digest, last_transition_invalidated) "
                            + "VALUES (?, ?, ?, ?, ?, ?, TRUE, 2, 1, 1, 'event-1', NULL, FALSE)",
                        accountUuid,
                        tenantUuid,
                        retained.legacyTenantId(),
                        retained.kind().name(),
                        retained.sourceOperationId(),
                        retained.digest()))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            inTransaction(
                context.transaction(),
                () -> context.repository().readForUpdate(accountUuid, tenantUuid)))
        .isEmpty();
  }

  private TestContext newTestContext() {
    return newTestContext(newSchema(), null);
  }

  private TestContext newTestContext(String schema, String targetVersion) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    flyway(dataSource, schema, targetVersion).migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    assertThat(setupDsl.fetchOne("SELECT current_schema()").get(0, String.class)).isEqualTo(schema);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        schema,
        dataSource,
        setupDsl,
        new AccountMembershipPairAuthorityRepository(transactionDsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private String newSchema() {
    return SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
  }

  private Flyway flyway(DataSource dataSource, String schema, String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (targetVersion != null) {
      configuration.target(targetVersion);
    }
    return configuration.load();
  }

  private UUID insertAccount(DSLContext dsl) {
    String unique = UUID.randomUUID().toString().replace("-", "");
    return dsl.resultQuery(
            "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                + "RETURNING account_uuid",
            "pair_" + unique.substring(0, 12),
            unique + "@example.test",
            "test-hash")
        .fetchOne(0, UUID.class);
  }

  private VerifiedTenantProvenance provenance(Long legacyTenantId, TenantProvenanceKind kind) {
    return new VerifiedTenantProvenance(legacyTenantId, kind, UUID.randomUUID(), digest(1));
  }

  private void insertPair(
      DSLContext dsl,
      UUID accountUuid,
      UUID tenantUuid,
      Long legacyTenantId,
      TenantProvenanceKind kind) {
    insertPair(dsl, accountUuid, tenantUuid, legacyTenantId, kind, UUID.randomUUID(), digest(1));
  }

  private void insertPair(
      DSLContext dsl,
      UUID accountUuid,
      UUID tenantUuid,
      Long legacyTenantId,
      TenantProvenanceKind kind,
      UUID sourceOperationId,
      String evidenceDigest) {
    dsl.execute(
        "INSERT INTO account_membership_pair_authority "
            + "(account_uuid, tenant_uuid, legacy_tenant_id, tenant_provenance_kind, "
            + "tenant_source_operation_id, tenant_provenance_digest, membership_exists, "
            + "membership_version, membership_authority_generation, last_event_sequence, "
            + "last_event_id, last_event_digest, last_transition_invalidated) "
            + "VALUES (?, ?, ?, ?, ?, ?, FALSE, 1, 1, 0, NULL, NULL, FALSE)",
        accountUuid,
        tenantUuid,
        legacyTenantId,
        kind.name(),
        sourceOperationId,
        evidenceDigest);
  }

  private String digest(int digit) {
    return "sha256:" + Integer.toString(digit).repeat(64);
  }

  private <T> T inTransaction(
      TransactionTemplate transaction, java.util.function.Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record TestContext(
      String schema,
      DataSource dataSource,
      DSLContext setupDsl,
      AccountMembershipPairAuthorityRepository repository,
      TransactionTemplate transaction) {}
}
