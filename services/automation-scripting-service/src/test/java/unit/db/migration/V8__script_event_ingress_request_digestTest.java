package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V8__script_event_ingress_request_digestTest {
  @Test
  void addsBoundedDigestColumnAndLeavesLegacyRowsUnbound() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V8__script_event_ingress_request_digest.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains(
            "ADD COLUMN request_digest VARCHAR(64) NOT NULL DEFAULT ''",
            "request_digest = '' OR request_digest ~ '^[0-9a-f]{64}$'",
            "DROP INDEX uq_script_event_ingress_audit_runtime_identity",
            "script_pin_epoch,",
            "script_event_id,")
        .doesNotContain("script_pin_control_plane_request_id,")
        .contains("Existing pre-v1 rows remain explicitly unbound");
  }
}
