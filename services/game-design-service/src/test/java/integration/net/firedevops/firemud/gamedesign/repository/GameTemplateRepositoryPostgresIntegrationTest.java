package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Owner-local SQL proof; controlled pre-phase rows supply no publication authority. */
class GameTemplateRepositoryPostgresIntegrationTest {
  private static final GameDesignPostgresIntegrationFixture POSTGRES =
      new GameDesignPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void savesOnlyTheExactTenantRowAndPreservesRowsOnForeignOrMissingIdentity() throws Exception {
    String schema = "game_design_template_owner_" + UUID.randomUUID().toString().replace("-", "");
    var source = POSTGRES.dataSource();
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

    try (Connection connection = source.getConnection()) {
      connection.setSchema(schema);
      DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
      GameRepository games = new GameRepository(dsl);
      for (String tenant : List.of("tenant-a", "tenant-b")) {
        Game game = new Game();
        game.setTenantId(tenant);
        game.setName(tenant);
        games.save(game);
      }
      // Deliberately exercise retained/pre-phase rows, where SQL's later phase guard is absent.
      assertThat(dsl.fetchCount(DSL.table("game_template_reference_phase"))).isZero();
      GameTemplateRepository repository = new GameTemplateRepository(dsl);
      GameTemplate first = repository.save(template("tenant-a", "Original A"));
      GameTemplate second = repository.save(template("tenant-b", "Original B"));
      List<String> before = rows(dsl);

      GameTemplate foreign = template("tenant-b", "Changed foreign row");
      foreign.setId(first.getId());
      Throwable foreignRefusal = catchThrowable(() -> repository.save(foreign));

      assertThat(rows(dsl))
          .as("foreign save preserves complete rows including xmin")
          .isEqualTo(before);
      assertThat(foreignRefusal)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("game template not found for tenant");
      assertThat(repository.findByTenantIdAndId("tenant-b", first.getId())).isEmpty();

      GameTemplate missing = template("tenant-a", "Missing row");
      missing.setId(Long.MAX_VALUE);
      Throwable missingRefusal = catchThrowable(() -> repository.save(missing));
      assertThat(rows(dsl))
          .as("missing save preserves complete rows including xmin")
          .isEqualTo(before);
      assertThat(missingRefusal)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("game template not found for tenant");

      first.setName("Owned update");
      GameTemplate updated = repository.save(first);
      assertThat(updated.getId()).isEqualTo(first.getId());
      assertThat(updated.getTenantId()).isEqualTo("tenant-a");
      assertThat(updated.getName()).isEqualTo("Owned update");
      assertThat(repository.findByTenantIdAndId("tenant-b", second.getId()).orElseThrow())
          .usingRecursiveComparison()
          .isEqualTo(second);
    }
  }

  private static GameTemplate template(String tenant, String name) {
    GameTemplate template = new GameTemplate();
    template.setTenantId(tenant);
    template.setName(name);
    template.setConfig("{}");
    template.setDefaultRuntimeFlagsJson("{}");
    template.setTemplateReferencePhase(TemplateReferencePhase.LEGACY);
    return template;
  }

  private static List<String> rows(DSLContext dsl) {
    return dsl.fetch(
            "SELECT row_to_json(t)::TEXT || ':' || xmin::TEXT AS snapshot "
                + "FROM game_templates t ORDER BY id")
        .getValues("snapshot", String.class);
  }
}
