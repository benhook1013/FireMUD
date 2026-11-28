package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.RestrictionState;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository.BirthSourceUnavailableException;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Physical PostgreSQL proof for V81's historical Account restriction birth evidence. */
class AccountPlatformRestrictionBirthPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void independentAccountAdvancePreservesBothHistoricalNonrestrictedResults() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));
    var birth = Objects.requireNonNull(readBirth(context, account.getAccountUuid()));
    assertThat(birth.accountSourceNumericId()).isEqualTo(account.getId());
    assertThat(birth.birthAuthority()).isEqualTo(birth.currentAuthority());
    assertThat(birth.accountSecurityLock().restrictionState())
        .isEqualTo(RestrictionState.NONRESTRICTED);
    assertThat(birth.platformAccessBan().restrictionState())
        .isEqualTo(RestrictionState.NONRESTRICTED);
    assertThat(context.dsl().fetchCount(DSL.table("account_authority_outbox_events"))).isZero();

    context
        .transaction()
        .executeWithoutResult(
            status -> {
              account.setPasswordHash("changed-birth-test-hash");
              new AccountRepository(context.dsl()).save(account);
            });
    var later = Objects.requireNonNull(readBirth(context, account.getAccountUuid()));
    assertThat(later.accountId()).isEqualTo(birth.accountId());
    assertThat(later.accountSourceNumericId()).isEqualTo(birth.accountSourceNumericId());
    assertThat(later.accountInsertTransactionId()).isEqualTo(birth.accountInsertTransactionId());
    assertThat(later.birthAuthority()).isEqualTo(birth.birthAuthority());
    assertThat(later.accountSecurityLock()).isEqualTo(birth.accountSecurityLock());
    assertThat(later.platformAccessBan()).isEqualTo(birth.platformAccessBan());
    assertThat(later.currentAuthority().generation()).isEqualTo(2L);
    assertThat(later.currentAuthority().sourceVersion()).isEqualTo(2L);
    assertThat(later.currentAuthority().issuanceFence().value()).isEqualTo(2L);
    assertThat(later.currentAuthority().issuanceFence().sourceVersion()).isEqualTo(2L);
    assertThat(context.dsl().fetchCount(DSL.table("account_authority_outbox_events"))).isEqualTo(1);
  }

  @Test
  void rollbackLeavesNoAccountAuthorityOrBirthEvidence() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              new AccountRepository(context.dsl()).save(account);
              assertThat(
                      new AccountPlatformRestrictionBirthRepository(context.dsl())
                          .readCurrentBirthSource(account.getAccountUuid()))
                  .isNotNull();
              status.setRollbackOnly();
            });

    assertNoAccountOrBirthEvidence(context);
    assertThatThrownBy(() -> readBirth(context, account.getAccountUuid()))
        .isInstanceOf(BirthSourceUnavailableException.class);
  }

  @Test
  void postgresRefusesCommitWhenOneRequiredBirthOutboxRowIsMissing() {
    Context context = context(null);
    interceptBirthOutboxInsert(context, "RETURN NULL;");
    Account account = account();

    assertPostgresRejection(
        () ->
            context
                .transaction()
                .executeWithoutResult(
                    status -> {
                      // Keep the transaction open after Java rejects the zero-row outbox insert.
                      assertThatThrownBy(() -> new AccountRepository(context.dsl()).save(account))
                          .isInstanceOf(BirthSourceUnavailableException.class);
                      assertThat(context.dsl().fetchCount(DSL.table("accounts"))).isEqualTo(1);
                      assertThat(
                              context
                                  .dsl()
                                  .fetchCount(DSL.table("account_platform_restriction_births")))
                          .isEqualTo(2);
                      assertThat(
                              context
                                  .dsl()
                                  .fetchCount(
                                      DSL.table("account_platform_restriction_projections")))
                          .isEqualTo(2);
                      assertThat(
                              context
                                  .dsl()
                                  .fetchCount(
                                      DSL.table("account_platform_restriction_birth_outbox")))
                          .isEqualTo(1);
                    }),
        "23514",
        "account_restriction_birth_complete");

    assertNoAccountOrBirthEvidence(context);
  }

  @Test
  void substitutesForExactBirthResultOrDigestRollBackTheSave() {
    Context context = context(null);
    for (String substitution :
        new String[] {
          "NEW.result_id := gen_random_uuid();", "NEW.payload_digest := repeat('0', 64);"
        }) {
      interceptBirthOutboxInsert(context, substitution);
      assertPostgresRejection(
          () ->
              context
                  .transaction()
                  .executeWithoutResult(
                      status -> new AccountRepository(context.dsl()).save(account())),
          "23503",
          "account_restriction_birth_outbox_exact_result");
      assertNoAccountOrBirthEvidence(context);
    }
  }

  @Test
  void committedBirthResultAndOutboxCannotBeRewritten() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));
    var before = Objects.requireNonNull(readBirth(context, account.getAccountUuid()));

    for (String mutation :
        new String[] {
          "UPDATE account_platform_restriction_births SET payload_digest = repeat('0', 64)",
          "UPDATE account_platform_restriction_birth_outbox SET result_id = gen_random_uuid()"
        }) {
      assertPostgresRejection(
          () ->
              context
                  .dsl()
                  .execute(
                      mutation + " WHERE account_uuid = ? AND category = 'account_security_lock'",
                      account.getAccountUuid()),
          "23514",
          "account_restriction_birth_immutable");
    }
    assertThat(readBirth(context, account.getAccountUuid())).isEqualTo(before);
  }

  @Test
  void v80RetainedDatabaseAccountStaysUnenrolledAfterMigrationAndReadback() {
    Context context = context("80");
    Account retained = account();
    UUID retainedUuid =
        Objects.requireNonNull(
            context
                .dsl()
                .resultQuery(
                    "INSERT INTO accounts (username, email, password_hash, role) "
                        + "VALUES (?, ?, ?, 'player') RETURNING account_uuid",
                    retained.getUsername(),
                    retained.getEmail(),
                    retained.getPasswordHash())
                .fetchOne(0, UUID.class));
    migrate(context, null);

    assertThatThrownBy(() -> readBirth(context, retainedUuid))
        .isInstanceOf(BirthSourceUnavailableException.class);
    assertThat(new AccountRepository(context.dsl()).findByEmail(retained.getEmail()))
        .hasValueSatisfying(
            account -> assertThat(account.getAccountUuid()).isEqualTo(retainedUuid));
    assertNoBirthEvidence(context);
  }

  private static AccountPlatformRestrictionBirthSource readBirth(Context context, UUID accountId) {
    return context
        .transaction()
        .execute(
            status ->
                new AccountPlatformRestrictionBirthRepository(context.dsl())
                    .readCurrentBirthSource(accountId));
  }

  private static void assertNoAccountOrBirthEvidence(Context context) {
    for (String table :
        new String[] {
          "accounts",
          "account_global_role_sources",
          "account_authority_generations",
          "account_authority_issuance_fences",
          "account_authority_source_records",
          "account_authority_outbox_streams",
          "account_authority_outbox_events"
        }) {
      assertThat(context.dsl().fetchCount(DSL.table(table))).as(table).isZero();
    }
    assertNoBirthEvidence(context);
  }

  private static void assertNoBirthEvidence(Context context) {
    for (String table :
        new String[] {
          "account_platform_restriction_births",
          "account_platform_restriction_projections",
          "account_platform_restriction_birth_outbox"
        }) {
      assertThat(context.dsl().fetchCount(DSL.table(table))).as(table).isZero();
    }
  }

  private static void assertPostgresRejection(
      ThrowingCallable write, String sqlState, String constraint) {
    assertThatThrownBy(write)
        .rootCause()
        .isInstanceOfSatisfying(
            PSQLException.class,
            failure -> {
              assertThat(failure.getSQLState()).isEqualTo(sqlState);
              assertThat(Objects.requireNonNull(failure.getServerErrorMessage()).getConstraint())
                  .isEqualTo(constraint);
            });
  }

  private static void interceptBirthOutboxInsert(Context context, String beforeReturn) {
    context
        .dsl()
        .execute(
            "CREATE OR REPLACE FUNCTION intercept_birth_outbox_insert() "
                + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.category = 'platform_access_ban' THEN "
                + beforeReturn
                + " END IF; RETURN NEW; END; $$");
    context
        .dsl()
        .execute(
            "CREATE OR REPLACE TRIGGER intercept_birth_outbox_insert BEFORE INSERT "
                + "ON account_platform_restriction_birth_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION intercept_birth_outbox_insert()");
  }

  private static Account account() {
    Account account = new Account();
    String suffix = UUID.randomUUID().toString();
    account.setUsername(suffix);
    account.setEmail(suffix + "@example.test");
    account.setPasswordHash("birth-test-hash");
    return account;
  }

  private static Context context(String target) {
    String schema = "restriction_birth_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
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
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private record Context(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction) {}
}
