package integration.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL raw-source and lock-order definitions. Complete external authenticated capture
 * and terminal owner RPC readbacks are explicitly test-only stipulated fixtures, NOT production
 * caller/source/World proof. These tests do not claim genuine issuance or COMMIT_ORDER provenance.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountControlUiSourceGuardPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void rawProtectedMutationCannotBypassOriginalFenceButNoOpAndUnrelatedAccountRemainWritable() {
    Context c = context();
    var binding = binding(c);
    c.tx(
        () -> {
          c.fences.reserve(binding);
          c.fences.claimCommitOrder(binding);
          return null;
        });
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE accounts SET password_hash = ? WHERE account_uuid = ?",
                            "test-only-bypass",
                            binding.actorAccountId())))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("every original owner settles");
    c.tx(
        () ->
            c.dsl.execute(
                "UPDATE accounts SET id = id WHERE account_uuid = ?", binding.actorAccountId()));
    // Username/email are not captured Account security/role fields. Existing protections still
    // apply.
    c.tx(
        () ->
            c.dsl.execute(
                "UPDATE accounts SET username = ? WHERE account_uuid = ?",
                "test-noop-metadata-" + UUID.randomUUID(),
                binding.actorAccountId()));
    UUID unrelated = account(c);
    changePassword(c, unrelated, "test-only-unrelated-account-change");
    var ordering =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT ordering FROM account_draft_authorization_fences WHERE operation_id = ?",
                binding.operationId()),
            "Original authorization fence readback required");
    assertThat(ordering.get("ordering", String.class)).isEqualTo("COMMIT_ORDER");
  }

  @Test
  void exactAllOwnerSettlementUnblocksButOneOrMixedReadbackDoesNot() {
    for (Outcome outcome : Outcome.values()) {
      Context c = context();
      var binding = binding(c);
      c.tx(
          () -> {
            c.fences.reserve(binding);
            c.fences.claimCommitOrder(binding);
            return null;
          });
      terminal(c, binding, Owner.WORLD, outcome);
      assertThatThrownBy(() -> changePassword(c, binding.actorAccountId(), "test-only-blocked"))
          .isInstanceOf(DataAccessException.class);
      terminal(c, binding, Owner.GAME_DESIGN, outcome);
      changePassword(c, binding.actorAccountId(), "test-only-settled-change");
      var settlement =
          Objects.requireNonNull(
              c.dsl.fetchOne(
                  "SELECT account_draft_authorization_is_settled(?) AS settled",
                  binding.operationId()),
              "Exact owner settlement readback required");
      assertThat(settlement.get("settled", Boolean.class)).isTrue();
    }
    Context c = context();
    var binding = binding(c);
    c.tx(
        () -> {
          c.fences.reserve(binding);
          c.fences.claimCommitOrder(binding);
          return null;
        });
    terminal(c, binding, Owner.WORLD, Outcome.COMMITTED);
    terminal(c, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThatThrownBy(() -> changePassword(c, binding.actorAccountId(), "test-only-mixed-denied"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void sourceFirstContentionRollsBackRowsFirstProducerWithoutWaitingOrTerminalReceipt()
      throws Exception {
    Context c = context();
    var binding = binding(c);
    try (Connection writer = c.source.getConnection()) {
      writer.setAutoCommit(false);
      try (var statement =
          writer.prepareStatement(
              "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ? FOR UPDATE")) {
        statement.setString(1, binding.sources().getFirst().key());
        statement.executeQuery().close();
      }
      Instant started = Instant.now();
      assertThatThrownBy(
              () ->
                  c.tx(
                      () -> {
                        c.dsl.fetchOne(
                            "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE",
                            binding.actorAccountId());
                        c.fences.lockProducerSourcesNowait(binding.sources());
                        c.fences.reserve(binding);
                        return c.fences.claimCommitOrder(binding);
                      }))
          .isInstanceOf(DataAccessException.class);
      assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(2));
      assertThat(c.dsl.fetchCount(DSL.table("account_draft_authorization_fences"))).isZero();
      assertThat(c.dsl.fetchCount(DSL.table("account_draft_authorization_owner_readbacks")))
          .isZero();
      writer.rollback();
    }
    c.tx(
        () -> {
          c.fences.lockProducerSourcesNowait(binding.sources());
          c.fences.reserve(binding);
          return c.fences.claimCommitOrder(binding);
        });
    assertThatThrownBy(
            () -> changePassword(c, binding.actorAccountId(), "test-only-producer-first"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void waitingAndRevokeIntentCanStillBeRecordedWhileActualMutationStaysDenied() {
    Context c = context();
    var binding = binding(c);
    c.tx(() -> c.fences.reserve(binding));
    var intent =
        new DraftAuthorizationFenceRepository.SourceChange(
            UUID.randomUUID(), binding.sources(), new byte[] {11});
    assertThat(c.tx(() -> c.fences.requestSourceChange(intent))).isFalse();
    assertThat(c.tx(() -> c.fences.read(binding)).ordering())
        .isEqualTo(DraftAuthorizationFenceRepository.Ordering.REVOKE_ORDER);
    assertThatThrownBy(() -> changePassword(c, binding.actorAccountId(), "test-only-before-aborts"))
        .isInstanceOf(DataAccessException.class);
    terminal(c, binding, Owner.WORLD, Outcome.DEFINITIVELY_ABORTED);
    terminal(c, binding, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    assertThat(c.tx(() -> c.fences.sourceMutationPermitted(intent))).isTrue();
    changePassword(c, binding.actorAccountId(), "test-only-after-aborts");
  }

  private static void terminal(
      Context c, DraftAuthorizationFenceBinding binding, Owner owner, Outcome outcome) {
    c.tx(
        () -> {
          c.fences.recordOwnerReadback(
              binding,
              new OwnerReadback(
                  owner,
                  outcome,
                  binding.operationId(),
                  binding.commitId(),
                  binding.fenceId(),
                  binding.inputDigest(),
                  binding.canonicalBytes(),
                  new byte[] {7}));
          return null;
        });
  }

  private static void changePassword(Context c, UUID id, String hash) {
    c.tx(
        () -> {
          Account account = new AccountRepository(c.dsl).findByAccountUuid(id).orElseThrow();
          account.setPasswordHash(hash);
          return new AccountRepository(c.dsl).save(account);
        });
  }

  private static UUID account(Context c) {
    return c.tx(
        () -> {
          Account row = new Account();
          String suffix = UUID.randomUUID().toString();
          row.setUsername("test-" + suffix);
          row.setEmail(suffix + "@example.test");
          row.setPasswordHash("test-only-hash");
          return new AccountRepository(c.dsl).save(row).getAccountUuid();
        });
  }

  private static DraftAuthorizationFenceBinding binding(Context c) {
    UUID actor = account(c);
    var complete =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "test-only-tenant-key",
                2,
                "test-only-tenant-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-only/base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-only-world-payload")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "test-region",
                    "aggregate",
                    "test-region",
                    "0")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        actor,
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "0",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                actor.toString(),
                "1",
                "1",
                null,
                null,
                "test-only-source-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  }

  private static Context context() {
    String schema = "control_ui_guard_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(source, dsl, new DraftAuthorizationFenceRepository(dsl), transaction);
  }

  private record Context(
      DriverManagerDataSource source,
      DSLContext dsl,
      DraftAuthorizationFenceRepository fences,
      TransactionTemplate transaction) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }
  }
}
