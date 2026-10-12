package integration.net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.data.TestDataSeeder;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TestDataSeederIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void freshBaselineSeedAndRestartReuseActualOwnerIdentityAndOpaqueBundle() {
    Fixture fixture = fixture(null);
    fixture.migrate(null);
    fixture.seed();
    var game = fixture.games().findByTenantId("1");
    var version = fixture.versions().findByTenantIdAndVersionNumber("1", 1).orElseThrow();
    var bundle = fixture.bundles().findByTenantIdAndVersionId("1", version.getId()).orElseThrow();
    assertThat(version.getCanonicalTenantId()).isEqualTo(game.getCanonicalTenantId());
    assertThat(version.getCanonicalVersionId()).isNotNull().isNotEqualTo(new UUID(0L, 0L));
    assertThat(version.getIdentitySourceGameRowId()).isEqualTo(game.getId());
    assertThat(version.getIdentitySourceGameTenantKey()).isEqualTo(game.getTenantId());
    assertThat(version.getIdentitySourceProvenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(bundle.getCanonicalTenantId()).isEqualTo(game.getCanonicalTenantId());
    assertThat(bundle.getCanonicalVersionId()).isEqualTo(version.getCanonicalVersionId());
    assertThat(bundle.getAttestationSchemaVersion()).isEqualTo("v1");
    assertThat(bundle.getPublishedReleaseBundleRef()).isNotBlank();
    String xmin = fixture.bundleXmin();

    fixture.seed();

    assertThat(fixture.bundleXmin()).isEqualTo(xmin);
    assertThat(fixture.bundles().findByTenantIdAndVersionId("1", version.getId()).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(bundle);
    assertThat(
            fixture
                .versions()
                .findByTenantIdAndVersionNumber("1", 1)
                .orElseThrow()
                .getCanonicalVersionId())
        .isEqualTo(version.getCanonicalVersionId());
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_authored_world_source_operations")))
        .isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_authored_world_source_deliveries")))
        .isZero();
    assertRetainedDescriptorInsertAndRead(fixture, version.getId(), bundle.getId());
    assertThatThrownBy(() -> fixture.bundles().save(bundle)).hasMessageContaining("immutable");
  }

  @Test
  void baselineUpgradeRetainsUnboundVersionBundleAndDescriptorWithoutIdentityBackfill() {
    Fixture fixture = fixture("30");
    fixture
        .dsl()
        .execute(
            "INSERT INTO game (tenant_id, name, description) VALUES ('1', 'Demo Game', 'Seed game')");
    Long versionId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, notes) VALUES ('1', 1, 'PUBLISHED', 1, false, 'Seed published version') RETURNING id")
            .get("id", Long.class);
    Long bundleId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, attestation_schema_version, publish_workflow_id, manifest_hash, generation_config_revision, required_manifest_asset_keys_json, participant_digests_json, command_definitions_json, script_only) VALUES ('1', ?, 1, 'v1', 'demo-seed-publish', 'demo-manifest-hash', 'genrev:demo', '[]', '[]', '[]', false) RETURNING id",
                versionId)
            .get("id", Long.class);
    fixture.migrate(null);
    String xmin = fixture.bundleXmin();
    fixture.seed();
    fixture.seed();
    var version = fixture.versions().findByTenantIdAndId("1", versionId).orElseThrow();
    var bundle = fixture.bundles().findByTenantIdAndVersionId("1", versionId).orElseThrow();
    assertThat(version.getCanonicalVersionId()).isNull();
    assertThat(version.getCanonicalTenantId()).isNull();
    assertThat(bundle.getCanonicalTenantId()).isNull();
    assertThat(bundle.getCanonicalVersionId()).isNull();
    assertThat(bundle.getPublishedReleaseBundleRef()).isNull();
    assertThat(bundle.getId()).isEqualTo(bundleId);
    assertThat(fixture.bundleXmin()).isEqualTo(xmin);
    assertRetainedDescriptorInsertAndRead(fixture, versionId, bundleId);
  }

  @Test
  void retainedVersionWithoutBundleRefusesAndRollsBackAllSeedWrites() {
    Fixture fixture = fixture("30");
    fixture
        .dsl()
        .execute(
            "INSERT INTO game (tenant_id, name, description) VALUES ('1', 'Retained Game', 'Retained description')");
    Long versionId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, notes) VALUES ('1', 1, 'FAILED', 2, false, 'retained-unbundled') RETURNING id")
            .get("id", Long.class);
    fixture.migrate(null);
    var originalGame = fixture.games().findByTenantId("1");
    var originalVersion = fixture.versions().findByTenantIdAndId("1", versionId).orElseThrow();

    assertThatThrownBy(fixture::seed)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No exact canonical Game Design source association");

    assertThat(fixture.games().findByTenantId("1"))
        .usingRecursiveComparison()
        .isEqualTo(originalGame);
    assertThat(fixture.versions().findByTenantIdAndId("1", versionId).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(originalVersion);
    assertThat(originalVersion.getCanonicalVersionId()).isNull();
    assertThat(originalVersion.getCanonicalTenantId()).isNull();
    for (String table :
        java.util.List.of(
            "published_release_bundle",
            "game_templates",
            "revision",
            "version_asset_artifact",
            "game_design_authored_world_source_operations",
            "game_design_authored_world_source_deliveries")) {
      assertThat(fixture.dsl().fetchCount(DSL.table(table))).as(table).isZero();
    }
  }

  private void assertRetainedDescriptorInsertAndRead(
      Fixture fixture, Long versionId, Long bundleId) {
    Long templateId =
        fixture
            .dsl()
            .fetchSingle("SELECT id FROM game_templates WHERE tenant_id = '1'")
            .get("id", Long.class);
    var repository = new LaunchDescriptorRepository(fixture.dsl());
    var descriptor = new LaunchDescriptor();
    descriptor.setLaunchDescriptorId("retained-v1-descriptor");
    descriptor.setTenantId("1");
    descriptor.setGameTemplateId(templateId);
    descriptor.setControlPlaneRequestId("baseline-request");
    descriptor.setRequestHash("baseline-request-hash");
    descriptor.setVersionId(versionId);
    descriptor.setRuntimeFlagsJson("{}");
    descriptor.setGenerationConfigRevision("genrev:demo");
    descriptor.setVersionStateEpoch(1L);
    descriptor.setReleaseBundleId(bundleId);
    descriptor.setPublishedReleaseBundleRef("prb:1:" + versionId + ":" + bundleId);
    var saved = repository.save(descriptor);
    assertThat(saved.getDescriptorSchemaVersion()).isNull();
    assertThat(saved.getCanonicalTenantId()).isNull();
    assertThat(saved.getAuthoredWorldSourceOperationId()).isNull();
    assertThat(
            repository
                .findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
                    "1", templateId, "baseline-request")
                .orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(saved);
    assertThatThrownBy(() -> repository.save(saved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Launch descriptors are immutable");
  }

  private Fixture fixture(String target) {
    String schema = "foundation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    Fixture fixture =
        new Fixture(
            schema,
            dataSource,
            dsl,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    if (target != null) fixture.migrate(target);
    return fixture;
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transactions) {
    void migrate(String target) {
      var configuration =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(schema)
              .defaultSchema(schema)
              .placeholders(Map.of("serviceSchema", schema))
              .locations("classpath:db/migration");
      if (target != null) configuration.target(target);
      configuration.load().migrate();
    }

    GameRepository games() {
      return new GameRepository(dsl);
    }

    VersionRepository versions() {
      return new VersionRepository(dsl);
    }

    PublishedReleaseBundleRepository bundles() {
      return new PublishedReleaseBundleRepository(dsl);
    }

    void seed() {
      TestDataSeeder seeder =
          new TestDataSeeder(
              games(),
              new GameTemplateRepository(dsl),
              new RevisionRepository(dsl),
              versions(),
              bundles(),
              new VersionAssetArtifactRepository(dsl));
      transactions.executeWithoutResult(
          status -> seeder.run(new DefaultApplicationArguments(new String[] {})));
    }

    String bundleXmin() {
      return dsl.fetchSingle(
              "SELECT xmin::text AS xmin FROM published_release_bundle WHERE tenant_id = '1'")
          .get("xmin", String.class);
    }
  }
}
