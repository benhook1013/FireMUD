package integration.net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateLaunchConfigView;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.impl.LaunchDescriptorServiceImpl;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "firemud.grpc.workload-namespace=launch-descriptor-test",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class LaunchDescriptorServiceIntegrationTest {
  private static final String NAMESPACE = "launch-descriptor-test";
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final org.jooq.Table<?> LAUNCH_DESCRIPTOR =
      DSL.table(DSL.name("launch_descriptor"));
  private static final org.jooq.Field<Long> LAUNCH_ROW_ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> LAUNCH_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final org.jooq.Field<UUID> LAUNCH_CANONICAL_TENANT =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final org.jooq.Field<String> LAUNCH_REQUEST_ID =
      DSL.field(DSL.name("control_plane_request_id"), String.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private LaunchDescriptorService launchDescriptorService;
  @Autowired private LaunchDescriptorServiceImpl legacyLaunchDescriptorService;
  @Autowired private GameRepository gameRepository;
  @Autowired private GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  @Autowired private GameTemplateRepository gameTemplateRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Autowired private PublishedReleaseBundleService publishedReleaseBundleService;
  @Autowired private LaunchDescriptorRepository launchDescriptorRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DSLContext dsl;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void legacyNumericResolveFailsClosedWithoutPersistingDescriptor() {
    assertThatThrownBy(
            () ->
                legacyLaunchDescriptorService.resolveLaunchDescriptor(
                    "1", 9L, "integration-cp-unbound", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED: canonical authored-world source binding is"
                + " required to resolve a launch descriptor");

    assertThat(launchDescriptorRepository.findByPrivateRequest("1", "integration-cp-unbound"))
        .isEmpty();
  }

  @Test
  void resolvesPersistedSourceAndReplaysOriginalDescriptorAfterDefaultsAndVersionChange() {
    Fixture fixture = fixture("cp-persisted");

    ResolvedLaunchDescriptorDto original =
        launchDescriptorService.resolveLaunchDescriptor(fixture.request());
    String generationConfigRevision = fixture.bundle().getGenerationConfigRevision();
    assertThat(generationConfigRevision.length()).isGreaterThan(128);
    assertThat(original.generationConfigRevision()).isEqualTo(generationConfigRevision);
    assertThat(original.gameTemplateId()).isEqualTo(fixture.template().getId());
    assertThat(original.versionId()).isEqualTo(fixture.version().getId());
    assertThat(original.versionStateEpoch()).isEqualTo(1L);
    assertThat(original.canonicalTenantId()).isEqualTo(fixture.canonicalTenantId().toString());
    assertThat(original.publishedReleaseBundleRef())
        .isEqualTo(fixture.bundle().getPublishedReleaseBundleRef());
    assertThat(
            publishedReleaseBundleRepository
                .findByTenantIdAndVersionId(
                    fixture.privateSourceTenantKey(), fixture.version().getId())
                .orElseThrow()
                .getPublishedReleaseBundleRef())
        .isEqualTo(original.publishedReleaseBundleRef());
    assertThat(original.authoredWorldBinding()).isNotNull();
    assertThat(original.authoredWorldBinding().authoredWorldSourceOperationId())
        .isEqualTo(fixture.source().operationId());
    assertThat(original.authoredWorldBinding().authoredWorldSourceEvidenceDigest())
        .isEqualTo(fixture.source().evidenceDigest());
    assertThat(original.canonicalTenantId()).isNotEqualTo(fixture.privateSourceTenantKey());

    Version newDraft = new Version();
    newDraft.setTenantId(fixture.privateSourceTenantKey());
    newDraft.setVersionNumber(2);
    newDraft.setVersionState(VersionLifecycleState.DRAFT);
    newDraft.setVersionStateEpoch(1L);
    newDraft.setNotes("new mutable template default");
    newDraft.setUpdatedAt(LocalDateTime.now());
    newDraft = versionRepository.save(newDraft);
    fixture.template().setDefaultVersionId(newDraft.getId());
    gameTemplateRepository.save(fixture.template());
    fixture.version().setVersionState(VersionLifecycleState.RETIRED);
    fixture.version().setVersionStateEpoch(2L);
    versionRepository.save(fixture.version());

    ResolvedLaunchDescriptorDto retry =
        launchDescriptorService.resolveLaunchDescriptor(fixture.request());

    assertThat(retry).isEqualTo(original);
    assertThat(retry.versionId()).isEqualTo(fixture.version().getId());
    LaunchDescriptor persisted =
        launchDescriptorRepository
            .findBoundByRequest(
                NAMESPACE, fixture.canonicalTenantId(), fixture.controlPlaneRequestId())
            .orElseThrow();
    assertThat(persisted.getLaunchDescriptorId()).isEqualTo(original.launchDescriptorId());
    assertThat(persisted.getRequestDigest())
        .isEqualTo(original.authoredWorldBinding().requestDigest());
    assertThat(persisted.getResultDigest())
        .isEqualTo(original.authoredWorldBinding().resultDigest());
    assertThat(persisted.getPublishedReleaseBundleRef())
        .isEqualTo(original.publishedReleaseBundleRef());
    assertThat(persisted.getGenerationConfigRevision()).isEqualTo(generationConfigRevision);
    assertThat(
            dsl.fetchCount(
                LAUNCH_DESCRIPTOR,
                LAUNCH_NAMESPACE
                    .eq(NAMESPACE)
                    .and(LAUNCH_CANONICAL_TENANT.eq(fixture.canonicalTenantId()))
                    .and(LAUNCH_REQUEST_ID.eq(fixture.controlPlaneRequestId()))))
        .isEqualTo(1);

    ResolvedLaunchDescriptorDto readback =
        launchDescriptorService.getLaunchDescriptor(
            UUID.randomUUID(),
            fixture.canonicalTenantId(),
            fixture.source().worldSlug(),
            fixture.controlPlaneRequestId(),
            original.authoredWorldBinding().requestDigest(),
            original.authoredWorldBinding().resultDigest());
    assertThat(readback).isEqualTo(original);
  }

  @Test
  void concurrentExactRetriesPersistOneOriginalDescriptor() throws Exception {
    Fixture fixture = fixture("cp-concurrent");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ResolvedLaunchDescriptorDto> first =
          executor.submit(() -> resolveAfterLatch(fixture, ready, start));
      Future<ResolvedLaunchDescriptorDto> second =
          executor.submit(() -> resolveAfterLatch(fixture, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      ResolvedLaunchDescriptorDto firstResult = first.get(20, TimeUnit.SECONDS);
      ResolvedLaunchDescriptorDto secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat(secondResult).isEqualTo(firstResult);
      assertThat(
              dsl.fetchCount(
                  LAUNCH_DESCRIPTOR,
                  LAUNCH_NAMESPACE
                      .eq(NAMESPACE)
                      .and(LAUNCH_CANONICAL_TENANT.eq(fixture.canonicalTenantId()))
                      .and(LAUNCH_REQUEST_ID.eq(fixture.controlPlaneRequestId()))))
          .isEqualTo(1);
      LaunchDescriptor persisted =
          launchDescriptorRepository
              .findBoundByRequest(
                  NAMESPACE, fixture.canonicalTenantId(), fixture.controlPlaneRequestId())
              .orElseThrow();
      assertThat(persisted.getLaunchDescriptorId()).isEqualTo(firstResult.launchDescriptorId());
      assertThat(persisted.getResultDigest())
          .isEqualTo(firstResult.authoredWorldBinding().resultDigest());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void deterministicFailureIsFrozenAcrossDependencyChangeAndConcurrentExactRetries()
      throws Exception {
    Fixture fixture = fixture("cp-frozen-phase");
    fixture.template().setTemplateReferencePhase(TemplateReferencePhase.LEGACY);
    gameTemplateRepository.save(fixture.template());

    assertThatThrownBy(() -> launchDescriptorService.resolveLaunchDescriptor(fixture.request()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED: template reference phase is not enforced");

    LaunchDescriptor failed =
        launchDescriptorRepository
            .findBoundByRequest(
                NAMESPACE, fixture.canonicalTenantId(), fixture.controlPlaneRequestId())
            .orElseThrow();
    assertThat(failed.getOutcomeStatus()).isEqualTo(LaunchDescriptor.OUTCOME_FAILED);
    assertThat(failed.getFailureCode()).isEqualTo("TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED");
    assertThat(failed.getFailureMessage())
        .isEqualTo(
            "TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED: template reference phase is not enforced");
    assertThat(failed.getRequestDigest()).isEqualTo(fixture.request().requestDigest());
    assertThat(failed.getOriginalRequestJson()).isNotBlank();
    assertThat(failed.getSourceEvidenceJson()).isNotBlank();
    assertThat(failed.getLaunchDescriptorId()).isNull();
    assertThat(failed.getGameTemplateId()).isNull();
    assertThat(failed.getVersionId()).isNull();
    assertThat(failed.getReleaseBundleId()).isNull();
    assertThat(failed.getResultDigest()).isNull();
    assertFailedShapeRejected(failed, "cp-frozen-null-schema", true);
    assertFailedShapeRejected(failed, "cp-frozen-null-failure-code", false);
    assertThat(
            dsl.fetchCount(
                LAUNCH_DESCRIPTOR,
                LAUNCH_NAMESPACE
                    .eq(NAMESPACE)
                    .and(LAUNCH_CANONICAL_TENANT.eq(fixture.canonicalTenantId()))
                    .and(LAUNCH_REQUEST_ID.eq(fixture.controlPlaneRequestId()))))
        .isEqualTo(1);

    fixture.template().setTemplateReferencePhase(TemplateReferencePhase.ENFORCED);
    gameTemplateRepository.save(fixture.template());

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> first = executor.submit(() -> resolveFailureAfterLatch(fixture, ready, start));
      Future<String> second =
          executor.submit(() -> resolveFailureAfterLatch(fixture, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(failed.getFailureMessage());
      assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(failed.getFailureMessage());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    assertThatThrownBy(
            () ->
                launchDescriptorService.resolveLaunchDescriptor(
                    fixture.requestWithPatch("different-patch")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("control-plane request identity was reused with changed input");

    ResolvedLaunchDescriptorDto freshAttempt =
        launchDescriptorService.resolveLaunchDescriptor(fixture.request("cp-frozen-phase-fresh"));
    assertThat(freshAttempt.versionId()).isEqualTo(fixture.version().getId());
    assertThat(
            launchDescriptorRepository
                .findBoundByRequest(NAMESPACE, fixture.canonicalTenantId(), "cp-frozen-phase-fresh")
                .orElseThrow()
                .getOutcomeStatus())
        .isEqualTo(LaunchDescriptor.OUTCOME_SUCCESS);
  }

  @Test
  void corruptFailureReadbackRollsBackTheOutcomeInsert() {
    Fixture fixture = fixture("cp-corrupt-failure-readback");
    fixture.template().setTemplateReferencePhase(TemplateReferencePhase.LEGACY);
    gameTemplateRepository.save(fixture.template());
    LaunchDescriptorRepository corruptingRepository =
        new LaunchDescriptorRepository(dsl) {
          @Override
          public LaunchDescriptor insertImmutable(LaunchDescriptor descriptor) {
            LaunchDescriptor persisted = super.insertImmutable(descriptor);
            persisted.setFailureCode("UNRECOGNIZED_FAILURE");
            return persisted;
          }
        };
    LaunchDescriptorServiceImpl corruptingService =
        new LaunchDescriptorServiceImpl(
            gameTemplateRepository,
            corruptingRepository,
            versionRepository,
            Mockito.mock(PublishedReleaseBundleService.class),
            Mockito.mock(TemplateRemapSetService.class),
            authoredWorldSourceRepository,
            new ObjectMapper());
    ReflectionTestUtils.setField(corruptingService, "workloadNamespace", NAMESPACE);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> corruptingService.resolveLaunchDescriptor(fixture.request())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stored failure is not the exact immutable request outcome");

    assertThat(
            launchDescriptorRepository.findBoundByRequest(
                NAMESPACE, fixture.canonicalTenantId(), fixture.controlPlaneRequestId()))
        .isEmpty();
  }

  @Test
  void concurrentSuccessAndFailureCandidatesPersistOnlyTheFirstOutcome() throws Exception {
    Fixture fixture = fixture("cp-mixed-candidates");
    CountDownLatch lockArrivals = new CountDownLatch(2);
    CountDownLatch viewReached = new CountDownLatch(1);
    CountDownLatch continueResolution = new CountDownLatch(1);
    LaunchDescriptorRepository serializedRepository =
        new LaunchDescriptorRepository(dsl) {
          @Override
          public void lockBoundRequest(String targetNamespace, UUID tenantId, String requestId) {
            lockArrivals.countDown();
            super.lockBoundRequest(targetNamespace, tenantId, requestId);
          }
        };
    LaunchDescriptorServiceImpl failureCandidate =
        candidateForPhase(
            fixture,
            serializedRepository,
            TemplateReferencePhase.LEGACY,
            viewReached,
            continueResolution);
    LaunchDescriptorServiceImpl successCandidate =
        candidateForPhase(
            fixture,
            serializedRepository,
            TemplateReferencePhase.ENFORCED,
            viewReached,
            continueResolution);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> first =
          executor.submit(
              () -> resolveCandidateInOwnerTransaction(failureCandidate, fixture, ready, start));
      Future<String> second =
          executor.submit(
              () -> resolveCandidateInOwnerTransaction(successCandidate, fixture, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(viewReached.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(lockArrivals.await(10, TimeUnit.SECONDS)).isTrue();
      continueResolution.countDown();

      String firstResult = first.get(20, TimeUnit.SECONDS);
      String secondResult = second.get(20, TimeUnit.SECONDS);
      assertThat(secondResult).isEqualTo(firstResult);
      LaunchDescriptor stored =
          launchDescriptorRepository
              .findBoundByRequest(
                  NAMESPACE, fixture.canonicalTenantId(), fixture.controlPlaneRequestId())
              .orElseThrow();
      assertThat(stored.getOutcomeStatus())
          .isEqualTo(
              firstResult.startsWith("FAILED:")
                  ? LaunchDescriptor.OUTCOME_FAILED
                  : LaunchDescriptor.OUTCOME_SUCCESS);
      assertThat(
              dsl.fetchCount(
                  LAUNCH_DESCRIPTOR,
                  LAUNCH_NAMESPACE
                      .eq(NAMESPACE)
                      .and(LAUNCH_CANONICAL_TENANT.eq(fixture.canonicalTenantId()))
                      .and(LAUNCH_REQUEST_ID.eq(fixture.controlPlaneRequestId()))))
          .isEqualTo(1);
    } finally {
      continueResolution.countDown();
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void rolledBackDescriptorInsertLeavesNoCanonicalOrPrivateRequestClaim() {
    Fixture fixture = fixture("cp-rollback");
    AuthoredWorldLaunchDescriptorEvidence evidence = evidence(fixture, "ld-rollback-candidate");
    LaunchDescriptor candidate = entity(evidence, fixture);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> {
                      launchDescriptorRepository.insertImmutable(candidate);
                      throw new IllegalStateException("force owner transaction rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("force owner transaction rollback");

    assertThat(
            launchDescriptorRepository.findBoundByRequest(
                NAMESPACE, fixture.canonicalTenantId(), fixture.controlPlaneRequestId()))
        .isEmpty();
    assertThat(
            launchDescriptorRepository.findByPrivateRequest(
                fixture.privateSourceTenantKey(), fixture.controlPlaneRequestId()))
        .isEmpty();
    ResolvedLaunchDescriptorDto committed =
        launchDescriptorService.resolveLaunchDescriptor(fixture.request());
    assertThat(committed.authoredWorldBinding().requestDigest())
        .isEqualTo(evidence.requestDigest());
  }

  @Test
  void v35AndV36MigrationsPreserveRetainedBundleAndUnboundDescriptorWithoutBackfill() {
    String schema = "game_design_launch_history_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = isolatedDataSource(schema);
    migrate(dataSource, schema, MigrationVersion.fromVersion("34.1"));
    DSLContext isolatedDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager isolatedTransactionManager =
        new DataSourceTransactionManager(dataSource);
    TransactionTemplate isolatedTransaction = new TransactionTemplate(isolatedTransactionManager);

    String privateTenantKey = privateTenantKey();
    Game game = new Game();
    game.setTenantId(privateTenantKey);
    game.setName("Retained launch source");
    game.setDescription("Persisted V34 source fixture");
    Game persistedGame =
        isolatedTransaction.execute(status -> new GameRepository(isolatedDsl).save(game));
    assertThat(persistedGame).isNotNull();
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    AuthoredWorldSourceEvidence source =
        seedPreV41AuthoredWorldSource(
            isolatedDsl,
            isolatedTransaction,
            persistedGame,
            UUID.randomUUID(),
            "tenant-" + suffix,
            "world-" + suffix,
            "Retained World 🐉");
    assertThat(source).isNotNull();

    Long versionId =
        isolatedDsl
            .resultQuery(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch) "
                    + "VALUES (?, 1, 'PUBLISHED', 1) RETURNING id",
                privateTenantKey)
            .fetchOne(0, Long.class);
    Long templateId =
        isolatedDsl
            .resultQuery(
                "INSERT INTO game_templates (tenant_id, name, config) "
                    + "VALUES (?, ?, '{}'::jsonb) RETURNING id",
                privateTenantKey,
                "Retained Template " + suffix)
            .fetchOne(0, Long.class);
    // Synthetic retained-row seed for storage compatibility only; not live publication proof.
    Long bundleId =
        isolatedDsl
            .resultQuery(
                "INSERT INTO published_release_bundle "
                    + "(tenant_id, version_id, version_number, attestation_schema_version, "
                    + "publish_workflow_id, manifest_hash, required_manifest_asset_keys_json, "
                    + "participant_digests_json, generation_config_revision) "
                    + "VALUES (?, ?, 1, 'v1', ?, ?, '[]', '[]', ?) RETURNING id",
                privateTenantKey,
                versionId,
                "synthetic-retained-fixture-" + suffix,
                "sha256:" + "d".repeat(64),
                "gen-rev:" + suffix)
            .fetchOne(0, Long.class);
    if (bundleId == null) {
      throw new IllegalStateException("Retained V34 release bundle insertion returned no identity");
    }
    String requestId = "retained-cp-" + suffix;
    String legacyDescriptorId = "ld-retained-" + suffix;
    isolatedDsl.execute(
        "INSERT INTO launch_descriptor "
            + "(launch_descriptor_id, tenant_id, game_template_id, control_plane_request_id, "
            + "request_hash, version_id, runtime_flags_json, generation_config_revision, "
            + "version_state_epoch, release_bundle_id, published_release_bundle_ref) "
            + "VALUES (?, ?, ?, ?, ?, ?, '{}', ?, 1, ?, ?)",
        legacyDescriptorId,
        privateTenantKey,
        templateId,
        requestId,
        "legacy-request-hash-" + suffix,
        versionId,
        "gen-rev:" + suffix,
        bundleId,
        "legacy-release-ref-" + suffix);
    Map<String, Object> retainedV34Snapshot =
        legacyLaunchDescriptorSnapshot(isolatedDsl, privateTenantKey, requestId);
    Map<String, Object> retainedBundleSnapshot =
        retainedReleaseBundleSnapshot(isolatedDsl, bundleId);

    migrate(dataSource, schema, null);
    LaunchDescriptorRepository launchRepository = new LaunchDescriptorRepository(isolatedDsl);
    var retainedBundleReference =
        isolatedDsl.fetchOne(
            "SELECT published_release_bundle_ref FROM published_release_bundle WHERE id = ?",
            bundleId);
    if (retainedBundleReference == null) {
      throw new IllegalStateException("Retained V34 release bundle reference row is missing");
    }
    assertThat(retainedBundleReference.get("published_release_bundle_ref", String.class)).isNull();
    assertThat(retainedReleaseBundleSnapshot(isolatedDsl, bundleId))
        .isEqualTo(retainedBundleSnapshot);
    assertThat(
            new PublishedReleaseBundleRepository(isolatedDsl)
                .findByTenantIdAndVersionId(privateTenantKey, versionId)
                .orElseThrow()
                .getPublishedReleaseBundleRef())
        .isNull();
    assertThat(legacyLaunchDescriptorSnapshot(isolatedDsl, privateTenantKey, requestId))
        .isEqualTo(retainedV34Snapshot);
    LaunchDescriptor before =
        launchRepository.findByPrivateRequest(privateTenantKey, requestId).orElseThrow();
    assertThat(before.getDescriptorSchemaVersion()).isNull();
    assertThat(before.getCanonicalTenantId()).isNull();
    assertThat(before.getRequestHash()).isEqualTo("legacy-request-hash-" + suffix);

    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            requestId,
            persistedGame.getCanonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            templateId,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    LaunchDescriptorServiceImpl isolatedService =
        new LaunchDescriptorServiceImpl(
            Mockito.mock(GameTemplateRepository.class),
            launchRepository,
            Mockito.mock(VersionRepository.class),
            Mockito.mock(PublishedReleaseBundleService.class),
            Mockito.mock(TemplateRemapSetService.class),
            new GameAuthoredWorldSourceRepository(isolatedDsl),
            new ObjectMapper());
    ReflectionTestUtils.setField(isolatedService, "workloadNamespace", NAMESPACE);

    assertThatThrownBy(() -> isolatedService.resolveLaunchDescriptor(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LAUNCH_DESCRIPTOR_CONFLICT")
        .hasMessageContaining("unbound");
    assertThat(
            launchRepository.findBoundByRequest(
                NAMESPACE, persistedGame.getCanonicalTenantId(), requestId))
        .isEmpty();
    assertRetainedRowUnchanged(
        before, launchRepository.findByPrivateRequest(privateTenantKey, requestId).orElseThrow());

    assertThatThrownBy(
            () ->
                isolatedDsl.execute(
                    "UPDATE launch_descriptor SET runtime_flags_json = ? WHERE id = ?",
                    "{\"changed\":true}",
                    before.getId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining("launch descriptors are immutable");
    assertRetainedRowUnchanged(
        before, launchRepository.findByPrivateRequest(privateTenantKey, requestId).orElseThrow());
  }

  private Fixture fixture(String controlPlaneRequestId) {
    String privateTenantKey = privateTenantKey();
    Game sourceGame = new Game();
    sourceGame.setTenantId(privateTenantKey);
    sourceGame.setName("Launch Source " + UUID.randomUUID());
    sourceGame.setDescription("Persisted fresh Game Design tenant source");
    Game persistedGame =
        new TransactionTemplate(transactionManager)
            .execute(status -> gameRepository.save(sourceGame));
    assertThat(persistedGame).isNotNull();

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    AuthoredWorldSourceEvidence source =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    authoredWorldSourceRepository.register(
                        NAMESPACE,
                        UUID.randomUUID(),
                        persistedGame.getCanonicalTenantId(),
                        "tenant-" + suffix,
                        "world-" + suffix,
                        "Launch World 🐉"));
    assertThat(source).isNotNull();

    Version version = new Version();
    version.setTenantId(privateTenantKey);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(1L);
    version.setNotes("synthetic release storage proof fixture");
    version.setUpdatedAt(LocalDateTime.now());
    version = versionRepository.save(version);

    GameTemplate template = new GameTemplate();
    template.setTenantId(privateTenantKey);
    template.setName("Launch Template " + suffix);
    template.setDescription("Persisted descriptor resolution fixture");
    template.setConfig("{}");
    template.setDefaultVersionId(version.getId());
    template.setDefaultRuntimeFlagsJson("{}");
    template.setTemplateReferencePhase(TemplateReferencePhase.ENFORCED);
    template = gameTemplateRepository.save(template);

    // This row exercises descriptor storage and resolution only; it is not live publication proof.
    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setTenantId(privateTenantKey);
    bundle.setVersionId(version.getId());
    bundle.setVersionNumber(1);
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId("synthetic-storage-proof-" + suffix);
    bundle.setManifestHash("sha256:" + "e".repeat(64));
    bundle.setGenerationConfigRevision("gen-rev:" + suffix + ":" + "g".repeat(180));
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setParticipantDigestsJson("[]");
    bundle.setScriptOnly(false);
    bundle = publishedReleaseBundleRepository.save(bundle);

    return new Fixture(
        controlPlaneRequestId,
        persistedGame.getCanonicalTenantId(),
        privateTenantKey,
        source,
        version,
        template,
        bundle);
  }

  private void assertFailedShapeRejected(
      LaunchDescriptor failed, String controlPlaneRequestId, boolean nullSchemaVersion) {
    assertThatThrownBy(
            () ->
                dsl.execute(
                    """
                    INSERT INTO launch_descriptor (
                        tenant_id,
                        control_plane_request_id,
                        request_hash,
                        descriptor_schema_version,
                        target_namespace,
                        canonical_tenant_id,
                        world_slug,
                        authored_world_source_operation_id,
                        authored_world_source_evidence_digest,
                        request_digest,
                        original_request_json,
                        source_evidence_json,
                        outcome_status,
                        failure_code,
                        failure_message)
                    VALUES (?, ?, ?, CAST(? AS SMALLINT), ?, CAST(? AS UUID), ?,
                            CAST(? AS UUID), ?, ?, ?, ?, 'FAILED', CAST(? AS VARCHAR(64)), ?)
                    """,
                    failed.getTenantId(),
                    controlPlaneRequestId,
                    failed.getRequestHash(),
                    nullSchemaVersion ? null : failed.getDescriptorSchemaVersion(),
                    failed.getTargetNamespace(),
                    failed.getCanonicalTenantId(),
                    failed.getWorldSlug(),
                    failed.getAuthoredWorldSourceOperationId(),
                    failed.getAuthoredWorldSourceEvidenceDigest(),
                    failed.getRequestDigest(),
                    failed.getOriginalRequestJson(),
                    failed.getSourceEvidenceJson(),
                    nullSchemaVersion ? failed.getFailureCode() : null,
                    failed.getFailureMessage()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasStackTraceContaining("ck_launch_descriptor_authored_binding_complete");
  }

  private ResolvedLaunchDescriptorDto resolveAfterLatch(
      Fixture fixture, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    return launchDescriptorService.resolveLaunchDescriptor(fixture.request());
  }

  private String resolveFailureAfterLatch(
      Fixture fixture, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    try {
      launchDescriptorService.resolveLaunchDescriptor(fixture.request());
      return "unexpected success";
    } catch (IllegalArgumentException exception) {
      return exception.getMessage();
    }
  }

  private String resolveCandidateInOwnerTransaction(
      LaunchDescriptorServiceImpl service,
      Fixture fixture,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    await(start);
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              try {
                return "SUCCESS:"
                    + service
                        .resolveLaunchDescriptor(fixture.request())
                        .authoredWorldBinding()
                        .resultDigest();
              } catch (IllegalArgumentException exception) {
                return "FAILED:" + exception.getMessage();
              }
            });
  }

  private LaunchDescriptorServiceImpl candidateForPhase(
      Fixture fixture,
      LaunchDescriptorRepository repository,
      TemplateReferencePhase phase,
      CountDownLatch viewReached,
      CountDownLatch continueResolution) {
    GameTemplateRepository templateRepository = Mockito.mock(GameTemplateRepository.class);
    GameTemplateLaunchConfigView view = Mockito.mock(GameTemplateLaunchConfigView.class);
    Mockito.when(view.getId()).thenReturn(fixture.template().getId());
    Mockito.when(view.getTenantId()).thenReturn(fixture.privateSourceTenantKey());
    Mockito.when(view.getDefaultVersionId()).thenReturn(fixture.version().getId());
    Mockito.when(view.getDefaultRuntimeFlagsJson()).thenReturn("{}");
    Mockito.when(view.getTemplateReferencePhase())
        .thenAnswer(
            invocation -> {
              viewReached.countDown();
              await(continueResolution);
              return phase;
            });
    Mockito.when(
            templateRepository.findLaunchConfigByTenantIdAndId(
                fixture.privateSourceTenantKey(), fixture.template().getId()))
        .thenReturn(java.util.Optional.of(view));
    LaunchDescriptorServiceImpl service =
        new LaunchDescriptorServiceImpl(
            templateRepository,
            repository,
            versionRepository,
            publishedReleaseBundleService,
            Mockito.mock(TemplateRemapSetService.class),
            authoredWorldSourceRepository,
            new ObjectMapper());
    ReflectionTestUtils.setField(service, "workloadNamespace", NAMESPACE);
    return service;
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for descriptor retry race");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for descriptor retry race", exception);
    }
  }

  private AuthoredWorldLaunchDescriptorEvidence evidence(Fixture fixture, String descriptorId) {
    return AuthoredWorldLaunchDescriptorEvidence.create(
        fixture.request(),
        descriptorId,
        fixture.version().getId(),
        false,
        null,
        "{}",
        fixture.bundle().getGenerationConfigRevision(),
        fixture.version().getVersionStateEpoch(),
        fixture.bundle().getId(),
        "release-bundle:"
            + fixture.canonicalTenantId()
            + ":"
            + fixture.version().getId()
            + ":"
            + fixture.bundle().getId(),
        false,
        null);
  }

  private LaunchDescriptor entity(AuthoredWorldLaunchDescriptorEvidence evidence, Fixture fixture) {
    LaunchDescriptor descriptor = new LaunchDescriptor();
    descriptor.setLaunchDescriptorId(evidence.launchDescriptorId());
    descriptor.setTenantId(fixture.privateSourceTenantKey());
    descriptor.setGameTemplateId(evidence.gameTemplateId());
    descriptor.setControlPlaneRequestId(evidence.controlPlaneRequestId());
    descriptor.setRequestHash(evidence.requestDigest());
    descriptor.setVersionId(evidence.versionId());
    descriptor.setScriptPatchVersion(evidence.scriptPatchVersion());
    descriptor.setRuntimeFlagsJson(evidence.runtimeFlagsJson());
    descriptor.setGenerationConfigRevision(evidence.generationConfigRevision());
    descriptor.setVersionStateEpoch(evidence.versionStateEpoch());
    descriptor.setReleaseBundleId(evidence.releaseBundleId());
    descriptor.setPublishedReleaseBundleRef(evidence.publishedReleaseBundleRef());
    descriptor.setRemapSetId(evidence.remapSetId());
    descriptor.setDescriptorSchemaVersion(evidence.schemaVersion());
    descriptor.setTargetNamespace(evidence.targetNamespace());
    descriptor.setCanonicalTenantId(evidence.canonicalTenantId().toString());
    descriptor.setWorldSlug(evidence.worldSlug());
    descriptor.setAuthoredWorldSourceOperationId(
        evidence.authoredWorldSourceOperationId().toString());
    descriptor.setAuthoredWorldSourceEvidenceDigest(evidence.authoredWorldSourceEvidenceDigest());
    descriptor.setRequestDigest(evidence.requestDigest());
    descriptor.setResultDigest(evidence.resultDigest());
    descriptor.setOriginalRequestJson(json(evidence.request()));
    descriptor.setSourceEvidenceJson(json(fixture.source()));
    return descriptor;
  }

  private String json(Object value) {
    try {
      return new ObjectMapper().writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Test evidence serialization failed", exception);
    }
  }

  private DriverManagerDataSource isolatedDataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  /** Seeds the immutable source/binding shape written before V41 added automatic delivery. */
  private AuthoredWorldSourceEvidence seedPreV41AuthoredWorldSource(
      DSLContext isolatedDsl,
      TransactionTemplate isolatedTransaction,
      Game sourceGame,
      UUID registrationRequestId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName) {
    UUID operationId = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registrationRequestId,
            sourceGame.getCanonicalTenantId(),
            tenantSlug,
            worldSlug,
            worldDisplayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            operationId,
            requestDigest,
            sourceGame.getCanonicalTenantId(),
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGame.getId(),
            sourceGame.getTenantId(),
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registrationRequestId,
            operationId,
            requestDigest,
            sourceGame.getCanonicalTenantId(),
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGame.getId(),
            sourceGame.getTenantId(),
            "NEW_GAME_ROW",
            evidenceDigest);

    isolatedTransaction.execute(
        status -> {
          isolatedDsl.execute(
              "INSERT INTO game_design_tenant_slug_binding "
                  + "(target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id, "
                  + "source_game_tenant_key, provenance_kind) VALUES (?, ?, ?, ?, ?, ?)",
              source.targetNamespace(),
              source.canonicalTenantId(),
              source.tenantSlug(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind());
          isolatedDsl.execute(
              "INSERT INTO game_design_authored_world_source_operations "
                  + "(operation_id, schema_version, target_namespace, registration_request_id, "
                  + "request_digest, canonical_tenant_id, tenant_slug, world_slug, "
                  + "world_display_name, source_game_row_id, source_game_tenant_key, "
                  + "provenance_kind, evidence_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              source.operationId(),
              source.schemaVersion(),
              source.targetNamespace(),
              source.registrationRequestId(),
              source.requestDigest(),
              source.canonicalTenantId(),
              source.tenantSlug(),
              source.worldSlug(),
              source.worldDisplayName(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind(),
              source.evidenceDigest());
          return null;
        });
    return source;
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

  private Map<String, Object> legacyLaunchDescriptorSnapshot(
      DSLContext isolatedDsl, String privateTenantKey, String controlPlaneRequestId) {
    var record =
        isolatedDsl.fetchOne(
            "SELECT id, launch_descriptor_id, tenant_id, game_template_id, "
                + "control_plane_request_id, request_hash, version_id, script_patch_version, "
                + "runtime_flags_json, generation_config_revision, version_state_epoch, "
                + "release_bundle_id, published_release_bundle_ref, remap_set_id, created_at "
                + "FROM launch_descriptor WHERE tenant_id = ? AND control_plane_request_id = ?",
            privateTenantKey,
            controlPlaneRequestId);
    if (record == null) {
      throw new IllegalStateException("Retained V34 launch descriptor fixture is missing");
    }
    return record.intoMap();
  }

  private Map<String, Object> retainedReleaseBundleSnapshot(DSLContext isolatedDsl, long bundleId) {
    var record =
        isolatedDsl.fetchOne(
            "SELECT id, tenant_id, version_id, version_number, attestation_schema_version, "
                + "publish_workflow_id, manifest_hash, generation_config_revision, "
                + "required_manifest_asset_keys_json, participant_digests_json, "
                + "command_definitions_json, script_only, script_patch_version, published_at "
                + "FROM published_release_bundle WHERE id = ?",
            bundleId);
    if (record == null) {
      throw new IllegalStateException("Retained V34 release bundle fixture is missing");
    }
    return record.intoMap();
  }

  private String privateTenantKey() {
    return "src-" + UUID.randomUUID().toString().replace("-", "");
  }

  private void assertRetainedRowUnchanged(LaunchDescriptor expected, LaunchDescriptor actual) {
    assertThat(actual.getId()).isEqualTo(expected.getId());
    assertThat(actual.getLaunchDescriptorId()).isEqualTo(expected.getLaunchDescriptorId());
    assertThat(actual.getTenantId()).isEqualTo(expected.getTenantId());
    assertThat(actual.getGameTemplateId()).isEqualTo(expected.getGameTemplateId());
    assertThat(actual.getControlPlaneRequestId()).isEqualTo(expected.getControlPlaneRequestId());
    assertThat(actual.getRequestHash()).isEqualTo(expected.getRequestHash());
    assertThat(actual.getVersionId()).isEqualTo(expected.getVersionId());
    assertThat(actual.getScriptPatchVersion()).isEqualTo(expected.getScriptPatchVersion());
    assertThat(actual.getRuntimeFlagsJson()).isEqualTo(expected.getRuntimeFlagsJson());
    assertThat(actual.getGenerationConfigRevision())
        .isEqualTo(expected.getGenerationConfigRevision());
    assertThat(actual.getVersionStateEpoch()).isEqualTo(expected.getVersionStateEpoch());
    assertThat(actual.getReleaseBundleId()).isEqualTo(expected.getReleaseBundleId());
    assertThat(actual.getPublishedReleaseBundleRef())
        .isEqualTo(expected.getPublishedReleaseBundleRef());
    assertThat(actual.getRemapSetId()).isEqualTo(expected.getRemapSetId());
    assertThat(actual.getCreatedAt()).isEqualTo(expected.getCreatedAt());
    assertThat(actual.getDescriptorSchemaVersion()).isNull();
    assertThat(actual.getTargetNamespace()).isNull();
    assertThat(actual.getCanonicalTenantId()).isNull();
    assertThat(actual.getWorldSlug()).isNull();
    assertThat(actual.getAuthoredWorldSourceOperationId()).isNull();
    assertThat(actual.getAuthoredWorldSourceEvidenceDigest()).isNull();
    assertThat(actual.getRequestDigest()).isNull();
    assertThat(actual.getResultDigest()).isNull();
    assertThat(actual.getOriginalRequestJson()).isNull();
    assertThat(actual.getSourceEvidenceJson()).isNull();
  }

  private record Fixture(
      String controlPlaneRequestId,
      UUID canonicalTenantId,
      String privateSourceTenantKey,
      AuthoredWorldSourceEvidence source,
      Version version,
      GameTemplate template,
      PublishedReleaseBundle bundle) {
    AuthoredWorldLaunchDescriptorEvidence.Request request() {
      return request(controlPlaneRequestId);
    }

    AuthoredWorldLaunchDescriptorEvidence.Request request(String requestId) {
      return new AuthoredWorldLaunchDescriptorEvidence.Request(
          NAMESPACE,
          requestId,
          canonicalTenantId,
          source.worldSlug(),
          source.operationId(),
          source.evidenceDigest(),
          template.getId(),
          false,
          null,
          false,
          null,
          false,
          null,
          false,
          null);
    }

    AuthoredWorldLaunchDescriptorEvidence.Request requestWithPatch(String patch) {
      return new AuthoredWorldLaunchDescriptorEvidence.Request(
          NAMESPACE,
          controlPlaneRequestId,
          canonicalTenantId,
          source.worldSlug(),
          source.operationId(),
          source.evidenceDigest(),
          template.getId(),
          true,
          patch,
          false,
          null,
          false,
          null,
          false,
          null);
    }
  }

  @Test
  void authoredSourceMigrationBindsFullSourceTupleAndRetainsLegacyRows() {
    String tenantId = "launch-schema-" + UUID.randomUUID().toString().substring(0, 8);
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("launch schema proof");
    Game savedGame = gameRepository.save(game);

    GameTemplate template = new GameTemplate();
    template.setTenantId(tenantId);
    template.setName("launch schema template");
    template.setConfig("{}");
    template.setTemplateReferencePhase(TemplateReferencePhase.ENFORCED);
    GameTemplate savedTemplate = gameTemplateRepository.save(template);

    Version version = new Version();
    version.setTenantId(tenantId);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setNotes("launch schema proof");
    Version savedVersion = versionRepository.save(version);

    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setTenantId(tenantId);
    bundle.setVersionId(savedVersion.getId());
    bundle.setVersionNumber(savedVersion.getVersionNumber());
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId("launch-schema-" + UUID.randomUUID());
    bundle.setManifestHash(sha256("a"));
    bundle.setManifestSchemaVersion(1);
    bundle.setArtifactDigestsJson("[]");
    bundle.setGenerationConfigRevision("launch-schema-generation");
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setParticipantDigestsJson("[]");
    bundle.setCommandDefinitionsJson("[]");
    PublishedReleaseBundle savedBundle = publishedReleaseBundleRepository.save(bundle);

    AuthoredWorldSourceFixture source = insertAuthoredWorldSourceFixture(savedGame);
    String requestDigest = sha256("b");
    String resultDigest = sha256("c");
    insertSuccessDescriptor(
        "launch-schema-bound-" + UUID.randomUUID(),
        tenantId,
        savedTemplate,
        savedVersion,
        savedBundle,
        "launch-schema-bound-request",
        requestDigest,
        resultDigest,
        source,
        source.evidenceDigest());
    insertSuccessDescriptor(
        "launch-schema-legacy-" + UUID.randomUUID(),
        tenantId,
        savedTemplate,
        savedVersion,
        savedBundle,
        "launch-schema-legacy-request",
        "legacy-request-hash",
        null,
        null,
        null);

    assertThatThrownBy(
            () ->
                insertFailedDescriptor(
                    tenantId,
                    "launch-schema-missing-source",
                    UUID.randomUUID(),
                    source,
                    source.evidenceDigest(),
                    requestDigest))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertFailedDescriptor(
                    tenantId,
                    "launch-schema-mismatched-source",
                    source.operationId(),
                    new AuthoredWorldSourceFixture(
                        source.targetNamespace(),
                        source.canonicalTenantId(),
                        source.tenantSlug(),
                        source.worldSlug(),
                        source.operationId(),
                        source.sourceGameRowId() + 1,
                        source.sourceGameTenantKey(),
                        source.provenanceKind(),
                        source.evidenceDigest()),
                    source.evidenceDigest(),
                    requestDigest))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertFailedDescriptor(
                    tenantId,
                    "launch-schema-malformed-source-digest",
                    source.operationId(),
                    source,
                    "sha256:" + "d".repeat(63),
                    requestDigest))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertFailedDescriptor(
                    tenantId,
                    "launch-schema-malformed-request-digest",
                    source.operationId(),
                    source,
                    source.evidenceDigest(),
                    "not-a-sha256-digest"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertSuccessDescriptor(
                    "launch-schema-malformed-result-" + UUID.randomUUID(),
                    tenantId,
                    savedTemplate,
                    savedVersion,
                    savedBundle,
                    "launch-schema-malformed-result-request",
                    requestDigest,
                    "sha256:" + "e".repeat(63),
                    source,
                    source.evidenceDigest()))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE TABLE launch_descriptor"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("launch descriptors are immutable");
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM launch_descriptor WHERE control_plane_request_id = ?",
                    "launch-schema-bound-request"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("launch descriptors are immutable");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM launch_descriptor WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isEqualTo(2);
  }

  private AuthoredWorldSourceFixture insertAuthoredWorldSourceFixture(Game game) {
    String targetNamespace = "dev";
    String tenantSlug = "launch-schema-tenant";
    String worldSlug = "launch-schema-world";
    String provenanceKind = "NEW_GAME_ROW";
    UUID operationId = UUID.randomUUID();
    String requestDigest = sha256("f");
    String evidenceDigest = sha256("1");
    new TransactionTemplate(transactionManager)
        .execute(
            transaction -> {
              jdbcTemplate.update(
                  "INSERT INTO game_design_tenant_slug_binding "
                      + "(target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id, "
                      + "source_game_tenant_key, provenance_kind) VALUES (?, ?, ?, ?, ?, ?)",
                  targetNamespace,
                  game.getCanonicalTenantId(),
                  tenantSlug,
                  game.getId(),
                  game.getTenantId(),
                  provenanceKind);
              jdbcTemplate.update(
                  "INSERT INTO game_design_authored_world_source_operations "
                      + "(operation_id, schema_version, target_namespace, registration_request_id, "
                      + "request_digest, canonical_tenant_id, tenant_slug, world_slug, "
                      + "world_display_name, source_game_row_id, source_game_tenant_key, "
                      + "provenance_kind, evidence_digest) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                  operationId,
                  targetNamespace,
                  UUID.randomUUID(),
                  requestDigest,
                  game.getCanonicalTenantId(),
                  tenantSlug,
                  worldSlug,
                  "Launch schema world",
                  game.getId(),
                  game.getTenantId(),
                  provenanceKind,
                  evidenceDigest);
              return null;
            });
    return new AuthoredWorldSourceFixture(
        targetNamespace,
        game.getCanonicalTenantId(),
        tenantSlug,
        worldSlug,
        operationId,
        game.getId(),
        game.getTenantId(),
        provenanceKind,
        evidenceDigest);
  }

  private void insertSuccessDescriptor(
      String descriptorId,
      String tenantId,
      GameTemplate template,
      Version version,
      PublishedReleaseBundle bundle,
      String controlPlaneRequestId,
      String requestDigest,
      String resultDigest,
      AuthoredWorldSourceFixture source,
      String sourceEvidenceDigest) {
    jdbcTemplate.update(
        "INSERT INTO launch_descriptor (launch_descriptor_id, tenant_id, game_template_id, "
            + "control_plane_request_id, request_hash, version_id, runtime_flags_json, "
            + "generation_config_revision, version_state_epoch, release_bundle_id, "
            + "published_release_bundle_ref, descriptor_schema_version, target_namespace, "
            + "canonical_tenant_id, authored_world_source_tenant_slug, world_slug, "
            + "authored_world_source_operation_id, authored_world_source_game_row_id, "
            + "authored_world_source_game_tenant_key, authored_world_source_provenance_kind, "
            + "authored_world_source_evidence_digest, request_digest, result_digest, "
            + "original_request_json, source_evidence_json, outcome_status) "
            + "VALUES (?, ?, ?, ?, ?, ?, '{}', ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS')",
        descriptorId,
        tenantId,
        template.getId(),
        controlPlaneRequestId,
        requestDigest,
        version.getId(),
        bundle.getGenerationConfigRevision(),
        bundle.getId(),
        bundle.getPublishedReleaseBundleRef(),
        source == null ? null : 1,
        source == null ? null : source.targetNamespace(),
        source == null ? null : source.canonicalTenantId(),
        source == null ? null : source.tenantSlug(),
        source == null ? null : source.worldSlug(),
        source == null ? null : source.operationId(),
        source == null ? null : source.sourceGameRowId(),
        source == null ? null : source.sourceGameTenantKey(),
        source == null ? null : source.provenanceKind(),
        sourceEvidenceDigest,
        source == null ? null : requestDigest,
        resultDigest,
        source == null ? null : "{}",
        source == null ? null : "{}");
  }

  private void insertFailedDescriptor(
      String tenantId,
      String controlPlaneRequestId,
      UUID operationId,
      AuthoredWorldSourceFixture source,
      String sourceEvidenceDigest,
      String requestDigest) {
    jdbcTemplate.update(
        "INSERT INTO launch_descriptor (tenant_id, control_plane_request_id, request_hash, "
            + "descriptor_schema_version, target_namespace, canonical_tenant_id, "
            + "authored_world_source_tenant_slug, world_slug, authored_world_source_operation_id, "
            + "authored_world_source_game_row_id, authored_world_source_game_tenant_key, "
            + "authored_world_source_provenance_kind, authored_world_source_evidence_digest, "
            + "request_digest, original_request_json, source_evidence_json, outcome_status, "
            + "failure_code, failure_message) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', '{}', 'FAILED', 'RELEASE_BUNDLE_NOT_FOUND', 'fixture failure')",
        tenantId,
        controlPlaneRequestId,
        requestDigest,
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.tenantSlug(),
        source.worldSlug(),
        operationId,
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        sourceEvidenceDigest,
        requestDigest);
  }

  private String sha256(String repeatedCharacter) {
    return "sha256:" + repeatedCharacter.repeat(64);
  }

  private record AuthoredWorldSourceFixture(
      String targetNamespace,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug,
      UUID operationId,
      Long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      String evidenceDigest) {}
}
