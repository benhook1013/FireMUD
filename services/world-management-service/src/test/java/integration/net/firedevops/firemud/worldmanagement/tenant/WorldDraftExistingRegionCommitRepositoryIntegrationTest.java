package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftReviewedBaseStaging.StagedDelta;
import net.firedevops.firemud.worldmanagement.tenant.WorldSelectedFullPublicationEvidenceFixture.AppliedGraph;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceClosureDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.jooq.DSLContext;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Actual current-frontier PostgreSQL component mutation and retained readback, all triggers
 * enabled. Source, Game Design synchronization/review, Account bytes and the initial APPLIED order
 * are controlled fixtures, not a genuine producer, COMMIT_ORDER grant or callable update route.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldDraftExistingRegionCommitRepositoryIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String NAMESPACE = "firemud";
  private static final String REVIEW_DIGEST = "sha256:" + "a".repeat(64);
  private static final String SCHEMA =
      "world_existing_region_" + UUID.randomUUID().toString().replace("-", "");
  private static final AtomicInteger NEXT_SOURCE_ROW = new AtomicInteger(1000);
  private static DSLContext dsl;
  private static DataSourceTransactionManager manager;
  private static TransactionTemplate write;
  private static WorldDesignPublicationFenceRepository fence;
  private static WorldDraftGraphApplicationRepository applications;
  private static WorldDraftExistingRegionCommitRepository repository;
  private static Fixture retained;
  private static String retainedBeforeUpgrade;
  private static String catalogBeforeUpgrade;
  private static String consumerBeforeUpgrade;
  private static String freshGuardBeforeUpgrade;
  private static StagedDelta retainedV1Input;
  private static WorldDraftExistingRegionStoredEvidence retainedV1;
  private static String retainedV1History;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void installAndUpgradeRetainedSchema() throws Exception {
    var source = source(SCHEMA);
    migration(source, SCHEMA, "1070").migrate();
    dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    manager = new DataSourceTransactionManager(source);
    write = new TransactionTemplate(manager);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    fence =
        new WorldDesignPublicationFenceRepository(
            dsl, new WorldAuthoredSourceIntakeRepository(dsl));
    applications = new WorldDraftGraphApplicationRepository(dsl, fence, JSON);
    retained = fixture();
    retainedBeforeUpgrade = retainedFacts(retained);
    catalogBeforeUpgrade = catalogFacts();
    assertCatalogSeal(dsl, 6);
    consumerBeforeUpgrade =
        function("world_consume_region_execution_manifest(text,text,jsonb,jsonb)");
    freshGuardBeforeUpgrade = function("world_guard_graph_application()");
    migration(source, SCHEMA, "1071").migrate();
    assertCatalogSeal(dsl, 7);
    retainedV1Input = stage(retained, retained.region(), "Retained v1", 1, 1);
    retainedV1 = storeV1(retainedV1Input);
    retainedV1History = history(retainedV1Input);
    migration(source, SCHEMA, "1074").migrate();
    assertCatalogSeal(dsl, 10);
    migration(source, SCHEMA, null).migrate();
    repository = new WorldDraftExistingRegionCommitRepository(dsl, fence, JSON);
  }

  @Test
  void freshAndRetainedUpgradeAppendExactInstalledCatalogWithoutChangingHistoricalBytes() {
    assertThat(retainedFacts(retained)).isEqualTo(retainedBeforeUpgrade);
    assertThat(function("world_consume_region_execution_manifest(text,text,jsonb,jsonb)"))
        .isEqualTo(consumerBeforeUpgrade);
    assertThat(function("world_guard_graph_application()")).isEqualTo(freshGuardBeforeUpgrade);
    assertThat(catalogFacts())
        .startsWith(catalogBeforeUpgrade.substring(0, catalogBeforeUpgrade.length() - 1));
    assertCatalogSeal(dsl, 13);
    String freshSchema =
        "world_existing_region_fresh_" + UUID.randomUUID().toString().replace("-", "");
    var fresh = source(freshSchema);
    migration(fresh, freshSchema, null).migrate();
    assertCatalogSeal(DSL.using(fresh, SQLDialect.POSTGRES), 13);
  }

  @Test
  void changesOnlyCurrentRegionAndBothUuidEpochsWhileRetainingCreationAndAppliedHistory()
      throws Exception {
    var f = fixture();
    String original = retainedFacts(f);
    String other = physicalRegion(f, f.otherRegion());
    var staged = stage(f, f.region(), "Changed", 1, 1);
    var result = store(staged);
    assertThat(result.status()).isEqualTo("STORED_PERMISSION_UNVERIFIED");
    assertThat(regionName(f, f.region())).isEqualTo("Changed");
    assertEpochs(f, f.region(), 2, 2);
    assertThat(physicalRegion(f, f.otherRegion())).isEqualTo(other);
    assertThat(retainedFacts(f)).isEqualTo(original);
    assertThat(dsl.fetchCount(DSL.table("world_region_draft_execution_manifest"))).isZero();
    assertThat(applications.readCommitted(f.graph().application()).orElseThrow().canonicalBytes())
        .containsExactly(f.graph().applied().canonicalBytes());
    assertThat(
            applications
                .readSynchronized(visibility(f))
                .orElseThrow()
                .family(WorldCanonicalAuthoredGraph.Family.REGION))
        .anyMatch(row -> row.content().getRegion().getName().equals("Original"));
    assertThat(
            one(
                    dsl,
                    "SELECT count(*) FROM world_draft_graph_application WHERE commit_id=?",
                    staged.originalOperation().commitId())
                .get(0, Long.class))
        .isZero();
    assertThat(
            one(
                    dsl,
                    "SELECT count(*) FROM world_draft_graph_terminal_identity WHERE commit_id=?",
                    staged.originalOperation().commitId())
                .get(0, Long.class))
        .isZero();
    assertThatThrownBy(
            () ->
                write.execute(
                    status -> {
                      var op = staged.originalOperation();
                      dsl.execute(
                          "INSERT INTO world_draft_graph_application "
                              + "(operation_id,request_id,commit_id,authorization_fence_id,operation_bytes,account_binding_bytes,"
                              + "account_binding_digest,result_bytes,result_digest) VALUES (?,?,?,?,?,?,?,?,?)",
                          op.operationId(),
                          op.requestId(),
                          op.commitId(),
                          op.authorizationFenceId(),
                          op.canonicalBytes(),
                          op.accountBindingBytes(),
                          op.accountBindingDigest(),
                          result.resultBytes(),
                          WorldDraftGraphAppliedResult.digest(result.resultBytes()));
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void staleAggregateRollsBackContentAndCompleteEpochVector() throws Exception {
    var f = fixture();
    var staged = stage(f, f.region(), "Stale aggregate", 0, 1);
    String before = currentFacts(f);
    assertThatThrownBy(() -> store(staged))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("DRAFT_WRITE_CONFLICT");
    assertThat(currentFacts(f)).isEqualTo(before);
    assertThat(repository.readCommitted(staged)).isEmpty();
  }

  @Test
  void staleSubtreeRollsBackContentAndCompleteEpochVector() throws Exception {
    var f = fixture();
    var staged = stage(f, f.region(), "Stale subtree", 1, 0);
    String before = currentFacts(f);
    assertThatThrownBy(() -> store(staged))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("DRAFT_WRITE_CONFLICT");
    assertThat(currentFacts(f)).isEqualTo(before);
    assertThat(repository.readCommitted(staged)).isEmpty();
  }

  @Test
  void exactReplayAfterLaterCurrentChangesReturnsImmutableOriginalReceiptWithoutWrites()
      throws Exception {
    var f = fixture();
    var original = stage(f, f.region(), "First", 1, 1);
    var first = store(original);
    String receiptBefore = history(original);
    store(stage(f, f.region(), "Second", 2, 2));
    store(stage(f, f.otherRegion(), "Disjoint later", 1, 1));
    String beforeReplay = currentFacts(f);
    assertThat(store(original).resultBytes()).containsExactly(first.resultBytes());
    assertThat(repository.readCommitted(original).orElseThrow().graphBytes())
        .containsExactly(first.graphBytes());
    assertThat(history(original)).isEqualTo(receiptBefore);
    assertThat(currentFacts(f)).isEqualTo(beforeReplay);
    byte[] returned = first.resultBytes();
    returned[0] ^= 1;
    assertThat(repository.readCommitted(original).orElseThrow().resultBytes())
        .containsExactly(first.resultBytes());
  }

  @Test
  void reusedBindingChangedOperationAndReviewDigestAreRejectedWithoutEffects() throws Exception {
    var f = fixture();
    var original = stage(f, f.region(), "Original update", 1, 1);
    store(original);
    var binding = original.originalOperation().binding();
    var changed =
        updateBinding(
            f, f.region(), "Changed reuse", 1, 1, binding.requestId(), binding.commitId());
    var changedStage =
        stageBinding(
            f, changed, operation(changed, f.graph().plan().ownerBinding()), REVIEW_DIGEST);
    var changedOperation =
        stageBinding(
            f, binding, operation(binding, f.graph().plan().ownerBinding()), REVIEW_DIGEST);
    var changedReview =
        stageBinding(f, binding, original.originalOperation(), "sha256:" + "b".repeat(64));
    String before = currentFacts(f);
    for (var bad : List.of(changedStage, changedOperation, changedReview)) {
      assertThatThrownBy(() -> store(bad)).isInstanceOf(RuntimeException.class);
    }
    assertThat(currentFacts(f)).isEqualTo(before);
  }

  @Test
  void concurrentOverlappingUpdatesHaveOneWinnerAndDisjointUpdatesPreserveBothChanges()
      throws Exception {
    var f = fixture();
    var first = stage(f, f.region(), "Winner A", 1, 1);
    var second = stage(f, f.region(), "Winner B", 1, 1);
    assertThat(concurrent(first, second).stream().filter(Boolean::booleanValue).count())
        .isEqualTo(1);
    assertEpochs(f, f.region(), 2, 2);
    var disjoint = fixture();
    var a = stage(disjoint, disjoint.region(), "Disjoint A", 1, 1);
    var b = stage(disjoint, disjoint.otherRegion(), "Disjoint B", 1, 1);
    assertThat(concurrent(a, b)).containsExactlyInAnyOrder(true, true);
    assertThat(regionName(disjoint, disjoint.region())).isEqualTo("Disjoint A");
    assertThat(regionName(disjoint, disjoint.otherRegion())).isEqualTo("Disjoint B");
  }

  @Test
  void ordinaryNumericAndMalformedExactManifestsCannotChangeMappedRows() throws Exception {
    var f = fixture();
    var staged = stage(f, f.region(), "Attempt", 1, 1);
    long row = rowKey(f, f.region());
    String before = currentFacts(f);
    assertThatThrownBy(
            () ->
                write.execute(
                    status -> dsl.execute("UPDATE region SET name='numeric' WHERE id=?", row)))
        .isInstanceOf(RuntimeException.class);
    for (String oldOrNew : List.of("old", "new", "transaction", "numeric")) {
      assertThatThrownBy(
              () ->
                  write.execute(
                      status -> {
                        var original =
                            one(dsl, "SELECT to_jsonb(r)::text FROM region r WHERE id=?", row)
                                .get(0, String.class);
                        var after = JSON.readTree(original).deepCopy();
                        ((tools.jackson.databind.node.ObjectNode) after).put("name", "forged");
                        String binding = staged.originalOperation().binding().canonicalJson();
                        if (oldOrNew.equals("numeric"))
                          binding = binding.replace(f.region().toString(), Long.toString(row));
                        dsl.execute(
                            "INSERT INTO world_region_draft_execution_manifest "
                                + "(transaction_id,operation_id,table_name,row_id,row_operation,version_identity_operation_id,"
                                + "local_tenant_key,local_version_key,owner_binding,complete_binding,expected_old,expected_new) "
                                + "VALUES (txid_current()+?,?,'region',?,'UPDATE',?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                            oldOrNew.equals("transaction") ? 1 : 0,
                            staged.originalOperation().commitId(),
                            row,
                            f.graph().plan().ownerBinding().versionIdentityOperationId(),
                            f.graph().sourceIntake().localTenantKey(),
                            f.graph().versionIdentity().localVersionKey(),
                            org.jooq.JSONB.valueOf(
                                WorldDraftTopologyCommitRepository.ownerBindingJson(
                                    f.graph().plan().ownerBinding())),
                            binding,
                            oldOrNew.equals("old") ? "{}" : original,
                            oldOrNew.equals("new") ? "{}" : after.toString());
                        dsl.execute("UPDATE region SET name='forged' WHERE id=?", row);
                        return null;
                      }))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("Mapped UUID World content");
      assertThat(currentFacts(f)).isEqualTo(before);
    }
    assertThatThrownBy(
            () -> write.execute(status -> dsl.execute("DELETE FROM region WHERE id=?", row)))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                write.execute(
                    status ->
                        dsl.execute(
                            "UPDATE world_authored_topology_identity SET revision_order='99' WHERE private_row_key=? AND family='REGION'",
                            row)))
        .isInstanceOf(RuntimeException.class);
    long zone = rowKey(f, f.zone());
    assertThatThrownBy(
            () ->
                write.execute(
                    status -> dsl.execute("UPDATE zone SET name='forged' WHERE id=?", zone)))
        .isInstanceOf(RuntimeException.class);
    assertThat(currentFacts(f)).isEqualTo(before);
  }

  @Test
  void sqlRejectsIncompleteChangedScopeOrCrossNamespaceBinding() throws Exception {
    var f = fixture();
    var staged = stage(f, f.region(), "Attempt", 1, 1);
    var binding = staged.originalOperation().binding();
    String before = currentFacts(f);
    for (String changed :
        List.of(
            binding
                .canonicalJson()
                .replace("\"scopeType\":\"REGION_SUBTREE\"", "\"scopeType\":\"ZONE_SUBTREE\""),
            binding
                .canonicalJson()
                .replace("\"scopeId\":\"" + f.region(), "\"scopeId\":\"" + UUID.randomUUID()))) {
      assertThatThrownBy(
              () ->
                  write.execute(
                      status ->
                          dsl.fetch(
                              "SELECT world_store_guarded_existing_region(?::jsonb,?::jsonb,?::jsonb)",
                              WorldDraftTopologyCommitRepository.ownerBindingJson(
                                  f.graph().plan().ownerBinding()),
                              changed,
                              JSON.writeValueAsString(
                                  WorldDraftExistingRegionCommitRepository.executionRevision(
                                      staged.delta())))))
          .isInstanceOf(RuntimeException.class);
    }
    String wrongOwner =
        WorldDraftTopologyCommitRepository.ownerBindingJson(f.graph().plan().ownerBinding())
            .replace("firemud", "other");
    assertThatThrownBy(
            () ->
                write.execute(
                    status ->
                        dsl.fetch(
                            "SELECT world_store_guarded_existing_region(?::jsonb,?::jsonb,?::jsonb)",
                            wrongOwner,
                            binding.canonicalJson(),
                            JSON.writeValueAsString(
                                WorldDraftExistingRegionCommitRepository.executionRevision(
                                    staged.delta())))))
        .isInstanceOf(RuntimeException.class);
    var changedDeltaBinding =
        updateBinding(
            f,
            f.region(),
            "Mismatched staged payload",
            1,
            1,
            binding.requestId(),
            binding.commitId());
    var forged =
        new StagedDelta(
            staged.originalOperation(),
            staged.companion(),
            staged.historicalBase(),
            WorldDraftExistingRegionCommitPlan.create(
                changedDeltaBinding, staged.originalOperation().ownerBinding()));
    assertThatThrownBy(() -> store(forged))
        .hasMessageContaining("differs from original reviewed operation");
    var visibility = staged.companion().baseVisibility();
    var wrongNamespace =
        new DraftSynchronizedVisibilityEvidence(
            new DraftSynchronizedVisibilityEvidence.Request(
                1, "other", UUID.randomUUID(), visibility.binding().target()),
            visibility.binding(),
            visibility.workflowState(),
            visibility.fence());
    var wrongCompanion =
        new WorldDraftReviewedBaseCompanion(
            binding, staged.companion().reviewedBase(), REVIEW_DIGEST, wrongNamespace);
    var graph = staged.historicalBase();
    var wrongTarget =
        new WorldCanonicalAuthoredGraph(
            UUID.randomUUID(),
            graph.versionId(),
            graph.localTenantKey(),
            graph.localVersionKey(),
            graph.rows(),
            graph.inboundSourceClosure());
    for (var bad :
        List.of(
            new StagedDelta(staged.originalOperation(), wrongCompanion, graph, staged.delta()),
            new StagedDelta(
                staged.originalOperation(), staged.companion(), wrongTarget, staged.delta()))) {
      assertThatThrownBy(() -> store(bad))
          .hasMessageContaining("differs from original reviewed operation");
      assertThatThrownBy(() -> repository.readCommitted(bad))
          .hasMessageContaining("differs from original reviewed operation");
    }

    assertThat(currentFacts(f)).isEqualTo(before);
  }

  @Test
  void abortedOperationCannotStoreAndStoredOperationCannotBecomeNoCommit() throws Exception {
    var f = fixture();
    var aborts =
        new WorldDraftTerminalOutcomeService(
            new WorldDraftTerminalOutcomeRepository(dsl, fence, JSON), manager);
    var aborted = stage(f, f.region(), "Aborted", 1, 1);
    aborts.recordDefinitiveAbort(aborted.originalOperation());
    String before = currentFacts(f);
    assertThatThrownBy(() -> store(aborted))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("definitive no-commit");
    assertThat(currentFacts(f)).isEqualTo(before);
    var stored = stage(f, f.otherRegion(), "Stored", 1, 1);
    store(stored);
    assertThatThrownBy(() -> aborts.recordDefinitiveAbort(stored.originalOperation()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("cannot be relabeled as no-commit");
    assertThat(repository.readCommitted(stored)).isPresent();
  }

  @Test
  void freezeAndUpdateUseSamePhysicalOwnerLockThenFrozenUpdateRefuses() throws Exception {
    var f = fixture();
    var staged = stage(f, f.region(), "Before freeze", 1, 1);
    var ownerLocked = new CountDownLatch(1);
    var releaseOwner = new CountDownLatch(1);
    var writerPid = new AtomicInteger();
    var freezePid = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var writer =
          pool.submit(
              () ->
                  write.execute(
                      status -> {
                        fence.lockOpenAndResolve(staged.originalOperation().ownerBinding());
                        writerPid.set(one(dsl, "SELECT pg_backend_pid()").get(0, Integer.class));
                        ownerLocked.countDown();
                        await(releaseOwner);
                        return repository.store(staged);
                      }));
      assertThat(ownerLocked.await(20, TimeUnit.SECONDS)).isTrue();
      var freezing =
          pool.submit(
              () ->
                  write.execute(
                      status -> {
                        freezePid.set(one(dsl, "SELECT pg_backend_pid()").get(0, Integer.class));
                        return fence.claimFreeze(
                            freeze(f),
                            () ->
                                new WorldDesignPublicationFenceEvidence.Checkpoint(
                                    f.graph().selectedCommit().commitId().toString(),
                                    "c".repeat(64),
                                    3));
                      }));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      boolean blocked = false;
      while (System.nanoTime() < deadline) {
        if (freezePid.get() != 0
            && Boolean.TRUE.equals(
                one(dsl, "SELECT ?=ANY(pg_blocking_pids(?))", writerPid.get(), freezePid.get())
                    .get(0, Boolean.class))) {
          blocked = true;
          break;
        }
        Thread.sleep(20);
      }
      assertThat(blocked).isTrue();
      releaseOwner.countDown();
      assertThat(writer.get(20, TimeUnit.SECONDS)).isNotNull();
      assertThat(freezing.get(20, TimeUnit.SECONDS)).isNotNull();
    } finally {
      releaseOwner.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
    var next = stage(f, f.region(), "After freeze", 2, 2);
    String before = currentFacts(f);
    assertThatThrownBy(() -> store(next)).isInstanceOf(RuntimeException.class);
    assertThat(currentFacts(f)).isEqualTo(before);
    assertThat(store(staged).status()).isEqualTo("STORED_PERMISSION_UNVERIFIED");
  }

  private static WorldDraftExistingRegionStoredEvidence store(StagedDelta staged) {
    return Objects.requireNonNull(write.execute(status -> repository.store(staged)));
  }

  private static List<Boolean> concurrent(StagedDelta first, StagedDelta second) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var futures =
          List.of(first, second).stream()
              .map(
                  staged ->
                      pool.submit(
                          () -> {
                            await(start);
                            try {
                              store(staged);
                              return true;
                            } catch (RuntimeException conflict) {
                              return false;
                            }
                          }))
              .toList();
      start.countDown();
      var result = new ArrayList<Boolean>();
      for (var future : futures) result.add(future.get(30, TimeUnit.SECONDS));
      return result;
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS))
        throw new IllegalStateException("Owner barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static Fixture fixture() throws Exception {
    return fixture(2);
  }

  private static Fixture fixture(int regionCount) throws Exception {
    long sourceRow = NEXT_SOURCE_ROW.incrementAndGet();
    UUID tenant = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    String slug = "fixture-" + tenant.toString().substring(0, 8);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, slug, "world", "World");
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            slug,
            "world",
            "World",
            sourceRow,
            "source",
            "NEW_GAME_ROW",
            AuthoredWorldSourceDigest.evidenceDigest(
                NAMESPACE,
                registration,
                sourceOperation,
                requestDigest,
                tenant,
                slug,
                "world",
                "World",
                sourceRow,
                "source",
                "NEW_GAME_ROW"));
    UUID region = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID zone = UUID.randomUUID();
    UUID room = UUID.randomUUID();
    UUID commit = UUID.randomUUID();
    var declaration =
        WorldFreshGraphDeclaration.newBuilder()
            .setTenantId(tenant.toString())
            .setVersionId(version.toString())
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                    .setTenantId(tenant.toString())
                    .setVersionId(version.toString())
                    .setRoomTemplateId(room.toString()));
    var families =
        List.of(
            "REGION", "ZONE", "ROOM", "ROOM_EXIT", "GENERATION_RULE", "WORLD_ENTITY_SPAWN_BINDING");
    for (String family : families) {
      declaration.addFamilyCounts(
          WorldFreshGraphFamilyCount.newBuilder()
              .setFamily(WorldDesignAggregateType.valueOf("WORLD_DESIGN_AGGREGATE_TYPE_" + family))
              .setCount(
                  family.equals("REGION")
                      ? regionCount
                      : family.equals("ZONE") || family.equals("ROOM") ? 1 : 0));
    }
    var inbound = WorldInboundSourceClosureDeclaration.newBuilder().setSchemaVersion(1);
    for (var family : WorldDraftTopologyInputGraph.INBOUND_SOURCE_FAMILY_ORDER) {
      inbound.addFamilyCounts(
          WorldInboundSourceFamilyCount.newBuilder().setFamily(family).setCount(0));
    }
    declaration.setInboundSourceClosure(inbound);
    var mutations =
        new ArrayList<>(
            List.of(
                mutation(commit, region, region, "REGION", 0, 0)
                    .setRegion(RegionDesignMutation.newBuilder().setName("Original"))
                    .setFreshGraphDeclaration(declaration)
                    .build(),
                mutation(commit, other, other, "REGION", 0, 0)
                    .setRegion(RegionDesignMutation.newBuilder().setName("Other"))
                    .build(),
                mutation(commit, zone, region, "ZONE", 0, 0)
                    .setZone(
                        ZoneDesignMutation.newBuilder()
                            .setName("Zone")
                            .setRegionId(region.toString()))
                    .build(),
                mutation(commit, room, region, "ROOM", 0, 0)
                    .setRoom(
                        RoomDesignMutation.newBuilder().setName("Room").setZoneId(zone.toString()))
                    .build()));
    for (int i = 2; i < regionCount; i++) {
      UUID extra = UUID.randomUUID();
      mutations.add(
          i,
          mutation(commit, extra, extra, "REGION", 0, 0)
              .setRegion(RegionDesignMutation.newBuilder().setName("Extra " + i))
              .build());
    }
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 901, "source", sourceRow, "source", "NEW_GAME_ROW");
    var binding =
        binding(target, UUID.randomUUID(), commit, "genesis:" + UUID.randomUUID(), mutations);
    var graph =
        WorldSelectedFullPublicationEvidenceFixture.applyGraph(
            dsl, manager, NAMESPACE, binding, source);
    return new Fixture(graph, region, other, zone);
  }

  @Test
  void completeBatchChangesTwoRegionsPreservesThirdAndReplaysWholeOperationAfterFreeze()
      throws Exception {
    var f = fixture(3);
    UUID third = region(f, 2);
    store(stage(f, third, "Unrelated current content", 1, 1));
    String unrelated = physicalRegion(f, third);
    String immutable = retainedFacts(f);
    var batch =
        stageBatch(f, List.of(f.region(), f.otherRegion()), List.of(1L, 1L), List.of(1L, 1L));
    var result = store(batch);
    assertThat(result.status()).isEqualTo("STORED_PERMISSION_UNVERIFIED");
    var graph = JSON.readTree(result.graphBytes());
    assertThat(graph.path("schemaVersion").asText()).isEqualTo("2");
    assertThat(graph.path("regions").size()).isEqualTo(2);
    assertThat(graph.path("regions").get(0).path("mapping").path("template_id").asText())
        .isEqualTo(f.region().toString());
    assertThat(graph.path("regions").get(1).path("mapping").path("template_id").asText())
        .isEqualTo(f.otherRegion().toString());
    assertEpochs(f, f.region(), 2, 2);
    assertEpochs(f, f.otherRegion(), 2, 2);
    assertThat(physicalRegion(f, third)).isEqualTo(unrelated);
    assertThat(retainedFacts(f)).isEqualTo(immutable);
    assertThat(
            one(dsl, "SELECT count(*) FROM world_region_draft_execution_manifest")
                .get(0, Long.class))
        .isZero();
    String originalHistory = history(batch);
    store(stage(f, f.region(), "Later first", 2, 2));
    store(stage(f, f.otherRegion(), "Later second", 2, 2));
    write.execute(
        status ->
            fence.claimFreeze(
                freeze(f),
                () ->
                    new WorldDesignPublicationFenceEvidence.Checkpoint(
                        f.graph().selectedCommit().commitId().toString(), "c".repeat(64), 4)));
    String before = currentFacts(f);
    assertThat(store(batch).resultBytes()).containsExactly(result.resultBytes());
    assertThat(repository.readCommitted(batch).orElseThrow().graphBytes())
        .containsExactly(result.graphBytes());
    assertThat(history(batch)).isEqualTo(originalHistory);
    var binding = batch.originalOperation().binding();
    var first = binding.revisions().getFirst();
    var firstMutation = WorldDesignMutationRevision.newBuilder();
    JsonFormat.parser().merge(first.payload(), firstMutation);
    var subset =
        binding(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            binding.baseCommitId(),
            List.of(firstMutation.build()));
    var subsetInput =
        stageBinding(f, subset, operation(subset, f.graph().plan().ownerBinding()), REVIEW_DIGEST);
    assertThatThrownBy(() -> store(subsetInput)).hasMessageContaining("changed complete input");
    var reordered = new ArrayList<WorldDesignMutationRevision>();
    for (var revision : batch.delta().revisions().reversed()) reordered.add(revision.mutation());
    var changed =
        binding(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            binding.baseCommitId(),
            reordered);
    var changedInput =
        stageBinding(
            f, changed, operation(changed, f.graph().plan().ownerBinding()), REVIEW_DIGEST);
    assertThatThrownBy(() -> store(changedInput)).hasMessageContaining("changed complete input");
    assertThat(currentFacts(f)).isEqualTo(before);
    assertThat(retainedFacts(f)).isEqualTo(immutable);
  }

  @Test
  void staleLastSubtreeRollsBackEveryEarlierBatchMutationEpochAndReceipt() throws Exception {
    var f = fixture();
    var batch =
        stageBatch(f, List.of(f.region(), f.otherRegion()), List.of(1L, 1L), List.of(1L, 0L));
    String before = currentFacts(f);
    String retained = retainedFacts(f);
    assertThatThrownBy(() -> store(batch)).hasStackTraceContaining("DRAFT_WRITE_CONFLICT");
    assertThat(currentFacts(f)).isEqualTo(before);
    assertThat(retainedFacts(f)).isEqualTo(retained);
    assertThat(repository.readCommitted(batch)).isEmpty();
    assertThat(
            one(dsl, "SELECT count(*) FROM world_region_draft_execution_manifest")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void exactConcurrentBatchReplayAndOverlappingReversedBatchesHaveNoPartialWinner()
      throws Exception {
    var exactFixture = fixture();
    var exact =
        stageBatch(
            exactFixture,
            List.of(exactFixture.region(), exactFixture.otherRegion()),
            List.of(1L, 1L),
            List.of(1L, 1L));
    assertThat(concurrent(exact, exact)).containsExactly(true, true);
    assertThat(
            one(
                    dsl,
                    "SELECT count(*) FROM world_region_draft_commit WHERE request_id=?",
                    exact.originalOperation().requestId())
                .get(0, Long.class))
        .isEqualTo(1);
    var f = fixture();
    var first =
        stageBatch(f, List.of(f.region(), f.otherRegion()), List.of(1L, 1L), List.of(1L, 1L));
    var reverse =
        stageBatch(f, List.of(f.otherRegion(), f.region()), List.of(1L, 1L), List.of(1L, 1L));
    assertThat(concurrent(first, reverse).stream().filter(Boolean::booleanValue).count())
        .isEqualTo(1);
    assertEpochs(f, f.region(), 2, 2);
    assertEpochs(f, f.otherRegion(), 2, 2);
    assertThat(
            one(
                    dsl,
                    "SELECT count(*) FROM world_region_draft_commit WHERE canonical_tenant_id=?",
                    f.graph().selectedCommit().target().canonicalTenantId())
                .get(0, Long.class))
        .isEqualTo(1);
    assertThat(
            one(dsl, "SELECT count(*) FROM world_region_draft_execution_manifest")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void disjointCompleteBatchesFromOneReviewedBasePreserveBothCurrentChanges() throws Exception {
    var f = fixture(4);
    var first =
        stageBatch(f, List.of(f.region(), f.otherRegion()), List.of(1L, 1L), List.of(1L, 1L));
    var second =
        stageBatch(f, List.of(region(f, 2), region(f, 3)), List.of(1L, 1L), List.of(1L, 1L));
    String retained = retainedFacts(f);
    assertThat(concurrent(first, second)).containsExactly(true, true);
    for (int i = 0; i < 4; i++) assertEpochs(f, region(f, i), 2, 2);
    assertThat(retainedFacts(f)).isEqualTo(retained);
    assertThat(repository.readCommitted(first)).isPresent();
    assertThat(repository.readCommitted(second)).isPresent();
  }

  @Test
  void completeBatchSqlRejectsMissingReorderedDuplicateAndChangedWholeVectorsBeforeEffects()
      throws Exception {
    var f = fixture();
    var staged =
        stageBatch(f, List.of(f.region(), f.otherRegion()), List.of(1L, 1L), List.of(1L, 1L));
    var executions = WorldDraftExistingRegionCommitRepository.executionRevisions(staged.delta());
    var variants =
        List.of(
            List.of(executions.getFirst()),
            executions.reversed(),
            List.of(executions.getFirst(), executions.getFirst()));
    for (var variant : variants) {
      String before = currentFacts(f);
      assertThatThrownBy(
              () ->
                  write.execute(
                      status ->
                          dsl.fetch(
                              "SELECT world_store_guarded_existing_regions(?::jsonb,?::jsonb,?::jsonb)",
                              WorldDraftTopologyCommitRepository.ownerBindingJson(
                                  staged.originalOperation().ownerBinding()),
                              staged.originalOperation().binding().canonicalJson(),
                              JSON.writeValueAsString(variant))))
          .isInstanceOf(RuntimeException.class);
      assertThat(currentFacts(f)).isEqualTo(before);
    }
    var changed =
        (tools.jackson.databind.node.ObjectNode)
            JSON.readTree(staged.originalOperation().binding().canonicalJson());
    ((tools.jackson.databind.node.ArrayNode) changed.path("affectedUnits")).remove(0);
    String before = currentFacts(f);
    assertThatThrownBy(
            () ->
                write.execute(
                    status ->
                        dsl.fetch(
                            "SELECT world_store_guarded_existing_regions(?::jsonb,?::jsonb,?::jsonb)",
                            WorldDraftTopologyCommitRepository.ownerBindingJson(
                                staged.originalOperation().ownerBinding()),
                            changed.toString(),
                            JSON.writeValueAsString(executions))))
        .hasStackTraceContaining("complete exact UUID");
    assertThat(currentFacts(f)).isEqualTo(before);
  }

  @Test
  void originalSingleReceiptRemainsByteExactAfterBatchUpgradeAndLaterChanges() throws Exception {
    assertThat(repository.readCommitted(retainedV1Input).orElseThrow().resultBytes())
        .containsExactly(retainedV1.resultBytes());
    assertThat(store(retainedV1Input).graphBytes()).containsExactly(retainedV1.graphBytes());
    assertThat(history(retainedV1Input)).isEqualTo(retainedV1History);
    assertThat(new String(retainedV1.resultBytes(), StandardCharsets.UTF_8))
        .contains("world-draft-existing-region-storage/v1");
    assertThat(JSON.readTree(retainedV1.graphBytes()).has("regions")).isFalse();
  }

  @Test
  void retainedRegionReceiptCorruptionFailsActualIndependentReadWithoutWrites() throws Exception {
    var f = fixture();
    var batch =
        stageBatch(f, List.of(f.region(), f.otherRegion()), List.of(1L, 1L), List.of(1L, 1L));
    var batchResult = store(batch);
    var canonicalBefore = canonicalReceiptOwnerRows();
    for (var staged : List.of(retainedV1Input, batch)) {
      var original = repository.readCommitted(staged).orElseThrow();
      assertThat(original.resultBytes())
          .containsExactly(staged == batch ? batchResult.resultBytes() : retainedV1.resultBytes());
      byte[] corrupt = original.resultBytes();
      corrupt[0] ^= 1;
      assertThat(corrupt).isNotEqualTo(original.resultBytes());
      dsl.connection(
          connection -> {
            assertThat(connection.getAutoCommit()).isTrue();
            var clone = DSL.using(connection, SQLDialect.POSTGRES);
            assertThat(one(clone, "SHOW session_replication_role").get(0, String.class))
                .isEqualTo("origin");
            // Negative retained-row fixture only. The canonical table and every installed guard
            // remain untouched; a connection-local TEMP shadow supplies the damaged reader input.
            clone.execute(
                "CREATE TEMP TABLE world_region_draft_commit AS SELECT * FROM "
                    + SCHEMA
                    + ".world_region_draft_commit WHERE request_id=?",
                staged.originalOperation().requestId());
            assertThat(
                    one(
                            clone,
                            "SELECT pg_my_temp_schema()=relnamespace FROM pg_class "
                                + "WHERE oid='world_region_draft_commit'::regclass")
                        .get(0, Boolean.class))
                .isTrue();
            var reader =
                new WorldDraftExistingRegionCommitRepository(
                    clone,
                    new WorldDesignPublicationFenceRepository(
                        clone, new WorldAuthoredSourceIntakeRepository(clone)),
                    JSON);
            // Prove this exact real reader accepts the unmodified copied v1/v2 receipt first.
            assertThat(reader.readCommitted(staged).orElseThrow().resultBytes())
                .containsExactly(original.resultBytes());
            String before =
                one(
                        clone,
                        "SELECT concat(to_jsonb(t)::text,'|xmin=',xmin::text) "
                            + "FROM world_region_draft_commit t")
                    .get(0, String.class);
            assertThat(
                    clone.execute(
                        "UPDATE world_region_draft_commit SET result_bytes=? WHERE request_id=?",
                        corrupt,
                        staged.originalOperation().requestId()))
                .isEqualTo(1);
            assertThat(
                    one(clone, "SELECT result_bytes FROM world_region_draft_commit")
                        .get(0, byte[].class))
                .containsExactly(corrupt);
            String damaged =
                one(
                        clone,
                        "SELECT concat(to_jsonb(t)::text,'|xmin=',xmin::text) "
                            + "FROM world_region_draft_commit t")
                    .get(0, String.class);
            assertThat(damaged).isNotEqualTo(before);
            assertThatThrownBy(() -> reader.readCommitted(staged))
                .isExactlyInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
                .hasMessage(
                    "Retained existing REGION bytes differ from the complete original input");
            assertThat(
                    one(
                            clone,
                            "SELECT concat(to_jsonb(t)::text,'|xmin=',xmin::text) "
                                + "FROM world_region_draft_commit t")
                        .get(0, String.class))
                .isEqualTo(damaged);
            // The independent normal connection still reads the untouched original receipt.
            assertThat(repository.readCommitted(staged).orElseThrow().resultBytes())
                .containsExactly(original.resultBytes());
            assertThat(connection.getAutoCommit()).isTrue();
          });
      assertThat(canonicalReceiptOwnerRows()).isEqualTo(canonicalBefore);
      assertThat(repository.readCommitted(staged).orElseThrow().graphBytes())
          .containsExactly(original.graphBytes());
    }
    assertThat(one(dsl, "SHOW session_replication_role").get(0, String.class)).isEqualTo("origin");
  }

  private static java.util.Map<String, List<String>> canonicalReceiptOwnerRows() {
    var rows = new LinkedHashMap<String, List<String>>();
    for (String table :
        List.of(
            "region",
            "zone",
            "room",
            "room_exit",
            "generation_rule",
            "world_entity_spawn_binding",
            "world_authored_topology_identity",
            "world_design_aggregate_epoch",
            "world_design_scope_epoch",
            "world_topology_draft_commit",
            "world_authored_source_intake",
            "world_authored_version_identity",
            "world_authored_source_tenant_association",
            "world_authored_source_tenant_key_reservation",
            "world_design_publication_fence_owner",
            "world_design_publication_fence_attempt",
            "world_authored_graph_snapshot",
            "world_design_revision_ledger",
            "world_draft_graph_application",
            "world_canonical_frozen_topology",
            "world_region_draft_execution_manifest",
            "world_draft_start_location_receipt",
            "world_draft_graph_terminal_identity",
            "world_draft_terminal_outcome",
            "world_design_publication_account_binding",
            "world_selected_publication_artifact_inventory",
            "world_design_publication_terminal",
            "world_region_draft_commit")) {
      rows.put(
          table,
          dsl.fetch(
                  "SELECT concat(to_jsonb(t)::text,'|xmin=',xmin::text) FROM "
                      + SCHEMA
                      + "."
                      + table
                      + " t ORDER BY to_jsonb(t)::text,xmin::text")
              .getValues(0, String.class));
    }
    return java.util.Map.copyOf(rows);
  }

  private static UUID region(Fixture f, int index) {
    return f.graph().plan().graph().nodes().stream()
        .filter(node -> node.mutation().hasRegion())
        .map(node -> node.templateId())
        .toList()
        .get(index);
  }

  private static StagedDelta stageBatch(
      Fixture f, List<UUID> regions, List<Long> aggregate, List<Long> scope) throws Exception {
    UUID commit = UUID.randomUUID();
    var mutations = new ArrayList<WorldDesignMutationRevision>();
    for (int i = 0; i < regions.size(); i++) {
      UUID region = regions.get(i);
      mutations.add(
          mutation(commit, region, region, "REGION", aggregate.get(i), scope.get(i))
              .setRegion(
                  RegionDesignMutation.newBuilder()
                      .setName("Batch " + region)
                      .setGenerationSeed(Long.MAX_VALUE))
              .build());
    }
    var binding =
        binding(
            f.graph().selectedCommit().target(),
            UUID.randomUUID(),
            commit,
            f.graph().selectedCommit().commitId().toString(),
            mutations);
    return stageBinding(
        f, binding, operation(binding, f.graph().plan().ownerBinding()), REVIEW_DIGEST);
  }

  private static WorldDraftExistingRegionStoredEvidence storeV1(StagedDelta staged) {
    return Objects.requireNonNull(
        write.execute(
            status -> {
              var plan = staged.delta();
              var binding = plan.binding();
              var owner = fence.lockOpenAndResolve(plan.ownerBinding());
              String snapshot =
                  one(
                          dsl,
                          "SELECT world_store_guarded_existing_region(?::jsonb,?::jsonb,?::jsonb)::text",
                          WorldDraftTopologyCommitRepository.ownerBindingJson(plan.ownerBinding()),
                          binding.canonicalJson(),
                          JSON.writeValueAsString(
                              WorldDraftExistingRegionCommitRepository.executionRevision(plan)))
                      .get(0, String.class);
              var stored = WorldDraftExistingRegionStoredEvidence.create(staged, snapshot);
              dsl.execute(
                  "INSERT INTO world_region_draft_commit (target_namespace,canonical_tenant_id,canonical_version_id,"
                      + "request_id,commit_id,version_identity_operation_id,local_tenant_key,local_version_key,"
                      + "owner_binding_json,binding_json,binding_digest,graph_bytes,graph_sha256,result_bytes,storage_status)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                  plan.ownerBinding().targetNamespace(),
                  binding.target().canonicalTenantId(),
                  binding.target().canonicalVersionId(),
                  binding.requestId(),
                  binding.commitId(),
                  plan.ownerBinding().versionIdentityOperationId(),
                  owner.localTenantKey(),
                  owner.localVersionKey(),
                  WorldDraftTopologyCommitRepository.ownerBindingJson(plan.ownerBinding()),
                  binding.canonicalJson(),
                  binding.digest(),
                  stored.graphBytes(),
                  WorldAuthoredGraphSnapshotCapture.sha256(stored.graphBytes()),
                  stored.resultBytes(),
                  WorldDraftExistingRegionStoredEvidence.STATUS);
              return stored;
            }));
  }

  private static DraftCommitBinding binding(
      DraftCommitBinding.TargetProof target,
      UUID request,
      UUID commit,
      String base,
      List<WorldDesignMutationRevision> mutations)
      throws Exception {
    var revisions = new ArrayList<RevisionPayload>();
    var units = new ArrayList<AffectedUnit>();
    for (var mutation : mutations) {
      String family =
          mutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      revisions.add(
          new RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(mutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().print(mutation)));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "AGGREGATE",
              mutation.getAggregateId(),
              Long.toString(mutation.getExpectedDraftRevisionEpoch())));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "REGION_SUBTREE",
              mutation.getScopeId(),
              Long.toString(mutation.getExpectedDraftScopeRevisionEpoch())));
    }
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "controlled Game Design fixture"));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE, "SOURCE", "source", "AGGREGATE", "source", "0"));
    return DraftCommitBinding.create(target, request, commit, base, revisions, units);
  }

  private static WorldDesignMutationRevision.Builder mutation(
      UUID commit,
      UUID aggregate,
      UUID scope,
      String family,
      long aggregateEpoch,
      long scopeEpoch) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateType(WorldDesignAggregateType.valueOf("WORLD_DESIGN_AGGREGATE_TYPE_" + family))
        .setAggregateId(aggregate.toString())
        .setScopeId(scope.toString())
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setExpectedDraftRevisionEpoch(aggregateEpoch)
        .setExpectedDraftScopeRevisionEpoch(scopeEpoch);
  }

  private static StagedDelta stage(Fixture f, UUID region, String name, long aggregate, long scope)
      throws Exception {
    var binding =
        updateBinding(f, region, name, aggregate, scope, UUID.randomUUID(), UUID.randomUUID());
    return stageBinding(
        f, binding, operation(binding, f.graph().plan().ownerBinding()), REVIEW_DIGEST);
  }

  private static DraftCommitBinding updateBinding(
      Fixture f, UUID region, String name, long aggregate, long scope, UUID request, UUID commit)
      throws Exception {
    var mutation =
        mutation(commit, region, region, "REGION", aggregate, scope)
            .setRegion(
                RegionDesignMutation.newBuilder()
                    .setName(name)
                    .setWeather("rain")
                    .setGenerationSeed(Long.MAX_VALUE)
                    .setSpacingMultiplier(0))
            .build();
    return binding(
        f.graph().selectedCommit().target(),
        request,
        commit,
        f.graph().selectedCommit().commitId().toString(),
        List.of(mutation));
  }

  private static StagedDelta stageBinding(
      Fixture f,
      DraftCommitBinding binding,
      WorldDraftTerminalOperation operation,
      String reviewDigest) {
    var companion =
        new WorldDraftReviewedBaseCompanion(
            binding, DraftBaseReference.parse(binding.baseCommitId()), reviewDigest, visibility(f));
    return new WorldDraftReviewedBaseStaging(applications)
        .readAndStage(NAMESPACE, operation, companion);
  }

  private static WorldDraftTerminalOperation operation(
      DraftCommitBinding binding, WorldDesignPublicationFenceEvidence.OwnerBinding owner) {
    UUID operation = UUID.randomUUID();
    UUID fenceId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    var account =
        new DraftAuthorizationFenceBinding(
                operation,
                binding.requestId(),
                binding.commitId(),
                fenceId,
                actor,
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.baseCommitId(),
                "0",
                binding.canonicalBytes(),
                binding.canonicalBytes(),
                binding.digest(),
                List.of(
                    new DraftAuthorizationFenceBinding.SourceEvidence(
                        DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                        actor.toString(),
                        "1",
                        "1",
                        null,
                        null,
                        "controlled original Account input".getBytes(StandardCharsets.UTF_8))))
            .withRequiredOwners();
    return new WorldDraftTerminalOperation(
        operation,
        binding.requestId(),
        binding.commitId(),
        fenceId,
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding,
        owner,
        account.canonicalBytes());
  }

  private static DraftSynchronizedVisibilityEvidence visibility(Fixture f) {
    var binding = f.graph().selectedCommit();
    var result = f.graph().applied();
    var vector =
        binding.requiredOwners().stream()
            .map(
                owner -> {
                  var item = new LinkedHashMap<String, Object>();
                  item.put("owner", owner.name());
                  item.put("status", "APPLIED");
                  item.put("commitId", binding.commitId().toString());
                  item.put("bindingDigest", binding.digest());
                  item.put(
                      "resultIdentity",
                      owner == Owner.WORLD_MANAGEMENT
                          ? result.resultIdentity()
                          : "controlled-game-design");
                  item.put(
                      "resultBytesBase64",
                      Base64.getEncoder()
                          .encodeToString(
                              owner == Owner.WORLD_MANAGEMENT
                                  ? result.canonicalBytes()
                                  : new byte[] {1}));
                  item.put(
                      "appliedEpochs",
                      owner == Owner.WORLD_MANAGEMENT
                          ? result.appliedEpochs()
                          : List.of(
                              new AppliedEpoch(
                                  "SOURCE", "source", "AGGREGATE", "source", "0", "1")));
                  return item;
                })
            .toList();
    try {
      String canonical =
          new String(
              Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(vector)),
              StandardCharsets.UTF_8);
      return new DraftSynchronizedVisibilityEvidence(
          new DraftSynchronizedVisibilityEvidence.Request(
              1, NAMESPACE, UUID.randomUUID(), binding.target()),
          binding,
          "SYNCHRONIZED",
          new DraftSynchronizedVisibilityEvidence.Fence(
              binding.requestId(),
              binding.commitId(),
              binding.digest(),
              canonical,
              OffsetDateTime.parse("2026-11-16T00:00:00Z")));
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException(invalid);
    }
  }

  private static WorldDesignPublicationFenceEvidence freeze(Fixture f) {
    var owner = f.graph().plan().ownerBinding();
    String publication = UUID.randomUUID().toString();
    return new WorldDesignPublicationFenceEvidence(
        owner.targetNamespace(),
        owner.canonicalTenantId(),
        owner.canonicalVersionId(),
        owner.versionIdentityOperationId(),
        owner.gameDesignVersionId(),
        owner.intakeRequestId(),
        owner.intakeOperationId(),
        owner.intakeRequestDigest(),
        owner.sourceOperationId(),
        owner.sourceEvidenceDigest(),
        owner.intakeReceiptDigest(),
        publication,
        "d".repeat(64),
        1,
        PublicationDigestRequestBinding.full(
                owner.canonicalTenantId().toString(),
                Long.toString(owner.gameDesignVersionId()),
                publication)
            .derivedWorkflowIdentity());
  }

  private static DriverManagerDataSource source(String schema) {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    return source;
  }

  private static long rowKey(Fixture f, UUID template) {
    return one(
            dsl,
            "SELECT private_row_key FROM world_authored_topology_identity "
                + "WHERE canonical_tenant_id=? AND canonical_version_id=? AND template_id=?",
            f.graph().selectedCommit().target().canonicalTenantId(),
            f.graph().selectedCommit().target().canonicalVersionId(),
            template)
        .get(0, Long.class);
  }

  private static String regionName(Fixture f, UUID region) {
    return one(dsl, "SELECT name FROM region WHERE id=?", rowKey(f, region)).get(0, String.class);
  }

  private static String physicalRegion(Fixture f, UUID region) {
    return one(
            dsl,
            "SELECT jsonb_build_object('row',to_jsonb(r),'xmin',xmin::text)::text FROM region r WHERE id=?",
            rowKey(f, region))
        .get(0, String.class);
  }

  private static void assertEpochs(Fixture f, UUID region, long aggregate, long scope) {
    assertThat(
            one(
                    dsl,
                    "SELECT draft_revision_epoch FROM world_design_aggregate_epoch "
                        + "WHERE tenant_id=? AND version_id=? AND aggregate_type='REGION' AND aggregate_id=?",
                    f.graph().sourceIntake().localTenantKey(),
                    f.graph().versionIdentity().localVersionKey(),
                    rowKey(f, region))
                .get(0, Long.class))
        .isEqualTo(aggregate);
    assertThat(
            one(
                    dsl,
                    "SELECT draft_scope_revision_epoch FROM world_design_scope_epoch "
                        + "WHERE tenant_id=? AND version_id=? AND scope_type='REGION_SUBTREE' AND scope_id=?",
                    f.graph().sourceIntake().localTenantKey(),
                    f.graph().versionIdentity().localVersionKey(),
                    region.toString())
                .get(0, Long.class))
        .isEqualTo(scope);
  }

  private static String retainedFacts(Fixture f) {
    var commit = f.graph().selectedCommit().commitId();
    return JSON.writeValueAsString(
        List.of(
            tableFacts("world_authored_topology_identity", "commit_id", commit),
            tableFacts("world_topology_draft_commit", "commit_id", commit),
            tableFacts("world_draft_graph_application", "commit_id", commit),
            tableFacts("world_draft_start_location_receipt", "commit_id", commit)));
  }

  private static String tableFacts(String table, String field, Object value) {
    return one(
            dsl,
            "SELECT coalesce(jsonb_agg(jsonb_build_object('row',to_jsonb(t),'xmin',xmin::text)"
                + " ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "
                + table
                + " t WHERE "
                + field
                + "=?",
            value)
        .get(0, String.class);
  }

  private static String currentFacts(Fixture f) {
    long tenant = f.graph().sourceIntake().localTenantKey();
    return JSON.writeValueAsString(
        List.of(
            tableFacts("region", "tenant_id", tenant),
            tableFacts("zone", "tenant_id", tenant),
            tableFacts("room", "tenant_id", tenant),
            tableFacts("world_design_aggregate_epoch", "tenant_id", tenant),
            tableFacts("world_design_scope_epoch", "tenant_id", tenant),
            tableFacts(
                "world_region_draft_commit",
                "canonical_tenant_id",
                f.graph().selectedCommit().target().canonicalTenantId())));
  }

  private static String history(StagedDelta staged) {
    return tableFacts(
        "world_region_draft_commit", "request_id", staged.originalOperation().requestId());
  }

  private static String function(String signature) {
    return one(dsl, "SELECT pg_get_functiondef(?::regprocedure)", SCHEMA + "." + signature)
        .get(0, String.class);
  }

  private static String catalogFacts() {
    return one(
            dsl,
            "SELECT jsonb_agg(to_jsonb(e) ORDER BY catalog_epoch)::text "
                + "FROM world_runtime_family_catalog_epoch e")
        .get(0, String.class);
  }

  private static void assertCatalogSeal(DSLContext context, long expectedEpoch) {
    assertThat(
            one(context, "SELECT world_runtime_family_catalog_installed()").get(0, Boolean.class))
        .isTrue();
    var seal =
        one(
            context,
            "SELECT catalog_bytes=world_runtime_family_catalog_bytes(catalog_epoch),"
                + "catalog_digest='sha256:'||encode(sha256(catalog_bytes),'hex'), catalog_epoch "
                + "FROM world_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1");
    assertThat(seal.get(0, Boolean.class)).isTrue();
    assertThat(seal.get(1, Boolean.class)).isTrue();
    assertThat(seal.get(2, Long.class)).isEqualTo(expectedEpoch);
  }

  private static org.jooq.Record one(DSLContext context, String sql, Object... bindings) {
    return Objects.requireNonNull(
        context.fetchOne(sql, bindings), "Expected exact physical query row");
  }

  private record Fixture(AppliedGraph graph, UUID region, UUID otherRegion, UUID zone) {}
}
