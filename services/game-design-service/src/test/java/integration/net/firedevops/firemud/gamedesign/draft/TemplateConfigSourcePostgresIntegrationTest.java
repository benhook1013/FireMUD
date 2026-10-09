package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSource;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationOwner;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociation;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociationRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSource;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceSnapshot;
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
    var sibling = binding(f, CommandSource.deletePayload("absent"), CommandSource.SCOPE, "0");
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
    assertThat(source(f, binding)).isEqualTo(first);
    var delete =
        binding(f, TemplateConfigSource.deletePayload(templateId), TemplateConfigSource.SCOPE, "2");
    apply(f, delete);
    assertThat(source(f, delete).entries()).isEmpty();
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
    var selected = binding(f, CommandSource.deletePayload("absent"), CommandSource.SCOPE, "0");
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
    var selected = binding(f, CommandSource.deletePayload("absent"), CommandSource.SCOPE, "0");
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
  void selectedPublicationRetainsInheritedTemplateToOriginalWorldSourceAssociation() {
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

    // This owner-local fixture supplies structured synthetic World evidence; it is not a proof of
    // the authenticated World or Account producer boundary.
    var operation =
        f.tx.execute(
            ignored -> {
              try {
                return IsolatedPublicationOwnerSetup.retainSourceBacked(
                    f.dsl, f.target, 1L, "selected inherited template association");
              } catch (Exception e) {
                throw new IllegalStateException("Unable to retain source-backed publication", e);
              }
            });
    var sourceCapture = new GameDesignSourceRepository(f.dsl).readCapture(operation).orElseThrow();
    var templateCapture = sourceCapture.templateConfig();
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
    var originalWorldSourceRead = IsolatedPublicationOwnerSetup.syntheticWorldSourceRead(operation);
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

    var prepared =
        f.tx.execute(
            ignored -> {
              try {
                return IsolatedPublicationOwnerSetup.selectSourceBackedDraftWithWorld(
                    f.dsl, f.target, 1L, "concurrent selected template association");
              } catch (Exception e) {
                throw new IllegalStateException("Unable to select source-backed publication", e);
              }
            });
    var authoredSelection = Objects.requireNonNull(prepared).selection().selection();
    var selection =
        AuthoredDraftPublishSelectionBinding.fromStored(
            authoredSelection.canonicalJson(), authoredSelection.digest());
    var operation = IsolatedPublicationOperationFixtures.forSelection(selection, prepared.world());
    var firstRead = IsolatedPublicationOwnerSetup.syntheticWorldSourceRead(operation);
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
            .readExact(operation, capture.templateConfig());
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
          coordinator.claim(binding);
          coordinator.claimApplicationSlot(binding);
          coordinator.markOwnerInProgress(
              binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
          var sources = new GameDesignSourceRepository(f.dsl);
          var applied = sources.apply(binding);
          coordinator.advanceSourceVisibilityFence(
              binding,
              new DraftCommitCoordinatorRepository.CoordinatorProof(
                  binding, List.of(applied.ownerOutcome())));
          sources.captureSynchronized(binding);
          coordinator.releaseApplicationSlot(binding);
        });
  }

  private static TemplateConfigSource.Config config(Fixture f, String inputs) {
    return new TemplateConfigSource.Config(
        "{\"schemaVersion\":1,\"baseVersionId\":\""
            + f.target.canonicalVersionId()
            + "\",\"world\":{\"regions\":[],\"rooms\":[]},\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":"
            + inputs
            + "},\"automation\":{\"scripts\":[],\"scriptPatch\":{\"presence\":\"ABSENT\"}},\"supportedSettings\":[]}");
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
        f.target, UUID.randomUUID(), UUID.randomUUID(), "base-commit-0", revisions, units);
  }

  private Fixture fixture() {
    String schema = "template_config_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    data.setSchema(schema);
    Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
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
    return new Fixture(dsl, tx, target);
  }

  private record Fixture(
      DSLContext dsl, TransactionTemplate tx, DraftCommitBinding.TargetProof target) {}
}
