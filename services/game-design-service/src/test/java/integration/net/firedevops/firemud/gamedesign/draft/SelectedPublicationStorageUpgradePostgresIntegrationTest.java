package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.CommandSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyGenesis;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Native storage upgrade proof preserving retained v1 and mandatory complete v2 decoding. Account,
 * World APPLIED/freeze and complete inventory input are explicitly stipulated external test data.
 * Real Game Design repositories write the Version, coordinator, selection and attempt; all database
 * guards remain enabled. This does not prove authenticated production or publication activation.
 * Qualification requires physical Docker execution with zero skips; unavailable Docker is unrun
 * proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class SelectedPublicationStorageUpgradePostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String V2 = "game-design-publication-operation/v2";

  // Exact installed parent artifacts at Entity source 9526190049a86feef40cd616ec7b5eb931247e8b.
  private static final Set<String> PARENT_MIGRATION_SCRIPTS =
      Set.of(
          "V10__version_asset_artifact.sql",
          "V11__launch_descriptor_framework.sql",
          "V12__version_asset_purge_workflow.sql",
          "V13__version_state_control_plane.sql",
          "V14__recorded_participant_digest.sql",
          "V15__template_remap_sets.sql",
          "V16__version_asset_artifact_export_version.sql",
          "V17__revision_version_scope.sql",
          "V18__published_plugin_versions.sql",
          "V19__plugin_publication_policy_metadata.sql",
          "V1__init.sql",
          "V20__plugin_publication_status_reason.sql",
          "V21__plugin_version_status_events.sql",
          "V22__game_authored_help_topics.sql",
          "V23__published_command_definitions.sql",
          "V24__publication_workflow_identity_capacity.sql",
          "V25__persist_script_patch_attempt_scope.sql",
          "V26__bind_participant_digest_patch_scope.sql",
          "V27__enforce_script_patch_artifact_identity.sql",
          "V28__quarantine_legacy_unattested_full_versions.sql",
          "V29__canonical_game_tenant_identity.sql",
          "V2__game_templates.sql",
          "V30__game_tenant_creation_operations.sql",
          "V34.1__authored_world_source_operations.sql",
          "V35.1__canonical_version_identity.sql",
          "V35.2__canonical_release_version_identity.sql",
          "V35__authored_source_launch_descriptor_binding.sql",
          "V36__persist_opaque_published_release_bundle_reference.sql",
          "V37__version_asset_export_snapshot.sql",
          "V38__published_asset_digest_evidence.sql",
          "V39__publication_evidence_text_storage.sql",
          "V3__game_assets.sql",
          "V41__world_authored_source_delivery.sql",
          "V42__enforce_version_asset_export_source_digest.sql",
          "V49.1__selected_world_release_participant_schema.sql",
          "V49__published_world_start_location_evidence.sql",
          "V4__add_tenant_indexes.sql",
          "V50.10__immutable_command_source.sql",
          "V50.11__immutable_reviewed_draft_base.sql",
          "V50.12__realm_policy_source_head_transition_guard.sql",
          "V50.13__stable_publication_release_row_evidence.sql",
          "V50.14__draft_visibility_and_realm_policy_source_insert_guards.sql",
          "V50.15__draft_commit_required_owner_guards.sql",
          "V50.16__reviewed_source_application_guards.sql",
          "V50.1__durable_draft_commit_coordination.sql",
          "V50.2__authored_draft_publish_selection.sql",
          "V50.3__authored_draft_publish_request_identity.sql",
          "V50.4__selected_publish_attempt_digest_capacity.sql",
          "V50.5__game_design_draft_terminal_outcomes.sql",
          "V50.6__publication_operation_terminal_seal.sql",
          "V50.7__publication_terminal_content_and_epoch.sql",
          "V50.8__immutable_realm_policy_source.sql",
          "V50.9__sealed_realm_policy_publication_evidence.sql",
          "V50__freeze_asset_export_candidate_public_origin.sql",
          "V5__update_version_table.sql",
          "V6__game_settings_overrides.sql",
          "V7__enforce_unique_version_numbers.sql",
          "V8__published_release_bundle.sql",
          "V9__publish_attempt_framework.sql",
          "V1000__create_saga_schema.sql",
          "V1001__saga_instance_table.sql",
          "V1002__saga_step_table.sql");

  @Test
  void actualDefaultLocationsFreshBootstrapAppliesFullCombinedHistoryAndEnabledGuards() {
    String schema = "gd_storage_fresh_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    flyway(source, schema, null).migrate();
    flyway(source, schema, null).validate();
    var dsl = DSL.using(source, SQLDialect.POSTGRES);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM flyway_schema_history_game_design_service WHERE version IS NOT NULL AND success")
                .get(0, Long.class))
        .isEqualTo(84L);
    assertThat(
            dsl.fetchSingle(
                    "SELECT max(version::int) FROM flyway_schema_history_game_design_service WHERE version ~ '^[0-9]+$'")
                .get(0, Integer.class))
        .isEqualTo(1024);
    assertEnabledGuards(dsl, schema);
  }

  @Test
  void actualReceivingParentIncludingSagaUpgradePreservesHistoryAndAppliesStorageTail(
      @TempDir Path parentMigrations) throws Exception {
    var fixture = fixture(true);
    var resources =
        new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/**/*.sql");
    int copied = 0;
    for (var resource : resources) {
      String filename = java.util.Objects.requireNonNull(resource.getFilename());
      if (!PARENT_MIGRATION_SCRIPTS.contains(filename)) continue;
      try (var input = resource.getInputStream()) {
        Files.copy(input, parentMigrations.resolve(filename));
      }
      copied++;
    }
    assertThat(copied).isEqualTo(62);
    flyway(fixture.source(), fixture.schema(), null, "filesystem:" + parentMigrations).migrate();
    var history =
        fixture
            .dsl()
            .fetch(
                "SELECT version || ':' || script || ':' || checksum || ':' || success FROM flyway_schema_history_game_design_service WHERE version IS NOT NULL ORDER BY installed_rank")
            .getValues(0, String.class);
    assertThat(history).hasSize(62);
    var rows = retainedRows(fixture);

    // The service's actual startup locations and default ordering must upgrade this parent.
    flyway(fixture.source(), fixture.schema(), null).migrate();
    flyway(fixture.source(), fixture.schema(), null).validate();

    assertThat(
            fixture
                .dsl()
                .fetch(
                    "SELECT version || ':' || script || ':' || checksum || ':' || success FROM flyway_schema_history_game_design_service WHERE version IS NOT NULL AND installed_rank <= 62 ORDER BY installed_rank")
                .getValues(0, String.class))
        .isEqualTo(history);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM flyway_schema_history_game_design_service WHERE version IS NOT NULL AND success")
                .get(0, Long.class))
        .isEqualTo(84L);
    assertThat(retainedRows(fixture)).isEqualTo(rows);
    assertEnabledGuards(fixture);
  }

  @Test
  void fullStorageUpgradeRetainsParentV1RowsBytesChecksumsAndOwnerReads() throws Exception {
    var fixture = fixture(true);
    var operation = fixture.operation();
    byte[] original = operation.canonicalBytes();
    new DraftAuthorizationFenceBinding.FrameReader(original)
        .expect("game-design-publication-operation/v1");
    var rows = retainedRows(fixture);
    var migrationHistory = predecessorChecksums(fixture);
    assertThat(migrationHistory).hasSize(59);
    var beforeRead = fixture.operations().read(operation.workflowId()).orElseThrow();
    assertThatThrownBy(operation::inventory)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("HISTORICAL_PUBLICATION_INVENTORY_UNAVAILABLE");

    upgrade(fixture);

    assertThat(predecessorChecksums(fixture)).isEqualTo(migrationHistory);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM flyway_schema_history_game_design_service WHERE version IS NOT NULL AND success")
                .get(0, Long.class))
        .isEqualTo(84L);
    assertThat(retainedRows(fixture)).isEqualTo(rows);
    assertThat(GameDesignPublicationOperation.fromStored(original).canonicalBytes())
        .containsExactly(original);
    var afterRead = fixture.operations().read(operation.workflowId()).orElseThrow();
    assertThat(afterRead.operation().canonicalBytes()).containsExactly(original);
    assertThat(afterRead.outcome()).isEqualTo(beforeRead.outcome()).isEqualTo("PENDING");
    assertThat(afterRead.receiptBytes()).isEqualTo(beforeRead.receiptBytes());
    assertThatThrownBy(afterRead.operation()::inventory)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("HISTORICAL_PUBLICATION_INVENTORY_UNAVAILABLE");
    assertEnabledGuards(fixture);

    deny(
        fixture,
        () ->
            fixture
                .write()
                .executeWithoutResult(
                    status ->
                        fixture
                            .operations()
                            .seal(
                                operation.tenantKey(),
                                operation.workflowId(),
                                operation.versionId(),
                                operation.selectionDigest(),
                                false)),
        "Truncated publication inventory frame");
    for (String family : List.of("command", "realm_policy", "asset")) {
      deny(
          fixture, () -> insertV1Capture(fixture, family), "Truncated publication inventory frame");
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM "
                          + captureTable(family)
                          + " WHERE publish_workflow_id = ?",
                      operation.workflowId())
                  .get(0, Long.class))
          .isZero();
    }
    deny(
        fixture,
        () ->
            fixture
                .dsl()
                .execute(
                    "DELETE FROM game_design_publication_operation WHERE publish_workflow_id = ?",
                    operation.workflowId()),
        "publication operation history is immutable");
    assertThat(retainedRows(fixture)).isEqualTo(rows);
    assertThat(predecessorChecksums(fixture)).isEqualTo(migrationHistory);
    assertThat(
            fixture
                .operations()
                .read(operation.workflowId())
                .orElseThrow()
                .operation()
                .canonicalBytes())
        .containsExactly(original);
  }

  @Test
  void retainedReviewedV1RemainsByteExactReadableButCannotAuthorizeCurrentWrites()
      throws Exception {
    var fixture = fixture(false, true);
    var binding = fixture.operation().account().input().selection().selectedCommit();
    var beforeRow = reviewedBaseRow(fixture, binding);
    var before =
        fixture
            .dsl()
            .fetchOne(
                "SELECT evidence_schema, evidence_bytes, evidence_digest FROM game_design_reviewed_draft_base WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ?",
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.requestId());
    assertThat(before).isNotNull();
    var originalBytes = before.get("evidence_bytes", byte[].class);
    var originalDigest = before.get("evidence_digest", String.class);
    assertThat(before.get("evidence_schema", String.class))
        .isEqualTo(GameDesignReviewedBaseEvidence.HISTORICAL_SCHEMA);
    assertThat(originalDigest).isEqualTo(DraftAuthorizationFenceBinding.digest(originalBytes));
    var beforeRead =
        new GameDesignReviewedBaseRepository(fixture.dsl()).read(binding).orElseThrow();
    assertThat(beforeRead.schema()).isEqualTo(GameDesignReviewedBaseEvidence.HISTORICAL_SCHEMA);
    assertThat(beforeRead.canonicalBytes()).containsExactly(originalBytes);
    assertThat(beforeRead.digest()).isEqualTo(originalDigest);
    var parentChecksums = predecessorChecksums(fixture);

    upgrade(fixture);

    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT max(version::int) FROM flyway_schema_history_game_design_service WHERE version ~ '^[0-9]+$'")
                .get(0, Integer.class))
        .isEqualTo(1024);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM flyway_schema_history_game_design_service WHERE version IS NOT NULL AND success")
                .get(0, Long.class))
        .isEqualTo(84L);
    assertThat(predecessorChecksums(fixture)).isEqualTo(parentChecksums);
    assertThat(reviewedBaseRow(fixture, binding)).isEqualTo(beforeRow);
    var retained = new GameDesignReviewedBaseRepository(fixture.dsl()).read(binding).orElseThrow();
    assertThat(retained.schema()).isEqualTo(GameDesignReviewedBaseEvidence.HISTORICAL_SCHEMA);
    assertThat(retained.canonicalBytes()).containsExactly(originalBytes);
    assertThat(retained.digest()).isEqualTo(originalDigest);
    assertThatThrownBy(
            () ->
                new GameDesignReviewedBaseRepository(fixture.dsl()).requireCurrentRetained(binding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REVIEWED_BASE_ASSET_LINEAGE_UNAVAILABLE");
    assertEnabledGuards(fixture);

    denyReviewedBase(
        fixture,
        () ->
            fixture
                .dsl()
                .execute(
                    "UPDATE game_design_reviewed_draft_base SET evidence_digest = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ?",
                    "sha256:" + "0".repeat(64),
                    binding.target().canonicalTenantId(),
                    binding.target().canonicalVersionId(),
                    binding.requestId()),
        "Reviewed Draft base evidence is immutable");
    denyReviewedBase(
        fixture,
        () ->
            fixture
                .dsl()
                .execute(
                    "DELETE FROM game_design_reviewed_draft_base WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ?",
                    binding.target().canonicalTenantId(),
                    binding.target().canonicalVersionId(),
                    binding.requestId()),
        "Reviewed Draft base evidence is immutable");

    var newBinding =
        DraftCommitBinding.create(
            binding.target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            binding.baseCommitId(),
            binding.revisions(),
            binding.affectedUnits());
    fixture
        .write()
        .executeWithoutResult(
            status -> new DraftCommitCoordinatorRepository(fixture.dsl()).claim(newBinding));
    denyReviewedBase(
        fixture,
        () ->
            fixture
                .write()
                .executeWithoutResult(
                    status ->
                        insertReviewedBaseV1(
                            fixture.dsl(),
                            newBinding,
                            originalBytes,
                            originalDigest,
                            newBinding.baseCommitId())),
        "Fresh reviewed base requires complete asset-bound v2 owner evidence");
    assertThat(reviewedBaseRow(fixture, binding)).isEqualTo(beforeRow);
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM game_design_reviewed_draft_base WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ?",
                    newBinding.target().canonicalTenantId(),
                    newBinding.target().canonicalVersionId(),
                    newBinding.requestId()))
        .isNull();
  }

  @Test
  void retainedTerminalFailureRemainsReadableWithItsOriginalReceiptAfterUpgrade() throws Exception {
    var fixture = fixture(true);
    var operation = fixture.operation();
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              var attempts = new PublishAttemptRepository(fixture.dsl());
              var attempt =
                  attempts.findByPublishWorkflowIdForUpdate(operation.workflowId()).orElseThrow();
              attempt.setStatus(PublishAttemptStatus.FAILED);
              attempt.setFailureCode("ISOLATED_STORAGE_DENIAL");
              attempts.save(attempt);
              fixture
                  .operations()
                  .seal(
                      operation.tenantKey(),
                      operation.workflowId(),
                      operation.versionId(),
                      operation.selectionDigest(),
                      false);
            });
    var original = fixture.operations().read(operation.workflowId()).orElseThrow();
    assertThat(original.outcome()).isEqualTo("NO_PUBLICATION");
    assertThat(original.receiptBytes()).isNotEmpty();
    assertThat(original.terminalEvidenceBytes()).isNotEmpty();
    var rows = retainedRows(fixture);
    var checksums = predecessorChecksums(fixture);
    upgrade(fixture);
    var retained = fixture.operations().read(operation.workflowId()).orElseThrow();
    assertThat(retained.operation().canonicalBytes()).containsExactly(operation.canonicalBytes());
    assertThat(retained.outcome()).isEqualTo(original.outcome());
    assertThat(retained.receiptBytes()).containsExactly(original.receiptBytes());
    assertThat(retained.terminalEvidenceBytes()).containsExactly(original.terminalEvidenceBytes());
    assertThat(retainedRows(fixture)).isEqualTo(rows);
    assertThat(predecessorChecksums(fixture)).isEqualTo(checksums);
    assertEnabledGuards(fixture);
  }

  @Test
  void newParentV1ReservationIsDeniedAndRawV2ReservationUsesActualOwnerSelectionAndAttempt()
      throws Exception {
    // Actual owner selection/attempt, deliberately not auto-reserved by the v1 fixture helper.
    var fixture = fixture(false);
    var before = retainedRows(fixture);
    upgrade(fixture);
    assertEnabledGuards(fixture);
    deny(
        fixture,
        () ->
            fixture
                .write()
                .executeWithoutResult(status -> fixture.operations().reserve(fixture.operation())),
        "Truncated publication inventory frame");
    assertThat(retainedRows(fixture)).isEqualTo(before);

    byte[] inventory = canonical(inventory(fixture.operation()));
    byte[] originalV2 = operationBytes(fixture.operation(), inventory);
    requireInventory(fixture, originalV2);
    deny(
        fixture,
        () ->
            fixture
                .write()
                .executeWithoutResult(
                    status ->
                        fixture
                            .dsl()
                            .execute(
                                "INSERT INTO game_design_publication_operation (publish_workflow_id, tenant_id, version_id, selection_digest, request_bytes) VALUES (?, ?, ?, ?, ?)",
                                fixture.operation().workflowId(),
                                fixture.operation().tenantKey(),
                                fixture.operation().versionId(),
                                "sha256:" + "f".repeat(64),
                                originalV2)),
        "publication operation does not bind exact selected attempt");
    assertThat(retainedRows(fixture)).isEqualTo(before);
    fixture.write().executeWithoutResult(status -> insertOperation(fixture, originalV2));
    var stored =
        fixture
            .dsl()
            .fetchOne(
                "SELECT request_bytes, outcome, revision, selection_digest FROM game_design_publication_operation WHERE publish_workflow_id = ?",
                fixture.operation().workflowId());
    assertThat(stored).isNotNull();
    assertThat(stored.get("request_bytes", byte[].class)).containsExactly(originalV2);
    assertThat(stored.get("outcome", String.class)).isEqualTo("PENDING");
    assertThat(stored.get("revision", Long.class)).isEqualTo(1L);
    assertThat(stored.get("selection_digest", String.class))
        .isEqualTo(fixture.operation().selectionDigest());
    // The evolved decoder preserves complete v2 while retained v1 remains a separate read profile.
    var decoded = GameDesignPublicationOperation.fromStored(originalV2);
    assertThat(decoded.canonicalBytes()).containsExactly(originalV2);
    assertThat(decoded.account().canonicalBytes())
        .containsExactly(fixture.operation().account().canonicalBytes());
    assertThat(decoded.world().canonicalBytes())
        .containsExactly(fixture.operation().world().canonicalBytes());
    assertThat(decoded.inventory().canonicalBytes()).containsExactly(inventory);
    assertThat(decoded.inventory().digest()).isEqualTo(sha256(inventory));
    byte[] beforeRow = canonicalRow(fixture, "game_design_publication_operation");
    deny(
        fixture,
        () ->
            fixture
                .dsl()
                .execute(
                    "UPDATE game_design_publication_operation SET request_bytes = ? WHERE publish_workflow_id = ?",
                    originalV2,
                    fixture.operation().workflowId()),
        "publication operation identity or terminal outcome is immutable");
    assertThat(canonicalRow(fixture, "game_design_publication_operation"))
        .containsExactly(beforeRow);
  }

  @Test
  void actualInventoryGuardRejectsMalformedFramesDigestUnknownFieldsAndExactBindingChanges()
      throws Exception {
    var fixture = fixture(false);
    upgrade(fixture);
    byte[] inventory = canonical(inventory(fixture.operation()));
    byte[] valid = operationBytes(fixture.operation(), inventory);
    requireInventory(fixture, valid);
    var before = retainedRows(fixture);
    List<byte[]> malformed = new ArrayList<>();
    malformed.add(fixture.operation().canonicalBytes());
    malformed.add(Arrays.copyOf(valid, valid.length - 1));
    malformed.add(Arrays.copyOf(valid, valid.length + 1));
    malformed.add(
        framed(
            "game-design-publication-operation/v999",
            fixture.operation(),
            inventory,
            sha256(inventory)));
    malformed.add(framed(V2, fixture.operation(), inventory, "sha256:" + "0".repeat(64)));
    malformed.add(new byte[4 * 1024 * 1024 + 1]);
    var unknown = inventory(fixture.operation());
    unknown.put("undeclaredFamily", List.of());
    malformed.add(operationBytes(fixture.operation(), canonical(unknown)));
    var wrongAccount = inventory(fixture.operation());
    member(wrongAccount, "accountOrder").put("fenceId", UUID.randomUUID().toString());
    malformed.add(operationBytes(fixture.operation(), canonical(wrongAccount)));
    var wrongGraph = inventory(fixture.operation());
    member(wrongGraph, "sourceModel").put("graphDigest", "sha256:" + "1".repeat(64));
    malformed.add(operationBytes(fixture.operation(), canonical(wrongGraph)));
    var missingFamily = inventory(fixture.operation());
    member(missingFamily, "sourceModel").put("familyCounts", List.of());
    malformed.add(operationBytes(fixture.operation(), canonical(missingFamily)));
    byte[] duplicateMember =
        (new String(inventory, StandardCharsets.UTF_8).replaceFirst("\\{", "{\"schemaVersion\":1,"))
            .getBytes(StandardCharsets.UTF_8);
    malformed.add(operationBytes(fixture.operation(), duplicateMember));
    for (byte[] bytes : malformed) {
      deny(fixture, () -> requireInventory(fixture, bytes), null);
    }
    assertThat(retainedRows(fixture)).isEqualTo(before);
    assertEnabledGuards(fixture);
  }

  @Test
  void closureProfileRequiresExactSchemaPairAndExplicitEmptyInboundEnumeration() throws Exception {
    var fixture = fixture(false);
    upgrade(fixture);
    var closure = inventory(fixture.operation());
    closure.put("schema", "world-selected-publication-artifact-inventory/v2");
    closure.put("schemaVersion", 2);
    member(closure, "checkpoint").put("digestSchemaVersion", 4);
    var model = member(closure, "sourceModel");
    model.put("modelId", "WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_INBOUND_EMPTY_V1");
    model.put("graphSchemaVersion", 3);
    model.put("inboundSourceClosure", Map.of("schemaVersion", 1, "familyCounts", inboundCounts(0)));
    var world = JSON.readValue(fixture.operation().world().canonicalBytes(), Map.class);
    member(world, "request").put("digestSchemaVersion", 4);
    byte[] worldBytes = canonical(world);
    byte[] closureBytes =
        framed(
            V2,
            fixture.operation().account().canonicalBytes(),
            worldBytes,
            canonical(closure),
            sha256(canonical(closure)));
    requireInventory(fixture, closureBytes);

    var wrongProfile = copy(closure);
    member(wrongProfile, "sourceModel").put("graphSchemaVersion", 2);
    deny(
        fixture,
        () ->
            requireInventory(
                fixture,
                framed(
                    V2,
                    fixture.operation().account().canonicalBytes(),
                    worldBytes,
                    canonical(wrongProfile),
                    sha256(canonical(wrongProfile)))),
        null);
    var nonempty = copy(closure);
    member(nonempty, "sourceModel")
        .put("inboundSourceClosure", Map.of("schemaVersion", 1, "familyCounts", inboundCounts(1)));
    deny(
        fixture,
        () ->
            requireInventory(
                fixture,
                framed(
                    V2,
                    fixture.operation().account().canonicalBytes(),
                    worldBytes,
                    canonical(nonempty),
                    sha256(canonical(nonempty)))),
        null);
    var missing = copy(closure);
    member(missing, "sourceModel").remove("inboundSourceClosure");
    deny(
        fixture,
        () ->
            requireInventory(
                fixture,
                framed(
                    V2,
                    fixture.operation().account().canonicalBytes(),
                    worldBytes,
                    canonical(missing),
                    sha256(canonical(missing)))),
        null);
    deny(
        fixture,
        () -> requireInventory(fixture, operationBytes(fixture.operation(), canonical(closure))),
        null);
    assertEnabledGuards(fixture);
  }

  private Fixture fixture(boolean reserveV1) throws Exception {
    return fixture(reserveV1, false);
  }

  private Fixture fixture(boolean reserveV1, boolean retainReviewedV1) throws Exception {
    String schema = "gd_storage_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    flyway(dataSource, schema, "50.16").migrate();
    var write = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var configuration = new org.jooq.impl.DefaultConfiguration();
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(
        new org.jooq.impl.DataSourceConnectionProvider(
            new TransactionAwareDataSourceProxy(dataSource)));
    configuration.set(
        new org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider(
            new DataSourceTransactionManager(dataSource)));
    var dsl = DSL.using(configuration);
    var operation =
        write.execute(
            status -> {
              var requested = new Game();
              requested.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              requested.setName("ISOLATED passive storage owner");
              var game = new GameRepository(dsl).save(requested);
              // Reproduce the original owner insertion under its actual pre-tail schema. The
              // current Version writer requires ordinary asset genesis, which did not exist here.
              var inserted =
                  java.util.Objects.requireNonNull(
                      dsl.fetchOne(
                          "INSERT INTO version (tenant_id, canonical_version_id, canonical_tenant_id, "
                              + "identity_source_game_row_id, identity_source_game_tenant_key, "
                              + "identity_source_provenance_kind, version_number, version_state, "
                              + "version_state_epoch, is_script_only, created_at, updated_at) "
                              + "SELECT g.tenant_id, ?, g.canonical_tenant_id, g.id, g.tenant_id, "
                              + "g.tenant_identity_provenance_kind, 1, 'DRAFT', 1, FALSE, "
                              + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM game g WHERE g.id = ? RETURNING id",
                          UUID.randomUUID(),
                          game.getId()));
              var version =
                  new VersionRepository(dsl).findById(inserted.get("id", Long.class)).orElseThrow();
              var target =
                  new TargetProof(
                      version.getCanonicalTenantId(),
                      version.getCanonicalVersionId(),
                      version.getId(),
                      version.getTenantId(),
                      version.getIdentitySourceGameRowId(),
                      version.getIdentitySourceGameTenantKey(),
                      version.getIdentitySourceProvenanceKind());
              RealmPolicyGenesis reviewedGenesis = null;
              String reviewedBaseReference = null;
              if (retainReviewedV1) {
                reviewedGenesis = new RealmPolicySourceRepository(dsl).recordFreshGenesis(target);
                new CommandSourceRepository(dsl)
                    .enrollNewDraftGenesis(
                        new CommandSource.NewDraftGenesisReceipt(
                            target,
                            reviewedGenesis.receiptId(),
                            reviewedGenesis.creationTransactionId()));
                reviewedBaseReference = "genesis:" + reviewedGenesis.receiptId();
              }
              try {
                return reserveV1
                    ? IsolatedPublicationOwnerSetup.retainHistoricalV1PreV56(
                        dsl, target, version.getVersionStateEpoch())
                    : prepareUnreservedOwnerOperation(
                        dsl, target, version, reviewedBaseReference, reviewedGenesis);
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    return new Fixture(
        dataSource,
        schema,
        dsl,
        write,
        operation,
        new GameDesignPublicationOperationRepository(dsl));
  }

  private GameDesignPublicationOperation prepareUnreservedOwnerOperation(
      DSLContext dsl,
      TargetProof target,
      Version version,
      String baseReference,
      RealmPolicyGenesis reviewedGenesis)
      throws Exception {
    var seed =
        baseReference == null
            ? IsolatedPublicationOperationFixtures.fresh(target)
            : IsolatedPublicationOperationFixtures.fresh(target, baseReference);
    var draft = seed.account().input().selection().selectedCommit();
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    coordinator.claim(draft);
    if (reviewedGenesis != null) {
      var commandGenesis =
          new CommandSourceRepository(dsl).readNewDraftGenesis(target).orElseThrow();
      var evidenceBytes =
          historicalReviewedGenesisBytes(draft.target(), reviewedGenesis, commandGenesis);
      insertReviewedBaseV1(
          dsl,
          draft,
          evidenceBytes,
          DraftAuthorizationFenceBinding.digest(evidenceBytes),
          draft.baseCommitId());
    }
    coordinator.claimApplicationSlot(draft);
    coordinator.markOwnerInProgress(draft, DraftCommitBinding.Owner.WORLD_MANAGEMENT);
    var epochs =
        draft.affectedUnits().stream()
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
            draft.commitId(),
            draft.digest(),
            "ISOLATED-world-applied",
            seed.world().appliedResultBytes(),
            epochs);
    coordinator.recordOwnerOutcome(draft, outcome);
    IsolatedPublicationOwnerSetup.advanceIsolatedVisibility(dsl, draft, List.of(outcome));
    coordinator.releaseApplicationSlot(draft);
    var selection =
        new AuthoredDraftPublishSelectionRepository(dsl, coordinator)
            .reserve(
                new AuthoredDraftPublishSelection.PublishIntent(
                    target.canonicalTenantId(),
                    target.canonicalVersionId(),
                    UUID.randomUUID().toString(),
                    Long.toString(version.getVersionStateEpoch()),
                    "ISOLATED passive storage input",
                    draft.requestId(),
                    draft.commitId(),
                    draft.digest()))
            .selection();
    var operation =
        IsolatedPublicationOperationFixtures.historicalV1ForSelection(
            AuthoredDraftPublishSelectionBinding.fromStored(
                selection.canonicalJson(), selection.digest()),
            seed.world());
    var attempt = new PublishAttempt();
    attempt.setTenantId(operation.tenantKey());
    attempt.setPublishWorkflowId(operation.workflowId());
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setVersionId(operation.versionId());
    attempt.setVersionNumber(version.getVersionNumber());
    attempt.setRequestDigest(operation.selectionDigest());
    new PublishAttemptRepository(dsl).save(attempt);
    return operation;
  }

  private byte[] historicalReviewedGenesisBytes(
      TargetProof target,
      RealmPolicyGenesis policyGenesis,
      CommandSource.NewDraftGenesisReceipt commandGenesis) {
    var ownerEvidence = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(ownerEvidence, "shared-policy-command-genesis/v1");
    DraftAuthorizationFenceBinding.frame(ownerEvidence, policyGenesis.creationTransactionId());
    DraftAuthorizationFenceBinding.frame(ownerEvidence, policyGenesis.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(ownerEvidence, commandGenesis.canonicalBytes());

    var evidence = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        evidence, GameDesignReviewedBaseEvidence.HISTORICAL_SCHEMA);
    DraftAuthorizationFenceBinding.frame(evidence, DraftBaseReference.SCHEMA);
    DraftAuthorizationFenceBinding.frame(evidence, DraftBaseReference.Kind.GENESIS.name());
    DraftAuthorizationFenceBinding.frame(evidence, "genesis:" + policyGenesis.receiptId());
    DraftAuthorizationFenceBinding.frame(evidence, target.canonicalTenantId().toString());
    DraftAuthorizationFenceBinding.frame(evidence, target.canonicalVersionId().toString());
    DraftAuthorizationFenceBinding.frame(evidence, Long.toString(target.gameDesignVersionRowId()));
    DraftAuthorizationFenceBinding.frame(evidence, target.gameDesignVersionTenantKey());
    DraftAuthorizationFenceBinding.frame(evidence, Long.toString(target.sourceGameRowId()));
    DraftAuthorizationFenceBinding.frame(evidence, target.sourceGameTenantKey());
    DraftAuthorizationFenceBinding.frame(evidence, target.sourceProvenanceKind());
    DraftAuthorizationFenceBinding.frame(evidence, ownerEvidence.toByteArray());
    return evidence.toByteArray();
  }

  private void insertReviewedBaseV1(
      DSLContext dsl,
      DraftCommitBinding binding,
      byte[] evidenceBytes,
      String evidenceDigest,
      String baseReference) {
    var reference = DraftBaseReference.parse(baseReference);
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
        GameDesignReviewedBaseEvidence.HISTORICAL_SCHEMA,
        evidenceBytes,
        evidenceDigest);
  }

  private String reviewedBaseRow(Fixture fixture, DraftCommitBinding binding) {
    return fixture
        .dsl()
        .fetchSingle(
            "SELECT to_jsonb(b)::TEXT FROM game_design_reviewed_draft_base b WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId())
        .get(0, String.class);
  }

  private Flyway flyway(DriverManagerDataSource source, String schema, String target) {
    return flyway(source, schema, target, "classpath:db/migration", "classpath:db/migration/saga");
  }

  private Flyway flyway(
      DriverManagerDataSource source, String schema, String target, String... locations) {
    var builder =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(locations)
            .table("flyway_schema_history_game_design_service");
    if (target != null) builder.target(target);
    return builder.load();
  }

  private void upgrade(Fixture fixture) {
    flyway(fixture.source(), fixture.schema(), null).migrate();
    flyway(fixture.source(), fixture.schema(), null).validate();
  }

  private List<String> predecessorChecksums(Fixture fixture) {
    return fixture
        .dsl()
        .fetch(
            "SELECT version || ':' || script || ':' || checksum || ':' || success FROM flyway_schema_history_game_design_service WHERE version IS NOT NULL AND installed_rank <= 59 ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private Map<String, String> retainedRows(Fixture fixture) {
    var rows = new LinkedHashMap<String, String>();
    for (String table :
        List.of(
            "game",
            "version",
            "game_design_draft_commit",
            "game_design_draft_commit_owner_result",
            "game_design_draft_commit_visibility_fence",
            "game_design_authored_draft_publish_selection",
            "publish_attempt",
            "game_design_publication_operation")) {
      byte[] bytes = canonicalRow(fixture, table);
      rows.put(table, new String(bytes, StandardCharsets.UTF_8) + ":" + sha256(bytes));
    }
    return rows;
  }

  private byte[] canonicalRow(Fixture fixture, String table) {
    return fixture
        .dsl()
        .fetchSingle(
            "SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::TEXT), '[]'::JSONB)::TEXT FROM "
                + table
                + " r")
        .get(0, String.class)
        .getBytes(StandardCharsets.UTF_8);
  }

  private void assertEnabledGuards(Fixture fixture) {
    assertEnabledGuards(fixture.dsl(), fixture.schema());
  }

  private void assertEnabledGuards(DSLContext dsl, String schema) {
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND NOT t.tgisinternal AND t.tgenabled <> 'O'",
                    schema)
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND t.tgname IN ('game_design_publication_operation_guard','publication_terminal_extension_guard','publication_operation_commit_guard','realm_policy_capture_guard','command_source_capture_guard','ordinary_asset_capture_guard') AND t.tgenabled = 'O'",
                    schema)
                .get(0, Long.class))
        .isEqualTo(6L);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND t.tgname IN ('command_capture_inventory_v2','policy_capture_inventory_v2','asset_capture_inventory_v2','a_published_policy_inventory_v2')",
                    schema)
                .get(0, Long.class))
        .isEqualTo(4L);
  }

  private void insertV1Capture(Fixture fixture, String family) {
    var operation = fixture.operation();
    // Every pre-existing source/lifecycle guard remains enabled. The new BEFORE INSERT guard
    // rejects legacy operation framing before these deliberately inadmissible new captures exist.
    String digestColumns = family.equals("realm_policy") ? "" : ", capture_digest";
    String digestValues = family.equals("realm_policy") ? "" : ", ?";
    var args =
        new ArrayList<Object>(
            List.of(
                operation.account().tenantId(),
                operation.world().request().canonicalVersionId(),
                operation.workflowId(),
                operation.account().input().selection().selectedCommit().commitId(),
                operation.canonicalBytes(),
                new byte[] {1}));
    if (!family.equals("realm_policy")) args.add("sha256:" + "0".repeat(64));
    String table = captureTable(family);
    fixture
        .dsl()
        .execute(
            "INSERT INTO "
                + table
                + " (canonical_tenant_id, canonical_version_id, publish_workflow_id, commit_id, operation_bytes, capture_bytes"
                + digestColumns
                + ") VALUES (?, ?, ?, ?, ?, ?"
                + digestValues
                + ")",
            args.toArray());
  }

  private String captureTable(String family) {
    return family.equals("realm_policy")
        ? "game_design_realm_policy_capture"
        : "game_design_" + family + "_source_capture";
  }

  private void insertOperation(Fixture fixture, byte[] bytes) {
    var operation = fixture.operation();
    fixture
        .dsl()
        .execute(
            "INSERT INTO game_design_publication_operation (publish_workflow_id, tenant_id, version_id, selection_digest, request_bytes) VALUES (?, ?, ?, ?, ?)",
            operation.workflowId(),
            operation.tenantKey(),
            operation.versionId(),
            operation.selectionDigest(),
            bytes);
  }

  private void requireInventory(Fixture fixture, byte[] bytes) {
    fixture.dsl().fetch("SELECT require_selected_inventory_operation_v2(?::BYTEA)", bytes);
  }

  private void deny(Fixture fixture, Runnable write, String message) {
    assertThatThrownBy(write::run)
        .isInstanceOf(RuntimeException.class)
        .satisfies(
            failure -> {
              Throwable cause = failure;
              while (!(cause instanceof SQLException) && cause.getCause() != null)
                cause = cause.getCause();
              assertThat(cause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
              if (message != null) assertThat(cause.getMessage()).contains(message);
            });
  }

  private void denyReviewedBase(Fixture fixture, Runnable write, String message) {
    assertThatThrownBy(write::run)
        .isInstanceOf(RuntimeException.class)
        .satisfies(
            failure -> {
              Throwable cause = failure;
              while (!(cause instanceof SQLException) && cause.getCause() != null)
                cause = cause.getCause();
              assertThat(cause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) cause).getSQLState()).isEqualTo("P0001");
              if (message != null) assertThat(cause.getMessage()).contains(message);
            });
  }

  /** Raw supplied external inventory, matching the published isolated fixture's closed profile. */
  private Map<String, Object> inventory(GameDesignPublicationOperation operation) throws Exception {
    var account = operation.account();
    var selection = account.input().selection();
    var commit = selection.selectedCommit();
    var world = operation.world();
    var request = world.request();
    var original = DraftAuthorizationFenceBinding.fromStored(world.originalAccountBindingBytes());
    String fixtureId =
        UUID.nameUUIDFromBytes("ISOLATED World inventory input".getBytes(StandardCharsets.UTF_8))
            .toString();
    var result = new LinkedHashMap<String, Object>();
    result.put("schema", "world-selected-publication-artifact-inventory/v1");
    result.put("schemaVersion", 1);
    result.put("completeness", "COMPLETE");
    result.put(
        "ownerScope",
        Map.of(
            "targetNamespace",
            request.targetNamespace(),
            "canonicalTenantId",
            request.canonicalTenantId().toString(),
            "canonicalVersionId",
            request.canonicalVersionId().toString(),
            "versionIdentityOperationId",
            fixtureId,
            "intakeRequestId",
            request.intakeRequestId().toString(),
            "intakeOperationId",
            fixtureId,
            "intakeRequestDigest",
            sha256(world.originalAccountBindingBytes()),
            "sourceOperationId",
            fixtureId,
            "sourceEvidenceDigest",
            sha256(world.selectorReceiptBytes()),
            "intakeReceiptDigest",
            sha256(world.selectorReceiptBytes())));
    result.put(
        "selectedApplication",
        Map.of(
            "applicationOperationId",
            original.operationId().toString(),
            "applicationRequestId",
            commit.requestId().toString(),
            "appliedCommitId",
            commit.commitId().toString(),
            "bindingDigest",
            commit.digest(),
            "appliedResultDigest",
            sha256(world.appliedResultBytes())));
    result.put(
        "accountOrder",
        Map.of(
            "operationId",
            account.operationId().toString(),
            "fenceId",
            account.fenceId().toString(),
            "actorAccountId",
            account.input().actorAccountId().toString(),
            "publicationRequestId",
            selection.intent().publishRequestId(),
            "selectionDigest",
            selection.digest(),
            "selectedCommitId",
            commit.commitId().toString(),
            "bindingDigest",
            sha256(account.canonicalBytes())));
    result.put(
        "freeze",
        Map.of(
            "publicationFence",
            request.publicationFence().toString(),
            "publicationRequestId",
            request.publicationRequestId(),
            "requestDigest",
            request.requestDigest(),
            "versionStateEpoch",
            Long.toString(request.versionStateEpoch()),
            "publishWorkflowId",
            request.publishWorkflowId()));
    result.put(
        "checkpoint",
        new LinkedHashMap<>(
            Map.of(
                "appliedCommitId",
                request.appliedCommitId(),
                "contentDigest",
                request.contentDigest(),
                "digestSchemaVersion",
                request.digestSchemaVersion())));
    var model = new LinkedHashMap<String, Object>();
    model.put("modelId", "WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1");
    model.put("graphSchemaVersion", 2);
    model.put(
        "graphDigest",
        WorldDraftStartLocationEvidence.fromStored(world.selectorReceiptBytes()).graphDigest());
    model.put(
        "topologyResultDigest",
        sha256("ISOLATED topology result".getBytes(StandardCharsets.UTF_8)));
    model.put(
        "familyCounts",
        List.of(
            Map.of("family", "REGION", "rowCount", 1),
            Map.of("family", "ZONE", "rowCount", 1),
            Map.of("family", "ROOM", "rowCount", 1),
            Map.of("family", "ROOM_EXIT", "rowCount", 0),
            Map.of("family", "GENERATION_RULE", "rowCount", 0),
            Map.of("family", "WORLD_ENTITY_SPAWN_BINDING", "rowCount", 0)));
    model.put(
        "regionGeneratorInputs",
        List.of(Map.of("regionTemplateId", fixtureId, "generatorType", "", "generatorParams", "")));
    model.put("generationRuleFields", List.of("id", "name", "scopeType", "scopeId", "value"));
    model.put(
        "regionFields",
        List.of(
            "id",
            "shardId",
            "name",
            "weather",
            "generationSeed",
            "generatorType",
            "generatorParams",
            "spacingMultiplier"));
    model.put("zoneFields", List.of("id", "regionId", "name"));
    model.put(
        "roomFields",
        List.of(
            "id",
            "zoneId",
            "name",
            "description",
            "nameLocalizedVariantsJson",
            "descriptionLocalizedVariantsJson"));
    model.put("roomExitFields", List.of("id", "fromRoomId", "toRoomId", "direction", "cost"));
    model.put(
        "spawnBindingFields",
        List.of(
            "id",
            "roomId",
            "entityTemplateType",
            "entityReference.kind",
            "entityReference.tenantId",
            "entityReference.versionId",
            "entityReference.templateId",
            "spawnCount",
            "respawnDelaySeconds"));
    model.put("spawnBindingInputs", List.of());
    model.put("generationRuleInputCount", 0);
    model.put("spawnBindingCount", 0);
    model.put(
        "appliedEpochs",
        commit.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    Map.of(
                        "aggregateType",
                        unit.aggregateType(),
                        "aggregateId",
                        unit.aggregateId(),
                        "scopeType",
                        unit.scopeType(),
                        "scopeId",
                        unit.scopeId(),
                        "expectedEpoch",
                        unit.expectedEpoch(),
                        "resultingEpoch",
                        new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
            .toList());
    result.put("sourceModel", model);
    result.put(
        "artifactDecisions",
        List.of(
            Map.of(
                "artifactKind",
                "NAVMESH",
                "state",
                "NOT_REQUIRED",
                "rule",
                "ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"),
            Map.of(
                "artifactKind",
                "PATH_GRAPH",
                "state",
                "NOT_REQUIRED",
                "rule",
                "AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH")));
    return result;
  }

  private List<Map<String, Object>> inboundCounts(int firstCount) {
    var families =
        List.of(
            "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT",
            "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT",
            "WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION",
            "WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING",
            "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK",
            "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE",
            "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING");
    var counts = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < families.size(); i++)
      counts.add(Map.of("family", families.get(i), "count", i == 0 ? firstCount : 0));
    return counts;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> member(Map<?, ?> object, String key) {
    // Mutations create independent supplied negatives, never validate or synthesize owner truth.
    var value = (Map<String, Object>) object.get(key);
    if (!(value instanceof LinkedHashMap)) {
      value = new LinkedHashMap<>(value);
      ((Map<String, Object>) object).put(key, value);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> copy(Map<String, Object> value) throws Exception {
    return JSON.readValue(canonical(value), Map.class);
  }

  private byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot encode supplied inventory test data", failure);
    }
  }

  private byte[] operationBytes(GameDesignPublicationOperation operation, byte[] inventory) {
    return framed(V2, operation, inventory, sha256(inventory));
  }

  private byte[] framed(
      String schema, GameDesignPublicationOperation operation, byte[] inventory, String digest) {
    return framed(
        schema,
        operation.account().canonicalBytes(),
        operation.world().canonicalBytes(),
        inventory,
        digest);
  }

  private byte[] framed(
      String schema, byte[] account, byte[] world, byte[] inventory, String digest) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, schema);
    DraftAuthorizationFenceBinding.frame(out, account);
    DraftAuthorizationFenceBinding.frame(out, world);
    DraftAuthorizationFenceBinding.frame(out, inventory);
    DraftAuthorizationFenceBinding.frame(out, digest);
    return out.toByteArray();
  }

  private String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Fixture(
      DriverManagerDataSource source,
      String schema,
      DSLContext dsl,
      TransactionTemplate write,
      GameDesignPublicationOperation operation,
      GameDesignPublicationOperationRepository operations) {}
}
