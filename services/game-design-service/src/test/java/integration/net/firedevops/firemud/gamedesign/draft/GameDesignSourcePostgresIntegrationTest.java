package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.publication.BrandingSourceRepository;
import net.firedevops.firemud.gamedesign.publication.CommandSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSnapshot;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real local source writers and coordinator SQL; publication Account and World remain isolated. */
@Testcontainers(disabledWithoutDocker = true)
class GameDesignSourcePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void exactReviewedClaimReplayPreservesAllRetainedRowsAndChangedInputCannotRewriteThem() {
    var local = fixture();
    var original = storageBinding(local, "reviewed-claim");
    local.tx(
        () -> {
          claimStorageBinding(local, original);
          return null;
        });
    var retained = reviewedClaimRows(local);
    local.tx(
        () -> {
          claimStorageBinding(local, original);
          return null;
        });
    assertThat(reviewedClaimRows(local)).isEqualTo(retained);
    var changed = changedClaimInput(original);
    assertThatThrownBy(
            () ->
                local.tx(
                    () -> {
                      claimStorageBinding(local, changed);
                      return null;
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("REVIEWED_BASE_STORED_BINDING_CONFLICT");
    assertThat(reviewedClaimRows(local)).isEqualTo(retained);
    assertReviewedClaimInventory(local, original);
  }

  @Test
  void reviewedClaimAndItsOwnerInventoryRollBackWithoutRetainingReviewEvidence() {
    var local = fixture();
    var binding = storageBinding(local, "rolled-back-reviewed-claim");
    local
        .write()
        .executeWithoutResult(
            status -> {
              claimStorageBinding(local, binding);
              status.setRollbackOnly();
            });
    assertThat(local.coordinator().read(local.target(), binding.requestId())).isEmpty();
    assertThat(new GameDesignReviewedBaseRepository(local.dsl()).read(binding)).isEmpty();
    assertThat(local.coordinator().readApplicationSlot(local.target())).isEmpty();
    for (String table :
        List.of(
            "game_design_draft_commit",
            "game_design_draft_commit_owner_result",
            "game_design_reviewed_draft_base")) {
      assertThat(local.dsl().fetchCount(DSL.table(table))).isZero();
    }
  }

  @Test
  void concurrentExactReviewedClaimsConvergeOnOneBindingAndOwnerInventory() throws Exception {
    var local = fixture();
    var binding = storageBinding(local, "concurrent-exact-reviewed");
    var claims = concurrentReviewedClaims(local, binding, binding);
    assertThat(claims)
        .allSatisfy(
            claim -> {
              assertThat(claim.failure()).isNull();
              assertThat(claim.binding()).isEqualTo(binding);
            });
    assertReviewedClaimInventory(local, binding);
  }

  @Test
  void concurrentRequestsCannotAliasOneReviewedCommitOrRetainLosingOwnerRows() throws Exception {
    var local = fixture();
    var first = storageBinding(local, "concurrent-commit-identity");
    var second =
        DraftCommitBinding.create(
            first.target(),
            UUID.randomUUID(),
            first.commitId(),
            first.baseCommitId(),
            first.revisions(),
            first.affectedUnits());
    var claims = concurrentReviewedClaims(local, first, second);
    var winner = soleReviewedClaimWinner(claims);
    var losingRequest = winner.equals(first) ? second.requestId() : first.requestId();
    assertThat(local.coordinator().read(local.target(), losingRequest)).isEmpty();
    assertReviewedClaimInventory(local, winner);
  }

  @Test
  void concurrentChangedReviewedInputCannotShareOneRequestIdentity() throws Exception {
    var local = fixture();
    var first = storageBinding(local, "concurrent-request-input");
    var changed = changedClaimInput(first);
    assertThat(changed.digest()).isNotEqualTo(first.digest());
    var claims = concurrentReviewedClaims(local, first, changed);
    assertReviewedClaimInventory(local, soleReviewedClaimWinner(claims));
  }

  private static DraftCommitBinding changedClaimInput(DraftCommitBinding original) {
    var revision = original.revisions().getFirst();
    // Preserve every request/commit/revision identity while changing a valid command alias.
    return DraftCommitBinding.create(
        original.target(),
        original.requestId(),
        original.commitId(),
        original.baseCommitId(),
        List.of(
            new RevisionPayload(
                revision.revisionOrder(),
                revision.revisionId(),
                revision.owner(),
                CommandSource.upsertPayload(command("look", "changed")))),
        original.affectedUnits());
  }

  private static List<org.jooq.Result<org.jooq.Record>> reviewedClaimRows(Fixture local) {
    return List.of(
        local
            .dsl()
            .fetch(
                "SELECT t.*, t.xmin::text AS retained_xmin FROM game_design_draft_commit t ORDER BY request_id"),
        local
            .dsl()
            .fetch(
                "SELECT t.*, t.xmin::text AS retained_xmin FROM game_design_draft_commit_owner_result t ORDER BY request_id"),
        local
            .dsl()
            .fetch(
                "SELECT t.*, t.xmin::text AS retained_xmin FROM game_design_reviewed_draft_base t ORDER BY request_id"));
  }

  private static void assertReviewedClaimInventory(Fixture local, DraftCommitBinding binding) {
    var persisted = local.coordinator().read(local.target(), binding.requestId()).orElseThrow();
    assertThat(persisted.binding().canonicalBytes()).containsExactly(binding.canonicalBytes());
    assertThat(persisted.ownerStates()).containsOnlyKeys(Owner.GAME_DESIGN_CONTROL_PLANE);
    assertThat(persisted.ownerStates().values())
        .allSatisfy(
            state -> {
              assertThat(state.status())
                  .isEqualTo(DraftCommitCoordinatorRepository.OwnerStatus.NOT_ATTEMPTED);
              assertThat(state.outcome()).isEmpty();
            });
    assertThat(new GameDesignReviewedBaseRepository(local.dsl()).read(binding)).isPresent();
    assertThat(local.coordinator().readApplicationSlot(local.target())).isEmpty();
    for (String table :
        List.of(
            "game_design_draft_commit",
            "game_design_draft_commit_owner_result",
            "game_design_reviewed_draft_base")) {
      assertThat(local.dsl().fetchCount(DSL.table(table))).isEqualTo(1);
    }
  }

  private static List<ReviewedClaimAttempt> concurrentReviewedClaims(
      Fixture local, DraftCommitBinding first, DraftCommitBinding second) throws Exception {
    var ready = new CountDownLatch(2);
    var launch = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var left = executor.submit(() -> reviewedClaimAttempt(local, first, ready, launch));
      var right = executor.submit(() -> reviewedClaimAttempt(local, second, ready, launch));
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      launch.countDown();
      return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
    } finally {
      launch.countDown();
      executor.shutdownNow();
    }
  }

  private static ReviewedClaimAttempt reviewedClaimAttempt(
      Fixture local, DraftCommitBinding binding, CountDownLatch ready, CountDownLatch launch)
      throws InterruptedException {
    ready.countDown();
    if (!launch.await(20, TimeUnit.SECONDS))
      throw new IllegalStateException("Reviewed claim launch timed out");
    try {
      var persisted =
          local.tx(
              () -> {
                claimStorageBinding(local, binding);
                return local
                    .coordinator()
                    .read(local.target(), binding.requestId())
                    .orElseThrow()
                    .binding();
              });
      return new ReviewedClaimAttempt(persisted, null);
    } catch (RuntimeException failure) {
      return new ReviewedClaimAttempt(null, failure);
    }
  }

  private static DraftCommitBinding soleReviewedClaimWinner(List<ReviewedClaimAttempt> claims) {
    assertThat(claims.stream().filter(claim -> claim.failure() == null)).hasSize(1);
    assertThat(claims.stream().filter(claim -> claim.failure() != null))
        .singleElement()
        .satisfies(
            claim ->
                assertThat(claim.failure())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("REVIEWED_BASE_STORED_BINDING_CONFLICT"));
    return claims.stream()
        .filter(claim -> claim.failure() == null)
        .findFirst()
        .orElseThrow()
        .binding();
  }

  private record ReviewedClaimAttempt(DraftCommitBinding binding, RuntimeException failure) {}

  @Test
  void firstReferenceRejectsStaleOrdinaryByteUpdate() throws Exception {
    assertFirstReferenceRejectsStaleUpdate(false, false);
  }

  @Test
  void firstReferenceRejectsStaleOrdinaryMetadataUpdate() throws Exception {
    assertFirstReferenceRejectsStaleUpdate(false, true);
  }

  @Test
  void firstReferenceRejectsStaleBrandingByteUpdate() throws Exception {
    assertFirstReferenceRejectsStaleUpdate(true, false);
  }

  @Test
  void firstReferenceRejectsStaleBrandingMetadataUpdate() throws Exception {
    assertFirstReferenceRejectsStaleUpdate(true, true);
  }

  private static void assertFirstReferenceRejectsStaleUpdate(boolean branding, boolean metadata)
      throws Exception {
    Fixture fixture = fixture();
    byte[] bytes = {0, 1, (byte) 0xff};
    long assetId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO game_assets (tenant_id, file_name, content_type, data) "
                    + "VALUES (?, 'logo.bin', 'application/octet-stream', ?) RETURNING id",
                fixture.target().gameDesignVersionTenantKey(),
                bytes)
            .get(0, Long.class);
    // Draft bytes are still mutable before the first source reference.
    assertThat(
            fixture.dsl().execute("UPDATE game_assets SET data = ? WHERE id = ?", bytes, assetId))
        .isEqualTo(1);
    String history =
        branding ? "game_design_branding_source_revision" : "game_design_asset_source_revision";
    try (Connection stale =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      stale.setSchema(fixture.schema());
      stale.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      stale.setAutoCommit(false);
      DSLContext staleDsl = DSL.using(stale, SQLDialect.POSTGRES);
      assertThat(staleDsl.fetchCount(DSL.table(history))).isZero();
      assertThat(
              staleDsl
                  .fetchSingle("SELECT data FROM game_assets WHERE id = ?", assetId)
                  .get(0, byte[].class))
          .containsExactly(bytes);
      if (branding) {
        fixture.tx(
            () -> {
              applyBrandingFirstReference(fixture, assetId);
              return null;
            });
      } else {
        fixture.tx(
            () ->
                IsolatedAssetSourceOwnerSetup.upsert(
                    fixture.dsl(), fixture.target(), assetId, "logo.bin"));
      }
      assertThat(fixture.dsl().fetchCount(DSL.table(history))).isEqualTo(1);
      // A still sees the pre-reference history, so a row version fence must reject its write.
      assertThat(staleDsl.fetchCount(DSL.table(history))).isZero();
      assertThatThrownBy(
              () ->
                  staleDsl.execute(
                      metadata
                          ? "UPDATE game_assets SET content_type = ? WHERE id = ?"
                          : "UPDATE game_assets SET data = ? WHERE id = ?",
                      metadata ? "image/png" : new byte[] {9},
                      assetId))
          .rootCause()
          .isInstanceOfSatisfying(
              PSQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("40001"));
      stale.rollback();
    }
    var retained =
        fixture
            .dsl()
            .fetchSingle("SELECT data, content_type FROM game_assets WHERE id = ?", assetId);
    assertThat(retained.get("data", byte[].class)).containsExactly(bytes);
    assertThat(retained.get("content_type", String.class)).isEqualTo("application/octet-stream");
    assertThatThrownBy(() -> fixture.dsl().execute("DELETE FROM game_assets WHERE id = ?", assetId))
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .hasMessageContaining("source bytes are immutable from source history");
  }

  /** Controlled branding schema producer: all installed coordinator/source guards remain active. */
  private static void applyBrandingFirstReference(Fixture fixture, long assetId) {
    var target = fixture.target();
    var genesis = fixture.sources().readGenesis(target).orElseThrow();
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis:" + genesis.policy().receiptId(),
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    BrandingSource.upsertPayload(
                        Long.toString(assetId),
                        "logo.bin",
                        BrandingSource.Role.LOGO,
                        BrandingSource.Requiredness.REQUIRED))),
            List.of(
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    BrandingSource.SCOPE,
                    target.canonicalVersionId().toString(),
                    BrandingSource.SCOPE,
                    BrandingSource.SCOPE_ID,
                    "0")));
    start(fixture, binding);
    fixture
        .dsl()
        .execute(
            "INSERT INTO game_design_branding_source_revision "
                + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, revision_id, "
                + "revision_order, operation_kind, usage_key, role, asset_id, requiredness, content_type, content_digest, byte_size) "
                + "SELECT ?, ?, ?, ?, ?, 0, 'UPSERT', file_name, 'LOGO', id, 'REQUIRED', content_type, "
                + "'sha256:' || encode(sha256(data), 'hex'), octet_length(data) FROM game_assets WHERE id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            binding.requestId(),
            binding.commitId(),
            binding.revisions().getFirst().revisionId(),
            assetId);
    String snapshot =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT jsonb_build_object('schema', 'game-design-branding-asset-source-snapshot/v1', "
                    + "'bindingJson', c.binding_json, 'bindingDigest', c.input_digest, 'genesisReceiptId', g.receipt_id::TEXT, "
                    + "'inheritedCommitId', '', 'sourceEpoch', '1', 'items', jsonb_build_array(branding_asset_revision_item(r)), "
                    + "'roles', jsonb_build_object('RESOURCE', 'EMPTY', 'LOGO', 'PRESENT', 'FAVICON', 'EMPTY', 'THEME', 'EMPTY'))::TEXT "
                    + "FROM game_design_branding_source_revision r "
                    + "JOIN game_design_draft_commit c USING (canonical_tenant_id, canonical_version_id, request_id, commit_id) "
                    + "JOIN game_design_branding_source_genesis g USING (canonical_tenant_id, canonical_version_id) WHERE r.commit_id = ?",
                binding.commitId())
            .get(0, String.class);
    byte[] application =
        frames(
            "game-design-branding-asset-source-application/v1".getBytes(StandardCharsets.UTF_8),
            "0".getBytes(StandardCharsets.UTF_8),
            snapshot.getBytes(StandardCharsets.UTF_8));
    fixture
        .dsl()
        .execute(
            "INSERT INTO game_design_branding_source_application "
                + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, expected_epoch, snapshot_json, result_bytes) VALUES (?, ?, ?, ?, '0', ?, ?)",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            binding.requestId(),
            binding.commitId(),
            snapshot,
            application);
    fixture
        .dsl()
        .execute(
            "UPDATE game_design_branding_source_head SET source_epoch = '1', applied_commit_id = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            binding.commitId(),
            target.canonicalTenantId(),
            target.canonicalVersionId());
    byte[] components =
        frames(
            "game-design-control-plane-sibling-components/v1".getBytes(StandardCharsets.UTF_8),
            "BRANDING".getBytes(StandardCharsets.UTF_8),
            application);
    byte[] result =
        frames(
            "game-design-control-plane-ordinary-source-application/v1"
                .getBytes(StandardCharsets.UTF_8),
            binding.canonicalJson().getBytes(StandardCharsets.UTF_8),
            components);
    fixture
        .coordinator()
        .recordOwnerOutcome(
            binding,
            new DraftCommitCoordinatorRepository.OwnerOutcome(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
                binding.commitId(),
                binding.digest(),
                "isolated-branding-first-reference",
                result,
                List.of(
                    new DraftCommitCoordinatorRepository.AppliedEpoch(
                        BrandingSource.SCOPE,
                        target.canonicalVersionId().toString(),
                        BrandingSource.SCOPE,
                        BrandingSource.SCOPE_ID,
                        "0",
                        "1"))));
  }

  private static byte[] frames(byte[]... values) {
    var result = new ByteArrayOutputStream();
    for (byte[] value : values) {
      result.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
      result.writeBytes(value);
    }
    return result.toByteArray();
  }

  @Test
  void ordinaryAssetSourceProjectsInheritedAndDeletedReferencesThroughCoordinatorFence()
      throws Exception {
    Fixture fixture = fixture();
    var genesis = fixture.sources().readGenesis(fixture.target()).orElseThrow();
    assertThat(genesis.asset().receiptId()).isNotEqualTo(genesis.policy().receiptId());
    assertThat(genesis.asset().target()).isEqualTo(fixture.target());
    assertThat(genesis.asset().creationTransactionId())
        .isEqualTo(genesis.policy().creationTransactionId());
    long assetId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO game_assets (tenant_id, file_name, content_type, data) "
                    + "VALUES (?, 'logo.bin', 'application/octet-stream', ?) RETURNING id",
                fixture.target().gameDesignVersionTenantKey(),
                new byte[] {0, 1, (byte) 0xff})
            .get(0, Long.class);
    DraftCommitBinding upsert =
        assetBinding(
            fixture.target(),
            "genesis:" + genesis.policy().receiptId(),
            "0",
            AssetSource.upsertPayload(
                Long.toString(assetId), "logo.bin", AssetSource.Requiredness.REQUIRED));
    var applied = apply(fixture, upsert);
    synchronize(fixture, upsert, applied);
    var selected =
        fixture.sources().readSynchronized(fixture.target(), upsert.commitId()).orElseThrow();
    assertThat(selected.asset().items()).hasSize(1);
    var selectedAsset = selected.asset().items().getFirst();
    assertThat(selectedAsset.reference().usageKey()).isEqualTo("logo.bin");
    assertThat(selectedAsset.reference().assetRowId()).isEqualTo(Long.toString(assetId));
    assertThat(selectedAsset.byteSize()).isEqualTo(3);
    assertThat(selectedAsset.contentType()).isEqualTo("application/octet-stream");
    assertThat(selectedAsset.contentDigest())
        .isEqualTo(
            "sha256:"
                + HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(new byte[] {0, 1, (byte) 0xff})));
    assertThat(fixture.dsl().fetchCount(DSL.table("version_asset"))).isEqualTo(1);

    DraftCommitBinding command =
        binding(fixture.target(), upsert.commitId().toString(), null, "0", null, false);
    var commandApplied = apply(fixture, command);
    synchronize(fixture, command, commandApplied);
    var inherited =
        fixture.sources().readSynchronized(fixture.target(), command.commitId()).orElseThrow();
    assertThat(inherited.asset().items()).containsExactlyElementsOf(selected.asset().items());
    assertThat(fixture.dsl().fetchCount(DSL.table("version_asset"))).isEqualTo(1);

    DraftCommitBinding staleDelete =
        assetBinding(
            fixture.target(),
            command.commitId().toString(),
            "0",
            AssetSource.deletePayload("logo.bin"));
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () -> {
                      start(fixture, staleDelete);
                      return fixture.sources().apply(staleDelete);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_SOURCE_EPOCH_CONFLICT");
    assertThat(fixture.coordinator().readApplicationSlot(fixture.target())).isEmpty();
    assertThat(
            fixture.sources().readSynchronized(fixture.target(), command.commitId()).orElseThrow())
        .isEqualTo(inherited);
    assertThat(fixture.dsl().fetchCount(DSL.table("version_asset"))).isEqualTo(1);
    DraftCommitBinding delete =
        assetBinding(
            fixture.target(),
            command.commitId().toString(),
            "1",
            AssetSource.deletePayload("logo.bin"));
    var deleted = apply(fixture, delete);
    synchronize(fixture, delete, deleted);
    assertThat(
            fixture
                .sources()
                .readSynchronized(fixture.target(), delete.commitId())
                .orElseThrow()
                .asset()
                .items())
        .isEmpty();
    assertThat(fixture.dsl().fetchCount(DSL.table("version_asset"))).isZero();
  }

  @Test
  void ordinaryAssetSourceRowsRejectChangesAndPreserveTheSynchronizedSnapshot() throws Exception {
    Fixture fixture = fixture();
    var genesis = fixture.sources().readGenesis(fixture.target()).orElseThrow();
    byte[] originalBytes = {0, 1, (byte) 0xff};
    long assetId =
        fixture
            .dsl()
            .fetchSingle(
                "INSERT INTO game_assets (tenant_id, file_name, content_type, data) "
                    + "VALUES (?, 'logo.bin', 'application/octet-stream', ?) RETURNING id",
                fixture.target().gameDesignVersionTenantKey(),
                originalBytes)
            .get(0, Long.class);
    DraftCommitBinding upsert =
        assetBinding(
            fixture.target(),
            "genesis:" + genesis.policy().receiptId(),
            "0",
            AssetSource.upsertPayload(
                Long.toString(assetId), "logo.bin", AssetSource.Requiredness.REQUIRED));
    synchronize(fixture, upsert, apply(fixture, upsert));
    var snapshot =
        fixture.sources().readSynchronized(fixture.target(), upsert.commitId()).orElseThrow();

    assertAssetMutationDenied(
        fixture,
        upsert,
        snapshot,
        () ->
            fixture
                .dsl()
                .execute("UPDATE game_assets SET data = ? WHERE id = ?", new byte[] {3}, assetId));
    assertAssetMutationDenied(
        fixture,
        upsert,
        snapshot,
        () ->
            fixture
                .dsl()
                .execute(
                    "UPDATE game_assets SET content_type = ? WHERE id = ?", "image/png", assetId));
    assertAssetMutationDenied(
        fixture,
        upsert,
        snapshot,
        () ->
            fixture
                .dsl()
                .execute(
                    "UPDATE game_assets SET file_name = ? WHERE id = ?", "renamed.bin", assetId));
    assertAssetMutationDenied(
        fixture,
        upsert,
        snapshot,
        () -> fixture.dsl().execute("UPDATE game_assets SET data = NULL WHERE id = ?", assetId));
  }

  private static void assertAssetMutationDenied(
      Fixture fixture,
      DraftCommitBinding binding,
      GameDesignSourceRepository.SynchronizedSources snapshot,
      Runnable mutation) {
    assertThatThrownBy(mutation::run)
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .satisfies(
            cause -> {
              PSQLException postgresException = (PSQLException) cause;
              assertThat(postgresException.getSQLState()).isEqualTo("23514");
              assertThat(postgresException.getServerErrorMessage().getMessage())
                  .isEqualTo("ordinary asset source bytes are immutable from source history");
            });
    assertThat(
            fixture.sources().readSynchronized(fixture.target(), binding.commitId()).orElseThrow())
        .isEqualTo(snapshot);
  }

  @Test
  void versionInsertSharesGenesisAndPolicyCommandMixedCommitsKeepOneExactOutcome() {
    Fixture fixture = fixture();
    var genesis = fixture.sources().readGenesis(fixture.target()).orElseThrow();
    assertThat(genesis.policy().receiptId()).isEqualTo(genesis.command().receiptId());
    assertThat(genesis.asset().receiptId()).isNotEqualTo(genesis.policy().receiptId());
    assertThat(genesis.asset().target()).isEqualTo(fixture.target());
    assertThat(genesis.brandingEvidence()).isPresent();
    assertThat(genesis.brandingEvidence().orElseThrow().target()).isEqualTo(fixture.target());
    assertThat(genesis.brandingEvidence().orElseThrow().creationTransactionId())
        .isEqualTo(genesis.policy().creationTransactionId());
    assertThat(genesis.asset().creationTransactionId())
        .isEqualTo(genesis.policy().creationTransactionId());
    assertThat(genesis.policy().creationTransactionId())
        .isEqualTo(genesis.command().creationTransactionId());
    assertThat(fixture.tx(() -> fixture.sources().enrollFreshDraft(fixture.target())))
        .isEqualTo(genesis);
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();

    DraftCommitBinding policy =
        binding(
            fixture.target(), "genesis:" + genesis.policy().receiptId(), "0", null, "main", true);
    var policyResult = apply(fixture, policy);
    synchronize(fixture, policy, policyResult);
    var policySnapshot =
        fixture.sources().readSynchronized(fixture.target(), policy.commitId()).orElseThrow();
    assertThat(policySnapshot.policy().policies()).hasSize(1);
    assertThat(policySnapshot.command().definitions()).isEmpty();
    assertThat(policySnapshot.asset().items()).isEmpty();

    DraftCommitBinding command =
        binding(fixture.target(), policy.commitId().toString(), null, "0", null, false);
    var commandResult = apply(fixture, command);
    synchronize(fixture, command, commandResult);
    var commandSnapshot =
        fixture.sources().readSynchronized(fixture.target(), command.commitId()).orElseThrow();
    assertThat(commandSnapshot.policy().policies())
        .containsExactlyElementsOf(policySnapshot.policy().policies());
    assertThat(commandSnapshot.command().definitions()).hasSize(1);
    assertThat(commandSnapshot.asset().items()).isEmpty();
    assertThat(commandResult.ownerOutcome().appliedEpochs())
        .containsExactly(commandResult.command().orElseThrow().commandAppliedEpoch());

    DraftCommitBinding mixed =
        binding(fixture.target(), command.commitId().toString(), "1", "1", "main", true);
    var mixedResult = apply(fixture, mixed);
    assertThat(mixedResult.ownerOutcome().appliedEpochs()).hasSize(2);
    assertThat(mixedResult.ownerOutcome().resultBytes())
        .contains(mixedResult.command().orElseThrow().canonicalBytes());
    assertThat(fixture.tx(() -> fixture.sources().apply(policy)).ownerOutcome())
        .isEqualTo(policyResult.ownerOutcome());
    assertThat(fixture.tx(() -> fixture.sources().apply(command)).ownerOutcome())
        .isEqualTo(commandResult.ownerOutcome());
    assertThat(fixture.tx(() -> fixture.sources().apply(mixed)).ownerOutcome())
        .isEqualTo(mixedResult.ownerOutcome());
    synchronize(fixture, mixed, mixedResult);
    var mixedSnapshot =
        fixture.sources().readSynchronized(fixture.target(), mixed.commitId()).orElseThrow();
    assertThat(mixedSnapshot.policy().sourceEpoch()).isEqualTo("2");
    assertThat(mixedSnapshot.command().sourceEpoch()).isEqualTo("2");
    assertThat(mixedSnapshot.asset().items()).isEmpty();
    assertThat(mixedSnapshot.command().definitions().getFirst().sourceCommitId())
        .isEqualTo(mixed.commitId());
    assertThat(fixture.tx(() -> fixture.sources().captureSynchronized(mixed)))
        .isEqualTo(mixedSnapshot);
    assertThat(fixture.tx(() -> fixture.sources().apply(policy)).ownerOutcome())
        .isEqualTo(policyResult.ownerOutcome());
    assertThat(fixture.tx(() -> fixture.sources().apply(command)).ownerOutcome())
        .isEqualTo(commandResult.ownerOutcome());
  }

  @Test
  void requiredOwnerSetMustBeNonEmptyDistinctAndKnownBeforeAnyOwnerFence() {
    Fixture fixture = fixture();
    String base =
        "genesis:"
            + fixture.sources().readGenesis(fixture.target()).orElseThrow().policy().receiptId();
    String[] invalidRequiredOwnerJson = {
      null,
      "[]",
      "\"GAME_DESIGN_CONTROL_PLANE\"",
      "[\"GAME_DESIGN_CONTROL_PLANE\",1]",
      "[\"UNKNOWN_OWNER\"]",
      "[\"GAME_DESIGN_CONTROL_PLANE\",\"GAME_DESIGN_CONTROL_PLANE\"]"
    };

    for (String requiredOwnerJson : invalidRequiredOwnerJson) {
      DraftCommitBinding binding = binding(fixture.target(), base, "0", null, "main", true);
      assertThat(binding.requiredOwners()).containsExactly(Owner.GAME_DESIGN_CONTROL_PLANE);
      String rawBindingJson = withRawRequiredOwners(binding.canonicalJson(), requiredOwnerJson);

      assertThatThrownBy(() -> insertRawDraftCommit(fixture, binding, rawBindingJson))
          .hasMessageContaining(
              "Draft commit requiredOwners must be a non-empty array of distinct known owner strings");
      assertThat(fixture.coordinator().read(fixture.target(), binding.requestId())).isEmpty();
      assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();
      assertThat(fixture.dsl().fetchCount(DSL.table("game_design_draft_commit"))).isZero();
      assertThat(fixture.dsl().fetchCount(DSL.table("game_design_draft_commit_owner_result")))
          .isZero();
      assertThat(fixture.dsl().fetchCount(DSL.table("game_design_draft_commit_visibility_fence")))
          .isZero();
    }
  }

  @Test
  void sourceApplicationsRequireReservedReviewedBaseBeforeEitherOwnerCanWrite() {
    Fixture policyFixture = fixture();
    UUID policyGenesis =
        policyFixture
            .sources()
            .readGenesis(policyFixture.target())
            .orElseThrow()
            .policy()
            .receiptId();
    DraftCommitBinding policy =
        binding(policyFixture.target(), "genesis:" + policyGenesis, "0", null, "main", true);
    assertMissingReviewedBaseDeniesOwnerApply(
        policyFixture,
        policy,
        () -> new RealmPolicySourceRepository(policyFixture.dsl()).apply(policy));
    var policyResult = apply(policyFixture, policy);
    synchronize(policyFixture, policy, policyResult);
    assertThat(policyFixture.tx(() -> policyFixture.sources().apply(policy)).ownerOutcome())
        .isEqualTo(policyResult.ownerOutcome());

    Fixture commandFixture = fixture();
    UUID commandGenesis =
        commandFixture
            .sources()
            .readGenesis(commandFixture.target())
            .orElseThrow()
            .command()
            .receiptId();
    DraftCommitBinding command =
        binding(commandFixture.target(), "genesis:" + commandGenesis, null, "0", null, false);
    assertMissingReviewedBaseDeniesOwnerApply(
        commandFixture,
        command,
        () -> new CommandSourceRepository(commandFixture.dsl()).apply(command));
    var commandResult = apply(commandFixture, command);
    synchronize(commandFixture, command, commandResult);
    assertThat(commandFixture.tx(() -> commandFixture.sources().apply(command)).ownerOutcome())
        .isEqualTo(commandResult.ownerOutcome());
  }

  private static void assertMissingReviewedBaseDeniesOwnerApply(
      Fixture fixture, DraftCommitBinding binding, Runnable ownerApply) {
    var genesis = fixture.sources().readGenesis(fixture.target()).orElseThrow();
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () -> {
                      fixture.coordinator().claim(binding);
                      fixture.coordinator().claimApplicationSlot(binding);
                      fixture
                          .coordinator()
                          .markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
                      ownerApply.run();
                      return null;
                    }))
        .hasMessageContaining("Draft source application requires exact reviewed base evidence");

    assertThat(fixture.sources().readGenesis(fixture.target())).contains(genesis);
    assertThat(fixture.coordinator().read(fixture.target(), binding.requestId())).isEmpty();
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_application")))
        .isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_command_source_application")))
        .isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_reviewed_draft_base"))).isZero();
  }

  @Test
  void sourceRollbackIncompleteScopesAndPublicationCaptureFailClosed() {
    Fixture fixture = fixture();
    DraftCommitBinding incomplete =
        binding(
            fixture.target(),
            "genesis:"
                + fixture
                    .sources()
                    .readGenesis(fixture.target())
                    .orElseThrow()
                    .policy()
                    .receiptId(),
            "0",
            null,
            "private",
            false);
    start(fixture, incomplete);
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.sources().apply(incomplete);
              status.setRollbackOnly();
            });
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_application")))
        .isZero();
    assertThat(
            fixture
                .coordinator()
                .read(fixture.target(), incomplete.requestId())
                .orElseThrow()
                .ownerStates()
                .get(Owner.GAME_DESIGN_CONTROL_PLANE)
                .outcome())
        .isEmpty();
    var result = fixture.tx(() -> fixture.sources().apply(incomplete));
    synchronize(fixture, incomplete, result);

    var unsupported =
        DraftCommitBinding.create(
            fixture.target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            incomplete.commitId().toString(),
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    CommandSource.deletePayload("unused"))),
            List.of(
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    RealmPolicySource.SCOPE,
                    fixture.target().canonicalVersionId().toString(),
                    RealmPolicySource.SCOPE,
                    "effective",
                    "1")));
    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().apply(unsupported)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Complete supported Game Design source scopes");

    var operation =
        fixture.tx(
            () -> {
              try {
                var retained =
                    IsolatedPublicationOwnerSetup.retainSourceBacked(
                        fixture.dsl(), fixture.target(), 1);
                fixture
                    .sources()
                    .captureSynchronized(retained.account().input().selection().selectedCommit());
                return retained;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().freeze(operation)))
        .isInstanceOfSatisfying(
            PublishGateFailureException.class,
            failure ->
                assertThat(failure.failureCode())
                    .isEqualTo(PublishGateFailureCode.INVALID_REALM_ENTRY_POLICY_SET));
    assertThat(fixture.sources().readCapture(operation)).isEmpty();
  }

  @Test
  void emptyRealmPolicySourceFailsWithTypedPublicationGateBeforeCapture() {
    Fixture fixture = fixture();
    var operation =
        fixture.tx(
            () -> {
              try {
                var retained =
                    IsolatedPublicationOwnerSetup.retainSourceBacked(
                        fixture.dsl(), fixture.target(), 1);
                fixture
                    .sources()
                    .captureSynchronized(retained.account().input().selection().selectedCommit());
                return retained;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });

    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().freeze(operation)))
        .isInstanceOfSatisfying(
            PublishGateFailureException.class,
            failure ->
                assertThat(failure.failureCode())
                    .isEqualTo(PublishGateFailureCode.INVALID_REALM_ENTRY_POLICY_SET));
    assertThat(fixture.sources().readCapture(operation)).isEmpty();
  }

  @Test
  void completeSelectedSourcesFreezeTogetherAndExactRetryReadsStoredBytes() {
    Fixture fixture = fixture();
    var authored =
        binding(
            fixture.target(),
            "genesis:"
                + fixture
                    .sources()
                    .readGenesis(fixture.target())
                    .orElseThrow()
                    .policy()
                    .receiptId(),
            "0",
            null,
            "main",
            true);
    synchronize(fixture, authored, apply(fixture, authored));
    var operation =
        fixture.tx(
            () -> {
              try {
                var retained =
                    IsolatedPublicationOwnerSetup.retainSourceBacked(
                        fixture.dsl(), fixture.target(), 1);
                fixture
                    .sources()
                    .captureSynchronized(retained.account().input().selection().selectedCommit());
                return retained;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    assertThat(fixture.sources().readCapture(operation)).isEmpty();
    var selectedBinding = operation.account().input().selection().selectedCommit();
    var selectedRules =
        fixture
            .sources()
            .readSynchronized(fixture.target(), selectedBinding.commitId())
            .orElseThrow()
            .gameplayEvidence()
            .orElseThrow();
    assertThat(selectedRules.entries()).isEmpty();
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.sources().freeze(operation);
              status.setRollbackOnly();
            });
    assertThat(fixture.sources().readCapture(operation)).isEmpty();

    fixture.tx(
        () -> {
          new CommandSourceRepository(fixture.dsl()).freeze(operation);
          new RealmPolicySourceRepository(fixture.dsl()).freeze(operation);
          new net.firedevops.firemud.gamedesign.publication.AssetSourceRepository(fixture.dsl())
              .freeze(operation);
          return null;
        });
    assertThatThrownBy(() -> fixture.sources().readCapture(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_CAPTURE_INCOMPLETE");

    var gameplayCapture = new GameplayRuleSnapshot.Capture(operation, selectedRules);
    byte[] changedOperation = operation.canonicalBytes();
    changedOperation[changedOperation.length - 1] ^= 1;
    assertInvalidGameplayCaptureInsert(
        fixture,
        operation,
        changedOperation,
        gameplayCapture.canonicalBytes(),
        gameplayCapture.digest());
    byte[] changedSnapshot = gameplayCapture.canonicalBytes();
    changedSnapshot[changedSnapshot.length - 1] ^= 1;
    assertInvalidGameplayCaptureInsert(
        fixture, operation, operation.canonicalBytes(), changedSnapshot, gameplayCapture.digest());
    assertInvalidGameplayCaptureInsert(
        fixture,
        operation,
        operation.canonicalBytes(),
        gameplayCapture.canonicalBytes(),
        "sha256:" + "0".repeat(64));

    var first = fixture.tx(() -> fixture.sources().freeze(operation));
    var retry = fixture.tx(() -> fixture.sources().freeze(operation));
    var read = fixture.sources().readCapture(operation).orElseThrow();
    assertThat(retry.command().canonicalBytes()).containsExactly(first.command().canonicalBytes());
    assertThat(retry.policy().canonicalBytes()).containsExactly(first.policy().canonicalBytes());
    assertThat(retry.branding()).isPresent();
    assertThat(retry.branding().orElseThrow().canonicalBytes())
        .containsExactly(first.branding().orElseThrow().canonicalBytes());
    assertThat(read.command().canonicalBytes()).containsExactly(first.command().canonicalBytes());
    assertThat(read.policy().canonicalBytes()).containsExactly(first.policy().canonicalBytes());
    assertThat(read.branding().orElseThrow().canonicalBytes())
        .containsExactly(first.branding().orElseThrow().canonicalBytes());
    assertThat(read.command().snapshot().binding())
        .isEqualTo(operation.account().input().selection().selectedCommit());
    assertThat(read.policy().snapshot().binding())
        .isEqualTo(operation.account().input().selection().selectedCommit());
    assertThat(read.branding().orElseThrow().snapshot().binding())
        .isEqualTo(operation.account().input().selection().selectedCommit());
    assertThat(retry.gameplay()).isPresent();
    assertThat(read.gameplay()).isPresent();
    assertThat(retry.gameplay().orElseThrow().snapshot().entries()).isEmpty();
    assertThat(read.gameplay().orElseThrow().canonicalBytes())
        .containsExactly(first.gameplay().orElseThrow().canonicalBytes());
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_design_gameplay_rule_source_capture SET capture_bytes = ? "
                            + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                        new byte[] {1},
                        fixture.target().canonicalTenantId(),
                        fixture.target().canonicalVersionId()))
        .rootCause()
        .hasMessageContaining("gameplay-rule publication source capture is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "DELETE FROM game_design_gameplay_rule_source_capture "
                            + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                        fixture.target().canonicalTenantId(),
                        fixture.target().canonicalVersionId()))
        .rootCause()
        .hasMessageContaining("gameplay-rule publication source capture is immutable");
    assertThatThrownBy(
            () -> fixture.dsl().execute("TRUNCATE game_design_gameplay_rule_source_capture"))
        .rootCause()
        .hasMessageContaining("gameplay-rule publication source capture cannot be truncated");
  }

  @Test
  void nonemptySelectedGameplaySnapshotIsFrozenWithItsOriginalOperation() {
    Fixture fixture = fixture();
    String genesisBase =
        "genesis:"
            + fixture.sources().readGenesis(fixture.target()).orElseThrow().policy().receiptId();
    DraftCommitBinding authored =
        withGameplay(
            binding(fixture.target(), genesisBase, "0", null, "main", true), gameplayUpsert());
    synchronize(fixture, authored, apply(fixture, authored));
    var operation =
        fixture.tx(
            () -> {
              try {
                var retained =
                    IsolatedPublicationOwnerSetup.retainSourceBacked(
                        fixture.dsl(), fixture.target(), 1);
                fixture
                    .sources()
                    .captureSynchronized(retained.account().input().selection().selectedCommit());
                return retained;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    var selectedBinding = operation.account().input().selection().selectedCommit();
    var selectedRules =
        fixture
            .sources()
            .readSynchronized(fixture.target(), selectedBinding.commitId())
            .orElseThrow()
            .gameplayEvidence()
            .orElseThrow();
    assertThat(selectedRules.entries()).hasSize(1);

    var first = fixture.tx(() -> fixture.sources().freeze(operation));
    var replay = fixture.tx(() -> fixture.sources().freeze(operation));
    assertThat(first.gameplay()).isPresent();
    assertThat(first.gameplay().orElseThrow().snapshot().canonicalBytes())
        .containsExactly(selectedRules.canonicalBytes());
    assertThat(replay.gameplay().orElseThrow().canonicalBytes())
        .containsExactly(first.gameplay().orElseThrow().canonicalBytes());
    assertThat(fixture.sources().readCapture(operation).orElseThrow().gameplay()).isPresent();
  }

  @Test
  void genericGameplayRuleRevisionCannotBeOmittedFromCapture() {
    Fixture fixture = fixture();
    var authored =
        binding(
            fixture.target(),
            "genesis:"
                + fixture
                    .sources()
                    .readGenesis(fixture.target())
                    .orElseThrow()
                    .policy()
                    .receiptId(),
            "0",
            null,
            "main",
            true);
    synchronize(fixture, authored, apply(fixture, authored));
    var operation = retainSelectedOperation(fixture);
    fixture
        .dsl()
        .execute(
            "INSERT INTO revision (tenant_id, version_id, author_account_id, data, revision_kind, logical_revision_id) "
                + "VALUES (?, ?, 1, '{}'::JSONB, 'GAMEPLAY_RULE', 'unbound-legacy-rule')",
            fixture.target().gameDesignVersionTenantKey(),
            fixture.target().gameDesignVersionRowId());

    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().freeze(operation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_GENERIC_SOURCE_NOT_CONVERGED");
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_gameplay_rule_source_capture")))
        .isZero();
  }

  @Test
  void nonemptyLegacyCommandInventoryCannotBeOmittedFromGameplayCapture() {
    Fixture fixture = fixture();
    var authored =
        binding(
            fixture.target(),
            "genesis:"
                + fixture
                    .sources()
                    .readGenesis(fixture.target())
                    .orElseThrow()
                    .policy()
                    .receiptId(),
            "0",
            "0",
            "main",
            true);
    synchronize(fixture, authored, apply(fixture, authored));
    var operation = retainSelectedOperation(fixture);

    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().freeze(operation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_LEGACY_COMMAND_SOURCE_NOT_CONVERGED");
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_gameplay_rule_source_capture")))
        .isZero();
  }

  @Test
  void preGameplayTerminalCaptureStaysUnknownAndCannotBeExtendedAfterV1032() throws Exception {
    Fixture fixture = fixture("1031");
    var authored =
        binding(
            fixture.target(),
            "genesis:"
                + fixture
                    .sources()
                    .readGenesis(fixture.target())
                    .orElseThrow()
                    .policy()
                    .receiptId(),
            "0",
            null,
            "main",
            true);
    synchronize(fixture, authored, apply(fixture, authored));
    var operation =
        fixture.tx(
            () -> {
              try {
                var retained =
                    IsolatedPublicationOwnerSetup.retainSourceBacked(
                        fixture.dsl(), fixture.target(), 1);
                fixture
                    .sources()
                    .captureSynchronized(retained.account().input().selection().selectedCommit());
                return retained;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    fixture.tx(
        () -> {
          new CommandSourceRepository(fixture.dsl()).freeze(operation);
          new RealmPolicySourceRepository(fixture.dsl()).freeze(operation);
          new net.firedevops.firemud.gamedesign.publication.AssetSourceRepository(fixture.dsl())
              .freeze(operation);
          new BrandingSourceRepository(fixture.dsl()).freeze(operation);
          new TemplateConfigSourceRepository(fixture.dsl()).freeze(operation);
          var attempts = new PublishAttemptRepository(fixture.dsl());
          var attempt =
              attempts.findByPublishWorkflowIdForUpdate(operation.workflowId()).orElseThrow();
          attempt.setStatus(PublishAttemptStatus.FAILED);
          attempt.setFailureCode("ISOLATED_PRE_GAMEPLAY_STORAGE_DENIAL");
          attempts.save(attempt);
          new GameDesignPublicationOperationRepository(fixture.dsl())
              .seal(
                  operation.tenantKey(),
                  operation.workflowId(),
                  operation.versionId(),
                  operation.selectionDigest(),
                  false);
          return null;
        });

    var operationBefore =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT request_bytes, xmin::TEXT AS row_xmin FROM game_design_publication_operation "
                        + "WHERE publish_workflow_id = ?",
                    operation.workflowId()),
            "Expected retained publication operation row before V1032 migration");
    byte[] operationBytesBefore = operationBefore.get("request_bytes", byte[].class);
    String operationXminBefore = operationBefore.get("row_xmin", String.class);
    var commandBefore = retainedCapture(fixture, "game_design_command_source_capture");
    var policyBefore = retainedCapture(fixture, "game_design_realm_policy_capture");
    var assetBefore = retainedCapture(fixture, "game_design_asset_source_capture");
    var brandingBefore = retainedCapture(fixture, "game_design_branding_source_capture");
    var templateConfigBefore =
        retainedCapture(fixture, "game_design_template_config_source_capture");

    var migrationDataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    migrationDataSource.setSchema(fixture.schema());
    Flyway.configure()
        .dataSource(migrationDataSource)
        .schemas(fixture.schema())
        .defaultSchema(fixture.schema())
        .placeholders(Map.of("serviceSchema", fixture.schema()))
        .locations(
            "filesystem:"
                + java.nio.file.Path.of("src/main/resources/db/migration")
                    .toAbsolutePath()
                    .normalize())
        .table("flyway_schema_history_game_design_service")
        .load()
        .migrate();

    var operationAfter =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT request_bytes, xmin::TEXT AS row_xmin FROM game_design_publication_operation "
                        + "WHERE publish_workflow_id = ?",
                    operation.workflowId()),
            "Expected retained publication operation row after V1032 migration");
    assertThat(operationAfter.get("request_bytes", byte[].class))
        .containsExactly(operationBytesBefore);
    assertThat(operationAfter.get("row_xmin", String.class)).isEqualTo(operationXminBefore);
    assertCaptureRetained(fixture, "game_design_command_source_capture", commandBefore);
    assertCaptureRetained(fixture, "game_design_realm_policy_capture", policyBefore);
    assertCaptureRetained(fixture, "game_design_asset_source_capture", assetBefore);
    assertCaptureRetained(fixture, "game_design_branding_source_capture", brandingBefore);
    assertCaptureRetained(
        fixture, "game_design_template_config_source_capture", templateConfigBefore);

    var historical = fixture.sources().readCapture(operation).orElseThrow();
    assertThat(historical.gameplay()).isEmpty();
    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().freeze(operation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_PUBLICATION_OPERATION_SEALED");
    assertThatThrownBy(
            () ->
                fixture.tx(() -> new GameplayRuleSourceRepository(fixture.dsl()).freeze(operation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_PUBLICATION_OPERATION_SEALED");
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_gameplay_rule_source_capture")))
        .isZero();
    assertCaptureRetained(fixture, "game_design_command_source_capture", commandBefore);
    assertCaptureRetained(fixture, "game_design_realm_policy_capture", policyBefore);
    assertCaptureRetained(fixture, "game_design_asset_source_capture", assetBefore);
    assertCaptureRetained(fixture, "game_design_branding_source_capture", brandingBefore);
    assertCaptureRetained(
        fixture, "game_design_template_config_source_capture", templateConfigBefore);
  }

  private static void assertInvalidGameplayCaptureInsert(
      Fixture fixture,
      net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation operation,
      byte[] operationBytes,
      byte[] captureBytes,
      String captureDigest) {
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "INSERT INTO game_design_gameplay_rule_source_capture "
                                    + "(canonical_tenant_id, canonical_version_id, publish_workflow_id, commit_id, "
                                    + "operation_bytes, capture_bytes, capture_digest) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId(),
                                operation.workflowId(),
                                operation.account().input().selection().selectedCommit().commitId(),
                                operationBytes,
                                captureBytes,
                                captureDigest)))
        .isInstanceOf(RuntimeException.class);
  }

  private static GameDesignPublicationOperation retainSelectedOperation(Fixture fixture) {
    return fixture.tx(
        () -> {
          try {
            var operation =
                IsolatedPublicationOwnerSetup.retainSourceBacked(
                    fixture.dsl(), fixture.target(), 1);
            fixture
                .sources()
                .captureSynchronized(operation.account().input().selection().selectedCommit());
            return operation;
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        });
  }

  private static CaptureRow retainedCapture(Fixture fixture, String table) {
    var row =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT operation_bytes, capture_bytes, xmin::TEXT AS row_xmin FROM "
                        + table
                        + " WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                    fixture.target().canonicalTenantId(),
                    fixture.target().canonicalVersionId()),
            "Expected retained source capture row in " + table);
    return new CaptureRow(
        row.get("operation_bytes", byte[].class),
        row.get("capture_bytes", byte[].class),
        row.get("row_xmin", String.class));
  }

  private static void assertCaptureRetained(Fixture fixture, String table, CaptureRow before) {
    var after = retainedCapture(fixture, table);
    assertThat(after.operationBytes()).containsExactly(before.operationBytes());
    assertThat(after.captureBytes()).containsExactly(before.captureBytes());
    assertThat(after.xmin()).isEqualTo(before.xmin());
  }

  private record CaptureRow(byte[] operationBytes, byte[] captureBytes, String xmin) {}

  private static GameDesignSourceRepository.Application apply(
      Fixture fixture, DraftCommitBinding binding) {
    start(fixture, binding);
    return fixture.tx(() -> fixture.sources().apply(binding));
  }

  private static void start(Fixture fixture, DraftCommitBinding binding) {
    fixture.tx(
        () -> {
          var reviewed = new GameDesignReviewedBaseRepository(fixture.dsl());
          fixture
              .sources()
              .claimReviewed(
                  binding,
                  reviewed.resolve(
                      fixture.target(),
                      net.firedevops.firemud.common.authoring.DraftBaseReference.parse(
                          binding.baseCommitId())));
          fixture.coordinator().claimApplicationSlot(binding);
          fixture.coordinator().markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
          return null;
        });
  }

  private static void synchronize(
      Fixture fixture, DraftCommitBinding binding, GameDesignSourceRepository.Application result) {
    fixture.tx(
        () -> {
          fixture
              .coordinator()
              .advanceSourceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      binding, List.of(result.ownerOutcome())));
          fixture.sources().captureSynchronized(binding);
          fixture.coordinator().releaseApplicationSlot(binding);
          return null;
        });
  }

  private static DraftCommitBinding binding(
      TargetProof target,
      String base,
      String policyEpoch,
      String commandEpoch,
      String realm,
      boolean production) {
    var revisions = new java.util.ArrayList<RevisionPayload>();
    var scopes = new java.util.ArrayList<AffectedUnit>();
    if (commandEpoch != null) {
      revisions.add(
          new RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.randomUUID(),
              Owner.GAME_DESIGN_CONTROL_PLANE,
              CommandSource.upsertPayload(command("look", realm == null ? "see" : realm))));
      scopes.add(
          new AffectedUnit(
              Owner.GAME_DESIGN_CONTROL_PLANE,
              CommandSource.SCOPE,
              target.canonicalVersionId().toString(),
              CommandSource.SCOPE,
              CommandSource.SCOPE_ID,
              commandEpoch));
    }
    if (policyEpoch != null) {
      String payload =
          "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"authored-"
              + realm
              + "\",\"policy\":{\"schemaVersion\":1,\"worldSlug\":\"world\","
              + "\"worldDisplayName\":\"World\",\"realmSlug\":\""
              + realm
              + "\","
              + "\"realmDisplayName\":\"Realm\",\"visible\":true,\"publicProduction\":"
              + production
              + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}}";
      revisions.add(
          new RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.randomUUID(),
              Owner.GAME_DESIGN_CONTROL_PLANE,
              payload));
      scopes.add(
          new AffectedUnit(
              Owner.GAME_DESIGN_CONTROL_PLANE,
              RealmPolicySource.SCOPE,
              target.canonicalVersionId().toString(),
              RealmPolicySource.SCOPE,
              "effective",
              policyEpoch));
    }
    return DraftCommitBinding.create(
        target, UUID.randomUUID(), UUID.randomUUID(), base, revisions, scopes);
  }

  private static DraftCommitBinding withGameplay(
      DraftCommitBinding binding, String gameplayPayload) {
    var revisions = new java.util.ArrayList<>(binding.revisions());
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            gameplayPayload));
    var scopes = new java.util.ArrayList<>(binding.affectedUnits());
    scopes.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.SCOPE,
            binding.target().canonicalVersionId().toString(),
            GameplayRuleSource.SCOPE,
            GameplayRuleSource.SCOPE_ID,
            "0"));
    return DraftCommitBinding.create(
        binding.target(),
        binding.requestId(),
        binding.commitId(),
        binding.baseCommitId(),
        revisions,
        scopes);
  }

  private static String gameplayUpsert() {
    return GameplayRuleSource.upsertPayload(
        new GameplayRuleManifest.Stat(
            "strength",
            GameplayRuleManifest.PrimitiveKind.NUMERIC,
            new BigDecimal("10"),
            null,
            BigDecimal.ZERO,
            new BigDecimal("20"),
            GameplayRuleManifest.Visibility.PUBLIC,
            List.of()));
  }

  private static DraftCommitBinding assetBinding(
      TargetProof target, String base, String expectedEpoch, String payload) {
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        base,
        List.of(
            new RevisionPayload("0", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                AssetSource.SCOPE,
                target.canonicalVersionId().toString(),
                AssetSource.SCOPE,
                AssetSource.SCOPE_ID,
                expectedEpoch)));
  }

  private static String withRawRequiredOwners(String canonicalJson, String requiredOwnerJson) {
    String validRequiredOwners = "\"requiredOwners\":[\"GAME_DESIGN_CONTROL_PLANE\"]";
    assertThat(canonicalJson).contains(validRequiredOwners);
    if (requiredOwnerJson == null) {
      return canonicalJson.replace(validRequiredOwners + ",", "");
    }
    return canonicalJson.replace(validRequiredOwners, "\"requiredOwners\":" + requiredOwnerJson);
  }

  private static void insertRawDraftCommit(
      Fixture fixture, DraftCommitBinding binding, String rawBindingJson) {
    TargetProof target = binding.target();
    fixture
        .dsl()
        .execute(
            """
            INSERT INTO game_design_draft_commit (
                canonical_tenant_id,
                canonical_version_id,
                game_design_version_row_id,
                game_design_version_tenant_key,
                source_game_row_id,
                source_game_tenant_key,
                source_provenance_kind,
                request_id,
                commit_id,
                base_commit_id,
                input_digest,
                binding_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            target.gameDesignVersionRowId(),
            target.gameDesignVersionTenantKey(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind(),
            binding.requestId(),
            binding.commitId(),
            binding.baseCommitId(),
            binding.digest(),
            rawBindingJson);
  }

  private static String command(String id, String alias) {
    return "{\"schemaVersion\":1,\"commandId\":\""
        + id
        + "\",\"semanticOwner\":\"WORLD\","
        + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
        + "\"promptPolicy\":\"NEVER\",\"actionCategory\":\"GAMEPLAY\",\"historyRecordable\":true,"
        + "\"aliases\":[\""
        + alias
        + "\"],\"actionTags\":[\"WORLD_BROWSE\"],\"effects\":[]}";
  }

  @Test
  void rawLifecycleFirstRejectsSlotInsertAcrossSupportedIsolationLevels() throws Exception {
    for (int isolation : storageIsolationLevels()) {
      var local = fixture();
      var binding = storageBinding(local, "lifecycle-first");
      tx(local).executeWithoutResult(ignored -> claimStorageBinding(local, binding));
      try (var slot = storageConnection(local, isolation);
          var lifecycle = storageConnection(local, Connection.TRANSACTION_READ_COMMITTED);
          var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var slotSql = DSL.using(slot, SQLDialect.POSTGRES);
        slotSql.fetch(
            "SELECT * FROM version WHERE id = ?", local.target().gameDesignVersionRowId());
        DSL.using(lifecycle, SQLDialect.POSTGRES)
            .execute(
                "UPDATE version SET version_state = 'RETIRED', version_state_epoch = version_state_epoch + 1 WHERE id = ?",
                local.target().gameDesignVersionRowId());
        var started = new java.util.concurrent.CountDownLatch(1);
        var inserting =
            worker.submit(
                () -> {
                  started.countDown();
                  return failedStorageWrite(() -> insertRawSlot(slotSql, binding));
                });
        try {
          assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          lifecycle.commit();
        }
        assertThat(inserting.get(20, TimeUnit.SECONDS))
            .isEqualTo(isolation == Connection.TRANSACTION_READ_COMMITTED ? "23514" : "40001");
        slot.rollback();
        assertThat(
                new DraftCommitCoordinatorRepository(local.dsl())
                    .readApplicationSlot(local.target()))
            .isEmpty();
        assertThat(
                local
                    .dsl
                    .fetchSingle(
                        "SELECT version_state FROM version WHERE id = ?",
                        local.target().gameDesignVersionRowId())
                    .get(0, String.class))
            .isEqualTo("RETIRED");
      }
    }
  }

  @Test
  void rawSlotFirstRejectsLifecycleWriteIncludingStaleSnapshotsWithoutChangingVersionFields()
      throws Exception {
    for (int isolation : storageIsolationLevels()) {
      var local = fixture();
      var binding = storageBinding(local, "slot-first");
      tx(local).executeWithoutResult(ignored -> claimStorageBinding(local, binding));
      var before = versionFields(local);
      try (var slot = storageConnection(local, Connection.TRANSACTION_READ_COMMITTED);
          var lifecycle = storageConnection(local, isolation);
          var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var lifecycleSql = DSL.using(lifecycle, SQLDialect.POSTGRES);
        lifecycleSql.fetch(
            "SELECT * FROM version WHERE id = ?", local.target().gameDesignVersionRowId());
        insertRawSlot(DSL.using(slot, SQLDialect.POSTGRES), binding);
        var started = new java.util.concurrent.CountDownLatch(1);
        var updating =
            worker.submit(
                () -> {
                  started.countDown();
                  return failedStorageWrite(
                      () ->
                          lifecycleSql.execute(
                              "UPDATE version SET version_state = 'RETIRED', version_state_epoch = version_state_epoch + 1 WHERE id = ?",
                              local.target().gameDesignVersionRowId()));
                });
        try {
          assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          slot.commit();
        }
        assertThat(updating.get(20, TimeUnit.SECONDS))
            .isEqualTo(isolation == Connection.TRANSACTION_READ_COMMITTED ? "23514" : "40001");
        lifecycle.rollback();
        assertThat(versionFields(local)).isEqualTo(before);
        assertThat(
                new DraftCommitCoordinatorRepository(local.dsl())
                    .readApplicationSlot(local.target()))
            .isPresent();
      }
    }
  }

  @Test
  void rawSelectionFirstRejectsSlotInsertEvenWhenSlotSnapshotPredatesSelection() throws Exception {
    for (int isolation : storageIsolationLevels()) {
      var local = fixture();
      var selected = synchronizedStorageSelection(local);
      var later = storageBinding(local, "after-selection");
      tx(local).executeWithoutResult(ignored -> claimStorageBinding(local, later));
      var before = versionFields(local);
      try (var slot = storageConnection(local, isolation);
          var selection = storageConnection(local, Connection.TRANSACTION_READ_COMMITTED);
          var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var slotSql = DSL.using(slot, SQLDialect.POSTGRES);
        slotSql.fetch(
            "SELECT * FROM version WHERE id = ?", local.target().gameDesignVersionRowId());
        insertRawSelection(DSL.using(selection, SQLDialect.POSTGRES), selected);
        var started = new java.util.concurrent.CountDownLatch(1);
        var inserting =
            worker.submit(
                () -> {
                  started.countDown();
                  return failedStorageWrite(() -> insertRawSlot(slotSql, later));
                });
        try {
          assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          selection.commit();
        }
        assertThat(inserting.get(20, TimeUnit.SECONDS))
            .isEqualTo(isolation == Connection.TRANSACTION_READ_COMMITTED ? "23514" : "40001");
        slot.rollback();
        assertThat(versionFields(local)).isEqualTo(before);
        assertThat(
                new DraftCommitCoordinatorRepository(local.dsl())
                    .readApplicationSlot(local.target()))
            .isEmpty();
        assertThat(
                local.dsl().fetchCount(DSL.table("game_design_authored_draft_publish_selection")))
            .isEqualTo(1);
      }
    }
  }

  @Test
  void rawSlotFirstRejectsSelectionEvenWhenSelectionSnapshotPredatesSlot() throws Exception {
    for (int isolation : storageIsolationLevels()) {
      var local = fixture();
      var selected = synchronizedStorageSelection(local);
      var later = storageBinding(local, "slot-before-selection");
      tx(local).executeWithoutResult(ignored -> claimStorageBinding(local, later));
      var before = versionFields(local);
      try (var slot = storageConnection(local, Connection.TRANSACTION_READ_COMMITTED);
          var selection = storageConnection(local, isolation);
          var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var selectionSql = DSL.using(selection, SQLDialect.POSTGRES);
        selectionSql.fetch(
            "SELECT * FROM version WHERE id = ?", local.target().gameDesignVersionRowId());
        insertRawSlot(DSL.using(slot, SQLDialect.POSTGRES), later);
        var started = new java.util.concurrent.CountDownLatch(1);
        var inserting =
            worker.submit(
                () -> {
                  started.countDown();
                  return failedStorageWrite(() -> insertRawSelection(selectionSql, selected));
                });
        try {
          assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          slot.commit();
        }
        assertThat(inserting.get(20, TimeUnit.SECONDS))
            .isEqualTo(isolation == Connection.TRANSACTION_READ_COMMITTED ? "23514" : "40001");
        selection.rollback();
        assertThat(versionFields(local)).isEqualTo(before);
        assertThat(
                new DraftCommitCoordinatorRepository(local.dsl())
                    .readApplicationSlot(local.target()))
            .isPresent();
        assertThat(
                local.dsl().fetchCount(DSL.table("game_design_authored_draft_publish_selection")))
            .isZero();
      }
    }
  }

  private int[] storageIsolationLevels() {
    return new int[] {
      Connection.TRANSACTION_READ_COMMITTED,
      Connection.TRANSACTION_REPEATABLE_READ,
      Connection.TRANSACTION_SERIALIZABLE
    };
  }

  private Connection storageConnection(Fixture local, int isolation) throws SQLException {
    var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    connection.setSchema(local.schema());
    connection.setTransactionIsolation(isolation);
    connection.setAutoCommit(false);
    DSL.using(connection, SQLDialect.POSTGRES).execute("SET LOCAL lock_timeout = '10s'");
    return connection;
  }

  private String failedStorageWrite(Runnable action) {
    try {
      action.run();
      throw new AssertionError("Conflicting storage write unexpectedly succeeded");
    } catch (org.jooq.exception.DataAccessException failure) {
      Throwable cause = failure;
      while (cause != null) {
        if (cause instanceof SQLException sql) return sql.getSQLState();
        cause = cause.getCause();
      }
      throw failure;
    }
  }

  private String versionFields(Fixture local) {
    return local
        .dsl
        .fetchSingle(
            "SELECT to_jsonb(v)::text FROM version v WHERE id = ?",
            local.target().gameDesignVersionRowId())
        .get(0, String.class);
  }

  private void insertRawSlot(DSLContext sql, DraftCommitBinding binding) {
    sql.execute(
        "INSERT INTO game_design_draft_commit_application_slot "
            + "(canonical_tenant_id, canonical_version_id, request_id, commit_id) VALUES (?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId());
  }

  // Storage-only setup uses existing typed source and coordinator owners. It creates no
  // creator credential, Account order, remote result or publication authority.
  private static DraftCommitBinding storageBinding(Fixture fixture, String description) {
    var current = fixture.coordinator().readVisibilityFence(fixture.target());
    String base =
        current
            .map(fence -> fence.commitId().toString())
            .orElseGet(
                () ->
                    "genesis:"
                        + fixture
                            .sources()
                            .readGenesis(fixture.target())
                            .orElseThrow()
                            .policy()
                            .receiptId());
    String epoch =
        current
            .map(
                fence ->
                    fixture
                        .sources()
                        .readSynchronized(fixture.target(), fence.commitId())
                        .orElseThrow()
                        .command()
                        .sourceEpoch())
            .orElse("0");
    return binding(fixture.target(), base, null, epoch, null, false);
  }

  private static void claimStorageBinding(Fixture fixture, DraftCommitBinding binding) {
    var reviewed = new GameDesignReviewedBaseRepository(fixture.dsl());
    fixture
        .sources()
        .claimReviewed(
            binding,
            reviewed.resolve(
                fixture.target(),
                net.firedevops.firemud.common.authoring.DraftBaseReference.parse(
                    binding.baseCommitId())));
  }

  private static TransactionTemplate tx(Fixture fixture) {
    return fixture.write();
  }

  private static AuthoredDraftPublishSelection synchronizedStorageSelection(Fixture fixture) {
    var binding = storageBinding(fixture, "storage-selected");
    var applied = apply(fixture, binding);
    synchronize(fixture, binding, applied);
    var intent =
        new AuthoredDraftPublishSelection.PublishIntent(
            fixture.target().canonicalTenantId(),
            fixture.target().canonicalVersionId(),
            UUID.randomUUID().toString(),
            "1",
            "controlled storage race",
            binding.requestId(),
            binding.commitId(),
            binding.digest());
    return fixture.tx(
        () ->
            AuthoredDraftPublishSelection.capture(
                intent,
                fixture.target(),
                fixture
                    .coordinator()
                    .requireSynchronizedPublicationEvidence(
                        fixture.target(),
                        binding.requestId(),
                        binding.commitId(),
                        binding.digest())));
  }

  private void insertRawSelection(DSLContext sql, AuthoredDraftPublishSelection selected) {
    var target = selected.target();
    var intent = selected.intent();
    sql.execute(
        "INSERT INTO game_design_authored_draft_publish_selection "
            + "(canonical_tenant_id, canonical_version_id, game_design_version_row_id, "
            + "game_design_version_tenant_key, source_game_row_id, source_game_tenant_key, "
            + "source_provenance_kind, publish_request_id, version_state_epoch, "
            + "selected_commit_request_id, selected_commit_id, selected_commit_digest, selection_digest, selection_json) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        target.gameDesignVersionRowId(),
        target.gameDesignVersionTenantKey(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        target.sourceProvenanceKind(),
        intent.publishRequestId(),
        Long.parseLong(intent.expectedVersionStateEpoch()),
        intent.selectedCommitRequestId(),
        intent.selectedCommitId(),
        intent.selectedCommitDigest(),
        selected.digest(),
        selected.canonicalJson());
  }

  private static Fixture fixture() {
    return fixture(null);
  }

  private static Fixture fixture(String migrationTarget) {
    String schema = "gd_combined_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    var migration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(
                "filesystem:"
                    + java.nio.file.Path.of("src/main/resources/db/migration")
                        .toAbsolutePath()
                        .normalize())
            .table("flyway_schema_history_game_design_service");
    if (migrationTarget != null) migration.target(MigrationVersion.fromVersion(migrationTarget));
    migration.load().migrate();
    var write = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var properties = new PostgresProperties();
    properties.setSchema(schema);
    TargetProof target =
        write.execute(
            status -> {
              var game = new Game();
              game.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              game.setName("ISOLATED composed source");
              game = new GameRepository(dsl).save(game);
              var version = new Version();
              version.setTenantId(game.getTenantId());
              version.setVersionNumber(1);
              version = new VersionRepository(dsl).save(version);
              return new TargetProof(
                  version.getCanonicalTenantId(),
                  version.getCanonicalVersionId(),
                  version.getId(),
                  version.getTenantId(),
                  version.getIdentitySourceGameRowId(),
                  version.getIdentitySourceGameTenantKey(),
                  version.getIdentitySourceProvenanceKind());
            });
    return new Fixture(
        schema,
        dsl,
        write,
        target,
        new GameDesignSourceRepository(dsl),
        new DraftCommitCoordinatorRepository(dsl));
  }

  private record Fixture(
      String schema,
      DSLContext dsl,
      TransactionTemplate write,
      TargetProof target,
      GameDesignSourceRepository sources,
      DraftCommitCoordinatorRepository coordinator) {
    <T> T tx(Supplier<T> work) {
      return write.execute(status -> work.get());
    }
  }
}
