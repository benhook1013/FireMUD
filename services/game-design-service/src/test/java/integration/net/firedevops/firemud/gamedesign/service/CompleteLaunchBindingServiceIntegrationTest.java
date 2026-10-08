package integration.net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.dto.CompleteLaunchBindingDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
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
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.Configuration;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL proof for the immutable descriptor/release pair read from one owner snapshot. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "firemud.grpc.workload-namespace=complete-launch-binding-test",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class CompleteLaunchBindingServiceIntegrationTest {
  private static final String NAMESPACE = "complete-launch-binding-test";
  private static final org.jooq.Table<?> VERSION_TABLE = DSL.table(DSL.name("version"));
  private static final org.jooq.Field<Long> VERSION_ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> VERSION_STATE =
      DSL.field(DSL.name("version_state"), String.class);
  private static final org.jooq.Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private CompleteLaunchBindingService completeLaunchBindingService;
  @Autowired private LaunchDescriptorService launchDescriptorService;
  @Autowired private GameRepository gameRepository;
  @Autowired private GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  @Autowired private GameTemplateRepository gameTemplateRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Autowired private LaunchDescriptorRepository launchDescriptorRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private org.jooq.DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void returnsAndRetriesThePersistedCompletePairWithoutChangingOwnerRows() {
    Fixture fixture = fixture("complete-pair", true);
    Map<String, String> before = rowVersions(fixture);

    CompleteLaunchBindingDto first = readComplete(fixture);
    CompleteLaunchBindingDto retry = readComplete(fixture);

    assertThat(first).isEqualTo(retry);
    assertThat(first.descriptor()).isEqualTo(fixture.descriptor().authoredWorldBinding());
    first.releaseAttestation().requireValid(first.descriptor());
    assertThat(first.releaseAttestation().canonicalTenantId())
        .isEqualTo(fixture.canonicalTenantId());
    assertThat(first.releaseAttestation().canonicalVersionId())
        .isEqualTo(fixture.version().getCanonicalVersionId());
    assertThat(first.releaseAttestation().versionStateEpoch())
        .isEqualTo(fixture.descriptor().versionStateEpoch());
    assertThat(fixture.descriptor().releaseBundleId()).isEqualTo(fixture.bundle().getId());
    assertThat(fixture.descriptor().publishedReleaseBundleRef())
        .isEqualTo(fixture.bundle().getPublishedReleaseBundleRef());
    assertThat(first.releaseAttestation().participantDigests())
        .extracting(AuthoredWorldReleaseAttestationEvidence.Participant::participantKey)
        .containsExactlyElementsOf(
            AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder());
    assertThat(first.releaseAttestation().artifactDigests()).hasSize(1);

    LaunchDescriptor persisted = persistedDescriptor(fixture);
    assertThat(persisted.getLaunchDescriptorId())
        .isEqualTo(first.descriptor().launchDescriptorId());
    assertThat(persisted.getResultDigest()).isEqualTo(first.descriptor().resultDigest());
    assertThat(rowVersions(fixture)).isEqualTo(before);
  }

  @Test
  void retainsTheHistoricalAttestationWhenCurrentVersionStateAdvances() {
    Fixture fixture = fixture("historical-epoch", true);
    CompleteLaunchBindingDto original = readComplete(fixture);
    Map<String, String> immutableRowsBefore = immutableRowVersions(fixture);

    advanceVersion(fixture, VersionLifecycleState.RETIRED, 2L);
    CompleteLaunchBindingDto later = readComplete(fixture);

    assertThat(later.descriptor()).isEqualTo(original.descriptor());
    assertThat(later.releaseAttestation()).isEqualTo(original.releaseAttestation());
    assertThat(later.releaseAttestation().versionStateEpoch())
        .isEqualTo(original.descriptor().versionStateEpoch());
    assertThat(
            dsl.select(VERSION_STATE)
                .from(VERSION_TABLE)
                .where(VERSION_ID.eq(fixture.version().getId()))
                .fetchOne(VERSION_STATE))
        .isEqualTo(VersionLifecycleState.RETIRED.name());
    assertThat(
            dsl.select(VERSION_STATE_EPOCH)
                .from(VERSION_TABLE)
                .where(VERSION_ID.eq(fixture.version().getId()))
                .fetchOne(VERSION_STATE_EPOCH))
        .isEqualTo(2L);
    assertThat(immutableRowVersions(fixture)).isEqualTo(immutableRowsBefore);
    // This is historical evidence equality, not a claim that RETIRED is currently launch-eligible.
  }

  @Test
  void deniesCrossTenantAndSubstitutedSourceSelectorsWithoutMutatingPersistedEvidence() {
    Fixture first = fixture("cross-tenant-first", true);
    Fixture second = fixture("cross-tenant-second", true);
    Map<String, String> firstBefore = rowVersions(first);
    Map<String, String> secondBefore = rowVersions(second);
    UUID absentCanonicalTenant = UUID.randomUUID();

    assertDenied(
        () ->
            completeLaunchBindingService.getCompleteLaunchBinding(
                first.readRequestId(),
                second.canonicalTenantId(),
                second.source().worldSlug(),
                first.controlPlaneRequestId(),
                first.descriptor().authoredWorldBinding().requestDigest(),
                first.descriptor().authoredWorldBinding().resultDigest()));
    assertDenied(
        () ->
            completeLaunchBindingService.getCompleteLaunchBinding(
                first.readRequestId(),
                first.canonicalTenantId(),
                second.source().worldSlug(),
                first.controlPlaneRequestId(),
                first.descriptor().authoredWorldBinding().requestDigest(),
                first.descriptor().authoredWorldBinding().resultDigest()));
    assertDenied(
        () ->
            completeLaunchBindingService.getCompleteLaunchBinding(
                first.readRequestId(),
                absentCanonicalTenant,
                first.source().worldSlug(),
                first.controlPlaneRequestId(),
                first.descriptor().authoredWorldBinding().requestDigest(),
                first.descriptor().authoredWorldBinding().resultDigest()));

    PublishedReleaseBundle swappedCanonicalSource = new PublishedReleaseBundle();
    swappedCanonicalSource.setTenantId(first.privateTenantKey());
    swappedCanonicalSource.setVersionId(first.version().getId());
    swappedCanonicalSource.setCanonicalTenantId(second.canonicalTenantId());
    swappedCanonicalSource.setCanonicalVersionId(second.version().getCanonicalVersionId());
    assertThatThrownBy(() -> publishedReleaseBundleRepository.save(swappedCanonicalSource))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical release identity");

    assertThat(rowVersions(first)).isEqualTo(firstBefore);
    assertThat(rowVersions(second)).isEqualTo(secondBefore);
  }

  @Test
  void deniesPersistedDescriptorWhoseBundleLacksCompleteOwnerEvidenceWithoutMutation() {
    Fixture fixture = fixture("missing-owner-evidence", false);
    Map<String, String> before = rowVersions(fixture);

    assertThatThrownBy(() -> readComplete(fixture))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PARTICIPANT_EVIDENCE_INCOMPLETE");

    assertThat(rowVersions(fixture)).isEqualTo(before);
  }

  @Test
  void descriptorSnapshotRejectsNonRepeatableReadBeforeAnyWrite() {
    Fixture fixture = fixture("snapshot-guard", true);
    Map<String, String> before = rowVersions(fixture);
    TransactionTemplate readCommitted = new TransactionTemplate(transactionManager);
    readCommitted.setReadOnly(true);
    readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

    assertThatThrownBy(
            () ->
                readCommitted.execute(
                    status ->
                        launchDescriptorService.getLaunchDescriptorInOwnerSnapshot(
                            fixture.readRequestId(),
                            fixture.canonicalTenantId(),
                            fixture.source().worldSlug(),
                            fixture.controlPlaneRequestId(),
                            fixture.descriptor().authoredWorldBinding().requestDigest(),
                            fixture.descriptor().authoredWorldBinding().resultDigest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("read-only REPEATABLE_READ");

    assertThat(rowVersions(fixture)).isEqualTo(before);
  }

  @Test
  void concurrentVersionAdvanceCannotSplitTheCompleteOwnerSnapshot() throws Exception {
    Fixture fixture = fixture("concurrent-owner-snapshot", true);
    Map<String, String> immutableRowsBefore = immutableRowVersions(fixture);
    CountDownLatch producerVersionReadCompleted = new CountDownLatch(1);
    CountDownLatch continueRead = new CountDownLatch(1);
    AtomicInteger versionStateSnapshotReads = new AtomicInteger();
    AtomicReference<Boolean> readOnlyObserved = new AtomicReference<>(false);
    AtomicReference<Integer> isolationObserved = new AtomicReference<>();
    DefaultExecuteListener interlock =
        new DefaultExecuteListener() {
          @Override
          public void executeEnd(ExecuteContext context) {
            String sql = context.sql();
            if (sql != null
                && isVersionStateSnapshotQuery(sql)
                && versionStateSnapshotReads.incrementAndGet() == 2) {
              readOnlyObserved.set(
                  TransactionSynchronizationManager.isCurrentTransactionReadOnly());
              isolationObserved.set(
                  TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
              producerVersionReadCompleted.countDown();
              awaitInterlock(continueRead);
            }
          }
        };
    Configuration configuration = dsl.configuration();
    ExecuteListenerProvider[] originalProviders = configuration.executeListenerProviders();
    List<ExecuteListenerProvider> providers = new ArrayList<>(Arrays.asList(originalProviders));
    providers.add(new DefaultExecuteListenerProvider(interlock));
    configuration.set(providers.toArray(ExecuteListenerProvider[]::new));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<CompleteLaunchBindingDto> inFlightRead = executor.submit(() -> readComplete(fixture));
      assertThat(producerVersionReadCompleted.await(10, TimeUnit.SECONDS)).isTrue();

      advanceVersion(fixture, VersionLifecycleState.RETIRED, 2L);
      continueRead.countDown();

      CompleteLaunchBindingDto snapshot = inFlightRead.get(20, TimeUnit.SECONDS);
      assertThat(readOnlyObserved.get()).isTrue();
      assertThat(isolationObserved.get())
          .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      assertThat(snapshot.descriptor()).isEqualTo(fixture.descriptor().authoredWorldBinding());
      assertThat(snapshot.releaseAttestation().versionStateEpoch())
          .isEqualTo(fixture.descriptor().versionStateEpoch());
      assertThat(snapshot.releaseAttestation())
          .isEqualTo(readComplete(fixture).releaseAttestation());
      assertThat(immutableRowVersions(fixture)).isEqualTo(immutableRowsBefore);
      assertThat(
              dsl.select(VERSION_STATE_EPOCH)
                  .from(VERSION_TABLE)
                  .where(VERSION_ID.eq(fixture.version().getId()))
                  .fetchOne(VERSION_STATE_EPOCH))
          .isEqualTo(2L);
    } finally {
      continueRead.countDown();
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
      configuration.set(originalProviders);
    }
  }

  private boolean isVersionStateSnapshotQuery(String sql) {
    String compact = sql.toLowerCase(Locale.ROOT).replace("\"", "").replaceAll("\\s+", "");
    return compact.contains(
        "selectid,tenant_id,canonical_version_id,canonical_tenant_id,"
            + "identity_source_game_row_id,identity_source_game_tenant_key,"
            + "identity_source_provenance_kind,version_state,version_state_epoch"
            + "fromversionwhereid=?");
  }

  private Fixture fixture(String label, boolean completeEvidence) {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String privateTenantKey = "gd-" + UUID.randomUUID().toString().replace("-", "");
    assertThat(privateTenantKey).matches("gd-[0-9a-f]{32}").hasSize(35);
    Game game = new Game();
    game.setTenantId(privateTenantKey);
    game.setName("Complete binding source " + label + " " + suffix);
    game.setDescription("Fresh persisted Game provenance for complete-binding proof");
    Game persistedGame = gameRepository.save(game);
    assertThat(privateTenantKey).isNotEqualTo(persistedGame.getCanonicalTenantId().toString());

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
                        "Synthetic Complete Binding World"));

    Version version = new Version();
    version.setTenantId(privateTenantKey);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(1L);
    version.setNotes("synthetic complete-release evidence fixture");
    version.setUpdatedAt(LocalDateTime.now());
    version = versionRepository.save(version);

    GameTemplate template = new GameTemplate();
    template.setTenantId(privateTenantKey);
    template.setName("Complete Binding Template " + suffix);
    template.setDescription("Persisted launch descriptor fixture");
    template.setConfig("{}");
    template.setDefaultVersionId(version.getId());
    template.setDefaultRuntimeFlagsJson("{}");
    template.setTemplateReferencePhase(TemplateReferencePhase.ENFORCED);
    template = gameTemplateRepository.save(template);

    PublishedReleaseBundle bundle =
        syntheticBundle(privateTenantKey, version, suffix, completeEvidence);
    PublishedReleaseBundle persistedBundle = publishedReleaseBundleRepository.save(bundle);
    String controlPlaneRequestId = "complete-launch-" + suffix;
    UUID readRequestId = UUID.randomUUID();
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            controlPlaneRequestId,
            persistedGame.getCanonicalTenantId(),
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
    ResolvedLaunchDescriptorDto descriptor =
        launchDescriptorService.resolveLaunchDescriptor(request);
    LaunchDescriptor storedDescriptor =
        launchDescriptorRepository
            .findBoundByRequest(
                NAMESPACE, persistedGame.getCanonicalTenantId(), controlPlaneRequestId)
            .orElseThrow();

    return new Fixture(
        readRequestId,
        controlPlaneRequestId,
        persistedGame.getId(),
        persistedGame.getCanonicalTenantId(),
        privateTenantKey,
        source,
        version,
        template,
        persistedBundle,
        descriptor,
        storedDescriptor);
  }

  private PublishedReleaseBundle syntheticBundle(
      String privateTenantKey, Version version, String suffix, boolean completeEvidence) {
    // Synthetic fixture bytes produce real content-addressed hashes; this is not a claim that
    // Game Logic or external object storage performed a normal publication in this test.
    byte[] artifactBytes =
        ("synthetic complete-binding artifact " + suffix).getBytes(StandardCharsets.UTF_8);
    String artifactHex = sha256Hex(artifactBytes);
    String artifactDigest = "sha256:" + artifactHex;
    String manifestBytes =
        "{\"schemaVersion\":1,\"assets\":[{\"usageKey\":\"world.navmesh\",\"contentDigest\":\""
            + artifactDigest
            + "\"}]}";
    String manifestHash = "sha256:" + sha256Hex(manifestBytes.getBytes(StandardCharsets.UTF_8));
    PublishedArtifactDigest artifact =
        new PublishedArtifactDigest(
            "world.navmesh",
            "world-navmesh",
            "artifacts/sha256/" + artifactHex,
            artifactDigest,
            "application/octet-stream",
            1);

    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setTenantId(privateTenantKey);
    bundle.setVersionId(version.getId());
    bundle.setVersionNumber(version.getVersionNumber());
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId("synthetic-publish-workflow:" + suffix);
    bundle.setManifestHash(manifestHash);
    bundle.setManifestSchemaVersion(1);
    bundle.setArtifactDigestsJson(objectMapper.writeValueAsString(List.of(artifact)));
    bundle.setGenerationConfigRevision("generation-config:" + suffix + ":" + "g".repeat(180));
    bundle.setRequiredManifestAssetKeysJson(
        objectMapper.writeValueAsString(List.of("world.navmesh")));
    bundle.setParticipantDigestsJson(
        objectMapper.writeValueAsString(
            completeEvidence ? syntheticParticipants(version) : List.of()));
    bundle.setCommandDefinitionsJson(
        objectMapper.writeValueAsString(List.of("{\"commandId\":\"look\"}")));
    bundle.setScriptOnly(false);
    return bundle;
  }

  private List<PublishParticipantDigestDto> syntheticParticipants(Version version) {
    List<PublishParticipantDigestDto> participants = new ArrayList<>();
    for (String participantKey :
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder()) {
      boolean gameLogic = "GAME_LOGIC".equals(participantKey);
      participants.add(
          new PublishParticipantDigestDto(
              participantKey,
              Long.toString(version.getId()),
              null,
              "synthetic-applied-commit-" + version.getId(),
              "a".repeat(64),
              AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                  participantKey),
              gameLogic ? "sha256:" + "b".repeat(64) : null,
              null,
              null));
    }
    return List.copyOf(participants);
  }

  private CompleteLaunchBindingDto readComplete(Fixture fixture) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = fixture.descriptor().authoredWorldBinding();
    return completeLaunchBindingService.getCompleteLaunchBinding(
        fixture.readRequestId(),
        fixture.canonicalTenantId(),
        fixture.source().worldSlug(),
        fixture.controlPlaneRequestId(),
        descriptor.requestDigest(),
        descriptor.resultDigest());
  }

  private void advanceVersion(Fixture fixture, VersionLifecycleState state, long epoch) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.execute(
        status -> {
          Version current =
              versionRepository
                  .findByTenantIdAndId(fixture.privateTenantKey(), fixture.version().getId())
                  .orElseThrow();
          current.setVersionState(state);
          current.setVersionStateEpoch(epoch);
          return versionRepository.save(current);
        });
  }

  private Map<String, String> rowVersions(Fixture fixture) {
    Map<String, String> snapshot = immutableRowVersions(fixture);
    snapshot.put("version", rowXmin("version", "id", fixture.version().getId()));
    return snapshot;
  }

  private Map<String, String> immutableRowVersions(Fixture fixture) {
    Map<String, String> snapshot = new LinkedHashMap<>();
    snapshot.put("game", rowXmin("game", "id", fixture.gameRowId()));
    snapshot.put(
        "source",
        rowXmin(
            "game_design_authored_world_source_operations",
            "operation_id",
            fixture.source().operationId()));
    snapshot.put("template", rowXmin("game_templates", "id", fixture.template().getId()));
    snapshot.put("bundle", rowXmin("published_release_bundle", "id", fixture.bundle().getId()));
    snapshot.put(
        "descriptor", rowXmin("launch_descriptor", "id", fixture.storedDescriptor().getId()));
    return snapshot;
  }

  private String rowXmin(String table, String idColumn, Object id) {
    var row = dsl.fetchOne("SELECT xmin::text FROM " + table + " WHERE " + idColumn + " = ?", id);
    return row == null ? "<missing>" : row.get(0, String.class);
  }

  private LaunchDescriptor persistedDescriptor(Fixture fixture) {
    return launchDescriptorRepository
        .findBoundByRequest(NAMESPACE, fixture.canonicalTenantId(), fixture.controlPlaneRequestId())
        .orElseThrow();
  }

  private void assertDenied(Runnable operation) {
    assertThatThrownBy(operation::run).isInstanceOf(IllegalArgumentException.class);
  }

  private void awaitInterlock(CountDownLatch continueRead) {
    try {
      if (!continueRead.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to resume complete-binding snapshot");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while holding complete-binding snapshot", exception);
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
    }
  }

  private record Fixture(
      UUID readRequestId,
      String controlPlaneRequestId,
      long gameRowId,
      UUID canonicalTenantId,
      String privateTenantKey,
      AuthoredWorldSourceEvidence source,
      Version version,
      GameTemplate template,
      PublishedReleaseBundle bundle,
      ResolvedLaunchDescriptorDto descriptor,
      LaunchDescriptor storedDescriptor) {}
}
