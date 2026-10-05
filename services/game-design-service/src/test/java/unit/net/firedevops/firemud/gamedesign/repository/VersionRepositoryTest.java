package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class VersionRepositoryTest {
  @Test
  void entityDigestBaselineVersionLockUsesConfiguredOwnerSchema() {
    AtomicReference<String> sql = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sql.set(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {new MockResult(0, resultDsl.newResult())};
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    PostgresProperties postgres = new PostgresProperties();
    postgres.setSchema("isolated_game_design_owner");

    VersionRepository repository = new VersionRepository(dsl, postgres);

    assertThat(repository.findByTenantIdAndIdForEntityDigestBaselineMigration("tenant", 17L))
        .isEmpty();
    assertThat(sql.get())
        .contains(
            "\"isolated_game_design_owner\".\"lock_version_for_entity_digest_baseline_migration\"");
  }

  @Test
  void entityDigestBaselineVersionLockRejectsMissingOrBlankOwnerSchemaWithoutQuery() {
    AtomicInteger queryCount = new AtomicInteger();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          queryCount.incrementAndGet();
          return new MockResult[] {new MockResult(0, resultDsl.newResult())};
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);

    for (String schema : new String[] {null, "   "}) {
      PostgresProperties postgres = new PostgresProperties();
      postgres.setSchema(schema);
      VersionRepository repository = new VersionRepository(dsl, postgres);

      assertThatThrownBy(
              () -> repository.findByTenantIdAndIdForEntityDigestBaselineMigration("tenant", 17L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Game Design PostgreSQL schema must be configured for the "
                  + "Entity digest baseline Version lock");
    }

    assertThat(queryCount.get()).isZero();
  }

  @Test
  void scriptPatchPublicationLookupUsesExactRetainedScopeAndLeavesAmbiguityVisible()
      throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:version-repository-lookup")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      dsl.execute(
          "CREATE TABLE \"version\" ("
              + "\"id\" BIGINT PRIMARY KEY, "
              + "\"tenant_id\" VARCHAR(36) NOT NULL, "
              + "\"version_number\" INT NOT NULL, "
              + "\"version_state\" VARCHAR(32) NOT NULL, "
              + "\"version_state_epoch\" BIGINT NOT NULL, "
              + "\"script_patch_version\" VARCHAR(128), "
              + "\"base_version_id\" BIGINT, "
              + "\"is_script_only\" BOOLEAN NOT NULL, "
              + "\"notes\" VARCHAR(500), "
              + "\"created_at\" TIMESTAMP, "
              + "\"updated_at\" TIMESTAMP)");

      insertVersion(dsl, 1L, "tenant-1", 10, VersionLifecycleState.PUBLISHED, 7L, "patch-1", true);
      insertVersion(dsl, 2L, "tenant-1", 11, VersionLifecycleState.ACTIVE, 7L, "patch-1", true);
      insertVersion(dsl, 3L, "tenant-1", 12, VersionLifecycleState.RETIRED, 7L, "patch-1", true);
      insertVersion(dsl, 4L, "tenant-1", 13, VersionLifecycleState.PUBLISHED, 7L, "patch-1", true);
      insertVersion(dsl, 5L, "tenant-1", 14, VersionLifecycleState.DRAFT, 7L, "patch-1", true);
      insertVersion(dsl, 6L, "tenant-1", 15, VersionLifecycleState.FAILED, 7L, "patch-1", true);
      insertVersion(dsl, 7L, "tenant-2", 16, VersionLifecycleState.PUBLISHED, 7L, "patch-1", true);
      insertVersion(dsl, 8L, "tenant-1", 17, VersionLifecycleState.PUBLISHED, 8L, "patch-1", true);
      insertVersion(dsl, 9L, "tenant-1", 18, VersionLifecycleState.PUBLISHED, 7L, "patch-2", true);
      insertVersion(
          dsl, 10L, "tenant-1", 19, VersionLifecycleState.PUBLISHED, 7L, "patch-1", false);

      List<Version> candidates =
          new VersionRepository(dsl, new PostgresProperties())
              .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndPublishedScriptOnly(
                  "tenant-1", 7L, "patch-1");

      assertThat(candidates).hasSize(4);
      assertThat(candidates).extracting(Version::getId).containsExactly(4L, 3L, 2L, 1L);
      assertThat(candidates)
          .extracting(Version::getVersionState)
          .containsExactly(
              VersionLifecycleState.PUBLISHED,
              VersionLifecycleState.RETIRED,
              VersionLifecycleState.ACTIVE,
              VersionLifecycleState.PUBLISHED);
    }
  }

  private static void insertVersion(
      DSLContext dsl,
      long id,
      String tenantId,
      int versionNumber,
      VersionLifecycleState state,
      long baseVersionId,
      String scriptPatchVersion,
      boolean scriptOnly) {
    LocalDateTime timestamp = LocalDateTime.of(2026, 9, 28, 12, 30).plusSeconds(id);
    dsl.execute(
        "INSERT INTO \"version\" ("
            + "\"id\", \"tenant_id\", \"version_number\", \"version_state\", "
            + "\"version_state_epoch\", \"script_patch_version\", \"base_version_id\", "
            + "\"is_script_only\", \"notes\", \"created_at\", \"updated_at\") "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        tenantId,
        versionNumber,
        state.name(),
        1L,
        scriptPatchVersion,
        baseVersionId,
        scriptOnly,
        "notes-" + id,
        timestamp,
        timestamp);
  }
}
