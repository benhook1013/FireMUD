package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.util.JsonFormat;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImpl;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalFrozenTopology.Request;
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
import org.jooq.ExecuteListener;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
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
 * Current-schema canonical journal proof with real source/APPLIED/freeze/capture SQL owners.
 * Upstream source, original Account order and full-commit selection are controlled inputs, never a
 * genuine Game Design producer or protected COMMIT_ORDER grant. No bypass seeds positive history.
 * The journal remains CAPTURED_UNVERIFIED; this supplies no update APPLIED, release or activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldCanonicalFrozenTopologyJournalIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String NAMESPACE = "firemud";
  private static final String SCHEMA =
      "world_journal_" + UUID.randomUUID().toString().replace("-", "");
  private static final AtomicLong NEXT_SOURCE = new AtomicLong(2000);
  private static final List<String> OWNER_TABLES =
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
          "world_region_draft_commit");
  private static DriverManagerDataSource source;
  private static DSLContext dsl;
  private static DataSourceTransactionManager manager;
  private static TransactionTemplate write;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void installCurrentSchema() {
    source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(SCHEMA);
    migration(source, SCHEMA, null).migrate();
    dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    manager = new DataSourceTransactionManager(source);
    write = new TransactionTemplate(manager);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertOrigin();
  }

  @Test
  void actualFirstCaptureAndDetachedExactReadsRetainAllOriginalBytesWithoutOtherWrites()
      throws Exception {
    var fixture = fixture();
    assertThat(count(fixture.request())).isZero();
    var before = snapshotRows();
    var captured = service(dsl).capture(fixture.request());
    assertThat(captured.status()).isEqualTo("CAPTURED_UNVERIFIED");
    assertThat(captured.request().freeze().digestSchemaVersion()).isEqualTo(4);
    assertThat(captured.graphBytes()).containsExactly(fixture.applied().applied().graphBytes());
    assertThat(captured.storageResultBytes())
        .containsExactly(
            topology(dsl).readCommitted(fixture.applied().plan()).orElseThrow().resultBytes());
    assertThat(count(fixture.request())).isEqualTo(1);
    var after = snapshotRows();
    var expectedUnchanged = new LinkedHashMap<>(before);
    expectedUnchanged.remove("world_canonical_frozen_topology");
    var actualUnchanged = new LinkedHashMap<>(after);
    actualUnchanged.remove("world_canonical_frozen_topology");
    assertThat(actualUnchanged).isEqualTo(expectedUnchanged);

    var deniedOwner = mock(WorldAuthoredGraphSnapshotRepository.class);
    var deniedDigest = mock(WorldDraftDesignDigestService.class);
    var detached =
        new WorldCanonicalFrozenTopologyRepository(dsl, deniedOwner, topology(dsl), deniedDigest);
    var exact = new WorldCanonicalFrozenTopologyService(detached, manager);
    assertThat(detached.readCommitted(fixture.request()).orElseThrow().resultBytes())
        .containsExactly(captured.resultBytes());
    assertThat(detached.readCommitted(fixture.request().freeze()).orElseThrow().captureId())
        .isEqualTo(captured.captureId());
    assertThat(exact.capture(fixture.request()).captureId()).isEqualTo(captured.captureId());
    assertThat(exact.readCommitted(fixture.request()).orElseThrow().graphBytes())
        .containsExactly(captured.graphBytes());
    verifyNoInteractions(deniedOwner, deniedDigest);
    assertThat(snapshotRows()).isEqualTo(after);

    // The installed immutability trigger, not a row manufactured under disabled guards.
    assertThatThrownBy(
            () ->
                write.execute(
                    status -> {
                      dsl.execute(
                          "UPDATE world_canonical_frozen_topology SET result_bytes='changed'::bytea WHERE publication_fence=?",
                          fixture.request().freeze().publicationFence());
                      throw new AssertionError(
                          "Journal UPDATE guard unexpectedly permitted a write");
                    }))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining("immutable");
    assertThatThrownBy(
            () ->
                write.execute(
                    status -> {
                      dsl.execute(
                          "DELETE FROM world_canonical_frozen_topology WHERE publication_fence=?",
                          fixture.request().freeze().publicationFence());
                      throw new AssertionError(
                          "Journal DELETE guard unexpectedly permitted a write");
                    }))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasStackTraceContaining("immutable");
    assertThat(snapshotRows()).isEqualTo(after);
    assertOrigin();
  }

  @Test
  void materialCheckpointAndChangedCompleteBindingRefuseExactReplayWithoutWrites()
      throws Exception {
    var fixture = fixture();
    var original = service(dsl).capture(fixture.request());
    var originalRequest = fixture.request();
    var r = originalRequest.freeze();
    var binding = originalRequest.plan().binding();
    var changedBinding =
        DraftCommitBinding.create(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            UUID.randomUUID().toString(),
            binding.revisions(),
            binding.affectedUnits());
    var changedPlan =
        WorldDraftTopologyCommitPlan.create(changedBinding, originalRequest.plan().ownerBinding());
    var variants = new ArrayList<Request>();
    variants.add(new Request(changedPlan, r));
    variants.add(
        new Request(
            originalRequest.plan(),
            copy(
                r,
                flipped(r.requestDigest()),
                r.contentDigest(),
                r.versionStateEpoch(),
                r.publicationRequestId())));
    variants.add(
        new Request(
            originalRequest.plan(),
            copy(
                r,
                r.requestDigest(),
                flipped(r.contentDigest()),
                r.versionStateEpoch(),
                r.publicationRequestId())));
    variants.add(
        new Request(
            originalRequest.plan(),
            copy(
                r,
                r.requestDigest(),
                r.contentDigest(),
                r.versionStateEpoch() + 1,
                r.publicationRequestId())));
    variants.add(
        new Request(
            originalRequest.plan(),
            copy(
                r,
                r.requestDigest(),
                r.contentDigest(),
                r.versionStateEpoch(),
                r.publicationRequestId() + "-changed")));
    var deniedOwner = mock(WorldAuthoredGraphSnapshotRepository.class);
    var deniedDigest = mock(WorldDraftDesignDigestService.class);
    var journal =
        new WorldCanonicalFrozenTopologyRepository(dsl, deniedOwner, topology(dsl), deniedDigest);
    var owner = new WorldCanonicalFrozenTopologyService(journal, manager);
    var before = snapshotRows();
    for (var changed : variants) {
      assertThatThrownBy(() -> journal.readCommitted(changed))
          .hasMessageContaining("changed complete input or checkpoint");
      if (changed.freeze().equals(r)) {
        // The selector-only overload reconstructs its binding from retained history.
        assertThat(journal.readCommitted(changed.freeze()).orElseThrow().captureId())
            .isEqualTo(original.captureId());
      } else {
        assertThatThrownBy(() -> journal.readCommitted(changed.freeze()))
            .hasMessageContaining("changed complete input or checkpoint");
      }
      assertThatThrownBy(() -> owner.capture(changed))
          .hasMessageContaining("changed complete input or checkpoint");
      assertThat(snapshotRows()).isEqualTo(before);
    }
    assertThat(journal.readCommitted(originalRequest).orElseThrow().resultBytes())
        .containsExactly(original.resultBytes());
    verifyNoInteractions(deniedOwner, deniedDigest);
    assertThat(snapshotRows()).isEqualTo(before);
    assertOrigin();
  }

  @Test
  void twoFirstCapturesActuallyBlockOnTheSameOwnerThenReturnOneImmutableJournal() throws Exception {
    var fixture = fixture();
    assertThat(count(fixture.request())).isZero();
    var before = snapshotRows();
    var atOwnerLock = new CountDownLatch(2);
    var firstPid = new AtomicInteger();
    var secondPid = new AtomicInteger();
    var first = service(observeOwnerLock(firstPid, atOwnerLock));
    var second = service(observeOwnerLock(secondPid, atOwnerLock));
    var pool = Executors.newFixedThreadPool(2);
    try (Connection blocker = source.getConnection()) {
      blocker.setAutoCommit(false);
      try (var lock =
          blocker.prepareStatement(
              "SELECT 1 FROM world_design_publication_fence_owner "
                  + "WHERE target_namespace=? AND canonical_tenant_id=? AND version_id=? FOR UPDATE")) {
        lock.setString(1, NAMESPACE);
        lock.setObject(2, fixture.applied().selectedCommit().target().canonicalTenantId());
        lock.setLong(3, fixture.applied().versionIdentity().localVersionKey());
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
        }
      }
      int blockerPid = blocker.unwrap(PGConnection.class).getBackendPID();
      try {
        var one = pool.submit(() -> first.capture(fixture.request()));
        var two = pool.submit(() -> second.capture(fixture.request()));
        assertThat(atOwnerLock.await(20, TimeUnit.SECONDS)).isTrue();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean bothBlocked = false;
        while (System.nanoTime() < end) {
          var blocking =
              dsl.fetchSingle(
                  "SELECT ?=ANY(pg_blocking_pids(?)) AS first_held,"
                      + "?=ANY(pg_blocking_pids(?)) AS second_held,"
                      + "?=ANY(pg_blocking_pids(?)) AS first_waits_second,"
                      + "?=ANY(pg_blocking_pids(?)) AS second_waits_first",
                  blockerPid,
                  firstPid.get(),
                  blockerPid,
                  secondPid.get(),
                  secondPid.get(),
                  firstPid.get(),
                  firstPid.get(),
                  secondPid.get());
          boolean firstHeld = Boolean.TRUE.equals(blocking.get("first_held", Boolean.class));
          boolean secondHeld = Boolean.TRUE.equals(blocking.get("second_held", Boolean.class));
          boolean firstWaitsSecond =
              Boolean.TRUE.equals(blocking.get("first_waits_second", Boolean.class));
          boolean secondWaitsFirst =
              Boolean.TRUE.equals(blocking.get("second_waits_first", Boolean.class));
          // PostgreSQL reports soft queue blockers as well as the current row-lock holder.
          boolean firstBlocked = firstHeld || (firstWaitsSecond && secondHeld);
          boolean secondBlocked = secondHeld || (secondWaitsFirst && firstHeld);
          if (firstBlocked && secondBlocked) {
            bothBlocked = true;
            break;
          }
          Thread.sleep(10);
        }
        assertThat(firstPid.get()).isNotEqualTo(secondPid.get());
        assertThat(bothBlocked)
            .as("Both first-capture transactions wait on the exact held V25 owner")
            .isTrue();
        assertThat(count(fixture.request())).isZero();
        blocker.rollback();
        var oneResult = one.get(30, TimeUnit.SECONDS);
        var twoResult = two.get(30, TimeUnit.SECONDS);
        assertThat(oneResult.captureId()).isEqualTo(twoResult.captureId());
        assertThat(oneResult.resultBytes()).containsExactly(twoResult.resultBytes());
        assertThat(count(fixture.request())).isEqualTo(1);
      } finally {
        blocker.rollback();
      }
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
    var after = snapshotRows();
    var priorUnchanged = new LinkedHashMap<>(before);
    priorUnchanged.remove("world_canonical_frozen_topology");
    var actualUnchanged = new LinkedHashMap<>(after);
    actualUnchanged.remove("world_canonical_frozen_topology");
    assertThat(actualUnchanged).isEqualTo(priorUnchanged);
    var captured = service(dsl).capture(fixture.request());
    assertThat(service(dsl).readCommitted(fixture.request()).orElseThrow().captureId())
        .isEqualTo(captured.captureId());
    assertThat(snapshotRows()).isEqualTo(after);
    assertOrigin();
  }

  private static DSLContext observeOwnerLock(AtomicInteger pid, CountDownLatch entered) {
    var listener =
        ExecuteListener.onExecuteStart(
            context -> {
              String sql = Objects.requireNonNull(context.sql()).toLowerCase(Locale.ROOT);
              if (sql.contains("world_design_publication_fence_owner")
                  && sql.contains("for update")) {
                try {
                  pid.set(
                      Objects.requireNonNull(context.connection())
                          .unwrap(PGConnection.class)
                          .getBackendPID());
                  entered.countDown();
                } catch (SQLException failure) {
                  throw new IllegalStateException(
                      "Cannot identify physical capture owner connection", failure);
                }
              }
            });
    return DSL.using(dsl.configuration().derive(new DefaultExecuteListenerProvider(listener)));
  }

  private static WorldDesignPublicationFenceRepository fence(DSLContext context) {
    return new WorldDesignPublicationFenceRepository(
        context, new WorldAuthoredSourceIntakeRepository(context));
  }

  private static WorldDraftTopologyCommitRepository topology(DSLContext context) {
    return new WorldDraftTopologyCommitRepository(context, fence(context), JSON);
  }

  private static WorldDraftDesignDigestService digest(DSLContext context) {
    return new WorldDraftDesignDigestServiceImpl(
        new RegionRepository(context),
        new ZoneRepository(context),
        new RoomRepository(context),
        new RoomExitRepository(context),
        new GenerationRuleRepository(context),
        new WorldEntitySpawnBindingRepository(context));
  }

  private static WorldCanonicalFrozenTopologyService service(DSLContext context) {
    return new WorldCanonicalFrozenTopologyService(
        new WorldCanonicalFrozenTopologyRepository(
            context,
            new WorldAuthoredGraphSnapshotRepository(context),
            topology(context),
            digest(context)),
        manager);
  }

  private static Fixture fixture() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    long sourceRow = NEXT_SOURCE.incrementAndGet();
    String slug = "journal-" + tenant.toString().substring(0, 8);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, slug, "world", "Controlled journal");
    var authoredSource =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            slug,
            "world",
            "Controlled journal",
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
                "Controlled journal",
                sourceRow,
                "source",
                "NEW_GAME_ROW"));
    UUID region = UUID.randomUUID();
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
    for (String family :
        List.of(
            "REGION",
            "ZONE",
            "ROOM",
            "ROOM_EXIT",
            "GENERATION_RULE",
            "WORLD_ENTITY_SPAWN_BINDING")) {
      declaration.addFamilyCounts(
          WorldFreshGraphFamilyCount.newBuilder()
              .setFamily(WorldDesignAggregateType.valueOf("WORLD_DESIGN_AGGREGATE_TYPE_" + family))
              .setCount(
                  family.equals("REGION") || family.equals("ZONE") || family.equals("ROOM")
                      ? 1
                      : 0));
    }
    var closure = WorldInboundSourceClosureDeclaration.newBuilder().setSchemaVersion(1);
    for (var family : WorldDraftTopologyInputGraph.INBOUND_SOURCE_FAMILY_ORDER) {
      closure.addFamilyCounts(
          WorldInboundSourceFamilyCount.newBuilder().setFamily(family).setCount(0));
    }
    declaration.setInboundSourceClosure(closure);
    var mutations =
        List.of(
            mutation(commit, region, region, "REGION")
                .setRegion(RegionDesignMutation.newBuilder().setName("Region"))
                .setFreshGraphDeclaration(declaration)
                .build(),
            mutation(commit, zone, region, "ZONE")
                .setZone(
                    ZoneDesignMutation.newBuilder().setName("Zone").setRegionId(region.toString()))
                .build(),
            mutation(commit, room, region, "ROOM")
                .setRoom(RoomDesignMutation.newBuilder().setName("Room").setZoneId(zone.toString()))
                .build());
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
              "0"));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              mutation.getAggregateId(),
              "REGION_SUBTREE",
              mutation.getScopeId(),
              "0"));
    }
    revisions.add(
        new RevisionPayload(
            "3",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "Controlled Game Design source selection"));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE, "SOURCE", "source", "AGGREGATE", "source", "0"));
    var binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                tenant, version, 901, "source", sourceRow, "source", "NEW_GAME_ROW"),
            UUID.randomUUID(),
            commit,
            "genesis:" + UUID.randomUUID(),
            revisions,
            units);
    var applied =
        WorldSelectedFullPublicationEvidenceFixture.applyGraph(
            dsl, manager, NAMESPACE, binding, authoredSource);
    var owner = applied.plan().ownerBinding();
    String publication = UUID.randomUUID().toString();
    var freeze =
        new WorldDesignPublicationFenceEvidence(
            NAMESPACE,
            tenant,
            version,
            owner.versionIdentityOperationId(),
            owner.gameDesignVersionId(),
            owner.intakeRequestId(),
            owner.intakeOperationId(),
            owner.intakeRequestDigest(),
            owner.sourceOperationId(),
            owner.sourceEvidenceDigest(),
            owner.intakeReceiptDigest(),
            publication,
            "a".repeat(64),
            1,
            PublicationDigestRequestBinding.full(tenant.toString(), "901", publication)
                .derivedWorkflowIdentity());
    var graph =
        new WorldCanonicalAuthoredGraphReader()
            .read(applied.plan(), applied.applied().graphBytes());
    // This controlled exact commit choice is not a Game Design checkpoint producer.
    var attempt =
        Objects.requireNonNull(
            write.execute(
                status ->
                    fence(dsl)
                        .claimFreeze(
                            freeze,
                            () -> {
                              var checkpoint =
                                  digest(dsl)
                                      .getSelectedDraftDesignDigest(
                                          Long.toString(applied.sourceIntake().localTenantKey()),
                                          Long.toString(
                                              applied.versionIdentity().localVersionKey()),
                                          graph.inboundSourceClosure().orElseThrow());
                              assertThat(checkpoint.digestSchemaVersion()).isEqualTo(4);
                              return new WorldDesignPublicationFenceEvidence.Checkpoint(
                                  commit.toString(),
                                  checkpoint.contentDigest(),
                                  checkpoint.digestSchemaVersion());
                            })));
    var tuples =
        binding.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var capture =
        new CaptureRequest(
            NAMESPACE,
            tenant,
            version,
            owner.intakeRequestId(),
            attempt.publicationFence(),
            publication,
            freeze.requestDigest(),
            1,
            freeze.publishWorkflowId(),
            commit.toString(),
            attempt.checkpoint().contentDigest(),
            4,
            tuples);
    return new Fixture(applied, new Request(applied.plan(), capture));
  }

  private static WorldDesignMutationRevision.Builder mutation(
      UUID commit, UUID id, UUID scope, String family) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateType(WorldDesignAggregateType.valueOf("WORLD_DESIGN_AGGREGATE_TYPE_" + family))
        .setAggregateId(id.toString())
        .setScopeId(scope.toString())
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT);
  }

  private static CaptureRequest copy(
      CaptureRequest r,
      String requestDigest,
      String contentDigest,
      long epoch,
      String publication) {
    return new CaptureRequest(
        r.targetNamespace(),
        r.canonicalTenantId(),
        r.canonicalVersionId(),
        r.intakeRequestId(),
        r.publicationFence(),
        publication,
        requestDigest,
        epoch,
        PublicationDigestRequestBinding.full(r.canonicalTenantId().toString(), "901", publication)
            .derivedWorkflowIdentity(),
        r.appliedCommitId(),
        contentDigest,
        r.digestSchemaVersion(),
        r.suppliedOwnedAffectedTuples());
  }

  private static String flipped(String digest) {
    return (digest.charAt(0) == 'a' ? "b" : "a") + digest.substring(1);
  }

  private static long count(Request request) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM world_canonical_frozen_topology WHERE publication_fence=?",
            request.freeze().publicationFence())
        .get(0, Long.class);
  }

  private static Map<String, List<String>> snapshotRows() {
    var rows = new LinkedHashMap<String, List<String>>();
    for (String table : OWNER_TABLES) {
      rows.put(
          table,
          dsl.fetch(
                  "SELECT concat(to_jsonb(t)::text,'|xmin=',xmin::text) FROM "
                      + table
                      + " t ORDER BY to_jsonb(t)::text,xmin::text")
              .getValues(0, String.class));
    }
    return Map.copyOf(rows);
  }

  private static void assertOrigin() {
    assertThat(dsl.fetchSingle("SHOW session_replication_role").get(0, String.class))
        .isEqualTo("origin");
  }

  private record Fixture(AppliedGraph applied, Request request) {}
}
