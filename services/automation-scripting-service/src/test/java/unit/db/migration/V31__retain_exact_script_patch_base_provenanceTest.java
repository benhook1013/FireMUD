package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class V31__retain_exact_script_patch_base_provenanceTest {
  private static final List<String> RETAINED_TABLE_CHECKS =
      List.of(
          "scripts ck_scripts_positive_base_version",
          "script_event_bindings ck_script_event_bindings_positive_base_version",
          "script_schedule_definitions ck_script_schedule_definitions_positive_base_version",
          "script_patch_readiness_projections ck_script_patch_readiness_positive_base_version",
          "script_work_items ck_script_work_items_positive_patch_base",
          "script_event_ingress_audit ck_script_event_ingress_positive_patch_base",
          "script_event_audit ck_script_event_audit_positive_patch_base",
          "script_schedule_instances ck_script_schedule_instances_positive_patch_base",
          "script_patch_pin_projections ck_script_patch_pin_projections_positive_patch_base");

  @Test
  void retainsExactBaseAcrossOwnerAndReplayRowsWithoutInventingHistoricalEvidence()
      throws IOException {
    String migration = readMigration("V3.1__retain_exact_script_patch_base_provenance.sql");

    String normalized = migration.replaceAll("\\s+", " ").trim();
    assertThat(normalized)
        .contains("CREATE TABLE script_patch_base_bindings")
        .contains("PRIMARY KEY (tenant_id, script_patch_version)")
        .contains("base_version_id BIGINT NOT NULL")
        .contains("ADD COLUMN base_version_id BIGINT")
        .contains("ADD COLUMN script_patch_base_version_id BIGINT")
        .contains("ADD COLUMN pinned_script_patch_base_version_id BIGINT")
        .contains("ck_script_patch_base_bindings_positive_base")
        .contains("ck_script_schedule_instances_positive_patch_base")
        .doesNotContain("UPDATE scripts")
        .doesNotContain("UPDATE script_event_bindings")
        .doesNotContain("UPDATE script_schedule_instances");
  }

  @Test
  void installsEveryRetainedTableCheckAsNotValidInStableOrder() throws IOException {
    String normalized =
        readMigration("V3.1__retain_exact_script_patch_base_provenance.sql")
            .replaceAll("\\s+", " ")
            .trim();

    Matcher matcher =
        Pattern.compile("ALTER TABLE (\\w+) ADD CONSTRAINT (ck_[a-z0-9_]+) CHECK .*? NOT VALID;")
            .matcher(normalized);
    List<String> actual = new ArrayList<>();
    while (matcher.find()) {
      actual.add(matcher.group(1) + " " + matcher.group(2));
    }

    assertThat(actual).containsExactlyElementsOf(RETAINED_TABLE_CHECKS);
    assertJooqIgnoresEach(normalized, "ADD CONSTRAINT");
  }

  @Test
  void validatesEveryRetainedTableCheckInTheSameStableOrder() throws IOException {
    String normalized =
        readMigration("V3.2__validate_exact_script_patch_base_provenance.sql")
            .replaceAll("\\s+", " ")
            .trim();

    Matcher matcher =
        Pattern.compile("ALTER TABLE (\\w+) VALIDATE CONSTRAINT (ck_[a-z0-9_]+);")
            .matcher(normalized);
    List<String> actual = new ArrayList<>();
    while (matcher.find()) {
      actual.add(matcher.group(1) + " " + matcher.group(2));
    }

    assertThat(actual).containsExactlyElementsOf(RETAINED_TABLE_CHECKS);
    assertThat(normalized).doesNotContain("ADD CONSTRAINT");
    assertJooqIgnoresEach(normalized, "VALIDATE CONSTRAINT");
  }

  private static void assertJooqIgnoresEach(String normalized, String operation) {
    for (String retainedTableCheck : RETAINED_TABLE_CHECKS) {
      String[] parts = retainedTableCheck.split(" ");
      String statementStart =
          "/* [jooq ignore start] */ ALTER TABLE " + parts[0] + " " + operation + " " + parts[1];
      Matcher matcher =
          Pattern.compile(
                  Pattern.quote(statementStart)
                      + ".*?; "
                      + Pattern.quote("/* [jooq ignore stop] */"))
              .matcher(normalized);
      assertThat(matcher.find()).as("jOOQ ignore markers for %s", retainedTableCheck).isTrue();
    }
  }

  private String readMigration(String fileName) throws IOException {
    try (var stream = getClass().getClassLoader().getResourceAsStream("db/migration/" + fileName)) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
