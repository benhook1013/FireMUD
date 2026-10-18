package integration.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignReviewedBaseRepository;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSource;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.gamedesign.publication.TemplateReferenceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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

/** Focused proof for fresh phase authority, exact current refs, and full-inventory denial. */
@Testcontainers(disabledWithoutDocker = true)
class TemplateReferencePhasePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void freshOwnerCreationStartsBackfillingAndEmptyInventoryCannotAdvance() {
    Fixture fixture = fixture();
    TemplateReferenceRepository references = new TemplateReferenceRepository(fixture.dsl());

    var phase = references.readPhase(fixture.target().canonicalTenantId()).orElseThrow();
    assertThat(phase.phase()).isEqualTo(TemplateReferenceRepository.Phase.BACKFILLING);
    assertThat(phase.phaseEpoch()).isEqualTo(1L);
    assertThat(phase.creationOperationId()).isEqualTo(fixture.creation().operationId());
    assertThat(phase.creationRequestId()).isEqualTo(fixture.creation().creationRequestId());
    assertThat(phase.sourceGameRowId()).isEqualTo(fixture.creation().sourceGameRowId());
    assertThat(phase.sourceGameTenantKey()).isEqualTo(fixture.creation().sourceGameTenantKey());

    assertThatThrownBy(
            () ->
                fixture
                    .tx()
                    .execute(
                        ignored ->
                            references.validateAndEnforce(fixture.target().canonicalTenantId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TEMPLATE_REFERENCE_INVENTORY_EMPTY");
    assertThat(references.readPhase(fixture.target().canonicalTenantId()).orElseThrow())
        .isEqualTo(phase);
    createTemplate(fixture, "Deleted", true);
    String deletedTemplateId = templateId(fixture, "Deleted");
    DraftCommitBinding deleted =
        applyTemplateSourceMutation(
            fixture, TemplateConfigSource.deletePayload(deletedTemplateId), "1");
    assertThat(sourceSnapshot(fixture, deleted).entries()).isEmpty();
    assertThatThrownBy(
            () ->
                fixture
                    .tx()
                    .execute(
                        ignored ->
                            new TemplateReferenceRepository(fixture.dsl())
                                .validateAndEnforce(fixture.target().canonicalTenantId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TEMPLATE_REFERENCE_INVENTORY_EMPTY");
    assertThat(
            new TemplateReferenceRepository(fixture.dsl())
                .readPhase(fixture.target().canonicalTenantId())
                .orElseThrow()
                .phase())
        .isEqualTo(phase.phase());
  }

  @Test
  void latestDeleteAfterDecimalEpochBoundaryDoesNotBlockActiveTemplate() {
    Fixture fixture = fixture();
    DraftCommitBinding active = createTemplate(fixture, "Active", true);
    applyTemplateSourceMutation(
        fixture, TemplateConfigSource.createPayload("Deleted", templateConfig(fixture)), "1");
    String deletedTemplateId = templateId(fixture, "Deleted");
    for (int expectedEpoch = 2; expectedEpoch <= 9; expectedEpoch++) {
      applyTemplateSourceMutation(
          fixture,
          TemplateConfigSource.upsertPayload(deletedTemplateId, templateConfig(fixture)),
          Integer.toString(expectedEpoch));
    }
    DraftCommitBinding deleted =
        applyTemplateSourceMutation(
            fixture, TemplateConfigSource.deletePayload(deletedTemplateId), "10");

    var currentSource = sourceSnapshot(fixture, deleted);
    assertThat(currentSource.entries()).hasSize(1);
    assertThat(currentSource.entries().getFirst().templateId())
        .isEqualTo(templateId(fixture, "Active"));
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_templates WHERE tenant_id = ?",
                    fixture.creation().sourceGameTenantKey())
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_design_template_config_source_qualification "
                        + "WHERE canonical_tenant_id = ? AND template_id = ?",
                    fixture.target().canonicalTenantId(),
                    Long.parseLong(deletedTemplateId))
                .get(0, Long.class))
        .isEqualTo(1L);

    var enforced =
        fixture
            .tx()
            .execute(
                ignored ->
                    new TemplateReferenceRepository(fixture.dsl())
                        .validateAndEnforce(fixture.target().canonicalTenantId()));
    assertThat(enforced).isNotNull();
    assertThat(enforced.phase()).isEqualTo(TemplateReferenceRepository.Phase.ENFORCED);
    assertThat(enforced.inventoryTemplateCount()).isEqualTo(1L);
    assertThat(currentSource.entries().getFirst().sourceBinding().commitId())
        .isEqualTo(active.commitId());
  }

  @Test
  void upsertCannotResurrectDeletedTemplateAndFailureLeavesCurrentSourceUsable() {
    Fixture fixture = fixture();
    DraftCommitBinding active = createTemplate(fixture, "Active", true);
    applyTemplateSourceMutation(
        fixture, TemplateConfigSource.createPayload("Reactivated", templateConfig(fixture)), "1");
    String reactivatedTemplateId = templateId(fixture, "Reactivated");
    DraftCommitBinding deleted =
        applyTemplateSourceMutation(
            fixture, TemplateConfigSource.deletePayload(reactivatedTemplateId), "2");
    var currentSource = sourceSnapshot(fixture, deleted);
    var phase =
        new TemplateReferenceRepository(fixture.dsl())
            .readPhase(fixture.target().canonicalTenantId())
            .orElseThrow();
    assertThat(currentSource.entries()).hasSize(1);
    assertThat(currentSource.entries().getFirst().templateId())
        .isEqualTo(templateId(fixture, "Active"));
    assertThat(currentSource.entries().getFirst().sourceBinding().commitId())
        .isEqualTo(active.commitId());

    assertThatThrownBy(
            () ->
                applyTemplateSourceMutation(
                    fixture,
                    TemplateConfigSource.upsertPayload(
                        reactivatedTemplateId, templateConfig(fixture)),
                    "3"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Template source identity is not an existing qualified entry");
    assertThat(sourceSnapshot(fixture, deleted)).isEqualTo(currentSource);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_templates WHERE tenant_id = ?",
                    fixture.creation().sourceGameTenantKey())
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_design_template_config_source_qualification "
                        + "WHERE canonical_tenant_id = ? AND template_id = ?",
                    fixture.target().canonicalTenantId(),
                    Long.parseLong(reactivatedTemplateId))
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            new TemplateReferenceRepository(fixture.dsl())
                .readPhase(fixture.target().canonicalTenantId())
                .orElseThrow())
        .isEqualTo(phase);

    String activeTemplateId = templateId(fixture, "Active");
    DraftCommitBinding activeUpsert =
        applyTemplateSourceMutation(
            fixture,
            TemplateConfigSource.upsertPayload(activeTemplateId, templateConfig(fixture)),
            "3");
    var currentAfterActiveUpsert = sourceSnapshot(fixture, activeUpsert);
    assertThat(currentAfterActiveUpsert.entries()).hasSize(1);
    assertThat(currentAfterActiveUpsert.entries().getFirst().templateId())
        .isEqualTo(activeTemplateId);
    assertThat(currentAfterActiveUpsert.entries().getFirst().sourceBinding().commitId())
        .isEqualTo(activeUpsert.commitId());
    assertThat(currentAfterActiveUpsert.entries().getFirst().revisionId())
        .isEqualTo(activeUpsert.revisions().getFirst().revisionId());
    assertThat(
            currentAfterActiveUpsert.entries().stream()
                .map(TemplateConfigSource.Entry::templateId)
                .toList())
        .doesNotContain(reactivatedTemplateId);

    var enforced =
        fixture
            .tx()
            .execute(
                ignored ->
                    new TemplateReferenceRepository(fixture.dsl())
                        .validateAndEnforce(fixture.target().canonicalTenantId()));
    assertThat(enforced).isNotNull();
    assertThat(enforced.phase()).isEqualTo(TemplateReferenceRepository.Phase.ENFORCED);
    assertThat(enforced.inventoryTemplateCount()).isEqualTo(1L);
  }

  @Test
  void qualifiedCurrentSourceCanEnforcePhaseAndRawCreateIsThenDenied() {
    Fixture fixture = fixture();
    DraftCommitBinding binding = createTemplate(fixture, "Starter");
    var currentReference =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT canonical_version_id, source_commit_id, source_revision_id "
                    + "FROM game_template_version_ref WHERE canonical_tenant_id = ?",
                fixture.target().canonicalTenantId());
    assertThat(currentReference).isNotNull();
    assertThat(currentReference.get("canonical_version_id", UUID.class))
        .isEqualTo(fixture.target().canonicalVersionId());
    assertThat(currentReference.get("source_commit_id", UUID.class)).isEqualTo(binding.commitId());
    assertThat(currentReference.get("source_revision_id", UUID.class))
        .isEqualTo(binding.revisions().getFirst().revisionId());

    TemplateReferenceRepository references = new TemplateReferenceRepository(fixture.dsl());
    var enforced =
        fixture
            .tx()
            .execute(
                ignored -> references.validateAndEnforce(fixture.target().canonicalTenantId()));
    assertThat(enforced).isNotNull();
    assertThat(enforced.phase()).isEqualTo(TemplateReferenceRepository.Phase.ENFORCED);
    assertThat(enforced.phaseEpoch()).isEqualTo(3L);
    assertThat(enforced.inventoryTemplateCount()).isEqualTo(1L);
    assertThat(enforced.inventoryDigest()).startsWith("sha256:");

    assertThatThrownBy(
            () ->
                fixture
                    .tx()
                    .executeWithoutResult(
                        ignored ->
                            fixture
                                .dsl()
                                .execute(
                                    "INSERT INTO game_templates (tenant_id, name, config) "
                                        + "VALUES (?, 'Raw bypass', '{}'::JSONB)",
                                    fixture.creation().sourceGameTenantKey())))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining(
            "validated template create requires exact active owner source binding");
    assertThat(references.readPhase(fixture.target().canonicalTenantId()).orElseThrow())
        .isEqualTo(enforced);
  }

  @Test
  void currentSourceSynchronizationStaysBackfillingUntilExplicitFullInventoryValidation() {
    Fixture fixture = fixture();
    DraftCommitBinding binding = createTemplate(fixture, "Starter", true);
    var references = new TemplateReferenceRepository(fixture.dsl());
    var before = references.readPhase(fixture.target().canonicalTenantId()).orElseThrow();
    assertThat(before.phase()).isEqualTo(TemplateReferenceRepository.Phase.BACKFILLING);
    assertThat(before.phaseEpoch()).isEqualTo(1L);
    assertThat(before.creationOperationId()).isEqualTo(fixture.creation().operationId());
    assertThat(before.creationRequestId()).isEqualTo(fixture.creation().creationRequestId());
    assertThat(before.sourceGameRowId()).isEqualTo(fixture.creation().sourceGameRowId());
    assertThat(before.sourceGameTenantKey()).isEqualTo(fixture.creation().sourceGameTenantKey());

    var visibleSources =
        new GameDesignSourceRepository(fixture.dsl())
            .readSynchronized(fixture.target(), binding.commitId())
            .orElseThrow();
    assertThat(visibleSources.command().binding()).isEqualTo(binding);
    assertThat(visibleSources.policy().binding()).isEqualTo(binding);
    var visibleTemplateConfig = visibleSources.templateConfig().orElseThrow();
    assertThat(visibleTemplateConfig.binding()).isEqualTo(binding);
    assertThat(visibleTemplateConfig.entries()).hasSize(1);
    assertThat(visibleTemplateConfig.entries().getFirst().sourceBinding()).isEqualTo(binding);
    assertThat(visibleTemplateConfig.entries().getFirst().revisionId())
        .isEqualTo(binding.revisions().getFirst().revisionId());

    var currentReference =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT template_id, canonical_version_id, source_commit_id, source_revision_id "
                    + "FROM game_template_version_ref WHERE canonical_tenant_id = ?",
                fixture.target().canonicalTenantId());
    assertThat(currentReference).isNotNull();
    assertThat(currentReference.get("canonical_version_id", UUID.class))
        .isEqualTo(fixture.target().canonicalVersionId());
    assertThat(currentReference.get("source_commit_id", UUID.class)).isEqualTo(binding.commitId());
    assertThat(currentReference.get("source_revision_id", UUID.class))
        .isEqualTo(binding.revisions().getFirst().revisionId());
    assertThat(
            references
                .readExactBaseReference(
                    fixture.target().canonicalTenantId(),
                    Objects.requireNonNull(currentReference.get("template_id", Long.class)))
                .orElseThrow()
                .sourceCommitId())
        .isEqualTo(binding.commitId());
    assertThat(references.readPhase(fixture.target().canonicalTenantId()).orElseThrow())
        .isEqualTo(before);

    var enforced =
        fixture
            .tx()
            .execute(
                ignored -> references.validateAndEnforce(fixture.target().canonicalTenantId()));
    assertThat(enforced).isNotNull();
    assertThat(enforced.phase()).isEqualTo(TemplateReferenceRepository.Phase.ENFORCED);
    assertThat(enforced.phaseEpoch()).isEqualTo(3L);
    assertThat(enforced.inventoryTemplateCount()).isEqualTo(1L);
    assertThat(enforced.inventoryDigest()).isNotBlank().startsWith("sha256:");
    assertThat(enforced.creationOperationId()).isEqualTo(before.creationOperationId());
    assertThat(enforced.creationRequestId()).isEqualTo(before.creationRequestId());
    assertThat(enforced.sourceGameRowId()).isEqualTo(before.sourceGameRowId());
    assertThat(enforced.sourceGameTenantKey()).isEqualTo(before.sourceGameTenantKey());
    assertThat(enforced.creationEvidenceDigest()).isEqualTo(before.creationEvidenceDigest());
    assertThat(enforced.provenanceKind()).isEqualTo(before.provenanceKind());
  }

  @Test
  void unqualifiedPhysicalTemplatePreventsTenantPromotion() {
    Fixture fixture = fixture();
    createTemplate(fixture, "Qualified");
    fixture
        .tx()
        .executeWithoutResult(
            ignored ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO game_templates (tenant_id, name, config) "
                            + "VALUES (?, 'Unqualified', '{}'::JSONB)",
                        fixture.creation().sourceGameTenantKey()));

    assertThatThrownBy(
            () ->
                fixture
                    .tx()
                    .execute(
                        ignored ->
                            new TemplateReferenceRepository(fixture.dsl())
                                .validateAndEnforce(fixture.target().canonicalTenantId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TEMPLATE_REFERENCE_INVENTORY_UNQUALIFIED");
    assertThat(
            new TemplateReferenceRepository(fixture.dsl())
                .readPhase(fixture.target().canonicalTenantId())
                .orElseThrow()
                .phase())
        .isEqualTo(TemplateReferenceRepository.Phase.BACKFILLING);
  }

  private DraftCommitBinding createTemplate(Fixture fixture, String name) {
    return createTemplate(fixture, name, false);
  }

  private DraftCommitBinding createTemplate(
      Fixture fixture, String name, boolean sourceAwareVisibility) {
    GameDesignSourceRepository sources = new GameDesignSourceRepository(fixture.dsl());
    String baseReference =
        "genesis:" + sources.readGenesis(fixture.target()).orElseThrow().policy().receiptId();
    return applyTemplateSourceMutation(
        fixture,
        TemplateConfigSource.createPayload(name, templateConfig(fixture)),
        "0",
        baseReference,
        sourceAwareVisibility);
  }

  private DraftCommitBinding applyTemplateSourceMutation(
      Fixture fixture, String payload, String expectedEpoch) {
    UUID visibleCommitId =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT visible_commit_id FROM game_design_realm_policy_source "
                        + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                    fixture.target().canonicalTenantId(),
                    fixture.target().canonicalVersionId())
                .get("visible_commit_id", UUID.class));
    return applyTemplateSourceMutation(
        fixture, payload, expectedEpoch, visibleCommitId.toString(), true);
  }

  private DraftCommitBinding applyTemplateSourceMutation(
      Fixture fixture,
      String payload,
      String expectedEpoch,
      String baseReference,
      boolean sourceAwareVisibility) {
    UUID revisionId = UUID.randomUUID();
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            fixture.target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            baseReference,
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0", revisionId, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    TemplateConfigSource.SCOPE,
                    fixture.target().canonicalVersionId().toString(),
                    TemplateConfigSource.SCOPE,
                    TemplateConfigSource.SCOPE_ID,
                    expectedEpoch)));
    var sources = new GameDesignSourceRepository(fixture.dsl());
    fixture
        .tx()
        .executeWithoutResult(
            ignored -> {
              var coordinator = new DraftCommitCoordinatorRepository(fixture.dsl());
              var reviewed = new GameDesignReviewedBaseRepository(fixture.dsl());
              sources.claimReviewed(
                  binding,
                  reviewed.resolve(
                      fixture.target(), DraftBaseReference.parse(binding.baseCommitId())));
              coordinator.claimApplicationSlot(binding);
              coordinator.markOwnerInProgress(
                  binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
              var applied = sources.apply(binding);
              if (sourceAwareVisibility) {
                net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup
                    .advanceIsolatedSourceVisibility(
                        fixture.dsl(), binding, List.of(applied.ownerOutcome()));
              } else {
                // These primitive-level cases deliberately leave promotion to the explicit
                // validator.
                net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup
                    .advanceIsolatedVisibility(
                        fixture.dsl(), binding, List.of(applied.ownerOutcome()));
                sources.captureSynchronized(binding);
              }
              coordinator.releaseApplicationSlot(binding);
            });
    return binding;
  }

  private TemplateConfigSourceSnapshot sourceSnapshot(Fixture fixture, DraftCommitBinding binding) {
    return new GameDesignSourceRepository(fixture.dsl())
        .readSynchronized(fixture.target(), binding.commitId())
        .orElseThrow()
        .templateConfig()
        .orElseThrow();
  }

  private TemplateConfigSource.Config templateConfig(Fixture fixture) {
    return new TemplateConfigSource.Config(
        "{\"schemaVersion\":1,\"baseVersionId\":\""
            + fixture.target().canonicalVersionId()
            + "\",\"world\":{\"regions\":[],\"rooms\":[]},"
            + "\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":[]},"
            + "\"automation\":{\"scripts\":[],\"scriptPatch\":{\"presence\":\"ABSENT\"}},"
            + "\"supportedSettings\":[]}");
  }

  private String templateId(Fixture fixture, String name) {
    Long id =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT id FROM game_templates WHERE tenant_id = ? AND name = ?",
                fixture.creation().sourceGameTenantKey(),
                name)
            .get("id", Long.class);
    return Objects.requireNonNull(id).toString();
  }

  private Fixture fixture() {
    String schema = "template_reference_phase_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

    UUID requestId = UUID.randomUUID();
    String tenantKey = UUID.randomUUID().toString();
    FreshTenantCreationEvidence creation =
        Objects.requireNonNull(
            tx.execute(
                ignored ->
                    new GameTenantCreationRepository(dsl, new GameRepository(dsl))
                        .createCandidate(
                            "template-phase-test",
                            requestId,
                            tenantKey,
                            "Template reference phase fixture",
                            null)));
    Version version = new Version();
    version.setTenantId(tenantKey);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    Version savedVersion =
        Objects.requireNonNull(tx.execute(ignored -> new VersionRepository(dsl).save(version)));
    DraftCommitBinding.TargetProof target =
        new DraftCommitBinding.TargetProof(
            savedVersion.getCanonicalTenantId(),
            savedVersion.getCanonicalVersionId(),
            savedVersion.getId(),
            savedVersion.getTenantId(),
            savedVersion.getIdentitySourceGameRowId(),
            savedVersion.getIdentitySourceGameTenantKey(),
            savedVersion.getIdentitySourceProvenanceKind());
    return new Fixture(dsl, tx, creation, target);
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate tx,
      FreshTenantCreationEvidence creation,
      DraftCommitBinding.TargetProof target) {}
}
