package integration.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSource;
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
  }

  @Test
  void qualifiedCurrentSourceCanEnforcePhaseAndRawCreateIsThenDenied() {
    Fixture fixture = fixture();
    DraftCommitBinding binding = createTemplate(fixture, "Starter");
    var currentReference =
        fixture
            .dsl()
            .fetchOne(
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
  void normalSourceSynchronizationAutomaticallyEnforcesFreshCompleteInventory() {
    Fixture fixture = fixture();
    DraftCommitBinding binding = createTemplate(fixture, "Automatic", true);
    var references = new TemplateReferenceRepository(fixture.dsl());
    var phase = references.readPhase(fixture.target().canonicalTenantId()).orElseThrow();
    assertThat(phase.phase()).isEqualTo(TemplateReferenceRepository.Phase.ENFORCED);
    assertThat(phase.phaseEpoch()).isEqualTo(3L);
    assertThat(phase.inventoryTemplateCount()).isEqualTo(1L);
    var configuredTemplateRow =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT id FROM game_templates WHERE tenant_id = ?",
                    fixture.creation().sourceGameTenantKey()),
            "Automatically synchronized template row is missing");
    Long configuredTemplateId =
        Objects.requireNonNull(
            configuredTemplateRow.get("id", Long.class),
            "Automatically synchronized template row has no id");
    assertThat(
            references
                .readExactBaseReference(fixture.target().canonicalTenantId(), configuredTemplateId)
                .orElseThrow()
                .sourceCommitId())
        .isEqualTo(binding.commitId());
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

  private DraftCommitBinding createTemplate(Fixture fixture, String name, boolean automaticPhase) {
    var config =
        new TemplateConfigSource.Config(
            "{\"schemaVersion\":1,\"baseVersionId\":\""
                + fixture.target().canonicalVersionId()
                + "\",\"world\":{\"regions\":[],\"rooms\":[]},"
                + "\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":[]},"
                + "\"automation\":{\"scripts\":[],\"scriptPatch\":{\"presence\":\"ABSENT\"}},"
                + "\"supportedSettings\":[]}");
    UUID revisionId = UUID.randomUUID();
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            fixture.target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base-commit-0",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    revisionId,
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    TemplateConfigSource.createPayload(name, config))),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    TemplateConfigSource.SCOPE,
                    fixture.target().canonicalVersionId().toString(),
                    TemplateConfigSource.SCOPE,
                    TemplateConfigSource.SCOPE_ID,
                    "0")));
    fixture
        .tx()
        .executeWithoutResult(
            ignored -> {
              var coordinator = new DraftCommitCoordinatorRepository(fixture.dsl());
              coordinator.claim(binding);
              coordinator.claimApplicationSlot(binding);
              coordinator.markOwnerInProgress(
                  binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
              GameDesignSourceRepository sources = new GameDesignSourceRepository(fixture.dsl());
              var applied = sources.apply(binding);
              if (automaticPhase) {
                net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup
                    .advanceSourceVisibility(
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
