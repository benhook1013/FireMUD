package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class V12__enforce_tenant_public_realm_cardinalityTest {
  private static final Pattern MIGRATION_FILE = Pattern.compile("^V([^_]+)__.*\\.sql$");

  @Test
  void keepsAdmissionPointerMigrationsUniqueAndOrderedFromTheV7AuditThroughV13()
      throws IOException, URISyntaxException {
    var auditMigrationUrl =
        getClass()
            .getClassLoader()
            .getResource("db/migration/V7__audit_gameplay_catalog_revision.sql");
    assertThat(auditMigrationUrl).isNotNull();

    Map<MigrationVersion, List<String>> namesByVersion = new TreeMap<>();
    for (String name : migrationNames(auditMigrationUrl)) {
      Matcher matcher = MIGRATION_FILE.matcher(name);
      assertThat(matcher.matches()).as("migration filename %s", name).isTrue();
      MigrationVersion version = MigrationVersion.fromVersion(matcher.group(1));
      namesByVersion.computeIfAbsent(version, ignored -> new java.util.ArrayList<>()).add(name);
    }

    assertThat(namesByVersion.values()).allSatisfy(names -> assertThat(names).hasSize(1));
    List<String> orderedMigrationNames =
        namesByVersion.values().stream().flatMap(List::stream).toList();
    assertThat(orderedMigrationNames)
        .containsSubsequence(
            "V7__audit_gameplay_catalog_revision.sql",
            "V8__repair_legacy_bootstrap_public_realm.sql",
            "V9__initial_admission_bind_owner_ledger.sql",
            "V10__run_owned_initial_launch_identity.sql",
            "V11__gameplay_command_account_uuid.sql",
            "V12__enforce_tenant_public_realm_cardinality.sql",
            "V13__index_gameplay_admission_pointer_event_tenant_lookup.sql");

    String v7 = readMigration("V7__audit_gameplay_catalog_revision.sql");
    String normalizedV7 = normalizeSql(v7);
    assertThat(normalizedV7)
        .contains(
            "ADD COLUMN catalog_revision bigint",
            "ADD COLUMN realm_id uuid",
            "ADD COLUMN playable_state_namespace_id uuid",
            "CHECK (catalog_revision IS NULL OR catalog_revision > 0)",
            "CHECK ((realm_id IS NULL) = (playable_state_namespace_id IS NULL))");
    assertNoDataRewrite(normalizedV7);

    String v8Repair = normalizeSql(readMigration("V8__repair_legacy_bootstrap_public_realm.sql"));
    // V8 repairs only a fully evidenced, unchanged bootstrap pair; it intentionally updates data.
    assertThat(v8Repair)
        .contains(
            "demo.world_slug = 'demo'",
            "demo.realm_slug = 'production'",
            "sandbox.world_slug = 'sandbox'",
            "sandbox.realm_slug = 'production'",
            "demo.last_updated_by = 'system/bootstrap'",
            "sandbox.last_updated_by = 'system/bootstrap'",
            "public_pointer.public_production_realm = true",
            "= 2",
            "UPDATE gameplay_admission_pointer sandbox",
            "SET public_production_realm = false",
            "AND sandbox.catalog_revision = 1",
            "migration:V8:repair-legacy-bootstrap-public-realm:");

    String v9 = normalizeSql(readMigration("V9__initial_admission_bind_owner_ledger.sql"));
    assertThat(v9).contains("CREATE TABLE gameplay_initial_admission_bind_catalog");
    assertNoDataRewrite(v9);

    String v10 = normalizeSql(readMigration("V10__run_owned_initial_launch_identity.sql"));
    assertThat(v10).contains("run_owned_start_request_id");
    assertNoDataRewrite(v10);

    String v11 = normalizeSql(readMigration("V11__gameplay_command_account_uuid.sql"));
    assertThat(v11)
        .contains(
            "ADD COLUMN account_uuid UUID",
            "ADD CONSTRAINT chk_gameplay_command_account_uuid_non_nil",
            "account_uuid IS NULL",
            "account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID");
    assertNoDataRewrite(v11);

    String v12 = normalizeSql(readMigration("V12__enforce_tenant_public_realm_cardinality.sql"));
    assertThat(v12)
        .contains(
            "CREATE UNIQUE INDEX uq_gameplay_admission_pointer_visible_public_tenant",
            "ON gameplay_admission_pointer (tenant_id)",
            "WHERE visible AND public_production_realm");
    assertNoDataRewrite(v12);

    String v13 =
        normalizeSql(
            readMigration("V13__index_gameplay_admission_pointer_event_tenant_lookup.sql"));
    assertThat(v13)
        .isEqualTo(
            "CREATE INDEX idx_gameplay_admission_pointer_event_tenant_world_realm_id "
                + "ON gameplay_admission_pointer_event USING btree "
                + "(tenant_id, world_slug, realm_slug, id DESC);");
    assertNoDataRewrite(v13);
  }

  private String readMigration(String name) throws IOException {
    try (var stream = getClass().getClassLoader().getResourceAsStream("db/migration/" + name)) {
      assertThat(stream).as("migration resource %s", name).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<String> migrationNames(URL migrationResourceUrl)
      throws IOException, URISyntaxException {
    if ("file".equals(migrationResourceUrl.getProtocol())) {
      try (var migrationFiles = Files.list(Path.of(migrationResourceUrl.toURI()).getParent())) {
        return migrationFiles
            .map(Path::getFileName)
            .filter(Objects::nonNull)
            .map(Path::toString)
            .filter(name -> name.endsWith(".sql"))
            .toList();
      }
    }

    if ("jar".equals(migrationResourceUrl.getProtocol())) {
      var connection = (JarURLConnection) migrationResourceUrl.openConnection();
      connection.setUseCaches(false);
      String migrationDirectory =
          connection.getEntryName().substring(0, connection.getEntryName().lastIndexOf('/') + 1);
      try (JarFile jarFile = connection.getJarFile()) {
        return jarFile.stream()
            .map(entry -> entry.getName())
            .filter(name -> name.startsWith(migrationDirectory))
            .map(name -> name.substring(migrationDirectory.length()))
            .filter(name -> !name.isEmpty() && !name.contains("/") && name.endsWith(".sql"))
            .toList();
      }
    }

    throw new IllegalStateException(
        "Unsupported migration resource URL protocol: " + migrationResourceUrl.getProtocol());
  }

  private static String normalizeSql(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  private static void assertNoDataRewrite(String normalizedSql) {
    assertThat(normalizedSql.toUpperCase(Locale.ROOT))
        .doesNotContain("UPDATE ", "DELETE ", "INSERT ", "TRUNCATE", "DROP TABLE", "DROP COLUMN");
  }
}
