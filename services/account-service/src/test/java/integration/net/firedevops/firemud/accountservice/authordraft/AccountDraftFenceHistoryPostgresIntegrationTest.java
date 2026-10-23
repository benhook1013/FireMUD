package integration.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Retained V75 storage only; source capture and World terminal authority are test stipulations. */
class AccountDraftFenceHistoryPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private static final List<String> TABLES =
      List.of(
          "account_draft_authorization_source_locks",
          "account_draft_authorization_fences",
          "account_draft_authorization_sources",
          "account_draft_authorization_owner_readbacks",
          "account_draft_authorization_source_changes",
          "account_draft_authorization_changed_scopes");

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void retainedV1OrderReadbackAndPendingChangeSurviveRequiredOwnerAndCurrentMigrations() {
    String schema = "account_fence_history_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source = POSTGRES.dataSource(schema);
    migrate(source, schema, "75");
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

    // V75 predates restriction birth. Let PostgreSQL generate the UUID and its real provenance;
    // current AccountRepository insertion must not be used to invent a historical birth receipt.
    UUID account = insertHistoricalAccount(dsl);
    DraftAuthorizationFenceBinding binding = binding(account);
    SourceEvidence legacyKey = binding.sources().get(1);
    assertThat(legacyKey.key().getBytes(StandardCharsets.UTF_8)).hasSize(512);
    var world =
        new OwnerReadback(
            Owner.WORLD,
            Outcome.COMMITTED,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            binding.canonicalBytes(),
            new byte[] {8});
    var change = new SourceChange(UUID.randomUUID(), binding.sources(), new byte[] {12});
    seedHistoricalState(dsl, transaction, binding, world, change);

    // Freeze the original columns so later additions cannot disguise a rewrite of retained data.
    Map<String, String> queries = originalRowQueries(dsl);
    Map<String, List<String>> rows = rows(dsl, queries);
    assertThat(rows.values()).allSatisfy(tableRows -> assertThat(tableRows).isNotEmpty());
    int rank75 = historyRank(dsl);
    List<String> history75 = history(dsl, rank75);
    migrate(source, schema, "76");
    assertThat(rows(dsl, queries)).isEqualTo(rows);
    assertThat(history(dsl, rank75)).isEqualTo(history75);
    assertRetained(dsl, transaction, binding, world, change, legacyKey);
    assertThat(rows(dsl, queries)).isEqualTo(rows);

    int rank76 = historyRank(dsl);
    List<String> history76 = history(dsl, rank76);
    Flyway current = migrate(source, schema, null);
    assertThat(current.info().pending()).isEmpty();
    assertThat(historyRank(dsl)).isGreaterThan(rank76);
    assertThat(history(dsl, rank76)).isEqualTo(history76);
    Map<String, List<String>> currentRows = rows(dsl, queries);
    rows.forEach(
        (table, originalRows) -> {
          if (table.equals("account_draft_authorization_source_locks")) {
            // V87 adds a serialization lock from the real Account scope, never authority evidence.
            assertThat(currentRows.get(table))
                .containsAll(originalRows)
                .hasSize(originalRows.size() + 1);
          } else {
            assertThat(currentRows.get(table)).isEqualTo(originalRows);
          }
        });
    assertThat(
            dsl.fetch("SELECT source_key FROM account_draft_authorization_source_locks")
                .getValues(0, String.class))
        .containsExactlyInAnyOrder(
            binding.sources().get(0).key(), legacyKey.key(), "GLOBAL_ROLES:" + account);
    assertRetained(dsl, transaction, binding, world, change, legacyKey);
    transaction.executeWithoutResult(
        ignored ->
            assertThat(new DraftAuthorizationFenceRepository(dsl).sourceMutationPermitted(change))
                .isFalse());
    assertThat(rows(dsl, queries)).isEqualTo(currentRows);
    assertHistoricalProvenance(dsl, account);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_draft_authorization_fences SET binding = ? WHERE operation_id = ?",
                    new byte[] {99},
                    binding.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM account_draft_authorization_owner_readbacks WHERE operation_id = ?",
                    binding.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThat(rows(dsl, queries)).isEqualTo(currentRows);
  }

  private static void assertRetained(
      DSLContext dsl,
      TransactionTemplate transaction,
      DraftAuthorizationFenceBinding binding,
      OwnerReadback world,
      SourceChange change,
      SourceEvidence legacyKey) {
    var repository = new DraftAuthorizationFenceRepository(dsl);
    transaction.executeWithoutResult(
        ignored -> {
          assertThat(
                  repository
                      .readOriginalBinding(binding.operationId())
                      .orElseThrow()
                      .canonicalBytes())
              .containsExactly(binding.canonicalBytes());
          assertThat(repository.reserve(binding).binding())
              .containsExactly(binding.canonicalBytes());
          assertThat(repository.read(binding).ordering()).isEqualTo(Ordering.COMMIT_ORDER);
          assertThat(repository.readOwnerResult(binding, Owner.WORLD).orElseThrow().readback())
              .containsExactly(world.canonicalBytes());
          assertThat(repository.readOwnerResult(binding, Owner.GAME_DESIGN)).isEmpty();
          assertThat(repository.readSettlement(binding)).isEqualTo(Settlement.PENDING);
          assertThat(repository.readSourceChange(change).status()).isEqualTo("WAITING");
        });
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ?",
                        legacyKey.key()))
                .get(0, String.class))
        .isEqualTo(legacyKey.key());
  }

  private static void seedHistoricalState(
      DSLContext dsl,
      TransactionTemplate transaction,
      DraftAuthorizationFenceBinding binding,
      OwnerReadback world,
      SourceChange change) {
    // Preserve V75's actual guarded insert/order lifecycle. Current source-change APIs also
    // inspect later publication owners, which do not exist in this predecessor schema.
    transaction.executeWithoutResult(
        ignored -> {
          for (SourceEvidence evidence : binding.sources()) {
            dsl.execute(
                "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?) ON CONFLICT DO NOTHING",
                evidence.key());
          }
          dsl.execute(
              "INSERT INTO account_draft_authorization_fences (operation_id, request_id, commit_id, fence_id, binding, ordering) VALUES (?, ?, ?, ?, ?, 'RESERVED')",
              binding.operationId(),
              binding.requestId(),
              binding.commitId(),
              binding.fenceId(),
              binding.canonicalBytes());
          for (SourceEvidence evidence : binding.sources()) {
            dsl.execute(
                "INSERT INTO account_draft_authorization_sources (operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
                binding.operationId(),
                evidence.key(),
                evidence.canonicalBytes());
          }
          dsl.execute(
              "UPDATE account_draft_authorization_fences SET ordering = 'COMMIT_ORDER', ordered_at = CURRENT_TIMESTAMP WHERE operation_id = ?",
              binding.operationId());
          dsl.execute(
              "INSERT INTO account_draft_authorization_owner_readbacks (operation_id, owner, outcome, readback) VALUES (?, 'WORLD', 'COMMITTED', ?)",
              binding.operationId(),
              world.canonicalBytes());
          dsl.execute(
              "INSERT INTO account_draft_authorization_source_changes (change_id, binding, status) VALUES (?, ?, 'WAITING')",
              change.changeId(),
              change.canonicalBytes());
          for (SourceEvidence evidence : change.sources()) {
            dsl.execute(
                "INSERT INTO account_draft_authorization_changed_scopes (change_id, source_key) VALUES (?, ?)",
                change.changeId(),
                evidence.key());
          }
        });
  }

  private static UUID insertHistoricalAccount(DSLContext dsl) {
    UUID suffix = UUID.randomUUID();
    Record row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO accounts (username, email, password_hash, role, email_verified, "
                    + "login_auth_modes, lifecycle_state) VALUES (?, ?, 'historical-test-verifier', "
                    + "'player', FALSE, 'PASSWORD,EMAIL_OTP', 'active') RETURNING account_uuid",
                "fence-history-" + suffix,
                suffix + "@example.test"));
    UUID account = Objects.requireNonNull(row.get(0, UUID.class));
    assertThat(account.version()).isEqualTo(4);
    assertHistoricalProvenance(dsl, account);
    return account;
  }

  private static void assertHistoricalProvenance(DSLContext dsl, UUID account) {
    Record row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id, account_uuid_provenance, account_uuid_source_numeric_id, "
                    + "account_repository_insert_transaction_id FROM accounts WHERE account_uuid = ?",
                account));
    assertThat(row.get("id", Long.class)).isPositive();
    assertThat(row.get("account_uuid_provenance", String.class))
        .isEqualTo("ACCOUNT_DATABASE_INSERT");
    assertThat(row.get("account_uuid_source_numeric_id", Long.class))
        .isEqualTo(row.get("id", Long.class));
    assertThat(row.get("account_repository_insert_transaction_id", Long.class)).isNull();
  }

  private static DraftAuthorizationFenceBinding binding(UUID account) {
    var complete =
        DraftCommitBinding.create(
            new TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "tenant-key",
                2,
                "tenant-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "historical/base:9007199254740993",
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "historical-world-payload")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "9007199254740999")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        account,
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "2",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                account.toString(),
                "3",
                "5",
                "stream/" + account,
                "2",
                new byte[] {7}),
            new SourceEvidence(
                SourceKind.ISSUER,
                "x".repeat(505),
                "3",
                "5",
                "stream/legacy512",
                "2",
                new byte[] {9})));
  }

  private static Map<String, String> originalRowQueries(DSLContext dsl) {
    Map<String, String> queries = new LinkedHashMap<>();
    for (String table : TABLES) {
      List<String> columns =
          dsl.fetch(
                  "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position",
                  table)
              .getValues(0, String.class);
      assertThat(columns).isNotEmpty();
      String projection =
          String.join(",", columns.stream().map(name -> "\"" + name + "\"").toList());
      queries.put(
          table,
          "SELECT to_jsonb(original)::text FROM (SELECT "
              + projection
              + ",xmin::text AS row_xmin FROM "
              + table
              + ") original ORDER BY to_jsonb(original)::text");
    }
    return queries;
  }

  private static Map<String, List<String>> rows(DSLContext dsl, Map<String, String> queries) {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    queries.forEach((table, query) -> rows.put(table, dsl.fetch(query).getValues(0, String.class)));
    return rows;
  }

  private static int historyRank(DSLContext dsl) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT max(installed_rank) FROM flyway_schema_history"))
        .get(0, Integer.class);
  }

  private static List<String> history(DSLContext dsl, int rank) {
    return dsl.fetch(
            "SELECT to_jsonb(h)::text FROM flyway_schema_history h WHERE installed_rank <= ? ORDER BY installed_rank",
            rank)
        .getValues(0, String.class);
  }

  private static Flyway migrate(DriverManagerDataSource source, String schema, String target) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isRegularFile(root.resolve("gradlew"))) root = root.getParent();
    root = Objects.requireNonNull(root, "Repository filesystem migrations required");
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(
                "filesystem:"
                    + root.resolve("services/account-service/src/main/resources/db/migration"),
                "filesystem:"
                    + root.resolve("services/common-saga/src/main/resources/db/migration/saga"));
    if (target != null) configuration.target(target);
    Flyway flyway = configuration.load();
    flyway.migrate();
    return flyway;
  }
}
