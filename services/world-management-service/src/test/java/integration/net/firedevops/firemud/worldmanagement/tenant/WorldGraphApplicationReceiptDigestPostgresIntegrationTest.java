package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.HISTORY;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.flyway;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.repositoryRoot;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.sagaMigrations;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.worldMigrations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the forward semantic receipt-digest repair against the installed World guards. The
 * positive APPLIED and receipt rows use controlled table clones with the original functions and
 * predicates; they prove SQL predicate behavior only and do not claim genuine Account authority or
 * production APPLIED creation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldGraphApplicationReceiptDigestPostgresIntegrationTest {
  private static final String GRAPH_GUARD = "world_guard_graph_application";
  private static final String RECEIPT_GUARD = "world_guard_draft_start_location_receipt";
  private static final String INVALID_DIGEST_PREFIX = "sha256:";
  private static final AtomicLong NEXT_PRIVATE_KEY_BASE = new AtomicLong(71002);
  private static final List<String> PROBE_TABLES =
      List.of(
          "world_instance",
          "initial_admission_bind_hold",
          "world_topology_draft_commit",
          "world_design_publication_fence_owner",
          "world_draft_terminal_outcome",
          "world_authored_topology_identity",
          "world_draft_start_location_receipt",
          "world_draft_graph_application",
          "world_design_aggregate_epoch",
          "world_design_scope_epoch",
          "region",
          "zone",
          "room",
          "room_exit",
          "generation_rule",
          "world_entity_spawn_binding");
  private static final List<String> PROBE_FUNCTIONS =
      List.of(
          GRAPH_GUARD,
          RECEIPT_GUARD,
          "world_reject_authored_source_history_mutation",
          "world_reject_draft_start_location_receipt_mutation");
  private static final List<String> FAMILY_NAMES =
      List.of(
          "REGION", "ZONE", "ROOM", "ROOM_EXIT", "GENERATION_RULE", "WORLD_ENTITY_SPAWN_BINDING");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void forwardMigrationBindsSemanticReceiptDigestOnFreshAndUpgradedSchemas() throws Exception {
    Path root = repositoryRoot();
    String worldLocation = worldMigrations(root);
    String sagaLocation = sagaMigrations(root);
    String upgradeSchema = schemaName("world_digest_upgrade_");
    String freshSchema = schemaName("world_digest_fresh_");
    String probeSchema = schemaName("world_digest_probe_");
    DriverManagerDataSource upgradeSource = source(upgradeSchema);
    DriverManagerDataSource freshSource = source(freshSchema);
    DriverManagerDataSource probeSource = source(probeSchema);
    DSLContext upgradeDsl = DSL.using(upgradeSource, SQLDialect.POSTGRES);
    DSLContext freshDsl = DSL.using(freshSource, SQLDialect.POSTGRES);
    DSLContext probeDsl = DSL.using(probeSource, SQLDialect.POSTGRES);

    try {
      Flyway through104211 =
          flyway(
              upgradeSource,
              upgradeSchema,
              MigrationVersion.fromVersion("1042.11"),
              worldLocation,
              sagaLocation);
      through104211.migrate();
      through104211.validate();
      assertThat(latestVersion(upgradeDsl)).isEqualTo("1042.11");

      String history1012Before = migrationHistory(upgradeDsl, "1012");
      String history1014Before = migrationHistory(upgradeDsl, "1014");
      String history104211Before = migrationHistory(upgradeDsl, "1042.11");

      cloneProbeTables(upgradeDsl, upgradeSchema, probeSchema);
      copyInstalledFunctions(upgradeDsl, upgradeSchema, probeSchema);
      installProbeTriggers(probeDsl, probeSchema);

      Fixture retainedFixture = fixture();
      persistFixture(probeDsl, probeSchema, retainedFixture, Corruption.NONE);

      Flyway probeThrough104212 =
          Flyway.configure()
              .dataSource(probeSource)
              .schemas(probeSchema)
              .defaultSchema(probeSchema)
              .table(HISTORY)
              .baselineOnMigrate(true)
              .baselineVersion(MigrationVersion.fromVersion("1042.11"))
              .target(MigrationVersion.fromVersion("1042.12"))
              .placeholders(Map.of("serviceSchema", probeSchema))
              .locations(worldLocation, sagaLocation)
              .load();
      probeThrough104212.migrate();
      probeThrough104212.validate();
      assertThat(latestVersion(probeDsl)).isEqualTo("1042.12");

      FunctionMetadata probeGraphBefore = functionMetadata(probeDsl, probeSchema, GRAPH_GUARD);
      FunctionMetadata probeReceiptBefore = functionMetadata(probeDsl, probeSchema, RECEIPT_GUARD);
      List<String> probeGraphTriggersBefore =
          triggerMetadata(probeDsl, probeSchema, "world_draft_graph_application");
      List<String> probeReceiptTriggersBefore =
          triggerMetadata(probeDsl, probeSchema, "world_draft_start_location_receipt");
      List<String> probeApplicationRowsBefore = applicationRows(probeDsl, probeSchema);
      List<String> probeReceiptRowsBefore = receiptRows(probeDsl, probeSchema);
      assertThat(probeApplicationRowsBefore).hasSize(1);
      assertThat(probeReceiptRowsBefore).hasSize(1);
      assertProtectedGuardMetadata(
          probeGraphBefore,
          probeReceiptBefore,
          probeGraphTriggersBefore,
          probeReceiptTriggersBefore);

      Flyway through104212 =
          flyway(
              upgradeSource,
              upgradeSchema,
              MigrationVersion.fromVersion("1042.12"),
              worldLocation,
              sagaLocation);
      through104212.migrate();
      through104212.validate();
      assertThat(latestVersion(upgradeDsl)).isEqualTo("1042.12");

      FunctionMetadata graphBefore = functionMetadata(upgradeDsl, upgradeSchema, GRAPH_GUARD);
      FunctionMetadata receiptBefore = functionMetadata(upgradeDsl, upgradeSchema, RECEIPT_GUARD);
      List<String> graphTriggersBefore =
          triggerMetadata(upgradeDsl, upgradeSchema, "world_draft_graph_application");
      List<String> receiptTriggersBefore =
          triggerMetadata(upgradeDsl, upgradeSchema, "world_draft_start_location_receipt");
      String history104212Before = migrationHistory(upgradeDsl, "1042.12");
      List<String> applicationRowsBefore = applicationRows(upgradeDsl, upgradeSchema);
      List<String> receiptRowsBefore = receiptRows(upgradeDsl, upgradeSchema);
      assertThat(applicationRowsBefore).isEmpty();
      assertThat(receiptRowsBefore).isEmpty();
      assertProtectedGuardMetadata(
          graphBefore, receiptBefore, graphTriggersBefore, receiptTriggersBefore);

      Flyway current = flyway(upgradeSource, upgradeSchema, null, worldLocation, sagaLocation);
      current.migrate();
      current.validate();
      assertThat(current.info().pending()).isEmpty();

      Flyway fresh = flyway(freshSource, freshSchema, null, worldLocation, sagaLocation);
      fresh.migrate();
      fresh.validate();
      assertThat(fresh.info().pending()).isEmpty();

      Flyway probeCurrent = flyway(probeSource, probeSchema, null, worldLocation, sagaLocation);
      probeCurrent.migrate();
      probeCurrent.validate();
      assertThat(probeCurrent.info().pending()).isEmpty();

      assertThat(latestVersion(upgradeDsl)).isEqualTo("1044");
      assertThat(latestVersion(freshDsl)).isEqualTo(latestVersion(upgradeDsl));
      assertThat(latestVersion(probeDsl)).isEqualTo(latestVersion(upgradeDsl));
      assertThat(migrationHistory(upgradeDsl, "1012")).isEqualTo(history1012Before);
      assertThat(migrationHistory(upgradeDsl, "1014")).isEqualTo(history1014Before);
      assertThat(migrationHistory(upgradeDsl, "1042.11")).isEqualTo(history104211Before);
      assertThat(migrationHistory(upgradeDsl, "1042.12")).isEqualTo(history104212Before);
      assertThat(migrationHistory(upgradeDsl, "1042.13")).isNotNull();
      assertThat(migrationHistory(upgradeDsl, "1044")).isNotNull();
      assertThat(migrationHistory(freshDsl, "1042.12")).isNotNull();
      assertThat(migrationHistory(freshDsl, "1042.13")).isNotNull();
      assertThat(migrationHistory(freshDsl, "1044")).isNotNull();
      assertThat(migrationHistory(probeDsl, "1044")).isNotNull();
      assertCanonicalInitialAdmissionHoldLifecycleGuard(upgradeDsl, upgradeSchema);
      assertCanonicalInitialAdmissionHoldLifecycleGuard(freshDsl, freshSchema);
      assertCanonicalInitialAdmissionHoldLifecycleGuard(probeDsl, probeSchema);
      assertThat(applicationRows(upgradeDsl, upgradeSchema)).isEqualTo(applicationRowsBefore);
      assertThat(receiptRows(upgradeDsl, upgradeSchema)).isEqualTo(receiptRowsBefore);

      FunctionMetadata graphAfter = functionMetadata(upgradeDsl, upgradeSchema, GRAPH_GUARD);
      FunctionMetadata receiptAfter = functionMetadata(upgradeDsl, upgradeSchema, RECEIPT_GUARD);
      assertFunctionPreservedExceptBody(graphBefore, graphAfter);
      assertFunctionPreservedExceptBody(receiptBefore, receiptAfter);
      assertThat(triggerMetadata(upgradeDsl, upgradeSchema, "world_draft_graph_application"))
          .isEqualTo(graphTriggersBefore);
      assertThat(triggerMetadata(upgradeDsl, upgradeSchema, "world_draft_start_location_receipt"))
          .isEqualTo(receiptTriggersBefore);
      assertDigestPredicates(graphAfter, receiptAfter);

      FunctionMetadata probeGraphAfter = functionMetadata(probeDsl, probeSchema, GRAPH_GUARD);
      FunctionMetadata probeReceiptAfter = functionMetadata(probeDsl, probeSchema, RECEIPT_GUARD);
      assertFunctionPreservedExceptBody(probeGraphBefore, probeGraphAfter);
      assertFunctionPreservedExceptBody(probeReceiptBefore, probeReceiptAfter);
      assertThat(triggerMetadata(probeDsl, probeSchema, "world_draft_graph_application"))
          .isEqualTo(probeGraphTriggersBefore);
      assertThat(triggerMetadata(probeDsl, probeSchema, "world_draft_start_location_receipt"))
          .isEqualTo(probeReceiptTriggersBefore);
      assertThat(applicationRows(probeDsl, probeSchema)).isEqualTo(probeApplicationRowsBefore);
      assertThat(receiptRows(probeDsl, probeSchema)).isEqualTo(probeReceiptRowsBefore);
      assertDigestPredicates(probeGraphAfter, probeReceiptAfter);

      FunctionMetadata freshGraph = functionMetadata(freshDsl, freshSchema, GRAPH_GUARD);
      FunctionMetadata freshReceipt = functionMetadata(freshDsl, freshSchema, RECEIPT_GUARD);
      assertProtectedGuardMetadata(
          freshGraph,
          freshReceipt,
          triggerMetadata(freshDsl, freshSchema, "world_draft_graph_application"),
          triggerMetadata(freshDsl, freshSchema, "world_draft_start_location_receipt"));
      assertDigestPredicates(freshGraph, freshReceipt);
      assertThat(normalizeSchema(graphAfter.source(), upgradeSchema))
          .isEqualTo(normalizeSchema(freshGraph.source(), freshSchema));
      assertThat(normalizeSchema(receiptAfter.source(), upgradeSchema))
          .isEqualTo(normalizeSchema(freshReceipt.source(), freshSchema));

      persistFixture(probeDsl, probeSchema, fixture(), Corruption.NONE);
      assertSqlState(
          () -> persistFixture(probeDsl, probeSchema, fixture(), Corruption.RECEIPT_ACCOUNT_DIGEST),
          "23514");
      assertSqlState(
          () -> persistFixture(probeDsl, probeSchema, fixture(), Corruption.RECEIPT_DIGEST),
          "23514");
      assertSqlState(
          () -> persistFixture(probeDsl, probeSchema, fixture(), Corruption.RECEIPT_FIELD_MISMATCH),
          "23514");
      assertSqlState(
          () ->
              persistFixture(
                  probeDsl, probeSchema, fixture(), Corruption.APPLICATION_ACCOUNT_DIGEST),
          "23514");
      assertSqlState(
          () -> persistFixture(probeDsl, probeSchema, fixture(), Corruption.RESULT_DIGEST),
          "23514");
      assertSqlState(
          () ->
              persistFixture(
                  probeDsl, probeSchema, fixture(), Corruption.APPLICATION_RECEIPT_FIELD_MISMATCH),
          "23514");
    } finally {
      dropSchema(upgradeSource, upgradeSchema);
      dropSchema(freshSource, freshSchema);
      dropSchema(probeSource, probeSchema);
    }
  }

  private static void assertProtectedGuardMetadata(
      FunctionMetadata graph,
      FunctionMetadata receipt,
      List<String> graphTriggers,
      List<String> receiptTriggers) {
    assertThat(graph.securityDefiner()).isTrue();
    assertThat(receipt.securityDefiner()).isTrue();
    assertThat(graph.configuration()).contains("search_path=pg_catalog");
    assertThat(receipt.configuration()).contains("search_path=pg_catalog");
    assertThat(graph.publicExecuteGrant()).isFalse();
    assertThat(receipt.publicExecuteGrant()).isFalse();
    assertThat(graphTriggers)
        .anyMatch(value -> value.contains("trg_world_graph_application_guard"));
    assertThat(receiptTriggers)
        .anyMatch(value -> value.contains("trg_world_start_location_receipt_guard"));
  }

  private static void assertFunctionPreservedExceptBody(
      FunctionMetadata before, FunctionMetadata after) {
    assertThat(after.oid()).isEqualTo(before.oid());
    assertThat(after.owner()).isEqualTo(before.owner());
    assertThat(after.acl()).isEqualTo(before.acl());
    assertThat(after.publicExecuteGrant()).isEqualTo(before.publicExecuteGrant());
    assertThat(after.securityDefiner()).isEqualTo(before.securityDefiner());
    assertThat(after.configuration()).isEqualTo(before.configuration());
  }

  private static void assertDigestPredicates(FunctionMetadata graph, FunctionMetadata receipt) {
    assertThat(graph.source())
        .contains("encode(sha256(NEW.account_binding_bytes), 'hex')")
        .contains("encode(sha256(NEW.result_bytes), 'hex')")
        .contains("encode(sha256(receipt.account_binding_bytes), 'hex')")
        .contains("world_start_location_receipt_matches(")
        .doesNotContain("encode(sha256(receipt.receipt_bytes), 'hex')");
    assertThat(receipt.source())
        .contains("encode(sha256(NEW.account_binding_bytes), 'hex')")
        .contains("world_start_location_receipt_matches(")
        .doesNotContain("encode(sha256(NEW.receipt_bytes), 'hex')");
  }

  private static void assertCanonicalInitialAdmissionHoldLifecycleGuard(
      DSLContext dsl, String schema) {
    assertThat(triggerMetadata(dsl, schema, "world_instance"))
        .anySatisfy(
            trigger -> {
              assertThat(trigger)
                  .contains("aaa_world_canonical_initial_admission_hold_lifecycle")
                  .contains("world_guard_canonical_initial_admission_hold_lifecycle");
              assertThat(trigger.split(":", 8)[4]).isIn("O", "A");
            });
  }

  private static void cloneProbeTables(DSLContext dsl, String sourceSchema, String probeSchema) {
    dsl.execute("CREATE SCHEMA " + DSL.name(probeSchema));
    for (String table : PROBE_TABLES)
      dsl.execute(
          "CREATE TABLE "
              + DSL.name(probeSchema, table)
              + " AS TABLE "
              + DSL.name(sourceSchema, table)
              + " WITH NO DATA");
  }

  private static void copyInstalledFunctions(
      DSLContext dsl, String sourceSchema, String probeSchema) {
    for (String function : PROBE_FUNCTIONS) {
      String definition =
          dsl.fetchSingle(
                  "SELECT pg_get_functiondef(to_regprocedure(?))",
                  sourceSchema + "." + function + "()")
              .get(0, String.class);
      definition =
          definition
              .replace("\"" + sourceSchema + "\"", "\"" + probeSchema + "\"")
              .replace(sourceSchema + ".", probeSchema + ".")
              .replace("'" + sourceSchema + "'", "'" + probeSchema + "'");
      dsl.execute(definition);
      // Function definitions do not carry ACLs into this controlled clone.
      dsl.execute("REVOKE ALL ON FUNCTION " + DSL.name(probeSchema, function) + "() FROM PUBLIC");
    }
  }

  private static void installProbeTriggers(DSLContext dsl, String schema) {
    dsl.execute(
        "CREATE TRIGGER trg_world_graph_application_guard BEFORE INSERT OR UPDATE OR DELETE ON "
            + DSL.name(schema, "world_draft_graph_application")
            + " FOR EACH ROW EXECUTE FUNCTION "
            + DSL.name(schema, GRAPH_GUARD)
            + "()");
    dsl.execute(
        "CREATE TRIGGER trg_world_graph_application_no_truncate BEFORE TRUNCATE ON "
            + DSL.name(schema, "world_draft_graph_application")
            + " FOR EACH STATEMENT EXECUTE FUNCTION "
            + DSL.name(schema, "world_reject_authored_source_history_mutation")
            + "()");
    dsl.execute(
        "CREATE TRIGGER trg_world_start_location_receipt_guard BEFORE INSERT ON "
            + DSL.name(schema, "world_draft_start_location_receipt")
            + " FOR EACH ROW EXECUTE FUNCTION "
            + DSL.name(schema, RECEIPT_GUARD)
            + "()");
    dsl.execute(
        "CREATE TRIGGER trg_world_start_location_receipt_immutable BEFORE UPDATE OR DELETE ON "
            + DSL.name(schema, "world_draft_start_location_receipt")
            + " FOR EACH ROW EXECUTE FUNCTION "
            + DSL.name(schema, "world_reject_draft_start_location_receipt_mutation")
            + "()");
    dsl.execute(
        "CREATE TRIGGER trg_world_start_location_receipt_no_truncate BEFORE TRUNCATE ON "
            + DSL.name(schema, "world_draft_start_location_receipt")
            + " FOR EACH STATEMENT EXECUTE FUNCTION "
            + DSL.name(schema, "world_reject_draft_start_location_receipt_mutation")
            + "()");
  }

  private static void persistFixture(
      DSLContext dsl, String schema, Fixture fixture, Corruption corruption) throws Exception {
    boolean receiptFieldMismatch =
        corruption == Corruption.RECEIPT_FIELD_MISMATCH
            || corruption == Corruption.APPLICATION_RECEIPT_FIELD_MISMATCH;
    UUID evidenceFenceId =
        receiptFieldMismatch ? UUID.randomUUID() : fixture.authorizationFenceId();
    WorldDraftStartLocationEvidence receiptEvidence = receiptEvidence(fixture, evidenceFenceId);
    byte[] receiptBytes = receiptEvidence.canonicalBytes();
    String claimedReceiptDigest = receiptEvidence.receiptDigest();
    String receiptDigest =
        corruption == Corruption.RECEIPT_DIGEST
            ? wrongDigest(claimedReceiptDigest)
            : claimedReceiptDigest;
    byte[] resultBytes = resultBytes(fixture, receiptBytes, receiptDigest);
    String correctResultDigest = digest(resultBytes);

    dsl.transaction(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          String commitTable = DSL.name(schema, "world_topology_draft_commit").toString();
          String ownerTable = DSL.name(schema, "world_design_publication_fence_owner").toString();
          String roomTable = DSL.name(schema, "room").toString();
          String identityTable = DSL.name(schema, "world_authored_topology_identity").toString();
          String receiptTable = DSL.name(schema, "world_draft_start_location_receipt").toString();
          String applicationTable = DSL.name(schema, "world_draft_graph_application").toString();

          tx.execute(
              "INSERT INTO "
                  + commitTable
                  + " (request_id, commit_id, target_namespace, canonical_tenant_id, "
                  + "canonical_version_id, local_tenant_key, local_version_key, binding_json, "
                  + "binding_digest, graph_bytes, graph_sha256, application_transaction_id) "
                  + "VALUES (?, ?, 'dev', ?, ?, ?, ?, ?::jsonb, ?, ?, ?, txid_current())",
              fixture.requestId(),
              fixture.commitId(),
              fixture.tenantId(),
              fixture.versionId(),
              fixture.localTenantKey(),
              fixture.localVersionKey(),
              fixture.bindingJson(),
              fixture.bindingDigest(),
              fixture.graphBytes(),
              fixture.graphSha256());
          tx.execute(
              "INSERT INTO "
                  + ownerTable
                  + " (target_namespace, canonical_tenant_id, local_tenant_key, version_id, "
                  + "owner_freeze_phase, current_publication_fence) "
                  + "VALUES ('dev', ?, ?, ?, 'OPEN', NULL)",
              fixture.tenantId(),
              fixture.localTenantKey(),
              fixture.localVersionKey());
          tx.execute(
              "INSERT INTO " + roomTable + " (id, tenant_id, version_id) VALUES (?, ?, ?)",
              fixture.roomRowId(),
              fixture.localTenantKey(),
              fixture.localVersionKey());
          tx.execute(
              "INSERT INTO "
                  + identityTable
                  + " (target_namespace, canonical_tenant_id, canonical_version_id, family, "
                  + "template_id, private_row_key, tenant_id, version_id, "
                  + "version_identity_operation_id, request_id, commit_id, revision_id, revision_order) "
                  + "VALUES ('dev', ?, ?, 'ROOM', ?, ?, ?, ?, ?, ?, ?, ?, '0')",
              fixture.tenantId(),
              fixture.versionId(),
              fixture.roomTemplateId(),
              fixture.roomRowId(),
              fixture.localTenantKey(),
              fixture.localVersionKey(),
              fixture.versionIdentityOperationId(),
              fixture.requestId(),
              fixture.commitId(),
              fixture.revisionId());

          String receiptAccountDigest =
              corruption == Corruption.RECEIPT_ACCOUNT_DIGEST
                  ? wrongDigest(fixture.accountBindingDigest())
                  : fixture.accountBindingDigest();
          boolean bypassReceiptGuard = corruption == Corruption.APPLICATION_RECEIPT_FIELD_MISMATCH;
          if (bypassReceiptGuard) {
            tx.execute(
                "ALTER TABLE "
                    + receiptTable
                    + " DISABLE TRIGGER trg_world_start_location_receipt_guard");
          }
          tx.execute(
              "INSERT INTO "
                  + receiptTable
                  + " (operation_id, request_id, commit_id, authorization_fence_id, "
                  + "target_namespace, account_binding_bytes, account_binding_digest, binding_digest, "
                  + "canonical_tenant_id, canonical_version_id, room_template_id, graph_digest, "
                  + "receipt_digest, receipt_bytes) "
                  + "VALUES (?, ?, ?, ?, 'dev', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              fixture.operationId(),
              fixture.requestId(),
              fixture.commitId(),
              fixture.authorizationFenceId(),
              fixture.accountBindingBytes(),
              receiptAccountDigest,
              fixture.bindingDigest(),
              fixture.tenantId(),
              fixture.versionId(),
              fixture.roomTemplateId(),
              fixture.graphDigest(),
              receiptDigest,
              receiptBytes);
          if (bypassReceiptGuard) {
            tx.execute(
                "ALTER TABLE "
                    + receiptTable
                    + " ENABLE TRIGGER trg_world_start_location_receipt_guard");
          }

          String applicationAccountDigest =
              corruption == Corruption.APPLICATION_ACCOUNT_DIGEST
                  ? wrongDigest(fixture.accountBindingDigest())
                  : fixture.accountBindingDigest();
          String resultDigest =
              corruption == Corruption.RESULT_DIGEST
                  ? wrongDigest(correctResultDigest)
                  : correctResultDigest;
          tx.execute(
              "INSERT INTO "
                  + applicationTable
                  + " (operation_id, request_id, commit_id, authorization_fence_id, "
                  + "operation_bytes, account_binding_bytes, account_binding_digest, result_bytes, "
                  + "result_digest, application_transaction_id) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, txid_current())",
              fixture.operationId(),
              fixture.requestId(),
              fixture.commitId(),
              fixture.authorizationFenceId(),
              fixture.operationBytes(),
              fixture.accountBindingBytes(),
              applicationAccountDigest,
              resultBytes,
              resultDigest);
        });
  }

  private static Fixture fixture() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID commitId = UUID.randomUUID();
    UUID authorizationFenceId = UUID.randomUUID();
    UUID versionIdentityOperationId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID roomTemplateId = UUID.randomUUID();
    byte[] accountBindingBytes =
        "{\"accountBinding\":\"controlled\"}".getBytes(StandardCharsets.UTF_8);
    byte[] operationBytes = "{\"operation\":\"controlled\"}".getBytes(StandardCharsets.UTF_8);
    byte[] graphBytes =
        "{\"schemaVersion\":3,\"rows\":[{\"family\":\"ROOM\"}]}".getBytes(StandardCharsets.UTF_8);
    String accountBindingDigest = digest(accountBindingBytes);
    String graphDigest = digest(graphBytes);
    String bindingDigest = "sha256:" + "b".repeat(64);
    String bindingJson = bindingJson(tenantId, versionId, roomTemplateId);
    WorldDraftStartLocationEvidence evidence =
        WorldDraftStartLocationEvidence.create(
            "dev",
            operationId,
            requestId,
            commitId,
            authorizationFenceId,
            accountBindingDigest,
            bindingDigest,
            new RoomTemplateRef(tenantId, versionId, roomTemplateId),
            graphDigest);
    byte[] receiptBytes = evidence.canonicalBytes();
    String receiptDigest = evidence.receiptDigest();
    long privateKeyBase = NEXT_PRIVATE_KEY_BASE.getAndAdd(10);
    byte[] resultBytes =
        resultBytes(graphDigest, receiptBytes, receiptDigest, operationBytes, graphBytes);
    return new Fixture(
        tenantId,
        versionId,
        operationId,
        requestId,
        commitId,
        authorizationFenceId,
        versionIdentityOperationId,
        revisionId,
        roomTemplateId,
        privateKeyBase,
        privateKeyBase + 4,
        privateKeyBase + 5,
        accountBindingBytes,
        accountBindingDigest,
        receiptBytes,
        receiptDigest,
        operationBytes,
        graphBytes,
        graphDigest.substring("sha256:".length()),
        graphDigest,
        bindingDigest,
        bindingJson,
        resultBytes,
        digest(resultBytes));
  }

  private static WorldDraftStartLocationEvidence receiptEvidence(Fixture fixture, UUID fenceId) {
    return WorldDraftStartLocationEvidence.create(
        "dev",
        fixture.operationId(),
        fixture.requestId(),
        fixture.commitId(),
        fenceId,
        fixture.accountBindingDigest(),
        fixture.bindingDigest(),
        new RoomTemplateRef(fixture.tenantId(), fixture.versionId(), fixture.roomTemplateId()),
        fixture.graphDigest());
  }

  private static byte[] resultBytes(Fixture fixture, byte[] receiptBytes, String receiptDigest) {
    return resultBytes(
        fixture.graphDigest(),
        receiptBytes,
        receiptDigest,
        fixture.operationBytes(),
        fixture.graphBytes());
  }

  private static byte[] resultBytes(
      String graphDigest,
      byte[] receiptBytes,
      String receiptDigest,
      byte[] operationBytes,
      byte[] graphBytes) {
    String resultJson =
        "{\"schema\":\"world-draft-graph-applied/v2\",\"status\":\"APPLIED\","
            + "\"graphDigest\":\""
            + graphDigest
            + "\",\"startLocationReceiptDigest\":\""
            + receiptDigest
            + "\",\"startLocationReceiptBase64\":\""
            + Base64.getEncoder().encodeToString(receiptBytes)
            + "\",\"operationBytesBase64\":\""
            + Base64.getEncoder().encodeToString(operationBytes)
            + "\",\"graphBytesBase64\":\""
            + Base64.getEncoder().encodeToString(graphBytes)
            + "\"}";
    return resultJson.getBytes(StandardCharsets.UTF_8);
  }

  private static String bindingJson(UUID tenantId, UUID versionId, UUID roomTemplateId) {
    String familyCounts =
        FAMILY_NAMES.stream()
            .map(
                family ->
                    "{\"family\":\"WORLD_DESIGN_AGGREGATE_TYPE_"
                        + family
                        + "\",\"count\":"
                        + (family.equals("ROOM") ? 1 : 0)
                        + "}")
            .reduce((left, right) -> left + "," + right)
            .orElseThrow();
    return "{\"revisions\":[{\"owner\":\"WORLD_MANAGEMENT\",\"payload\":{"
        + "\"freshGraphDeclaration\":{\"tenantId\":\""
        + tenantId
        + "\",\"versionId\":\""
        + versionId
        + "\",\"familyCounts\":["
        + familyCounts
        + "],\"startLocation\":{\"tenantId\":\""
        + tenantId
        + "\",\"versionId\":\""
        + versionId
        + "\",\"roomTemplateId\":\""
        + roomTemplateId
        + "\"}}}}],\"affectedUnits\":[]}";
  }

  private static FunctionMetadata functionMetadata(DSLContext dsl, String schema, String function) {
    var row =
        dsl.fetchSingle(
            "SELECT p.oid::bigint, pg_get_userbyid(p.proowner), COALESCE(p.proacl::text, ''), "
                + "EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(p.proacl, "
                + "pg_catalog.acldefault('f', p.proowner))) acl "
                + "WHERE acl.grantee=0::oid AND acl.privilege_type='EXECUTE'), "
                + "p.prosecdef, COALESCE(p.proconfig::text, ''), pg_get_functiondef(p.oid) "
                + "FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace "
                + "WHERE n.nspname=? AND p.proname=? "
                + "AND pg_get_function_identity_arguments(p.oid)=''",
            schema,
            function);
    return new FunctionMetadata(
        row.get(0, Long.class),
        row.get(1, String.class),
        row.get(2, String.class),
        row.get(3, Boolean.class),
        row.get(4, Boolean.class),
        row.get(5, String.class),
        row.get(6, String.class));
  }

  private static List<String> triggerMetadata(DSLContext dsl, String schema, String table) {
    return dsl.fetch(
            "SELECT t.tgname || ':' || t.oid::text || ':' || t.tgfoid::text || ':' "
                + "|| t.tgtype::text || ':' || t.tgenabled::text || ':' || t.tgdeferrable::text || ':' "
                + "|| t.tginitdeferred::text || ':' || pg_get_triggerdef(t.oid) "
                + "FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid "
                + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=? AND NOT t.tgisinternal ORDER BY t.tgname",
            schema,
            table)
        .getValues(0, String.class);
  }

  private static List<String> applicationRows(DSLContext dsl, String schema) {
    return dsl.fetch(
            "SELECT a.xmin::text || ':' || to_jsonb(a)::text FROM "
                + DSL.name(schema, "world_draft_graph_application")
                + " a ORDER BY operation_id")
        .getValues(0, String.class);
  }

  private static List<String> receiptRows(DSLContext dsl, String schema) {
    return dsl.fetch(
            "SELECT r.xmin::text || ':' || to_jsonb(r)::text FROM "
                + DSL.name(schema, "world_draft_start_location_receipt")
                + " r ORDER BY operation_id")
        .getValues(0, String.class);
  }

  private static String latestVersion(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT version FROM "
                + HISTORY
                + " WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
        .get(0, String.class);
  }

  private static String migrationHistory(DSLContext dsl, String version) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(h)::text FROM " + HISTORY + " h WHERE version=? AND success", version)
        .get(0, String.class);
  }

  private static String normalizeSchema(String source, String schema) {
    return source
        .replace("\"" + schema + "\"", "\"service_schema\"")
        .replace(schema + ".", "\"service_schema\".")
        .replace("'" + schema + "'", "'service_schema'");
  }

  private static String digest(byte[] bytes) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String wrongDigest(String digest) {
    String hex = digest.substring(INVALID_DIGEST_PREFIX.length());
    char replacement = hex.charAt(0) == '0' ? '1' : '0';
    return INVALID_DIGEST_PREFIX + replacement + hex.substring(1);
  }

  private static void assertSqlState(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state) {
    assertThatThrownBy(action)
        .satisfies(
            failure -> {
              Throwable cause = failure;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(PSQLException.class);
              assertThat(((PSQLException) cause).getSQLState()).isEqualTo(state);
            });
  }

  private static DriverManagerDataSource source(String schema) {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    return source;
  }

  private static void dropSchema(DriverManagerDataSource source, String schema)
      throws SQLException {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + DSL.name(schema) + " CASCADE");
    }
  }

  private static String schemaName(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  private enum Corruption {
    NONE,
    RECEIPT_ACCOUNT_DIGEST,
    RECEIPT_DIGEST,
    RECEIPT_FIELD_MISMATCH,
    APPLICATION_ACCOUNT_DIGEST,
    RESULT_DIGEST,
    APPLICATION_RECEIPT_FIELD_MISMATCH
  }

  private record FunctionMetadata(
      long oid,
      String owner,
      String acl,
      boolean publicExecuteGrant,
      boolean securityDefiner,
      String configuration,
      String source) {}

  private record Fixture(
      UUID tenantId,
      UUID versionId,
      UUID operationId,
      UUID requestId,
      UUID commitId,
      UUID authorizationFenceId,
      UUID versionIdentityOperationId,
      UUID revisionId,
      UUID roomTemplateId,
      long localTenantKey,
      long localVersionKey,
      long roomRowId,
      byte[] accountBindingBytes,
      String accountBindingDigest,
      byte[] receiptBytes,
      String receiptDigest,
      byte[] operationBytes,
      byte[] graphBytes,
      String graphSha256,
      String graphDigest,
      String bindingDigest,
      String bindingJson,
      byte[] resultBytes,
      String resultDigest) {}
}
