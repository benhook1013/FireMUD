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

/** Physical V127 installation proof for both known V126 inventory guard shapes. */
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
