package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.impl.PublishedReleaseBundleServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Actual Game Design source, operation, and bundle SQL; Account and World inputs are ISOLATED. */
@Testcontainers(disabledWithoutDocker = true)
class SelectedCommandSourceBundlePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String COMMAND_KIND = "COMMAND_DEFINITION";

  @Test
  void bundleUsesCompleteSelectedCaptureAndRetryKeepsItsOriginalBytesAndIdentity()
      throws Exception {
    Fixture fixture = fixture();
    String inheritedDefinition = commandDefinition("look", "l");
    String lastCommitDefinition = commandDefinition("inventory", "i");
    DraftCommitBinding first = mixedSourceCommit(fixture.target(), inheritedDefinition);
    applyAndSynchronize(fixture, first);
    DraftCommitBinding lastCommandChange =
        commandSourceCommit(
            fixture.target(), first.commitId().toString(), "1", lastCommitDefinition);
    applyAndSynchronize(fixture, lastCommandChange);

    GameDesignPublicationOperation operation =
        fixture.txChecked(
            () ->
                IsolatedPublicationOwnerSetup.retainSourceBacked(
                    fixture.dsl(), fixture.target(), 1));
    var selectedCapture =
        fixture.tx(
            () ->
                new GameDesignPublicationOperationRepository(fixture.dsl())
                    .reserveSourceBacked(operation));
    var retainedCapture = fixture.sources().readCapture(operation).orElseThrow();
    assertThat(retainedCapture.command().canonicalBytes())
        .containsExactly(selectedCapture.command().canonicalBytes());
    assertThat(retainedCapture.policy().canonicalBytes())
        .containsExactly(selectedCapture.policy().canonicalBytes());
    List<String> capturedDefinitions =
        selectedCapture.command().snapshot().definitions().stream()
            .map(CommandSource.Definition::definitionJson)
            .toList();
    assertThat(capturedDefinitions)
        .containsExactlyInAnyOrder(inheritedDefinition, lastCommitDefinition);
    assertThat(CommandSource.mutations(operation.account().input().selection().selectedCommit()))
        .isEmpty();
    assertThat(selectedCapture.policy().snapshot().policies()).hasSize(1);

    var mutableRevisionRepository = mock(RevisionRepository.class);
    Revision mutableFirst = new Revision();
    mutableFirst.setData(commandDefinition("mutable", "m"));
    Revision mutableRetry = new Revision();
    mutableRetry.setData(commandDefinition("changed", "c"));
    when(mutableRevisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            fixture.target().gameDesignVersionTenantKey(),
            fixture.target().gameDesignVersionRowId(),
            COMMAND_KIND))
        .thenReturn(List.of(mutableFirst), List.of(mutableRetry));
    var bundles = new PublishedReleaseBundleRepository(fixture.dsl());
    var service =
        new PublishedReleaseBundleServiceImpl(
            bundles, mutableRevisionRepository, fixture.versions(), new ObjectMapper());
    var version = versionDto(fixture);
    var evidence = operation.world();
    var manifest = emptyManifest();
    var participants =
        PublishedWorldSelectorFixtures.participants(
            fixture.target().gameDesignVersionRowId(), evidence);

    fixture
        .write()
        .executeWithoutResult(
            status -> {
              service.createFullVersionBundle(
                  version,
                  operation.workflowId(),
                  manifest,
                  "ISOLATED-generation-revision",
                  participants,
                  evidence);
              status.setRollbackOnly();
            });
    assertThat(bundleCount(fixture)).isZero();

    var firstBundle =
        fixture.tx(
            () ->
                service.createFullVersionBundle(
                    version,
                    operation.workflowId(),
                    manifest,
                    "ISOLATED-generation-revision",
                    participants,
                    evidence));
    assertThat(firstBundle.commandDefinitions()).containsExactlyElementsOf(capturedDefinitions);
    assertThat(firstBundle.id()).isNotNull();
    assertThat(firstBundle.publishedReleaseBundleRef()).isNotBlank();

    when(mutableRevisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            fixture.target().gameDesignVersionTenantKey(),
            fixture.target().gameDesignVersionRowId(),
            COMMAND_KIND))
        .thenReturn(List.of(mutableRetry));
    var retry =
        fixture.tx(
            () ->
                service.createFullVersionBundle(
                    version,
                    operation.workflowId(),
                    manifest,
                    "ISOLATED-generation-revision",
                    participants,
                    evidence));
    assertThat(retry.id()).isEqualTo(firstBundle.id());
    assertThat(retry.publishedReleaseBundleRef())
        .isEqualTo(firstBundle.publishedReleaseBundleRef());
    assertThat(retry.commandDefinitions()).containsExactlyElementsOf(capturedDefinitions);
    assertThat(bundleCount(fixture)).isEqualTo(1);
    verifyNoInteractions(mutableRevisionRepository);
  }

  @Test
  void missingCaptureAndOperationSubstitutionDoNotWriteBundle() throws Exception {
    Fixture fixture = fixture();
    DraftCommitBinding authored =
        mixedSourceCommit(fixture.target(), commandDefinition("look", "l"));
    applyAndSynchronize(fixture, authored);
    GameDesignPublicationOperation operation =
        fixture.txChecked(
            () ->
                IsolatedPublicationOwnerSetup.retainSourceBacked(
                    fixture.dsl(), fixture.target(), 1));
    var bundles = new PublishedReleaseBundleRepository(fixture.dsl());
    var mutableRevisionRepository = mock(RevisionRepository.class);
    var service =
        new PublishedReleaseBundleServiceImpl(
            bundles, mutableRevisionRepository, fixture.versions(), new ObjectMapper());
    var version = versionDto(fixture);
    var evidence = operation.world();
    var participants =
        PublishedWorldSelectorFixtures.participants(
            fixture.target().gameDesignVersionRowId(), evidence);

    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        service.createFullVersionBundle(
                            version,
                            operation.workflowId(),
                            emptyManifest(),
                            "ISOLATED-generation-revision",
                            participants,
                            evidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELECTED_SOURCE_CAPTURE_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        bundles.requireSelectedCommandDefinitions(
                            "ISOLATED-substituted-tenant",
                            fixture.target().gameDesignVersionRowId(),
                            operation.workflowId(),
                            evidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELECTED_PUBLICATION_OPERATION_BINDING_CONFLICT");
    fixture.tx(
        () ->
            new GameDesignPublicationOperationRepository(fixture.dsl())
                .reserveSourceBacked(operation));
    WorldPublishedStartLocationEvidence substitutedEvidence =
        withDifferentPublicationFence(evidence);
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        service.createFullVersionBundle(
                            version,
                            operation.workflowId(),
                            emptyManifest(),
                            "ISOLATED-generation-revision",
                            participants,
                            substitutedEvidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELECTED_PUBLICATION_OPERATION_BINDING_CONFLICT");
    assertThat(bundleCount(fixture)).isZero();
    verifyNoInteractions(mutableRevisionRepository);
  }

  private static WorldPublishedStartLocationEvidence withDifferentPublicationFence(
      WorldPublishedStartLocationEvidence evidence) {
    var original = evidence.request();
    var substituted =
        new WorldPublishedStartLocationEvidence.Request(
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.canonicalVersionId(),
            original.intakeRequestId(),
            UUID.randomUUID(),
            original.publicationRequestId(),
            original.requestDigest(),
            original.versionStateEpoch(),
            original.publishWorkflowId(),
            original.appliedCommitId(),
            original.contentDigest(),
            original.digestSchemaVersion(),
            original.worldAffectedTuples());
    return new WorldPublishedStartLocationEvidence(
        substituted,
        evidence.selectorReceiptBytes(),
        evidence.originalAccountBindingBytes(),
        evidence.appliedResultBytes());
  }

  private static Fixture fixture() {
    String schema = "gd_selected_bundle_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .table("flyway_schema_history_game_design_service")
        .target("54")
        .load()
        .migrate();

    var transactions = new DataSourceTransactionManager(dataSource);
    var write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var configuration = new DefaultConfiguration();
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(
        new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
    configuration.set(new SpringTransactionProvider(transactions));
    var dsl = DSL.using(configuration);
    var properties = new PostgresProperties();
    properties.setSchema(schema);
    var versions = new VersionRepository(dsl, properties);
    var sources = new GameDesignSourceRepository(dsl);
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    TargetProof target =
        write.execute(
            status -> {
              var game = new Game();
              game.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              game.setName("ISOLATED selected source bundle");
              game = new GameRepository(dsl).save(game);
              var draft = new Version();
              draft.setTenantId(game.getTenantId());
              draft.setVersionNumber(1);
              draft.setVersionState(
                  net.firedevops.firemud.gamedesign.model.VersionLifecycleState.DRAFT);
              draft.setVersionStateEpoch(1L);
              Version saved = versions.save(draft);
              return new TargetProof(
                  saved.getCanonicalTenantId(),
                  saved.getCanonicalVersionId(),
                  saved.getId(),
                  saved.getTenantId(),
                  saved.getIdentitySourceGameRowId(),
                  saved.getIdentitySourceGameTenantKey(),
                  saved.getIdentitySourceProvenanceKind());
            });
    if (sources.readGenesis(target).isEmpty()) {
      throw new IllegalStateException(
          "Actual full Draft Version creation did not enroll source genesis");
    }
    return new Fixture(dsl, write, target, versions, sources, coordinator);
  }

  private static DraftCommitBinding mixedSourceCommit(TargetProof target, String definition) {
    String policy =
        "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"authored-main\",\"policy\":"
            + RealmEntryPolicy.parse(
                    "{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
                        + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
                        + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                        + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
                    new ObjectMapper())
                .canonicalJson()
            + "}";
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "ISOLATED-explicit-first-base",
        List.of(revision("0", CommandSource.upsertPayload(definition)), revision("1", policy)),
        List.of(commandScope(target, "0"), policyScope(target, "0")));
  }

  private static DraftCommitBinding commandSourceCommit(
      TargetProof target, String baseCommitId, String epoch, String definition) {
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        baseCommitId,
        List.of(revision("0", CommandSource.upsertPayload(definition))),
        List.of(commandScope(target, epoch)));
  }

  private static void applyAndSynchronize(Fixture fixture, DraftCommitBinding binding) {
    fixture.tx(
        () -> {
          fixture.coordinator().claim(binding);
          fixture.coordinator().claimApplicationSlot(binding);
          fixture.coordinator().markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
          return null;
        });
    GameDesignSourceRepository.Application applied =
        fixture.tx(() -> fixture.sources().apply(binding));
    fixture.tx(
        () -> {
          fixture
              .coordinator()
              .advanceSourceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      binding, List.of(applied.ownerOutcome())));
          fixture.coordinator().releaseApplicationSlot(binding);
          return null;
        });
  }

  private static RevisionPayload revision(String order, String payload) {
    return new RevisionPayload(order, UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, payload);
  }

  private static AffectedUnit commandScope(TargetProof target, String epoch) {
    return new AffectedUnit(
        Owner.GAME_DESIGN_CONTROL_PLANE,
        CommandSource.SCOPE,
        target.canonicalVersionId().toString(),
        CommandSource.SCOPE,
        CommandSource.SCOPE_ID,
        epoch);
  }

  private static AffectedUnit policyScope(TargetProof target, String epoch) {
    return new AffectedUnit(
        Owner.GAME_DESIGN_CONTROL_PLANE,
        RealmPolicySource.SCOPE,
        target.canonicalVersionId().toString(),
        RealmPolicySource.SCOPE,
        "effective",
        epoch);
  }

  private static String commandDefinition(String id, String alias) {
    return "{\"schemaVersion\":1,\"commandId\":\""
        + id
        + "\",\"semanticOwner\":\"WORLD\",\"executionDiscipline\":\"DURABLE_GAMEPLAY\","
        + "\"stageRequirement\":\"GAMEPLAY\",\"promptPolicy\":\"NEVER\",\"actionCategory\":\"GAMEPLAY\","
        + "\"historyRecordable\":true,\"aliases\":[\""
        + alias
        + "\"],\"actionTags\":[\"WORLD_BROWSE\"],\"effects\":[],\"legacyExtension\":{\"kept\":true}}";
  }

  private static net.firedevops.firemud.gamedesign.dto.VersionDto versionDto(Fixture fixture) {
    Version version =
        fixture
            .versions()
            .findByTenantIdAndId(
                fixture.target().gameDesignVersionTenantKey(),
                fixture.target().gameDesignVersionRowId())
            .orElseThrow();
    return new net.firedevops.firemud.gamedesign.dto.VersionDto(
        version.getId(),
        version.getTenantId(),
        version.getVersionNumber(),
        version.getVersionState(),
        version.getVersionStateEpoch(),
        version.getScriptPatchVersion(),
        version.getBaseVersionId(),
        version.isScriptOnly(),
        version.getNotes(),
        version.getCreatedAt(),
        version.getUpdatedAt());
  }

  private static ExportedAssetManifest emptyManifest() {
    return new ExportedAssetManifest("sha256:" + "a".repeat(64), 1, List.of(), List.of());
  }

  private static long bundleCount(Fixture fixture) {
    return java.util.Objects.requireNonNull(
            fixture.dsl().fetchOne("SELECT count(*) FROM published_release_bundle"),
            "bundle count query must return its aggregate row")
        .get(0, Long.class);
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate write,
      TargetProof target,
      VersionRepository versions,
      GameDesignSourceRepository sources,
      DraftCommitCoordinatorRepository coordinator) {
    <T> T tx(Supplier<T> work) {
      return write.execute(status -> work.get());
    }

    <T> T txChecked(CheckedSupplier<T> work) {
      return write.execute(
          status -> {
            try {
              return work.get();
            } catch (RuntimeException | Error failure) {
              throw failure;
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
          });
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
      T get() throws Exception;
    }
  }
}
