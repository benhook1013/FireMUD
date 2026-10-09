package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
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
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class PublishedReleaseCanonicalIdentityIntegrationTest {
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String OTHER_MANIFEST_HASH = "sha256:" + "b".repeat(64);
  private static final String GENERATION_CONFIG_REVISION = "generation-1";
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final MigrationVersion V35 = MigrationVersion.fromVersion("35");
  private static final MigrationVersion V35_1 = MigrationVersion.fromVersion("35.1");
  private static final MigrationVersion V35_2 = MigrationVersion.fromVersion("35.2");
  private static final MigrationVersion V36 = MigrationVersion.fromVersion("36");
  private static final MigrationVersion V38 = MigrationVersion.fromVersion("38");
  private static final Table<?> VERSION = DSL.table(DSL.name("version"));
  private static final Table<?> RELEASE_BUNDLE = DSL.table(DSL.name("published_release_bundle"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("version_id"), Long.class);
  private static final Field<String> PUBLISHED_RELEASE_BUNDLE_REF =
      DSL.field(DSL.name("published_release_bundle_ref"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<UUID> CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
  private static final Field<Integer> MANIFEST_SCHEMA_VERSION =
      DSL.field(DSL.name("manifest_schema_version"), Integer.class);
  private static final Field<String> ARTIFACT_DIGESTS_JSON =
      DSL.field(DSL.name("artifact_digests_json"), String.class);
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
    migrate(fixture.dataSource(), fixture.schema(), V36);

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
    assertThat(
            fixture
                .dsl()
                .select(PUBLISHED_RELEASE_BUNDLE_REF)
                .from(RELEASE_BUNDLE)
                .where(ID.eq(retainedBundleId))
                .fetchOne(PUBLISHED_RELEASE_BUNDLE_REF))
        .isNull();

    // V38 only adds nullable proof columns; it must not rewrite or invent proof for this row.
    migrate(fixture.dataSource(), fixture.schema(), V38);
    assertThat(retainedTuple(fixture.dsl(), retainedBundleId)).isEqualTo(retainedTupleBefore);
    assertThat(bundleXmin(fixture.dsl(), retainedBundleId)).isEqualTo(retainedXminBefore);
    assertThat(
            fixture
                .dsl()
                .select(MANIFEST_SCHEMA_VERSION, ARTIFACT_DIGESTS_JSON)
                .from(RELEASE_BUNDLE)
                .where(ID.eq(retainedBundleId))
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get(MANIFEST_SCHEMA_VERSION)).isNull();
              assertThat(row.get(ARTIFACT_DIGESTS_JSON)).isNull();
            });
    migrate(fixture.dataSource(), fixture.schema(), null);
    assertThat(retainedTuple(fixture.dsl(), retainedBundleId)).isEqualTo(retainedTupleBefore);
    assertThat(bundleXmin(fixture.dsl(), retainedBundleId)).isEqualTo(retainedXminBefore);
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT world_published_start_location_evidence_json FROM published_release_bundle WHERE id = ?",
                            retainedBundleId))
                .get(0))
        .isNull();
    PublishedReleaseBundle retained =
        fixture
            .releaseBundleRepository()
            .findByTenantIdAndVersionId(game.getTenantId(), retainedVersionId)
            .orElseThrow();
    assertThat(retained.getCanonicalTenantId()).isNull();
    assertThat(retained.getCanonicalVersionId()).isNull();
    assertThat(retained.getPublishedReleaseBundleRef()).isNull();
    assertThat(retained.getManifestSchemaVersion()).isNull();
    assertThat(retained.getArtifactDigestsJson()).isNull();
    assertThatThrownBy(() -> fixture.releaseBundleRepository().save(retained))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Published release bundle is immutable");
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
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "fresh-release-source");
    Game other = saveGame(fixture, "other-release-source");
    Version version = saveVersion(fixture, owner);

    PublishedReleaseBundle saved =
        fixture.releaseBundleRepository().save(bundle(owner.getTenantId(), version.getId()));
    assertThat(saved.getCanonicalTenantId()).isEqualTo(owner.getCanonicalTenantId());
    assertThat(saved.getCanonicalVersionId()).isEqualTo(version.getCanonicalVersionId());
    assertThat(saved.getPublishedReleaseBundleRef()).isNotBlank();
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
    Fixture fixture = fixture(null);
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
                          assertThat(saved.getPublishedReleaseBundleRef()).isNotBlank();
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
    Fixture fixture = fixture(null);
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
    Map<String, Object> retainedTupleBeforeIdentityMigration =
        retainedTuple(fixture.dsl(), retainedBundleId);
    String retainedXminBeforeIdentityMigration = bundleXmin(fixture.dsl(), retainedBundleId);
    migrate(fixture.dataSource(), fixture.schema(), V35_1);
    migrate(fixture.dataSource(), fixture.schema(), V35_2);

    Map<String, Object> retainedTupleBeforeV36 = retainedTuple(fixture.dsl(), retainedBundleId);
    String retainedXminBeforeV36 = bundleXmin(fixture.dsl(), retainedBundleId);
    assertThat(retainedTuple(fixture.dsl(), retainedBundleId))
        .isEqualTo(retainedTupleBeforeIdentityMigration);
    assertThat(bundleXmin(fixture.dsl(), retainedBundleId))
        .isEqualTo(retainedXminBeforeIdentityMigration);
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
    migrate(fixture.dataSource(), fixture.schema(), V36);
    assertThat(retainedTuple(fixture.dsl(), retainedBundleId)).isEqualTo(retainedTupleBeforeV36);
    assertThat(bundleXmin(fixture.dsl(), retainedBundleId)).isEqualTo(retainedXminBeforeV36);
    assertThat(
            fixture
                .dsl()
                .select(PUBLISHED_RELEASE_BUNDLE_REF)
                .from(RELEASE_BUNDLE)
                .where(ID.eq(retainedBundleId))
                .fetchOne(PUBLISHED_RELEASE_BUNDLE_REF))
        .isNull();

    Map<String, Object> retainedTupleBeforeV38 = retainedTuple(fixture.dsl(), retainedBundleId);
    String retainedXminBeforeV38 = bundleXmin(fixture.dsl(), retainedBundleId);
    migrate(fixture.dataSource(), fixture.schema(), V38);
    assertThat(retainedTuple(fixture.dsl(), retainedBundleId)).isEqualTo(retainedTupleBeforeV38);
    assertThat(bundleXmin(fixture.dsl(), retainedBundleId)).isEqualTo(retainedXminBeforeV38);
    assertThat(
            fixture
                .dsl()
                .select(
                    CANONICAL_TENANT_ID,
                    CANONICAL_VERSION_ID,
                    PUBLISHED_RELEASE_BUNDLE_REF,
                    MANIFEST_SCHEMA_VERSION,
                    ARTIFACT_DIGESTS_JSON)
                .from(RELEASE_BUNDLE)
                .where(ID.eq(retainedBundleId))
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get(CANONICAL_TENANT_ID)).isNull();
              assertThat(row.get(CANONICAL_VERSION_ID)).isNull();
              assertThat(row.get(PUBLISHED_RELEASE_BUNDLE_REF)).isNull();
              assertThat(row.get(MANIFEST_SCHEMA_VERSION)).isNull();
              assertThat(row.get(ARTIFACT_DIGESTS_JSON)).isNull();
            });

    migrate(fixture.dataSource(), fixture.schema(), null);
    assertThat(retainedTuple(fixture.dsl(), retainedBundleId)).isEqualTo(retainedTupleBeforeV38);
    assertThat(bundleXmin(fixture.dsl(), retainedBundleId)).isEqualTo(retainedXminBeforeV38);
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
    exactReadback.setManifestHash(OTHER_MANIFEST_HASH);
    assertThatThrownBy(() -> fixture.releaseBundleRepository().save(exactReadback))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Published release bundle is immutable");

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(RELEASE_BUNDLE)
                    .set(DSL.field(DSL.name("manifest_hash"), String.class), OTHER_MANIFEST_HASH)
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
                    .set(DSL.field(DSL.name("manifest_hash"), String.class), OTHER_MANIFEST_HASH)
                    .where(ID.eq(retainedBundleId))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("published release bundle attestation is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(RELEASE_BUNDLE)
                    .set(PUBLISHED_RELEASE_BUNDLE_REF, "attempted-retained-release-reference")
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
    assertThat(retained.getPublishedReleaseBundleRef()).isNull();
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
    assertThat(retainedAfterAttempts.getPublishedReleaseBundleRef()).isNull();
    assertThat(retainedAfterAttempts.getManifestHash()).isEqualTo(MANIFEST_HASH);

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

  @Test
  void selectorV2RetainsCompleteOriginalEvidenceAndExactlyReplaysWithoutReplacingRelease()
      throws Exception {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "selector-release-source");
    Version version = saveVersion(fixture, owner);
    var operation = selectorOperation(fixture, version);
    WorldPublishedStartLocationEvidence evidence = operation.world();
    PublishedReleaseBundle requested = selectorBundle(version, evidence);
    PublishedReleaseBundle saved =
        fixture
            .transactionTemplate()
            .execute(
                status ->
                    net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup
                        .commitStorage(
                            fixture.dsl(),
                            fixture.versionRepository(),
                            operation,
                            () -> fixture.releaseBundleRepository().save(requested)));
    String beforeXmin = bundleXmin(fixture.dsl(), saved.getId());
    String original = new String(evidence.canonicalBytes(), StandardCharsets.UTF_8);
    assertThat(saved.getWorldPublishedStartLocationEvidenceJson()).isEqualTo(original);
    PublishedReleaseBundle independent =
        new PublishedReleaseBundleRepository(fixture.dsl())
            .findByTenantIdAndVersionId(owner.getTenantId(), version.getId())
            .orElseThrow();
    assertThat(
            WorldPublishedStartLocationEvidence.fromStored(
                    independent
                        .getWorldPublishedStartLocationEvidenceJson()
                        .getBytes(StandardCharsets.UTF_8))
                .canonicalBytes())
        .containsExactly(evidence.canonicalBytes());
    assertThat(fixture.releaseBundleRepository().save(selectorBundle(version, evidence)))
        .usingRecursiveComparison()
        .isEqualTo(saved);
    assertThat(bundleXmin(fixture.dsl(), saved.getId())).isEqualTo(beforeXmin);

    PublishedReleaseBundle changed = selectorBundle(version, evidence);
    changed.setGenerationConfigRevision("changed-generation");
    assertThatThrownBy(() -> fixture.releaseBundleRepository().save(changed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE published_release_bundle SET world_published_start_location_evidence_json = NULL WHERE id = ?",
                        saved.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute("DELETE FROM published_release_bundle WHERE id = ?", saved.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("retained");
    assertThat(bundleXmin(fixture.dsl(), saved.getId())).isEqualTo(beforeXmin);
    assertThat(bundleCount(fixture.dsl())).isEqualTo(1);
  }

  @Test
  void concurrentExactSelectorRetriesCommitOneImmutableBundle() throws Exception {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "selector-concurrent-source");
    Version version = saveVersion(fixture, owner);
    var operation = selectorOperation(fixture, version);
    var evidence = operation.world();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<PublishedReleaseBundle> save =
          () -> {
            start.await();
            return fixture
                .transactionTemplate()
                .execute(
                    status ->
                        net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup
                            .commitStorage(
                                fixture.dsl(),
                                fixture.versionRepository(),
                                operation,
                                () ->
                                    new PublishedReleaseBundleRepository(fixture.dsl())
                                        .save(selectorBundle(version, evidence))));
          };
      var first = executor.submit(save);
      var second = executor.submit(save);
      start.countDown();
      var one = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
      var two = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(two).usingRecursiveComparison().isEqualTo(one);
      assertThat(bundleCount(fixture.dsl())).isEqualTo(1);
    }
  }

  @Test
  void sqlGuardRejectsMissingInjectedAndMismatchedSelectorBindingsWithoutAnyRelease()
      throws Exception {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "selector-denial-source");
    Version version = saveVersion(fixture, owner);
    var operation = selectorOperation(fixture, version);
    WorldPublishedStartLocationEvidence evidence = operation.world();
    var json = new ObjectMapper();
    String original = new String(evidence.canonicalBytes(), StandardCharsets.UTF_8);
    String participants = json.writeValueAsString(selectedParticipants(version.getId(), evidence));
    var originalRequest = json.readTree(original).path("request");
    assertThat(originalRequest.path("versionStateEpoch").isTextual()).isTrue();
    assertThat(originalRequest.path("versionStateEpoch").asText()).matches("[1-9][0-9]*");
    var selectedV1ControlPlaneParticipants =
        (tools.jackson.databind.node.ArrayNode) json.readTree(participants);
    boolean foundControlPlaneParticipant = false;
    for (var participant : selectedV1ControlPlaneParticipants) {
      if ("GAME_DESIGN_CONTROL_PLANE".equals(participant.path("participantKey").asText())) {
        assertThat(participant.path("digestSchemaVersion").asInt()).isEqualTo(2);
        ((tools.jackson.databind.node.ObjectNode) participant).put("digestSchemaVersion", 1);
        foundControlPlaneParticipant = true;
        break;
      }
    }
    assertThat(foundControlPlaneParticipant).isTrue();
    assertThatThrownBy(
            () ->
                insertRawSelector(
                    fixture,
                    version,
                    "v2",
                    original,
                    json.writeValueAsString(selectedV1ControlPlaneParticipants)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("release v2 participant differs from selected commit/digest/schema");
    assertThat(bundleCount(fixture.dsl())).isZero();
    assertThatThrownBy(() -> insertRawSelector(fixture, version, "v2", null, participants))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> insertRawSelector(fixture, version, "v1", original, participants))
        .isInstanceOf(DataAccessException.class);
    for (String invalidEpoch : List.of("0", "01", "-1", "9223372036854775808")) {
      var root = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
      ((tools.jackson.databind.node.ObjectNode) root.get("request"))
          .put("versionStateEpoch", invalidEpoch);
      assertThatThrownBy(
              () ->
                  insertRawSelector(
                      fixture, version, "v2", json.writeValueAsString(root), participants))
          .isInstanceOf(DataAccessException.class);
    }
    var numericEpoch = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
    ((tools.jackson.databind.node.ObjectNode) numericEpoch.get("request"))
        .put("versionStateEpoch", 1L);
    assertThatThrownBy(
            () ->
                insertRawSelector(
                    fixture, version, "v2", json.writeValueAsString(numericEpoch), participants))
        .isInstanceOf(DataAccessException.class);
    for (String field :
        List.of(
            "canonicalTenantId",
            "canonicalVersionId",
            "publishWorkflowId",
            "appliedCommitId",
            "contentDigest",
            "digestSchemaVersion",
            "intakeRequestId")) {
      var root = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
      var request = (tools.jackson.databind.node.ObjectNode) root.get("request");
      if (field.equals("digestSchemaVersion")) request.put(field, 2);
      else
        request.put(
            field,
            field.endsWith("Id") || field.equals("publicationFence")
                ? UUID.randomUUID().toString()
                : "changed");
      assertThatThrownBy(
              () ->
                  insertRawSelector(
                      fixture, version, "v2", json.writeValueAsString(root), participants))
          .isInstanceOf(DataAccessException.class);
    }
    for (String field :
        List.of(
            "selectorReceiptBytesBase64",
            "originalAccountBindingBytesBase64",
            "appliedResultBytesBase64")) {
      var root = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
      root.remove(field);
      assertThatThrownBy(
              () ->
                  insertRawSelector(
                      fixture, version, "v2", json.writeValueAsString(root), participants))
          .isInstanceOf(DataAccessException.class);
    }
    assertThatThrownBy(() -> insertRawSelector(fixture, version, "v2", original, "[]"))
        .isInstanceOf(DataAccessException.class);
    var changedParticipants = (tools.jackson.databind.node.ArrayNode) json.readTree(participants);
    ((tools.jackson.databind.node.ObjectNode) changedParticipants.get(0))
        .put("contentDigest", "c".repeat(64));
    assertThatThrownBy(
            () ->
                insertRawSelector(
                    fixture, version, "v2", original, json.writeValueAsString(changedParticipants)))
        .isInstanceOf(DataAccessException.class);
    assertThat(bundleCount(fixture.dsl())).isZero();
    fixture
        .transactionTemplate()
        .executeWithoutResult(
            status ->
                net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup.commitStorage(
                    fixture.dsl(),
                    fixture.versionRepository(),
                    operation,
                    () -> {
                      insertRawSelector(fixture, version, "v2", original, participants);
                      return fixture
                          .releaseBundleRepository()
                          .findByTenantIdAndVersionId(version.getTenantId(), version.getId())
                          .orElseThrow();
                    }));
    assertThat(
            new PublishedReleaseBundleRepository(fixture.dsl())
                .findByTenantIdAndVersionId(version.getTenantId(), version.getId())
                .orElseThrow()
                .getWorldPublishedStartLocationEvidenceJson())
        .isEqualTo(original);
  }

  private WorldPublishedStartLocationEvidence selectorEvidence(Version version) throws Exception {
    return PublishedWorldSelectorFixtures.evidence(
        new TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind()));
  }

  /**
   * ISOLATED upstream Account/World evidence; actual GD genesis, synchronized source snapshots,
   * publication captures and terminal rows use the canonical owner repositories.
   */
  private net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation
      selectorOperation(Fixture fixture, Version version) {
    var target =
        new TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    return fixture
        .transactionTemplate()
        .execute(
            status -> {
              try {
                var operation =
                    net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup
                        .retainSourceBacked(fixture.dsl(), target, version.getVersionStateEpoch());
                var capture =
                    new GameDesignPublicationOperationRepository(fixture.dsl())
                        .readSourceCapture(operation)
                        .orElseThrow();
                assertThat(capture.policy().operation()).isEqualTo(operation);
                assertThat(capture.policy().snapshot().binding())
                    .isEqualTo(operation.account().input().selection().selectedCommit());
                assertThat(capture.command().operation()).isEqualTo(operation);
                return operation;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
  }

  private PublishedReleaseBundle selectorBundle(
      Version version, WorldPublishedStartLocationEvidence evidence) {
    PublishedReleaseBundle bundle = bundle(version.getTenantId(), version.getId());
    bundle.setAttestationSchemaVersion("v2");
    bundle.setPublishWorkflowId(evidence.request().publishWorkflowId());
    bundle.setParticipantDigestsJson(
        new ObjectMapper().writeValueAsString(selectedParticipants(version.getId(), evidence)));
    bundle.setWorldPublishedStartLocationEvidenceJson(
        new String(evidence.canonicalBytes(), StandardCharsets.UTF_8));
    return bundle;
  }

  private List<PublishParticipantDigestDto> selectedParticipants(
      long versionId, WorldPublishedStartLocationEvidence evidence) {
    return PublishedWorldSelectorFixtures.participants(versionId, evidence).stream()
        .map(
            participant ->
                "GAME_DESIGN_CONTROL_PLANE".equals(participant.participantKey())
                    ? new PublishParticipantDigestDto(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionId(),
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        2,
                        participant.abilitySchemaDigest(),
                        participant.errorCode(),
                        participant.errorMessage())
                    : participant)
        .toList();
  }

  private void insertRawSelector(
      Fixture fixture, Version version, String schema, String evidence, String participants) {
    var operationRow =
        fixture
            .dsl()
            .fetchOne(
                "SELECT publish_workflow_id FROM game_design_publication_operation WHERE tenant_id = ? AND version_id = ?",
                version.getTenantId(),
                version.getId());
    if (operationRow == null) {
      throw new IllegalStateException(
          "Selected publication operation row is absent for release selector fixture");
    }
    fixture
        .dsl()
        .execute(
            "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, "
                + "canonical_tenant_id, canonical_version_id, published_release_bundle_ref, attestation_schema_version, "
                + "publish_workflow_id, manifest_hash, generation_config_revision, manifest_schema_version, artifact_digests_json, "
                + "required_manifest_asset_keys_json, participant_digests_json, command_definitions_json, script_only, "
                + "world_published_start_location_evidence_json) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 1, '[]', '[]', ?, '[]', FALSE, ?)",
            version.getTenantId(),
            version.getId(),
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            UUID.randomUUID().toString(),
            schema,
            operationRow.get(0, String.class),
            MANIFEST_HASH,
            GENERATION_CONFIG_REVISION,
            participants,
            evidence);
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
    transactionTemplate.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    DefaultConfiguration jooqConfiguration = new DefaultConfiguration();
    jooqConfiguration.set(SQLDialect.POSTGRES);
    jooqConfiguration.set(
        new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
    jooqConfiguration.set(new SpringTransactionProvider(transactionManager));
    DSLContext dsl = DSL.using(jooqConfiguration);
    GameRepository gameRepository = new GameRepository(dsl);
    VersionRepository versionRepository = new VersionRepository(dsl);
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
                + "?, 'retained-config', '[\"asset-a\"]', '[]', '[]', FALSE, NULL) "
                + "RETURNING id",
            tenantId,
            versionId,
            MANIFEST_HASH)
        .fetchOne(0, Long.class);
  }

  private PublishedReleaseBundle bundle(String tenantId, Long versionId) {
    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setTenantId(tenantId);
    bundle.setVersionId(versionId);
    bundle.setVersionNumber(1);
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId("workflow-" + versionId);
    bundle.setManifestHash(MANIFEST_HASH);
    bundle.setManifestSchemaVersion(1);
    bundle.setArtifactDigestsJson("[]");
    bundle.setGenerationConfigRevision(GENERATION_CONFIG_REVISION);
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
            + "participant_digests_json, command_definitions_json, manifest_schema_version, "
            + "artifact_digests_json, script_only, "
            + "canonical_tenant_id, canonical_version_id, published_release_bundle_ref) "
            + "VALUES (?, ?, 1, 'v1', 'direct-write-test', ?, 'generation-1', "
            + "'[]', '[]', '[]', 1, '[]', FALSE, ?, ?, ?)",
        tenantId,
        versionId,
        MANIFEST_HASH,
        canonicalTenantId,
        canonicalVersionId,
        UUID.randomUUID().toString());
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
                    + "published_release_bundle_ref, "
                    + "version_number, attestation_schema_version, publish_workflow_id, "
                    + "manifest_hash, generation_config_revision, "
                    + "required_manifest_asset_keys_json, participant_digests_json, "
                    + "command_definitions_json, script_only, script_patch_version, published_at, "
                    + "manifest_schema_version, artifact_digests_json "
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
