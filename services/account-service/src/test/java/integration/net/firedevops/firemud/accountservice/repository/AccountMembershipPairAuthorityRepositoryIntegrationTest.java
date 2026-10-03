package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.ProvenPositiveCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import org.flywaydb.core.Flyway;
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
  private static final String SCHEMA = "account_membership_pair_authority_proof";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void immutableValueTypesRejectContradictoryAuthorityEvidence() {
    VerifiedTenantProvenance provenance = provenance(701L, TenantProvenanceKind.FRESH_GAME_DESIGN);
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
                    701L,
                    TenantProvenanceKind.FRESH_GAME_DESIGN,
                    UUID.randomUUID(),
                    "SHA256:" + "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical lowercase SHA-256");
    assertThatThrownBy(() -> new ProvenPositiveCheckpoint(1L, 1L, 0L, "event", digest(1), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incomplete");
  }

  @Test
  void enrollmentAndTransitionsPreserveExactPositiveAuthorityAndProvenance() {
    TestContext context = newTestContext();
    UUID accountUuid = insertAccount(context.setupDsl());
    UUID tenantUuid = UUID.randomUUID();
    VerifiedTenantProvenance fresh = provenance(702L, TenantProvenanceKind.FRESH_GAME_DESIGN);

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
            702L, TenantProvenanceKind.FRESH_GAME_DESIGN, UUID.randomUUID(), digest(4));
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
                            .enrollAbsence(
                                accountUuid,
                                UUID.randomUUID(),
                                provenance(702L, TenantProvenanceKind.FRESH_GAME_DESIGN))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with an existing pair authority");

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
                                provenance(703L, TenantProvenanceKind.FRESH_GAME_DESIGN),
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
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + SCHEMA);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .placeholders(Map.of("serviceSchema", SCHEMA))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        setupDsl,
        new AccountMembershipPairAuthorityRepository(transactionDsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
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

  private VerifiedTenantProvenance provenance(long legacyTenantId, TenantProvenanceKind kind) {
    return new VerifiedTenantProvenance(legacyTenantId, kind, UUID.randomUUID(), digest(1));
  }

  private String digest(int digit) {
    return "sha256:" + Integer.toString(digit).repeat(64);
  }

  private <T> T inTransaction(
      TransactionTemplate transaction, java.util.function.Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record TestContext(
      DSLContext setupDsl,
      AccountMembershipPairAuthorityRepository repository,
      TransactionTemplate transaction) {}
}
