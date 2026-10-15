package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationOwner;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociation;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociationRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSource;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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

/**
 * Local coordinator/storage definitions; upstream Account authority and GL intake are not mocked
 * proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class TemplateConfigSourcePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void authoredAutomationInventoryHasExactRetryInheritanceAndFrozenOriginalProvenance() {
    var f = fixture();
    var inventory = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    var declaration =
        binding(
            f,
            TemplateConfigSource.ownerInventoryPayload(
                DraftCommitBinding.Owner.AUTOMATION_SCRIPTING, inventory),
            TemplateConfigSource.SCOPE,
            "0");
    apply(f, declaration);

    var first = source(f, declaration);
    assertThat(first.sourceEpoch()).isEqualTo("1");
    assertThat(first.entries()).isEmpty();
    assertThat(first.ownerSourceInventoryDeclarations()).hasSize(1);
    assertThat(first.ownerSourceInventoryDeclarations().getFirst().sourceBinding())
        .isEqualTo(declaration);
    assertThat(first.ownerSourceInventoryDeclarations().getFirst().revisionOrder()).isEqualTo("0");
    assertThat(first.ownerSourceInventoryDeclarations().getFirst().revisionId())
        .isEqualTo(declaration.revisions().getFirst().revisionId());
    assertThat(f.dsl.fetchSingle("SELECT count(*) FROM game_templates").get(0, Long.class))
        .isZero();
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT count(*) FROM game_design_template_config_owner_source_inventory_declaration")
                .get(0, Long.class))
        .isEqualTo(1L);

    f.tx.executeWithoutResult(
        ignored ->
            assertThat(new TemplateConfigSourceRepository(f.dsl).apply(declaration).orElseThrow())
                .isEqualTo(new TemplateConfigSourceSnapshot.Application(declaration, "0", first)));
    assertThat(f.dsl.fetchSingle("SELECT count(*) FROM game_templates").get(0, Long.class))
        .isZero();

    var unrelated =
        binding(
            f,
            GameplayRuleSource.upsertPayload(new GameplayRuleManifest.AdmissionTag("unrelated")),
            GameplayRuleSource.SCOPE,
            "0");
    apply(f, unrelated);
    var inherited = source(f, unrelated);
    assertThat(inherited.sourceEpoch()).isEqualTo("1");
    assertThat(inherited.ownerSourceInventoryDeclarations())
        .containsExactly(first.ownerSourceInventoryDeclarations().getFirst());
    assertThat(inherited.ownerSourceInventoryDeclarations().getFirst().sourceBinding())
        .isEqualTo(declaration);

    var actualTemplate =
        binding(
            f,
            TemplateConfigSource.createPayload("Starter", config(f, "[]")),
            TemplateConfigSource.SCOPE,
            "1");
    apply(f, actualTemplate);
    assertThat(source(f, actualTemplate).entries()).hasSize(1);
    assertThat(source(f, actualTemplate).ownerSourceInventoryDeclarations())
        .containsExactly(first.ownerSourceInventoryDeclarations().getFirst());
    assertThat(f.dsl.fetchSingle("SELECT count(*) FROM game_templates").get(0, Long.class))
        .isEqualTo(1L);

    var operation =
        f.tx.execute(
            ignored -> {
              try {
                return IsolatedPublicationOwnerSetup.retainSourceBacked(
                    f.dsl,
                    f.target,
                    1L,
                    "Automation inventory declaration source freeze",
                    actualTemplate.commitId().toString());
              } catch (Exception e) {
                throw new IllegalStateException("Unable to retain source-backed publication", e);
              }
            });
    var capture =
        Objects.requireNonNull(
                f.tx.execute(
                    ignored -> new TemplateConfigSourceRepository(f.dsl).freeze(operation)))
            .snapshot();
    assertThat(capture.ownerSourceInventoryDeclarations())
        .containsExactly(first.ownerSourceInventoryDeclarations().getFirst());
    assertThat(capture.ownerSourceInventoryDeclarations().getFirst().sourceBinding())
        .isEqualTo(declaration);
    assertThat(capture.binding())
        .isEqualTo(operation.account().input().selection().selectedCommit());
    assertThat(capture.entries().getFirst().sourceBinding()).isEqualTo(actualTemplate);
  }

  @Test
  void latestSameOrderAutomationDeclarationRetainsItsExactBindingAcrossRetryAndInheritance() {
    var f = fixture();
    var inventory = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    String identicalContent =
        TemplateConfigSource.ownerInventoryPayload(
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING, inventory);
    var first = binding(f, identicalContent, TemplateConfigSource.SCOPE, "0");
    apply(f, first);
    var firstSnapshot = source(f, first);
    assertThat(firstSnapshot.ownerSourceInventoryDeclarations().getFirst().sourceBinding())
        .isEqualTo(first);
    assertThat(firstSnapshot.ownerSourceInventoryDeclarations().getFirst().revisionOrder())
        .isEqualTo("0");

    var second = binding(f, identicalContent, TemplateConfigSource.SCOPE, "1");
    assertThat(second.revisions().getFirst().revisionOrder()).isEqualTo("0");
    assertThat(second.revisions().getFirst().payload()).isEqualTo(identicalContent);
    apply(f, second);
    var secondSnapshot = source(f, second);
    var latest = secondSnapshot.ownerSourceInventoryDeclarations().getFirst();
    assertThat(secondSnapshot.sourceEpoch()).isEqualTo("2");
    assertThat(secondSnapshot.ownerSourceInventoryDeclarations()).hasSize(1);
    assertThat(latest.sourceBinding()).isEqualTo(second);
    assertThat(latest.revisionOrder()).isEqualTo("0");
    assertThat(latest.revisionId()).isEqualTo(second.revisions().getFirst().revisionId());
    assertThat(latest.inventory()).isEqualTo(inventory);

    f.tx.executeWithoutResult(
        ignored ->
            assertThat(new TemplateConfigSourceRepository(f.dsl).apply(second).orElseThrow())
                .isEqualTo(
                    new TemplateConfigSourceSnapshot.Application(second, "1", secondSnapshot)));
    var unrelated =
        binding(
            f,
            GameplayRuleSource.upsertPayload(new GameplayRuleManifest.AdmissionTag("unrelated")),
            GameplayRuleSource.SCOPE,
            "0");
    apply(f, unrelated);
    var inherited = source(f, unrelated);
    assertThat(inherited.sourceEpoch()).isEqualTo("2");
    assertThat(inherited.ownerSourceInventoryDeclarations()).containsExactly(latest);
    assertThat(inherited.ownerSourceInventoryDeclarations().getFirst().sourceBinding())
        .isEqualTo(second);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT count(*) FROM game_design_template_config_owner_source_inventory_declaration")
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThat(f.dsl.fetchSingle("SELECT count(*) FROM game_templates").get(0, Long.class))
        .isZero();
  }

  @Test
  void v1025RetainsV1AndV2SourceHistoryAcrossLatestUpgradeAndExactBindingRetry() {
    var f = fixture("1025");
    var initialTemplate =
        binding(
            f,
            TemplateConfigSource.createPayload("Starter", config(f, "[]")),
            TemplateConfigSource.SCOPE,
            "0");
    apply(f, initialTemplate);
    String v1SnapshotJson =
        f.dsl
            .fetchSingle(
                "SELECT snapshot_json FROM game_design_template_config_source_snapshot "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                f.target.canonicalTenantId(),
                f.target.canonicalVersionId(),
                initialTemplate.commitId())
            .get("snapshot_json", String.class);
    String v1SnapshotDigest =
        f.dsl
            .fetchSingle(
                "SELECT snapshot_digest FROM game_design_template_config_source_snapshot "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                f.target.canonicalTenantId(),
                f.target.canonicalVersionId(),
                initialTemplate.commitId())
            .get("snapshot_digest", String.class);
    var originalV1Snapshot =
        new TemplateConfigSourceRepository(f.dsl)
            .readSnapshot(f.target, initialTemplate.commitId())
            .orElseThrow();
    assertThat(originalV1Snapshot.canonicalJson()).isEqualTo(v1SnapshotJson);
    assertThat(originalV1Snapshot.digest()).isEqualTo(v1SnapshotDigest);
    String originalV1SnapshotXmin =
        f.dsl
            .fetchSingle(
                "SELECT xmin::text AS row_xmin FROM game_design_template_config_source_snapshot "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                f.target.canonicalTenantId(),
                f.target.canonicalVersionId(),
                initialTemplate.commitId())
            .get("row_xmin", String.class);

    var automationInventory =
        AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    String automationPayload =
        TemplateConfigSource.ownerInventoryPayload(
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING, automationInventory);
    var automation = binding(f, automationPayload, TemplateConfigSource.SCOPE, "1");
    apply(f, automation);
    var automationSource = source(f, automation);
    assertThat(automationSource.ownerSourceInventoryDeclarations()).hasSize(1);
    assertThat(automationSource.ownerSourceInventoryDeclarations().getFirst().sourceBinding())
        .isEqualTo(automation);

    var retainedDeclaration =
        f.dsl.fetchSingle(
            "SELECT request_id, commit_id, revision_order, owner, inventory_json, payload_json, "
                + "xmin::text AS row_xmin FROM game_design_template_config_owner_source_inventory_declaration "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND revision_id = ?",
            f.target.canonicalTenantId(),
            f.target.canonicalVersionId(),
            automation.revisions().getFirst().revisionId());
    String originalInventoryJson = retainedDeclaration.get("inventory_json", String.class);
    String originalPayloadJson = retainedDeclaration.get("payload_json", String.class);
    String originalDeclarationXmin = retainedDeclaration.get("row_xmin", String.class);
    assertThat(retainedDeclaration.get("request_id", UUID.class)).isEqualTo(automation.requestId());
    assertThat(retainedDeclaration.get("commit_id", UUID.class)).isEqualTo(automation.commitId());
    assertThat(retainedDeclaration.get("revision_order", Integer.class)).isZero();
    assertThat(retainedDeclaration.get("owner", String.class)).isEqualTo("AUTOMATION_SCRIPTING");
    var originalCommit =
        f.dsl.fetchSingle(
            "SELECT binding_json, input_digest, xmin::text AS row_xmin FROM game_design_draft_commit "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ?",
            f.target.canonicalTenantId(),
            f.target.canonicalVersionId(),
            automation.requestId(),
            automation.commitId());
    String originalBindingJson = originalCommit.get("binding_json", String.class);
    String originalBindingDigest = originalCommit.get("input_digest", String.class);
    String originalCommitXmin = originalCommit.get("row_xmin", String.class);
    var originalAutomationApplication =
        f.dsl.fetchSingle(
            "SELECT snapshot_json, result_bytes, xmin::text AS row_xmin FROM game_design_template_config_source_application "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            f.target.canonicalTenantId(),
            f.target.canonicalVersionId(),
            automation.commitId());
    String v2SnapshotJson = originalAutomationApplication.get("snapshot_json", String.class);
    byte[] originalApplicationBytes =
        originalAutomationApplication.get("result_bytes", byte[].class);
    String originalApplicationXmin = originalAutomationApplication.get("row_xmin", String.class);
    String v2SnapshotDigest =
        f.dsl
            .fetchSingle(
                "SELECT snapshot_digest FROM game_design_template_config_source_snapshot "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                f.target.canonicalTenantId(),
                f.target.canonicalVersionId(),
                automation.commitId())
            .get("snapshot_digest", String.class);
    String originalV2SnapshotXmin =
        f.dsl
            .fetchSingle(
                "SELECT xmin::text AS row_xmin FROM game_design_template_config_source_snapshot "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                f.target.canonicalTenantId(),
                f.target.canonicalVersionId(),
                automation.commitId())
            .get("row_xmin", String.class);
    assertThat(v2SnapshotJson)
        .contains("AUTOMATION_SCRIPTING")
        .contains(automation.requestId().toString())
        .contains(automation.commitId().toString());
    var v1025MigrationHistory =
        f.dsl
            .fetch(
                "SELECT version || ':' || script || ':' || checksum || ':' || success AS history_entry "
                    + "FROM flyway_schema_history_game_design_service "
                    + "WHERE version IS NOT NULL ORDER BY installed_rank")
            .getValues("history_entry", String.class);

    migrateToLatest(f);
    assertThat(
            f.dsl
                .fetch(
                    "SELECT version || ':' || script || ':' || checksum || ':' || success AS history_entry "
                        + "FROM flyway_schema_history_game_design_service "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank LIMIT ?",
                    v1025MigrationHistory.size())
                .getValues("history_entry", String.class))
        .containsExactlyElementsOf(v1025MigrationHistory);
    f.tx.executeWithoutResult(
        ignored ->
            assertThat(new TemplateConfigSourceRepository(f.dsl).apply(automation).orElseThrow())
                .isEqualTo(
                    new TemplateConfigSourceSnapshot.Application(
                        automation, "1", automationSource)));

    var migratedDeclaration =
        f.dsl.fetchSingle(
            "SELECT request_id, commit_id, revision_order, owner, inventory_json, payload_json, "
                + "xmin::text AS row_xmin FROM game_design_template_config_owner_source_inventory_declaration "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND revision_id = ?",
            f.target.canonicalTenantId(),
            f.target.canonicalVersionId(),
            automation.revisions().getFirst().revisionId());
    assertThat(migratedDeclaration.get("request_id", UUID.class))
        .isEqualTo(retainedDeclaration.get("request_id", UUID.class));
    assertThat(migratedDeclaration.get("commit_id", UUID.class))
        .isEqualTo(retainedDeclaration.get("commit_id", UUID.class));
    assertThat(migratedDeclaration.get("revision_order", Integer.class))
        .isEqualTo(retainedDeclaration.get("revision_order", Integer.class));
    assertThat(migratedDeclaration.get("owner", String.class))
        .isEqualTo(retainedDeclaration.get("owner", String.class));
    assertThat(migratedDeclaration.get("inventory_json", String.class))
        .isEqualTo(originalInventoryJson);
    assertThat(migratedDeclaration.get("payload_json", String.class))
        .isEqualTo(originalPayloadJson);
    assertThat(migratedDeclaration.get("row_xmin", String.class))
        .isEqualTo(originalDeclarationXmin);
    var migratedCommit =
        f.dsl.fetchSingle(
            "SELECT binding_json, input_digest, xmin::text AS row_xmin FROM game_design_draft_commit "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ?",
            f.target.canonicalTenantId(),
            f.target.canonicalVersionId(),
            automation.requestId(),
            automation.commitId());
    assertThat(migratedCommit.get("binding_json", String.class)).isEqualTo(originalBindingJson);
    assertThat(migratedCommit.get("input_digest", String.class)).isEqualTo(originalBindingDigest);
    assertThat(migratedCommit.get("row_xmin", String.class)).isEqualTo(originalCommitXmin);
    var migratedApplication =
        f.dsl.fetchSingle(
            "SELECT snapshot_json, result_bytes, xmin::text AS row_xmin FROM game_design_template_config_source_application "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            f.target.canonicalTenantId(),
            f.target.canonicalVersionId(),
            automation.commitId());
    assertThat(migratedApplication.get("snapshot_json", String.class)).isEqualTo(v2SnapshotJson);
    assertThat(migratedApplication.get("result_bytes", byte[].class))
        .isEqualTo(originalApplicationBytes);
    assertThat(migratedApplication.get("row_xmin", String.class))
        .isEqualTo(originalApplicationXmin);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT snapshot_digest FROM game_design_template_config_source_snapshot "
                        + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                    f.target.canonicalTenantId(),
                    f.target.canonicalVersionId(),
                    automation.commitId())
                .get("snapshot_digest", String.class))
        .isEqualTo(v2SnapshotDigest);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT xmin::text AS row_xmin FROM game_design_template_config_source_snapshot "
                        + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                    f.target.canonicalTenantId(),
                    f.target.canonicalVersionId(),
                    automation.commitId())
                .get("row_xmin", String.class))
        .isEqualTo(originalV2SnapshotXmin);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT snapshot_json FROM game_design_template_config_source_snapshot "
                        + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                    f.target.canonicalTenantId(),
                    f.target.canonicalVersionId(),
                    initialTemplate.commitId())
                .get("snapshot_json", String.class))
        .isEqualTo(v1SnapshotJson);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT snapshot_digest FROM game_design_template_config_source_snapshot "
                        + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                    f.target.canonicalTenantId(),
                    f.target.canonicalVersionId(),
                    initialTemplate.commitId())
                .get("snapshot_digest", String.class))
        .isEqualTo(v1SnapshotDigest);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT xmin::text AS row_xmin FROM game_design_template_config_source_snapshot "
                        + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                    f.target.canonicalTenantId(),
                    f.target.canonicalVersionId(),
                    initialTemplate.commitId())
                .get("row_xmin", String.class))
        .isEqualTo(originalV1SnapshotXmin);
    var upgradedTemplateSources = new TemplateConfigSourceRepository(f.dsl);
    assertThat(
            upgradedTemplateSources
                .readSnapshot(f.target, initialTemplate.commitId())
                .orElseThrow()
                .canonicalJson())
        .isEqualTo(v1SnapshotJson);
    assertThat(
            upgradedTemplateSources
                .readSnapshot(f.target, automation.commitId())
                .orElseThrow()
                .canonicalJson())
        .isEqualTo(v2SnapshotJson);

    var entityInventory = EntityAuthoredSourceInventoryDeclaration.parse(entityInventory());
    var entity =
        binding(
            f,
            TemplateConfigSource.ownerInventoryPayload(
                DraftCommitBinding.Owner.ENTITY_MANAGEMENT, entityInventory),
            TemplateConfigSource.SCOPE,
            "2");
    apply(f, entity);
    var entitySource = source(f, entity);
    assertThat(entitySource.sourceEpoch()).isEqualTo("3");
    assertThat(entitySource.ownerSourceInventoryDeclarations()).hasSize(2);
    assertThat(entitySource.ownerSourceInventoryDeclarations().get(0))
        .isEqualTo(automationSource.ownerSourceInventoryDeclarations().getFirst());
    assertThat(entitySource.ownerSourceInventoryDeclarations().get(0).owner())
        .isEqualTo(DraftCommitBinding.Owner.AUTOMATION_SCRIPTING);
    assertThat(entitySource.ownerSourceInventoryDeclarations().get(0).sourceBinding())
        .isEqualTo(automation);
    assertThat(entitySource.ownerSourceInventoryDeclarations().get(1).owner())
        .isEqualTo(DraftCommitBinding.Owner.ENTITY_MANAGEMENT);
    assertThat(entitySource.ownerSourceInventoryDeclarations().get(1).sourceBinding())
        .isEqualTo(entity);
    assertThat(entitySource.entries()).hasSize(1);
    assertThat(f.dsl.fetchSingle("SELECT count(*) FROM game_templates").get(0, Long.class))
        .isEqualTo(1L);
    f.tx.executeWithoutResult(
        ignored ->
            assertThat(new TemplateConfigSourceRepository(f.dsl).apply(entity).orElseThrow())
                .isEqualTo(
                    new TemplateConfigSourceSnapshot.Application(entity, "2", entitySource)));

    var unrelated =
        binding(
            f,
            GameplayRuleSource.upsertPayload(new GameplayRuleManifest.AdmissionTag("after-entity")),
            GameplayRuleSource.SCOPE,
            "0");
    apply(f, unrelated);
    var inherited = source(f, unrelated);
    assertThat(inherited.ownerSourceInventoryDeclarations())
        .containsExactlyElementsOf(entitySource.ownerSourceInventoryDeclarations());
    assertThat(inherited.ownerSourceInventoryDeclarations().getFirst().sourceBinding())
        .isEqualTo(automation);
    assertThat(inherited.ownerSourceInventoryDeclarations().get(1).sourceBinding())
        .isEqualTo(entity);
    var postEntityReadback = new TemplateConfigSourceRepository(f.dsl);
    var retainedV1 =
        postEntityReadback.readSnapshot(f.target, initialTemplate.commitId()).orElseThrow();
    assertThat(retainedV1.canonicalJson()).isEqualTo(v1SnapshotJson);
    assertThat(retainedV1.digest()).isEqualTo(v1SnapshotDigest);
    var retainedAutomation =
        postEntityReadback.readSnapshot(f.target, automation.commitId()).orElseThrow();
    assertThat(retainedAutomation.canonicalJson()).isEqualTo(v2SnapshotJson);
    assertThat(retainedAutomation.digest()).isEqualTo(v2SnapshotDigest);
    var retainedInherited =
        postEntityReadback.readSnapshot(f.target, unrelated.commitId()).orElseThrow();
    assertThat(retainedInherited.ownerSourceInventoryDeclarations())
        .containsExactlyElementsOf(entitySource.ownerSourceInventoryDeclarations());
    var postEntityAutomationApplication =
        f.dsl.fetchSingle(
            "SELECT snapshot_json, result_bytes FROM game_design_template_config_source_application "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            f.target.canonicalTenantId(),
            f.target.canonicalVersionId(),
            automation.commitId());
    assertThat(postEntityAutomationApplication.get("snapshot_json", String.class))
        .isEqualTo(v2SnapshotJson);
    assertThat(postEntityAutomationApplication.get("result_bytes", byte[].class))
        .isEqualTo(originalApplicationBytes);
    var postEntityAutomationDeclaration =
        f.dsl.fetchSingle(
            "SELECT request_id, commit_id, revision_order, owner, inventory_json, payload_json FROM "
                + "game_design_template_config_owner_source_inventory_declaration "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND revision_id = ?",
            f.target.canonicalTenantId(),
            f.target.canonicalVersionId(),
            automation.revisions().getFirst().revisionId());
    assertThat(postEntityAutomationDeclaration.get("request_id", UUID.class))
        .isEqualTo(automation.requestId());
    assertThat(postEntityAutomationDeclaration.get("commit_id", UUID.class))
        .isEqualTo(automation.commitId());
    assertThat(postEntityAutomationDeclaration.get("revision_order", Integer.class)).isZero();
    assertThat(postEntityAutomationDeclaration.get("owner", String.class))
        .isEqualTo("AUTOMATION_SCRIPTING");
    assertThat(postEntityAutomationDeclaration.get("inventory_json", String.class))
        .isEqualTo(originalInventoryJson);
    assertThat(postEntityAutomationDeclaration.get("payload_json", String.class))
        .isEqualTo(originalPayloadJson);
  }

  @Test
  void deferredTemplateApplicationGuardRejectsMissingOrChangedAtomicOwnerResult() {
    var f = fixture();
    var automationInventory =
        AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
    var missingResult =
        binding(
            f,
            TemplateConfigSource.ownerInventoryPayload(
                DraftCommitBinding.Owner.AUTOMATION_SCRIPTING, automationInventory),
            TemplateConfigSource.SCOPE,
            "0");
    assertThatThrownBy(
            () ->
                f.tx.executeWithoutResult(
                    ignored -> {
                      stageInProgressTemplateApplication(f, missingResult);
                      f.dsl.execute(
                          "SET CONSTRAINTS template_config_application_commit_guard IMMEDIATE");
                    }))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining("template config application requires exact atomic owner result");

    var entityInventory = EntityAuthoredSourceInventoryDeclaration.parse(entityInventory());
    var changedResult =
        binding(
            f,
            TemplateConfigSource.ownerInventoryPayload(
                DraftCommitBinding.Owner.ENTITY_MANAGEMENT, entityInventory),
            TemplateConfigSource.SCOPE,
            "0");
    assertThatThrownBy(
            () ->
                f.tx.executeWithoutResult(
                    ignored -> {
                      var application = stageInProgressTemplateApplication(f, changedResult);
                      byte[] alteredResultBytes = application.canonicalBytes();
                      alteredResultBytes[0] ^= 1;
                      new DraftCommitCoordinatorRepository(f.dsl)
                          .recordOwnerOutcome(
                              changedResult,
                              new DraftCommitCoordinatorRepository.OwnerOutcome(
                                  DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                                  DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
                                  changedResult.commitId(),
                                  changedResult.digest(),
                                  "ordinary-source:" + changedResult.commitId(),
                                  alteredResultBytes,
                                  List.of(application.appliedEpoch())));
                      f.dsl.execute(
                          "SET CONSTRAINTS template_config_application_commit_guard IMMEDIATE");
                    }))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining(
            "template config owner result must contain exact complete tagged source bytes");
  }

  @Test
  void actualCreateAndSameCommitGameplayInputHaveDurableGeneratedIdentityAndExactRetry() {
    var f = fixture();
    UUID ruleRevision = UUID.randomUUID();
    var rule =
        new DraftCommitBinding.RevisionPayload(
            "0",
            ruleRevision,
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.upsertPayload(new GameplayRuleManifest.AdmissionTag("ordinary")));
    var config =
        config(
            f,
            "[{\"family\":\"ADMISSION_TAGS\",\"key\":\"ordinary\",\"revisionId\":\""
                + ruleRevision
                + "\"}]");
    var create =
        new DraftCommitBinding.RevisionPayload(
            "1",
            UUID.randomUUID(),
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            TemplateConfigSource.createPayload("Starter", config));
    var binding =
        binding(
            f,
            List.of(rule, create),
            Map.of(GameplayRuleSource.SCOPE, "0", TemplateConfigSource.SCOPE, "0"));
    apply(f, binding);
    var first = source(f, binding);
    String templateId = first.entries().getFirst().templateId();
    assertThat(first.entries().getFirst().sourceBinding()).isEqualTo(binding);
    var initialBaseReference =
        f.dsl.fetchOne(
            "SELECT canonical_tenant_id, template_id, canonical_version_id, source_commit_id, source_revision_id "
                + "FROM game_template_version_ref WHERE canonical_tenant_id = ? AND template_id = ?",
            f.target.canonicalTenantId(),
            Long.parseLong(templateId));
    assertThat(initialBaseReference).isNotNull();
    assertThat(initialBaseReference.get("canonical_version_id", UUID.class))
        .isEqualTo(f.target.canonicalVersionId());
    assertThat(initialBaseReference.get("source_commit_id", UUID.class))
        .isEqualTo(binding.commitId());
    assertThat(initialBaseReference.get("source_revision_id", UUID.class))
        .isEqualTo(create.revisionId());
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT count(*) FROM game_design_template_config_source_ref WHERE revision_id = ?",
                    create.revisionId())
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT template_reference_phase FROM game_templates WHERE id = ?",
                    Long.parseLong(templateId))
                .get(0, String.class))
        .isEqualTo("LEGACY");
    // Replay reads the actual original stored application, not a second CREATE or current name
    // lookup.
    f.tx.executeWithoutResult(
        ignored ->
            assertThat(
                    new GameDesignSourceRepository(f.dsl)
                        .apply(binding)
                        .templateConfig()
                        .orElseThrow()
                        .snapshot())
                .isEqualTo(first));
    assertThat(f.dsl.fetchSingle("SELECT count(*) FROM game_templates").get(0, Long.class))
        .isEqualTo(1L);
    var sibling = binding(f, commandUpsert("TEMPLATE_SIBLING"), CommandSource.SCOPE, "0");
    apply(f, sibling);
    assertThat(source(f, sibling).entries()).isEqualTo(first.entries());
    assertThat(source(f, sibling).sourceEpoch()).isEqualTo("1");

    var update =
        binding(
            f,
            TemplateConfigSource.upsertPayload(templateId, config(f, "[]")),
            TemplateConfigSource.SCOPE,
            "1");
    apply(f, update);
    assertThat(source(f, update).entries().getFirst().sourceBinding()).isEqualTo(update);
    var updatedBaseReference =
        f.dsl.fetchOne(
            "SELECT canonical_version_id, source_commit_id, source_revision_id "
                + "FROM game_template_version_ref WHERE canonical_tenant_id = ? AND template_id = ?",
            f.target.canonicalTenantId(),
            Long.parseLong(templateId));
    assertThat(updatedBaseReference).isNotNull();
    assertThat(updatedBaseReference.get("canonical_version_id", UUID.class))
        .isEqualTo(f.target.canonicalVersionId());
    assertThat(updatedBaseReference.get("source_commit_id", UUID.class))
        .isEqualTo(update.commitId());
    assertThat(updatedBaseReference.get("source_revision_id", UUID.class))
        .isEqualTo(TemplateConfigSource.mutations(update).getFirst().revisionId());
    assertThat(source(f, binding)).isEqualTo(first);
    var delete =
        binding(f, TemplateConfigSource.deletePayload(templateId), TemplateConfigSource.SCOPE, "2");
    apply(f, delete);
    assertThat(source(f, delete).entries()).isEmpty();
    assertThat(
            f.dsl.fetchOne(
                "SELECT 1 FROM game_template_version_ref WHERE canonical_tenant_id = ? AND template_id = ?",
                f.target.canonicalTenantId(),
                Long.parseLong(templateId)))
        .isNull();
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT count(*) FROM game_templates WHERE id = ?", Long.parseLong(templateId))
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(source(f, binding)).isEqualTo(first);
    assertThatThrownBy(
            () ->
                f.dsl.execute(
                    "UPDATE game_design_template_config_source_revision SET payload_json = '{}'"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(() -> f.dsl.execute("DELETE FROM game_design_template_config_source_ref"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () ->
                f.dsl.execute(
                    "UPDATE game_templates SET config = '{}' WHERE id = ?",
                    Long.parseLong(templateId)))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  @Test
  void unsupportedExternalOwnerReferencesRollbackWithoutInventingQualifiedRows() {
    var f = fixture();
    var unsupported =
        new TemplateConfigSource.Config(
            config(f, "[]")
                .canonicalJson()
                .replace(
                    "\"regions\":[]",
                    "\"regions\":[{\"regionTemplateId\":\"" + UUID.randomUUID() + "\"}]"));
    var binding =
        binding(
            f,
            TemplateConfigSource.createPayload("Denied", unsupported),
            TemplateConfigSource.SCOPE,
            "0");
    assertThatThrownBy(() -> apply(f, binding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TEMPLATE_CONFIG_WORLD_EXACT_OWNER_READ_UNAVAILABLE");
    assertThat(f.dsl.fetchSingle("SELECT count(*) FROM game_templates").get(0, Long.class))
        .isZero();
    assertThat(new TemplateConfigSourceRepository(f.dsl).readSnapshot(f.target, binding.commitId()))
        .isEmpty();
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT count(*) FROM game_design_template_config_source_qualification")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void legacyTenantMatchingRowCannotAcquireCanonicalSourceAssociation() {
    var f = fixture();
    long legacy =
        f.dsl
            .fetchSingle(
                "INSERT INTO game_templates (tenant_id, name, config) VALUES (?, 'Unqualified', '{}'::JSONB) RETURNING id",
                f.target.gameDesignVersionTenantKey())
            .get(0, Long.class);
    var binding =
        binding(
            f,
            TemplateConfigSource.upsertPayload(Long.toString(legacy), config(f, "[]")),
            TemplateConfigSource.SCOPE,
            "0");
    assertThatThrownBy(() -> apply(f, binding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TEMPLATE_CONFIG_TEMPLATE_OWNER_PROVENANCE_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                f.tx.executeWithoutResult(
                    ignored ->
                        f.dsl.execute(
                            "INSERT INTO game_design_template_config_source_row_insert VALUES (?, pg_current_xact_id()::TEXT)",
                            legacy)))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(new TemplateConfigSourceRepository(f.dsl).readSnapshot(f.target, binding.commitId()))
        .isEmpty();
  }

  @Test
  void explicitTargetRawInsertAndLegacyReassignmentCannotCommit() {
    var f = fixture();
    assertThatThrownBy(
            () ->
                f.dsl.execute(
                    "INSERT INTO game_templates (tenant_id, name, config, default_version_id) VALUES (?, 'Raw target', '{}'::JSONB, ?)",
                    f.target.gameDesignVersionTenantKey(),
                    f.target.gameDesignVersionRowId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining(
            "explicit template target requires actual authorized CREATE qualification");
    long legacy =
        f.dsl
            .fetchSingle(
                "INSERT INTO game_templates (tenant_id, name, config) VALUES (?, 'Unrelated legacy', '{}'::JSONB) RETURNING id",
                f.target.gameDesignVersionTenantKey())
            .get(0, Long.class);
    assertThatThrownBy(
            () ->
                f.dsl.execute(
                    "UPDATE game_templates SET default_version_id = ? WHERE id = ?",
                    f.target.gameDesignVersionRowId(),
                    legacy))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining(
            "unqualified template cannot be reassigned into managed source target");
    assertThat(
            f.dsl
                .fetchSingle("SELECT default_version_id FROM game_templates WHERE id = ?", legacy)
                .get(0, Long.class))
        .isNull();
    var selected = binding(f, commandUpsert("TEMPLATE_SELECTED"), CommandSource.SCOPE, "0");
    apply(f, selected);
    assertThat(source(f, selected).entries()).isEmpty();
    assertThatThrownBy(
            () ->
                f.dsl.execute(
                    "INSERT INTO game_templates (tenant_id, name, config, default_version_id) VALUES (?, 'After capture', '{}'::JSONB, ?)",
                    f.target.gameDesignVersionTenantKey(),
                    f.target.gameDesignVersionRowId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining(
            "explicit template target requires actual authorized CREATE qualification");
    assertThat(source(f, selected).entries()).isEmpty();
  }

  @Test
  void newCaptureCannotIgnorePendingUnqualifiedExplicitTargetRow() {
    var f = fixture();
    var selected = binding(f, commandUpsert("TEMPLATE_BLOCKED"), CommandSource.SCOPE, "0");
    assertThatThrownBy(
            () ->
                f.tx.executeWithoutResult(
                    ignored -> {
                      // The deferred insert guard has not fired: new source capture must
                      // independently deny.
                      f.dsl.execute(
                          "INSERT INTO game_templates (tenant_id, name, config, default_version_id) VALUES (?, 'Pending raw target', '{}'::JSONB, ?)",
                          f.target.gameDesignVersionTenantKey(),
                          f.target.gameDesignVersionRowId());
                      apply(f, selected);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TEMPLATE_CONFIG_EXPLICIT_TARGET_INVENTORY_UNQUALIFIED");
    assertThat(
            new TemplateConfigSourceRepository(f.dsl).readSnapshot(f.target, selected.commitId()))
        .isEmpty();
    assertThat(f.dsl.fetchSingle("SELECT count(*) FROM game_templates").get(0, Long.class))
        .isZero();
  }

  @Test
  void changedGameplayRevisionCannotLeaveStaleTemplateReferencesVisible() {
    var f = fixture();
    UUID ruleId = UUID.randomUUID();
    var config =
        config(
            f,
            "[{\"family\":\"ADMISSION_TAGS\",\"key\":\"ordinary\",\"revisionId\":\""
                + ruleId
                + "\"}]");
    var initial =
        binding(
            f,
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    ruleId,
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSource.upsertPayload(
                        new GameplayRuleManifest.AdmissionTag("ordinary"))),
                new DraftCommitBinding.RevisionPayload(
                    "1",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    TemplateConfigSource.createPayload("Starter", config))),
            Map.of(GameplayRuleSource.SCOPE, "0", TemplateConfigSource.SCOPE, "0"));
    apply(f, initial);
    var changed =
        binding(
            f,
            GameplayRuleSource.upsertPayload(new GameplayRuleManifest.AdmissionTag("ordinary")),
            GameplayRuleSource.SCOPE,
            "1");
    assertThatThrownBy(() -> apply(f, changed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TEMPLATE_CONFIG_GAMEPLAY_INPUT_CHANGED");
    assertThat(new TemplateConfigSourceRepository(f.dsl).readSnapshot(f.target, changed.commitId()))
        .isEmpty();
    assertThat(source(f, initial).entries()).hasSize(1);
  }

  @Test
  void selectedPublicationRetainsInheritedTemplateToOriginalWorldSourceAssociation()
      throws Exception {
    var f = fixture();
    UUID ruleRevision = UUID.randomUUID();
    var rule =
        new DraftCommitBinding.RevisionPayload(
            "0",
            ruleRevision,
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.upsertPayload(new GameplayRuleManifest.AdmissionTag("ordinary")));
    var config =
        config(
            f,
            "[{\"family\":\"ADMISSION_TAGS\",\"key\":\"ordinary\",\"revisionId\":\""
                + ruleRevision
                + "\"}]");
    var create =
        new DraftCommitBinding.RevisionPayload(
            "1",
            UUID.randomUUID(),
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            TemplateConfigSource.createPayload("Starter", config));
    var initial =
        binding(
            f,
            List.of(rule, create),
            Map.of(GameplayRuleSource.SCOPE, "0", TemplateConfigSource.SCOPE, "0"));
    apply(f, initial);
    var worldSeed =
        IsolatedPublicationOperationFixtures.fresh(f.target, initial.commitId().toString());
    var selectedCommit = worldSeed.account().input().selection().selectedCommit();
    applyIsolatedWorldSuccessor(f, selectedCommit, worldSeed.world());
    assertThat(selectedCommit.commitId()).isNotEqualTo(initial.commitId());

    // These isolated upstream bytes exercise this owner only; they do not prove authenticated
    // Account or World producer authority.
    var inputs =
        selectedPublicationInputs(f, selectedCommit, "selected inherited template association");
    var operation =
        Objects.requireNonNull(
                f.tx.execute(
                    ignored ->
                        new SelectedDraftPublicationOwner(f.dsl)
                            .reserve(
                                inputs.intent(),
                                inputs.operation().account(),
                                inputs.operation().world(),
                                inputs.operation().inventory(),
                                inputs.firstRead())))
            .operation();
    var sourceCapture = new GameDesignSourceRepository(f.dsl).readCapture(operation).orElseThrow();
    var templateCapture = sourceCapture.templateConfig().orElseThrow();
    assertThat(templateCapture.snapshot().entries()).hasSize(1);
    assertThat(templateCapture.snapshot().binding().commitId())
        .isEqualTo(operation.account().input().selection().selectedCommit().commitId());
    assertThat(templateCapture.snapshot().entries().getFirst().sourceBinding().commitId())
        .isEqualTo(initial.commitId());

    var associations = new SelectedDraftTemplateWorldSourceAssociationRepository(f.dsl);
    var retained = associations.readExact(operation, templateCapture);
    assertThat(retained).hasSize(1);
    assertThat(retained.getFirst().selectedCommitId())
        .isEqualTo(operation.account().input().selection().selectedCommit().commitId());
    assertThat(retained.getFirst().sourceOperationId()).isNotNull();
    assertThat(retained.getFirst().worldOperationId()).isNotNull();
    assertThat(retained.getFirst().worldReadRequestBytes()).isNotEmpty();
    assertThat(retained.getFirst().worldReadResponseBytes()).isNotEmpty();
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT count(*) FROM game_design_selected_template_world_source_association")
                .get(0, Long.class))
        .isEqualTo(1L);

    var selection =
        AuthoredDraftPublishSelection.fromStored(
            operation.account().input().selection().canonicalJson(),
            operation.account().input().selection().digest());
    var originalWorldSourceRead = inputs.firstRead();
    UUID replayReadRequestId = UUID.randomUUID();
    while (replayReadRequestId.equals(originalWorldSourceRead.request().intakeRequestId())) {
      replayReadRequestId = UUID.randomUUID();
    }
    var replayWorldSourceRead =
        new SelectedDraftTemplateWorldSourceAssociation.SourceRead(
            new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                1,
                originalWorldSourceRead.request().targetNamespace(),
                replayReadRequestId,
                originalWorldSourceRead.request().intakeRequestId(),
                originalWorldSourceRead.request().canonicalTenantId()),
            originalWorldSourceRead.receipt());
    f.tx.executeWithoutResult(
        ignored ->
            new SelectedDraftPublicationOwner(f.dsl)
                .reserve(
                    selection.intent(),
                    operation.account(),
                    operation.world(),
                    operation.inventory(),
                    replayWorldSourceRead));
    assertThat(associations.readExact(operation, templateCapture).getFirst().canonicalBytes())
        .containsExactly(retained.getFirst().canonicalBytes());
    var originalReceipt = originalWorldSourceRead.receipt();
    var substitutedReceipt =
        new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
            originalReceipt.schemaVersion(),
            originalReceipt.targetNamespace(),
            originalReceipt.intakeRequestId(),
            UUID.randomUUID(),
            originalReceipt.canonicalTenantId(),
            originalReceipt.worldSlug(),
            originalReceipt.sourceOperationId(),
            originalReceipt.sourceEvidenceDigest(),
            originalReceipt.requestDigest(),
            originalReceipt.receiptDigest(),
            originalReceipt.source());
    UUID substitutedReadRequestId = UUID.randomUUID();
    while (substitutedReadRequestId.equals(originalWorldSourceRead.request().intakeRequestId())) {
      substitutedReadRequestId = UUID.randomUUID();
    }
    var substitutedWorldSourceRead =
        new SelectedDraftTemplateWorldSourceAssociation.SourceRead(
            new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                1,
                originalWorldSourceRead.request().targetNamespace(),
                substitutedReadRequestId,
                originalWorldSourceRead.request().intakeRequestId(),
                originalWorldSourceRead.request().canonicalTenantId()),
            substitutedReceipt);
    assertThatThrownBy(
            () ->
                f.tx.executeWithoutResult(
                    ignored ->
                        new SelectedDraftPublicationOwner(f.dsl)
                            .reserve(
                                selection.intent(),
                                operation.account(),
                                operation.world(),
                                operation.inventory(),
                                substitutedWorldSourceRead)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ASSOCIATION_CONFLICT");
    assertThat(associations.readExact(operation, templateCapture).getFirst().canonicalBytes())
        .containsExactly(retained.getFirst().canonicalBytes());
    assertThatThrownBy(
            () ->
                f.dsl.execute(
                    "UPDATE game_design_selected_template_world_source_association SET world_slug = 'substituted'"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  @Test
  void concurrentExactFirstReservationsKeepTheWinningReadCorrelation() throws Exception {
    var f = fixture();
    var initial =
        binding(
            f,
            TemplateConfigSource.createPayload("Starter", config(f, "[]")),
            TemplateConfigSource.SCOPE,
            "0");
    apply(f, initial);
    var worldSeed =
        IsolatedPublicationOperationFixtures.fresh(f.target, initial.commitId().toString());
    var selectedCommit = worldSeed.account().input().selection().selectedCommit();
    applyIsolatedWorldSuccessor(f, selectedCommit, worldSeed.world());
    assertThat(selectedCommit.commitId()).isNotEqualTo(initial.commitId());

    // The caller names the exact synchronized Draft; concurrent owner calls race only over the
    // first durable selection/association transaction. Upstream evidence remains stipulated.
    var inputs =
        selectedPublicationInputs(f, selectedCommit, "concurrent selected template association");
    var selection = inputs.selection();
    var operation = inputs.operation();
    var firstRead = inputs.firstRead();
    UUID competingReadRequestId = UUID.randomUUID();
    while (competingReadRequestId.equals(firstRead.request().intakeRequestId())
        || competingReadRequestId.equals(firstRead.request().readRequestId())) {
      competingReadRequestId = UUID.randomUUID();
    }
    var competingRead =
        new SelectedDraftTemplateWorldSourceAssociation.SourceRead(
            new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                firstRead.request().schemaVersion(),
                firstRead.request().targetNamespace(),
                competingReadRequestId,
                firstRead.request().intakeRequestId(),
                firstRead.request().canonicalTenantId()),
            firstRead.receipt());

    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> concurrentReserve(f, operation, selection, firstRead, ready, start));
      var second =
          executor.submit(
              () -> concurrentReserve(f, operation, selection, competingRead, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var firstReservation = first.get(30, TimeUnit.SECONDS);
      var secondReservation = second.get(30, TimeUnit.SECONDS);
      assertThat(firstReservation.operation().canonicalBytes())
          .containsExactly(operation.canonicalBytes());
      assertThat(secondReservation.operation().canonicalBytes())
          .containsExactly(operation.canonicalBytes());
    } finally {
      executor.shutdownNow();
    }

    var capture = new GameDesignSourceRepository(f.dsl).readCapture(operation).orElseThrow();
    var retained =
        new SelectedDraftTemplateWorldSourceAssociationRepository(f.dsl)
            .readExact(operation, capture.templateConfig().orElseThrow());
    assertThat(retained).hasSize(1);
    byte[] winnerRequest = retained.getFirst().worldReadRequestBytes();
    assertThat(
            Arrays.equals(winnerRequest, firstRead.requestBytes())
                || Arrays.equals(winnerRequest, competingRead.requestBytes()))
        .isTrue();
    assertThat(retained.getFirst().worldReadResponseBytes()).isNotEmpty();
  }

  private static SelectedDraftPublicationOwner.Reservation concurrentReserve(
      Fixture f,
      GameDesignPublicationOperation operation,
      AuthoredDraftPublishSelectionBinding selection,
      SelectedDraftTemplateWorldSourceAssociation.SourceRead sourceRead,
      CountDownLatch ready,
      CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting to start concurrent owner reservation");
    }
    return Objects.requireNonNull(
        f.tx.execute(
            ignored ->
                new SelectedDraftPublicationOwner(f.dsl)
                    .reserve(
                        AuthoredDraftPublishSelection.fromStored(
                                selection.canonicalJson(), selection.digest())
                            .intent(),
                        operation.account(),
                        operation.world(),
                        operation.inventory(),
                        sourceRead)),
        "concurrent owner reservation returned no result");
  }

  private SelectedPublicationInputs selectedPublicationInputs(
      Fixture f, DraftCommitBinding selectedCommit, String notes) throws Exception {
    var prepared =
        Objects.requireNonNull(
            f.tx.execute(
                ignored -> {
                  String versionEpoch =
                      Objects.requireNonNull(
                              f.dsl
                                  .fetchSingle(
                                      "SELECT version_state_epoch FROM version WHERE id = ?",
                                      f.target.gameDesignVersionRowId())
                                  .get(0, Long.class))
                          .toString();
                  var intent =
                      new AuthoredDraftPublishSelection.PublishIntent(
                          f.target.canonicalTenantId(),
                          f.target.canonicalVersionId(),
                          UUID.randomUUID().toString(),
                          versionEpoch,
                          notes,
                          selectedCommit.requestId(),
                          selectedCommit.commitId(),
                          selectedCommit.digest());
                  var coordinator = new DraftCommitCoordinatorRepository(f.dsl);
                  var evidence =
                      coordinator.requireSynchronizedPublicationEvidence(
                          f.target,
                          selectedCommit.requestId(),
                          selectedCommit.commitId(),
                          selectedCommit.digest());
                  return new PreparedSelection(
                      intent, AuthoredDraftPublishSelection.capture(intent, f.target, evidence));
                }));
    var selection =
        AuthoredDraftPublishSelectionBinding.fromStored(
            prepared.selection().canonicalJson(), prepared.selection().digest());
    var worldSeed = PublishedWorldSelectorFixtures.evidence(f.target, selectedCommit);
    var operation = IsolatedPublicationOperationFixtures.forSelection(selection, worldSeed);
    return new SelectedPublicationInputs(
        prepared.intent(),
        selection,
        operation,
        syntheticWorldSourceRead(f.target, operation.world()));
  }

  /** Structured, isolated source evidence for owner-local association tests only. */
  private static SelectedDraftTemplateWorldSourceAssociation.SourceRead syntheticWorldSourceRead(
      DraftCommitBinding.TargetProof target, WorldPublishedStartLocationEvidence world) {
    UUID registrationRequestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String namespace = world.request().targetNamespace();
    UUID intakeRequestId = world.request().intakeRequestId();
    UUID tenantId = world.request().canonicalTenantId();
    String tenantSlug = "isolated-tenant";
    String worldSlug = "world";
    String worldDisplayName = "Isolated World";
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, registrationRequestId, tenantId, tenantSlug, worldSlug, worldDisplayName);
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind());
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            namespace,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind(),
            sourceEvidenceDigest);
    var binding =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1,
            namespace,
            intakeRequestId,
            tenantId,
            worldSlug,
            sourceOperationId,
            sourceEvidenceDigest);
    var receipt =
        new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
            1,
            namespace,
            intakeRequestId,
            UUID.randomUUID(),
            tenantId,
            worldSlug,
            sourceOperationId,
            sourceEvidenceDigest,
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(binding),
            "a".repeat(64),
            source);
    return new SelectedDraftTemplateWorldSourceAssociation.SourceRead(
        new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
            1, namespace, UUID.randomUUID(), intakeRequestId, tenantId),
        receipt);
  }

  private record PreparedSelection(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AuthoredDraftPublishSelection selection) {}

  private record SelectedPublicationInputs(
      AuthoredDraftPublishSelection.PublishIntent intent,
      AuthoredDraftPublishSelectionBinding selection,
      GameDesignPublicationOperation operation,
      SelectedDraftTemplateWorldSourceAssociation.SourceRead firstRead) {}

  private TemplateConfigSourceSnapshot source(Fixture f, DraftCommitBinding binding) {
    return new GameDesignSourceRepository(f.dsl)
        .readSynchronized(f.target, binding.commitId())
        .orElseThrow()
        .templateConfig()
        .orElseThrow();
  }

  private void apply(Fixture f, DraftCommitBinding binding) {
    f.tx.executeWithoutResult(
        ignored -> {
          var coordinator = new DraftCommitCoordinatorRepository(f.dsl);
          var reviewed = new GameDesignReviewedBaseRepository(f.dsl);
          var sources = new GameDesignSourceRepository(f.dsl);
          sources.claimReviewed(
              binding,
              reviewed.resolve(f.target, DraftBaseReference.parse(binding.baseCommitId())));
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

  /** Retains controlled World owner/source inputs without reserving the publication under test. */
  private void applyIsolatedWorldSuccessor(
      Fixture f,
      DraftCommitBinding binding,
      WorldPublishedStartLocationEvidence worldEvidence) {
    f.tx.executeWithoutResult(
        ignored -> {
          var coordinator = new DraftCommitCoordinatorRepository(f.dsl);
          var reviewed = new GameDesignReviewedBaseRepository(f.dsl);
          var sources = new GameDesignSourceRepository(f.dsl);
          sources.claimReviewed(
              binding,
              reviewed.resolve(f.target, DraftBaseReference.parse(binding.baseCommitId())));
          coordinator.claimApplicationSlot(binding);
          coordinator.markOwnerInProgress(binding, DraftCommitBinding.Owner.WORLD_MANAGEMENT);
          var appliedEpochs =
              binding.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
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
                  binding.commitId(),
                  binding.digest(),
                  "ISOLATED-world-applied",
                  worldEvidence.appliedResultBytes(),
                  appliedEpochs);
          coordinator.recordOwnerOutcome(binding, outcome);
          coordinator.advanceSourceVisibilityFence(
              binding,
              new DraftCommitCoordinatorRepository.CoordinatorProof(binding, List.of(outcome)));
          sources.captureSynchronized(binding);
          coordinator.releaseApplicationSlot(binding);
        });
  }

  private static TemplateConfigSourceSnapshot.Application stageInProgressTemplateApplication(
      Fixture f, DraftCommitBinding binding) {
    var coordinator = new DraftCommitCoordinatorRepository(f.dsl);
    coordinator.claim(binding);
    coordinator.claimApplicationSlot(binding);
    coordinator.markOwnerInProgress(binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
    return new TemplateConfigSourceRepository(f.dsl).apply(binding).orElseThrow();
  }

  private static TemplateConfigSource.Config config(Fixture f, String inputs) {
    return new TemplateConfigSource.Config(
        "{\"schemaVersion\":1,\"baseVersionId\":\""
            + f.target.canonicalVersionId()
            + "\",\"world\":{\"regions\":[],\"rooms\":[]},\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":"
            + inputs
            + "},\"automation\":{\"scripts\":[],\"scriptPatch\":{\"presence\":\"ABSENT\"}},\"supportedSettings\":[]}");
  }

  private static String automationInventory() {
    return "{\"schema\":\"automation-authored-source-inventory/v1\","
        + "\"families\":{\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
        + "\"SCRIPT_PATCH_SOURCES\":[]}}";
  }

  private static String entityInventory() {
    return "{\"schema\":\"entity-authored-source-inventory/v1\","
        + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
        + "\"ACTOR_BODY_LAYOUT_ASSIGNMENTS\":[],\"ARCHETYPE_ASSIGNMENTS\":[],"
        + "\"ARCHETYPE_CONSTRAINTS\":[],\"ARCHETYPE_ROOTS\":[],"
        + "\"BALANCE_CURVE_ATTACHMENTS\":[],\"BALANCE_CURVE_ROOTS\":[],"
        + "\"BODY_LAYOUT_MEMBERSHIPS\":[],\"BODY_LAYOUT_ROOTS\":[],"
        + "\"CRAFTING_INGREDIENT_BINDINGS\":[],\"CRAFTING_RECIPE_RESULT_BINDINGS\":[],"
        + "\"CRAFTING_RECIPE_ROOTS\":[],\"EQUIPMENT_ATTACHMENT_RULES\":[],"
        + "\"EQUIPMENT_CAPABILITIES\":[],\"EQUIPMENT_COMPATIBILITY_RULES\":[],"
        + "\"EQUIPMENT_OCCUPANCY_RULES\":[],\"EQUIPMENT_SLOT_GROUPS\":[],"
        + "\"EQUIPMENT_SLOT_ROOTS\":[],\"INBOUND_LOOT_BINDINGS\":[],"
        + "\"ITEM_TEMPLATE_ROOTS\":[],\"LOOT_ITEM_MAPPINGS\":[],"
        + "\"LOOT_TABLE_ROOTS\":[],\"NPC_TEMPLATE_ROOTS\":[],"
        + "\"OTHER_ACTOR_TEMPLATE_ROOTS\":[]}}";
  }

  private DraftCommitBinding binding(Fixture f, String payload, String scope, String epoch) {
    return binding(
        f,
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                payload)),
        Map.of(scope, epoch));
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
      Fixture f, List<DraftCommitBinding.RevisionPayload> revisions, Map<String, String> scopes) {
    var units =
        scopes.entrySet().stream()
            .map(
                e ->
                    new DraftCommitBinding.AffectedUnit(
                        DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                        e.getKey(),
                        f.target.canonicalVersionId().toString(),
                        e.getKey(),
                        "effective",
                        e.getValue()))
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
        new GameDesignSourceRepository(f.dsl)
            .readGenesis(f.target)
            .orElseThrow()
            .policy()
            .receiptId();
    return "genesis:" + genesisReceipt;
  }

  private Fixture fixture() {
    return fixture(null);
  }

  private Fixture fixture(String migrationTarget) {
    String schema = "template_config_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    data.setSchema(schema);
    var flyway =
        Flyway.configure()
            .dataSource(data)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history_game_design_service")
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (migrationTarget != null) flyway.target(MigrationVersion.fromVersion(migrationTarget));
    flyway.load().migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(data));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("Template source fixture");
    var savedGame =
        Objects.requireNonNull(tx.execute(ignored -> new GameRepository(dsl).save(game)));
    var version = new Version();
    version.setTenantId(savedGame.getTenantId());
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    var saved =
        Objects.requireNonNull(tx.execute(ignored -> new VersionRepository(dsl).save(version)));
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

  private static void migrateToLatest(Fixture f) {
    Flyway.configure()
        .dataSource(f.dataSource())
        .schemas(f.schema())
        .defaultSchema(f.schema())
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", f.schema()))
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate tx,
      DraftCommitBinding.TargetProof target,
      DriverManagerDataSource dataSource,
      String schema) {}
}
