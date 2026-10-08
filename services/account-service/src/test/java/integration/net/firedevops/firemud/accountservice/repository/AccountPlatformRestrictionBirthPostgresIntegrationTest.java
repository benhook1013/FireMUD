package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository.BirthSourceUnavailableException;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated fresh-birth PostgreSQL fixture exercising the real AccountRepository.save boundary. */
class AccountPlatformRestrictionBirthPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startFixture() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopFixture() {
    POSTGRES.stop();
  }

  @Test
  void freshSaveAtomicallyRetainsIndependentExactBirthResultsWithoutAuthEvent() {
    Context context = context(null);
    Account account = account();
    AccountPlatformRestrictionBirthSource birth =
        context
            .transaction()
            .execute(
                status -> {
                  new AccountRepository(context.dsl()).save(account);
                  return new AccountPlatformRestrictionBirthRepository(context.dsl())
                      .readCurrentBirthSource(account.getAccountUuid());
                });
    AccountPlatformRestrictionBirthSource replay =
        context
            .transaction()
            .execute(
                status ->
                    new AccountPlatformRestrictionBirthRepository(context.dsl())
                        .readCurrentBirthSource(account.getAccountUuid()));
    assertThat(replay).isEqualTo(birth);
    assertThat(Objects.requireNonNull(birth).accountSourceNumericId()).isEqualTo(account.getId());
    assertThat(birth.birthAuthority()).isEqualTo(birth.currentAuthority());
    assertThat(birth.accountSecurityLock().operationId())
        .isNotEqualTo(birth.platformAccessBan().operationId());
    assertThat(birth.accountSecurityLock().resultId())
        .isNotEqualTo(birth.platformAccessBan().resultId());
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_births")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_projections")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_birth_outbox")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("account_authority_outbox_events"))).isZero();
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT last_outbox_sequence FROM account_authority_source_records WHERE account_uuid = ?",
                            account.getAccountUuid()))
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void independentAccountAdvancePreservesBirthEvidenceAndReadsCurrentAuthoritySeparately() {
    Context context = context(null);
    Account account = account();
    AccountPlatformRestrictionBirthSource birth =
        context
            .transaction()
            .execute(
                status -> {
                  new AccountRepository(context.dsl()).save(account);
                  return new AccountPlatformRestrictionBirthRepository(context.dsl())
                      .readCurrentBirthSource(account.getAccountUuid());
                });
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              account.setPasswordHash("changed-birth-test-hash");
              new AccountRepository(context.dsl()).save(account);
            });
    AccountPlatformRestrictionBirthSource later =
        context
            .transaction()
            .execute(
                status ->
                    new AccountPlatformRestrictionBirthRepository(context.dsl())
                        .readCurrentBirthSource(account.getAccountUuid()));
    assertThat(Objects.requireNonNull(later).birthAuthority())
        .isEqualTo(Objects.requireNonNull(birth).birthAuthority());
    assertThat(later.accountSecurityLock()).isEqualTo(birth.accountSecurityLock());
    assertThat(later.platformAccessBan()).isEqualTo(birth.platformAccessBan());
    assertThat(later.currentAuthority().generation()).isEqualTo(2);
    assertThat(later.currentAuthority().issuanceFence().value()).isEqualTo(2);
  }

  @Test
  void nonActiveFreshSaveRollsBackWithoutInventingNonrestrictedBirthEvidence() {
    Context context = context(null);
    for (AccountLifecycleState lifecycle :
        new AccountLifecycleState[] {
          AccountLifecycleState.SECURITY_LOCKED,
          AccountLifecycleState.DEACTIVATED_PENDING_DELETE,
          AccountLifecycleState.DELETED
        }) {
      Account account = account();
      account.setLifecycleState(lifecycle);
      assertThatThrownBy(
              () ->
                  context
                      .transaction()
                      .executeWithoutResult(
                          status -> new AccountRepository(context.dsl()).save(account)))
          .as("fresh %s Account must not acquire NONRESTRICTED birth evidence", lifecycle)
          .isInstanceOf(IllegalStateException.class);
      for (String table :
          new String[] {
            "accounts",
            "account_authority_generations",
            "account_authority_issuance_fences",
            "account_authority_source_records",
            "account_authority_outbox_events",
            "account_platform_restriction_births",
            "account_platform_restriction_projections",
            "account_platform_restriction_birth_outbox"
          }) {
        assertThat(context.dsl().fetchCount(DSL.table(table)))
            .as("%s after failed fresh %s save", table, lifecycle)
            .isZero();
      }
    }
  }

  @Test
  void rollbackLeavesNoAccountCategoryResultProjectionOrOutbox() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              new AccountRepository(context.dsl()).save(account);
              new AccountPlatformRestrictionBirthRepository(context.dsl())
                  .readCurrentBirthSource(account.getAccountUuid());
              status.setRollbackOnly();
            });
    for (String table :
        new String[] {
          "accounts",
          "account_platform_restriction_births",
          "account_platform_restriction_projections",
          "account_platform_restriction_birth_outbox"
        }) {
      assertThat(context.dsl().fetchCount(DSL.table(table))).isZero();
    }
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            new AccountPlatformRestrictionBirthRepository(context.dsl())
                                .readCurrentBirthSource(account.getAccountUuid())))
        .isInstanceOf(BirthSourceUnavailableException.class);
  }

  @Test
  void retainedPreSourceAccountIsNeverEnrolledByReadback() {
    Context context = context("39");
    String suffix = UUID.randomUUID().toString();
    Long retainedId =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "INSERT INTO accounts (username, email, password_hash, role) VALUES (?, ?, ?, 'player') RETURNING id",
                        suffix,
                        suffix + "@example.test",
                        "retained-test-hash"))
            .get(0, Long.class);
    migrate(context, null);
    UUID retainedUuid =
        new AccountRepository(context.dsl()).findById(retainedId).orElseThrow().getAccountUuid();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            new AccountPlatformRestrictionBirthRepository(context.dsl())
                                .readCurrentBirthSource(retainedUuid)))
        .isInstanceOf(BirthSourceUnavailableException.class);
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_births"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_birth_outbox")))
        .isZero();
  }

  @Test
  void immutableIdentityAndDigestSubstitutionsAreRejected() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));
    for (String mutation :
        new String[] {
          "UPDATE account_platform_restriction_births SET payload_digest = repeat('0', 64)",
          "UPDATE account_platform_restriction_birth_outbox SET result_id = '10203040-5060-4070-8090-a0b0c0d0e0f0'",
          "DELETE FROM account_platform_restriction_projections",
          "TRUNCATE account_platform_restriction_birth_outbox"
        }) {
      assertThatThrownBy(
              () ->
                  context
                      .transaction()
                      .executeWithoutResult(status -> context.dsl().execute(mutation)))
          .isInstanceOf(DataAccessException.class);
    }
    AccountPlatformRestrictionBirthSource readback =
        context
            .transaction()
            .execute(
                status ->
                    new AccountPlatformRestrictionBirthRepository(context.dsl())
                        .readCurrentBirthSource(account.getAccountUuid()));
    assertThat(readback).isNotNull();
  }

  @Test
  void incompleteBirthCannotCommitAndFailedSaveCannotRetainPartialAuthority() {
    Context context = context(null);
    // Fault injection after birth insertion forces the mandatory exact readback to fail.
    context
        .dsl()
        .execute(
            "CREATE FUNCTION reject_restriction_birth_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'fixture rejects outbox' USING ERRCODE = '23514'; END; $$");
    context
        .dsl()
        .execute(
            "CREATE TRIGGER reject_restriction_birth_outbox BEFORE INSERT "
                + "ON account_platform_restriction_birth_outbox FOR EACH ROW EXECUTE FUNCTION reject_restriction_birth_outbox()");
    Account account = account();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> new AccountRepository(context.dsl()).save(account)))
        .isInstanceOf(DataAccessException.class);
    for (String table :
        new String[] {
          "accounts",
          "account_authority_generations",
          "account_platform_restriction_births",
          "account_platform_restriction_projections",
          "account_platform_restriction_birth_outbox"
        }) {
      assertThat(context.dsl().fetchCount(DSL.table(table))).isZero();
    }
  }

  private static Account account() {
    Account account = new Account();
    String suffix = UUID.randomUUID().toString();
    account.setUsername(suffix);
    account.setEmail(suffix + "@example.test");
    account.setPasswordHash("birth-test-hash");
    account.setRole("player");
    return account;
  }

  private static Context context(String target) {
    String schema = "restriction_birth_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = POSTGRES.dataSource(schema);
    Context context =
        new Context(
            schema,
            dataSource,
            DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    migrate(context, target);
    return context;
  }

  private static void migrate(Context context, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(context.dataSource())
            .schemas(context.schema())
            .defaultSchema(context.schema())
            .locations("classpath:db/migration")
            .placeholders(Map.of("serviceSchema", context.schema()));
    if (target != null) configuration.target(target);
    configuration.load().migrate();
  }

  private record Context(
      String schema,
      org.springframework.jdbc.datasource.DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction) {}
}
