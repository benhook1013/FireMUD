package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V4__script_pin_epoch_work_items_and_schedulesTest {
  @Test
  void normalizesNullableUnpinnedRequestIdInWorkItemIdentity() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/migration/V4__script_pin_epoch_work_items_and_schedules.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains(
            "CREATE UNIQUE INDEX uq_script_work_item_trigger_identity ON script_work_items",
            "script_pin_control_plane_request_id,",
            "WHERE script_pin_epoch > 0",
            "CREATE UNIQUE INDEX uq_script_work_item_trigger_identity_unpinned",
            "WHERE script_pin_epoch = 0",
            "script_pin_control_plane_request_id IS NULL")
        .doesNotContain("ADD CONSTRAINT uq_script_work_item_trigger_identity UNIQUE");

    int unpinnedStart =
        migration.indexOf("CREATE UNIQUE INDEX uq_script_work_item_trigger_identity_unpinned");
    assertThat(unpinnedStart).isGreaterThanOrEqualTo(0);
    String unpinnedIndex =
        migration.substring(unpinnedStart, migration.indexOf(") WHERE", unpinnedStart));
    assertThat(unpinnedIndex)
        .contains("script_event_id", "dry_run")
        .doesNotContain("script_pin_control_plane_request_id");
  }
}
