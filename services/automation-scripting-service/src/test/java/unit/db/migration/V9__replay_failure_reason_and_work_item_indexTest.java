package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V9__replay_failure_reason_and_work_item_indexTest {
  @Test
  void preservesFailureReasonAndIndexesTenantQualifiedWorkItemLookups() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V9__replay_failure_reason_and_work_item_index.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("ADD COLUMN failure_reason VARCHAR(256) NOT NULL DEFAULT ''")
        .contains("ADD COLUMN requested_work_item_id BIGINT")
        .contains("ALTER COLUMN work_item_id DROP NOT NULL")
        .contains("UNIQUE (replay_request_id, requested_work_item_id)")
        .contains("CREATE INDEX idx_script_dead_letter_replay_results_work_item")
        .contains("(tenant_id, work_item_id)");
  }
}
