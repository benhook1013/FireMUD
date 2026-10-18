package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.AssetSourceRepository;
import net.firedevops.firemud.gamedesign.publication.BrandingSourceRepository;
import net.firedevops.firemud.gamedesign.publication.CommandSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical storage proof only; Account creator admission is not simulated by this fixture. */
@Testcontainers(disabledWithoutDocker = true)
class BrandingSourcePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void actualFreshGenesisMixedSourceApplicationAndRestartReadbackPreserveOriginalHistory()
      throws Exception {
    var f = fixture(null);
    var logo = asset(f, "logo.png");
    var ordinary = asset(f, "ordinary.png");
    var policy = policyPayload();
    var first =
        binding(
            f,
            "0",
            BrandingSource.SCOPE,
            BrandingSource.upsertPayload(
                logo.getId().toString(),
                "logo.png",
                BrandingSource.Role.LOGO,
                BrandingSource.Requiredness.REQUIRED),
            AssetSource.upsertPayload(
                ordinary.getId().toString(), "ordinary.png", AssetSource.Requiredness.REQUIRED),
            GameplayRuleSource.upsertPayload(
                new GameplayRuleManifest.AdmissionTag("branding-proof")),
            commandUpsert("BRANDING_INITIAL"),
            policy);
    apply(f, first);
    var synchronizedSources =
        new GameDesignSourceRepository(f.dsl)
            .readSynchronized(f.target, first.commitId())
            .orElseThrow();
    var selected = synchronizedSources.branding().orElseThrow();
    assertThat(selected.items()).hasSize(1);
    assertThat(selected.items().getFirst().reference().sourceBinding()).isEqualTo(first);
    assertThat(selected.roleDeclarations())
        .containsEntry("LOGO", "PRESENT")
        .containsEntry("THEME", "EMPTY");
    assertThat(
            synchronizedSources
                .gameplayEvidence()
                .orElseThrow()
                .manifest()
                .families()
                .get(GameplayRuleManifest.Family.ADMISSION_TAGS))
        .containsExactly(new GameplayRuleManifest.AdmissionTag("branding-proof"));
    assertThat(new BrandingSourceRepository(f.dsl).readSnapshot(f.target, UUID.randomUUID()))
        .isEmpty();
    assertThatThrownBy(
            () ->
                f.dsl.execute(
                    "UPDATE game_assets SET data = ? WHERE id = ?", new byte[] {9}, logo.getId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(() -> f.dsl.execute("DELETE FROM game_design_branding_source_revision"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);

    var sibling = binding(f, "1", CommandSource.SCOPE, commandUpsert("BRANDING_SIBLING"));
    apply(f, sibling);
    var inherited =
        new GameDesignSourceRepository(f.dsl)
            .readSynchronized(f.target, sibling.commitId())
            .orElseThrow()
            .branding()
            .orElseThrow();
    assertThat(inherited.items()).isEqualTo(selected.items());
    assertThat(inherited.sourceEpoch()).isEqualTo("1");
    assertThat(inherited.binding()).isEqualTo(sibling);
    var deleted =
        binding(
            f,
            "1",
            BrandingSource.SCOPE,
            BrandingSource.deletePayload("logo.png", BrandingSource.Role.LOGO));
    apply(f, deleted);
    assertThat(
            new BrandingSourceRepository(f.dsl)
                .readSnapshot(f.target, deleted.commitId())
                .orElseThrow()
                .roleDeclarations()
                .values())
        .containsOnly("EMPTY");
    assertThat(
            new BrandingSourceRepository(f.dsl)
                .readSnapshot(f.target, first.commitId())
                .orElseThrow())
        .isEqualTo(selected);
  }

  @Test
  void v1025RetainedVersionPreservesFiveOwnerHistoryWithoutAcquiringBrandingGenesis() {
    var f = fixture("1025");
    var original = retainPreBrandingSourceCommit(f);
    var originalSources =
        List.of(
            "command_source",
            "realm_policy",
            "asset_source",
            "gameplay_rule",
            "template_config_source");
    var originalSourceRows =
        originalSources.stream()
            .map(
                name ->
                    f.dsl.fetchSingle(
                        "SELECT snapshot_json, xmin::text AS row_xmin FROM game_design_"
                            + name
                            + "_snapshot WHERE canonical_version_id = ?",
                        f.target.canonicalVersionId()))
            .toList();
    var sourceJson =
        originalSourceRows.stream().map(row -> row.get("snapshot_json", String.class)).toList();
    var sourceXmins =
        originalSourceRows.stream().map(row -> row.get("row_xmin", String.class)).toList();
    var originalFence =
        f.dsl.fetchSingle(
            "SELECT to_jsonb(f)::text AS row_json, xmin::text AS row_xmin "
                + "FROM game_design_draft_commit_visibility_fence f WHERE canonical_version_id = ?",
            f.target.canonicalVersionId());
    var originalResult =
        f.dsl.fetchSingle(
            "SELECT result_bytes, xmin::text AS row_xmin "
                + "FROM game_design_draft_commit_owner_result WHERE canonical_version_id = ?",
            f.target.canonicalVersionId());
    var originalCommit =
        f.dsl.fetchSingle(
            "SELECT binding_json, input_digest, xmin::text AS row_xmin "
                + "FROM game_design_draft_commit WHERE canonical_version_id = ? AND commit_id = ?",
            f.target.canonicalVersionId(),
            original.commitId());
    var v1025MigrationHistory =
        f.dsl
            .fetch(
                "SELECT version || ':' || script || ':' || checksum || ':' || success AS history_entry "
                    + "FROM flyway_schema_history_game_design_service "
                    + "WHERE version IS NOT NULL ORDER BY installed_rank")
            .getValues("history_entry", String.class);

    f.migrate();
    assertThat(
            f.dsl
                .fetch(
                    "SELECT version || ':' || script || ':' || checksum || ':' || success AS history_entry "
                        + "FROM flyway_schema_history_game_design_service "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank LIMIT ?",
                    v1025MigrationHistory.size())
                .getValues("history_entry", String.class))
        .containsExactlyElementsOf(v1025MigrationHistory);
    var historical =
        new GameDesignSourceRepository(f.dsl)
            .readSynchronized(f.target, original.commitId())
            .orElseThrow();
    assertThat(historical.branding()).isEmpty();
    assertThat(historical.command().binding()).isEqualTo(original);
    assertThat(historical.templateConfig()).isPresent();
    assertThat(
            List.of(
                historical.command().canonicalJson(),
                historical.policy().canonicalJson(),
                historical.asset().canonicalJson(),
                historical.gameplayEvidence().orElseThrow().canonicalJson(),
                historical.templateConfig().orElseThrow().canonicalJson()))
        .isEqualTo(sourceJson);
    assertThat(
            originalSources.stream()
                .map(
                    name ->
                        f.dsl
                            .fetchSingle(
                                "SELECT xmin::text AS row_xmin FROM game_design_"
                                    + name
                                    + "_snapshot WHERE canonical_version_id = ?",
                                f.target.canonicalVersionId())
                            .get("row_xmin", String.class))
                .toList())
        .isEqualTo(sourceXmins);
    var migratedFence =
        f.dsl.fetchSingle(
            "SELECT to_jsonb(f)::text AS row_json, xmin::text AS row_xmin "
                + "FROM game_design_draft_commit_visibility_fence f WHERE canonical_version_id = ?",
            f.target.canonicalVersionId());
    assertThat(migratedFence.get("row_json", String.class))
        .isEqualTo(originalFence.get("row_json", String.class));
    assertThat(migratedFence.get("row_xmin", String.class))
        .isEqualTo(originalFence.get("row_xmin", String.class));
    var migratedResult =
        f.dsl.fetchSingle(
            "SELECT result_bytes, xmin::text AS row_xmin "
                + "FROM game_design_draft_commit_owner_result WHERE canonical_version_id = ?",
            f.target.canonicalVersionId());
    assertThat(migratedResult.get("result_bytes", byte[].class))
        .isEqualTo(originalResult.get("result_bytes", byte[].class));
    assertThat(migratedResult.get("row_xmin", String.class))
        .isEqualTo(originalResult.get("row_xmin", String.class));
    var migratedCommit =
        f.dsl.fetchSingle(
            "SELECT binding_json, input_digest, xmin::text AS row_xmin "
                + "FROM game_design_draft_commit WHERE canonical_version_id = ? AND commit_id = ?",
            f.target.canonicalVersionId(),
            original.commitId());
    assertThat(migratedCommit.get("binding_json", String.class))
        .isEqualTo(originalCommit.get("binding_json", String.class));
    assertThat(migratedCommit.get("input_digest", String.class))
        .isEqualTo(originalCommit.get("input_digest", String.class));
    assertThat(migratedCommit.get("row_xmin", String.class))
        .isEqualTo(originalCommit.get("row_xmin", String.class));
    assertThatThrownBy(
            () ->
                f.tx.execute(
                    ignored -> new GameDesignSourceRepository(f.dsl).captureSynchronized(original)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_GENESIS_INCOMPLETE");
    assertThat(new BrandingSourceRepository(f.dsl).readGenesis(f.target)).isEmpty();
    assertThatThrownBy(
            () ->
                f.tx.execute(
                    ignored -> new BrandingSourceRepository(f.dsl).enrollFreshDraft(f.target)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BRANDING_SOURCE_FRESH_INSERT_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                f.tx.execute(
                    ignored -> new BrandingSourceRepository(f.dsl).captureSynchronized(original)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BRANDING_SOURCE_GENESIS_UNAVAILABLE");
    assertThatThrownBy(() -> new GameDesignSourceRepository(f.dsl).readGenesis(f.target))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_GENESIS_INCOMPLETE");
  }

  @Test
  void retainedV2CaptureWithoutBrandingRemainsReadableAsPartialHistory() throws Exception {
    var f = fixture("1025");
    var original = retainPreBrandingSourceCommit(f);
    var operation = retainPreBrandingV2Capture(f, original);
    var captureRows =
        Map.of(
            "command",
            retainedCapture(f, "game_design_command_source_capture", operation.workflowId()),
            "realm_policy",
            retainedCapture(f, "game_design_realm_policy_capture", operation.workflowId()),
            "asset",
            retainedCapture(f, "game_design_asset_source_capture", operation.workflowId()),
            "template_config",
            retainedCapture(f, "game_design_template_config_source_capture", operation.workflowId()));
    var operationBefore =
        f.dsl.fetchSingle(
            "SELECT request_bytes, xmin::text AS row_xmin FROM game_design_publication_operation "
                + "WHERE publish_workflow_id = ?",
            operation.workflowId());
    byte[] operationBytes = operationBefore.get("request_bytes", byte[].class);

    f.migrate();

    var operationAfter =
        f.dsl.fetchSingle(
            "SELECT request_bytes, xmin::text AS row_xmin FROM game_design_publication_operation "
                + "WHERE publish_workflow_id = ?",
            operation.workflowId());
    assertThat(operationAfter.get("request_bytes", byte[].class)).containsExactly(operationBytes);
    assertThat(operationAfter.get("row_xmin", String.class))
        .isEqualTo(operationBefore.get("row_xmin", String.class));
    captureRows.forEach(
        (family, before) ->
            assertRetainedCapture(
                f,
                switch (family) {
                  case "command" -> "game_design_command_source_capture";
                  case "realm_policy" -> "game_design_realm_policy_capture";
                  case "asset" -> "game_design_asset_source_capture";
                  case "template_config" -> "game_design_template_config_source_capture";
                  default -> throw new IllegalStateException("Unknown capture family " + family);
                },
                operation.workflowId(),
                before));

    var retained = new GameDesignSourceRepository(f.dsl).readCapture(operation).orElseThrow();
    assertThat(retained.branding()).isEmpty();
    assertThat(retained.gameplay()).isEmpty();
    assertThat(retained.command().canonicalBytes())
        .containsExactly(captureRows.get("command").captureBytes());
    assertThat(retained.policy().canonicalBytes())
        .containsExactly(captureRows.get("realm_policy").captureBytes());
    assertThat(retained.asset().orElseThrow().canonicalBytes())
        .containsExactly(captureRows.get("asset").captureBytes());
    assertThat(retained.templateConfig().orElseThrow().canonicalBytes())
        .containsExactly(captureRows.get("template_config").captureBytes());
    assertThatThrownBy(
            () -> f.tx.execute(ignored -> new GameDesignSourceRepository(f.dsl).freeze(operation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_GENESIS_INCOMPLETE");
  }

  @Test
  void retainedCaptureWithoutBrandingStillFailsForBrandingEnrolledTarget() throws Exception {
    var f = fixture(null);
    var operation = retainFreshV2CaptureWithoutBranding(f);
    assertThat(new BrandingSourceRepository(f.dsl).readGenesis(f.target)).isPresent();
    for (String table :
        List.of(
            "game_design_command_source_capture",
            "game_design_realm_policy_capture",
            "game_design_asset_source_capture",
            "game_design_gameplay_rule_source_capture",
            "game_design_template_config_source_capture")) {
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT count(*) FROM " + table + " WHERE publish_workflow_id = ?",
                      operation.workflowId())
                  .get(0, Long.class))
          .as(table)
          .isEqualTo(1L);
    }
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT count(*) FROM game_design_branding_source_capture "
                        + "WHERE publish_workflow_id = ?",
                    operation.workflowId())
                .get(0, Long.class))
        .isZero();
    assertThatThrownBy(() -> new GameDesignSourceRepository(f.dsl).readCapture(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_CAPTURE_INCOMPLETE");
  }

  @Test
  void wrongTenantSourceRowCannotWriteOrExposeSnapshot() {
    var f = fixture(null);
    var foreignGame = new Game();
    foreignGame.setTenantId(UUID.randomUUID().toString());
    foreignGame.setName("Foreign asset owner");
    var foreign =
        Objects.requireNonNull(
            f.tx.execute(ignored -> new GameRepository(f.dsl).save(foreignGame)));
    var source = new GameAsset();
    source.setTenantId(foreign.getTenantId());
    source.setFileName("foreign.png");
    source.setContentType("image/png");
    source.setData(new byte[] {1});
    var foreignAsset =
        Objects.requireNonNull(
            f.tx.execute(ignored -> new GameAssetRepository(f.dsl).save(source)));
    var payload =
        BrandingSource.upsertPayload(
            foreignAsset.getId().toString(),
            "foreign.png",
            BrandingSource.Role.FAVICON,
            BrandingSource.Requiredness.REQUIRED);
    var binding = binding(f, "0", BrandingSource.SCOPE, payload);
    assertThatThrownBy(() -> apply(f, binding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BRANDING_SOURCE_ROW_UNAVAILABLE");
    assertThat(new BrandingSourceRepository(f.dsl).readSnapshot(f.target, binding.commitId()))
        .isEmpty();
  }

  private DraftCommitBinding retainPreBrandingSourceCommit(Fixture f) {
    var original = binding(f, "0", CommandSource.SCOPE, commandUpsert("BRANDING_HISTORICAL"));
    f.tx.executeWithoutResult(
        ignored -> {
          var coordinator = new DraftCommitCoordinatorRepository(f.dsl);
          coordinator.claim(original);
          var policyGenesis = new RealmPolicySourceRepository(f.dsl).readGenesis(f.target).orElseThrow();
          var commands = new CommandSourceRepository(f.dsl);
          var commandGenesis = commands.readNewDraftGenesis(f.target).orElseThrow();
          var assetGenesis = new AssetSourceRepository(f.dsl).readGenesis(f.target).orElseThrow();
          var gameplayGenesis =
              new GameplayRuleSourceRepository(f.dsl).readGenesis(f.target).orElseThrow();
          var templateGenesis =
              new TemplateConfigSourceRepository(f.dsl).readGenesis(f.target).orElseThrow();
          var reviewedEvidence =
              GameDesignReviewedBaseEvidence.genesis(
                  new GameDesignSourceRepository.Genesis(
                      policyGenesis,
                      commandGenesis,
                      Optional.of(assetGenesis),
                      Optional.of(gameplayGenesis),
                      Optional.of(templateGenesis)));
          insertReviewedBaseV2(f.dsl, original, reviewedEvidence);
          coordinator.claimApplicationSlot(original);
          coordinator.markOwnerInProgress(
              original, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
          var applied = commands.apply(original).orElseThrow();
          var out = new java.io.ByteArrayOutputStream();
          net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
              out, "game-design-control-plane-command-application/v1");
          net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
              out, original.canonicalBytes());
          net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
              out, applied.canonicalBytes());
          var outcome =
              new DraftCommitCoordinatorRepository.OwnerOutcome(
                  DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                  DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
                  original.commitId(),
                  original.digest(),
                  applied.resultIdentity(),
                  out.toByteArray(),
                  List.of(applied.commandAppliedEpoch()));
          coordinator.recordOwnerOutcome(original, outcome);
          coordinator.advanceVisibilityFence(
              original,
              new DraftCommitCoordinatorRepository.CoordinatorProof(original, List.of(outcome)));
          commands.captureSynchronized(original);
          new RealmPolicySourceRepository(f.dsl).captureSynchronized(original);
          new AssetSourceRepository(f.dsl).captureSynchronized(original);
          new GameplayRuleSourceRepository(f.dsl).captureSynchronized(original);
          new TemplateConfigSourceRepository(f.dsl).captureSynchronized(original);
          coordinator.releaseApplicationSlot(original);
        });
    return original;
  }

  private GameDesignPublicationOperation retainPreBrandingV2Capture(
      Fixture f, DraftCommitBinding original) throws Exception {
    var seed =
        IsolatedPublicationOperationFixtures.fresh(f.target, original.commitId().toString());
    var selectedCommit = seed.account().input().selection().selectedCommit();
    var world = seed.world();
    return Objects.requireNonNull(
        f.tx.execute(
            ignored -> {
              var coordinator = new DraftCommitCoordinatorRepository(f.dsl);
              var sources = new GameDesignSourceRepository(f.dsl);
              var originalSources =
                  sources.readSynchronized(f.target, original.commitId()).orElseThrow();
              var originalOutcome =
                  coordinator.read(f.target, original.requestId()).orElseThrow();
              var reviewed = GameDesignReviewedBaseEvidence.authored(originalSources, originalOutcome);
              sources.claimReviewed(selectedCommit, reviewed);
              coordinator.claimApplicationSlot(selectedCommit);
              coordinator.markOwnerInProgress(
                  selectedCommit, DraftCommitBinding.Owner.WORLD_MANAGEMENT);
              var epochs =
                  selectedCommit.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
                      .map(
                          unit ->
                              new DraftCommitCoordinatorRepository.AppliedEpoch(
                                  unit.aggregateType(),
                                  unit.aggregateId(),
                                  unit.scopeType(),
                                  unit.scopeId(),
                                  unit.expectedEpoch(),
                                  new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
                      .toList();
              var outcome =
                  new DraftCommitCoordinatorRepository.OwnerOutcome(
                      DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                      DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
                      selectedCommit.commitId(),
                      selectedCommit.digest(),
                      "ISOLATED-world-applied",
                      world.appliedResultBytes(),
                      epochs);
              coordinator.recordOwnerOutcome(selectedCommit, outcome);
              coordinator.advanceVisibilityFence(
                  selectedCommit,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      selectedCommit, List.of(outcome)));
              new CommandSourceRepository(f.dsl).captureSynchronized(selectedCommit);
              new RealmPolicySourceRepository(f.dsl).captureSynchronized(selectedCommit);
              new AssetSourceRepository(f.dsl).captureSynchronized(selectedCommit);
              new GameplayRuleSourceRepository(f.dsl).captureSynchronized(selectedCommit);
              new TemplateConfigSourceRepository(f.dsl).captureSynchronized(selectedCommit);
              coordinator.releaseApplicationSlot(selectedCommit);

              var intent =
                  new AuthoredDraftPublishSelection.PublishIntent(
                      f.target.canonicalTenantId(),
                      f.target.canonicalVersionId(),
                      UUID.randomUUID().toString(),
                      "1",
                      "ISOLATED retained pre-branding source capture",
                      selectedCommit.requestId(),
                      selectedCommit.commitId(),
                      selectedCommit.digest());
              var selection =
                  new AuthoredDraftPublishSelectionRepository(f.dsl, coordinator)
                      .reserve(intent)
                      .selection();
              var selectionBinding =
                  AuthoredDraftPublishSelectionBinding.fromStored(
                      selection.canonicalJson(), selection.digest());
              var operation =
                  IsolatedPublicationOperationFixtures.forSelection(selectionBinding, world);
              var attempt = new PublishAttempt();
              attempt.setTenantId(f.target.gameDesignVersionTenantKey());
              attempt.setPublishWorkflowId(operation.workflowId());
              attempt.setPublishType(PublishType.FULL_VERSION);
              attempt.setVersionId(f.target.gameDesignVersionRowId());
              attempt.setVersionNumber(
                  f.dsl
                      .fetchSingle(
                          "SELECT version_number FROM version WHERE id = ?",
                          f.target.gameDesignVersionRowId())
                      .get("version_number", Integer.class));
              attempt.setRequestDigest(selection.digest());
              new PublishAttemptRepository(f.dsl).save(attempt);
              var operations = new GameDesignPublicationOperationRepository(f.dsl);
              operations.reserve(operation);
              new CommandSourceRepository(f.dsl).freeze(operation);
              new RealmPolicySourceRepository(f.dsl).freeze(operation);
              new AssetSourceRepository(f.dsl).freeze(operation);
              new TemplateConfigSourceRepository(f.dsl).freeze(operation);
              var attempts = new PublishAttemptRepository(f.dsl);
              var retainedAttempt =
                  attempts.findByPublishWorkflowIdForUpdate(operation.workflowId()).orElseThrow();
              retainedAttempt.setStatus(PublishAttemptStatus.FAILED);
              retainedAttempt.setFailureCode("ISOLATED_PRE_BRANDING_CAPTURE_HISTORY");
              attempts.save(retainedAttempt);
              operations.seal(
                  operation.tenantKey(),
                  operation.workflowId(),
                  operation.versionId(),
                  operation.selectionDigest(),
                  false);
              return operation;
            }));
  }

  private GameDesignPublicationOperation retainFreshV2CaptureWithoutBranding(Fixture f) {
    return Objects.requireNonNull(
        f.tx.execute(
            ignored -> {
              try {
                var operation =
                    IsolatedPublicationOwnerSetup.retainSourceBacked(f.dsl, f.target, 1);
                new CommandSourceRepository(f.dsl).freeze(operation);
                new RealmPolicySourceRepository(f.dsl).freeze(operation);
                new AssetSourceRepository(f.dsl).freeze(operation);
                new GameplayRuleSourceRepository(f.dsl).freeze(operation);
                new TemplateConfigSourceRepository(f.dsl).freeze(operation);
                return operation;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            }));
  }

  private RetainedCapture retainedCapture(Fixture f, String table, String workflowId) {
    var row =
        f.dsl.fetchSingle(
            "SELECT operation_bytes, capture_bytes, xmin::text AS row_xmin FROM "
                + table
                + " WHERE publish_workflow_id = ?",
            workflowId);
    return new RetainedCapture(
        row.get("operation_bytes", byte[].class),
        row.get("capture_bytes", byte[].class),
        row.get("row_xmin", String.class));
  }

  private void assertRetainedCapture(
      Fixture f, String table, String workflowId, RetainedCapture original) {
    var retained = retainedCapture(f, table, workflowId);
    assertThat(retained.operationBytes()).containsExactly(original.operationBytes());
    assertThat(retained.captureBytes()).containsExactly(original.captureBytes());
    assertThat(retained.rowXmin()).isEqualTo(original.rowXmin());
  }

  private record RetainedCapture(byte[] operationBytes, byte[] captureBytes, String rowXmin) {}

  private static void insertReviewedBaseV2(
      DSLContext dsl, DraftCommitBinding binding, GameDesignReviewedBaseEvidence reviewedEvidence) {
    if (!binding.target().equals(reviewedEvidence.target())
        || !binding.baseCommitId().equals(reviewedEvidence.reference().canonicalValue())) {
      throw new IllegalArgumentException(
          "Reviewed genesis evidence must match the exact historical Draft base");
    }
    var reference = reviewedEvidence.reference();
    dsl.execute(
        "INSERT INTO game_design_reviewed_draft_base "
            + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, input_digest, "
            + "binding_json, base_kind, base_identity, base_reference, evidence_schema, "
            + "evidence_bytes, evidence_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        binding.digest(),
        binding.canonicalJson(),
        reference.kind().name(),
        reference.identity(),
        reference.canonicalValue(),
        GameDesignReviewedBaseEvidence.SCHEMA,
        reviewedEvidence.canonicalBytes(),
        reviewedEvidence.digest());
  }

  private void apply(Fixture f, DraftCommitBinding binding) {
    f.tx.executeWithoutResult(
        ignored -> {
          var sources = new GameDesignSourceRepository(f.dsl);
          sources.claimReviewed(
              binding,
              new GameDesignReviewedBaseRepository(f.dsl)
                  .resolve(f.target, DraftBaseReference.parse(binding.baseCommitId())));
          var coordinator = new DraftCommitCoordinatorRepository(f.dsl);
          coordinator.claimApplicationSlot(binding);
          coordinator.markOwnerInProgress(
              binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
          var applied = sources.apply(binding);
          coordinator.advanceSourceVisibilityFence(
              binding,
              new DraftCommitCoordinatorRepository.CoordinatorProof(
                  binding, List.of(applied.ownerOutcome())));
          sources.captureSynchronized(binding);
          coordinator.releaseApplicationSlot(binding);
        });
  }

  private static String commandUpsert(String commandId) {
    return CommandSource.upsertPayload(
        "{\"schemaVersion\":1,\"commandId\":\""
            + commandId
            + "\",\"semanticOwner\":\"WORLD\",\"executionDiscipline\":\"DURABLE_GAMEPLAY\","
            + "\"stageRequirement\":\"GAMEPLAY\",\"promptPolicy\":\"NEVER\","
            + "\"actionCategory\":\"GAMEPLAY\",\"historyRecordable\":true,"
            + "\"aliases\":[],\"actionTags\":[\"WORLD_BROWSE\"],\"effects\":[]}");
  }

  private DraftCommitBinding binding(
      Fixture f, String epoch, String primaryScope, String... payloads) {
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>();
    var scopes = new java.util.TreeSet<String>();
    scopes.add(primaryScope);
    for (int i = 0; i < payloads.length; i++) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(i),
              UUID.randomUUID(),
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              payloads[i]));
      if (payloads[i].contains("\"revisionKind\":\"ASSET_REFERENCE\""))
        scopes.add(AssetSource.SCOPE);
      if (payloads[i].contains("\"revisionKind\":\"GAMEPLAY_RULE\""))
        scopes.add(GameplayRuleSource.SCOPE);
      if (payloads[i].contains("\"revisionKind\":\"COMMAND_DEFINITION\""))
        scopes.add(CommandSource.SCOPE);
      if (payloads[i].contains("\"revisionKind\":\"REALM_ENTRY_POLICY\""))
        scopes.add(net.firedevops.firemud.common.gamedesign.RealmPolicySource.SCOPE);
    }
    var units =
        scopes.stream()
            .map(
                scope ->
                    new DraftCommitBinding.AffectedUnit(
                        DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                        scope,
                        f.target.canonicalVersionId().toString(),
                        scope,
                        "effective",
                        scope.equals(primaryScope) ? epoch : "0"))
            .toList();
    return DraftCommitBinding.create(
        f.target, UUID.randomUUID(), UUID.randomUUID(), currentBaseReference(f), revisions, units);
  }

  private static String currentBaseReference(Fixture f) {
    UUID visibleCommit =
        f.dsl
            .fetchSingle(
                "SELECT visible_commit_id FROM game_design_realm_policy_source "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                f.target.canonicalTenantId(),
                f.target.canonicalVersionId())
            .get("visible_commit_id", UUID.class);
    if (visibleCommit != null) return visibleCommit.toString();
    UUID genesisReceipt =
        new net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository(f.dsl)
            .readGenesis(f.target)
            .orElseThrow()
            .receiptId();
    return "genesis:" + genesisReceipt;
  }

  private static String policyPayload() {
    return "{\"revisionKind\":\"REALM_ENTRY_POLICY\","
        + "\"logicalRevisionId\":\"isolated-finalizer-main\","
        + "\"policy\":{\"schemaVersion\":1,\"worldSlug\":\"world\","
        + "\"worldDisplayName\":\"World\",\"realmSlug\":\"main\","
        + "\"realmDisplayName\":\"Main\",\"visible\":true,"
        + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
        + "\"entryPolicy\":\"PRESEEDED_ONLY\"}}";
  }

  private GameAsset asset(Fixture f, String name) {
    var asset = new GameAsset();
    asset.setTenantId(f.target.gameDesignVersionTenantKey());
    asset.setFileName(name);
    asset.setContentType("image/png");
    asset.setData(name.getBytes(StandardCharsets.UTF_8));
    return Objects.requireNonNull(
        f.tx.execute(ignored -> new GameAssetRepository(f.dsl).save(asset)));
  }

  private Fixture fixture(String maximumMigration) {
    var schema = "branding_source_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    data.setSchema(schema);
    var config =
        Flyway.configure()
            .dataSource(data)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history_game_design_service")
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (maximumMigration != null) config.target(maximumMigration);
    config.load().migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(data));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("Branding source storage fixture");
    var savedGame =
        Objects.requireNonNull(tx.execute(ignored -> new GameRepository(dsl).save(game)));
    var version = new Version();
    version.setTenantId(savedGame.getTenantId());
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    var saved =
        Objects.requireNonNull(
            tx.execute(
                ignored -> {
                  if (maximumMigration == null) return new VersionRepository(dsl).save(version);
                  // Retained fixture uses the V1025 producer transaction, with every then-current
                  // source genesis except Branding deliberately enrolled.
                  long id =
                      dsl.fetchSingle(
                              "INSERT INTO version (tenant_id, canonical_tenant_id, canonical_version_id, identity_source_game_row_id, identity_source_game_tenant_key, identity_source_provenance_kind, version_number, version_state, version_state_epoch, is_script_only, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'NEW_GAME_ROW', 1, 'DRAFT', 1, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                              savedGame.getTenantId(),
                              savedGame.getCanonicalTenantId(),
                              UUID.randomUUID(),
                              savedGame.getId(),
                              savedGame.getTenantId())
                          .get(0, Long.class);
                  var retained = new VersionRepository(dsl).findById(id).orElseThrow();
                  var oldTarget =
                      new DraftCommitBinding.TargetProof(
                          retained.getCanonicalTenantId(),
                          retained.getCanonicalVersionId(),
                          id,
                          retained.getTenantId(),
                          retained.getIdentitySourceGameRowId(),
                          retained.getIdentitySourceGameTenantKey(),
                          retained.getIdentitySourceProvenanceKind());
                  var policy =
                      new net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository(
                              dsl)
                          .recordFreshGenesis(oldTarget);
                  new net.firedevops.firemud.gamedesign.publication.CommandSourceRepository(dsl)
                      .enrollNewDraftGenesis(
                          new CommandSource.NewDraftGenesisReceipt(
                              oldTarget, policy.receiptId(), policy.creationTransactionId()));
                  new net.firedevops.firemud.gamedesign.publication.AssetSourceRepository(dsl)
                      .enrollFreshDraft(oldTarget);
                  new net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository(
                          dsl)
                      .enrollFreshDraft(oldTarget);
                  new net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceRepository(
                          dsl)
                      .enrollFreshDraft(oldTarget);
                  return retained;
                }));
    var target =
        new DraftCommitBinding.TargetProof(
            saved.getCanonicalTenantId(),
            saved.getCanonicalVersionId(),
            saved.getId(),
            saved.getTenantId(),
            saved.getIdentitySourceGameRowId(),
            saved.getIdentitySourceGameTenantKey(),
            saved.getIdentitySourceProvenanceKind());
    return new Fixture(dsl, tx, target, data, schema);
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate tx,
      DraftCommitBinding.TargetProof target,
      DriverManagerDataSource data,
      String schema) {
    void migrate() {
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history_game_design_service")
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
    }
  }
}
