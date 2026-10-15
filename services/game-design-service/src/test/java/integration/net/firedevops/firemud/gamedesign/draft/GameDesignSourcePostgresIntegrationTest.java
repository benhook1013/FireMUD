package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.gamedesign.publication.CommandSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
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
    Fixture fixture = fixture(branding);
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
            "0",
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
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.sources().freeze(operation);
              status.setRollbackOnly();
            });
    assertThat(fixture.sources().readCapture(operation)).isEmpty();
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
  }

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

  private static Fixture fixture() {
    return fixture(false);
  }

  private static Fixture fixture(boolean branding) {
    String schema = "gd_combined_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
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
        .table("flyway_schema_history_game_design_service")
        .load()
        .migrate();
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
              if (branding) {
                dsl.execute(
                    "INSERT INTO game_design_branding_source_genesis "
                        + "(canonical_tenant_id, canonical_version_id, version_id, receipt_id, creation_transaction_id, target_json, roles_json) "
                        + "SELECT canonical_tenant_id, canonical_version_id, version_id, ?, creation_transaction_id, target_json, "
                        + "'{\"RESOURCE\":\"EMPTY\",\"LOGO\":\"EMPTY\",\"FAVICON\":\"EMPTY\",\"THEME\":\"EMPTY\"}' "
                        + "FROM game_design_asset_source_genesis WHERE version_id = ?",
                    UUID.randomUUID(),
                    version.getId());
                dsl.execute(
                    "INSERT INTO game_design_branding_source_head (canonical_tenant_id, canonical_version_id, source_epoch, genesis_receipt_id) "
                        + "SELECT canonical_tenant_id, canonical_version_id, '0', receipt_id FROM game_design_branding_source_genesis WHERE version_id = ?",
                    version.getId());
              }
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
