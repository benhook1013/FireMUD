package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCreatorMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapAuthorizationSource;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Physical PostgreSQL proof for the first Account source/currentness forward stage. */
class AccountSourceCurrentnessForwardMigrationsPostgresIntegrationTest {
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
  void freshInstallAppliesEveryForwardMigrationThroughV97() {
    TestContext context = context(null);

    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT version FROM flyway_schema_history "
                        + "WHERE success AND version = '97'")
                .fetchOne(0, String.class))
        .isEqualTo("97");
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM flyway_schema_history "
                        + "WHERE version::numeric BETWEEN 74 AND 97 AND success")
                .fetchOne(0, Long.class))
        .isEqualTo(24L);

    for (String relation :
        new String[] {
          "account_global_role_sources",
          "account_draft_authorization_source_locks",
          "account_draft_authorization_fences",
          "account_draft_authorization_source_changes",
          "account_password_reset_operation_receipts",
          "account_logout_all_operation_receipts",
          "account_security_state_operations",
          "account_platform_restriction_births",
          "account_tenant_creation_bootstrap_operations",
          "account_individual_creator_party_sources",
          "account_tenant_creator_party_history",
          "account_hosted_terms_scopes",
          "account_hosted_terms_catalog_versions",
          "account_hosted_terms_environment_binding_heads",
          "account_logout_all_draft_source_changes",
          "account_password_reset_draft_source_changes",
          "account_game_logic_intake_source_read_reservations",
          "account_game_logic_intake_source_read_sources",
          "account_game_logic_intake_source_read_aborts"
        }) {
      assertThat(
              context
                  .dsl()
                  .resultQuery("SELECT to_regclass(?)::TEXT", relation)
                  .fetchOne(0, String.class))
          .as("forward relation %s", relation)
          .isNotNull();
    }
    for (String function :
        new String[] {
          "account_draft_authorization_required_owners(bytea)",
          "account_draft_authorization_is_settled(uuid)",
          "account_creator_party_initial_receipt_guard()",
          "account_hosted_terms_acceptance_insert_guard()",
          "account_password_reset_draft_link_guard()",
          "account_game_logic_intake_source_read_is_pending(uuid)",
          "account_game_logic_intake_source_read_complete_guard()",
          "account_game_logic_intake_source_read_source_guard()",
          "account_game_logic_intake_source_read_terminal_guard()"
        }) {
      assertThat(
              context
                  .dsl()
                  .resultQuery("SELECT to_regprocedure(?)::TEXT", function)
                  .fetchOne(0, String.class))
          .as("forward SQL guard %s", function)
          .isNotNull();
    }
  }

  @Test
  void v73RetainedAccountStaysUnenrolledWhileFreshRepositoryAccountGetsExactBirthSources() {
    TestContext context = context("73");
    String retainedSuffix = UUID.randomUUID().toString();
    Long retainedId =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "INSERT INTO accounts (username, email, password_hash, role) "
                            + "VALUES (?, ?, ?, 'player') RETURNING id",
                        "retained-" + retainedSuffix,
                        retainedSuffix + "@example.test",
                        "retained-password-" + retainedSuffix))
            .get(0, Long.class);
    UUID retainedUuid =
        Objects.requireNonNull(
            context
                .dsl()
                .resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", retainedId)
                .fetchOne(0, UUID.class));

    migrate(context, null);
    Account fresh = account(null);
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(fresh));

    assertThat(count(context, "account_authority_source_records", "account_uuid", retainedUuid))
        .isZero();
    assertThat(count(context, "account_global_role_sources", "account_uuid", retainedUuid))
        .isZero();
    assertThat(count(context, "account_platform_restriction_births", "account_uuid", retainedUuid))
        .isZero();

    assertThat(
            count(
                context,
                "account_authority_source_records",
                "account_uuid",
                fresh.getAccountUuid()))
        .isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_global_role_sources "
                        + "WHERE account_uuid = ? AND global_role_source_version = 1 "
                        + "AND global_roles = ARRAY[]::TEXT[]",
                    fresh.getAccountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_platform_restriction_births "
                        + "WHERE account_uuid = ? AND revision = 1 AND enforcement_epoch = 1 "
                        + "AND source_kind = 'CATEGORY' AND restriction_state = 'NONRESTRICTED'",
                    fresh.getAccountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_platform_restriction_birth_outbox "
                        + "WHERE account_uuid = ? AND outbox_sequence = 1",
                    fresh.getAccountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            count(
                context,
                "account_platform_restriction_projections",
                "account_uuid",
                fresh.getAccountUuid()))
        .isEqualTo(2L);

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_global_role_sources "
                            + "SET global_roles = ARRAY['moderator']::TEXT[] "
                            + "WHERE account_uuid = ?",
                        fresh.getAccountUuid()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_platform_restriction_births "
                            + "SET payload_digest = repeat('0', 64) "
                            + "WHERE account_uuid = ? AND category = 'account_security_lock'",
                        fresh.getAccountUuid()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void malformedDraftOwnerEvidenceCannotSettleOrRewriteItsSourceChange() {
    TestContext context = context(null);
    UUID operationId = UUID.randomUUID();
    UUID changeId = UUID.randomUUID();
    String sourceKey = "TEST_SOURCE:" + UUID.randomUUID();
    byte[] malformedBinding = "not-a-canonical-draft-fence".getBytes(StandardCharsets.UTF_8);

    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)",
            sourceKey);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_fences "
                + "(operation_id, request_id, commit_id, fence_id, binding, ordering) "
                + "VALUES (?, ?, ?, ?, ?, 'RESERVED')",
            operationId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            malformedBinding);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_sources "
                + "(operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
            operationId,
            sourceKey,
            "malformed-source-evidence".getBytes(StandardCharsets.UTF_8));
    context
        .dsl()
        .execute(
            "UPDATE account_draft_authorization_fences SET ordering = 'COMMIT_ORDER', "
                + "ordered_at = CURRENT_TIMESTAMP WHERE operation_id = ?",
            operationId);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_changes (change_id, binding, status) "
                + "VALUES (?, ?, 'WAITING')",
            changeId,
            malformedBinding);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_changed_scopes (change_id, source_key) "
                + "VALUES (?, ?)",
            changeId,
            sourceKey);

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_draft_authorization_owner_readbacks "
                            + "(operation_id, owner, outcome, readback) "
                            + "VALUES (?, 'GAME_DESIGN', 'COMMITTED', ?)",
                        operationId,
                        new byte[] {1}))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_draft_authorization_source_changes "
                            + "SET status = 'SOURCE_COMMITTED', committed_at = CURRENT_TIMESTAMP "
                            + "WHERE change_id = ?",
                        changeId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_draft_authorization_source_changes WHERE change_id = ?",
                        changeId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT status FROM account_draft_authorization_source_changes WHERE change_id = ?",
                    changeId)
                .fetchOne(0, String.class))
        .isEqualTo("WAITING");
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_draft_authorization_owner_readbacks "
                        + "WHERE operation_id = ?",
                    operationId)
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void nonoperativeTermsAndUnverifiedCreatorSourcesStayImmutableAndRejectContradictoryEvidence() {
    TestContext context = context(null);
    Account account = account("player");
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));

    UUID creatorPartyId = UUID.randomUUID();
    byte[] creatorPayload = "test-only unverified creator source".getBytes(StandardCharsets.UTF_8);
    context
        .dsl()
        .execute(
            "INSERT INTO account_individual_creator_party_sources "
                + "(creator_party_id, account_uuid, verification_status, identity_version, "
                + "source_version, source_payload, source_digest) "
                + "VALUES (?, ?, 'UNVERIFIED', 1, 1, ?, ?)",
            creatorPartyId,
            account.getAccountUuid(),
            creatorPayload,
            sha256(creatorPayload));

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_individual_creator_party_sources "
                            + "SET source_version = source_version WHERE creator_party_id = ?",
                        creatorPartyId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_individual_creator_party_sources "
                            + "WHERE creator_party_id = ?",
                        creatorPartyId))
        .isInstanceOf(DataAccessException.class);

    UUID missingTenantSource = UUID.randomUUID();
    byte[] creatorHistory = "no canonical tenant owner source".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_tenant_creator_party_history "
                            + "(history_id, tenant_uuid, origin, creator_party_id, source_version, "
                            + "evidence_payload, evidence_digest) "
                            + "VALUES (?, ?, 'FRESH_INITIAL', ?, 1, ?, ?)",
                        UUID.randomUUID(),
                        missingTenantSource,
                        creatorPartyId,
                        creatorHistory,
                        sha256(creatorHistory)))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_tenant_creator_party_history "
                        + "WHERE tenant_uuid = ?",
                    missingTenantSource)
                .fetchOne(0, Long.class))
        .isZero();

    UUID hostedScopeId = UUID.randomUUID();
    UUID termsVersionId = UUID.randomUUID();
    byte[] nonoperativeDocument =
        "synthetic test fixture; not legal terms or a publication".getBytes(StandardCharsets.UTF_8);
    byte[] versionPayload =
        "synthetic nonoperative catalog candidate".getBytes(StandardCharsets.UTF_8);
    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_scopes (hosted_scope_id) VALUES (?)", hostedScopeId);
    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_catalog_versions "
                + "(version_id, hosted_scope_id, predecessor_version_id, operator_legal_identity, "
                + "operator_identity_version, document_bytes, document_digest, source_version, "
                + "material_generation, materiality, publication_evidence_reference, "
                + "publication_evidence_version, notice_evidence_reference, notice_evidence_version, "
                + "effective_at, version_payload, version_digest) "
                + "VALUES (?, ?, NULL, 'TEST FIXTURE ONLY', 1, ?, ?, 1, 1, 'INITIAL', "
                + "'no-publication-fixture', 1, 'no-notice-fixture', 1, ?, ?, ?)",
            termsVersionId,
            hostedScopeId,
            nonoperativeDocument,
            sha256(nonoperativeDocument),
            OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString(),
            versionPayload,
            sha256(versionPayload));

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_hosted_terms_catalog_versions "
                            + "SET operator_legal_identity = 'changed fixture' WHERE version_id = ?",
                        termsVersionId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_hosted_terms_catalog_versions WHERE version_id = ?",
                        termsVersionId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT current_version_id FROM account_hosted_terms_scopes "
                        + "WHERE hosted_scope_id = ?",
                    hostedScopeId)
                .fetchOne(0, UUID.class))
        .isNull();
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_hosted_terms_publication_operations")
                .fetchOne(0, Long.class))
        .isZero();

    byte[] affirmativeAction = "{}".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_individual_hosted_terms_acceptances "
                            + "(evidence_id, action_request_id, creator_party_id, account_uuid, "
                            + "hosted_scope_id, terms_version_id, document_digest, "
                            + "operator_legal_identity, operator_identity_version, source_version, "
                            + "material_generation, individual_party_source, "
                            + "individual_party_source_digest, "
                            + "affirmative_action_evidence, affirmative_action_digest, accepted_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST FIXTURE ONLY', 1, 1, 1, ?, ?, ?, ?, "
                            + "CURRENT_TIMESTAMP)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        creatorPartyId,
                        account.getAccountUuid(),
                        hostedScopeId,
                        termsVersionId,
                        sha256(nonoperativeDocument),
                        creatorPayload,
                        sha256(creatorPayload),
                        affirmativeAction,
                        sha256(affirmativeAction)))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_individual_hosted_terms_acceptances "
                        + "WHERE creator_party_id = ?",
                    creatorPartyId)
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void creatorBootstrapWithoutAuthorizationSourceDeniesBeforeAnyOwnerWrite() {
    TestContext context = context(null);
    Account creator = account("player");
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(creator));

    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    ObjectProvider<AccountTenantCreationBootstrapAuthorizationSource> noAuthorizationSource =
        beans.getBeanProvider(AccountTenantCreationBootstrapAuthorizationSource.class);
    AccountTenantCreationBootstrapService service =
        new AccountTenantCreationBootstrapService(
            new AccountRepository(context.dsl()),
            mock(FreshTenantIdentityAssociationRepository.class),
            mock(AccountCreatorMembershipSourceReader.class),
            mock(AccountMembershipPairAuthorityRepository.class),
            mock(AccountTenantMembershipRepository.class),
            mock(AccountTenantMembershipRoleSnapshotRepository.class),
            mock(AccountAuthorityOutboxRepository.class),
            mock(AccountAuditOutboxRepository.class),
            new AccountTenantCreationBootstrapOperationRepository(context.dsl()),
            noAuthorizationSource);
    FreshTenantCreatorEvidence evidence = creatorEvidence(creator.getAccountUuid());

    assertThatThrownBy(
            () -> context.transaction().executeWithoutResult(status -> service.bootstrap(evidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No current Account creator-authorization/source participant");
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_tenant_creation_bootstrap_operations")
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_tenant_membership")
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_authority_outbox_events")
                .fetchOne(0, Long.class))
        .isZero();
  }

  private static FreshTenantCreatorEvidence creatorEvidence(UUID accountUuid) {
    UUID creationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    UUID tenantUuid = UUID.randomUUID();
    String namespace = "test";
    String tenantKey = "fixture-tenant";
    String creationRequestDigest =
        GameTenantCreationDigest.requestDigest(
            namespace, creationRequestId, tenantKey, "test fixture", null);
    String creationEvidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            namespace,
            creationRequestId,
            creationOperationId,
            creationRequestDigest,
            tenantUuid,
            1L,
            tenantKey,
            "NEW_GAME_ROW");
    FreshTenantCreationEvidence creation =
        new FreshTenantCreationEvidence(
            1,
            namespace,
            creationRequestId,
            creationOperationId,
            creationRequestDigest,
            tenantUuid,
            1L,
            tenantKey,
            "NEW_GAME_ROW",
            creationEvidenceDigest);
    UUID authorizationOperationId = UUID.randomUUID();
    String authorizationDigest =
        GameTenantCreationDigest.requestDigest(
            namespace, UUID.randomUUID(), "fixture-authorization", "test fixture", null);
    String creatorDigest =
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, accountUuid, authorizationOperationId, authorizationDigest);
    return new FreshTenantCreatorEvidence(
        1, creation, accountUuid, authorizationOperationId, authorizationDigest, creatorDigest);
  }

  private static Account account(String role) {
    String suffix = UUID.randomUUID().toString();
    Account account = new Account();
    account.setUsername("forward-source-" + suffix.replace("-", ""));
    account.setEmail(suffix.replace("-", "") + "@example.test");
    account.setPasswordHash("test-only-password-hash");
    account.setRole(role);
    account.setLifecycleState(AccountLifecycleState.ACTIVE);
    return account;
  }

  private static long count(TestContext context, String table, String column, UUID value) {
    return Objects.requireNonNull(
        context
            .dsl()
            .resultQuery("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", value)
            .fetchOne(0, Long.class));
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static TestContext context(String target) {
    String schema = "account_forward_source_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
    TestContext context =
        new TestContext(
            schema,
            dataSource,
            DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    migrate(context, target);
    return context;
  }

  private static void migrate(TestContext context, String target) {
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

  private record TestContext(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction) {}
}
