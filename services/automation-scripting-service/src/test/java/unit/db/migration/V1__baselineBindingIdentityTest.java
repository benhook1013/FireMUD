package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V1__baselineBindingIdentityTest {
  @Test
  void bindingUniquenessAndResolutionOrderRetainStableBindingIdentity() throws IOException {
    String migration;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/V1__baseline.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    int uniqueStart = migration.indexOf("CONSTRAINT uq_script_event_binding UNIQUE");
    int uniqueEnd = migration.indexOf(")", uniqueStart);
    assertThat(uniqueStart).isGreaterThanOrEqualTo(0);
    assertThat(uniqueEnd).isGreaterThan(uniqueStart);
    String uniqueColumns = migration.substring(uniqueStart, uniqueEnd).replaceAll("\\s+", " ");
    assertThat(uniqueColumns).contains("script_id, binding_id, target_scope_type");

    int indexStart = migration.indexOf("CREATE INDEX idx_script_event_bindings_resolution");
    int indexEnd = migration.indexOf(");", indexStart);
    assertThat(indexStart).isGreaterThanOrEqualTo(0);
    assertThat(indexEnd).isGreaterThan(indexStart);
    String indexColumns = migration.substring(indexStart, indexEnd).replaceAll("\\s+", " ");
    assertThat(indexColumns).contains("script_id, binding_id, id");
  }
}
