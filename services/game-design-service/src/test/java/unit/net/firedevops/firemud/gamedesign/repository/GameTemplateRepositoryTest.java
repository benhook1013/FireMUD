package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class GameTemplateRepositoryTest {
  @Test
  void updateRequiresExistingTenantAndKeepsTenantBindingImmutable() throws Exception {
    try (var connection =
        DriverManager.getConnection("jdbc:h2:mem:game-template-update;DATABASE_TO_LOWER=TRUE")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createTable(dsl);
      dsl.execute(
          "INSERT INTO game_templates (id, tenant_id, name, config, created_at, "
              + "default_runtime_flags_json, template_reference_phase) "
              + "VALUES (1, 'tenant-1', 'Original', '{}', CURRENT_TIMESTAMP, '{}', 'ENFORCED')");
      GameTemplateRepository repository = new GameTemplateRepository(dsl);

      GameTemplate sameTenantUpdate = template(1L, "tenant-1", "Updated");
      GameTemplate updated = repository.save(sameTenantUpdate);

      assertThat(updated.getTenantId()).isEqualTo("tenant-1");
      assertThat(updated.getName()).isEqualTo("Updated");

      GameTemplate crossTenantUpdate = template(1L, "tenant-2", "Overwritten");
      assertThatThrownBy(() -> repository.save(crossTenantUpdate))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("game template not found for tenant");
      assertThat(repository.findByTenantIdAndId("tenant-1", 1L))
          .get()
          .satisfies(
              stored -> {
                assertThat(stored.getTenantId()).isEqualTo("tenant-1");
                assertThat(stored.getName()).isEqualTo("Updated");
              });
      assertThat(repository.findByTenantIdAndId("tenant-2", 1L)).isEmpty();
    }
  }

  private static void createTable(DSLContext dsl) {
    dsl.execute(
        "CREATE TABLE game_templates ("
            + "id BIGINT PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, name VARCHAR(100) NOT NULL, "
            + "description VARCHAR(255), config VARCHAR(100) NOT NULL, default_version_id BIGINT, "
            + "default_script_patch_version VARCHAR(128), default_runtime_flags_json VARCHAR(100), "
            + "template_reference_phase VARCHAR(32), created_at TIMESTAMP)");
  }

  private static GameTemplate template(Long id, String tenantId, String name) {
    GameTemplate template = new GameTemplate();
    template.setId(id);
    template.setTenantId(tenantId);
    template.setName(name);
    template.setConfig("{}");
    template.setDefaultRuntimeFlagsJson("{}");
    template.setTemplateReferencePhase(TemplateReferencePhase.ENFORCED);
    template.setCreatedAt(LocalDateTime.of(2026, 10, 9, 12, 0));
    return template;
  }
}
