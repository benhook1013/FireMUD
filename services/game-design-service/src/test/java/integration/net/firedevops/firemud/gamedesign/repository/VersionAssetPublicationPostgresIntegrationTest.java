package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.AssetSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.AssetExportServiceImpl;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence proof for version-scoped asset selection. Fixtures create synthetic Game and Version
 * rows through Game Design repositories; those fixture values are test inputs, not authenticated
 * Account or Gameplay source authority.
 */
@Testcontainers(disabledWithoutDocker = true)
class VersionAssetPublicationPostgresIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final MigrationVersion V35 = MigrationVersion.fromVersion("35");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void exactTenantVersionMappingsExcludeUnmappedAssetsAndSnapshotSurvivesRepositoryRestart()
      throws IOException {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "a");
    Game otherOwner = saveGame(fixture, "b");
    Version selectedVersion = saveDraftVersion(fixture, owner, 1);
    Version siblingVersion = saveDraftVersion(fixture, owner, 2);
    Version foreignVersion = saveDraftVersion(fixture, otherOwner, 1);
    GameAsset selected = saveAsset(fixture, owner, "selected.png", "selected-bytes");
    GameAsset sibling = saveAsset(fixture, owner, "sibling.png", "sibling-bytes");
    saveAsset(fixture, owner, "unmapped.png", "unmapped-bytes");
    GameAsset foreign = saveAsset(fixture, otherOwner, "foreign.png", "foreign-bytes");

    assertThatThrownBy(
            () -> associate(fixture, owner.getTenantId(), foreignVersion.getId(), selected.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Exact tenant Version row was not found");
    assertThatThrownBy(
            () -> associate(fixture, owner.getTenantId(), selectedVersion.getId(), foreign.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Exact tenant game asset row was not found");
    assertThat(mappingCount(fixture, owner.getTenantId(), selectedVersion.getId())).isZero();
    associate(fixture, owner.getTenantId(), selectedVersion.getId(), selected.getId());
    associate(fixture, owner.getTenantId(), siblingVersion.getId(), sibling.getId());

    VersionAssetPublicationRepository repository = fixture.publicationRepository();
    VersionAssetPublicationRepository.ExportSnapshot frozen =
        inTransaction(
            fixture,
            () ->
                repository.freezeOrReadSnapshot(
                    owner.getTenantId(), selectedVersion.getVersionNumber()));

    assertThat(frozen.canonicalTenantId()).isEqualTo(owner.getCanonicalTenantId());
    assertThat(frozen.canonicalVersionId()).isEqualTo(selectedVersion.getCanonicalVersionId());
    assertThat(frozen.items())
        .extracting(VersionAssetPublicationRepository.AssetSelection::usageKey)
        .containsExactly("selected.png");
    assertThat(frozen.items().get(0).assetId()).isEqualTo(selected.getId());
    assertThat(frozen.items().get(0).bytes()).containsExactly(selected.getData());
    assertThat(frozen.items().get(0).contentDigest()).matches("sha256:[0-9a-f]{64}");

    VersionAssetPublicationRepository restartedRepository =
        new VersionAssetPublicationRepository(fixture.dsl());
    VersionAssetPublicationRepository.ExportSnapshot reloaded =
        restartedRepository.readFrozenSnapshot(
            owner.getTenantId(), selectedVersion.getVersionNumber());
    assertThat(reloaded.canonicalTenantId()).isEqualTo(frozen.canonicalTenantId());
    assertThat(reloaded.canonicalVersionId()).isEqualTo(frozen.canonicalVersionId());
    assertThat(reloaded.items()).hasSize(1);
    assertThat(reloaded.items().get(0).usageKey()).isEqualTo("selected.png");
    assertThat(reloaded.items().get(0).assetId()).isEqualTo(selected.getId());
    assertThat(reloaded.items().get(0).bytes()).containsExactly(selected.getData());
    assertThat(reloaded.items().get(0).contentDigest())
        .isEqualTo(frozen.items().get(0).contentDigest());

    VersionAssetPublicationRepository.ExportSnapshot retried =
        inTransaction(
            fixture,
            () ->
                restartedRepository.freezeOrReadSnapshot(
                    owner.getTenantId(), selectedVersion.getVersionNumber()));
    assertThat(retried.items()).hasSize(1);
    assertThat(retried.items().get(0).assetId()).isEqualTo(selected.getId());
    assertThat(retried.items().get(0).contentDigest())
        .isEqualTo(frozen.items().get(0).contentDigest());

    // Exercise the actual exporter and actual PostgreSQL snapshot read. Only S3 is simulated.
    S3Client sink = mock(S3Client.class);
    AssetStoreProperties properties = new AssetStoreProperties();
    properties.setBucket("bucket");
    properties.setEndpoint("http://asset-origin");
    var exporter = new AssetExportServiceImpl(repository, sink, properties, new ObjectMapper());
    var firstManifest = exporter.exportAssets(owner.getTenantId(), 1);
    var restartedExporter =
        new AssetExportServiceImpl(restartedRepository, sink, properties, new ObjectMapper());
    var replayManifest = restartedExporter.exportAssets(owner.getTenantId(), 1);

    assertThat(replayManifest).isEqualTo(firstManifest);
    assertThat(firstManifest.requiredManifestAssetKeys())
        .containsExactly("selected.png", "manifest.json");
    var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
    var bodies = ArgumentCaptor.forClass(RequestBody.class);
    verify(sink, times(4)).putObject(requests.capture(), bodies.capture());
    String prefix = owner.getTenantId() + "/1/";
    assertThat(requests.getAllValues())
        .extracting(PutObjectRequest::key)
        .containsExactly(
            prefix + "selected.png", prefix + "manifest.json",
            prefix + "selected.png", prefix + "manifest.json");
    assertThat(requests.getAllValues().get(0).contentType()).isEqualTo("image/png");
    try (var selectedStream = bodies.getAllValues().get(0).contentStreamProvider().newStream()) {
      assertThat(selectedStream.readAllBytes()).containsExactly(selected.getData());
    }
    for (int index = 0; index < 2; index++) {
      assertThat(requests.getAllValues().get(index + 2))
          .isEqualTo(requests.getAllValues().get(index));
      try (var firstStream = bodies.getAllValues().get(index).contentStreamProvider().newStream();
          var replayStream =
              bodies.getAllValues().get(index + 2).contentStreamProvider().newStream()) {
        assertThat(replayStream.readAllBytes()).containsExactly(firstStream.readAllBytes());
      }
    }
  }

  @Test
  void ordinarySourceIsImmutableBeforeExportAndCannotBeBypassedByRepositoryOrSql() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "ordinary-source");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "resource.png", "source-bytes");
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture,
                    () ->
                        fixture
                            .publicationRepository()
                            .freezeOrReadSnapshot(owner.getTenantId(), 1)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status -> {
                          fixture
                              .publicationRepository()
                              .associateDraftAsset(
                                  owner.getTenantId(), version.getId(), asset.getId(), null);
                          return null;
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synchronized ASSET_REFERENCE");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO version_asset (tenant_id, version_id, asset_id, usage_key) VALUES (?, ?, ?, ?)",
                        owner.getTenantId(),
                        version.getId(),
                        asset.getId(),
                        asset.getFileName()))
        .rootCause()
        .isInstanceOf(SQLException.class);
    associate(fixture, owner.getTenantId(), version.getId(), asset.getId());
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_assets SET data = ? WHERE id = ?",
                        new byte[] {9},
                        asset.getId()))
        .rootCause()
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("immutable from source history");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM version_asset_export_snapshot")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void mixedCommandAssetOutcomeRetriesExactlyAndDisjointCommandInheritsWithoutAssetEpochAdvance() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "mixed-source");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "resource.png", "source-bytes");
    var binding =
        sourceBinding(
            fixture,
            version,
            CommandSource.deletePayload("not-present"),
            AssetSource.upsertPayload(
                asset.getId().toString(), asset.getFileName(), AssetSource.Requiredness.REQUIRED));
    var applied = inTransaction(fixture, () -> applySources(fixture, binding));
    assertThat(applied.ownerOutcome().appliedEpochs()).hasSize(2);
    assertThat(applied.command()).isPresent();
    assertThat(applied.asset()).isPresent();
    var replay =
        inTransaction(fixture, () -> new GameDesignSourceRepository(fixture.dsl()).apply(binding));
    assertThat(replay.ownerOutcome()).isEqualTo(applied.ownerOutcome());
    var changed =
        DraftCommitBinding.create(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            binding.baseCommitId(),
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    AssetSource.deletePayload(asset.getFileName()))),
            binding.affectedUnits());
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture, () -> new GameDesignSourceRepository(fixture.dsl()).apply(changed)))
        .isInstanceOf(IllegalArgumentException.class);
    var disjoint =
        sourceBinding(fixture, version, CommandSource.deletePayload("still-not-present"));
    inTransaction(fixture, () -> applySources(fixture, disjoint));
    var inherited =
        new AssetSourceRepository(fixture.dsl())
            .readSnapshot(disjoint.target(), disjoint.commitId())
            .orElseThrow();
    assertThat(inherited.sourceEpoch()).isEqualTo("1");
    assertThat(inherited.items()).isEqualTo(applied.asset().orElseThrow().snapshot().items());
    assertThat(inherited.items().getFirst().reference().sourceBinding()).isEqualTo(binding);
    var deletion = sourceBinding(fixture, version, AssetSource.deletePayload(asset.getFileName()));
    inTransaction(fixture, () -> applySources(fixture, deletion));
    assertThat(mappingCount(fixture, owner.getTenantId(), version.getId())).isZero();
    assertThat(
            new AssetSourceRepository(fixture.dsl())
                .readSnapshot(deletion.target(), deletion.commitId())
                .orElseThrow()
                .sourceEpoch())
        .isEqualTo("2");
    assertThatThrownBy(
            () -> fixture.dsl().execute("DELETE FROM game_assets WHERE id = ?", asset.getId()))
        .rootCause()
        .isInstanceOf(SQLException.class);
  }

  @Test
  void policyCommandAndAssetShareOneExactOwnerOutcomeAndSynchronizedSnapshot() throws Exception {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "all-local-sources");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "resource.png", "source-bytes");
    String reference =
        AssetSource.upsertPayload(
            asset.getId().toString(), asset.getFileName(), AssetSource.Requiredness.REQUIRED);
    var target = sourceBinding(fixture, version, reference).target();
    // Only the policy input is borrowed; no Account or World authority is claimed by this test.
    String policy =
        IsolatedPublicationOperationFixtures.fresh(target)
            .account()
            .input()
            .selection()
            .selectedCommit()
            .revisions()
            .stream()
            .filter(
                revision -> revision.owner() == DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE)
            .map(DraftCommitBinding.RevisionPayload::payload)
            .filter(payload -> payload.contains("REALM_ENTRY_POLICY"))
            .findFirst()
            .orElseThrow();
    var binding =
        sourceBinding(
            fixture, version, CommandSource.deletePayload("not-present"), reference, policy);
    var result = inTransaction(fixture, () -> applySources(fixture, binding));
    assertThat(result.ownerOutcome().resultIdentity())
        .isEqualTo("control-plane-source:" + binding.commitId());
    assertThat(result.ownerOutcome().appliedEpochs())
        .extracting(DraftCommitCoordinatorRepository.AppliedEpoch::aggregateType)
        .containsExactly(AssetSource.SCOPE, CommandSource.SCOPE, RealmPolicySource.SCOPE);
    var readback =
        new GameDesignSourceRepository(fixture.dsl())
            .readSynchronized(target, binding.commitId())
            .orElseThrow();
    assertThat(readback.asset()).isEqualTo(result.asset().orElseThrow().snapshot());
    assertThat(readback.command()).isEqualTo(result.command().orElseThrow().snapshot());
    assertThat(readback.policy()).isEqualTo(result.policy().orElseThrow().snapshot());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stagedOrdinaryApplicationCannotCommitWithoutItsCompleteAppliedOwnerOutcome(boolean unknown) {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "partial-source");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "resource.png", "source-bytes");
    var binding =
        sourceBinding(
            fixture,
            version,
            AssetSource.upsertPayload(
                asset.getId().toString(), asset.getFileName(), AssetSource.Requiredness.REQUIRED));
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status -> {
                          var coordinator = new DraftCommitCoordinatorRepository(fixture.dsl());
                          coordinator.claim(binding);
                          coordinator.claimApplicationSlot(binding);
                          coordinator.markOwnerInProgress(
                              binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
                          new AssetSourceRepository(fixture.dsl()).apply(binding).orElseThrow();
                          if (unknown) {
                            coordinator.recordOwnerOutcome(
                                binding,
                                new DraftCommitCoordinatorRepository.OwnerOutcome(
                                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                                    DraftCommitCoordinatorRepository.OwnerStatus.UNKNOWN,
                                    binding.commitId(),
                                    binding.digest(),
                                    null,
                                    null,
                                    List.of()));
                          }
                          assertThat(
                                  new AssetSourceRepository(fixture.dsl())
                                      .readSnapshot(binding.target(), binding.commitId()))
                              .isEmpty();
                          assertThatThrownBy(
                                  () ->
                                      new AssetSourceRepository(fixture.dsl())
                                          .requireExportSelection(
                                              owner.getTenantId(), version.getId()))
                              .isInstanceOf(IllegalStateException.class);
                          return null;
                        }))
        .rootCause()
        .isInstanceOf(SQLException.class);
    assertThat(mappingCount(fixture, owner.getTenantId(), version.getId())).isZero();
    assertThat(
            new DraftCommitCoordinatorRepository(fixture.dsl())
                .readVisibilityFence(binding.target()))
        .isEmpty();
  }

  @Test
  void sourceVisibilityAndProjectionRollbackTogether() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "source-rollback");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "resource.png", "source-bytes");
    var binding =
        sourceBinding(
            fixture,
            version,
            AssetSource.upsertPayload(
                asset.getId().toString(), asset.getFileName(), AssetSource.Requiredness.REQUIRED));
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status -> {
                          applySources(fixture, binding);
                          throw new IllegalStateException("fixture rollback");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fixture rollback");
    assertThat(mappingCount(fixture, owner.getTenantId(), version.getId())).isZero();
    assertThat(
            new GameDesignSourceRepository(fixture.dsl())
                .readSynchronized(binding.target(), binding.commitId()))
        .isEmpty();
    assertThat(
            new DraftCommitCoordinatorRepository(fixture.dsl())
                .readVisibilityFence(binding.target()))
        .isEmpty();
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT source_epoch FROM game_design_asset_source_head WHERE canonical_version_id = ?",
                    version.getCanonicalVersionId())
                .get(0, String.class))
        .isEqualTo("0");
  }

  @Test
  void selectedCommitFreezesOrdinarySourceAndRetainsExactExportAfterReconstruction() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "selected-ordinary");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "resource.png", "source-bytes");
    var binding =
        sourceBinding(
            fixture,
            version,
            AssetSource.upsertPayload(
                asset.getId().toString(), asset.getFileName(), AssetSource.Requiredness.REQUIRED));
    inTransaction(fixture, () -> applySources(fixture, binding));
    var intent =
        new AuthoredDraftPublishSelection.PublishIntent(
            owner.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            UUID.randomUUID().toString(),
            "1",
            "local selected ordinary source proof",
            binding.requestId(),
            binding.commitId(),
            binding.digest());
    var selected =
        inTransaction(
            fixture,
            () ->
                new AuthoredDraftPublishSelectionRepository(
                        fixture.dsl(), new DraftCommitCoordinatorRepository(fixture.dsl()))
                    .reserve(intent));
    assertThat(selected.selection().selectedCommit()).isEqualTo(binding);
    var frozen =
        inTransaction(
            fixture,
            () -> fixture.publicationRepository().freezeOrReadSnapshot(owner.getTenantId(), 1));
    var restarted =
        new VersionAssetPublicationRepository(fixture.dsl())
            .readFrozenSnapshot(owner.getTenantId(), 1);
    assertSameSnapshot(frozen, restarted);
    assertThat(
            new AssetSourceRepository(fixture.dsl())
                .requireExportSelection(owner.getTenantId(), version.getId())
                .binding())
        .isEqualTo(binding);
    var changed = sourceBinding(fixture, version, AssetSource.deletePayload(asset.getFileName()));
    assertThatThrownBy(() -> inTransaction(fixture, () -> applySources(fixture, changed)))
        .isInstanceOf(RuntimeException.class);
    assertThat(mappingCount(fixture, owner.getTenantId(), version.getId())).isEqualTo(1);
  }

  @Test
  void qualifiedRetainedDraftCannotReceiveInventedFreshOrdinaryGenesis() {
    Fixture fixture = fixture(MigrationVersion.fromVersion("54"));
    Game owner = saveGame(fixture, "retained-qualified");
    UUID versionId = UUID.randomUUID();
    Long rowId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, "
                    + "canonical_tenant_id, canonical_version_id, identity_source_game_row_id, identity_source_game_tenant_key, identity_source_provenance_kind) "
                    + "SELECT tenant_id, 1, 'DRAFT', 1, FALSE, canonical_tenant_id, ?, id, tenant_id, tenant_identity_provenance_kind FROM game WHERE id = ? RETURNING id",
                versionId,
                owner.getId())
            .get(0, Long.class);
    migrate(fixture.dataSource(), fixture.schema(), null);
    Version retained =
        fixture.versions().findByTenantIdAndId(owner.getTenantId(), rowId).orElseThrow();
    var target =
        new DraftCommitBinding.TargetProof(
            retained.getCanonicalTenantId(),
            retained.getCanonicalVersionId(),
            rowId,
            retained.getTenantId(),
            retained.getIdentitySourceGameRowId(),
            retained.getIdentitySourceGameTenantKey(),
            retained.getIdentitySourceProvenanceKind());
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture,
                    () -> new AssetSourceRepository(fixture.dsl()).enrollFreshDraft(target)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_SOURCE_FRESH_INSERT_UNAVAILABLE");
    assertThat(new AssetSourceRepository(fixture.dsl()).readGenesis(target)).isEmpty();
    assertThatThrownBy(
            () ->
                new AssetSourceRepository(fixture.dsl())
                    .requireExportSelection(owner.getTenantId(), rowId))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void retainedVersionWithoutCanonicalSourceCannotReceiveAssetMapping() {
    Fixture fixture = fixture(V35);
    Game owner = saveGame(fixture, "c");
    Long retainedVersionId = insertUnqualifiedRetainedVersion(fixture.dsl(), owner.getTenantId());
    migrate(fixture.dataSource(), fixture.schema(), null);
    GameAsset asset = saveAsset(fixture, owner, "retained.png", "retained-bytes");

    assertThatThrownBy(
            () -> associate(fixture, owner.getTenantId(), retainedVersionId, asset.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Version lacks a complete canonical owner identity");
    assertThat(mappingCount(fixture, owner.getTenantId(), retainedVersionId)).isZero();
  }

  @Test
  void immutableSourceRejectsChangedBytesBeforeCommittedSnapshotReadback() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "d");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "mutable-source.png", "content-original");
    associate(fixture, owner.getTenantId(), version.getId(), asset.getId());
    VersionAssetPublicationRepository.ExportSnapshot frozen =
        inTransaction(
            fixture,
            () -> fixture.publicationRepository().freezeOrReadSnapshot(owner.getTenantId(), 1));

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_assets SET data = ? WHERE tenant_id = ? AND id = ?",
                        "content-replaced".getBytes(StandardCharsets.UTF_8),
                        owner.getTenantId(),
                        asset.getId()))
        .rootCause()
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("immutable")
        .satisfies(
            failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));

    Record persistedAsset =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT data FROM game_assets WHERE tenant_id = ? AND id = ?",
                    owner.getTenantId(),
                    asset.getId()));
    assertThat(persistedAsset.get("data", byte[].class)).containsExactly(asset.getData());

    VersionAssetPublicationRepository restartedRepository =
        new VersionAssetPublicationRepository(fixture.dsl());
    VersionAssetPublicationRepository.ExportSnapshot reloaded =
        restartedRepository.readFrozenSnapshot(owner.getTenantId(), version.getVersionNumber());
    assertSameSnapshot(frozen, reloaded);

    VersionAssetPublicationRepository.ExportSnapshot retried =
        inTransaction(
            fixture,
            () ->
                restartedRepository.freezeOrReadSnapshot(
                    owner.getTenantId(), version.getVersionNumber()));
    assertSameSnapshot(frozen, retried);
  }

  @Test
  void associationAfterSnapshotFreezeIsRejected() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "e");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset first = saveAsset(fixture, owner, "first.png", "first-bytes");
    GameAsset second = saveAsset(fixture, owner, "second.png", "second-bytes");
    associate(fixture, owner.getTenantId(), version.getId(), first.getId());
    inTransaction(
        fixture,
        () -> fixture.publicationRepository().freezeOrReadSnapshot(owner.getTenantId(), 1));

    assertThatThrownBy(
            () -> associate(fixture, owner.getTenantId(), version.getId(), second.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_SOURCE_FROZEN_OR_NOT_DRAFT");
    assertThat(mappingCount(fixture, owner.getTenantId(), version.getId())).isEqualTo(1);
  }

  @Test
  void absentSnapshotIsNotReadAsAnEmptySelection() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "f");
    Version version = saveDraftVersion(fixture, owner, 1);

    assertThatThrownBy(
            () ->
                fixture
                    .publicationRepository()
                    .readFrozenSnapshot(owner.getTenantId(), version.getVersionNumber()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No durable frozen Version asset snapshot exists");
  }

  private Fixture fixture(MigrationVersion target) {
    String schema = "gd_asset_publication_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, target);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Fixture(
        schema,
        dataSource,
        dsl,
        transaction,
        new GameRepository(dsl),
        new VersionRepository(dsl),
        new GameAssetRepository(dsl),
        new VersionAssetPublicationRepository(dsl));
  }

  @Test
  void gameplaySourceAuthorsNonemptyReferencedCatalogWithMixedAssetsAndExactDurableRetry() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "gameplay-source");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "resource.png", "actual-source-bytes");
    var payloads = new java.util.ArrayList<>(gameplayPayloads());
    payloads.add(
        AssetSource.upsertPayload(
            asset.getId().toString(), asset.getFileName(), AssetSource.Requiredness.REQUIRED));
    var binding = sourceBinding(fixture, version, payloads.toArray(String[]::new));
    var application = inTransaction(fixture, () -> applySources(fixture, binding));
    var repository = new GameplayRuleSourceRepository(fixture.dsl());
    var snapshot = repository.requireCompleteForGameLogic(binding.target(), binding.commitId());

    assertThat(snapshot.manifest().families()).hasSize(GameplayRuleManifest.Family.values().length);
    assertThat(snapshot.manifest().families().get(GameplayRuleManifest.Family.ACTIONS)).hasSize(1);
    assertThat(snapshot.entries())
        .allSatisfy(entry -> assertThat(entry.sourceBinding()).isEqualTo(binding));
    assertThat(application.gameplay().orElseThrow().snapshot().canonicalBytes())
        .containsExactly(snapshot.canonicalBytes());
    var restarted = new GameplayRuleSourceRepository(fixture.dsl());
    assertThat(
            restarted
                .requireCompleteForGameLogic(binding.target(), binding.commitId())
                .canonicalBytes())
        .containsExactly(snapshot.canonicalBytes());
    var retry =
        inTransaction(fixture, () -> new GameDesignSourceRepository(fixture.dsl()).apply(binding));
    assertThat(retry.ownerOutcome()).isEqualTo(application.ownerOutcome());
    assertThat(retry.gameplay().orElseThrow().canonicalBytes())
        .containsExactly(application.gameplay().orElseThrow().canonicalBytes());

    var disjoint = sourceBinding(fixture, version, AssetSource.deletePayload(asset.getFileName()));
    inTransaction(fixture, () -> applySources(fixture, disjoint));
    var inherited = restarted.requireCompleteForGameLogic(disjoint.target(), disjoint.commitId());
    assertThat(inherited.sourceEpoch()).isEqualTo("1");
    assertThat(inherited.inheritedCommitId()).isEqualTo(binding.commitId());
    assertThat(inherited.manifest().canonicalBytes())
        .containsExactly(snapshot.manifest().canonicalBytes());
    assertThat(inherited.entries())
        .allSatisfy(entry -> assertThat(entry.sourceBinding()).isEqualTo(binding));
    assertThat(
            restarted
                .readSnapshot(binding.target(), binding.commitId())
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(snapshot.canonicalBytes());

    var deletion =
        sourceBinding(
            fixture,
            version,
            GameplayRuleSource.deletePayload(GameplayRuleManifest.Family.ABILITIES, "focusAbility"),
            GameplayRuleSource.deletePayload(GameplayRuleManifest.Family.ACTIONS, "focusAction"));
    inTransaction(fixture, () -> applySources(fixture, deletion));
    assertThat(
            restarted
                .requireCompleteForGameLogic(deletion.target(), deletion.commitId())
                .sourceEpoch())
        .isEqualTo("2");
    assertThat(
            restarted
                .requireCompleteForGameLogic(deletion.target(), deletion.commitId())
                .manifest()
                .families()
                .get(GameplayRuleManifest.Family.ACTIONS))
        .isEmpty();
  }

  @Test
  void gameplaySourceDeniesStaleEpochAndMissingRetainedVersionGenesis() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "gameplay-conflict");
    Version version = saveDraftVersion(fixture, owner, 1);
    var first = sourceBinding(fixture, version, gameplayPayloads().toArray(String[]::new));
    var stale =
        sourceBinding(
            fixture,
            version,
            GameplayRuleSource.upsertPayload(new GameplayRuleManifest.AdmissionTag("newTag")));
    inTransaction(fixture, () -> applySources(fixture, first));
    assertThatThrownBy(() -> inTransaction(fixture, () -> applySources(fixture, stale)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_EPOCH_CONFLICT");
    assertThat(
            new GameplayRuleSourceRepository(fixture.dsl())
                .readSnapshot(first.target(), stale.commitId()))
        .isEmpty();

    var unqualified =
        new DraftCommitBinding.TargetProof(
            first.target().canonicalTenantId(),
            UUID.randomUUID(),
            first.target().gameDesignVersionRowId(),
            first.target().gameDesignVersionTenantKey(),
            first.target().sourceGameRowId(),
            first.target().sourceGameTenantKey(),
            first.target().sourceProvenanceKind());
    assertThat(new GameplayRuleSourceRepository(fixture.dsl()).readGenesis(unqualified)).isEmpty();
    assertThatThrownBy(
            () ->
                new GameplayRuleSourceRepository(fixture.dsl())
                    .requireCompleteForGameLogic(unqualified, first.commitId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_SELECTED_SOURCE_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_design_gameplay_rule_snapshot SET snapshot_json = '{}' WHERE canonical_version_id = ?",
                        version.getCanonicalVersionId()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "DELETE FROM game_design_gameplay_rule_genesis WHERE canonical_version_id = ?",
                        version.getCanonicalVersionId()))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void preexistingQualifiedDraftCannotReceiveInventedGameplayInventoryAfterMigration() {
    Fixture fixture = fixture(MigrationVersion.fromVersion("56"));
    Game owner = saveGame(fixture, "retained-gameplay");
    UUID versionId = UUID.randomUUID();
    Long rowId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, is_script_only, "
                    + "canonical_tenant_id, canonical_version_id, identity_source_game_row_id, identity_source_game_tenant_key, identity_source_provenance_kind) "
                    + "SELECT tenant_id, 1, 'DRAFT', 1, FALSE, canonical_tenant_id, ?, id, tenant_id, tenant_identity_provenance_kind FROM game WHERE id = ? RETURNING id",
                versionId,
                owner.getId())
            .get(0, Long.class);
    migrate(fixture.dataSource(), fixture.schema(), null);
    Version retained =
        fixture.versions().findByTenantIdAndId(owner.getTenantId(), rowId).orElseThrow();
    var target =
        new DraftCommitBinding.TargetProof(
            retained.getCanonicalTenantId(),
            retained.getCanonicalVersionId(),
            rowId,
            retained.getTenantId(),
            retained.getIdentitySourceGameRowId(),
            retained.getIdentitySourceGameTenantKey(),
            retained.getIdentitySourceProvenanceKind());
    var repository = new GameplayRuleSourceRepository(fixture.dsl());
    assertThat(repository.readGenesis(target)).isEmpty();
    assertThatThrownBy(() -> inTransaction(fixture, () -> repository.enrollFreshDraft(target)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_FRESH_INSERT_UNAVAILABLE");
    assertThat(repository.readGenesis(target)).isEmpty();
    assertThatThrownBy(() -> repository.requireCompleteForGameLogic(target, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_SELECTED_SOURCE_UNAVAILABLE");
  }

  @Test
  void gameplayHandoffDeniesUnqualifiedLegacyEffectiveCommands() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "legacy-command-source");
    Version version = saveDraftVersion(fixture, owner, 1);
    var command =
        "{\"schemaVersion\":1,\"commandId\":\"legacy\",\"semanticOwner\":\"GAME_LOGIC\","
            + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
            + "\"promptPolicy\":\"WHEN_GAMEPLAY\",\"actionCategory\":\"GAMEPLAY\",\"historyRecordable\":true,"
            + "\"aliases\":[],\"actionTags\":[],\"effects\":[]}";
    var binding = sourceBinding(fixture, version, CommandSource.upsertPayload(command));
    inTransaction(fixture, () -> applySources(fixture, binding));
    assertThat(
            new GameplayRuleSourceRepository(fixture.dsl())
                .readSnapshot(binding.target(), binding.commitId()))
        .isPresent();
    assertThatThrownBy(
            () ->
                new GameplayRuleSourceRepository(fixture.dsl())
                    .requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_LEGACY_COMMAND_SOURCE_NOT_CONVERGED");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void gameplayHandoffDeniesGenericRuleEvenWhenTypedRevisionMatches(boolean legacyBeforeSnapshot) {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "legacy-gameplay-source");
    Version version = saveDraftVersion(fixture, owner, 1);
    var binding = sourceBinding(fixture, version, gameplayPayloads().toArray(String[]::new));
    var revision = binding.revisions().getFirst();
    if (!legacyBeforeSnapshot) {
      inTransaction(fixture, () -> applySources(fixture, binding));
    }
    fixture
        .dsl()
        .execute(
            "INSERT INTO revision (tenant_id, version_id, author_account_id, revision_kind, logical_revision_id, data) "
                + "VALUES (?, ?, 1, 'GAMEPLAY_RULE', ?, CAST(? AS JSONB))",
            version.getTenantId(),
            version.getId(),
            revision.revisionId().toString(),
            revision.payload());
    var retainedRows = fixture.dsl().fetch("SELECT * FROM revision ORDER BY id");
    if (legacyBeforeSnapshot) {
      inTransaction(fixture, () -> applySources(fixture, binding));
    }
    var repository = new GameplayRuleSourceRepository(fixture.dsl());
    var snapshot = repository.readSnapshot(binding.target(), binding.commitId()).orElseThrow();
    assertThat(snapshot.entries()).isNotEmpty();

    assertThatThrownBy(
            () -> repository.requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_GENERIC_SOURCE_NOT_CONVERGED");
    assertThatThrownBy(
            () ->
                new GameplayRuleSourceRepository(fixture.dsl())
                    .requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_GENERIC_SOURCE_NOT_CONVERGED");
    assertThat(repository.readSnapshot(binding.target(), binding.commitId()).orElseThrow())
        .isEqualTo(snapshot);
    assertThat(fixture.dsl().fetch("SELECT * FROM revision ORDER BY id")).isEqualTo(retainedRows);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void gameplayHandoffGenericRuleGuardUsesExactPrivateTenantAndVersion(boolean differentTenant) {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "scoped-legacy-gameplay-source");
    Version version = saveDraftVersion(fixture, owner, 1);
    Version otherVersion = saveDraftVersion(fixture, owner, 2);
    Game otherOwner = saveGame(fixture, "other-legacy-gameplay-source");
    var binding = sourceBinding(fixture, version, gameplayPayloads().toArray(String[]::new));
    inTransaction(fixture, () -> applySources(fixture, binding));
    var repository = new GameplayRuleSourceRepository(fixture.dsl());
    var snapshot = repository.requireCompleteForGameLogic(binding.target(), binding.commitId());
    // Generic history has no composite owner FK; scope both private keys even for retained rows.
    fixture
        .dsl()
        .execute(
            "INSERT INTO revision (tenant_id, version_id, author_account_id, revision_kind, data) "
                + "VALUES (?, ?, 1, 'GAMEPLAY_RULE', '{}'::JSONB)",
            differentTenant ? otherOwner.getTenantId() : owner.getTenantId(),
            differentTenant ? version.getId() : otherVersion.getId());
    var retainedRows = fixture.dsl().fetch("SELECT * FROM revision ORDER BY id");

    assertThat(repository.requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isEqualTo(snapshot);
    assertThat(fixture.dsl().fetch("SELECT * FROM revision ORDER BY id")).isEqualTo(retainedRows);
  }

  @Test
  void gameplayHandoffCannotClaimEmptyWhileGenericRuleHistoryExists() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "empty-with-legacy-gameplay-source");
    Version version = saveDraftVersion(fixture, owner, 1);
    var binding = sourceBinding(fixture, version, CommandSource.deletePayload("not-present"));
    inTransaction(fixture, () -> applySources(fixture, binding));
    var repository = new GameplayRuleSourceRepository(fixture.dsl());
    assertThat(
            repository.requireCompleteForGameLogic(binding.target(), binding.commitId()).entries())
        .isEmpty();
    fixture
        .dsl()
        .execute(
            "INSERT INTO revision (tenant_id, version_id, author_account_id, revision_kind, data) "
                + "VALUES (?, ?, 1, 'GAMEPLAY_RULE', '{}'::JSONB)",
            version.getTenantId(),
            version.getId());
    var retainedRows = fixture.dsl().fetch("SELECT * FROM revision ORDER BY id");

    assertThatThrownBy(
            () -> repository.requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_GENERIC_SOURCE_NOT_CONVERGED");
    assertThat(fixture.dsl().fetch("SELECT * FROM revision ORDER BY id")).isEqualTo(retainedRows);
  }

  @Test
  void mixedAssetGameplayOwnerResultRejectsSubstitutedRuleComponent() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "gameplay-component-substitution");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "resource.png", "actual-source-bytes");
    var payloads = new java.util.ArrayList<>(gameplayPayloads());
    payloads.add(
        AssetSource.upsertPayload(
            asset.getId().toString(), asset.getFileName(), AssetSource.Requiredness.REQUIRED));
    var binding = sourceBinding(fixture, version, payloads.toArray(String[]::new));
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture,
                    () -> {
                      var coordinator = new DraftCommitCoordinatorRepository(fixture.dsl());
                      coordinator.claim(binding);
                      coordinator.claimApplicationSlot(binding);
                      coordinator.markOwnerInProgress(
                          binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
                      var assetResult =
                          new AssetSourceRepository(fixture.dsl()).apply(binding).orElseThrow();
                      var gameplayResult =
                          new GameplayRuleSourceRepository(fixture.dsl())
                              .apply(binding)
                              .orElseThrow();
                      var components = new java.io.ByteArrayOutputStream();
                      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
                          components, "game-design-control-plane-sibling-components/v1");
                      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
                          components, "ASSET");
                      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
                          components, assetResult.canonicalBytes());
                      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
                          components, "GAMEPLAY");
                      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
                          components, assetResult.canonicalBytes());
                      var result = new java.io.ByteArrayOutputStream();
                      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
                          result, "game-design-control-plane-ordinary-source-application/v1");
                      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
                          result, binding.canonicalBytes());
                      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
                          result, components.toByteArray());
                      coordinator.recordOwnerOutcome(
                          binding,
                          new DraftCommitCoordinatorRepository.OwnerOutcome(
                              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                              DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
                              binding.commitId(),
                              binding.digest(),
                              "substituted-rule-component",
                              result.toByteArray(),
                              List.of(assetResult.appliedEpoch(), gameplayResult.appliedEpoch())));
                      return true;
                    }))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            new GameplayRuleSourceRepository(fixture.dsl())
                .readSnapshot(binding.target(), binding.commitId()))
        .isEmpty();
  }

  private List<String> gameplayPayloads() {
    var action =
        new GameplayRuleManifest.Action(
            "focusAction",
            "Focus",
            List.of("canFocus"),
            List.of(),
            List.of(
                new GameplayRuleManifest.Cost(
                    "energy",
                    java.math.BigDecimal.ONE,
                    GameplayRuleManifest.CommitPolicy.ON_EXECUTION)),
            List.of(
                new GameplayRuleManifest.Cooldown(
                    "focusCooldown", 3L, GameplayRuleManifest.CommitPolicy.ON_EFFECT_SUCCESS)),
            List.of(new GameplayRuleManifest.EffectBinding("restore", "SOURCE")),
            List.of());
    List<GameplayRuleManifest.Definition> definitions =
        List.of(
            new GameplayRuleManifest.AdmissionTag("canFocus"),
            new GameplayRuleManifest.Resource(
                "energy",
                java.math.BigDecimal.TEN,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.TEN,
                java.math.BigDecimal.valueOf(100),
                GameplayRuleManifest.Visibility.PUBLIC,
                List.of()),
            new GameplayRuleManifest.Effect(
                "restore",
                GameplayRuleManifest.Lifecycle.INSTANT,
                GameplayRuleManifest.EffectOperation.ADJUST_RESOURCE,
                "energy",
                null,
                java.math.BigDecimal.ONE,
                null,
                null),
            action,
            new GameplayRuleManifest.Ability("focusAbility", "Focus ability", "focusAction"));
    return definitions.stream().map(GameplayRuleSource::upsertPayload).toList();
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
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

  private Game saveGame(Fixture fixture, String tenantPrefix) {
    Game game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("Version asset publication fixture: " + tenantPrefix);
    game.setDescription("Synthetic Game Design source row for persistence proof");
    return Objects.requireNonNull(
        fixture.transaction().execute(status -> fixture.games().save(game)));
  }

  private Version saveDraftVersion(Fixture fixture, Game game, int versionNumber) {
    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setVersionNumber(versionNumber);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setNotes("Synthetic version asset publication fixture");
    return Objects.requireNonNull(
        fixture.transaction().execute(status -> fixture.versions().save(version)));
  }

  private GameAsset saveAsset(Fixture fixture, Game game, String fileName, String contents) {
    GameAsset asset = new GameAsset();
    asset.setTenantId(game.getTenantId());
    asset.setFileName(fileName);
    asset.setContentType("image/png");
    asset.setData(contents.getBytes(StandardCharsets.UTF_8));
    return Objects.requireNonNull(
        fixture.transaction().execute(status -> fixture.assets().save(asset)));
  }

  private void associate(Fixture fixture, String tenantId, long versionId, long assetId) {
    fixture
        .transaction()
        .execute(
            status -> {
              var version =
                  fixture
                      .versions()
                      .findByTenantIdAndId(tenantId, versionId)
                      .orElseThrow(
                          () ->
                              new IllegalStateException("Exact tenant Version row was not found"));
              if (version.getCanonicalTenantId() == null
                  || version.getCanonicalVersionId() == null) {
                throw new IllegalStateException(
                    "Version lacks a complete canonical owner identity");
              }
              var asset =
                  fixture
                      .dsl()
                      .fetchOne(
                          "SELECT file_name FROM game_assets WHERE tenant_id = ? AND id = ?",
                          tenantId,
                          assetId);
              if (asset == null)
                throw new IllegalStateException("Exact tenant game asset row was not found");
              var target =
                  new DraftCommitBinding.TargetProof(
                      version.getCanonicalTenantId(),
                      version.getCanonicalVersionId(),
                      versionId,
                      tenantId,
                      version.getIdentitySourceGameRowId(),
                      version.getIdentitySourceGameTenantKey(),
                      version.getIdentitySourceProvenanceKind());
              IsolatedPublicationOwnerSetup.applyOrdinaryReferences(
                  fixture.dsl(),
                  target,
                  List.of(
                      AssetSource.upsertPayload(
                          Long.toString(assetId),
                          asset.get("file_name", String.class),
                          AssetSource.Requiredness.REQUIRED)));
              return null;
            });
  }

  private DraftCommitBinding sourceBinding(Fixture fixture, Version version, String... payloads) {
    var target =
        new DraftCommitBinding.TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    var revisions = new java.util.ArrayList<DraftCommitBinding.RevisionPayload>();
    var units = new java.util.ArrayList<DraftCommitBinding.AffectedUnit>();
    for (int index = 0; index < payloads.length; index++) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(index),
              UUID.randomUUID(),
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              payloads[index]));
    }
    for (String scope :
        List.of(
            CommandSource.SCOPE,
            AssetSource.SCOPE,
            RealmPolicySource.SCOPE,
            GameplayRuleSource.SCOPE)) {
      String kind =
          scope.equals(AssetSource.SCOPE)
              ? "ASSET_REFERENCE"
              : scope.equals(RealmPolicySource.SCOPE)
                  ? "REALM_ENTRY_POLICY"
                  : scope.equals(GameplayRuleSource.SCOPE) ? "GAMEPLAY_RULE" : "COMMAND_DEFINITION";
      if (List.of(payloads).stream().noneMatch(payload -> payload.contains(kind))) continue;
      String table =
          scope.equals(AssetSource.SCOPE)
              ? "game_design_asset_source_head"
              : scope.equals(RealmPolicySource.SCOPE)
                  ? "game_design_realm_policy_source"
                  : scope.equals(GameplayRuleSource.SCOPE)
                      ? "game_design_gameplay_rule_head"
                      : "game_design_command_source_head";
      String epoch =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT source_epoch FROM " + table + " WHERE canonical_version_id = ?",
                  version.getCanonicalVersionId())
              .get(0, String.class);
      units.add(
          new DraftCommitBinding.AffectedUnit(
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              scope,
              target.canonicalVersionId().toString(),
              scope,
              "effective",
              epoch));
    }
    var prior = new DraftCommitCoordinatorRepository(fixture.dsl()).readVisibilityFence(target);
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        prior.map(fence -> fence.commitId().toString()).orElse("base-commit-0"),
        revisions,
        units);
  }

  private GameDesignSourceRepository.Application applySources(
      Fixture fixture, DraftCommitBinding binding) {
    var coordinator = new DraftCommitCoordinatorRepository(fixture.dsl());
    coordinator.claim(binding);
    coordinator.claimApplicationSlot(binding);
    coordinator.markOwnerInProgress(binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
    var applied = new GameDesignSourceRepository(fixture.dsl()).apply(binding);
    IsolatedPublicationOwnerSetup.advanceSourceVisibility(
        fixture.dsl(), binding, List.of(applied.ownerOutcome()));
    coordinator.releaseApplicationSlot(binding);
    return applied;
  }

  private <T> T inTransaction(Fixture fixture, java.util.function.Supplier<T> action) {
    return Objects.requireNonNull(fixture.transaction().execute(status -> action.get()));
  }

  private long mappingCount(Fixture fixture, String tenantId, long versionId) {
    Record countRecord =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) AS mapping_count FROM version_asset "
                        + "WHERE tenant_id = ? AND version_id = ?",
                    tenantId,
                    versionId));
    return Objects.requireNonNull(countRecord.get("mapping_count", Long.class));
  }

  private void assertSameSnapshot(
      VersionAssetPublicationRepository.ExportSnapshot expected,
      VersionAssetPublicationRepository.ExportSnapshot actual) {
    assertThat(actual.tenantId()).isEqualTo(expected.tenantId());
    assertThat(actual.versionId()).isEqualTo(expected.versionId());
    assertThat(actual.versionNumber()).isEqualTo(expected.versionNumber());
    assertThat(actual.capturedVersionStateEpoch()).isEqualTo(expected.capturedVersionStateEpoch());
    assertThat(actual.canonicalTenantId()).isEqualTo(expected.canonicalTenantId());
    assertThat(actual.canonicalVersionId()).isEqualTo(expected.canonicalVersionId());
    assertThat(actual.items()).hasSize(expected.items().size());
    for (int i = 0; i < expected.items().size(); i++) {
      VersionAssetPublicationRepository.AssetSelection expectedItem = expected.items().get(i);
      VersionAssetPublicationRepository.AssetSelection actualItem = actual.items().get(i);
      assertThat(actualItem.usageKey()).isEqualTo(expectedItem.usageKey());
      assertThat(actualItem.assetId()).isEqualTo(expectedItem.assetId());
      assertThat(actualItem.contentType()).isEqualTo(expectedItem.contentType());
      assertThat(actualItem.contentDigest()).isEqualTo(expectedItem.contentDigest());
      assertThat(actualItem.bytes()).containsExactly(expectedItem.bytes());
    }
  }

  private long insertUnqualifiedRetainedVersion(DSLContext dsl, String tenantId) {
    Record insertedVersion =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, "
                        + "script_patch_version, base_version_id, is_script_only, notes) "
                        + "VALUES (?, 9, 'FAILED', 3, NULL, NULL, FALSE, 'retained history') RETURNING id",
                    tenantId)
                .fetchSingle());
    return Objects.requireNonNull(insertedVersion.get("id", Long.class));
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction,
      GameRepository games,
      VersionRepository versions,
      GameAssetRepository assets,
      VersionAssetPublicationRepository publicationRepository) {}
}
