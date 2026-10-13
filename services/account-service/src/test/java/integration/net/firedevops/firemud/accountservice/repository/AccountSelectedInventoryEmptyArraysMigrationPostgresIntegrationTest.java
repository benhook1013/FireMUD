package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical Account selected-inventory migration and retained-ordering guard proof. */
@Testcontainers(disabledWithoutDocker = true)
class AccountSelectedInventoryEmptyArraysMigrationPostgresIntegrationTest {
  private static final String HISTORY_TABLE = ".flyway_schema_history";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void acceptsTheCurrentCoalescedV126DefinitionAndRetainsBothOrderingGuards() {
    withVersion126(
        context -> {
          assertCanonicalOrderingGuards(functionDefinition(context));

          migrateTo127(context);

          assertVersionApplied(context, "127");
          assertCanonicalOrderingGuards(functionDefinition(context));
          assertThat(sourceKeyLength(context)).isEqualTo(2048);
        });
  }

  @Test
  void upgradesTheOriginalV126OrderingAnchorsToCanonicalEmptyArrayGuards() {
    withVersion126(
        context -> {
          String current = functionDefinition(context);
          String originalAnchors =
              replaceExactlyOnce(
                  replaceExactlyOnce(current, regionReplacement(), regionAnchor()),
                  spawnReplacement(),
                  spawnAnchor());
          executeFunctionDefinition(context, originalAnchors);
          assertOriginalOrderingGuards(functionDefinition(context));

          migrateTo127(context);

          assertVersionApplied(context, "127");
          assertCanonicalOrderingGuards(functionDefinition(context));
          assertThat(sourceKeyLength(context)).isEqualTo(2048);
        });
  }

  @Test
  void rejectsAMissingOrderingGuardAndRollsBackV127() {
    withVersion126(
        context -> {
          String before = functionDefinition(context);
          String missingRegion = replaceExactlyOnce(before, regionReplacement(), "");
          executeFunctionDefinition(context, missingRegion);
          String preflightDefinition = functionDefinition(context);
          Integer preflightSourceKeyLength = sourceKeyLength(context);

          assertV127RejectedWithoutChangingV126(context);

          assertThat(functionDefinition(context)).isEqualTo(preflightDefinition);
          assertThat(sourceKeyLength(context)).isEqualTo(preflightSourceKeyLength);
        });
  }

  @Test
  void rejectsADuplicatedOrderingGuardAndRollsBackV127() {
    withVersion126(
        context -> {
          String before = functionDefinition(context);
          String duplicatedSpawn =
              replaceExactlyOnce(
                  before, spawnReplacement(), spawnReplacement() + "\n" + spawnReplacement());
          executeFunctionDefinition(context, duplicatedSpawn);
          String preflightDefinition = functionDefinition(context);
          Integer preflightSourceKeyLength = sourceKeyLength(context);

          assertV127RejectedWithoutChangingV126(context);

          assertThat(functionDefinition(context)).isEqualTo(preflightDefinition);
          assertThat(sourceKeyLength(context)).isEqualTo(preflightSourceKeyLength);
        });
  }

  @Test
  void upgradesV162ToPairedClosureProfileWithoutChangingOtherGuardsOrHistory() {
    withVersion162(
        context -> {
          String before = functionDefinition(context);
          Long originalOid = functionOid(context);
          Map<String, Integer> originalChecksums = migrationChecksums(context);
          assertCanonicalOrderingGuards(before);

          migrateTo163(context);

          String after = functionDefinition(context);
          assertVersionApplied(context, "163");
          assertThat(after).isEqualTo(expectedV163Definition(before));
          assertThat(functionOid(context)).isEqualTo(originalOid);
          assertThat(migrationChecksums(context)).containsAllEntriesOf(originalChecksums);
          assertThat(migrationChecksums(context)).containsKeys("162", "163");
          assertCanonicalOrderingGuards(after);
          assertThat(sourceKeyLength(context)).isEqualTo(2048);
        });
  }

  private static void withVersion126(Consumer<TestContext> proof) {
    String schema = "selected_inventory_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      migration(dataSource, schema, "126").migrate();
      TestContext context = new TestContext(schema, jdbc, dataSource);
      assertVersionApplied(context, "126");
      assertVersionNotApplied(context, "127");
      proof.accept(context);
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + quotedSchema(schema) + " CASCADE");
    }
  }

  private static void withVersion162(Consumer<TestContext> proof) {
    String schema = "selected_inventory_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      migration(dataSource, schema, "162").migrate();
      TestContext context = new TestContext(schema, jdbc, dataSource);
      assertVersionApplied(context, "162");
      assertVersionNotApplied(context, "163");
      proof.accept(context);
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + quotedSchema(schema) + " CASCADE");
    }
  }

  private static Flyway migration(
      DriverManagerDataSource dataSource, String schema, String targetVersion) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion(targetVersion))
        .load();
  }

  private static void migrateTo127(TestContext context) {
    migration(context.dataSource(), context.schema(), "127").migrate();
  }

  private static void migrateTo163(TestContext context) {
    migration(context.dataSource(), context.schema(), "163").migrate();
  }

  private static void assertV127RejectedWithoutChangingV126(TestContext context) {
    assertThatThrownBy(() -> migrateTo127(context))
        .hasStackTraceContaining(
            "Expected exactly one V108 ordering guard for each selected inventory input array");
    assertVersionApplied(context, "126");
    assertVersionNotApplied(context, "127");
  }

  private static String functionDefinition(TestContext context) {
    String signature =
        quotedSchema(context.schema()) + ".require_selected_inventory_operation_v2(bytea)";
    return Objects.requireNonNull(
        context
            .jdbc()
            .queryForObject("SELECT pg_get_functiondef(?::regprocedure)", String.class, signature));
  }

  private static Long functionOid(TestContext context) {
    String signature =
        quotedSchema(context.schema()) + ".require_selected_inventory_operation_v2(bytea)";
    return Objects.requireNonNull(
        context
            .jdbc()
            .queryForObject("SELECT ?::regprocedure::oid::bigint", Long.class, signature));
  }

  private static Map<String, Integer> migrationChecksums(TestContext context) {
    var checksums = new java.util.HashMap<String, Integer>();
    context
        .jdbc()
        .query(
            "SELECT version, checksum FROM "
                + quotedSchema(context.schema())
                + HISTORY_TABLE
                + " WHERE version IS NOT NULL AND success",
            row -> {
              checksums.put(row.getString("version"), row.getInt("checksum"));
            });
    return Map.copyOf(checksums);
  }

  private static String expectedV163Definition(String before) {
    String expected = replaceExactlyOnce(before, modelShapeAnchor(), modelShapeReplacement());
    expected =
        replaceExactlyOnce(expected, inventoryProfileAnchor(), inventoryProfileReplacement());
    expected = replaceExactlyOnce(expected, modelCountAnchor(), modelCountReplacement());
    return replaceExactlyOnce(expected, profileAnchor(), profileReplacement());
  }

  private static String modelShapeAnchor() {
    return "        (model, ARRAY['modelId','graphSchemaVersion','graphDigest','topologyResultDigest',"
        + "'familyCounts','regionGeneratorInputs','generationRuleFields','regionFields','zoneFields',"
        + "'roomFields','roomExitFields','spawnBindingFields','spawnBindingInputs','generationRuleInputCount',"
        + "'spawnBindingCount','appliedEpochs'])";
  }

  private static String modelShapeReplacement() {
    return "        (model, CASE\n"
        + "            WHEN inventory->>'schema' = 'world-selected-publication-artifact-inventory/v2'\n"
        + "                AND inventory->'schemaVersion' = '2'::JSONB\n"
        + "            THEN ARRAY['modelId','graphSchemaVersion','graphDigest','topologyResultDigest',"
        + "'familyCounts','regionGeneratorInputs','generationRuleFields','regionFields','zoneFields',"
        + "'roomFields','roomExitFields','spawnBindingFields','spawnBindingInputs','generationRuleInputCount',"
        + "'spawnBindingCount','appliedEpochs','inboundSourceClosure']\n"
        + "            ELSE ARRAY['modelId','graphSchemaVersion','graphDigest','topologyResultDigest',"
        + "'familyCounts','regionGeneratorInputs','generationRuleFields','regionFields','zoneFields',"
        + "'roomFields','roomExitFields','spawnBindingFields','spawnBindingInputs','generationRuleInputCount',"
        + "'spawnBindingCount','appliedEpochs']\n"
        + "        END)";
  }

  private static String inventoryProfileAnchor() {
    return "        OR inventory->>'schema' IS DISTINCT FROM 'world-selected-publication-artifact-inventory/v1'\n"
        + "        OR inventory->'schemaVersion' IS DISTINCT FROM '1'::JSONB OR inventory->>'completeness' IS DISTINCT FROM 'COMPLETE'";
  }

  private static String inventoryProfileReplacement() {
    return "        OR NOT ((inventory->>'schema' = 'world-selected-publication-artifact-inventory/v1'\n"
        + "                AND inventory->'schemaVersion' = '1'::JSONB)\n"
        + "            OR (inventory->>'schema' = 'world-selected-publication-artifact-inventory/v2'\n"
        + "                AND inventory->'schemaVersion' = '2'::JSONB))\n"
        + "        OR inventory->>'completeness' IS DISTINCT FROM 'COMPLETE'";
  }

  private static String modelCountAnchor() {
    return "        OR (SELECT count(*) FROM jsonb_object_keys(model)) <> 16";
  }

  private static String modelCountReplacement() {
    return "        OR (SELECT count(*) FROM jsonb_object_keys(model)) <> (CASE\n"
        + "            WHEN inventory->>'schema' = 'world-selected-publication-artifact-inventory/v2'\n"
        + "                AND inventory->'schemaVersion' = '2'::JSONB THEN 17\n"
        + "            ELSE 16\n"
        + "        END)";
  }

  private static String profileAnchor() {
    return "        OR model->>'modelId' IS DISTINCT FROM 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1'\n"
        + "        OR model->'graphSchemaVersion' IS DISTINCT FROM '2'::JSONB";
  }

  private static String profileReplacement() {
    return "        OR ((inventory->>'schema' = 'world-selected-publication-artifact-inventory/v1'\n"
        + "                AND inventory->'schemaVersion' = '1'::JSONB\n"
        + "                AND model->>'modelId' = 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1'\n"
        + "                AND model->'graphSchemaVersion' = '2'::JSONB\n"
        + "                AND checkpoint->'digestSchemaVersion' = '3'::JSONB\n"
        + "                AND selector->'request'->'digestSchemaVersion' = '3'::JSONB\n"
        + "                AND NOT (model ? 'inboundSourceClosure'))\n"
        + "            OR (inventory->>'schema' = 'world-selected-publication-artifact-inventory/v2'\n"
        + "                AND inventory->'schemaVersion' = '2'::JSONB\n"
        + "                AND model->>'modelId' = 'WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_INBOUND_EMPTY_V1'\n"
        + "                AND model->'graphSchemaVersion' = '3'::JSONB\n"
        + "                AND checkpoint->'digestSchemaVersion' = '4'::JSONB\n"
        + "                AND selector->'request'->'digestSchemaVersion' = '4'::JSONB\n"
        + "                AND model ?& ARRAY['modelId', 'graphSchemaVersion', 'graphDigest',\n"
        + "                    'topologyResultDigest', 'familyCounts', 'regionGeneratorInputs',\n"
        + "                    'generationRuleFields', 'regionFields', 'zoneFields', 'roomFields',\n"
        + "                    'roomExitFields', 'spawnBindingFields', 'spawnBindingInputs',\n"
        + "                    'generationRuleInputCount', 'spawnBindingCount', 'appliedEpochs',\n"
        + "                    'inboundSourceClosure']\n"
        + "                AND model->'inboundSourceClosure' IS NOT DISTINCT FROM\n"
        + "                    '{\"schemaVersion\":1,\"familyCounts\":[{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT\",\"count\":0},{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT\",\"count\":0},{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION\",\"count\":0},{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING\",\"count\":0},{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK\",\"count\":0},{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE\",\"count\":0},{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING\",\"count\":0}]}'::JSONB)) IS NOT TRUE";
  }

  private static void executeFunctionDefinition(TestContext context, String definition) {
    context.jdbc().execute(definition);
  }

  private static void assertOriginalOrderingGuards(String definition) {
    assertThat(countOccurrences(definition, regionAnchor())).isEqualTo(1);
    assertThat(countOccurrences(definition, spawnAnchor())).isEqualTo(1);
    assertThat(countOccurrences(definition, regionReplacement())).isZero();
    assertThat(countOccurrences(definition, spawnReplacement())).isZero();
    assertOrderingLoopsRemain(definition);
  }

  private static void assertCanonicalOrderingGuards(String definition) {
    assertThat(countOccurrences(definition, regionAnchor())).isZero();
    assertThat(countOccurrences(definition, spawnAnchor())).isZero();
    assertThat(countOccurrences(definition, regionReplacement())).isEqualTo(1);
    assertThat(countOccurrences(definition, spawnReplacement())).isEqualTo(1);
    assertOrderingLoopsRemain(definition);
  }

  private static void assertOrderingLoopsRemain(String definition) {
    assertThat(countOccurrences(definition, "previous_key COLLATE \"C\" >=")).isEqualTo(2);
  }

  private static int sourceKeyLength(TestContext context) {
    return Objects.requireNonNull(
        context
            .jdbc()
            .queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                    + "WHERE table_schema = ? AND table_name = 'account_selected_publication_sources' "
                    + "AND column_name = 'source_key'",
                Integer.class,
                context.schema()));
  }

  private static void assertVersionApplied(TestContext context, String version) {
    assertThat(historyCount(context, version)).isEqualTo(1);
  }

  private static void assertVersionNotApplied(TestContext context, String version) {
    assertThat(historyCount(context, version)).isZero();
  }

  private static int historyCount(TestContext context, String version) {
    return Objects.requireNonNull(
        context
            .jdbc()
            .queryForObject(
                "SELECT count(*) FROM "
                    + quotedSchema(context.schema())
                    + HISTORY_TABLE
                    + " WHERE version = ? AND success",
                Integer.class,
                version));
  }

  private static String regionAnchor() {
    return orderingGuard("regionGeneratorInputs", "regionTemplateId", false);
  }

  private static String regionReplacement() {
    return orderingGuard("regionGeneratorInputs", "regionTemplateId", true);
  }

  private static String spawnAnchor() {
    return orderingGuard("spawnBindingInputs", "bindingTemplateId", false);
  }

  private static String spawnReplacement() {
    return orderingGuard("spawnBindingInputs", "bindingTemplateId", true);
  }

  private static String orderingGuard(String inputArray, String orderKey, boolean coalesceEmpty) {
    String aggregate = "jsonb_agg(entry ORDER BY entry->>'" + orderKey + "')";
    if (coalesceEmpty) aggregate = "coalesce(" + aggregate + ", '[]'::JSONB)";
    return "        OR (SELECT "
        + aggregate
        + " FROM\n"
        + "            jsonb_array_elements(model->'"
        + inputArray
        + "') entry)\n"
        + "            IS DISTINCT FROM model->'"
        + inputArray
        + "'";
  }

  private static String replaceExactlyOnce(String source, String expected, String replacement) {
    int first = source.indexOf(expected);
    if (first < 0 || source.indexOf(expected, first + expected.length()) >= 0) {
      throw new AssertionError("Expected exactly one fixture guard occurrence");
    }
    return source.substring(0, first) + replacement + source.substring(first + expected.length());
  }

  private static int countOccurrences(String source, String value) {
    int count = 0;
    int offset = 0;
    while ((offset = source.indexOf(value, offset)) >= 0) {
      count++;
      offset += value.length();
    }
    return count;
  }

  private static String quotedSchema(String schema) {
    return "\"" + schema + "\"";
  }

  private record TestContext(
      String schema, JdbcTemplate jdbc, DriverManagerDataSource dataSource) {}
}
