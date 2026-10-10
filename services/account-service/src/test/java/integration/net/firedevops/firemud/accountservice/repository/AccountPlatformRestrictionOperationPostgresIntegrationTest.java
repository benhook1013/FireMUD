package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.SourceKind;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL proof that historical birth never becomes current restriction absence. */
class AccountPlatformRestrictionOperationPostgresIntegrationTest {
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
  void freshBirthDoesNotPopulateTheCurrentRestrictionProjection() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));

    assertThat(
            context.dsl().fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isZero();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            restrictionRepository(context.dsl())
                                .readCurrent(
                                    account.getAccountUuid(), Category.ACCOUNT_SECURITY_LOCK)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionProjectionUnavailableException
                .class);
  }

  @Test
  void fabricatedOwnerCorrelationCannotPromoteBirthToCurrentState() {
    Context context = context(null);
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));

    AccountPlatformRestrictionMutationRequest fabricated =
        context
            .transaction()
            .execute(
                status -> {
                  var birth =
                      new AccountPlatformRestrictionBirthRepository(context.dsl())
                          .readCurrentBirthSource(account.getAccountUuid());
                  var category = birth.accountSecurityLock();
                  return new AccountPlatformRestrictionMutationRequest(
                      UUID.randomUUID(),
                      account.getAccountUuid(),
                      Category.ACCOUNT_SECURITY_LOCK,
                      birth.currentAuthority().generation(),
                      birth.currentAuthority().sourceVersion(),
                      category.revision(),
                      category.enforcementEpoch(),
                      category.resultId(),
                      RestrictionState.RESTRICTED,
                      SourceKind.ACCOUNT_SECURITY_POLICY,
                      UUID.randomUUID(),
                      "sha256:" + "0".repeat(64));
                });

    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(status -> restrictionRepository(context.dsl()).commit(fabricated)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.OwnerAuthorizationUnresolvedException
                .class);
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_operations")))
        .isZero();
    assertThat(
            context.dsl().fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isZero();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            restrictionRepository(context.dsl())
                                .readCurrent(
                                    account.getAccountUuid(), Category.ACCOUNT_SECURITY_LOCK)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionProjectionUnavailableException
                .class);
  }

  @Test
  void retainedPreV137BirthDoesNotBecomeCurrentRestrictionAbsenceAfterMigration() {
    Context context = context("134");
    Account account = account();
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));
    assertThat(context.dsl().fetchCount(DSL.table("account_platform_restriction_projections")))
        .isEqualTo(2);

    migrate(context, null);

    assertThat(
            context.dsl().fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isZero();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            restrictionRepository(context.dsl())
                                .readCurrent(
                                    account.getAccountUuid(), Category.PLATFORM_ACCESS_BAN)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionProjectionUnavailableException
                .class);
  }

  private static AccountPlatformRestrictionOperationRepository restrictionRepository(
      DSLContext dsl) {
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    return new AccountPlatformRestrictionOperationRepository(
        dsl,
        generations,
        outbox,
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox),
        new AccountPlatformRestrictionBirthRepository(dsl));
  }

  private static Account account() {
    Account account = new Account();
    String suffix = UUID.randomUUID().toString();
    account.setUsername(suffix);
    account.setEmail(suffix + "@example.test");
    account.setPasswordHash("restriction-current-test-hash");
    account.setRole("player");
    return account;
  }

  private static Context context(String target) {
    String schema = "restriction_current_" + UUID.randomUUID().toString().replace("-", "");
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
