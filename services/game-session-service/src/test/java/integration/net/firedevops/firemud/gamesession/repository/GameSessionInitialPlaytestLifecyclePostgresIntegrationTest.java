package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPrivateFreshTargetSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerTestFixtures;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Allocation;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.IdempotencyConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.InitializationMode;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Preparation;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.ReadState;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Request;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Snapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.SourceUnavailableException;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshCatalogRevisionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshCatalogRevisionRepository.AdvanceRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshRuntimeAllocationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivatePreparationProtectionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateWorldPreparationBuildReader;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateWorldPreparationSourceReader;
import net.firedevops.firemud.gamesession.repository.GameSessionRuntimeFamilyCatalogRepository;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical initial allocation/read proof using actual GS source/catalog/preparation owners and SQL.
 *
 * <p>The existing canonical publication fixture stipulates upstream Account/World/Game Design
 * authority; this does not execute those producers or establish caller authorization, participant
 * manifests, isolation, source delivery, registration or playtest admission. Fixture-policy source
 * rows are never accepted by this primitive.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionInitialPlaytestLifecyclePostgresIntegrationTest {
  private static final String NAMESPACE = "initial-playtest-proof";
  private static final String TABLE = "game_session_initial_playtest_preparations";
  private static final String REVISION_HISTORY = "game_session_private_catalog_revision_history";
  private static final String REVISION_HEAD = "game_session_current_private_catalog_revisions";
  private static final tools.jackson.databind.ObjectMapper POLICY_JSON =
      JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void awaitPostgresReady() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  void privateRuntimeIdentityAllocationRetainsExactCurrentSourcesAndHasNoGameplayEffects() {
    var fixture = runtimeFixture(3600);
    var context = fixture.context();
    var owner = new GameSessionPrivateFreshRuntimeAllocationRepository(context.dsl());
    var sourceRows = runtimeSourceRows(context);
    var allocated = allocateRuntime(context, fixture.request());
    var result = allocated.snapshot();
    assertThat(allocated.replayed()).isFalse();
    assertThat(result.matches(fixture.target())).isTrue();
    assertThat(result.gameInstanceId())
        .isNotEqualTo(new UUID(0, 0))
        .isNotEqualTo(result.realmId())
        .isNotEqualTo(result.playtestLifecycleId())
        .isNotEqualTo(result.playableStateNamespaceId());
    assertThat(result.selectionRequestId()).isEqualTo(fixture.selection().request().requestId());
    assertThat(result.selectionHeadRevision()).isEqualTo(fixture.selection().headRevision());
    assertThat(result.selectionRequestDigest()).isEqualTo(fixture.selection().requestDigest());
    assertThat(result.admissionAllowed()).isFalse();
    assertThat(owner.readByRequest(fixture.request().requestId())).contains(result);
    var row =
        retainedRowWithXmin(
            context, RUNTIME_ALLOCATIONS, "request_id", result.allocationRequestId());
    assertThat(allocateRuntime(context, fixture.request()))
        .isEqualTo(new GameSessionPrivateFreshRuntimeAllocationRepository.Allocation(result, true));
    var currentRead =
        context
            .transaction()
            .execute(
                status ->
                    owner.readForWorldPreparation(
                        result.preparationRequestId(), result.playtestLifecycleId()));
    assertThat(currentRead).contains(result);
    assertThat(
            retainedRowWithXmin(
                context, RUNTIME_ALLOCATIONS, "request_id", result.allocationRequestId()))
        .isEqualTo(row);
    assertThat(runtimeSourceRows(context)).isEqualTo(sourceRows);
    assertThat(context.dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
  }

  @Test
  void privateBuildReadProjectsActualRetainedWorldSelectionWithoutChangingOwnerRows() {
    var fixture = runtimeFixture(3600);
    var context = fixture.context();
    allocateRuntime(context, fixture.request());
    var before = runtimeSourceRows(context);
    byte[] retainedCarrier =
        context
            .dsl()
            .fetchSingle(
                "SELECT complete_policy_set_evidence FROM game_session_canonical_realm_catalog "
                    + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ?",
                NAMESPACE,
                context.catalog().tenantId(),
                context.catalog().realmId())
            .get("complete_policy_set_evidence", byte[].class);
    assertThat(retainedCarrier)
        .containsExactly(RealmPolicyPublishedEvidenceCodec.encode(context.published()));
    var request =
        new PrivateWorldPreparationReadGrpcCodec.Request(
            UUID.randomUUID(),
            new CurrentPrivatePlaytestLifecycleReadEvidence.Scope(
                NAMESPACE,
                fixture.target().tenantId(),
                fixture.target().worldSlug(),
                fixture.target().realmSlug()),
            fixture.preparation().request().requestId());
    var source =
        new GameSessionPrivateWorldPreparationSourceReader(
            new GameSessionCurrentPrivatePlaytestRepository(context.dsl()),
            context.privateTargets(),
            new GameSessionPrivateFreshRuntimeAllocationRepository(context.dsl()));
    var reader = new GameSessionPrivateWorldPreparationBuildReader(context.dsl(), source);
    var build =
        Objects.requireNonNull(context.transaction().execute(status -> reader.read(request)));
    assertThat(build.source().source().policyCarrierDigest()).isEqualTo(sha256(retainedCarrier));
    assertThat(build.source().source().targetPolicy()).isIn(context.published().policies());
    assertThat(build.selectedWorld())
        .isEqualTo(
            GameDesignPublicationOperationBinding.fromStored(context.published().operationBytes())
                .world());
    assertThat(build.selectedWorld().request().targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(build.source().admissionAllowed()).isFalse();
    assertThat(runtimeSourceRows(context)).isEqualTo(before);
    assertThat(context.dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void privateRuntimeReadsNeverAllocateAndChangedRetryIntentConflictsWithoutWrites() {
    var fixture = runtimeFixture(3600);
    var context = fixture.context();
    var owner = new GameSessionPrivateFreshRuntimeAllocationRepository(context.dsl());
    var absentRead =
        context
            .transaction()
            .execute(
                status ->
                    owner.readForWorldPreparation(
                        fixture.target().preparationRequestId(),
                        fixture.target().playtestLifecycleId()));
    assertThat(absentRead).isEmpty();
    assertThat(context.dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isZero();
    var first = allocateRuntime(context, fixture.request());
    var r = fixture.request();
    var before = retainedRowWithXmin(context, RUNTIME_ALLOCATIONS, "request_id", r.requestId());
    for (var changed :
        List.of(
            new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
                r.requestId(),
                UUID.randomUUID(),
                r.playtestLifecycleId(),
                r.realmId(),
                r.expectedCatalogRevision(),
                r.expectedCatalogRequestId(),
                r.expectedCatalogReceiptDigest(),
                r.expectedSelectionRequestId(),
                r.expectedSelectionHeadRevision()),
            new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
                r.requestId(),
                r.preparationRequestId(),
                UUID.randomUUID(),
                r.realmId(),
                r.expectedCatalogRevision(),
                r.expectedCatalogRequestId(),
                r.expectedCatalogReceiptDigest(),
                r.expectedSelectionRequestId(),
                r.expectedSelectionHeadRevision()),
            new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
                r.requestId(),
                r.preparationRequestId(),
                r.playtestLifecycleId(),
                UUID.randomUUID(),
                r.expectedCatalogRevision(),
                r.expectedCatalogRequestId(),
                r.expectedCatalogReceiptDigest(),
                r.expectedSelectionRequestId(),
                r.expectedSelectionHeadRevision()),
            new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
                r.requestId(),
                r.preparationRequestId(),
                r.playtestLifecycleId(),
                r.realmId(),
                2,
                r.expectedCatalogRequestId(),
                r.expectedCatalogReceiptDigest(),
                r.expectedSelectionRequestId(),
                r.expectedSelectionHeadRevision()),
            new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
                r.requestId(),
                r.preparationRequestId(),
                r.playtestLifecycleId(),
                r.realmId(),
                r.expectedCatalogRevision(),
                UUID.randomUUID(),
                r.expectedCatalogReceiptDigest(),
                r.expectedSelectionRequestId(),
                r.expectedSelectionHeadRevision()),
            new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
                r.requestId(),
                r.preparationRequestId(),
                r.playtestLifecycleId(),
                r.realmId(),
                r.expectedCatalogRevision(),
                r.expectedCatalogRequestId(),
                "sha256:" + "f".repeat(64),
                r.expectedSelectionRequestId(),
                r.expectedSelectionHeadRevision()),
            new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
                r.requestId(),
                r.preparationRequestId(),
                r.playtestLifecycleId(),
                r.realmId(),
                r.expectedCatalogRevision(),
                r.expectedCatalogRequestId(),
                r.expectedCatalogReceiptDigest(),
                UUID.randomUUID(),
                r.expectedSelectionHeadRevision()),
            new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
                r.requestId(),
                r.preparationRequestId(),
                r.playtestLifecycleId(),
                r.realmId(),
                r.expectedCatalogRevision(),
                r.expectedCatalogRequestId(),
                r.expectedCatalogReceiptDigest(),
                r.expectedSelectionRequestId(),
                2))) {
      assertThatThrownBy(() -> allocateRuntime(context, changed))
          .isInstanceOf(
              GameSessionPrivateFreshRuntimeAllocationRepository.IdempotencyConflictException
                  .class);
    }
    assertThatThrownBy(
            () ->
                allocateRuntime(
                    context,
                    runtimeRequest(UUID.randomUUID(), fixture.target(), fixture.selection())))
        .isInstanceOf(
            GameSessionPrivateFreshRuntimeAllocationRepository.IdempotencyConflictException.class);
    assertThat(owner.readByRequest(r.requestId())).contains(first.snapshot());
    assertThat(retainedRowWithXmin(context, RUNTIME_ALLOCATIONS, "request_id", r.requestId()))
        .isEqualTo(before);
  }

  @Test
  void privateRuntimeIdentityRejectsMissingSelectionAndWrongExpectedCurrentSource() {
    var revision = privateRevisionFixture(3600);
    var context = revision.context();
    var target = revision.initialTarget();
    var expected =
        new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
            UUID.randomUUID(),
            target.preparationRequestId(),
            target.playtestLifecycleId(),
            target.realmId(),
            target.catalogRevision(),
            target.creationRequestId(),
            target.receiptDigest(),
            UUID.randomUUID(),
            1);
    assertThatThrownBy(() -> allocateRuntime(context, expected))
        .isInstanceOf(
            GameSessionPrivateFreshRuntimeAllocationRepository.SourceUnavailableException.class);
    var selection = selectRuntimeSource(context, target, null);
    assertThatThrownBy(() -> allocateRuntime(context, expected))
        .isInstanceOf(
            GameSessionPrivateFreshRuntimeAllocationRepository.SourceUnavailableException.class);
    var request = runtimeRequest(UUID.randomUUID(), target, selection);
    var wrong =
        new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
            request.requestId(),
            request.preparationRequestId(),
            request.playtestLifecycleId(),
            request.realmId(),
            2,
            request.expectedCatalogRequestId(),
            request.expectedCatalogReceiptDigest(),
            request.expectedSelectionRequestId(),
            request.expectedSelectionHeadRevision());
    assertThatThrownBy(() -> allocateRuntime(context, wrong))
        .isInstanceOf(
            GameSessionPrivateFreshRuntimeAllocationRepository.SourceUnavailableException.class);
    assertThat(context.dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isZero();
  }

  @Test
  void privateRuntimeHistoricalReplaySurvivesSupersessionButCannotQualifyCurrentSource() {
    var revision = privateRevisionFixture(3600);
    var context = revision.context();
    var initial = revision.initialTarget();
    var firstSelection = selectRuntimeSource(context, initial, null);
    var firstRequest = runtimeRequest(UUID.randomUUID(), initial, firstSelection);
    var first = allocateRuntime(context, firstRequest);
    var owner = new GameSessionPrivateFreshRuntimeAllocationRepository(context.dsl());
    var before =
        retainedRowWithXmin(context, RUNTIME_ALLOCATIONS, "request_id", firstRequest.requestId());
    var later = context.advancePrivateRevision(revision.advanceRequest()).snapshot();
    // Catalog advancement and workflow selection are distinct authorities; a mismatch refuses.
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            owner.readForWorldPreparation(
                                initial.preparationRequestId(), initial.playtestLifecycleId())))
        .isInstanceOf(IllegalStateException.class);
    var wrongSelectionRequest = runtimeRequest(UUID.randomUUID(), later, firstSelection);
    assertThatThrownBy(() -> allocateRuntime(context, wrongSelectionRequest))
        .isInstanceOf(
            GameSessionPrivateFreshRuntimeAllocationRepository.SourceUnavailableException.class);
    var laterSelection = selectRuntimeSource(context, later, firstSelection);
    var laterAllocation =
        allocateRuntime(context, runtimeRequest(UUID.randomUUID(), later, laterSelection));
    assertThat(laterAllocation.snapshot().realmId()).isEqualTo(first.snapshot().realmId());
    assertThat(laterAllocation.snapshot().gameInstanceId())
        .isNotEqualTo(first.snapshot().gameInstanceId());
    assertThat(laterAllocation.snapshot().playableStateNamespaceId())
        .isNotEqualTo(first.snapshot().playableStateNamespaceId());
    assertThat(owner.readByRequest(firstRequest.requestId())).contains(first.snapshot());
    assertThat(allocateRuntime(context, firstRequest))
        .isEqualTo(
            new GameSessionPrivateFreshRuntimeAllocationRepository.Allocation(
                first.snapshot(), true));
    assertThatThrownBy(
            () ->
                allocateRuntime(
                    context, runtimeRequest(UUID.randomUUID(), initial, firstSelection)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            retainedRowWithXmin(
                context, RUNTIME_ALLOCATIONS, "request_id", firstRequest.requestId()))
        .isEqualTo(before);
    assertThat(context.dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void privateRuntimeExpiryRefusesNewEffectsAndKeepsHistoricalAllocationAndRetry() {
    var fixture = runtimeFixture(5);
    var context = fixture.context();
    var first = allocateRuntime(context, fixture.request());
    var owner = new GameSessionPrivateFreshRuntimeAllocationRepository(context.dsl());
    var before =
        retainedRowWithXmin(
            context, RUNTIME_ALLOCATIONS, "request_id", fixture.request().requestId());
    awaitExpiry(context, fixture.preparation().request().forkExpiresAt());
    assertThat(owner.readByRequest(fixture.request().requestId())).contains(first.snapshot());
    assertThat(allocateRuntime(context, fixture.request()))
        .isEqualTo(
            new GameSessionPrivateFreshRuntimeAllocationRepository.Allocation(
                first.snapshot(), true));
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status ->
                            owner.readForWorldPreparation(
                                first.snapshot().preparationRequestId(),
                                first.snapshot().playtestLifecycleId())))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                allocateRuntime(
                    context,
                    runtimeRequest(UUID.randomUUID(), fixture.target(), fixture.selection())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            retainedRowWithXmin(
                context, RUNTIME_ALLOCATIONS, "request_id", fixture.request().requestId()))
        .isEqualTo(before);
    var neverAllocated = runtimeFixture(3);
    awaitExpiry(neverAllocated.context(), neverAllocated.preparation().request().forkExpiresAt());
    assertThatThrownBy(() -> allocateRuntime(neverAllocated.context(), neverAllocated.request()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(neverAllocated.context().dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isZero();
  }

  @Test
  void privateRuntimeFinalCommitRejectsExpiryOrSourceChangeAndRollsBackAllocation() {
    var expiring = runtimeFixture(5);
    Throwable expired =
        catchThrowable(
            () ->
                expiring
                    .context()
                    .serializableTransaction()
                    .executeWithoutResult(
                        status -> {
                          new GameSessionPrivateFreshRuntimeAllocationRepository(
                                  expiring.context().dsl())
                              .allocate(expiring.request());
                          awaitExpiry(
                              expiring.context(), expiring.preparation().request().forkExpiresAt());
                        }));
    assertThat(sqlStateInCauseChain(expired)).isEqualTo("23514");
    assertThat(expiring.context().dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isZero();
    var changed = runtimeFixture(3600);
    Throwable superseded =
        catchThrowable(
            () ->
                changed
                    .context()
                    .serializableTransaction()
                    .executeWithoutResult(
                        status -> {
                          new GameSessionPrivateFreshRuntimeAllocationRepository(
                                  changed.context().dsl())
                              .allocate(changed.request());
                          // Even a self-consistent re-selection of the same lifecycle changes the
                          // captured exact head.
                          selectRuntimeSource(
                              changed.context(), changed.target(), changed.selection());
                        }));
    assertThat(sqlStateInCauseChain(superseded)).isEqualTo("23514");
    assertThat(changed.context().dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isZero();
  }

  @Test
  void privateRuntimeStorageRefusesSelfConsistentForgedSourceAndRetainsImmutableRows() {
    var fixture = runtimeFixture(3600);
    var context = fixture.context();
    allocateRuntime(context, fixture.request());
    var before =
        retainedRowWithXmin(
            context, RUNTIME_ALLOCATIONS, "request_id", fixture.request().requestId());
    for (String patch :
        List.of(
            "{\"canonical_tenant_id\":\"" + UUID.randomUUID() + "\"}",
            "{\"world_slug\":\"forged-world\"}",
            "{\"realm_slug\":\"forged-realm\"}",
            "{\"playable_state_namespace_id\":\"" + UUID.randomUUID() + "\"}",
            "{\"canonical_version_id\":\"" + UUID.randomUUID() + "\"}",
            "{\"published_release_bundle_ref\":\"forged-release\"}",
            "{\"published_release_bundle_digest\":\"sha256:" + "e".repeat(64) + "\"}",
            "{\"catalog_revision\":2}",
            "{\"catalog_request_digest\":\"sha256:" + "e".repeat(64) + "\"}",
            "{\"catalog_receipt_digest\":\"sha256:" + "e".repeat(64) + "\"}",
            "{\"selection_head_revision\":2}",
            "{\"selection_request_digest\":\"sha256:" + "e".repeat(64) + "\"}")) {
      Throwable rejected =
          catchThrowable(
              () ->
                  context
                      .serializableTransaction()
                      .executeWithoutResult(
                          status ->
                              insertForgedRuntimeAllocation(
                                  context, fixture.request().requestId(), patch)));
      assertThat(sqlStateInCauseChain(rejected)).as(patch).isIn("23514", "P0002");
    }
    for (String sql :
        List.of(
            "UPDATE " + RUNTIME_ALLOCATIONS + " SET game_instance_id=gen_random_uuid()",
            "DELETE FROM " + RUNTIME_ALLOCATIONS,
            "TRUNCATE " + RUNTIME_ALLOCATIONS + " CASCADE")) {
      assertThatThrownBy(() -> context.dsl().execute(sql))
          .hasMessageContaining("immutable and retained");
    }
    assertThat(
            retainedRowWithXmin(
                context, RUNTIME_ALLOCATIONS, "request_id", fixture.request().requestId()))
        .isEqualTo(before);
    assertThat(context.dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void privateRuntimeConcurrentStaleSnapshotRequiresWholeTransactionRetryAndRetainsOneIdentity()
      throws Exception {
    var fixture = runtimeFixture(3600);
    var context = fixture.context();
    var owner = new GameSessionPrivateFreshRuntimeAllocationRepository(context.dsl());
    var held = new CountDownLatch(1);
    var snapshotEstablished = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var secondPid = new AtomicInteger();
    var secondFailure = new AtomicReference<Throwable>();
    var workers = Executors.newFixedThreadPool(2);
    try {
      var first =
          workers.submit(
              () ->
                  context
                      .serializableTransaction()
                      .execute(
                          status -> {
                            var result = owner.allocate(fixture.request());
                            held.countDown();
                            await(release);
                            return result;
                          }));
      await(held);
      var second =
          workers.submit(
              () -> {
                try {
                  context
                      .serializableTransaction()
                      .executeWithoutResult(
                          status -> {
                            assertThat(context.dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS)))
                                .isZero();
                            secondPid.set(
                                context
                                    .dsl()
                                    .fetchSingle("SELECT pg_backend_pid()")
                                    .get(0, Integer.class));
                            snapshotEstablished.countDown();
                            owner.allocate(fixture.request());
                          });
                } catch (Throwable failed) {
                  secondFailure.set(failed);
                }
              });
      await(snapshotEstablished);
      awaitAdvisoryLockWait(context, secondPid.get());
      release.countDown();
      var committed = Objects.requireNonNull(first.get(60, TimeUnit.SECONDS));
      second.get(60, TimeUnit.SECONDS);
      assertThat(sqlStateInCauseChain(secondFailure.get())).isEqualTo("40001");
      var row =
          retainedRowWithXmin(
              context, RUNTIME_ALLOCATIONS, "request_id", fixture.request().requestId());
      assertThat(allocateRuntime(context, fixture.request()))
          .isEqualTo(
              new GameSessionPrivateFreshRuntimeAllocationRepository.Allocation(
                  committed.snapshot(), true));
      assertThat(
              retainedRowWithXmin(
                  context, RUNTIME_ALLOCATIONS, "request_id", fixture.request().requestId()))
          .isEqualTo(row);
      assertThat(context.dsl().fetchCount(DSL.table(RUNTIME_ALLOCATIONS))).isEqualTo(1);
    } finally {
      release.countDown();
      workers.shutdown();
      if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void retainsPrivateRealmDisplayNameAtCanonicalCodePointAndUtf8Limits() {
    String displayName = "\ud83d\ude00".repeat(128);
    Context context =
        context(
            true,
            List.of(
                privatePolicy(
                    "private-playtest",
                    "source-world",
                    true,
                    false,
                    "ISOLATED",
                    "Source World",
                    displayName)));
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(request).preparation();
    assertThat(preparation.source().targetPolicy().source().policy().realmDisplayName())
        .isEqualTo(displayName);
    assertThat(
            context
                .read(request, preparation.playtestLifecycleId())
                .preparation()
                .source()
                .targetPolicy()
                .source()
                .policy()
                .realmDisplayName())
        .isEqualTo(displayName);
    var row =
        context
            .dsl()
            .fetchSingle(
                "SELECT char_length(target_policy_realm_display_name) AS characters, octet_length(target_policy_realm_display_name) AS bytes FROM game_session_initial_playtest_preparations WHERE request_id = ?",
                request.requestId());
    assertThat(row.get("characters", Integer.class)).isEqualTo(128);
    assertThat(row.get("bytes", Integer.class)).isEqualTo(512);
    var privateTarget =
        context.allocatePrivateTarget(
            new GameSessionPrivateFreshTargetRepository.Request(
                UUID.randomUUID(), request.requestId(), preparation.playtestLifecycleId()));
    assertThat(privateTarget.snapshot().realmDisplayName()).isEqualTo(displayName);
    assertThat(privateTarget.snapshot().visible()).isFalse();
    assertThat(privateTarget.snapshot().publicProduction()).isFalse();
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void allocatesFreshNonAdmissibleIdentityFromExactRetainedCanonicalSourceAndReplaysOriginal() {
    Context context = context(true);
    UUID legacyNamespace = UUID.randomUUID();
    context
        .dsl()
        .execute(
            "INSERT INTO gameplay_tenant_shared_playable_state_namespace (tenant_id, playable_state_namespace_id) VALUES (99174, ?)",
            legacyNamespace);
    var retainedBefore = context.retainedRows();
    Request request =
        context.request(
            UUID.randomUUID(),
            "private-playtest",
            context
                .now()
                .plusSeconds(3600)
                .with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789));
    Allocation allocated = context.prepare(request);
    assertThat(allocated.replayed()).isFalse();
    var preparation = allocated.preparation();
    assertThat(preparation.request()).isEqualTo(request);
    assertThat(preparation.requestDigest()).isEqualTo(request.requestDigest());
    byte[] retainedRequestBytes =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT request_preimage FROM " + TABLE + " WHERE request_id = ?",
                        request.requestId()))
            .get("request_preimage", byte[].class);
    // The 46-byte schema marker has a four-byte big-endian length, not decimal-colon framing.
    assertThat(retainedRequestBytes)
        .startsWith(new byte[] {0, 0, 0, 46})
        .containsExactly(request.canonicalPreimage());
    assertThat(preparation.playtestLifecycleId()).isNotEqualTo(new UUID(0, 0));
    assertThat(preparation.playableStateNamespaceId())
        .isNotEqualTo(new UUID(0, 0))
        .isNotEqualTo(preparation.playtestLifecycleId())
        .isNotEqualTo(context.catalog().playableStateNamespaceId())
        .isNotEqualTo(legacyNamespace);
    assertThat(preparation.playtestStateGeneration()).isEqualTo(1);
    assertThat(preparation.preparationState()).isEqualTo("PREPARING");
    assertThat(preparation.source().intakeOperationId())
        .isEqualTo(context.catalog().sourceIntakeReceipt().operationId());
    assertThat(preparation.source().sourceOperationId())
        .isEqualTo(context.catalog().sourceIntakeReceipt().source().operationId());
    assertThat(preparation.source().sourceEvidenceDigest())
        .isEqualTo(context.catalog().sourceIntakeReceipt().source().evidenceDigest());
    assertThat(preparation.source().canonicalVersionId())
        .isEqualTo(context.published().target().canonicalVersionId());
    assertThat(preparation.source().publishedReleaseBundleDigest())
        .isEqualTo(context.published().publishedReleaseBundleDigest());
    var selectedPolicy = preparation.source().targetPolicy();
    assertThat(selectedPolicy.source().policy().worldSlug()).isEqualTo(request.worldSlug());
    assertThat(selectedPolicy.source().policy().realmSlug()).isEqualTo(request.realmSlug());
    assertThat(selectedPolicy.source().policy().visible()).isTrue();
    assertThat(selectedPolicy.source().policy().publicProduction()).isFalse();
    assertThat(selectedPolicy.source().policy().stateScope())
        .isEqualTo(RealmEntryPolicy.StateScope.ISOLATED);
    assertThat(
            selectedPolicy
                .source()
                .policy()
                .canonicalJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .containsExactly(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT target_policy_canonical_json FROM "
                                + TABLE
                                + " WHERE request_id = ?",
                            request.requestId()))
                .get("target_policy_canonical_json", byte[].class));
    assertThat(selectedPolicy)
        .isEqualTo(
            context.published().policies().stream()
                .filter(
                    candidate ->
                        candidate.source().policy().worldSlug().equals(request.worldSlug())
                            && candidate.source().policy().realmSlug().equals(request.realmSlug()))
                .findFirst()
                .orElseThrow());
    assertThat(context.prepare(request)).isEqualTo(new Allocation(preparation, true));
    var snapshot = context.read(request, preparation.playtestLifecycleId());
    assertThat(snapshot.preparation()).isEqualTo(preparation);
    assertThat(snapshot.state()).isEqualTo(ReadState.INCOMPLETE);
    assertThat(snapshot.admissionAllowed()).isFalse();
    assertThat(context.count()).isEqualTo(1);
    assertThat(context.retainedRows()).usingRecursiveComparison().isEqualTo(retainedBefore);
  }

  @Test
  void
      allocatesPrivateCatalogIdentityFromExactPreparationAndReadsBackWithoutPublicOrRuntimeEffects() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    var publicCatalogBefore = context.publicCatalogRow();
    var namespaceRowsBefore = context.namespaceRows();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            preparation.request().requestId(),
            preparation.playtestLifecycleId());

    var allocated = context.allocatePrivateTarget(targetRequest);
    CanonicalPrivateFreshTargetSnapshot target = allocated.snapshot();
    assertThat(allocated.replayed()).isFalse();
    assertThat(target.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(target.tenantId()).isEqualTo(preparation.request().tenantId());
    assertThat(target.worldSlug()).isEqualTo(preparation.request().worldSlug());
    assertThat(target.realmSlug()).isEqualTo(preparation.request().realmSlug());
    assertThat(target.sourceCatalogRealmId()).isEqualTo(context.catalog().realmId());
    assertThat(target.realmId())
        .isNotEqualTo(target.sourceCatalogRealmId())
        .isNotEqualTo(preparation.source().targetPolicy().policyId());
    assertThat(target.playableStateNamespaceId()).isEqualTo(preparation.playableStateNamespaceId());
    assertThat(target.playtestLifecycleId()).isEqualTo(preparation.playtestLifecycleId());
    assertThat(target.playtestStateGeneration()).isEqualTo(1);
    assertThat(target.catalogRevision()).isEqualTo(1);
    assertThat(target.visible()).isFalse();
    assertThat(target.publicProduction()).isFalse();
    assertThat(target.stateScope()).isEqualTo("ISOLATED");
    assertThat(target.preparationState()).isEqualTo("PREPARING");
    assertThat(target.admissionAllowed()).isFalse();
    assertThat(target.realmDisplayName())
        .isEqualTo(preparation.source().targetPolicy().source().policy().realmDisplayName());
    assertThat(target.characterCreationPolicy())
        .isEqualTo(preparation.source().targetPolicy().source().policy().entryPolicy().name());
    assertThat(target.targetPolicyId()).isEqualTo(preparation.source().targetPolicy().policyId());
    assertThat(target.targetPolicyDigest())
        .isEqualTo(preparation.source().targetPolicy().policyDigest());
    assertThat(target.canonicalVersionId()).isEqualTo(preparation.source().canonicalVersionId());
    assertThat(target.publishedReleaseBundleRef())
        .isEqualTo(preparation.source().publishedReleaseBundleRef());
    assertThat(target.publishedReleaseBundleDigest())
        .isEqualTo(preparation.source().publishedReleaseBundleDigest());

    var independentlyRead =
        context
            .privateTargets()
            .readByPreparation(preparation.request().requestId(), preparation.playtestLifecycleId())
            .orElseThrow();
    assertThat(independentlyRead).isEqualTo(target);
    assertThat(context.allocatePrivateTarget(targetRequest))
        .isEqualTo(new GameSessionPrivateFreshTargetRepository.Allocation(target, true));
    assertThat(context.publicCatalogRow()).isEqualTo(publicCatalogBefore);
    assertThat(context.namespaceRows()).isEqualTo(namespaceRowsBefore);
    assertThat(context.catalogs().readVisiblePublicProductionSnapshots(NAMESPACE))
        .containsExactly(context.catalog());
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_published_realm_catalog_entry")))
        .isZero();
  }

  @Test
  void laterPreparationAndSelectionRemainAvailableAfterPriorPrivateCatalogAllocation() {
    Context context = context(true);
    Request firstRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var firstPreparation = context.prepare(firstRequest).preparation();
    var selectionRepository = new GameSessionCurrentPrivatePlaytestRepository(context.dsl());
    var scope =
        new GameSessionCurrentPrivatePlaytestRepository.Scope(
            NAMESPACE, firstRequest.tenantId(), firstRequest.worldSlug(), firstRequest.realmSlug());
    var firstSelectionRequest =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            scope,
            firstPreparation.request().requestId(),
            firstPreparation.playtestLifecycleId(),
            null,
            null);
    var firstSelection =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(status -> selectionRepository.select(firstSelectionRequest)));
    assertThat(firstSelection.replayed()).isFalse();
    assertThat(firstSelection.receipt().headRevision()).isEqualTo(1L);
    var firstPrivateTargetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            firstPreparation.request().requestId(),
            firstPreparation.playtestLifecycleId());
    var firstPrivateTarget = context.allocatePrivateTarget(firstPrivateTargetRequest);
    assertThat(firstPrivateTarget.replayed()).isFalse();

    String privateCatalogBefore = privateCatalogRow(context, firstPrivateTargetRequest.requestId());
    String firstPreparationBefore =
        retainedRowWithXmin(context, TABLE, "request_id", firstPreparation.request().requestId());
    String firstSelectionBefore =
        retainedRowWithXmin(
            context,
            "game_session_private_playtest_selections",
            "request_id",
            firstSelectionRequest.requestId());
    byte[] firstPreparationRequestBytes =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT request_preimage FROM " + TABLE + " WHERE request_id = ?",
                        firstPreparation.request().requestId()))
            .get("request_preimage", byte[].class);
    byte[] firstSelectionRequestBytes =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT request_preimage FROM game_session_private_playtest_selections WHERE request_id = ?",
                        firstSelectionRequest.requestId()))
            .get("request_preimage", byte[].class);
    var publicCatalogBefore = context.publicCatalogRow();
    String sourceIntakeBefore =
        retainedRowWithXmin(
            context,
            "game_session_authored_world_source_intake",
            "operation_id",
            context.catalog().sourceIntakeReceipt().operationId());
    String sourceBindingBefore =
        retainedRowWithXmin(
            context,
            "game_session_authored_world_tenant_source_binding",
            "canonical_tenant_id",
            context.catalog().tenantId());
    var namespaceRowsBefore = context.namespaceRows();

    Request laterRequest =
        context.request(
            UUID.randomUUID(), firstRequest.realmSlug(), context.now().plusSeconds(7200));
    var laterAllocation = context.prepare(laterRequest);
    assertThat(laterAllocation.replayed()).isFalse();
    var laterPreparation = laterAllocation.preparation();
    assertThat(laterPreparation.request().realmSlug()).isEqualTo(firstRequest.realmSlug());
    assertThat(laterPreparation.request().requestId())
        .isNotEqualTo(firstPreparation.request().requestId());
    assertThat(laterPreparation.playtestLifecycleId())
        .isNotEqualTo(firstPreparation.playtestLifecycleId());
    assertThat(laterPreparation.playableStateNamespaceId())
        .isNotEqualTo(firstPreparation.playableStateNamespaceId());
    assertThat(laterPreparation.playtestStateGeneration()).isEqualTo(1);

    var replacementSelectionRequest =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            scope,
            laterPreparation.request().requestId(),
            laterPreparation.playtestLifecycleId(),
            1L,
            firstPreparation.playtestLifecycleId());
    var replacementSelection =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(status -> selectionRepository.select(replacementSelectionRequest)));
    assertThat(replacementSelection.replayed()).isFalse();
    assertThat(replacementSelection.receipt().headRevision()).isEqualTo(2L);
    String replacementSelectionBeforeReplay =
        retainedRowWithXmin(
            context,
            "game_session_private_playtest_selections",
            "request_id",
            replacementSelectionRequest.requestId());
    String replacementHeadBeforeReplay =
        retainedRowWithXmin(
            context,
            "game_session_current_private_playtests",
            "selection_request_id",
            replacementSelectionRequest.requestId());
    var current =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(
                    status ->
                        selectionRepository
                            .readCurrent(
                                NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug())
                            .orElseThrow()));
    assertThat(current.selection()).isEqualTo(replacementSelection.receipt());
    assertThat(current.selectedPrepSnapshot().preparation()).isEqualTo(laterPreparation);
    assertThat(current.selectedPrepSnapshot().state()).isEqualTo(ReadState.INCOMPLETE);
    assertThat(current.admissionAllowed()).isFalse();
    assertThat(current.selectedPrepSnapshot().admissionAllowed()).isFalse();

    assertThat(context.prepare(firstRequest)).isEqualTo(new Allocation(firstPreparation, true));
    assertThat(context.read(firstRequest, firstPreparation.playtestLifecycleId()).preparation())
        .isEqualTo(firstPreparation);
    var firstSelectionReplay =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(status -> selectionRepository.select(firstSelectionRequest)));
    assertThat(firstSelectionReplay)
        .isEqualTo(
            new GameSessionCurrentPrivatePlaytestRepository.SelectionResult(
                firstSelection.receipt(), true));
    assertThat(context.allocatePrivateTarget(firstPrivateTargetRequest))
        .isEqualTo(
            new GameSessionPrivateFreshTargetRepository.Allocation(
                firstPrivateTarget.snapshot(), true));
    assertThat(context.prepare(laterRequest)).isEqualTo(new Allocation(laterPreparation, true));

    assertThat(context.count()).isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("game_session_private_playtest_selections")))
        .isEqualTo(2);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_private_playtest_selections",
                "request_id",
                firstSelectionRequest.requestId()))
        .isEqualTo(firstSelectionBefore);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_private_playtest_selections",
                "request_id",
                replacementSelectionRequest.requestId()))
        .isEqualTo(replacementSelectionBeforeReplay);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_current_private_playtests",
                "selection_request_id",
                replacementSelectionRequest.requestId()))
        .isEqualTo(replacementHeadBeforeReplay);
    assertThat(
            retainedRowWithXmin(
                context, TABLE, "request_id", firstPreparation.request().requestId()))
        .isEqualTo(firstPreparationBefore);
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT request_preimage FROM " + TABLE + " WHERE request_id = ?",
                            firstPreparation.request().requestId()))
                .get("request_preimage", byte[].class))
        .containsExactly(firstPreparationRequestBytes);
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT request_preimage FROM game_session_private_playtest_selections WHERE request_id = ?",
                            firstSelectionRequest.requestId()))
                .get("request_preimage", byte[].class))
        .containsExactly(firstSelectionRequestBytes);
    assertThat(privateCatalogRow(context, firstPrivateTargetRequest.requestId()))
        .isEqualTo(privateCatalogBefore);
    assertThat(context.publicCatalogRow()).isEqualTo(publicCatalogBefore);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_authored_world_source_intake",
                "operation_id",
                context.catalog().sourceIntakeReceipt().operationId()))
        .isEqualTo(sourceIntakeBefore);
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_authored_world_tenant_source_binding",
                "canonical_tenant_id",
                context.catalog().tenantId()))
        .isEqualTo(sourceBindingBefore);
    assertThat(context.namespaceRows()).usingRecursiveComparison().isEqualTo(namespaceRowsBefore);
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(2);
    assertThat(
            context
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_realm_catalog WHERE private_preparation_request_id IS NOT NULL")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
    var currentAfterReplay =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(
                    status ->
                        selectionRepository
                            .readCurrent(
                                NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug())
                            .orElseThrow()));
    assertThat(currentAfterReplay.selection()).isEqualTo(replacementSelection.receipt());
    assertThat(currentAfterReplay.admissionAllowed()).isFalse();
  }

  @Test
  void upgradesExistingPrivateAllocationFrom1059WithExactHeadAndRetainedOriginalEvidence() {
    var context =
        context(
            true,
            List.of(privatePolicy("private-playtest", "source-world", true, false, "ISOLATED")),
            "1059");
    var initialRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var initial = context.prepare(initialRequest).preparation();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(), initialRequest.requestId(), initial.playtestLifecycleId());
    var target = context.allocatePrivateTarget(targetRequest).snapshot();
    var selections = new GameSessionCurrentPrivatePlaytestRepository(context.dsl());
    var scope =
        new GameSessionCurrentPrivatePlaytestRepository.Scope(
            NAMESPACE,
            initialRequest.tenantId(),
            initialRequest.worldSlug(),
            initialRequest.realmSlug());
    var selectionRequest =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            scope,
            initialRequest.requestId(),
            initial.playtestLifecycleId(),
            null,
            null);
    var selection =
        Objects.requireNonNull(
            context.transaction().execute(status -> selections.select(selectionRequest)));
    var catalogOwner =
        new GameSessionRuntimeFamilyCatalogRepository(
            context.dsl(),
            context.transaction().getTransactionManager(),
            context.schema(),
            NAMESPACE);
    var captureRequest =
        new net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Request(
            UUID.randomUUID(), NAMESPACE);
    var catalogBefore = catalogOwner.capture(captureRequest);
    assertThat(catalogBefore.catalog().catalogEpoch()).isEqualTo(3);
    assertThat(catalogOwner.readExact(captureRequest)).isEqualTo(catalogBefore);
    var oldRows = new LinkedHashMap<String, List<String>>();
    for (String table :
        List.of(
            "game_session_canonical_realm_catalog",
            TABLE,
            "game_session_private_playtest_selections",
            "game_session_current_private_playtests",
            "game_session_authored_world_source_intake",
            "game_session_authored_world_tenant_source_binding",
            "gameplay_tenant_shared_playable_state_namespace",
            "game_session_runtime_family_catalog_snapshot")) {
      oldRows.put(
          table,
          context
              .dsl()
              .fetch(
                  "SELECT to_jsonb(r)::TEXT || ':' || xmin::TEXT FROM " + table + " r ORDER BY 1")
              .getValues(0, String.class));
    }
    var oldEpochs =
        context
            .dsl()
            .fetch(
                "SELECT to_jsonb(e)::TEXT || ':' || xmin::TEXT FROM game_session_runtime_family_catalog_epoch e WHERE catalog_epoch <= 3 ORDER BY catalog_epoch")
            .getValues(0, String.class);
    assertThat(oldEpochs).hasSize(3);
    var oldHistory =
        context
            .dsl()
            .fetch(
                "SELECT to_jsonb(h)::TEXT || ':' || xmin::TEXT FROM flyway_schema_history h ORDER BY installed_rank")
            .getValues(0, String.class);
    assertThat(
            context
                .dsl()
                .fetchSingle(
                    "SELECT to_regclass('game_session_current_private_catalog_revisions') IS NULL")
                .get(0, Boolean.class))
        .isTrue();

    CanonicalGameplayIssuerTestFixtures.migrationConfiguration(
            context.dataSource(),
            context.schema(),
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .target("1060")
        .load()
        .migrate();

    for (var retained : oldRows.entrySet()) {
      assertThat(
              context
                  .dsl()
                  .fetch(
                      "SELECT to_jsonb(r)::TEXT || ':' || xmin::TEXT FROM "
                          + retained.getKey()
                          + " r ORDER BY 1")
                  .getValues(0, String.class))
          .as("retained upgrade rows: %s", retained.getKey())
          .isEqualTo(retained.getValue());
    }
    assertThat(
            context
                .dsl()
                .fetch(
                    "SELECT to_jsonb(e)::TEXT || ':' || xmin::TEXT FROM game_session_runtime_family_catalog_epoch e WHERE catalog_epoch <= 3 ORDER BY catalog_epoch")
                .getValues(0, String.class))
        .isEqualTo(oldEpochs);
    var upgradedHistory =
        context
            .dsl()
            .fetch(
                "SELECT to_jsonb(h)::TEXT || ':' || xmin::TEXT FROM flyway_schema_history h ORDER BY installed_rank")
            .getValues(0, String.class);
    assertThat(upgradedHistory).hasSize(oldHistory.size() + 1);
    assertThat(upgradedHistory.subList(0, oldHistory.size())).isEqualTo(oldHistory);
    var installed =
        context
            .dsl()
            .fetchSingle(
                "SELECT type, script, checksum, success FROM flyway_schema_history WHERE version = '1060'");
    assertThat(installed.get("type", String.class)).isEqualTo("SQL");
    assertThat(installed.get("script", String.class))
        .isEqualTo("V1060__private_catalog_lifecycle_revisions.sql");
    assertThat(installed.get("checksum", Integer.class)).isNotNull();
    assertThat(installed.get("success", Boolean.class)).isTrue();
    var head =
        context
            .dsl()
            .fetchSingle(
                "SELECT * FROM " + REVISION_HEAD + " WHERE realm_id = ?", target.realmId());
    assertThat(head.get("catalog_revision", Long.class)).isEqualTo(1);
    assertThat(head.get("revision_request_id", UUID.class)).isEqualTo(targetRequest.requestId());
    assertThat(head.get("preparation_request_id", UUID.class))
        .isEqualTo(initialRequest.requestId());
    assertThat(head.get("playtest_lifecycle_id", UUID.class))
        .isEqualTo(initial.playtestLifecycleId());
    assertThat(context.dsl().fetchCount(DSL.table(REVISION_HEAD))).isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table(REVISION_HISTORY))).isZero();
    assertThat(context.prepare(initialRequest)).isEqualTo(new Allocation(initial, true));
    assertThat(context.allocatePrivateTarget(targetRequest))
        .isEqualTo(new GameSessionPrivateFreshTargetRepository.Allocation(target, true));
    var originalSelectionReplay =
        Objects.requireNonNull(
            context.transaction().execute(status -> selections.select(selectionRequest)));
    assertThat(originalSelectionReplay)
        .isEqualTo(
            new GameSessionCurrentPrivatePlaytestRepository.SelectionResult(
                selection.receipt(), true));
    var revisions = new GameSessionPrivateFreshCatalogRevisionRepository(context.dsl());
    assertThat(revisions.readCurrent(target.realmId())).contains(target);
    var currentCatalog =
        catalogOwner.capture(
            new net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Request(
                UUID.randomUUID(), NAMESPACE));
    assertThat(currentCatalog.catalog().catalogEpoch()).isEqualTo(4);
    assertThat(catalogOwner.readExact(currentCatalog.request())).isEqualTo(currentCatalog);
    assertThatThrownBy(() -> catalogOwner.readExact(captureRequest))
        .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    var laterRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var later = context.prepare(laterRequest).preparation();
    var advanced =
        context.advancePrivateRevision(
            new AdvanceRequest(
                UUID.randomUUID(),
                target.realmId(),
                laterRequest.requestId(),
                later.playtestLifecycleId(),
                1,
                initial.playtestLifecycleId()));
    assertThat(advanced.snapshot().realmId()).isEqualTo(target.realmId());
    assertThat(advanced.snapshot().realmSlug()).isEqualTo(target.realmSlug());
    assertThat(advanced.snapshot().catalogRevision()).isEqualTo(2);
    assertThat(advanced.snapshot().playableStateNamespaceId())
        .isEqualTo(later.playableStateNamespaceId());
    assertThat(advanced.snapshot().admissionAllowed()).isFalse();
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void advancesPrivateCatalogLifecycleWithoutChangingStableRealmOrOriginalEvidence() {
    var fixture = privateRevisionFixture(3600);
    var context = fixture.context();
    var revisions = new GameSessionPrivateFreshCatalogRevisionRepository(context.dsl());
    var selections = new GameSessionCurrentPrivatePlaytestRepository(context.dsl());
    var scope =
        new GameSessionCurrentPrivatePlaytestRepository.Scope(
            NAMESPACE,
            context.catalog().tenantId(),
            fixture.initialRequest().worldSlug(),
            fixture.initialRequest().realmSlug());
    var firstSelectionRequest =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            scope,
            fixture.initialRequest().requestId(),
            fixture.initialPreparation().playtestLifecycleId(),
            null,
            null);
    var firstSelection =
        Objects.requireNonNull(
            context.transaction().execute(status -> selections.select(firstSelectionRequest)));
    String selectionBefore =
        retainedRowWithXmin(
            context,
            "game_session_private_playtest_selections",
            "request_id",
            firstSelectionRequest.requestId());
    var retainedBefore = context.retainedRows();
    var namespacesBefore = context.namespaceRows();
    var publicBefore = context.publicCatalogRow();
    String initialCatalogBefore =
        privateCatalogRow(context, fixture.initialTargetRequest().requestId());
    String initialPreparationBefore =
        retainedRowWithXmin(context, TABLE, "request_id", fixture.initialRequest().requestId());
    String laterPreparationBefore =
        retainedRowWithXmin(context, TABLE, "request_id", fixture.laterRequest().requestId());

    var advanced = context.advancePrivateRevision(fixture.advanceRequest());
    var target = advanced.snapshot();
    assertThat(advanced.replayed()).isFalse();
    assertThat(target.realmId()).isEqualTo(fixture.initialTarget().realmId());
    assertThat(target.realmSlug()).isEqualTo(fixture.initialTarget().realmSlug());
    assertThat(target.catalogRevision()).isEqualTo(2);
    assertThat(target.preparationRequestId()).isEqualTo(fixture.laterRequest().requestId());
    assertThat(target.playtestLifecycleId())
        .isEqualTo(fixture.laterPreparation().playtestLifecycleId())
        .isNotEqualTo(fixture.initialTarget().playtestLifecycleId());
    assertThat(target.playableStateNamespaceId())
        .isEqualTo(fixture.laterPreparation().playableStateNamespaceId())
        .isNotEqualTo(fixture.initialTarget().playableStateNamespaceId());
    assertThat(target.playtestStateGeneration()).isEqualTo(1);
    assertThat(target.visible()).isFalse();
    assertThat(target.publicProduction()).isFalse();
    assertThat(target.preparationState()).isEqualTo("PREPARING");
    assertThat(target.admissionAllowed()).isFalse();
    var laterSelectionRequest =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            scope,
            fixture.laterRequest().requestId(),
            fixture.laterPreparation().playtestLifecycleId(),
            1L,
            fixture.initialPreparation().playtestLifecycleId());
    var laterSelection =
        Objects.requireNonNull(
            context.transaction().execute(status -> selections.select(laterSelectionRequest)));
    assertThat(laterSelection.receipt().headRevision()).isEqualTo(2);
    var currentSelection =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(
                    status ->
                        selections
                            .readCurrent(
                                NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug())
                            .orElseThrow()));
    assertThat(currentSelection.selection()).isEqualTo(laterSelection.receipt());
    assertThat(currentSelection.selectedPrepSnapshot().preparation())
        .isEqualTo(fixture.laterPreparation());
    assertThat(currentSelection.admissionAllowed()).isFalse();
    assertThat(revisions.readByRequest(fixture.advanceRequest().requestId())).contains(target);
    assertThat(
            revisions.readByPreparation(
                fixture.laterRequest().requestId(), target.playtestLifecycleId()))
        .contains(target);
    assertThat(revisions.readCurrent(target.realmId())).contains(target);
    var currentWorldTarget =
        Objects.requireNonNull(
            context
                .serializableTransaction()
                .execute(
                    status ->
                        revisions.readForWorldPreparation(
                            fixture.laterRequest().requestId(), target.playtestLifecycleId())));
    assertThat(currentWorldTarget).contains(target);
    assertThat(context.prepare(fixture.initialRequest()))
        .isEqualTo(new Allocation(fixture.initialPreparation(), true));
    assertThat(context.prepare(fixture.laterRequest()))
        .isEqualTo(new Allocation(fixture.laterPreparation(), true));
    assertThat(context.allocatePrivateTarget(fixture.initialTargetRequest()))
        .isEqualTo(
            new GameSessionPrivateFreshTargetRepository.Allocation(fixture.initialTarget(), true));
    var firstSelectionReplay =
        Objects.requireNonNull(
            context.transaction().execute(status -> selections.select(firstSelectionRequest)));
    assertThat(firstSelectionReplay)
        .isEqualTo(
            new GameSessionCurrentPrivatePlaytestRepository.SelectionResult(
                firstSelection.receipt(), true));
    assertThat(context.advancePrivateRevision(fixture.advanceRequest()))
        .isEqualTo(new GameSessionPrivateFreshTargetRepository.Allocation(target, true));
    assertThat(
            retainedRowWithXmin(
                context,
                "game_session_private_playtest_selections",
                "request_id",
                firstSelectionRequest.requestId()))
        .isEqualTo(selectionBefore);
    assertThat(
            retainedRowWithXmin(context, TABLE, "request_id", fixture.initialRequest().requestId()))
        .isEqualTo(initialPreparationBefore);
    assertThat(
            retainedRowWithXmin(context, TABLE, "request_id", fixture.laterRequest().requestId()))
        .isEqualTo(laterPreparationBefore);
    assertThat(privateCatalogRow(context, fixture.initialTargetRequest().requestId()))
        .isEqualTo(initialCatalogBefore);
    assertThat(context.retainedRows()).isEqualTo(retainedBefore);
    assertThat(context.namespaceRows()).isEqualTo(namespacesBefore);
    assertThat(context.publicCatalogRow()).isEqualTo(publicBefore);
    assertThat(context.dsl().fetchCount(DSL.table(REVISION_HISTORY))).isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table(REVISION_HEAD))).isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void privateRevisionConflictsDoNotMutateAndHistoricalReplaySurvivesAnotherAdvancement() {
    var fixture = privateRevisionFixture(3600);
    var context = fixture.context();
    var revisions = new GameSessionPrivateFreshCatalogRevisionRepository(context.dsl());
    var second = context.advancePrivateRevision(fixture.advanceRequest()).snapshot();
    var thirdRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var third = context.prepare(thirdRequest).preparation();
    var anotherRealmRequest =
        context.request(UUID.randomUUID(), "other-playtest", context.now().plusSeconds(3600));
    var anotherRealm = context.prepare(anotherRealmRequest).preparation();
    var before = privateRevisionRows(context);
    var invalid =
        List.of(
            new AdvanceRequest(
                UUID.randomUUID(),
                second.realmId(),
                thirdRequest.requestId(),
                third.playtestLifecycleId(),
                1,
                second.playtestLifecycleId()),
            new AdvanceRequest(
                UUID.randomUUID(),
                second.realmId(),
                thirdRequest.requestId(),
                third.playtestLifecycleId(),
                2,
                fixture.initialPreparation().playtestLifecycleId()),
            new AdvanceRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                thirdRequest.requestId(),
                third.playtestLifecycleId(),
                2,
                second.playtestLifecycleId()),
            new AdvanceRequest(
                UUID.randomUUID(),
                second.realmId(),
                anotherRealmRequest.requestId(),
                anotherRealm.playtestLifecycleId(),
                2,
                second.playtestLifecycleId()),
            new AdvanceRequest(
                fixture.advanceRequest().requestId(),
                second.realmId(),
                thirdRequest.requestId(),
                third.playtestLifecycleId(),
                2,
                second.playtestLifecycleId()),
            new AdvanceRequest(
                UUID.randomUUID(),
                second.realmId(),
                fixture.laterRequest().requestId(),
                second.playtestLifecycleId(),
                2,
                second.playtestLifecycleId()));
    for (var request : invalid) {
      assertThatThrownBy(() -> context.advancePrivateRevision(request))
          .isInstanceOf(RuntimeException.class);
      assertThat(privateRevisionRows(context)).isEqualTo(before);
    }
    var thirdAdvance =
        new AdvanceRequest(
            UUID.randomUUID(),
            second.realmId(),
            thirdRequest.requestId(),
            third.playtestLifecycleId(),
            2,
            second.playtestLifecycleId());
    var thirdTarget = context.advancePrivateRevision(thirdAdvance).snapshot();
    assertThat(thirdTarget.catalogRevision()).isEqualTo(3);
    assertThat(thirdTarget.realmId()).isEqualTo(second.realmId());
    var afterThird = privateRevisionRows(context);
    assertThat(context.advancePrivateRevision(fixture.advanceRequest()))
        .isEqualTo(new GameSessionPrivateFreshTargetRepository.Allocation(second, true));
    assertThat(revisions.readByRequest(fixture.advanceRequest().requestId())).contains(second);
    assertThat(
            revisions.readByPreparation(
                fixture.laterRequest().requestId(), second.playtestLifecycleId()))
        .contains(second);
    assertThat(revisions.readCurrent(second.realmId())).contains(thirdTarget);
    assertThat(
            context
                .privateTargets()
                .readByPreparation(
                    fixture.initialRequest().requestId(),
                    fixture.initialPreparation().playtestLifecycleId()))
        .contains(fixture.initialTarget());
    assertThat(privateRevisionRows(context)).isEqualTo(afterThird);
    assertThat(context.dsl().fetchCount(DSL.table(REVISION_HISTORY))).isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table(REVISION_HEAD))).isEqualTo(1);
  }

  @Test
  void privateRevisionSqlGuardsRejectForgedFramesAndMutationOfRetainedHistory() {
    var fixture = privateRevisionFixture(3600);
    var context = fixture.context();
    var second = context.advancePrivateRevision(fixture.advanceRequest()).snapshot();
    var nextRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var next = context.prepare(nextRequest).preparation();
    var intended =
        new AdvanceRequest(
            UUID.randomUUID(),
            second.realmId(),
            nextRequest.requestId(),
            next.playtestLifecycleId(),
            2,
            second.playtestLifecycleId());
    var before = privateRevisionRows(context);
    String initialBefore = privateCatalogRow(context, fixture.initialTargetRequest().requestId());
    String sql =
        """
        INSERT INTO game_session_private_catalog_revision_history (
            realm_id, catalog_revision, request_id, request_preimage, request_digest, receipt_digest,
            preparation_request_id, playtest_lifecycle_id, playable_state_namespace_id,
            expected_catalog_revision, expected_current_lifecycle_id)
        VALUES (?, 3, ?, ?, ?, ?, ?, ?, ?, 2, ?)
        """;
    byte[] validPreimage = intended.canonicalPreimage(next.requestDigest());
    byte[] wrongPreimage =
        frame(
            "game-session-private-catalog-revision-request/v1",
            intended.requestId().toString(),
            second.realmId().toString(),
            nextRequest.requestId().toString(),
            UUID.randomUUID().toString(),
            "2",
            second.playtestLifecycleId().toString(),
            next.requestDigest());
    Throwable wrongFrame =
        catchThrowable(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    sql,
                                    second.realmId(),
                                    intended.requestId(),
                                    wrongPreimage,
                                    sha256(wrongPreimage),
                                    "sha256:" + "0".repeat(64),
                                    nextRequest.requestId(),
                                    next.playtestLifecycleId(),
                                    next.playableStateNamespaceId(),
                                    second.playtestLifecycleId())));
    assertThat(sqlStateInCauseChain(wrongFrame)).isEqualTo("23514");
    assertThat(databaseFailureSummary(wrongFrame).toLowerCase(java.util.Locale.ROOT))
        .contains("request");
    assertThat(privateRevisionRows(context)).isEqualTo(before);

    Throwable wrongReceipt =
        catchThrowable(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    sql,
                                    second.realmId(),
                                    intended.requestId(),
                                    validPreimage,
                                    intended.requestDigest(next.requestDigest()),
                                    "sha256:" + "0".repeat(64),
                                    nextRequest.requestId(),
                                    next.playtestLifecycleId(),
                                    next.playableStateNamespaceId(),
                                    second.playtestLifecycleId())));
    assertThat(sqlStateInCauseChain(wrongReceipt)).isEqualTo("23514");
    assertThat(databaseFailureSummary(wrongReceipt).toLowerCase(java.util.Locale.ROOT))
        .contains("receipt");
    assertThat(privateRevisionRows(context)).isEqualTo(before);

    var reusedPreparation =
        new AdvanceRequest(
            UUID.randomUUID(),
            second.realmId(),
            fixture.initialRequest().requestId(),
            fixture.initialPreparation().playtestLifecycleId(),
            2,
            second.playtestLifecycleId());
    byte[] reusedPreimage =
        reusedPreparation.canonicalPreimage(fixture.initialPreparation().requestDigest());
    Throwable wrongPreparation =
        catchThrowable(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    sql,
                                    second.realmId(),
                                    reusedPreparation.requestId(),
                                    reusedPreimage,
                                    reusedPreparation.requestDigest(
                                        fixture.initialPreparation().requestDigest()),
                                    "sha256:" + "0".repeat(64),
                                    fixture.initialRequest().requestId(),
                                    fixture.initialPreparation().playtestLifecycleId(),
                                    fixture.initialPreparation().playableStateNamespaceId(),
                                    second.playtestLifecycleId())));
    assertThat(sqlStateInCauseChain(wrongPreparation)).isEqualTo("23514");
    assertThat(databaseFailureSummary(wrongPreparation).toLowerCase(java.util.Locale.ROOT))
        .contains("reuses original");
    assertThat(privateRevisionRows(context)).isEqualTo(before);

    for (String mutation :
        List.of(
            "UPDATE " + REVISION_HISTORY + " SET receipt_digest = receipt_digest",
            "DELETE FROM " + REVISION_HISTORY,
            "TRUNCATE " + REVISION_HISTORY,
            "UPDATE " + REVISION_HEAD + " SET catalog_revision = catalog_revision - 1",
            "DELETE FROM " + REVISION_HEAD,
            "TRUNCATE " + REVISION_HEAD,
            "UPDATE game_session_canonical_realm_catalog SET receipt_digest = receipt_digest",
            "DELETE FROM game_session_canonical_realm_catalog")) {
      Throwable refused =
          catchThrowable(
              () ->
                  context
                      .serializableTransaction()
                      .executeWithoutResult(status -> context.dsl().execute(mutation)));
      assertThat(refused).as("retained-history mutation: %s", mutation).isNotNull();
      assertThat(sqlStateInCauseChain(refused))
          .as("retained-history mutation: %s", mutation)
          .isEqualTo("23514");
      assertThat(privateRevisionRows(context)).isEqualTo(before);
      assertThat(privateCatalogRow(context, fixture.initialTargetRequest().requestId()))
          .isEqualTo(initialBefore);
    }
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void committedPrivateRevisionReplaysAfterExpiryWhileNewAdvancementIsRefused() {
    var fixture = privateRevisionFixture(5);
    var context = fixture.context();
    var revisions = new GameSessionPrivateFreshCatalogRevisionRepository(context.dsl());
    var second = context.advancePrivateRevision(fixture.advanceRequest()).snapshot();
    var before = privateRevisionRows(context);
    awaitExpiry(context, fixture.laterRequest().forkExpiresAt());
    assertThat(context.read(fixture.laterRequest(), second.playtestLifecycleId()).state())
        .isEqualTo(ReadState.EXPIRED);
    assertThat(context.advancePrivateRevision(fixture.advanceRequest()))
        .isEqualTo(new GameSessionPrivateFreshTargetRepository.Allocation(second, true));
    assertThat(revisions.readByRequest(fixture.advanceRequest().requestId())).contains(second);
    assertThat(
            revisions.readByPreparation(
                fixture.laterRequest().requestId(), second.playtestLifecycleId()))
        .contains(second);
    assertThat(second.admissionAllowed()).isFalse();
    var expiredRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    var expiredPreparation = context.prepare(expiredRequest).preparation();
    awaitExpiry(context, expiredRequest.forkExpiresAt());
    var expiredAdvance =
        new AdvanceRequest(
            UUID.randomUUID(),
            second.realmId(),
            expiredRequest.requestId(),
            expiredPreparation.playtestLifecycleId(),
            2,
            second.playtestLifecycleId());
    assertThatThrownBy(() -> context.advancePrivateRevision(expiredAdvance))
        .isInstanceOf(RuntimeException.class);
    assertThat(revisions.readByRequest(expiredAdvance.requestId())).isEmpty();
    assertThat(privateRevisionRows(context)).isEqualTo(before);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void privateRevisionCommitExpiryRollsBackHistoryAndHeadTogether() {
    var fixture = privateRevisionFixture(5);
    var context = fixture.context();
    var revisions = new GameSessionPrivateFreshCatalogRevisionRepository(context.dsl());
    var before = privateRevisionRows(context);
    Throwable failure =
        catchThrowable(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status -> {
                          var allocated = revisions.advance(fixture.advanceRequest());
                          assertThat(allocated.snapshot().catalogRevision()).isEqualTo(2);
                          awaitExpiry(context, fixture.laterRequest().forkExpiresAt());
                        }));
    assertThat(failure).isNotNull();
    assertThat(sqlStateInCauseChain(failure)).isEqualTo("23514");
    assertThat(privateRevisionRows(context)).isEqualTo(before);
    assertThat(revisions.readByRequest(fixture.advanceRequest().requestId())).isEmpty();
    assertThat(context.allocatePrivateTarget(fixture.initialTargetRequest()).snapshot())
        .isEqualTo(fixture.initialTarget());
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void privateCatalogRequestRejectsChangedPreparationLifecycleAndSecondRequestForOnePreparation() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    var requestId = UUID.randomUUID();
    var original =
        new GameSessionPrivateFreshTargetRepository.Request(
            requestId, preparation.request().requestId(), preparation.playtestLifecycleId());
    var allocation = context.allocatePrivateTarget(original);

    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        requestId, UUID.randomUUID(), preparation.playtestLifecycleId())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        requestId, preparation.request().requestId(), UUID.randomUUID())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        UUID.randomUUID(),
                        preparation.request().requestId(),
                        preparation.playtestLifecycleId())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(
            GameSessionPrivateFreshTargetRepository.PreparationUnavailableException.class);
    assertThat(context.allocatePrivateTarget(original).snapshot()).isEqualTo(allocation.snapshot());
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(2);
  }

  @Test
  void committedPrivateCatalogOutcomeReplaysAfterExpiryWithoutAllocatingOrRenewingAdmission() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    var preparation = context.prepare(preparationRequest).preparation();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            preparation.request().requestId(),
            preparation.playtestLifecycleId());
    var original = context.allocatePrivateTarget(targetRequest);
    assertThat(original.replayed()).isFalse();
    String retainedRow =
        context
            .dsl()
            .fetchSingle(
                "SELECT to_jsonb(c)::text||':'||xmin::text FROM game_session_canonical_realm_catalog c WHERE creation_request_id=?",
                targetRequest.requestId())
            .get(0, String.class);
    awaitExpiry(context, preparationRequest.forkExpiresAt());
    assertThat(context.read(preparationRequest, preparation.playtestLifecycleId()).state())
        .isEqualTo(ReadState.EXPIRED);

    var retry = context.allocatePrivateTarget(targetRequest);
    assertThat(retry.replayed()).isTrue();
    assertThat(retry.snapshot()).isEqualTo(original.snapshot());
    assertThat(retry.snapshot().admissionAllowed()).isFalse();
    assertThat(
            context
                .dsl()
                .fetchSingle(
                    "SELECT to_jsonb(c)::text||':'||xmin::text FROM game_session_canonical_realm_catalog c WHERE creation_request_id=?",
                    targetRequest.requestId())
                .get(0, String.class))
        .isEqualTo(retainedRow);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        UUID.randomUUID(),
                        preparation.request().requestId(),
                        preparation.playtestLifecycleId())))
        .isInstanceOf(
            GameSessionPrivateFreshTargetRepository.PreparationUnavailableException.class);
    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        targetRequest.requestId(),
                        preparation.request().requestId(),
                        UUID.randomUUID())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.IdempotencyConflictException.class);
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void expiredPreparationCannotAllocatePrivateCatalogIdentity() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    var preparation = context.prepare(preparationRequest).preparation();
    awaitExpiry(context, preparationRequest.forkExpiresAt());

    assertThatThrownBy(
            () ->
                context.allocatePrivateTarget(
                    new GameSessionPrivateFreshTargetRepository.Request(
                        UUID.randomUUID(),
                        preparation.request().requestId(),
                        preparation.playtestLifecycleId())))
        .isInstanceOf(GameSessionPrivateFreshTargetRepository.PreparationUnavailableException.class)
        .hasMessageContaining("expired");
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);
  }

  @Test
  void privateCatalogSqlGuardRejectsReplacementNamespaceAndRollsBackInsert() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    UUID requestId = UUID.randomUUID();
    UUID wrongNamespace = UUID.randomUUID();
    byte[] requestPreimage =
        frame(
            "game-session-private-fresh-catalog-request/v1",
            requestId.toString(),
            preparation.request().requestId().toString(),
            preparation.playtestLifecycleId().toString(),
            preparation.requestDigest());
    String requestDigest = sha256(requestPreimage);
    String sql =
        """
        INSERT INTO game_session_canonical_realm_catalog (
            target_namespace, canonical_tenant_id, tenant_slug, world_slug, realm_id, realm_slug,
            realm_display_name, visible, public_production, state_scope, playable_state_namespace_id,
            character_creation_policy, catalog_revision, creation_request_id, request_digest,
            receipt_digest, source_intake_operation_id, source_intake_schema_version,
            source_intake_request_id, source_intake_request_digest, source_intake_receipt_digest,
            source_schema_version, source_registration_request_id, source_operation_id,
            source_request_digest, source_world_display_name, source_game_row_id,
            source_game_tenant_key, source_provenance_kind, source_evidence_digest,
            complete_policy_set_evidence, private_target_request_preimage,
            private_preparation_request_id, private_playtest_lifecycle_id,
            private_preparation_request_digest, private_state_generation,
            private_source_catalog_realm_id, private_source_catalog_request_id,
            private_source_catalog_revision, private_source_catalog_receipt_digest,
            private_target_policy_id, private_target_policy_digest,
            private_target_policy_source_commit_id, private_target_policy_source_revision_id,
            private_target_policy_logical_revision_id, private_canonical_version_id,
            private_published_release_bundle_ref, private_published_release_bundle_digest,
            private_source_policy_set_digest, private_source_policy_carrier_digest)
        SELECT p.target_namespace, p.canonical_tenant_id, source.tenant_slug, p.world_slug,
            gen_random_uuid(), p.realm_slug, p.target_policy_realm_display_name, false, false,
            'ISOLATED', ?, p.target_policy_entry_policy, 1, ?, ?,
            'sha256:0000000000000000000000000000000000000000000000000000000000000000',
            source.source_intake_operation_id, source.source_intake_schema_version,
            source.source_intake_request_id, source.source_intake_request_digest,
            source.source_intake_receipt_digest, source.source_schema_version,
            source.source_registration_request_id, source.source_operation_id,
            source.source_request_digest, source.source_world_display_name, source.source_game_row_id,
            source.source_game_tenant_key, source.source_provenance_kind, source.source_evidence_digest,
            source.complete_policy_set_evidence, ?, p.request_id, p.playtest_lifecycle_id,
            p.request_digest, p.playtest_state_generation, p.source_catalog_realm_id,
            p.source_catalog_request_id, p.source_catalog_revision, p.source_catalog_receipt_digest,
            p.target_policy_id, p.target_policy_digest, p.target_policy_source_commit_id,
            p.target_policy_source_revision_id, p.target_policy_logical_revision_id,
            p.canonical_version_id, p.published_release_bundle_ref,
            p.published_release_bundle_digest, p.source_policy_set_digest,
            p.source_policy_carrier_digest
        FROM game_session_initial_playtest_preparations p
        JOIN game_session_canonical_realm_catalog source
          ON source.target_namespace = p.target_namespace
         AND source.canonical_tenant_id = p.canonical_tenant_id
         AND source.realm_id = p.source_catalog_realm_id
        WHERE p.request_id = ? AND p.playtest_lifecycle_id = ?
        """;

    assertThatThrownBy(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    sql,
                                    wrongNamespace,
                                    requestId,
                                    requestDigest,
                                    requestPreimage,
                                    preparation.request().requestId(),
                                    preparation.playtestLifecycleId())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining(
            "Private FRESH catalog row differs from its exact retained preparation");
    // A NULL build reference must not turn the SQL guard and receipt conjunction into UNKNOWN.
    // This uses the real guarded INSERT, a matching namespace and the same rolled-back intent.
    String nullBuildSql =
        sql.replace(
            "p.canonical_version_id, p.published_release_bundle_ref,",
            "p.canonical_version_id, NULL,");
    assertThat(nullBuildSql).isNotEqualTo(sql);
    assertThatThrownBy(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    nullBuildSql,
                                    preparation.playableStateNamespaceId(),
                                    requestId,
                                    requestDigest,
                                    requestPreimage,
                                    preparation.request().requestId(),
                                    preparation.playtestLifecycleId())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("chk_gs_private_fresh_catalog_binding");
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
  }

  @Test
  void canonicalIntakeGuardRejectsPrivateForgedTenantSlugWithRecomputedReceipt() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    UUID creationRequestId = UUID.randomUUID();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            creationRequestId,
            preparation.request().requestId(),
            preparation.playtestLifecycleId());
    var publicCatalogBefore = context.publicCatalogRow();
    DetachedCatalogRow rolledBackPrivateRow =
        Objects.requireNonNull(
            context
                .serializableTransaction()
                .execute(
                    status -> {
                      context.privateTargets().allocate(targetRequest);
                      var genuine =
                          Objects.requireNonNull(
                              context
                                  .dsl()
                                  .fetchOne(
                                      "SELECT * FROM game_session_canonical_realm_catalog WHERE creation_request_id = ?",
                                      creationRequestId));
                      DetachedCatalogRow detachedValues =
                          new DetachedCatalogRow(
                              new LinkedHashMap<>(genuine.intoMap()),
                              List.copyOf(java.util.Arrays.asList(genuine.fields())));
                      status.setRollbackOnly();
                      return detachedValues;
                    }));
    assertThat(
            context
                .privateTargets()
                .readByPreparation(
                    preparation.request().requestId(), preparation.playtestLifecycleId()))
        .isEmpty();
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);

    String sourceTenantSlug =
        context
            .dsl()
            .fetchSingle(
                "SELECT tenant_slug FROM game_session_canonical_realm_catalog WHERE realm_id = ?",
                context.catalog().realmId())
            .get(0, String.class);
    assertThat(rolledBackPrivateRow.values().get("tenant_slug")).isEqualTo(sourceTenantSlug);
    String forgedTenantSlug = "forged-" + sourceTenantSlug;
    assertThat(forgedTenantSlug).isNotEqualTo(sourceTenantSlug);
    String forgedReceiptDigest =
        privateCatalogReceiptDigest(rolledBackPrivateRow.values(), forgedTenantSlug);
    assertThat(forgedReceiptDigest)
        .startsWith("sha256:")
        .isNotEqualTo(rolledBackPrivateRow.values().get("receipt_digest"));

    Map<String, Object> forged = new LinkedHashMap<>(rolledBackPrivateRow.values());
    forged.put("tenant_slug", forgedTenantSlug);
    forged.put("receipt_digest", forgedReceiptDigest);
    Field<?>[] insertFields = new Field<?>[rolledBackPrivateRow.fields().size()];
    Object[] values = new Object[insertFields.length];
    for (int index = 0; index < insertFields.length; index++) {
      Field<?> originalField = rolledBackPrivateRow.fields().get(index);
      insertFields[index] =
          DSL.field(DSL.name(originalField.getName()), originalField.getDataType());
      values[index] = forged.get(originalField.getName());
    }
    Throwable insertFailure =
        catchThrowable(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .insertInto(
                                    DSL.table(DSL.name("game_session_canonical_realm_catalog")),
                                    insertFields)
                                .values(values)
                                .execute()));
    String failureSummary = databaseFailureSummary(insertFailure);
    assertThat(insertFailure)
        .as("Forged INSERT failure should expose its database cause: %s", failureSummary)
        .isNotNull();
    assertThat(sqlStateInCauseChain(insertFailure))
        .as("Expected SQLSTATE 23514; actual database failure: %s", failureSummary)
        .isEqualTo("23514");
    assertThat(insertFailure)
        .hasStackTraceContaining(
            "Canonical realm catalog requires exact committed authored-world intake evidence");

    assertThat(context.publicCatalogRow()).isEqualTo(publicCatalogBefore);
    assertThat(
            context
                .privateTargets()
                .readByPreparation(
                    preparation.request().requestId(), preparation.playtestLifecycleId()))
        .isEmpty();
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void privateCatalogCommitGuardRechecksPreparationExpiry() {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    var preparation = context.prepare(preparationRequest).preparation();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            preparation.request().requestId(),
            preparation.playtestLifecycleId());

    assertThatThrownBy(
            () ->
                context
                    .serializableTransaction()
                    .executeWithoutResult(
                        status -> {
                          context.privateTargets().allocate(targetRequest);
                          awaitExpiry(context, preparationRequest.forkExpiresAt());
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining(
            "Private FRESH catalog preparation expired before final catalog commit");
    assertThat(context.dsl().fetchCount(DSL.table("game_session_canonical_realm_catalog")))
        .isEqualTo(1);
    assertThat(
            context
                .privateTargets()
                .readByPreparation(
                    preparation.request().requestId(), preparation.playtestLifecycleId()))
        .isEmpty();
  }

  @Test
  void changedIntentUnderOneRequestConflictsBeforeSourceLookupOrAllocation() {
    Context context = context(true);
    Request original =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    Allocation allocated = context.prepare(original);
    for (Request changed :
        List.of(
            context.request(
                original.requestId(), "another-private-realm", original.forkExpiresAt()),
            context.request(
                original.requestId(), original.realmSlug(), original.forkExpiresAt().plusNanos(1)),
            new Request(
                original.requestId(),
                NAMESPACE,
                original.tenantId(),
                "another-world",
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                original.requestId(),
                NAMESPACE,
                UUID.randomUUID(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                original.requestId(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                UUID.randomUUID(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()))) {
      assertThatThrownBy(() -> context.prepare(changed))
          .isInstanceOf(IdempotencyConflictException.class);
    }
    assertThat(context.prepare(original).preparation()).isEqualTo(allocated.preparation());
    assertThat(context.count()).isEqualTo(1);
  }

  @Test
  void missingOrMismatchedRetainedSourceAndBuildNeverAllocate() {
    Context missing = context(false);
    assertThatThrownBy(
            () ->
                missing.prepare(
                    missing.request(
                        UUID.randomUUID(), "private-playtest", missing.now().plusSeconds(300))))
        .isInstanceOf(SourceUnavailableException.class);
    assertThat(missing.count()).isZero();
    Context context = context(true);
    Request original =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(300));
    for (Request changed :
        List.of(
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                UUID.randomUUID(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                UUID.randomUUID(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                UUID.randomUUID(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                original.sourceCatalogRevision(),
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                "sha256:" + "a".repeat(64),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            context.request(
                UUID.randomUUID(), context.catalog().realmSlug(), original.forkExpiresAt()))) {
      assertThatThrownBy(() -> context.prepare(changed))
          .isInstanceOf(SourceUnavailableException.class);
    }
    assertThat(context.count()).isZero();
  }

  @Test
  void onlyExactVisiblePrivateIsolatedPublishedTargetPolicyCanAllocate() {
    List<List<RealmEntryPolicy>> invalidPolicySets =
        List.of(
            List.of(),
            List.of(privatePolicy("private-playtest", "source-world", false, false, "ISOLATED")),
            List.of(privatePolicy("private-playtest", "source-world", true, false, "SHARED")),
            List.of(
                privatePolicy(
                    "private-playtest", "source-world", true, false, "ISOLATED", "Other World")),
            List.of(privatePolicy("another-target", "source-world", true, false, "ISOLATED")),
            List.of(privatePolicy("private-playtest", "another-world", true, false, "ISOLATED")));
    for (List<RealmEntryPolicy> policies : invalidPolicySets) {
      Context context = context(true, policies);
      Request request =
          context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(600));
      assertThatThrownBy(() -> context.prepare(request))
          .isInstanceOf(SourceUnavailableException.class);
      assertThat(context.count()).isZero();
    }
  }

  @Test
  void sqlGuardRejectsTargetPolicyBytesNotSelectedFromRetainedPublishedSet() {
    Context context = context(true);
    Request original =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    context.prepare(original);
    Request tampered =
        context.request(UUID.randomUUID(), original.realmSlug(), original.forkExpiresAt());
    byte[] storedPolicy =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT target_policy_canonical_json FROM "
                            + TABLE
                            + " WHERE request_id = ?",
                        original.requestId()))
            .get("target_policy_canonical_json", byte[].class);
    String changedPolicy =
        new String(storedPolicy, java.nio.charset.StandardCharsets.UTF_8)
            .replace("\"realmSlug\":\"private-playtest\"", "\"realmSlug\":\"other-target\"");
    assertThat(changedPolicy)
        .isNotEqualTo(new String(storedPolicy, java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            context
                                .dsl()
                                .execute(
                                    "INSERT INTO "
                                        + TABLE
                                        + " (request_id, request_preimage, request_digest, target_namespace, canonical_tenant_id, world_slug, realm_slug, initialization_mode, preparation_state, playtest_state_generation, fork_expiry_second, fork_expiry_nano, fork_expiry_text, source_catalog_realm_id, source_catalog_request_id, source_catalog_revision, source_catalog_receipt_digest, source_intake_operation_id, source_intake_receipt_digest, source_operation_id, source_evidence_digest, canonical_version_id, published_release_bundle_ref, published_release_bundle_digest, source_policy_set_digest, source_policy_carrier_digest, target_policy_id, target_policy_source_commit_id, target_policy_source_revision_id, target_policy_logical_revision_id, target_policy_digest, target_policy_canonical_json, target_policy_schema_version, target_policy_world_slug, target_policy_realm_slug, target_policy_visible, target_policy_world_display_name, target_policy_public_production, target_policy_state_scope, target_policy_realm_display_name, target_policy_entry_policy) "
                                        + "SELECT ?, ?, ?, target_namespace, canonical_tenant_id, world_slug, realm_slug, initialization_mode, preparation_state, playtest_state_generation, fork_expiry_second, fork_expiry_nano, fork_expiry_text, source_catalog_realm_id, source_catalog_request_id, source_catalog_revision, source_catalog_receipt_digest, source_intake_operation_id, source_intake_receipt_digest, source_operation_id, source_evidence_digest, canonical_version_id, published_release_bundle_ref, published_release_bundle_digest, source_policy_set_digest, source_policy_carrier_digest, target_policy_id, target_policy_source_commit_id, target_policy_source_revision_id, target_policy_logical_revision_id, target_policy_digest, ?, target_policy_schema_version, target_policy_world_slug, target_policy_realm_slug, target_policy_visible, target_policy_world_display_name, target_policy_public_production, target_policy_state_scope, target_policy_realm_display_name, target_policy_entry_policy FROM "
                                        + TABLE
                                        + " WHERE request_id = ?",
                                    tampered.requestId(),
                                    tampered.canonicalPreimage(),
                                    tampered.requestDigest(),
                                    changedPolicy.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    original.requestId())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("Selected private target policy differs");
    assertThat(context.count()).isEqualTo(1);
  }

  @Test
  void concurrentExactRequestAllocatesOnceAndNewRequestsForSameRealmGetDistinctIdentities()
      throws Exception {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch begin = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                await(begin);
                return context.prepare(request);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                await(begin);
                return context.prepare(request);
              });
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      begin.countDown();
      Allocation left = first.get(60, TimeUnit.SECONDS);
      Allocation right = second.get(60, TimeUnit.SECONDS);
      assertThat(left.preparation()).isEqualTo(right.preparation());
      assertThat(List.of(left.replayed(), right.replayed())).containsExactlyInAnyOrder(false, true);
      assertThat(context.count()).isEqualTo(1);
      Request newFirst =
          context.request(UUID.randomUUID(), request.realmSlug(), request.forkExpiresAt());
      Request newSecond =
          context.request(UUID.randomUUID(), request.realmSlug(), request.forkExpiresAt());
      var third = executor.submit(() -> context.prepare(newFirst));
      var fourth = executor.submit(() -> context.prepare(newSecond));
      var another = third.get(60, TimeUnit.SECONDS).preparation();
      var other = fourth.get(60, TimeUnit.SECONDS).preparation();
      assertThat(another.playtestLifecycleId())
          .isNotEqualTo(left.preparation().playtestLifecycleId())
          .isNotEqualTo(other.playtestLifecycleId());
      assertThat(another.playableStateNamespaceId())
          .isNotEqualTo(left.preparation().playableStateNamespaceId())
          .isNotEqualTo(other.playableStateNamespaceId());
      assertThat(context.read(request, left.preparation().playtestLifecycleId()).preparation())
          .isEqualTo(left.preparation());
      assertThat(context.count()).isEqualTo(3);
    } finally {
      begin.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void staleSerializableSnapshotMustRetryPrivateAllocationInANewOwnerTransaction()
      throws Exception {
    Context context = context(true);
    Request preparationRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var preparation = context.prepare(preparationRequest).preparation();
    var targetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            preparation.request().requestId(),
            preparation.playtestLifecycleId());
    CountDownLatch firstAllocationHeld = new CountDownLatch(1);
    CountDownLatch secondSnapshotEstablished = new CountDownLatch(1);
    CountDownLatch releaseFirstAllocation = new CountDownLatch(1);
    AtomicInteger secondBackendPid = new AtomicInteger();
    AtomicReference<Throwable> staleFailure = new AtomicReference<>();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstAllocation =
          new AtomicReference<GameSessionPrivateFreshTargetRepository.Allocation>();
      var first =
          executor.submit(
              () ->
                  Objects.requireNonNull(
                      context
                          .serializableTransaction()
                          .execute(
                              status -> {
                                var allocated = context.privateTargets().allocate(targetRequest);
                                firstAllocation.set(allocated);
                                firstAllocationHeld.countDown();
                                await(releaseFirstAllocation);
                                return allocated;
                              })));
      await(firstAllocationHeld);

      var second =
          executor.submit(
              () -> {
                try {
                  context
                      .serializableTransaction()
                      .executeWithoutResult(
                          status -> {
                            var visibleBeforeCommit =
                                context
                                    .dsl()
                                    .fetchSingle(
                                        "SELECT count(*) FROM game_session_canonical_realm_catalog WHERE private_preparation_request_id = ?",
                                        preparation.request().requestId())
                                    .get(0, Long.class);
                            assertThat(visibleBeforeCommit).isZero();
                            secondBackendPid.set(
                                context
                                    .dsl()
                                    .fetchSingle("SELECT pg_backend_pid()")
                                    .get(0, Integer.class));
                            secondSnapshotEstablished.countDown();
                            context.privateTargets().allocate(targetRequest);
                          });
                } catch (Throwable failure) {
                  staleFailure.set(failure);
                }
              });
      await(secondSnapshotEstablished);
      awaitAdvisoryLockWait(context, secondBackendPid.get());
      releaseFirstAllocation.countDown();

      first.get(60, TimeUnit.SECONDS);
      second.get(60, TimeUnit.SECONDS);
      Throwable failure = staleFailure.get();
      assertThat(failure)
          .as("stale SERIALIZABLE allocation must refuse with 40001 instead of committing")
          .isNotNull();
      assertThat(sqlStateInCauseChain(failure)).isEqualTo("40001");

      var committed = Objects.requireNonNull(firstAllocation.get());
      String retainedRowBeforeRetry = privateCatalogRow(context, targetRequest.requestId());
      // This is manual caller composition in the test, not automatic production retry behavior.
      var freshTransactionRetry = context.allocatePrivateTarget(targetRequest);
      assertThat(freshTransactionRetry.replayed()).isTrue();
      assertThat(freshTransactionRetry.snapshot()).isEqualTo(committed.snapshot());
      assertThat(freshTransactionRetry.snapshot().realmId())
          .isEqualTo(committed.snapshot().realmId());
      assertThat(freshTransactionRetry.snapshot().receiptDigest())
          .isEqualTo(committed.snapshot().receiptDigest());
      assertThat(privateCatalogRow(context, targetRequest.requestId()))
          .isEqualTo(retainedRowBeforeRetry);
      assertThat(
              context
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_canonical_realm_catalog WHERE private_preparation_request_id = ?",
                      preparation.request().requestId())
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
      assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
    } finally {
      releaseFirstAllocation.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void exactReadRejectsWrongLifecycleAndReportsRealExpiryWithoutChangingOriginal() {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    Allocation allocation = context.prepare(request);
    var original = allocation.preparation();
    assertThat(context.read(request, original.playtestLifecycleId()).state())
        .isEqualTo(ReadState.INCOMPLETE);
    Optional<Snapshot> wrongLifecycle =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            request.tenantId(),
                            request.worldSlug(),
                            request.realmSlug(),
                            UUID.randomUUID()));
    Optional<Snapshot> wrongRealm =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            request.tenantId(),
                            request.worldSlug(),
                            "wrong-realm",
                            original.playtestLifecycleId()));
    Optional<Snapshot> wrongTenant =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            UUID.randomUUID(),
                            request.worldSlug(),
                            request.realmSlug(),
                            original.playtestLifecycleId()));
    Optional<Snapshot> wrongWorld =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            request.tenantId(),
                            "wrong-world",
                            request.realmSlug(),
                            original.playtestLifecycleId()));
    assertThat(wrongLifecycle).isEmpty();
    assertThat(wrongRealm).isEmpty();
    assertThat(wrongTenant).isEmpty();
    assertThat(wrongWorld).isEmpty();
    awaitExpiry(context, request.forkExpiresAt());
    var expired = context.read(request, original.playtestLifecycleId());
    assertThat(expired.observedAt()).isAfterOrEqualTo(request.forkExpiresAt());
    assertThat(expired.state()).isEqualTo(ReadState.EXPIRED);
    assertThat(expired.admissionAllowed()).isFalse();
    assertThat(expired.preparation()).isEqualTo(original);
    assertThat(context.prepare(request)).isEqualTo(new Allocation(original, true));
    var reuse =
        context
            .prepare(
                context.request(
                    UUID.randomUUID(), request.realmSlug(), context.now().plusSeconds(3600)))
            .preparation();
    assertThat(reuse.playtestLifecycleId()).isNotEqualTo(original.playtestLifecycleId());
    assertThat(reuse.playableStateNamespaceId()).isNotEqualTo(original.playableStateNamespaceId());
  }

  @Test
  void rollbackExpiredFinalCommitAndImmutableHistoryViolationNeverPublishAuthority() {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          context.repository().prepare(request);
                          throw new IllegalStateException("Controlled coordinator refusal");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled coordinator refusal");
    assertThat(context.count()).isZero();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          Request shortRequest =
                              context.request(
                                  UUID.randomUUID(),
                                  "private-playtest",
                                  context.now().plusSeconds(5));
                          context.repository().prepare(shortRequest);
                          awaitExpiry(context, shortRequest.forkExpiresAt());
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("expired before final preparation commit");
    assertThat(context.count()).isZero();
    var committed = context.prepare(request);
    for (String sql :
        List.of(
            "UPDATE " + TABLE + " SET preparation_state = 'OPEN'",
            "UPDATE " + TABLE + " SET playtest_state_generation = 2",
            "UPDATE " + TABLE + " SET fork_expiry_second = fork_expiry_second + 1",
            "DELETE FROM " + TABLE,
            "TRUNCATE " + TABLE)) {
      assertThatThrownBy(
              () ->
                  context.transaction().executeWithoutResult(status -> context.dsl().execute(sql)))
          .as(sql)
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(context.count()).isEqualTo(1);
    assertThat(context.prepare(request).preparation()).isEqualTo(committed.preparation());
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
  }

  @Test
  void unsupportedInitializationAndMissingOwnerTransactionFailBeforeAllocation() {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    for (InitializationMode mode :
        List.of(InitializationMode.SEEDED, InitializationMode.SNAPSHOT)) {
      assertThatThrownBy(
              () ->
                  new Request(
                      request.requestId(),
                      NAMESPACE,
                      request.tenantId(),
                      request.worldSlug(),
                      request.realmSlug(),
                      request.sourceCatalogRealmId(),
                      request.sourceCatalogRequestId(),
                      request.sourceCatalogRevision(),
                      request.sourceIntakeOperationId(),
                      request.canonicalVersionId(),
                      request.publishedReleaseBundleRef(),
                      request.publishedReleaseBundleDigest(),
                      mode,
                      request.forkExpiresAt()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Only initial FRESH");
    }
    assertThatThrownBy(() -> context.repository().prepare(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owner transaction");
    assertThat(context.count()).isZero();
  }

  @Test
  void canonicalPublicPolicyCannotQualifyAsPrivatePlaytestTarget() {
    Context context = context(true);
    Request publicTarget =
        context.request(
            UUID.randomUUID(), context.catalog().realmSlug(), context.now().plusSeconds(3600));
    assertThatThrownBy(() -> context.prepare(publicTarget))
        .isInstanceOf(
            GameSessionInitialPlaytestLifecycleRepository.SourceUnavailableException.class);
    assertThat(context.count()).isZero();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS))
        throw new AssertionError("Concurrent allocation did not start");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static void awaitAdvisoryLockWait(Context context, int backendPid) {
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(10, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              var activity =
                  context
                      .dsl()
                      .fetchOne(
                          "SELECT wait_event_type, wait_event FROM pg_stat_activity WHERE pid = ?",
                          backendPid);
              assertThat(activity).isNotNull();
              assertThat(activity.get("wait_event_type", String.class)).isEqualTo("Lock");
              assertThat(activity.get("wait_event", String.class)).isEqualTo("advisory");
            });
  }

  private static String privateCatalogRow(Context context, UUID requestId) {
    return context
        .dsl()
        .fetchSingle(
            "SELECT to_jsonb(c)::text || ':' || xmin::text FROM game_session_canonical_realm_catalog c WHERE creation_request_id = ?",
            requestId)
        .get(0, String.class);
  }

  private static String retainedRowWithXmin(
      Context context, String tableName, String identityColumn, UUID identity) {
    return context
        .dsl()
        .fetchSingle(
            "SELECT to_jsonb(retained)::text || ':' || xmin::text FROM "
                + tableName
                + " retained WHERE "
                + identityColumn
                + " = ?",
            identity)
        .get(0, String.class);
  }

  private static String privateCatalogReceiptDigest(Map<String, Object> row, String tenantSlug) {
    return sha256(
        frame(
            "game-session-private-fresh-catalog-receipt/v1",
            privateCatalogReceiptField(row, "creation_request_id"),
            privateCatalogReceiptField(row, "request_digest"),
            privateCatalogReceiptField(row, "target_namespace"),
            privateCatalogReceiptField(row, "canonical_tenant_id"),
            tenantSlug,
            privateCatalogReceiptField(row, "world_slug"),
            privateCatalogReceiptField(row, "realm_id"),
            privateCatalogReceiptField(row, "realm_slug"),
            privateCatalogReceiptField(row, "realm_display_name"),
            privateCatalogReceiptField(row, "visible"),
            privateCatalogReceiptField(row, "public_production"),
            privateCatalogReceiptField(row, "state_scope"),
            privateCatalogReceiptField(row, "playable_state_namespace_id"),
            privateCatalogReceiptField(row, "character_creation_policy"),
            privateCatalogReceiptField(row, "catalog_revision"),
            privateCatalogReceiptField(row, "private_preparation_request_id"),
            privateCatalogReceiptField(row, "private_playtest_lifecycle_id"),
            privateCatalogReceiptField(row, "private_preparation_request_digest"),
            privateCatalogReceiptField(row, "private_state_generation"),
            privateCatalogReceiptField(row, "private_source_catalog_realm_id"),
            privateCatalogReceiptField(row, "private_source_catalog_request_id"),
            privateCatalogReceiptField(row, "private_source_catalog_revision"),
            privateCatalogReceiptField(row, "private_source_catalog_receipt_digest"),
            privateCatalogReceiptField(row, "private_target_policy_id"),
            privateCatalogReceiptField(row, "private_target_policy_digest"),
            privateCatalogReceiptField(row, "private_target_policy_source_commit_id"),
            privateCatalogReceiptField(row, "private_target_policy_source_revision_id"),
            privateCatalogReceiptField(row, "private_target_policy_logical_revision_id"),
            privateCatalogReceiptField(row, "private_canonical_version_id"),
            privateCatalogReceiptField(row, "private_published_release_bundle_ref"),
            privateCatalogReceiptField(row, "private_published_release_bundle_digest"),
            privateCatalogReceiptField(row, "private_source_policy_set_digest"),
            privateCatalogReceiptField(row, "private_source_policy_carrier_digest"),
            privateCatalogReceiptField(row, "source_intake_operation_id"),
            privateCatalogReceiptField(row, "source_intake_receipt_digest")));
  }

  private static String privateCatalogReceiptField(Map<String, Object> row, String field) {
    return Objects.requireNonNull(row.get(field), field).toString();
  }

  private static String sqlStateInCauseChain(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        for (SQLException chained = sqlException;
            chained != null;
            chained = chained.getNextException()) {
          if (chained.getSQLState() != null) return chained.getSQLState();
        }
      }
    }
    return null;
  }

  private static String databaseFailureSummary(Throwable failure) {
    if (failure == null) return "<no exception>";
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        for (SQLException chained = sqlException;
            chained != null;
            chained = chained.getNextException()) {
          if (chained.getSQLState() != null) {
            return boundedDiagnostic(
                chained.getSQLState() + ": " + Objects.toString(chained.getMessage(), ""));
          }
        }
      }
    }
    return boundedDiagnostic(failure.getClass().getName() + ": " + failure.getMessage());
  }

  private static String boundedDiagnostic(String diagnostic) {
    if (diagnostic.length() <= 512) return diagnostic;
    return diagnostic.substring(0, 512) + "...";
  }

  private static void awaitExpiry(Context context, Instant expiry) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (!context.now().isBefore(expiry)) return;
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
    throw new AssertionError("Real database clock did not reach expiry");
  }

  private static byte[] frame(String... values) {
    var out = new ByteArrayOutputStream();
    for (String value : values) DraftAuthorizationFenceBinding.frame(out, value);
    return out.toByteArray();
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @Test
  void privateSourceProtectionCommitsExactReplayWithoutChangingSourceOrAdmission() {
    var f = runtimeFixture(3600);
    allocateRuntime(f.context(), f.request());
    var source = privateProtectionSource(f);
    var before = runtimeSourceRows(f.context());
    var owner = privateProtectionOwner(f.context());
    UUID request = UUID.randomUUID();
    var held = owner.acquire(request, source);
    assertThat(owner.acquire(request, source)).isEqualTo(held);
    assertThat(owner.readCurrent(request, held.holdId(), held.fence())).isEqualTo(held);
    assertThat(PrivateWorldPreparationReadGrpcCodec.stableBytes(held.source()))
        .containsExactly(PrivateWorldPreparationReadGrpcCodec.stableBytes(source));
    assertThat(held.admissionAllowed()).isFalse();
    assertThat(runtimeSourceRows(f.context())).isEqualTo(before);
    assertThat(
            f.context().dsl().fetchCount(DSL.table("game_session_private_preparation_protection")))
        .isEqualTo(1);
    assertThat(f.context().dsl().fetchCount(DSL.table("game_instances"))).isZero();
    assertThat(f.context().dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThatThrownBy(() -> owner.acquire(UUID.randomUUID(), source))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> owner.readCurrent(request, UUID.randomUUID(), held.fence()))
        .isInstanceOf(
            GameSessionPrivatePreparationProtectionRepository.ProtectionUnavailableException.class);
  }

  @Test
  void pendingPrivateSourceProtectionBlocksBothSupersessionWritersAndCannotBeDeleted() {
    var f = runtimeFixture(3600);
    allocateRuntime(f.context(), f.request());
    var later =
        f.context()
            .prepare(
                f.context()
                    .request(
                        UUID.randomUUID(), "private-playtest", f.context().now().plusSeconds(7200)))
            .preparation();
    var owner = privateProtectionOwner(f.context());
    var held = owner.acquire(UUID.randomUUID(), privateProtectionSource(f));
    var before = runtimeSourceRows(f.context());
    var select =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            new GameSessionCurrentPrivatePlaytestRepository.Scope(
                NAMESPACE, f.target().tenantId(), f.target().worldSlug(), f.target().realmSlug()),
            later.request().requestId(),
            later.playtestLifecycleId(),
            f.selection().headRevision(),
            f.selection().playtestLifecycleId());
    assertThatThrownBy(
            () ->
                f.context()
                    .transaction()
                    .execute(
                        status ->
                            new GameSessionCurrentPrivatePlaytestRepository(f.context().dsl())
                                .select(select)))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("unresolved World preparation");
    var unsupported = new TransactionTemplate(f.context().transaction().getTransactionManager());
    unsupported.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(
            () ->
                unsupported.execute(
                    status ->
                        new GameSessionCurrentPrivatePlaytestRepository(f.context().dsl())
                            .select(select)))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("READ COMMITTED or SERIALIZABLE");
    var advance =
        new AdvanceRequest(
            UUID.randomUUID(),
            f.target().realmId(),
            later.request().requestId(),
            later.playtestLifecycleId(),
            f.target().catalogRevision(),
            f.target().playtestLifecycleId());
    assertThatThrownBy(() -> f.context().advancePrivateRevision(advance))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("unresolved World preparation");
    for (String sql :
        List.of(
            "DELETE FROM game_session_private_preparation_protection",
            "UPDATE game_session_private_preparation_protection SET source_digest=source_digest",
            "TRUNCATE game_session_private_preparation_protection")) {
      assertThatThrownBy(() -> f.context().dsl().execute(sql))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("without World terminal proof");
    }
    assertThat(runtimeSourceRows(f.context())).isEqualTo(before);
    assertThat(owner.readCurrent(held.requestId(), held.holdId(), held.fence())).isEqualTo(held);
  }

  @Test
  void expiredPrivateProtectionRemainsPendingButCannotQualifyCurrentSource() {
    // The immutable expiry starts before preparation, catalog/selection/runtime allocation and
    // complete source qualification. Leave room to establish a real committed hold first.
    var f = runtimeFixture(30);
    allocateRuntime(f.context(), f.request());
    var source = privateProtectionSource(f);
    var owner = privateProtectionOwner(f.context());
    UUID request = UUID.randomUUID();
    var held = owner.acquire(request, source);
    assertThat(f.context().now()).isBefore(f.preparation().request().forkExpiresAt());
    var retained =
        retainedRowWithXmin(
            f.context(), "game_session_private_preparation_protection", "request_id", request);
    f.context()
        .dsl()
        .fetch(
            "SELECT pg_sleep(GREATEST(0,extract(epoch FROM ?::timestamptz-clock_timestamp()))+0.05)",
            f.preparation().request().forkExpiresAt().atOffset(java.time.ZoneOffset.UTC));
    assertThat(owner.acquire(request, source)).isEqualTo(held);
    assertThatThrownBy(() -> owner.readCurrent(request, held.holdId(), held.fence()))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            f.context().dsl().fetchCount(DSL.table("game_session_private_preparation_protection")))
        .isEqualTo(1);
    assertThat(
            retainedRowWithXmin(
                f.context(), "game_session_private_preparation_protection", "request_id", request))
        .isEqualTo(retained);
    assertThatThrownBy(
            () ->
                f.context()
                    .dsl()
                    .execute("DELETE FROM game_session_private_preparation_protection"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void competingExactPrivateProtectionAcquisitionsReturnOneCommittedHold() throws Exception {
    var f = runtimeFixture(3600);
    allocateRuntime(f.context(), f.request());
    var source = privateProtectionSource(f);
    var attempts = new CountDownLatch(2);
    var attemptedThreads = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
    var observedDsl =
        DSL.using(
            f.context()
                .dsl()
                .configuration()
                .derive(
                    org.jooq.ExecuteListener.onRecordEnd(
                        execution -> {
                          String sql = execution.sql();
                          var record = execution.record();
                          if (sql != null
                              && sql.contains("pg_try_advisory_xact_lock")
                              && record != null
                              && Boolean.FALSE.equals(record.get("acquired", Boolean.class))
                              && attemptedThreads.add(Thread.currentThread().threadId()))
                            attempts.countDown();
                        })));
    var owner =
        new GameSessionPrivatePreparationProtectionRepository(
            observedDsl, Objects.requireNonNull(f.context().transaction().getTransactionManager()));
    UUID request = UUID.randomUUID();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                gate.await();
                return owner.acquire(request, source);
              });
      var second =
          executor.submit(
              () -> {
                gate.await();
                return owner.acquire(request, source);
              });
      // Both callers must encounter a busy request. They must release those SERIALIZABLE
      // transactions before retrying, rather than retain pre-wait snapshots under a blocking lock.
      f.context()
          .transaction()
          .executeWithoutResult(
              status -> {
                f.context()
                    .dsl()
                    .fetch(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                        "private-source-protection/" + request);
                gate.countDown();
                try {
                  assertThat(attempts.await(3, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
              });
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
    }
    assertThat(
            f.context().dsl().fetchCount(DSL.table("game_session_private_preparation_protection")))
        .isEqualTo(1);
  }

  @Test
  void staleSerializableSelectionWriterCannotMissCommittedPrivateProtection() throws Exception {
    var f = runtimeFixture(3600);
    allocateRuntime(f.context(), f.request());
    var later =
        f.context()
            .prepare(
                f.context()
                    .request(
                        UUID.randomUUID(), "private-playtest", f.context().now().plusSeconds(7200)))
            .preparation();
    var expected = privateProtectionSource(f);
    var snapshotTaken = new CountDownLatch(1);
    var protectionCommitted = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var writer =
          executor.submit(
              () ->
                  catchThrowable(
                      () ->
                          f.context()
                              .serializableTransaction()
                              .executeWithoutResult(
                                  status -> {
                                    f.context()
                                        .dsl()
                                        .fetch(
                                            "SELECT * FROM game_session_current_private_playtests");
                                    snapshotTaken.countDown();
                                    try {
                                      if (!protectionCommitted.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException(
                                            "Protection did not commit");
                                    } catch (InterruptedException interrupted) {
                                      Thread.currentThread().interrupt();
                                      throw new IllegalStateException(interrupted);
                                    }
                                    new GameSessionCurrentPrivatePlaytestRepository(
                                            f.context().dsl())
                                        .select(
                                            new GameSessionCurrentPrivatePlaytestRepository.Request(
                                                UUID.randomUUID(),
                                                new GameSessionCurrentPrivatePlaytestRepository
                                                    .Scope(
                                                    NAMESPACE,
                                                    f.target().tenantId(),
                                                    f.target().worldSlug(),
                                                    f.target().realmSlug()),
                                                later.request().requestId(),
                                                later.playtestLifecycleId(),
                                                f.selection().headRevision(),
                                                f.selection().playtestLifecycleId()));
                                  })));
      assertThat(snapshotTaken.await(10, TimeUnit.SECONDS)).isTrue();
      var owner = privateProtectionOwner(f.context());
      var held = owner.acquire(UUID.randomUUID(), expected);
      protectionCommitted.countDown();
      assertThat(writer.get(10, TimeUnit.SECONDS)).isNotNull();
      assertThat(owner.readCurrent(held.requestId(), held.holdId(), held.fence())).isEqualTo(held);
    } finally {
      protectionCommitted.countDown();
    }
  }

  private static GameSessionPrivatePreparationProtectionRepository privateProtectionOwner(
      Context c) {
    return new GameSessionPrivatePreparationProtectionRepository(
        c.dsl(), Objects.requireNonNull(c.transaction().getTransactionManager()));
  }

  private static PrivateWorldPreparationReadEvidence.Snapshot privateProtectionSource(
      RuntimeFixture f) {
    var c = f.context();
    return Objects.requireNonNull(
        c.transaction()
            .execute(
                status ->
                    new GameSessionPrivateWorldPreparationSourceReader(
                            new GameSessionCurrentPrivatePlaytestRepository(c.dsl()),
                            c.privateTargets(),
                            new GameSessionPrivateFreshRuntimeAllocationRepository(c.dsl()))
                        .read(
                            new PrivateWorldPreparationReadGrpcCodec.Request(
                                UUID.randomUUID(),
                                new CurrentPrivatePlaytestLifecycleReadEvidence.Scope(
                                    NAMESPACE,
                                    f.target().tenantId(),
                                    f.target().worldSlug(),
                                    f.target().realmSlug()),
                                f.preparation().request().requestId()))));
  }

  private static final String RUNTIME_ALLOCATIONS =
      "game_session_private_fresh_runtime_allocations";

  private RuntimeFixture runtimeFixture(long lifetimeSeconds) {
    var context = context(true);
    var request =
        context.request(
            UUID.randomUUID(), "private-playtest", context.now().plusSeconds(lifetimeSeconds));
    var preparation = context.prepare(request).preparation();
    var target =
        context
            .allocatePrivateTarget(
                new GameSessionPrivateFreshTargetRepository.Request(
                    UUID.randomUUID(), request.requestId(), preparation.playtestLifecycleId()))
            .snapshot();
    var selection = selectRuntimeSource(context, target, null);
    return new RuntimeFixture(
        context,
        preparation,
        target,
        selection,
        runtimeRequest(UUID.randomUUID(), target, selection));
  }

  private static GameSessionCurrentPrivatePlaytestRepository.SelectionReceipt selectRuntimeSource(
      Context context,
      CanonicalPrivateFreshTargetSnapshot target,
      GameSessionCurrentPrivatePlaytestRepository.SelectionReceipt previous) {
    var scope =
        new GameSessionCurrentPrivatePlaytestRepository.Scope(
            target.targetNamespace(), target.tenantId(), target.worldSlug(), target.realmSlug());
    var request =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            scope,
            target.preparationRequestId(),
            target.playtestLifecycleId(),
            previous == null ? null : previous.headRevision(),
            previous == null ? null : previous.playtestLifecycleId());
    return Objects.requireNonNull(
            context
                .transaction()
                .execute(
                    status ->
                        new GameSessionCurrentPrivatePlaytestRepository(context.dsl())
                            .select(request)))
        .receipt();
  }

  private static GameSessionPrivateFreshRuntimeAllocationRepository.Request runtimeRequest(
      UUID requestId,
      CanonicalPrivateFreshTargetSnapshot target,
      GameSessionCurrentPrivatePlaytestRepository.SelectionReceipt selection) {
    return new GameSessionPrivateFreshRuntimeAllocationRepository.Request(
        requestId,
        target.preparationRequestId(),
        target.playtestLifecycleId(),
        target.realmId(),
        target.catalogRevision(),
        target.creationRequestId(),
        target.receiptDigest(),
        selection.request().requestId(),
        selection.headRevision());
  }

  private static GameSessionPrivateFreshRuntimeAllocationRepository.Allocation allocateRuntime(
      Context context, GameSessionPrivateFreshRuntimeAllocationRepository.Request request) {
    return Objects.requireNonNull(
        context
            .serializableTransaction()
            .execute(
                status ->
                    new GameSessionPrivateFreshRuntimeAllocationRepository(context.dsl())
                        .allocate(request)));
  }

  private static List<Map<String, Object>> runtimeSourceRows(Context context) {
    return context
        .dsl()
        .fetch(
            "SELECT 'catalog' AS source,to_jsonb(a)::TEXT AS row,xmin::TEXT AS version"
                + " FROM game_session_canonical_realm_catalog a UNION ALL SELECT 'preparation',to_jsonb(a)::TEXT,xmin::TEXT"
                + " FROM game_session_initial_playtest_preparations a UNION ALL SELECT 'selection',to_jsonb(a)::TEXT,xmin::TEXT"
                + " FROM game_session_private_playtest_selections a UNION ALL SELECT 'selection-head',to_jsonb(a)::TEXT,xmin::TEXT"
                + " FROM game_session_current_private_playtests a UNION ALL SELECT 'catalog-head',to_jsonb(a)::TEXT,xmin::TEXT"
                + " FROM game_session_current_private_catalog_revisions a ORDER BY 1,2")
        .intoMaps();
  }

  private static void insertForgedRuntimeAllocation(
      Context context, UUID originalRequestId, String patch) {
    // Recompute both digests after altering the source fields. Hash self-consistency alone must
    // never replace exact source/current-head qualification at the database boundary.
    context
        .dsl()
        .execute(
            "WITH candidate AS (SELECT jsonb_populate_record(NULL::"
                + RUNTIME_ALLOCATIONS
                + ",to_jsonb(a)||?::jsonb||jsonb_build_object('request_id',?::uuid,'game_instance_id',?::uuid)) AS a"
                + " FROM "
                + RUNTIME_ALLOCATIONS
                + " a WHERE request_id=?), bytes AS (SELECT (a).*,"
                + " gs_private_fresh_runtime_request_bytes(a) AS b FROM candidate), intent AS (SELECT"
                + " jsonb_populate_record(NULL::"
                + RUNTIME_ALLOCATIONS
                + ", (to_jsonb(bytes)-'b')||jsonb_build_object("
                + "'request_preimage','\\x'||encode(b,'hex'),'request_digest','sha256:'||encode(sha256(b),'hex'))) AS a FROM bytes),"
                + " receipt AS (SELECT jsonb_populate_record(NULL::"
                + RUNTIME_ALLOCATIONS
                + ",to_jsonb(a)||jsonb_build_object('receipt_digest',gs_private_fresh_runtime_receipt_digest(a))) AS a FROM intent)"
                + " INSERT INTO "
                + RUNTIME_ALLOCATIONS
                + " SELECT (a).* FROM receipt",
            patch,
            UUID.randomUUID(),
            UUID.randomUUID(),
            originalRequestId);
  }

  private record RuntimeFixture(
      Context context,
      Preparation preparation,
      CanonicalPrivateFreshTargetSnapshot target,
      GameSessionCurrentPrivatePlaytestRepository.SelectionReceipt selection,
      GameSessionPrivateFreshRuntimeAllocationRepository.Request request) {}

  private PrivateRevisionFixture privateRevisionFixture(long laterLifetimeSeconds) {
    Context context =
        context(
            true,
            List.of(
                privatePolicy("private-playtest", "source-world", true, false, "ISOLATED"),
                privatePolicy("other-playtest", "source-world", true, false, "ISOLATED")));
    Request initialRequest =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    var initialPreparation = context.prepare(initialRequest).preparation();
    var initialTargetRequest =
        new GameSessionPrivateFreshTargetRepository.Request(
            UUID.randomUUID(),
            initialRequest.requestId(),
            initialPreparation.playtestLifecycleId());
    var initialTarget = context.allocatePrivateTarget(initialTargetRequest).snapshot();
    Request laterRequest =
        context.request(
            UUID.randomUUID(), "private-playtest", context.now().plusSeconds(laterLifetimeSeconds));
    var laterPreparation = context.prepare(laterRequest).preparation();
    var advanceRequest =
        new AdvanceRequest(
            UUID.randomUUID(),
            initialTarget.realmId(),
            laterRequest.requestId(),
            laterPreparation.playtestLifecycleId(),
            1,
            initialPreparation.playtestLifecycleId());
    return new PrivateRevisionFixture(
        context,
        initialRequest,
        initialPreparation,
        initialTargetRequest,
        initialTarget,
        laterRequest,
        laterPreparation,
        advanceRequest);
  }

  private static List<Map<String, Object>> privateRevisionRows(Context context) {
    return context
        .dsl()
        .fetch(
            "SELECT 'history' AS source, to_jsonb(h)::TEXT AS retained_row, xmin::TEXT AS row_version FROM "
                + REVISION_HISTORY
                + " h UNION ALL SELECT 'head', to_jsonb(h)::TEXT, xmin::TEXT FROM "
                + REVISION_HEAD
                + " h ORDER BY 1, 2")
        .intoMaps();
  }

  private record PrivateRevisionFixture(
      Context context,
      Request initialRequest,
      Preparation initialPreparation,
      GameSessionPrivateFreshTargetRepository.Request initialTargetRequest,
      CanonicalPrivateFreshTargetSnapshot initialTarget,
      Request laterRequest,
      Preparation laterPreparation,
      AdvanceRequest advanceRequest) {}

  private Context context(boolean withSource) {
    return context(
        withSource,
        List.of(privatePolicy("private-playtest", "source-world", true, false, "ISOLATED")));
  }

  private Context context(boolean withSource, List<RealmEntryPolicy> privatePolicies) {
    return context(withSource, privatePolicies, null);
  }

  private Context context(
      boolean withSource, List<RealmEntryPolicy> privatePolicies, String targetVersion) {
    String schema = "initial_playtest_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        dataSource,
        schema,
        "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
        targetVersion);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    TransactionTemplate serializableTransaction = new TransactionTemplate(transactionManager);
    serializableTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    var repository = new GameSessionInitialPlaytestLifecycleRepository(dsl);
    var catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
    var privateTargets = new GameSessionPrivateFreshTargetRepository(dsl);
    if (!withSource)
      return new Context(
          dataSource,
          schema,
          dsl,
          transaction,
          serializableTransaction,
          repository,
          privateTargets,
          catalogs,
          null,
          null);
    // These upstream receipts are explicitly stipulated. Every retained GS row is created through
    // its actual source, fresh association and closed Human canonical catalog owner.
    UUID tenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, "source-tenant", "source-world", "Source World");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            "source-tenant",
            "source-world",
            "Source World",
            731L,
            "controlled-source-game",
            "NEW_GAME_ROW");
    var evidence =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            "source-tenant",
            "source-world",
            "Source World",
            731L,
            "controlled-source-game",
            "NEW_GAME_ROW",
            evidenceDigest);
    var sourceOwner = new GameSessionAuthoredWorldSourceRepository(dsl);
    var intake =
        Objects.requireNonNull(
            transaction.execute(status -> sourceOwner.register(UUID.randomUUID(), evidence)));
    transaction.executeWithoutResult(
        status ->
            new GameSessionFreshTenantAssociationRepository(dsl)
                .associateFresh(
                    UUID.randomUUID(),
                    NAMESPACE,
                    intake,
                    CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(evidence)));
    var catalogRequest =
        new CreateCanonicalRealmCatalogRequest(
            UUID.randomUUID(),
            NAMESPACE,
            tenant,
            intake.operationId(),
            "source-public",
            "Source Public",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    UUID version = UUID.randomUUID();
    var published =
        CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            catalogRequest,
            evidence,
            version,
            72L,
            "controlled-publish-" + UUID.randomUUID(),
            "release-bundle:" + version,
            "generation-1",
            3L,
            privatePolicies);
    var preparedCatalog = catalogs.prepareInitialPublicProduction(catalogRequest, published);
    var catalog =
        Objects.requireNonNull(
            transaction.execute(status -> catalogs.createInitialPublicProduction(preparedCatalog)));
    return new Context(
        dataSource,
        schema,
        dsl,
        transaction,
        serializableTransaction,
        repository,
        privateTargets,
        catalogs,
        catalog,
        published);
  }

  private static RealmEntryPolicy privatePolicy(
      String realmSlug,
      String worldSlug,
      boolean visible,
      boolean publicProduction,
      String stateScope) {
    return privatePolicy(
        realmSlug, worldSlug, visible, publicProduction, stateScope, "Source World");
  }

  private static RealmEntryPolicy privatePolicy(
      String realmSlug,
      String worldSlug,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      String worldDisplayName) {
    return privatePolicy(
        realmSlug,
        worldSlug,
        visible,
        publicProduction,
        stateScope,
        worldDisplayName,
        "Private Playtest");
  }

  private static RealmEntryPolicy privatePolicy(
      String realmSlug,
      String worldSlug,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      String worldDisplayName,
      String realmDisplayName) {
    try {
      String json =
          new String(
              net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
                  "{\"entryPolicy\":\"PRESEEDED_ONLY\",\"realmDisplayName\":\""
                      + realmDisplayName
                      + "\",\"realmSlug\":\""
                      + realmSlug
                      + "\",\"publicProduction\":"
                      + publicProduction
                      + ",\"schemaVersion\":1,\"stateScope\":\""
                      + stateScope
                      + "\",\"visible\":"
                      + visible
                      + ",\"worldDisplayName\":\""
                      + worldDisplayName
                      + "\",\"worldSlug\":\""
                      + worldSlug
                      + "\"}"),
              java.nio.charset.StandardCharsets.UTF_8);
      return RealmEntryPolicy.parseCanonical(json, POLICY_JSON);
    } catch (java.io.IOException malformed) {
      throw new IllegalStateException(
          "Controlled private policy fixture encoding failed", malformed);
    }
  }

  private record DetachedCatalogRow(Map<String, Object> values, List<Field<?>> fields) {}

  private record Context(
      DriverManagerDataSource dataSource,
      String schema,
      DSLContext dsl,
      TransactionTemplate transaction,
      TransactionTemplate serializableTransaction,
      GameSessionInitialPlaytestLifecycleRepository repository,
      GameSessionPrivateFreshTargetRepository privateTargets,
      GameSessionCanonicalRealmCatalogRepository catalogs,
      CanonicalRealmCatalogSnapshot catalog,
      RealmPolicyPublishedEvidence.PublishedSet published) {
    Instant now() {
      return Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS now"))
          .get("now", OffsetDateTime.class)
          .toInstant();
    }

    Request request(UUID request, String realm, Instant expiry) {
      if (catalog == null)
        return new Request(
            request,
            NAMESPACE,
            UUID.randomUUID(),
            "missing-world",
            realm,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "missing-release",
            "sha256:" + "b".repeat(64),
            InitializationMode.FRESH,
            expiry);
      return new Request(
          request,
          NAMESPACE,
          catalog.tenantId(),
          catalog.worldSlug(),
          realm,
          catalog.realmId(),
          catalog.creationRequestId(),
          catalog.catalogRevision(),
          catalog.sourceIntakeReceipt().operationId(),
          published.target().canonicalVersionId(),
          published.publishedReleaseBundleRef(),
          published.publishedReleaseBundleDigest(),
          InitializationMode.FRESH,
          expiry);
    }

    Allocation prepare(Request request) {
      return Objects.requireNonNull(transaction.execute(status -> repository.prepare(request)));
    }

    GameSessionPrivateFreshTargetRepository.Allocation allocatePrivateTarget(
        GameSessionPrivateFreshTargetRepository.Request request) {
      return Objects.requireNonNull(
          serializableTransaction.execute(status -> privateTargets.allocate(request)));
    }

    GameSessionPrivateFreshTargetRepository.Allocation advancePrivateRevision(
        AdvanceRequest request) {
      var revisions = new GameSessionPrivateFreshCatalogRevisionRepository(dsl);
      return Objects.requireNonNull(
          serializableTransaction.execute(status -> revisions.advance(request)));
    }

    GameSessionInitialPlaytestLifecycleRepository.Snapshot read(Request request, UUID lifecycle) {
      return Objects.requireNonNull(
          transaction.execute(
              status ->
                  repository
                      .readExact(
                          NAMESPACE,
                          request.tenantId(),
                          request.worldSlug(),
                          request.realmSlug(),
                          lifecycle)
                      .orElseThrow()));
    }

    int count() {
      return dsl.fetchCount(DSL.table(TABLE));
    }

    List<Map<String, Object>> retainedRows() {
      return dsl.fetch(
              "SELECT 'catalog' AS source, realm_id::TEXT AS id, xmin::TEXT AS version FROM game_session_canonical_realm_catalog "
                  + "UNION ALL SELECT 'namespace', playable_state_namespace_id::TEXT, xmin::TEXT FROM gameplay_tenant_shared_playable_state_namespace ORDER BY 1, 2")
          .intoMaps();
    }

    Map<String, Object> publicCatalogRow() {
      return Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT to_jsonb(catalog)::TEXT AS retained_row, xmin::TEXT AS row_version "
                      + "FROM game_session_canonical_realm_catalog catalog WHERE realm_id = ?",
                  catalog.realmId()))
          .intoMap();
    }

    List<Map<String, Object>> namespaceRows() {
      return dsl.fetch(
              "SELECT tenant_id, canonical_tenant_id, target_namespace, "
                  + "playable_state_namespace_id::TEXT AS namespace_id, xmin::TEXT AS row_version "
                  + "FROM gameplay_tenant_shared_playable_state_namespace "
                  + "ORDER BY playable_state_namespace_id")
          .intoMaps();
    }
  }
}
