package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PublishedReleaseCanonicalIdentityIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final MigrationVersion V35 = MigrationVersion.fromVersion("35");
  private static final MigrationVersion V35_1 = MigrationVersion.fromVersion("35.1");
  private static final MigrationVersion V35_2 = MigrationVersion.fromVersion("35.2");
  private static final Table<?> VERSION = DSL.table(DSL.name("version"));
  private static final Table<?> RELEASE_BUNDLE = DSL.table(DSL.name("published_release_bundle"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<UUID> CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void migrationLeavesRetainedReleaseTupleAndXminUnchangedAndUnmapped() {
    Fixture fixture = fixture(V35);
    Game game = saveGame(fixture, "retained-release-tenant");
    Long retainedVersionId = insertRetainedVersion(fixture.dsl(), game.getTenantId());
    Long retainedBundleId =
        insertRetainedBundle(fixture.dsl(), game.getTenantId(), retainedVersionId);
    Map<String, Object> retainedTupleBefore = retainedTuple(fixture.dsl(), retainedBundleId);
    String retainedXminBefore = bundleXmin(fixture.dsl(), retainedBundleId);

    migrate(fixture.dataSource(), fixture.schema(), V35_1);
    migrate(fixture.dataSource(), fixture.schema(), V35_2);

    assertThat(retainedTuple(fixture.dsl(), retainedBundleId)).isEqualTo(retainedTupleBefore);
    assertThat(bundleXmin(fixture.dsl(), retainedBundleId)).isEqualTo(retainedXminBefore);
    assertThat(
            fixture
                .dsl()
                .select(CANONICAL_TENANT_ID, CANONICAL_VERSION_ID)
                .from(RELEASE_BUNDLE)
                .where(ID.eq(retainedBundleId))
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get(CANONICAL_TENANT_ID)).isNull();
              assertThat(row.get(CANONICAL_VERSION_ID)).isNull();
            });
    assertThatThrownBy(
            () ->
                fixture
                    .releaseBundleRepository()
                    .save(bundle(game.getTenantId(), retainedVersionId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No exact canonical Game Design source association");
    assertThat(bundleCount(fixture.dsl())).isEqualTo(1);
  }

  @Test
  void normalSavePersistsExactVersionAndVerifiedGameIdentityAndReadsBackWithinTransaction() {
    Fixture fixture = fixture(V35_2);
    Game owner = saveGame(fixture, "fresh-release-source");
    Game other = saveGame(fixture, "other-release-source");
    Version version = saveVersion(fixture, owner);

    PublishedReleaseBundle saved =
        fixture.releaseBundleRepository().save(bundle(owner.getTenantId(), version.getId()));
    assertThat(saved.getCanonicalTenantId()).isEqualTo(owner.getCanonicalTenantId());
    assertThat(saved.getCanonicalVersionId()).isEqualTo(version.getCanonicalVersionId());
    assertThat(
            fixture
                .releaseBundleRepository()
                .findByTenantIdAndVersionId(owner.getTenantId(), version.getId())
                .orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(saved);

    Map<String, Object> bundleTupleBeforeSourceMutation =
        releaseBundleTuple(fixture.dsl(), saved.getId());
    String bundleXminBeforeSourceMutation = bundleXmin(fixture.dsl(), saved.getId());
    String versionXminBefore = versionXmin(fixture.dsl(), version.getId());
    String gameXminBefore = gameXmin(fixture.dsl(), owner.getId());
    String otherGameXminBefore = gameXmin(fixture.dsl(), other.getId());
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(VERSION)
                    .set(CANONICAL_TENANT_ID, other.getCanonicalTenantId())
                    .where(ID.eq(version.getId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("canonical Version identity and its tenant source are immutable");

    assertThat(releaseBundleTuple(fixture.dsl(), saved.getId()))
        .isEqualTo(bundleTupleBeforeSourceMutation);
    assertThat(bundleXmin(fixture.dsl(), saved.getId())).isEqualTo(bundleXminBeforeSourceMutation);
    assertThat(bundleCount(fixture.dsl())).isEqualTo(1);
    assertThat(versionXmin(fixture.dsl(), version.getId())).isEqualTo(versionXminBefore);
    assertThat(gameXmin(fixture.dsl(), owner.getId())).isEqualTo(gameXminBefore);
    assertThat(gameXmin(fixture.dsl(), other.getId())).isEqualTo(otherGameXminBefore);
    assertThat(
            fixture
                .releaseBundleRepository()
                .findByTenantIdAndVersionId(owner.getTenantId(), version.getId())
                .orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(saved);
  }

  @Test
  void repositoryNestedTransactionRollsBackWithOuterSpringPublisherTransaction() {
    Fixture fixture = fixture(V35_2);
    Game owner = saveGame(fixture, "outer-release-rollback-owner");
    Version version = saveVersion(fixture, owner);
    String versionXminBefore = versionXmin(fixture.dsl(), version.getId());
    String gameXminBefore = gameXmin(fixture.dsl(), owner.getId());

    assertThatThrownBy(
            () ->
                fixture
                    .transactionTemplate()
                    .execute(
                        status -> {
                          PublishedReleaseBundle saved =
                              fixture
                                  .releaseBundleRepository()
                                  .save(bundle(owner.getTenantId(), version.getId()));
                          assertThat(saved.getCanonicalTenantId())
                              .isEqualTo(owner.getCanonicalTenantId());
                          assertThat(saved.getCanonicalVersionId())
                              .isEqualTo(version.getCanonicalVersionId());
                          Version publishingVersion =
                              fixture
                                  .versionRepository()
                                  .findByTenantIdAndId(owner.getTenantId(), version.getId())
                                  .orElseThrow();
                          publishingVersion.setVersionState(VersionLifecycleState.PUBLISHED);
                          publishingVersion.setVersionStateEpoch(
                              publishingVersion.getVersionStateEpoch() + 1L);
                          fixture.versionRepository().save(publishingVersion);
                          assertThat(bundleCount(fixture.dsl())).isEqualTo(1);
                          assertThat(
                                  fixture
                                      .versionRepository()
                                      .findByTenantIdAndId(owner.getTenantId(), version.getId())
                                      .orElseThrow()
                                      .getVersionState())
                              .isEqualTo(VersionLifecycleState.PUBLISHED);
                          throw new IllegalStateException("simulate failed outer publication");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulate failed outer publication");

    assertThat(bundleCount(fixture.dsl())).isZero();
    assertThat(
            fixture
                .releaseBundleRepository()
                .findByTenantIdAndVersionId(owner.getTenantId(), version.getId()))
        .isEmpty();
    assertThat(versionXmin(fixture.dsl(), version.getId())).isEqualTo(versionXminBefore);
    assertThat(gameXmin(fixture.dsl(), owner.getId())).isEqualTo(gameXminBefore);
    Version exactVersionAfterRollback =
        fixture
            .versionRepository()
            .findByTenantIdAndId(owner.getTenantId(), version.getId())
            .orElseThrow();
    assertThat(exactVersionAfterRollback.getCanonicalTenantId())
        .isEqualTo(owner.getCanonicalTenantId());
    assertThat(exactVersionAfterRollback.getCanonicalVersionId())
        .isEqualTo(version.getCanonicalVersionId());
    assertThat(exactVersionAfterRollback.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(exactVersionAfterRollback.getVersionStateEpoch())
        .isEqualTo(version.getVersionStateEpoch());
    assertThat(exactVersionAfterRollback.getIdentitySourceGameRowId()).isEqualTo(owner.getId());
    assertThat(exactVersionAfterRollback.getIdentitySourceGameTenantKey())
        .isEqualTo(owner.getTenantId());
  }

  @Test
  void deniesWrongTenantNilAndSubstitutedCallerOrDatabaseIdentityWithoutGrowingBundles() {
    Fixture fixture = fixture(V35_2);
    Game owner = saveGame(fixture, "denied-release-owner");
    Game other = saveGame(fixture, "denied-release-other");
    Version version = saveVersion(fixture, owner);

    assertThatThrownBy(
            () ->
                fixture
                    .releaseBundleRepository()
                    .save(bundle(other.getTenantId(), version.getId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No exact canonical Game Design source association");

    PublishedReleaseBundle substitutedTenant = bundle(owner.getTenantId(), version.getId());
    substitutedTenant.setCanonicalTenantId(other.getCanonicalTenantId());
    assertThatThrownBy(() -> fixture.releaseBundleRepository().save(substitutedTenant))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Caller-supplied canonical release identity");

    PublishedReleaseBundle substitutedVersion = bundle(owner.getTenantId(), version.getId());
    substitutedVersion.setCanonicalVersionId(UUID.randomUUID());
    assertThatThrownBy(() -> fixture.releaseBundleRepository().save(substitutedVersion))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Caller-supplied canonical release identity");

    assertThatThrownBy(
            () ->
                insertRawBundle(
                    fixture.dsl(),
                    owner.getTenantId(),
                    version.getId(),
                    NIL_UUID,
                    version.getCanonicalVersionId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("non-nil canonical identity");
    assertThatThrownBy(
            () ->
                insertRawBundle(
                    fixture.dsl(),
                    owner.getTenantId(),
                    version.getId(),
                    other.getCanonicalTenantId(),
                    version.getCanonicalVersionId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("does not match its exact Version and Game source");
    assertThatThrownBy(
            () ->
                insertRawBundle(
                    fixture.dsl(),
                    other.getTenantId(),
                    version.getId(),
                    owner.getCanonicalTenantId(),
                    version.getCanonicalVersionId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("does not match its exact Version and Game source");
    assertThat(bundleCount(fixture.dsl())).isZero();
  }

  @Test
  void bundleIdentityIsImmutableRetainedNullCannotBeBackfilledAndMetadataCannotBeDeleted() {
    Fixture fixture = fixture(V35);
    Game game = saveGame(fixture, "release-retention-owner");
    Long retainedVersionId = insertRetainedVersion(fixture.dsl(), game.getTenantId());
    Long retainedBundleId =
        insertRetainedBundle(fixture.dsl(), game.getTenantId(), retainedVersionId);
    migrate(fixture.dataSource(), fixture.schema(), V35_1);
    migrate(fixture.dataSource(), fixture.schema(), V35_2);

    Version mappedVersion = saveVersion(fixture, game);
    PublishedReleaseBundle mapped =
        fixture.releaseBundleRepository().save(bundle(game.getTenantId(), mappedVersion.getId()));
    Map<String, Object> mappedTupleBefore = releaseBundleTuple(fixture.dsl(), mapped.getId());
    String mappedXminBefore = bundleXmin(fixture.dsl(), mapped.getId());
    Map<String, Object> retainedTupleBefore = releaseBundleTuple(fixture.dsl(), retainedBundleId);
    String retainedXminBefore = bundleXmin(fixture.dsl(), retainedBundleId);

    PublishedReleaseBundle exactReadback =
        fixture
            .releaseBundleRepository()
            .findByTenantIdAndVersionId(game.getTenantId(), mappedVersion.getId())
            .orElseThrow();
    assertThat(exactReadback).usingRecursiveComparison().isEqualTo(mapped);
    assertThatThrownBy(() -> fixture.releaseBundleRepository().save(exactReadback))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Published release bundle is immutable");
    exactReadback.setManifestHash("sha256:attempted-release-metadata-mutation");
    assertThatThrownBy(() -> fixture.releaseBundleRepository().save(exactReadback))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Published release bundle is immutable");

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(RELEASE_BUNDLE)
                    .set(DSL.field(DSL.name("manifest_hash"), String.class), "sha256:forged")
                    .where(ID.eq(mapped.getId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("published release bundle attestation is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE published_release_bundle SET manifest_hash = manifest_hash "
                            + "WHERE id = ?",
                        mapped.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("published release bundle attestation is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(RELEASE_BUNDLE)
                    .set(CANONICAL_TENANT_ID, UUID.randomUUID())
                    .where(ID.eq(mapped.getId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("published release bundle attestation is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(RELEASE_BUNDLE)
                    .set(CANONICAL_VERSION_ID, UUID.randomUUID())
                    .where(ID.eq(mapped.getId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("published release bundle attestation is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(RELEASE_BUNDLE)
                    .set(DSL.field(DSL.name("manifest_hash"), String.class), "sha256:forged")
                    .where(ID.eq(retainedBundleId))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("published release bundle attestation is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(RELEASE_BUNDLE)
                    .set(CANONICAL_TENANT_ID, game.getCanonicalTenantId())
                    .set(CANONICAL_VERSION_ID, mappedVersion.getCanonicalVersionId())
                    .where(ID.eq(retainedBundleId))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("published release bundle attestation is immutable");

    PublishedReleaseBundle retained =
        fixture
            .releaseBundleRepository()
            .findByTenantIdAndVersionId(game.getTenantId(), retainedVersionId)
            .orElseThrow();
    assertThat(retained.getCanonicalTenantId()).isNull();
    assertThat(retained.getCanonicalVersionId()).isNull();
    assertThatThrownBy(() -> fixture.releaseBundleRepository().save(retained))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Published release bundle is immutable");
    retained.setCanonicalTenantId(game.getCanonicalTenantId());
    assertThatThrownBy(() -> fixture.releaseBundleRepository().save(retained))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Published release bundle is immutable");

    assertThat(releaseBundleTuple(fixture.dsl(), mapped.getId())).isEqualTo(mappedTupleBefore);
    assertThat(bundleXmin(fixture.dsl(), mapped.getId())).isEqualTo(mappedXminBefore);
    assertThat(releaseBundleTuple(fixture.dsl(), retainedBundleId)).isEqualTo(retainedTupleBefore);
    assertThat(bundleXmin(fixture.dsl(), retainedBundleId)).isEqualTo(retainedXminBefore);
    assertThat(
            fixture
                .releaseBundleRepository()
                .findByTenantIdAndVersionId(game.getTenantId(), mappedVersion.getId())
                .orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(mapped);
    PublishedReleaseBundle retainedAfterAttempts =
        fixture
            .releaseBundleRepository()
            .findByTenantIdAndVersionId(game.getTenantId(), retainedVersionId)
            .orElseThrow();
    assertThat(retainedAfterAttempts.getCanonicalTenantId()).isNull();
    assertThat(retainedAfterAttempts.getCanonicalVersionId()).isNull();
    assertThat(retainedAfterAttempts.getManifestHash()).isEqualTo("sha256:retained");

    assertThatThrownBy(
            () -> fixture.dsl().deleteFrom(RELEASE_BUNDLE).where(ID.eq(mapped.getId())).execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("published release bundle metadata is retained");
    assertThatThrownBy(
            () -> fixture.dsl().execute("TRUNCATE TABLE published_release_bundle CASCADE"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("metadata cannot be truncated");
    assertThat(bundleCount(fixture.dsl())).isEqualTo(2);
  }

  private Fixture fixture(MigrationVersion target) {
    String schema = "game_design_release_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, target);
    return fixtureFromMigratedSchema(schema, dataSource);
  }

  private Fixture fixtureFromMigratedSchema(String schema, DriverManagerDataSource dataSource) {
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    DefaultConfiguration jooqConfiguration = new DefaultConfiguration();
    jooqConfiguration.set(SQLDialect.POSTGRES);
    jooqConfiguration.set(
        new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
    jooqConfiguration.set(new SpringTransactionProvider(transactionManager));
    DSLContext dsl = DSL.using(jooqConfiguration);
    GameRepository gameRepository = new GameRepository(dsl);
    PostgresProperties postgresProperties = new PostgresProperties();
    postgresProperties.setSchema(schema);
    VersionRepository versionRepository = new VersionRepository(dsl, postgresProperties);
    PublishedReleaseBundleRepository releaseBundleRepository =
        new PublishedReleaseBundleRepository(dsl);
    return new Fixture(
        schema,
        dataSource,
        dsl,
        transactionTemplate,
        gameRepository,
        versionRepository,
        releaseBundleRepository);
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private void migrate(DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private Game saveGame(Fixture fixture, String tenantKey) {
    Game game = new Game();
    game.setTenantId(tenantKey);
    game.setName("Published Release Identity Owner");
    game.setDescription("Exact immutable Game source association");
    return fixture.transactionTemplate().execute(status -> fixture.gameRepository().save(game));
  }

  private Version saveVersion(Fixture fixture, Game game) {
    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setVersionNumber(1);
    version.setNotes("fresh release source");
    return fixture
        .transactionTemplate()
        .execute(status -> fixture.versionRepository().save(version));
  }

  private Long insertRetainedVersion(DSLContext dsl, String tenantId) {
    return dsl.resultQuery(
            "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, "
                + "script_patch_version, base_version_id, is_script_only, notes) "
                + "VALUES (?, 4, 'FAILED', 3, NULL, NULL, FALSE, 'retained release source') "
                + "RETURNING id",
            tenantId)
        .fetchOne(0, Long.class);
  }

  private Long insertRetainedBundle(DSLContext dsl, String tenantId, Long versionId) {
    return dsl.resultQuery(
            "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, "
                + "attestation_schema_version, publish_workflow_id, manifest_hash, "
                + "generation_config_revision, required_manifest_asset_keys_json, "
                + "participant_digests_json, command_definitions_json, script_only, "
                + "script_patch_version) VALUES (?, ?, 4, 'v1', 'retained-workflow', "
                + "'sha256:retained', 'retained-config', '[\"asset-a\"]', '[]', '[]', FALSE, NULL) "
                + "RETURNING id",
            tenantId,
            versionId)
        .fetchOne(0, Long.class);
  }

  private PublishedReleaseBundle bundle(String tenantId, Long versionId) {
    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setTenantId(tenantId);
    bundle.setVersionId(versionId);
    bundle.setVersionNumber(1);
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId("workflow-" + versionId);
    bundle.setManifestHash("sha256:manifest");
    bundle.setGenerationConfigRevision("generation-1");
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setParticipantDigestsJson("[]");
    bundle.setCommandDefinitionsJson("[]");
    return bundle;
  }

  private void insertRawBundle(
      DSLContext dsl,
      String tenantId,
      Long versionId,
      UUID canonicalTenantId,
      UUID canonicalVersionId) {
    dsl.execute(
        "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, "
            + "attestation_schema_version, publish_workflow_id, manifest_hash, "
            + "generation_config_revision, required_manifest_asset_keys_json, "
            + "participant_digests_json, command_definitions_json, script_only, "
            + "canonical_tenant_id, canonical_version_id) "
            + "VALUES (?, ?, 1, 'v1', 'direct-write-test', 'sha256:raw', 'generation-1', "
            + "'[]', '[]', '[]', FALSE, ?, ?)",
        tenantId,
        versionId,
        canonicalTenantId,
        canonicalVersionId);
  }

  private long bundleCount(DSLContext dsl) {
    return dsl.fetchCount(RELEASE_BUNDLE);
  }

  private Map<String, Object> retainedTuple(DSLContext dsl, Long bundleId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id, tenant_id, version_id, version_number, attestation_schema_version, "
                    + "publish_workflow_id, manifest_hash, required_manifest_asset_keys_json, "
                    + "script_only, script_patch_version, published_at, "
                    + "generation_config_revision, participant_digests_json, "
                    + "command_definitions_json FROM published_release_bundle WHERE id = ?",
                bundleId),
            "Retained release bundle query returned no row")
        .intoMap();
  }

  private Map<String, Object> releaseBundleTuple(DSLContext dsl, Long bundleId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id, tenant_id, version_id, canonical_tenant_id, canonical_version_id, "
                    + "version_number, attestation_schema_version, publish_workflow_id, "
                    + "manifest_hash, generation_config_revision, "
                    + "required_manifest_asset_keys_json, participant_digests_json, "
                    + "command_definitions_json, script_only, script_patch_version, published_at "
                    + "FROM published_release_bundle WHERE id = ?",
                bundleId),
            "Published release bundle tuple query returned no row")
        .intoMap();
  }

  private String bundleXmin(DSLContext dsl, Long bundleId) {
    var row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS xmin FROM published_release_bundle WHERE id = ?", bundleId),
            "Published release bundle xmin query returned no row");
    return Objects.requireNonNull(
        row.get("xmin", String.class), "Published release bundle xmin readback returned null");
  }

  private String versionXmin(DSLContext dsl, Long versionId) {
    var row =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT xmin::text AS xmin FROM version WHERE id = ?", versionId),
            "Version xmin query returned no row");
    return Objects.requireNonNull(row.get("xmin", String.class));
  }

  private String gameXmin(DSLContext dsl, Long gameId) {
    var row =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT xmin::text AS xmin FROM game WHERE id = ?", gameId),
            "Game xmin query returned no row");
    return Objects.requireNonNull(row.get("xmin", String.class));
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transactionTemplate,
      GameRepository gameRepository,
      VersionRepository versionRepository,
      PublishedReleaseBundleRepository releaseBundleRepository) {}
}
