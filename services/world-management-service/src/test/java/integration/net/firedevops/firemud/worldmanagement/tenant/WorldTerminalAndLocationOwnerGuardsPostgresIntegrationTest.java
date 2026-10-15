package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.HISTORY;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.flyway;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.repositoryRoot;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.sagaMigrations;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.worldMigrations;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the V1042.6, V1042.8, and V1042.10 forward owner-guard repairs against native
 * PostgreSQL. Malformed terminal inputs run through test-local probes built from the installed
 * function body; they do not create production owner rows or claim Account-authorized admission.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldTerminalAndLocationOwnerGuardsPostgresIntegrationTest {
  private static final String BINDING_CONSTRAINT = "ck_world_draft_terminal_binding";
  private static final String LOCATION_FUNCTION = "world_require_initial_location_operation_result";
  private static final String LOCATION_TRIGGER =
      "trg_world_require_initial_location_operation_result";
  private static final String TERMINAL_FUNCTION = "world_draft_terminal_outcome_guard";
  private static final String TERMINAL_TRIGGER = "trg_world_draft_terminal_outcome_guard";
  private static final String LOCATION_DELETE_TRIGGER =
      "trg_world_reject_character_location_delete";
  private static final String LOCATION_TRUNCATE_TRIGGER =
      "trg_world_reject_character_location_truncate";
  private static final String LOCATION_RETENTION_FUNCTION =
      "world_reject_authored_source_history_mutation";
  private static final String OPERATION_TRUNCATE_TRIGGER =
      "trg_world_reject_initial_location_operation_truncate";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void defaultInstallAndForwardUpgradeEnforceTerminalUnitsAndOwnerGuards() throws Exception {
    Path root = repositoryRoot();
    String worldLocation = worldMigrations(root);
    String sagaLocation = sagaMigrations(root);
    String upgradeSchema = schemaName("world_guard_upgrade_");
    String freshSchema = schemaName("world_guard_fresh_");
    DriverManagerDataSource upgradeSource = source(upgradeSchema);
    DriverManagerDataSource freshSource = source(freshSchema);
    DSLContext upgradeDsl = DSL.using(upgradeSource, SQLDialect.POSTGRES);
    DSLContext freshDsl = DSL.using(freshSource, SQLDialect.POSTGRES);

    try (AutoCloseable freshSchemaCleanup = () -> dropSchema(freshSource, freshSchema);
        AutoCloseable upgradeSchemaCleanup = () -> dropSchema(upgradeSource, upgradeSchema)) {
      Flyway through10425 =
          flyway(
              upgradeSource,
              upgradeSchema,
              MigrationVersion.fromVersion("1042.5"),
              worldLocation,
              sagaLocation);
      through10425.migrate();
      through10425.validate();
      assertThat(latestVersion(upgradeDsl)).isEqualTo("1042.5");

      String oldTerminalConstraint = constraintDefinition(upgradeDsl, upgradeSchema);
      String oldTerminalHistory = migrationHistory(upgradeDsl, "1010");
      String oldLocationHistory = migrationHistory(upgradeDsl, "1019");
      LocationFunctionMetadata before = locationFunctionMetadata(upgradeDsl, upgradeSchema);
      assertThat(before.publicExecuteGrant()).isFalse();
      assertThat(before.triggerDeferrable()).isTrue();
      assertThat(before.triggerInitiallyDeferred()).isTrue();
      assertThat(before.triggerEnabled()).isEqualTo("O");

      // The exact V1010 predicate admits UNKNOWN in CHECK context when a required JSON key is
      // absent or JSON null. This table copies only that installed predicate and is not a
      // production terminal outcome or an Account-authorized abort.
      exerciseTerminalBindingProbe(upgradeSource, oldTerminalConstraint, false);

      List<String> productionRowsBefore = productionLocationRows(upgradeDsl, upgradeSchema);
      assertThat(productionRowsBefore).isEmpty();

      Flyway through10426 =
          flyway(
              upgradeSource,
              upgradeSchema,
              MigrationVersion.fromVersion("1042.6"),
              worldLocation,
              sagaLocation);
      through10426.migrate();
      through10426.validate();
      assertThat(latestVersion(upgradeDsl)).isEqualTo("1042.6");
      assertThat(migrationHistory(upgradeDsl, "1010")).isEqualTo(oldTerminalHistory);
      assertThat(migrationHistory(upgradeDsl, "1019")).isEqualTo(oldLocationHistory);

      String currentTerminalConstraint = constraintDefinition(upgradeDsl, upgradeSchema);
      assertThat(currentTerminalConstraint).contains("IS TRUE");
      exerciseTerminalBindingProbe(upgradeSource, currentTerminalConstraint, true);

      LocationFunctionMetadata after = locationFunctionMetadata(upgradeDsl, upgradeSchema);
      assertFunctionMetadataPreserved(before, after);
      assertOnlyAppliedOperationIdentityWasAdded(before.functionSource(), after.functionSource());

      // The pre-upgrade copy accepts a location owned by another operation because V1019 did not
      // bind the APPLIED lookup to NEW.operation_id. After V1042.6, exact ownership commits and
      // the otherwise identical borrowed-operation case is rejected at deferred commit.
      exerciseLocationOperationProbe(
          upgradeSource, upgradeSchema, before.functionSource(), true, false);
      exerciseLocationOperationProbe(
          upgradeSource, upgradeSchema, after.functionSource(), true, true);
      exerciseLocationOperationProbe(
          upgradeSource, upgradeSchema, after.functionSource(), false, false);
      assertThat(productionLocationRows(upgradeDsl, upgradeSchema)).isEqualTo(productionRowsBefore);

      String repairedTerminalHistory = migrationHistory(upgradeDsl, "1042.6");
      Flyway through10427 =
          flyway(
              upgradeSource,
              upgradeSchema,
              MigrationVersion.fromVersion("1042.7"),
              worldLocation,
              sagaLocation);
      through10427.migrate();
      through10427.validate();
      assertThat(latestVersion(upgradeDsl)).isEqualTo("1042.7");
      String predecessorHistory = migrationHistory(upgradeDsl, "1042.7");
      TerminalFunctionMetadata terminalBefore = terminalFunctionMetadata(upgradeDsl, upgradeSchema);
      assertThat(terminalBefore.publicExecuteGrant()).isFalse();
      assertThat(terminalBefore.securityDefiner()).isTrue();
      List<String> terminalRowsBefore = productionTerminalRows(upgradeDsl, upgradeSchema);
      assertThat(terminalRowsBefore).isEmpty();

      Flyway through10429 =
          flyway(
              upgradeSource,
              upgradeSchema,
              MigrationVersion.fromVersion("1042.9"),
              worldLocation,
              sagaLocation);
      through10429.migrate();
      through10429.validate();
      assertThat(latestVersion(upgradeDsl)).isEqualTo("1042.9");
      TriggerMetadata operationTruncateBefore =
          triggerMetadata(
              upgradeDsl,
              upgradeSchema,
              "world_canonical_initial_player_location_operation",
              OPERATION_TRUNCATE_TRIGGER);
      assertThat(operationTruncateBefore.enabled()).isEqualTo("O");
      assertThat(operationTruncateBefore.definition()).contains("BEFORE TRUNCATE");
      assertThat(
              triggerCount(
                  upgradeDsl, upgradeSchema, "character_location", LOCATION_DELETE_TRIGGER))
          .isZero();
      assertThat(
              triggerCount(
                  upgradeDsl, upgradeSchema, "character_location", LOCATION_TRUNCATE_TRIGGER))
          .isZero();
      List<String> locationRowsBeforeRetentionMigration =
          productionLocationRows(upgradeDsl, upgradeSchema);

      Flyway current = flyway(upgradeSource, upgradeSchema, null, worldLocation, sagaLocation);
      current.migrate();
      current.validate();
      assertThat(current.info().pending()).isEmpty();
      assertThat(migrationHistory(upgradeDsl, "1042.6")).isEqualTo(repairedTerminalHistory);
      assertThat(migrationHistory(upgradeDsl, "1042.7")).isEqualTo(predecessorHistory);
      assertThat(migrationHistory(upgradeDsl, "1010")).isEqualTo(oldTerminalHistory);
      assertThat(migrationHistory(upgradeDsl, "1019")).isEqualTo(oldLocationHistory);
      assertThat(constraintDefinition(upgradeDsl, upgradeSchema))
          .isEqualTo(currentTerminalConstraint);
      LocationFunctionMetadata atCurrentFrontier =
          locationFunctionMetadata(upgradeDsl, upgradeSchema);
      assertFunctionMetadataPreserved(after, atCurrentFrontier);
      TerminalFunctionMetadata terminalAfter = terminalFunctionMetadata(upgradeDsl, upgradeSchema);
      assertTerminalFunctionMetadataPreserved(terminalBefore, terminalAfter);
      List<String> terminalRowsAfter = productionTerminalRows(upgradeDsl, upgradeSchema);
      assertThat(terminalRowsAfter).isEqualTo(terminalRowsBefore);
      exerciseTerminalAffectedUnitProbe(
          upgradeSource, upgradeSchema, terminalAfter.functionSource());

      // A clean default installation must also land on the same repaired frontier.
      Flyway fresh = flyway(freshSource, freshSchema, null, worldLocation, sagaLocation);
      fresh.migrate();
      fresh.validate();
      assertThat(fresh.info().pending()).isEmpty();
      assertThat(constraintDefinition(freshDsl, freshSchema)).contains("IS TRUE");
      assertThat(
              normalizeFunctionSource(
                  locationFunctionMetadata(freshDsl, freshSchema).functionSource(), freshSchema))
          .isEqualTo(normalizeFunctionSource(after.functionSource(), upgradeSchema));
      TerminalFunctionMetadata freshTerminal = terminalFunctionMetadata(freshDsl, freshSchema);
      assertThat(freshTerminal.publicExecuteGrant()).isFalse();
      assertThat(freshTerminal.securityDefiner()).isTrue();
      assertThat(normalizeSchemaQualifier(freshTerminal.functionSource(), freshSchema))
          .isEqualTo(normalizeSchemaQualifier(terminalAfter.functionSource(), upgradeSchema));
      assertThat(productionTerminalRows(freshDsl, freshSchema)).isEmpty();
      assertThat(productionLocationRows(freshDsl, freshSchema)).isEmpty();

      assertThat(MigrationVersion.fromVersion(latestVersion(upgradeDsl)))
          .isGreaterThanOrEqualTo(MigrationVersion.fromVersion("1042.10"));
      assertThat(latestVersion(freshDsl)).isEqualTo(latestVersion(upgradeDsl));
      assertThat(productionLocationRows(upgradeDsl, upgradeSchema))
          .isEqualTo(locationRowsBeforeRetentionMigration);
      assertLocationRetentionGuards(upgradeDsl, upgradeSchema);
      assertLocationRetentionGuards(freshDsl, freshSchema);
      assertThat(
              triggerMetadata(
                  upgradeDsl,
                  upgradeSchema,
                  "world_canonical_initial_player_location_operation",
                  OPERATION_TRUNCATE_TRIGGER))
          .isEqualTo(operationTruncateBefore);
      assertSqlState(
          upgradeSource,
          "TRUNCATE TABLE \""
              + upgradeSchema
              + "\".world_canonical_initial_player_location_operation, \""
              + upgradeSchema
              + "\".character_location",
          "55000");
      assertSqlState(
          upgradeSource, "TRUNCATE TABLE \"" + upgradeSchema + "\".character_location", "55000");
      exerciseCharacterLocationDeleteProbe(upgradeSource, upgradeSchema);
      assertSqlState(
          freshSource,
          "TRUNCATE TABLE \""
              + freshSchema
              + "\".world_canonical_initial_player_location_operation, \""
              + freshSchema
              + "\".character_location",
          "55000");
      assertSqlState(
          freshSource, "TRUNCATE TABLE \"" + freshSchema + "\".character_location", "55000");
      exerciseCharacterLocationDeleteProbe(freshSource, freshSchema);
    }
  }

  private static void exerciseTerminalBindingProbe(
      DriverManagerDataSource source, String installedConstraint, boolean strict)
      throws SQLException {
    TerminalBinding row = TerminalBinding.create();
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute(
          "CREATE TEMP TABLE terminal_binding_probe ("
              + "binding_json TEXT NOT NULL, owner_binding_json TEXT NOT NULL, "
              + "canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL, "
              + "request_id UUID NOT NULL, commit_id UUID NOT NULL, target_namespace TEXT NOT NULL, "
              + "version_identity_operation_id UUID NOT NULL, CONSTRAINT copied_installed_binding "
              + installedConstraint
              + ") ON COMMIT DROP");

      insertTerminalProbe(connection, row.bindingJson(), row.ownerBindingJson(), row);
      List<String> requiredBindingKeys =
          List.of("canonicalTenantId", "canonicalVersionId", "requestId", "commitId");
      List<String> requiredOwnerKeys =
          List.of(
              "targetNamespace",
              "canonicalTenantId",
              "canonicalVersionId",
              "versionIdentityOperationId");
      for (String key : requiredBindingKeys) {
        String omitted = json(row.bindingValues(), key, Mutation.OMIT);
        String jsonNull = json(row.bindingValues(), key, Mutation.NULL);
        assertTerminalProbeOutcome(connection, row, omitted, row.ownerBindingJson(), strict);
        assertTerminalProbeOutcome(connection, row, jsonNull, row.ownerBindingJson(), strict);
      }
      for (String key : requiredOwnerKeys) {
        String omitted = json(row.ownerBindingValues(), key, Mutation.OMIT);
        String jsonNull = json(row.ownerBindingValues(), key, Mutation.NULL);
        assertTerminalProbeOutcome(connection, row, row.bindingJson(), omitted, strict);
        assertTerminalProbeOutcome(connection, row, row.bindingJson(), jsonNull, strict);
      }
      connection.commit();
    }
  }

  private static void assertTerminalProbeOutcome(
      Connection connection,
      TerminalBinding row,
      String bindingJson,
      String ownerBindingJson,
      boolean strict)
      throws SQLException {
    Savepoint savepoint = connection.setSavepoint();
    try {
      insertTerminalProbe(connection, bindingJson, ownerBindingJson, row);
      if (strict) {
        throw new AssertionError("Installed terminal binding CHECK accepted an incomplete tuple");
      }
      connection.releaseSavepoint(savepoint);
    } catch (SQLException failure) {
      connection.rollback(savepoint);
      if (!strict) throw failure;
      assertThat(failure.getSQLState()).isEqualTo("23514");
    }
  }

  private static void exerciseTerminalAffectedUnitProbe(
      DriverManagerDataSource source, String schema, String functionSource) throws SQLException {
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID commitId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID maxAggregateId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    String targetNamespace = "terminal-guard-probe";
    long localTenantKey = 71_001L;
    long localVersionKey = 81_001L;
    long aggregateKey = 91_001L;
    long maxAggregateKey = 91_002L;

    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute(
          "CREATE TEMP TABLE world_design_publication_fence_owner ("
              + "target_namespace TEXT, canonical_tenant_id UUID, local_tenant_key BIGINT, "
              + "version_id BIGINT, owner_freeze_phase TEXT, current_publication_fence UUID) "
              + "ON COMMIT DROP");
      statement.execute(
          "CREATE TEMP TABLE world_region_draft_commit (request_id UUID, commit_id UUID) "
              + "ON COMMIT DROP");
      statement.execute(
          "CREATE TEMP TABLE world_topology_draft_commit (request_id UUID, commit_id UUID) "
              + "ON COMMIT DROP");
      statement.execute(
          "CREATE TEMP TABLE world_authored_topology_identity ("
              + "target_namespace TEXT, canonical_tenant_id UUID, canonical_version_id UUID, "
              + "request_id UUID, commit_id UUID, private_row_key BIGINT, family TEXT, "
              + "template_id UUID) ON COMMIT DROP");
      statement.execute(
          "CREATE TEMP TABLE world_design_revision_ledger ("
              + "tenant_id BIGINT, version_id BIGINT, commit_id TEXT) ON COMMIT DROP");
      statement.execute(
          "CREATE TEMP TABLE world_design_aggregate_epoch ("
              + "tenant_id BIGINT, version_id BIGINT, aggregate_type TEXT, aggregate_id BIGINT, "
              + "draft_revision_epoch BIGINT) ON COMMIT DROP");
      statement.execute(
          "CREATE TEMP TABLE world_design_scope_epoch ("
              + "tenant_id BIGINT, version_id BIGINT, scope_type TEXT, scope_id TEXT, "
              + "draft_scope_revision_epoch BIGINT) ON COMMIT DROP");
      statement.execute(
          "CREATE TEMP TABLE world_draft_terminal_outcome_probe ("
              + "target_namespace TEXT, canonical_tenant_id UUID, canonical_version_id UUID, "
              + "local_tenant_key BIGINT, "
              + "local_version_key BIGINT, request_id UUID, commit_id UUID, "
              + "affected_units_json JSONB NOT NULL) ON COMMIT DROP");

      try (PreparedStatement owner =
          connection.prepareStatement(
              "INSERT INTO pg_temp.world_design_publication_fence_owner VALUES (?, ?, ?, ?, 'OPEN', NULL)")) {
        owner.setString(1, targetNamespace);
        owner.setObject(2, tenantId);
        owner.setLong(3, localTenantKey);
        owner.setLong(4, localVersionKey);
        owner.executeUpdate();
      }
      insertTerminalProbeIdentity(
          connection, targetNamespace, tenantId, versionId, aggregateId, aggregateKey, 4L);
      insertTerminalProbeIdentity(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          maxAggregateId,
          maxAggregateKey,
          Long.MAX_VALUE);
      try (PreparedStatement scope =
          connection.prepareStatement(
              "INSERT INTO pg_temp.world_design_scope_epoch VALUES (?, ?, 'REGION_SUBTREE', ?, 7)")) {
        scope.setLong(1, localTenantKey);
        scope.setLong(2, localVersionKey);
        scope.setString(3, scopeId.toString());
        scope.executeUpdate();
      }

      String schemaQualifier = "\"" + schema + "\".";
      assertThat(functionSource).contains(schemaQualifier + "world_design_publication_fence_owner");
      String probeBody = functionSource.replace(schemaQualifier, "pg_temp.");
      statement.execute(
          "CREATE FUNCTION pg_temp.world_draft_terminal_outcome_guard_probe() RETURNS TRIGGER "
              + "LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $probe$\n"
              + probeBody
              + "\n$probe$");
      statement.execute(
          "CREATE TRIGGER terminal_outcome_probe_guard BEFORE INSERT "
              + "ON pg_temp.world_draft_terminal_outcome_probe FOR EACH ROW "
              + "EXECUTE FUNCTION pg_temp.world_draft_terminal_outcome_guard_probe()");

      String validAggregate =
          terminalUnitJson(
              aggregateId,
              aggregateId,
              true,
              "\"WORLD_MANAGEMENT\"",
              true,
              "\"AGGREGATE\"",
              true,
              "4");
      // Existing World writers use JSON integer epochs and DraftCommitBinding uses canonical
      // decimal strings, so the installed guard must retain both representations.
      String validScope =
          terminalUnitJson(
              aggregateId,
              scopeId,
              true,
              "\"WORLD_MANAGEMENT\"",
              true,
              "\"REGION_SUBTREE\"",
              true,
              "\"7\"");
      String validMaximum =
          terminalUnitJson(
              maxAggregateId,
              maxAggregateId,
              true,
              "\"WORLD_MANAGEMENT\"",
              true,
              "\"AGGREGATE\"",
              true,
              "\"9223372036854775807\"");
      insertTerminalAffectedUnitProbe(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          "[" + validAggregate + "," + validScope + "," + validMaximum + "]");

      assertTerminalAffectedUnitRejected(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          "missing owner",
          "["
              + terminalUnitJson(
                  aggregateId, aggregateId, false, "", true, "\"AGGREGATE\"", true, "4")
              + "]");
      assertTerminalAffectedUnitRejected(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          "null owner",
          "["
              + terminalUnitJson(
                  aggregateId, aggregateId, true, "null", true, "\"AGGREGATE\"", true, "4")
              + "]");
      assertTerminalAffectedUnitRejected(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          "foreign owner",
          "["
              + terminalUnitJson(
                  aggregateId,
                  aggregateId,
                  true,
                  "\"ENTITY_MANAGEMENT\"",
                  true,
                  "\"AGGREGATE\"",
                  true,
                  "4")
              + "]");
      assertTerminalAffectedUnitRejected(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          "missing scope type",
          "["
              + terminalUnitJson(
                  aggregateId, scopeId, true, "\"WORLD_MANAGEMENT\"", false, "", true, "0")
              + "]");
      assertTerminalAffectedUnitRejected(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          "null scope type",
          "["
              + terminalUnitJson(
                  aggregateId, scopeId, true, "\"WORLD_MANAGEMENT\"", true, "null", true, "0")
              + "]");
      assertTerminalAffectedUnitRejected(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          "unknown scope type",
          "["
              + terminalUnitJson(
                  aggregateId,
                  scopeId,
                  true,
                  "\"WORLD_MANAGEMENT\"",
                  true,
                  "\"UNKNOWN\"",
                  true,
                  "0")
              + "]");

      assertInvalidTerminalEpoch(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          aggregateId,
          "missing epoch",
          false,
          "");
      assertInvalidTerminalEpoch(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          aggregateId,
          "null epoch",
          true,
          "null");
      assertInvalidTerminalEpoch(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          aggregateId,
          "malformed string epoch",
          true,
          "\"not-a-counter\"");
      assertInvalidTerminalEpoch(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          aggregateId,
          "fractional epoch",
          true,
          "4.4");
      assertInvalidTerminalEpoch(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          aggregateId,
          "negative epoch",
          true,
          "-1");
      assertInvalidTerminalEpoch(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          aggregateId,
          "out-of-range epoch",
          true,
          "9223372036854775808");
      assertInvalidTerminalEpoch(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          aggregateId,
          "noncanonical decimal string epoch",
          true,
          "\"04\"");
      connection.commit();
    }
  }

  private static void insertTerminalProbeIdentity(
      Connection connection,
      String targetNamespace,
      UUID tenantId,
      UUID versionId,
      UUID templateId,
      long aggregateKey,
      long epoch)
      throws SQLException {
    try (PreparedStatement identity =
            connection.prepareStatement(
                "INSERT INTO pg_temp.world_authored_topology_identity "
                    + "(target_namespace,canonical_tenant_id,canonical_version_id,request_id,commit_id,"
                    + "private_row_key,family,template_id) VALUES (?, ?, ?, ?, ?, ?, 'REGION', ?)");
        PreparedStatement aggregate =
            connection.prepareStatement(
                "INSERT INTO pg_temp.world_design_aggregate_epoch "
                    + "VALUES (?, ?, 'REGION', ?, ?)")) {
      identity.setString(1, targetNamespace);
      identity.setObject(2, tenantId);
      identity.setObject(3, versionId);
      identity.setObject(4, UUID.randomUUID());
      identity.setObject(5, UUID.randomUUID());
      identity.setLong(6, aggregateKey);
      identity.setObject(7, templateId);
      identity.executeUpdate();
      aggregate.setLong(1, 71_001L);
      aggregate.setLong(2, 81_001L);
      aggregate.setLong(3, aggregateKey);
      aggregate.setLong(4, epoch);
      aggregate.executeUpdate();
    }
  }

  private static void assertInvalidTerminalEpoch(
      Connection connection,
      String targetNamespace,
      UUID tenantId,
      UUID versionId,
      long localTenantKey,
      long localVersionKey,
      UUID requestId,
      UUID commitId,
      UUID aggregateId,
      String label,
      boolean includeEpoch,
      String epochJson)
      throws SQLException {
    String unit =
        terminalUnitJson(
            aggregateId,
            aggregateId,
            true,
            "\"WORLD_MANAGEMENT\"",
            true,
            "\"AGGREGATE\"",
            includeEpoch,
            epochJson);
    assertTerminalAffectedUnitRejected(
        connection,
        targetNamespace,
        tenantId,
        versionId,
        localTenantKey,
        localVersionKey,
        requestId,
        commitId,
        label,
        "[" + unit + "]");
  }

  private static String terminalUnitJson(
      UUID aggregateId,
      UUID scopeId,
      boolean includeOwner,
      String ownerJson,
      boolean includeScopeType,
      String scopeTypeJson,
      boolean includeExpectedEpoch,
      String expectedEpochJson) {
    List<String> fields = new ArrayList<>();
    fields.add("\"aggregateType\":\"REGION\"");
    fields.add("\"aggregateId\":\"" + aggregateId + "\"");
    if (includeOwner) fields.add("\"owner\":" + ownerJson);
    if (includeScopeType) fields.add("\"scopeType\":" + scopeTypeJson);
    fields.add("\"scopeId\":\"" + scopeId + "\"");
    if (includeExpectedEpoch) fields.add("\"expectedEpoch\":" + expectedEpochJson);
    return "{" + String.join(",", fields) + "}";
  }

  private static void assertTerminalAffectedUnitRejected(
      Connection connection,
      String targetNamespace,
      UUID tenantId,
      UUID versionId,
      long localTenantKey,
      long localVersionKey,
      UUID requestId,
      UUID commitId,
      String label,
      String affectedUnitsJson)
      throws SQLException {
    Savepoint savepoint = connection.setSavepoint();
    try {
      insertTerminalAffectedUnitProbe(
          connection,
          targetNamespace,
          tenantId,
          versionId,
          localTenantKey,
          localVersionKey,
          requestId,
          commitId,
          affectedUnitsJson);
      throw new AssertionError("Installed terminal guard accepted " + label);
    } catch (SQLException failure) {
      connection.rollback(savepoint);
      assertThat(failure.getSQLState()).as(label).isEqualTo("23514");
    }
  }

  private static void insertTerminalAffectedUnitProbe(
      Connection connection,
      String targetNamespace,
      UUID tenantId,
      UUID versionId,
      long localTenantKey,
      long localVersionKey,
      UUID requestId,
      UUID commitId,
      String affectedUnitsJson)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO pg_temp.world_draft_terminal_outcome_probe "
                + "(target_namespace, canonical_tenant_id, canonical_version_id, local_tenant_key, "
                + "local_version_key, request_id, commit_id, affected_units_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)")) {
      insert.setString(1, targetNamespace);
      insert.setObject(2, tenantId);
      insert.setObject(3, versionId);
      insert.setLong(4, localTenantKey);
      insert.setLong(5, localVersionKey);
      insert.setObject(6, requestId);
      insert.setObject(7, commitId);
      insert.setString(8, affectedUnitsJson);
      insert.executeUpdate();
    }
  }

  private static void insertTerminalProbe(
      Connection connection, String bindingJson, String ownerBindingJson, TerminalBinding row)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO pg_temp.terminal_binding_probe VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, bindingJson);
      insert.setString(2, ownerBindingJson);
      insert.setObject(3, row.tenantId());
      insert.setObject(4, row.versionId());
      insert.setObject(5, row.requestId());
      insert.setObject(6, row.commitId());
      insert.setString(7, row.targetNamespace());
      insert.setObject(8, row.versionIdentityOperationId());
      insert.executeUpdate();
    }
  }

  private static void exerciseLocationOperationProbe(
      DriverManagerDataSource source,
      String schema,
      String functionSource,
      boolean borrowedOperation,
      boolean shouldReject)
      throws SQLException {
    PlacementEvidence evidence = PlacementEvidence.create();
    UUID locationOperationId = UUID.randomUUID();
    UUID operationId = borrowedOperation ? UUID.randomUUID() : locationOperationId;
    String lookupAnchor = "FROM \"" + schema + "\".character_location l";
    assertThat(occurrences(functionSource, lookupAnchor)).isEqualTo(2);
    String probeFunctionSource =
        functionSource.replace(lookupAnchor, "FROM pg_temp.initial_location_probe l");

    SQLException commitFailure = null;
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute(
          "CREATE TEMP TABLE initial_location_probe ("
              + "canonical_tenant_id UUID, playable_state_namespace_id UUID, "
              + "canonical_game_instance_id UUID, canonical_account_id UUID, character_id UUID, "
              + "entity_assignment_operation_id UUID, entity_assignment_digest TEXT, "
              + "canonical_version_id UUID, initial_room_template_id UUID, "
              + "initial_runtime_room_instance_id BIGINT, initial_admission_hold_id UUID, "
              + "initial_admission_hold_fence UUID, realm_id UUID, world_slug TEXT, "
              + "playable_state_scope TEXT, active_lifecycle_epoch BIGINT, "
              + "initial_admission_request_id TEXT, initial_admission_request_digest TEXT, "
              + "catalog_revision BIGINT, initial_admission_owner_proof_id TEXT, "
              + "initial_admission_owner_proof_digest TEXT, pointer_audit_id TEXT, "
              + "pointer_version BIGINT, initial_location_operation_id UUID) ON COMMIT DROP");
      statement.execute(
          "CREATE TEMP TABLE initial_location_operation_probe ("
              + "operation_id UUID NOT NULL, canonical_tenant_id UUID NOT NULL, "
              + "playable_state_namespace_id UUID NOT NULL, canonical_game_instance_id UUID NOT NULL, "
              + "request_bytes BYTEA NOT NULL, result_bytes BYTEA NOT NULL, "
              + "outcome TEXT NOT NULL) ON COMMIT DROP");
      statement.execute(
          "CREATE FUNCTION pg_temp.initial_location_operation_result_probe() RETURNS TRIGGER "
              + "LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, \""
              + schema
              + "\" AS $probe$"
              + probeFunctionSource
              + "$probe$");
      statement.execute(
          "CREATE CONSTRAINT TRIGGER initial_location_operation_probe_deferred AFTER INSERT ON "
              + "pg_temp.initial_location_operation_probe DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
              + "EXECUTE FUNCTION pg_temp.initial_location_operation_result_probe()");
      insertPlacement(connection, evidence, locationOperationId);
      insertLocationOperation(connection, evidence, operationId);
      try {
        connection.commit();
      } catch (SQLException failure) {
        commitFailure = failure;
        connection.rollback();
      }
    }

    if (shouldReject) {
      assertThat((Throwable) commitFailure)
          .as("the copied deferred APPLIED guard rejects a location owned by another operation")
          .isNotNull();
      assertThat(commitFailure.getSQLState()).isEqualTo("23514");
      assertThat(commitFailure.getMessage())
          .contains("Applied World initial-location operation has no atomic location row");
    } else {
      assertThat((Throwable) commitFailure).isNull();
    }
  }

  private static void insertPlacement(
      Connection connection, PlacementEvidence evidence, UUID locationOperationId)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO pg_temp.initial_location_probe VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      int i = 1;
      insert.setObject(i++, evidence.tenantId());
      insert.setObject(i++, evidence.playableNamespaceId());
      insert.setObject(i++, evidence.gameInstanceId());
      insert.setObject(i++, evidence.accountId());
      insert.setObject(i++, evidence.characterId());
      insert.setObject(i++, evidence.assignmentOperationId());
      insert.setString(i++, evidence.assignmentDigest());
      insert.setObject(i++, evidence.versionId());
      insert.setObject(i++, evidence.roomTemplateId());
      insert.setLong(i++, evidence.runtimeRoomId());
      insert.setObject(i++, evidence.holdId());
      insert.setObject(i++, evidence.holdFence());
      insert.setObject(i++, evidence.realmId());
      insert.setString(i++, evidence.worldSlug());
      insert.setString(i++, evidence.playableScope());
      insert.setLong(i++, evidence.lifecycleEpoch());
      insert.setString(i++, evidence.admissionRequestId());
      insert.setString(i++, evidence.admissionRequestDigest());
      insert.setLong(i++, evidence.catalogRevision());
      insert.setString(i++, evidence.ownerProofId());
      insert.setString(i++, evidence.ownerProofDigest());
      insert.setString(i++, evidence.pointerAuditId());
      insert.setLong(i++, evidence.pointerVersion());
      insert.setObject(i, locationOperationId);
      insert.executeUpdate();
    }
  }

  private static void insertLocationOperation(
      Connection connection, PlacementEvidence evidence, UUID operationId) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO pg_temp.initial_location_operation_probe VALUES (?, ?, ?, ?, ?, ?, 'APPLIED')")) {
      insert.setObject(1, operationId);
      insert.setObject(2, evidence.tenantId());
      insert.setObject(3, evidence.playableNamespaceId());
      insert.setObject(4, evidence.gameInstanceId());
      insert.setBytes(5, evidence.requestJson().getBytes(StandardCharsets.UTF_8));
      insert.setBytes(6, evidence.resultJson().getBytes(StandardCharsets.UTF_8));
      insert.executeUpdate();
    }
  }

  private static String constraintDefinition(DSLContext dsl, String schema) {
    return dsl.fetchSingle(
            "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace "
                + "WHERE n.nspname=? AND t.relname='world_draft_terminal_outcome' AND c.conname=?",
            schema,
            BINDING_CONSTRAINT)
        .get(0, String.class);
  }

  private static LocationFunctionMetadata locationFunctionMetadata(DSLContext dsl, String schema) {
    var row =
        dsl.fetchSingle(
            "SELECT p.oid::bigint AS function_oid,p.proowner::regrole::text AS function_owner,"
                + "p.proacl::text AS function_acl,p.prosecdef AS security_definer,"
                + "p.proconfig::text AS function_config,p.prosrc AS function_source,l.lanname AS language,"
                + "t.oid::bigint AS trigger_oid,t.tgfoid::bigint AS trigger_function_oid,"
                + "t.tgdeferrable AS trigger_deferrable,t.tginitdeferred AS trigger_initially_deferred,"
                + "t.tgenabled::text AS trigger_enabled,pg_get_triggerdef(t.oid) AS trigger_definition,"
                + "(SELECT count(*) FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a "
                + "WHERE a.grantee=0 AND a.privilege_type='EXECUTE') AS public_execute_grants "
                + "FROM pg_proc p JOIN pg_namespace pn ON pn.oid=p.pronamespace "
                + "JOIN pg_language l ON l.oid=p.prolang JOIN pg_trigger t ON t.tgfoid=p.oid "
                + "JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace tn ON tn.oid=c.relnamespace "
                + "WHERE pn.nspname=? AND tn.nspname=? AND p.proname=? AND t.tgname=?",
            schema,
            schema,
            LOCATION_FUNCTION,
            LOCATION_TRIGGER);
    return new LocationFunctionMetadata(
        row.get("function_oid", Long.class),
        row.get("function_owner", String.class),
        row.get("function_acl", String.class),
        row.get("security_definer", Boolean.class),
        row.get("function_config", String.class),
        row.get("function_source", String.class),
        row.get("language", String.class),
        row.get("trigger_oid", Long.class),
        row.get("trigger_function_oid", Long.class),
        row.get("trigger_deferrable", Boolean.class),
        row.get("trigger_initially_deferred", Boolean.class),
        row.get("trigger_enabled", String.class),
        row.get("trigger_definition", String.class),
        row.get("public_execute_grants", Long.class) > 0);
  }

  private static TriggerMetadata triggerMetadata(
      DSLContext dsl, String schema, String table, String trigger) {
    var row =
        dsl.fetchSingle(
            "SELECT t.oid::bigint AS trigger_oid,t.tgfoid::bigint AS function_oid,"
                + "t.tgenabled::text AS enabled,pg_get_triggerdef(t.oid) AS definition "
                + "FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid "
                + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=? AND t.tgname=? AND NOT t.tgisinternal",
            schema,
            table,
            trigger);
    return new TriggerMetadata(
        row.get("trigger_oid", Long.class),
        row.get("function_oid", Long.class),
        row.get("enabled", String.class),
        row.get("definition", String.class));
  }

  private static long triggerCount(DSLContext dsl, String schema, String table, String trigger) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid "
                + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=? AND t.tgname=? AND NOT t.tgisinternal",
            schema,
            table,
            trigger)
        .get(0, Long.class);
  }

  private static void assertLocationRetentionGuards(DSLContext dsl, String schema) {
    long sharedFunctionOid =
        dsl.fetchSingle(
                "SELECT p.oid::bigint FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                    + "WHERE n.nspname=? AND p.proname=? AND pg_get_function_identity_arguments(p.oid)=''",
                schema,
                LOCATION_RETENTION_FUNCTION)
            .get(0, Long.class);
    TriggerMetadata delete =
        triggerMetadata(dsl, schema, "character_location", LOCATION_DELETE_TRIGGER);
    TriggerMetadata truncate =
        triggerMetadata(dsl, schema, "character_location", LOCATION_TRUNCATE_TRIGGER);
    assertThat(delete.enabled()).isEqualTo("O");
    assertThat(delete.functionOid()).isEqualTo(sharedFunctionOid);
    assertThat(delete.definition()).contains("BEFORE DELETE");
    assertThat(truncate.enabled()).isEqualTo("O");
    assertThat(truncate.functionOid()).isEqualTo(sharedFunctionOid);
    assertThat(truncate.definition()).contains("BEFORE TRUNCATE");
  }

  private static void assertSqlState(
      DriverManagerDataSource source, String sql, String expectedSqlState) throws SQLException {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      try {
        statement.execute(sql);
        throw new AssertionError("Expected SQLSTATE " + expectedSqlState + " for: " + sql);
      } catch (SQLException failure) {
        assertThat(failure.getSQLState()).isEqualTo(expectedSqlState);
      }
    }
  }

  private static void exerciseCharacterLocationDeleteProbe(
      DriverManagerDataSource source, String schema) throws SQLException {
    // Test-local probe: genuine APPLIED rows require future Account/World admission authority.
    // This exercises the installed immutable-history function without fabricating that authority.
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TEMP TABLE character_location_delete_probe (marker INTEGER NOT NULL)");
      statement.execute(
          "CREATE TRIGGER trg_world_probe_character_location_delete BEFORE DELETE ON "
              + "character_location_delete_probe FOR EACH ROW EXECUTE FUNCTION \""
              + schema
              + "\"."
              + LOCATION_RETENTION_FUNCTION
              + "()");
      statement.execute("INSERT INTO character_location_delete_probe VALUES (1)");
      try {
        statement.execute("DELETE FROM character_location_delete_probe");
        throw new AssertionError("Test-local character-location deletion probe was not rejected");
      } catch (SQLException failure) {
        assertThat(failure.getSQLState()).isEqualTo("55000");
      }
    }
  }

  private static TerminalFunctionMetadata terminalFunctionMetadata(DSLContext dsl, String schema) {
    var row =
        dsl.fetchSingle(
            "SELECT p.oid::bigint AS function_oid,p.proowner::regrole::text AS function_owner,"
                + "p.proacl::text AS function_acl,p.prosecdef AS security_definer,"
                + "p.proconfig::text AS function_config,p.prosrc AS function_source,l.lanname AS language,"
                + "t.oid::bigint AS trigger_oid,t.tgfoid::bigint AS trigger_function_oid,"
                + "t.tgdeferrable AS trigger_deferrable,t.tginitdeferred AS trigger_initially_deferred,"
                + "t.tgenabled::text AS trigger_enabled,pg_get_triggerdef(t.oid) AS trigger_definition,"
                + "(SELECT count(*) FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a "
                + "WHERE a.grantee=0 AND a.privilege_type='EXECUTE') AS public_execute_grants "
                + "FROM pg_proc p JOIN pg_namespace pn ON pn.oid=p.pronamespace "
                + "JOIN pg_language l ON l.oid=p.prolang JOIN pg_trigger t ON t.tgfoid=p.oid "
                + "JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace tn ON tn.oid=c.relnamespace "
                + "WHERE pn.nspname=? AND tn.nspname=? AND p.proname=? AND t.tgname=?",
            schema,
            schema,
            TERMINAL_FUNCTION,
            TERMINAL_TRIGGER);
    return new TerminalFunctionMetadata(
        row.get("function_oid", Long.class),
        row.get("function_owner", String.class),
        row.get("function_acl", String.class),
        row.get("security_definer", Boolean.class),
        row.get("function_config", String.class),
        row.get("function_source", String.class),
        row.get("language", String.class),
        row.get("trigger_oid", Long.class),
        row.get("trigger_function_oid", Long.class),
        row.get("trigger_deferrable", Boolean.class),
        row.get("trigger_initially_deferred", Boolean.class),
        row.get("trigger_enabled", String.class),
        row.get("trigger_definition", String.class),
        row.get("public_execute_grants", Long.class) > 0);
  }

  private static void assertFunctionMetadataPreserved(
      LocationFunctionMetadata before, LocationFunctionMetadata after) {
    assertThat(after.functionOid()).isEqualTo(before.functionOid());
    assertThat(after.functionOwner()).isEqualTo(before.functionOwner());
    assertThat(after.functionAcl()).isEqualTo(before.functionAcl());
    assertThat(after.securityDefiner()).isEqualTo(before.securityDefiner()).isTrue();
    assertThat(after.functionConfig()).isEqualTo(before.functionConfig());
    assertThat(after.language()).isEqualTo(before.language()).isEqualTo("plpgsql");
    assertThat(after.triggerOid()).isEqualTo(before.triggerOid());
    assertThat(after.triggerFunctionOid()).isEqualTo(before.triggerFunctionOid());
    assertThat(after.triggerDeferrable()).isEqualTo(before.triggerDeferrable()).isTrue();
    assertThat(after.triggerInitiallyDeferred())
        .isEqualTo(before.triggerInitiallyDeferred())
        .isTrue();
    assertThat(after.triggerEnabled()).isEqualTo(before.triggerEnabled()).isEqualTo("O");
    assertThat(after.triggerDefinition()).isEqualTo(before.triggerDefinition());
    assertThat(after.publicExecuteGrant()).isEqualTo(before.publicExecuteGrant()).isFalse();
    assertThat(after.functionConfig()).contains("search_path=pg_catalog");
  }

  private static void assertTerminalFunctionMetadataPreserved(
      TerminalFunctionMetadata before, TerminalFunctionMetadata after) {
    assertThat(after.functionOid()).isEqualTo(before.functionOid());
    assertThat(after.functionOwner()).isEqualTo(before.functionOwner());
    assertThat(after.functionAcl()).isEqualTo(before.functionAcl());
    assertThat(after.securityDefiner()).isEqualTo(before.securityDefiner()).isTrue();
    assertThat(after.functionConfig()).isEqualTo(before.functionConfig());
    assertThat(after.language()).isEqualTo(before.language()).isEqualTo("plpgsql");
    assertThat(after.triggerOid()).isEqualTo(before.triggerOid());
    assertThat(after.triggerFunctionOid()).isEqualTo(before.triggerFunctionOid());
    assertThat(after.triggerDeferrable()).isEqualTo(before.triggerDeferrable()).isFalse();
    assertThat(after.triggerInitiallyDeferred())
        .isEqualTo(before.triggerInitiallyDeferred())
        .isFalse();
    assertThat(after.triggerEnabled()).isEqualTo(before.triggerEnabled()).isEqualTo("O");
    assertThat(after.triggerDefinition()).isEqualTo(before.triggerDefinition());
    assertThat(after.publicExecuteGrant()).isEqualTo(before.publicExecuteGrant()).isFalse();
    assertThat(after.functionConfig()).contains("search_path=pg_catalog");
  }

  private static void assertOnlyAppliedOperationIdentityWasAdded(String before, String after) {
    String normalizedBefore = normalizeSql(before);
    String normalizedAfter = normalizeSql(after);
    String appliedIdentityPredicate =
        "and l.initial_location_operation_id = new.operation_id and l.canonical_account_id = ";
    assertThat(
            occurrences(normalizedAfter, "and l.initial_location_operation_id = new.operation_id"))
        .isEqualTo(
            occurrences(normalizedBefore, "and l.initial_location_operation_id = new.operation_id")
                + 1);
    assertThat(normalizedAfter)
        .contains(appliedIdentityPredicate)
        .doesNotContain("initial_location_operation_id = new.operation_id::");
    assertThat(normalizedAfter.replace(appliedIdentityPredicate, "and l.canonical_account_id = "))
        .isEqualTo(normalizedBefore);
  }

  private static List<String> productionLocationRows(DSLContext dsl, String schema) {
    return dsl.fetch(
            "SELECT 'operation:' || xmin::text || ':' || to_jsonb(o)::text FROM \""
                + schema
                + "\".world_canonical_initial_player_location_operation o "
                + "UNION ALL SELECT 'location:' || xmin::text || ':' || to_jsonb(l)::text FROM \""
                + schema
                + "\".character_location l ORDER BY 1")
        .getValues(0, String.class);
  }

  private static List<String> productionTerminalRows(DSLContext dsl, String schema) {
    return dsl.fetch(
            "SELECT jsonb_build_object('xmin',t.xmin::text,'row',to_jsonb(t))::text FROM \""
                + schema
                + "\".world_draft_terminal_outcome t ORDER BY t.operation_id")
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

  private static DriverManagerDataSource source(String schema) {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    return source;
  }

  private static void dropSchema(DriverManagerDataSource source, String schema)
      throws SQLException {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
  }

  private static String schemaName(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  private static String json(Map<String, String> values, String changedKey, Mutation mutation) {
    StringBuilder result = new StringBuilder("{");
    boolean first = true;
    for (var entry : values.entrySet()) {
      if (entry.getKey().equals(changedKey) && mutation == Mutation.OMIT) continue;
      if (!first) result.append(',');
      first = false;
      result.append('"').append(entry.getKey()).append("\":");
      if (entry.getKey().equals(changedKey) && mutation == Mutation.NULL) {
        result.append("null");
      } else {
        result.append('"').append(entry.getValue()).append('"');
      }
    }
    return result.append('}').toString();
  }

  private static String normalizeSql(String sql) {
    return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeFunctionSource(String source, String schema) {
    return normalizeSql(
        source.replace(
            "FROM \"" + schema + "\".character_location l",
            "FROM <service-schema>.character_location l"));
  }

  private static String normalizeSchemaQualifier(String source, String schema) {
    return normalizeSql(source.replace("\"" + schema + "\".", "<service-schema>."));
  }

  private static int occurrences(String value, String fragment) {
    int count = 0;
    int start = 0;
    while ((start = value.indexOf(fragment, start)) >= 0) {
      count++;
      start += fragment.length();
    }
    return count;
  }

  private enum Mutation {
    OMIT,
    NULL
  }

  private record TerminalBinding(
      UUID tenantId,
      UUID versionId,
      UUID requestId,
      UUID commitId,
      UUID versionIdentityOperationId,
      String targetNamespace) {
    private static TerminalBinding create() {
      return new TerminalBinding(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "firemud");
    }

    private Map<String, String> bindingValues() {
      Map<String, String> result = new LinkedHashMap<>();
      result.put("canonicalTenantId", tenantId.toString());
      result.put("canonicalVersionId", versionId.toString());
      result.put("requestId", requestId.toString());
      result.put("commitId", commitId.toString());
      return result;
    }

    private Map<String, String> ownerBindingValues() {
      Map<String, String> result = new LinkedHashMap<>();
      result.put("targetNamespace", targetNamespace);
      result.put("canonicalTenantId", tenantId.toString());
      result.put("canonicalVersionId", versionId.toString());
      result.put("versionIdentityOperationId", versionIdentityOperationId.toString());
      return result;
    }

    private String bindingJson() {
      return json(bindingValues(), "", Mutation.OMIT);
    }

    private String ownerBindingJson() {
      return json(ownerBindingValues(), "", Mutation.OMIT);
    }
  }

  private record PlacementEvidence(
      UUID tenantId,
      UUID playableNamespaceId,
      UUID gameInstanceId,
      UUID accountId,
      UUID characterId,
      UUID assignmentOperationId,
      String assignmentDigest,
      UUID versionId,
      UUID roomTemplateId,
      long runtimeRoomId,
      UUID holdId,
      UUID holdFence,
      UUID realmId,
      String worldSlug,
      String playableScope,
      long lifecycleEpoch,
      String admissionRequestId,
      String admissionRequestDigest,
      long catalogRevision,
      String ownerProofId,
      String ownerProofDigest,
      String pointerAuditId,
      long pointerVersion) {
    private static PlacementEvidence create() {
      UUID versionId = UUID.randomUUID();
      UUID roomTemplateId = UUID.randomUUID();
      long runtimeRoomId = 991L;
      return new PlacementEvidence(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "a".repeat(64),
          versionId,
          roomTemplateId,
          runtimeRoomId,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "test-world",
          "ISOLATED",
          3L,
          "admission-request",
          "b".repeat(64),
          7L,
          "owner-proof",
          "c".repeat(64),
          "pointer-audit",
          11L);
    }

    private String requestJson() {
      String startLocation = startLocationJson();
      return "{\"canonicalAccountId\":\""
          + accountId
          + "\",\"characterId\":\""
          + characterId
          + "\",\"entityAssignmentOperationId\":\""
          + assignmentOperationId
          + "\",\"entityAssignmentDigest\":\""
          + assignmentDigest
          + "\",\"initialAdmissionHoldId\":\""
          + holdId
          + "\",\"initialAdmissionHoldFence\":\""
          + holdFence
          + "\",\"realmId\":\""
          + realmId
          + "\",\"worldSlug\":\""
          + worldSlug
          + "\",\"playableStateScope\":\""
          + playableScope
          + "\",\"activeLifecycleEvidence\":{\"lifecycleEpoch\":\""
          + lifecycleEpoch
          + "\",\"request\":{\"canonicalVersionId\":\""
          + versionId
          + "\"},\"startLocation\":"
          + startLocation
          + ",\"runtimeRoomInstanceId\":\""
          + runtimeRoomId
          + "\"},\"initialAdmissionRequestId\":\""
          + admissionRequestId
          + "\",\"initialAdmissionRequestDigest\":\""
          + admissionRequestDigest
          + "\",\"catalogRevision\":\""
          + catalogRevision
          + "\",\"initialAdmissionOwnerProofId\":\""
          + ownerProofId
          + "\",\"initialAdmissionOwnerProofDigest\":\""
          + ownerProofDigest
          + "\",\"pointerAuditId\":\""
          + pointerAuditId
          + "\",\"pointerVersion\":\""
          + pointerVersion
          + "\"}";
    }

    private String resultJson() {
      return "{\"startLocation\":"
          + startLocationJson()
          + ",\"runtimeRoomInstanceId\":\""
          + runtimeRoomId
          + "\"}";
    }

    private String startLocationJson() {
      return "{\"tenantId\":\""
          + tenantId
          + "\",\"versionId\":\""
          + versionId
          + "\",\"roomTemplateId\":\""
          + roomTemplateId
          + "\"}";
    }
  }

  private record LocationFunctionMetadata(
      long functionOid,
      String functionOwner,
      String functionAcl,
      boolean securityDefiner,
      String functionConfig,
      String functionSource,
      String language,
      long triggerOid,
      long triggerFunctionOid,
      boolean triggerDeferrable,
      boolean triggerInitiallyDeferred,
      String triggerEnabled,
      String triggerDefinition,
      boolean publicExecuteGrant) {}

  private record TerminalFunctionMetadata(
      long functionOid,
      String functionOwner,
      String functionAcl,
      boolean securityDefiner,
      String functionConfig,
      String functionSource,
      String language,
      long triggerOid,
      long triggerFunctionOid,
      boolean triggerDeferrable,
      boolean triggerInitiallyDeferred,
      String triggerEnabled,
      String triggerDefinition,
      boolean publicExecuteGrant) {}

  private record TriggerMetadata(
      long triggerOid, long functionOid, String enabled, String definition) {}
}
