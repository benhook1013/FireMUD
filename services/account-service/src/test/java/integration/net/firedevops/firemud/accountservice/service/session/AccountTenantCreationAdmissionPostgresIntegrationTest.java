package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mkammerer.argon2.Argon2Factory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real migrated PostgreSQL storage and SQL denial ordering. Reauthentication uses the actual
 * password owner. Issuance/signing and physical Redis evidence are explicitly stipulated SQL
 * fixtures: these tests do not prove genuine actor preparation or qualified Redis/SQL integration.
 * No private actor, admission, or Redis proof constructor is bypassed.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountTenantCreationAdmissionPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final String PASSWORD = "test-only-creation-admission-password";

  @Test
  void frozenOriginalReadbackAndExistingAdmissionPreserveBytesAndXminAfterLaterSqlDenial() {
    Context c = context();
    var binding = frozen(c, false);
    var repository = new AccountTenantCreationAdmissionRepository(c.dsl);
    assertThat(c.tx(() -> repository.readAdmitted(binding.creationOperationId()))).isEmpty();
    assertThat(
            c.tx(() -> repository.readPrepared(binding.creationOperationId()))
                .orElseThrow()
                .binding())
        .isEqualTo(binding);
    insertAdmission(c, binding);
    String before = xmin(c, binding.creationOperationId());
    var issuer = new AccountControlUiIssuanceRepository(c.dsl);
    c.tx(() -> issuer.beginRecoveryFailure(issuer.findToken(binding.tokenHash())));
    // History recovery is an existing-only SQL read, even with a now REVOKING original token.
    var original = c.tx(() -> repository.readAdmitted(binding.creationOperationId())).orElseThrow();
    var retry = c.tx(() -> repository.readAdmitted(binding.creationOperationId())).orElseThrow();
    assertThat(retry.receiptPayload()).containsExactly(binding.canonicalReceipt());
    assertThat(retry.receiptPayload()).containsExactly(original.receiptPayload());
    assertThat(retry.admittedAt()).isEqualTo(original.admittedAt());
    assertThat(xmin(c, binding.creationOperationId())).isEqualTo(before);
  }

  @Test
  void admittedTransactionHoldsActualIssuanceAgainstRecoveryFailureUpdate() {
    Context c = context();
    var binding = frozen(c, false);
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      c.tx(
          () -> {
            insertAdmission(c, binding);
            java.util.concurrent.CompletableFuture.runAsync(
                    () ->
                        assertThatThrownBy(
                                () ->
                                    c.tx(
                                        () -> {
                                          c.dsl.execute("SET LOCAL lock_timeout = '100ms'");
                                          var issuer =
                                              new AccountControlUiIssuanceRepository(c.dsl);
                                          return issuer.beginRecoveryFailure(
                                              issuer.findToken(binding.tokenHash()));
                                        }))
                            .isInstanceOf(DataAccessException.class),
                    executor)
                .join();
            return null;
          });
      var issuer = new AccountControlUiIssuanceRepository(c.dsl);
      assertThat(
              c.tx(() -> issuer.beginRecoveryFailure(issuer.findToken(binding.tokenHash()))).status)
          .isEqualTo("REVOKING");
      assertThat(
              c.tx(
                  () ->
                      new AccountTenantCreationAdmissionRepository(c.dsl)
                          .readAdmitted(binding.creationOperationId())))
          .isPresent();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void exactFrozenDataRetryPreservesXminAndChangedTokenOrInputsCannotReplaceOriginal() {
    Context c = context();
    var binding = frozen(c, false);
    String query =
        "SELECT xmin::text AS xmin FROM account_tenant_creation_admission_requests WHERE creation_operation_id = ?";
    String original =
        Objects.requireNonNull(c.dsl.fetchOne(query, binding.creationOperationId()))
            .get("xmin", String.class);
    assertThat(
            c.tx(
                () ->
                    c.dsl.execute(
                        "INSERT INTO account_tenant_creation_admission_requests SELECT * FROM account_tenant_creation_admission_requests "
                            + "WHERE creation_operation_id = ? ON CONFLICT DO NOTHING",
                        binding.creationOperationId())))
        .isZero();
    assertThat(
            Objects.requireNonNull(c.dsl.fetchOne(query, binding.creationOperationId()))
                .get("xmin", String.class))
        .isEqualTo(original);
    for (String field :
        List.of(
            "token_hash", "target_namespace", "intent_digest", "reauthentication_evidence_id")) {
      String changed =
          field.equals("reauthentication_evidence_id") ? UUID.randomUUID().toString() : "changed";
      assertThatThrownBy(
              () ->
                  c.tx(
                      () ->
                          c.dsl.execute(
                              "INSERT INTO account_tenant_creation_admission_requests "
                                  + "SELECT (jsonb_populate_record(NULL::account_tenant_creation_admission_requests, "
                                  + "to_jsonb(r) || jsonb_build_object(?::text, ?::text))).* "
                                  + "FROM account_tenant_creation_admission_requests r WHERE creation_operation_id = ?",
                              field,
                              changed,
                              binding.creationOperationId())))
          .isInstanceOf(DataAccessException.class);
    }
    assertThat(
            c.tx(
                    () ->
                        new AccountTenantCreationAdmissionRepository(c.dsl)
                            .readPrepared(binding.creationOperationId()))
                .orElseThrow()
                .binding())
        .isEqualTo(binding);
  }

  @Test
  void earlierActualRecoveryFailureDeniesAdmissionDespiteUnchangedStoredActiveRegistry() {
    Context c = context();
    var binding = frozen(c, false);
    var issuer = new AccountControlUiIssuanceRepository(c.dsl);
    byte[] active = c.tx(() -> issuer.findToken(binding.tokenHash()).activeRegistry.clone());
    c.tx(() -> issuer.beginRecoveryFailure(issuer.findToken(binding.tokenHash())));
    assertThat(c.tx(() -> issuer.findToken(binding.tokenHash()).activeRegistry))
        .containsExactly(active);
    assertThatThrownBy(() -> insertAdmission(c, binding)).isInstanceOf(DataAccessException.class);
    assertThat(
            c.tx(
                () ->
                    new AccountTenantCreationAdmissionRepository(c.dsl)
                        .readAdmitted(binding.creationOperationId())))
        .isEmpty();
  }

  @Test
  void logicalExpiryDeniesEvenWhilePhysicalCleanupDeadlineLives() {
    Context c = context();
    var binding = frozen(c, true);
    assertThat(binding.tokenCleanupDeadlineEpochMillis()).isGreaterThan(System.currentTimeMillis());
    assertThatThrownBy(() -> insertAdmission(c, binding)).isInstanceOf(DataAccessException.class);
  }

  @Test
  void directSqlCannotChangeBindingTupleReceiptOrRetainedRows() {
    Context c = context();
    var binding = frozen(c, false);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "INSERT INTO account_tenant_creation_admissions "
                                + "(creation_operation_id, receipt_payload, receipt_digest, local_aof_count, replica_aof_count, protected_binding_id, qualification_fence) "
                                + "VALUES (?, ?, ?, 1, 1, 'test-stipulated', 1)",
                            binding.creationOperationId(),
                            "changed".getBytes(StandardCharsets.UTF_8),
                            AccountControlUiCoordination.digest(
                                "SHA-256", "changed".getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(DataAccessException.class);
    for (String field : List.of("token_hash", "target_namespace", "intent_digest")) {
      assertThatThrownBy(
              () ->
                  c.tx(
                      () ->
                          c.dsl.execute(
                              "UPDATE account_tenant_creation_admission_requests SET "
                                  + field
                                  + " = 'changed'")))
          .isInstanceOf(DataAccessException.class);
    }
    insertAdmission(c, binding);
    for (String table :
        List.of(
            "account_tenant_creation_admission_requests", "account_tenant_creation_admissions")) {
      assertThatThrownBy(() -> c.tx(() -> c.dsl.execute("DELETE FROM " + table)))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> c.tx(() -> c.dsl.execute("TRUNCATE " + table + " CASCADE")))
          .isInstanceOf(DataAccessException.class);
    }
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_tenant_creation_admissions SET qualification_fence = qualification_fence + 1")))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void absentOrUncertainPhysicalEvidenceHasNoAdmissionAndZeroLocalAofCannotBeInserted() {
    Context c = context();
    var binding = frozen(c, false);
    var repository = new AccountTenantCreationAdmissionRepository(c.dsl);
    var prepared = c.tx(() -> repository.readPrepared(binding.creationOperationId())).orElseThrow();
    assertThatThrownBy(() -> c.tx(() -> repository.admitLocked(prepared, null)))
        .isInstanceOf(NullPointerException.class);
    assertThat(c.tx(() -> repository.readAdmitted(binding.creationOperationId()))).isEmpty();
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "INSERT INTO account_tenant_creation_admissions "
                                + "(creation_operation_id, receipt_payload, receipt_digest, local_aof_count, replica_aof_count, protected_binding_id, qualification_fence) "
                                + "VALUES (?, ?, ?, 0, 0, 'test-stipulated', 1)",
                            binding.creationOperationId(),
                            binding.canonicalReceipt(),
                            AccountControlUiCoordination.digest(
                                "SHA-256", binding.canonicalReceipt()))))
        .isInstanceOf(DataAccessException.class);
  }

  private static String xmin(Context c, UUID operation) {
    return Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT xmin::text AS xmin FROM account_tenant_creation_admissions WHERE creation_operation_id = ?",
                operation))
        .get("xmin", String.class);
  }

  private static void insertAdmission(Context c, AccountTenantCreationAdmissionBinding binding) {
    c.tx(
        () ->
            c.dsl.execute(
                "INSERT INTO account_tenant_creation_admissions "
                    + "(creation_operation_id, receipt_payload, receipt_digest, local_aof_count, replica_aof_count, protected_binding_id, qualification_fence) "
                    + "VALUES (?, ?, ?, 1, 1, 'test-stipulated-physical-evidence', 7)",
                binding.creationOperationId(),
                binding.canonicalReceipt(),
                AccountControlUiCoordination.digest("SHA-256", binding.canonicalReceipt())));
  }

  private static AccountTenantCreationAdmissionBinding frozen(Context c, boolean expired) {
    Account account = c.account("PASSWORD");
    UUID creation = UUID.randomUUID();
    var evidence =
        c.service.reauthenticate(c.request(account, UUID.randomUUID(), creation, PASSWORD));
    var intent =
        c.tx(
            () ->
                new AccountTenantCreationIntentRepository(c.dsl, c.evidenceRepository)
                    .claim(
                        new AccountTenantCreationIntentRepository.Request(
                            UUID.randomUUID(),
                            creation,
                            UUID.randomUUID(),
                            account.getAccountUuid(),
                            "test-game",
                            "source-game",
                            "Game",
                            null,
                            evidence.evidenceId(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            1,
                            true)));
    UUID issuance = UUID.randomUUID();
    String tokenHash = "d".repeat(64);
    byte[] active = "test-only-active-registry".getBytes(StandardCharsets.UTF_8);
    long issued = Instant.now().getEpochSecond() - (expired ? 301 : 0);
    long expiry = (issued + 300) * 1000;
    long prepared = issued * 1000;
    c.tx(
        () ->
            c.dsl.execute(
                "INSERT INTO account_control_ui_issuance_operations "
                    + "(request_id, operation_id, token_jti, account_uuid, tenant_uuid, authorization_scope, caller_workload, "
                    + "caller_context_id, request_mac_key_id, request_digest, claims_payload, source_payload, bundle_payload, signer_receipt, "
                    + "issued_at_epoch_second, expires_at_epoch_second, registry_cleanup_deadline_epoch_millis, recovery_expires_at, status) "
                    + "VALUES (?, ?, ?, ?, NULL, 'ACCOUNT', 'spiffe://test-only/creation', ?, 'test-mac', ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'PREPARED')",
                UUID.randomUUID(),
                issuance,
                UUID.randomUUID(),
                account.getAccountUuid(),
                UUID.randomUUID(),
                "c".repeat(64),
                active,
                active,
                active,
                active,
                issued,
                issued + 300,
                expiry + 300000,
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(issued + 60), ZoneOffset.UTC)));
    c.tx(
        () ->
            c.dsl.execute(
                "UPDATE account_control_ui_issuance_operations SET status = 'CANDIDATE', token_hash = ?, "
                    + "pending_registry = ?, active_registry = ? WHERE operation_id = ?",
                tokenHash,
                active,
                active,
                issuance));
    c.tx(
        () ->
            c.dsl.execute(
                "INSERT INTO account_control_ui_response_envelopes (operation_id, encrypted_response, owner_binding, recovery_expires_at) "
                    + "SELECT operation_id, ?, ?, recovery_expires_at FROM account_control_ui_issuance_operations WHERE operation_id = ?",
                active,
                active,
                issuance));
    c.tx(
        () ->
            c.dsl.execute(
                "UPDATE account_control_ui_issuance_operations SET status = 'COMMITTED', pending_receipt = ?, "
                    + "committed_at = CURRENT_TIMESTAMP WHERE operation_id = ?",
                active,
                issuance));
    var binding =
        new AccountTenantCreationAdmissionBinding(
            intent.requestId(),
            creation,
            issuance,
            account.getAccountUuid(),
            intent.targetNamespace(),
            intent.gameDesignCreationRequestId(),
            intent.gameDesignRequestDigest(),
            intent.requestDigest(),
            AccountControlUiCoordination.digest("SHA-256", active),
            evidence.evidenceId(),
            tokenHash,
            expiry + 300000,
            expiry,
            prepared,
            expiry,
            expiry + 86400000);
    c.tx(
        () ->
            c.dsl.execute(
                "INSERT INTO account_tenant_creation_admission_requests "
                    + "(creation_operation_id, intent_request_id, issuance_operation_id, actor_account_id, target_namespace, "
                    + "game_design_creation_request_id, game_design_request_digest, intent_digest, active_registry_digest, "
                    + "reauthentication_evidence_id, token_hash, token_cleanup_deadline_epoch_millis, token_expires_at_epoch_millis, "
                    + "prepared_at_epoch_millis, operation_deadline_epoch_millis, receipt_cleanup_deadline_epoch_millis, binding_payload, binding_digest) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                creation,
                intent.requestId(),
                issuance,
                account.getAccountUuid(),
                binding.targetNamespace(),
                binding.gameDesignCreationRequestId(),
                binding.gameDesignRequestDigest(),
                binding.intentDigest(),
                binding.activeRegistryDigest(),
                evidence.evidenceId(),
                tokenHash,
                binding.tokenCleanupDeadlineEpochMillis(),
                expiry,
                prepared,
                expiry,
                binding.receiptCleanupDeadlineEpochMillis(),
                binding.canonicalReceipt(),
                AccountControlUiCoordination.digest("SHA-256", binding.canonicalReceipt())));
    return binding;
  }

  private static Context context() {
    String schema = "recent_reauth_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var flywayConfiguration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    flywayConfiguration.load().migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(source);
    var transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.executeWithoutResult(
        ignored -> {
          dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_values "
                  + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                  + "VALUES ('PLATFORM', 1, 300, 30), ('DEPLOYMENT', 1, 300, 30)");
          dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_current "
                  + "(policy_scope, policy_version) VALUES ('PLATFORM', 1), ('DEPLOYMENT', 1)");
        });
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    var accounts = new AccountRepository(dsl, sourceEvidence);
    var challenges = new AccountEmailLoginChallengeRepository(dsl);
    var primary =
        new AccountServiceImpl(
            accounts,
            generations,
            sourceEvidence,
            outbox,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            mock(AccountSecurityStateOperationRepository.class),
            mock(AccountPasswordResetDraftSourceChangeRepository.class),
            mock(AccountAuditOutboxRepository.class),
            mock(AccountConnectScopeRepository.class),
            mock(AccountJoinOperationRepository.class),
            challenges,
            null,
            null,
            Mappers.getMapper(AccountMapper.class),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            manager);
    var evidence = new AccountRecentReauthenticationRepository(dsl);
    var policies = new AccountRecentReauthenticationPolicyRepository(dsl);
    var clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    var service =
        new AccountRecentReauthenticationService(
            primary, accounts, generations, evidence, policies, manager, clock);
    return new Context(dsl, accounts, evidence, service, transaction);
  }

  private static String hash(String secret) {
    char[] chars = secret.toCharArray();
    var argon = Argon2Factory.create();
    try {
      return argon.hash(2, 4096, 1, chars);
    } finally {
      argon.wipeArray(chars);
    }
  }

  private record Context(
      DSLContext dsl,
      AccountRepository accounts,
      AccountRecentReauthenticationRepository evidenceRepository,
      AccountRecentReauthenticationService service,
      TransactionTemplate transaction) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }

    Account account(String modes) {
      Account account = new Account();
      String suffix = UUID.randomUUID().toString();
      account.setUsername("creation-admission-" + suffix);
      account.setEmail(suffix + "@example.test");
      account.setPasswordHash(hash(PASSWORD));
      account.setRole("player");
      account.setLoginAuthModes(modes);
      account.setLifecycleState(AccountLifecycleState.ACTIVE);
      return tx(() -> accounts.save(account));
    }

    AccountRecentReauthenticationService.Request request(
        Account account, UUID requestId, UUID operationId, String secret) {
      return new AccountRecentReauthenticationService.Request(
          requestId,
          account.getAccountUuid(),
          AccountRecentReauthenticationPolicy.PURPOSE,
          operationId,
          account.getEmail(),
          secret);
    }
  }
}
