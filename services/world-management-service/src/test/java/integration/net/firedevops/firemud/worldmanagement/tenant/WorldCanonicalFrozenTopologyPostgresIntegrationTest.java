package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalFrozenTopology.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.GenerationRuleDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldEntitySpawnBindingDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Actual V30 rows and V31/V32 capture, with synthetic source/permission and computed schema-3 V25
 * content checkpoints. Selected commit identities remain synthetic complete-application evidence.
 * Fixture-only trigger bypasses model terminal/corrupt storage unavailable through the current
 * owner API; origin triggers are restored before every tested capture/read boundary.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldCanonicalFrozenTopologyPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final long GAME_DESIGN_VERSION = 9_000_000_000_000_001L;

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager manager;
  @Autowired private WorldAuthoredSourceIntakeRepository intakeRepository;
  @Autowired private WorldDesignPublicationFenceRepository fence;
  @Autowired private WorldAuthoredGraphSnapshotRepository snapshots;
  @Autowired private ObjectMapper mapper;

  @Autowired
  private net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository
      spawnRepository;

  @Autowired
  private net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService
      digestService;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void capturesActualSixFamilyGraphAndOriginalFullInputWithExactRetry() {
    Fixture f = fixture();
    var p = plan(f);
    var stored = component().store(p);
    Request request = freeze(p);
    var capture = frozen().capture(request);
    assertThat(capture.status()).isEqualTo("CAPTURED_UNVERIFIED");
    assertThat(capture.request().freeze().digestSchemaVersion()).isEqualTo(3);
    assertThat(capture.graphBytes()).containsExactly(stored.graphBytes());
    assertThat(capture.storageResultBytes()).containsExactly(stored.resultBytes());
    assertThat(capture.request().plan().binding().canonicalBytes())
        .containsExactly(p.binding().canonicalBytes());
    assertThat(capture.sourceIdentity()).isEqualTo(f.version());
    assertThat(capture.graph().rows()).hasSize(7);
    assertThat(capture.graph().family(Family.ROOM)).hasSize(2);
    for (Family family : Family.values()) assertThat(capture.graph().family(family)).isNotEmpty();
    var region = capture.graph().family(Family.REGION).getFirst();
    var spawn = capture.graph().family(Family.WORLD_ENTITY_SPAWN_BINDING).getFirst();
    assertThat(region.content().getRegion().getSpacingMultiplier()).isEqualTo(1.0);
    assertThat(spawn.content().getWorldEntitySpawnBinding().getSpawnCount()).isEqualTo(1);
    assertThat(spawn.entityReference()).isEqualTo(p.graph().nodes().getFirst().entityReference());
    assertThat(
            capture.graph().family(Family.ROOM_EXIT).getFirst().content().getRoomExit().getCost())
        .isEqualTo(1);
    assertThat(new String(capture.resultBytes(), StandardCharsets.UTF_8))
        .contains(
            "ACCOUNT_AUTHORIZATION", "COMPLETE_PARTICIPANT_COMMIT", "PUBLICATION_CHECKPOINT_DIGEST")
        .doesNotContain("\"APPLIED\"", "permissionFenceReleased", "\"PREPARING\"");
    byte[] exposed = capture.graphBytes();
    exposed[0] = 0;
    assertThat(capture.graphBytes()).containsExactly(stored.graphBytes());
    assertThat(frozen().capture(request).resultBytes()).containsExactly(capture.resultBytes());
    assertThat(countCaptures(request)).isEqualTo(1);
    assertOrigin();
  }

  @Test
  void canonicalSpawnSqlMappingIsLosslessAndProducesSchema3DigestWithoutWriteBypass() {
    Fixture f = fixture();
    var p = plan(f);
    component().store(p);
    var spawns =
        spawnRepository.findByTenantIdAndVersionIdOrderByIdAsc(
            f.intake().localTenantKey(), f.version().localVersionKey());
    assertThat(spawns).hasSize(1);
    var spawn = spawns.getFirst();
    var row = dsl.fetchOne("SELECT * FROM world_entity_spawn_binding WHERE id=?", spawn.getId());
    assertThat(spawn.getEntityTemplateId()).isNull();
    assertThat(spawn.getEntityCanonicalTenantId())
        .isEqualTo(row.get("entity_canonical_tenant_id", UUID.class));
    assertThat(spawn.getEntityCanonicalVersionId())
        .isEqualTo(row.get("entity_canonical_version_id", UUID.class));
    assertThat(spawn.getEntityCanonicalTemplateId())
        .isEqualTo(row.get("entity_canonical_template_id", UUID.class));
    var digest =
        digestService.getDraftDesignDigest(
            Long.toString(spawn.getTenantId()), Long.toString(spawn.getVersionId()));
    assertThat(digest.digestSchemaVersion()).isEqualTo(3);
    assertThat(digest.contentDigest()).matches("[0-9a-f]{64}");
    assertThat(spawnRepository.findById(spawn.getId()).orElseThrow()).isEqualTo(spawn);
    assertThatThrownBy(() -> spawnRepository.save(spawn)).isInstanceOf(RuntimeException.class);
    assertThat(spawnRepository.findById(spawn.getId()).orElseThrow()).isEqualTo(spawn);
    assertOrigin();
  }

  @Test
  void freshSchema2CaptureIsDeniedByApplicationAndDatabase() {
    Fixture f = fixture();
    var p = plan(f);
    component().store(p);
    Request request = freeze(p, 2);
    assertThat(frozen().readCommitted(request)).isEmpty();
    assertThatThrownBy(() -> frozen().capture(request))
        .hasMessageContaining("requires digest schema 3");
    assertThat(countCaptures(request)).isZero();
    var templatePlan = plan(fixture());
    component().store(templatePlan);
    Request template = freeze(templatePlan);
    // Supply a complete otherwise-valid insertion from an existing schema-3 journal.
    frozen().capture(template);
    for (int denied : new int[] {2, 4}) {
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO world_canonical_frozen_topology SELECT (jsonb_populate_record(NULL::world_canonical_frozen_topology, "
                              + "to_jsonb(t) || jsonb_build_object('capture_id', ?, 'freeze_request_json', "
                              + "replace(freeze_request_json, '\"digestSchemaVersion\":3', ?)))).* "
                              + "FROM world_canonical_frozen_topology t WHERE publication_fence=?",
                          UUID.randomUUID(),
                      "\"digestSchemaVersion\":" + denied, template.freeze().publicationFence()))
          .hasStackTraceContaining("requires digest schema 3");
    }
    assertOrigin();
  }

  @Test
  void newSchema3CaptureRejectsChangedContentDigestAndEveryReturnedScopeField() {
    Fixture f = fixture();
    var p = plan(f);
    component().store(p);
    Request request = freeze(p);
    for (int changed = 0; changed < 4; changed++) {
      int field = changed;
      var bad =
          frozen(
              (tenant, version) ->
                  new net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService
                      .WorldDraftDesignDigest(
                      field == 0 ? "999999999" : tenant,
                      field == 1 ? "999999999" : version,
                      "version:" + version,
                      field == 2 ? "c".repeat(64) : request.freeze().contentDigest(),
                      field == 3 ? 2 : 3));
      assertThatThrownBy(() -> bad.capture(request))
          .hasMessageContaining("differs from exact schema-3 frozen content checkpoint");
      assertThat(countCaptures(request)).isZero();
    }
    var captured = frozen().capture(request);
    assertThat(captured.request().freeze().digestSchemaVersion()).isEqualTo(3);
    assertThat(
            frozen(
                    (tenant, version) -> {
                      throw new AssertionError("Exact retry must use retained journal");
                    })
                .capture(request)
                .resultBytes())
        .containsExactly(captured.resultBytes());
  }

  @Test
  void changedCompleteBindingOrAnyFreezeCheckpointFieldCannotReplayCapture() {
    Fixture f = fixture();
    var p = plan(f);
    component().store(p);
    Request original = freeze(p);
    frozen().capture(original);
    var b = p.binding();
    var changed =
        WorldDraftTopologyCommitPlan.create(
            DraftCommitBinding.create(
                b.target(),
                b.requestId(),
                b.commitId(),
                b.baseCommitId() + "-changed",
                b.revisions(),
                b.affectedUnits()),
            p.ownerBinding());
    assertThatThrownBy(() -> frozen().readCommitted(new Request(changed, original.freeze())))
        .hasMessageContaining("changed complete input");
    CaptureRequest r = original.freeze();
    for (CaptureRequest bad :
        List.of(
            copy(
                r,
                r.requestDigest(),
                r.contentDigest(),
                r.versionStateEpoch() + 1,
                r.publicationRequestId(),
                r.publishWorkflowId()),
            copy(
                r,
                "c".repeat(64),
                r.contentDigest(),
                r.versionStateEpoch(),
                r.publicationRequestId(),
                r.publishWorkflowId()),
            copy(
                r,
                r.requestDigest(),
                "c".repeat(64),
                r.versionStateEpoch(),
                r.publicationRequestId(),
                r.publishWorkflowId()),
            copy(
                r,
                r.requestDigest(),
                r.contentDigest(),
                r.versionStateEpoch(),
                r.publicationRequestId() + "-changed",
                r.publishWorkflowId() + "-changed"))) {
      assertThatThrownBy(() -> frozen().capture(new Request(p, bad)))
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(countCaptures(original)).isEqualTo(1);
  }

  @Test
  void historicalReadAndExactRetryAfterEveryTerminalPhaseUseOnlyOriginalJournal() {
    for (String phase : List.of("PUBLISHED", "ABORTED", "RECONCILIATION_REQUIRED")) {
      Fixture f = fixture();
      var p = plan(f);
      component().store(p);
      Request request = freeze(p);
      byte[] original = frozen().capture(request).resultBytes();
      bypass(
          "UPDATE world_design_publication_fence_owner SET owner_freeze_phase=? WHERE local_tenant_key=?",
          phase,
          f.intake().localTenantKey());
      bypass(
          "UPDATE region SET weather='corrupt-current-row' WHERE tenant_id=?",
          f.intake().localTenantKey());
      bypass(
          "UPDATE world_topology_draft_commit SET result_bytes='corrupt-original-history'::bytea WHERE request_id=?",
          p.binding().requestId());
      assertThat(frozen().readCommitted(request).orElseThrow().resultBytes())
          .containsExactly(original);
      assertThat(frozen().capture(request).resultBytes()).containsExactly(original);
      assertThat(countCaptures(request)).isEqualTo(1);
      assertOrigin();
    }
  }

  @Test
  void noNewCaptureAfterTerminalAndAbsentJournalRemainsUnknown() {
    for (String phase : List.of("PUBLISHED", "ABORTED", "RECONCILIATION_REQUIRED")) {
      Fixture f = fixture();
      var p = plan(f);
      component().store(p);
      Request request = freeze(p);
      bypass(
          "UPDATE world_design_publication_fence_owner SET owner_freeze_phase=? WHERE local_tenant_key=?",
          phase,
          f.intake().localTenantKey());
      assertThat(frozen().readCommitted(request)).isEmpty();
      assertThatThrownBy(() -> frozen().capture(request)).hasMessageContaining("current FROZEN");
      assertThat(countCaptures(request)).isZero();
    }
  }

  @Test
  void captureRejectsActualRowTamperingAndMismatchedCurrentFenceBeforeJournalInsert() {
    Fixture f = fixture();
    var p = plan(f);
    component().store(p);
    Request request = freeze(p);
    bypass("UPDATE room_exit SET cost=99 WHERE tenant_id=?", f.intake().localTenantKey());
    assertThatThrownBy(() -> frozen().capture(request)).hasMessageContaining("payload field");
    assertThat(countCaptures(request)).isZero();
    Fixture second = fixture();
    var secondPlan = plan(second);
    component().store(secondPlan);
    Request exact = freeze(secondPlan);
    bypass(
        "UPDATE world_design_publication_fence_owner SET current_publication_fence=? WHERE local_tenant_key=?",
        UUID.randomUUID(),
        second.intake().localTenantKey());
    assertThatThrownBy(() -> frozen().capture(exact))
        .hasMessageContaining("retained V25 owner binding");
    assertThat(countCaptures(exact)).isZero();
  }

  @Test
  void frozenCheckpointWithoutCommittedCompleteStorageCannotCreateCapture() {
    Fixture f = fixture();
    var p = plan(f);
    Request request = freeze(p);
    assertThat(frozen().readCommitted(request)).isEmpty();
    assertThatThrownBy(() -> frozen().capture(request))
        .hasMessageContaining("no committed complete topology result");
    assertThat(countCaptures(request)).isZero();
  }

  @Test
  void v31UpgradePreservesExistingV30SourceRowsMappingsResultsAndFrozenCheckpoint()
      throws Exception {
    Fixture f = fixture();
    var p = plan(f);
    var stored = component().store(p);
    Request request = freeze(p);
    String schema = "retained_capture_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("30"))
        .load()
        .migrate();
    Map<String, String> tables = new LinkedHashMap<>();
    for (String table :
        List.of(
            "world_authored_source_tenant_association",
            "world_authored_source_intake",
            "world_authored_version_identity",
            "world_design_publication_fence_attempt",
            "world_design_publication_fence_owner",
            "world_topology_draft_commit")) tables.put(table, "local_tenant_key");
    tables.put("world_authored_source_tenant_key_reservation", "tenant_key");
    for (String table :
        List.of(
            "world_authored_topology_identity",
            "region",
            "zone",
            "room",
            "room_exit",
            "generation_rule",
            "world_entity_spawn_binding",
            "world_design_aggregate_epoch",
            "world_design_scope_epoch")) {
      tables.put(table, "tenant_id");
    }
    try (var connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      DSLContext retained = DSL.using(connection, SQLDialect.POSTGRES);
      retained.execute("SET search_path TO " + schema + ", public");
      connection.setAutoCommit(false);
      retained.execute("SET LOCAL session_replication_role='replica'");
      for (var table : tables.entrySet()) {
        String override =
            table.getKey().equals("world_authored_version_identity")
                ? " OVERRIDING SYSTEM VALUE"
                : "";
        retained.execute(
            "INSERT INTO "
                + table.getKey()
                + override
                + " SELECT * FROM world_management_service."
                + table.getKey()
                + " WHERE "
                + table.getValue()
                + "=?",
            f.intake().localTenantKey());
      }
      retained.execute("SET LOCAL session_replication_role='origin'");
      connection.commit();
      connection.setAutoCommit(true);
      Map<String, List<String>> before = retainedRows(retained, tables);
      Flyway.configure()
          .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      assertThat(retainedRows(retained, tables)).isEqualTo(before);
      assertThat(
              retained
                  .resultQuery("SELECT count(*) FROM world_canonical_frozen_topology")
                  .fetchOne(0, Long.class))
          .isZero();
      assertThat(
              retained
                  .resultQuery("SELECT graph_bytes FROM world_topology_draft_commit")
                  .fetchOne(0, byte[].class))
          .containsExactly(stored.graphBytes());
      assertThat(
              retained
                  .resultQuery("SELECT result_bytes FROM world_topology_draft_commit")
                  .fetchOne(0, byte[].class))
          .containsExactly(stored.resultBytes());
      assertThat(
              retained
                  .resultQuery(
                      "SELECT publication_fence FROM world_design_publication_fence_attempt")
                  .fetchOne(0, UUID.class))
          .isEqualTo(request.freeze().publicationFence());
    } finally {
      dsl.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private Map<String, List<String>> retainedRows(DSLContext database, Map<String, String> tables) {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table : tables.keySet()) {
      rows.put(
          table,
          database
              .resultQuery(
                  "SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY to_jsonb(t)::text")
              .fetch(0, String.class));
    }
    return rows;
  }

  @Test
  void immutableJournalDeniesWritesAndHistoricalReadRejectsEveryTamperedField() {
    Map<String, String> mutations = new LinkedHashMap<>();
    mutations.put("result_bytes", "result_bytes='corrupt'::bytea");
    mutations.put("storage_result_bytes", "storage_result_bytes='corrupt'::bytea");
    mutations.put("binding_digest", "binding_digest='sha256:" + "f".repeat(64) + "'");
    mutations.put(
        "binding_json", "binding_json=replace(binding_json,'retained-base','tampered-base')");
    mutations.put(
        "identity_json",
        "identity_json=replace(identity_json,'local_version_key','unsupported_key')");
    mutations.put(
        "intake_json",
        "intake_json=replace(intake_json,'Synthetic component world','changed source')");
    mutations.put(
        "owner_binding_json", "owner_binding_json=replace(owner_binding_json,'firemud','other')");
    mutations.put("freeze_request_json contentDigest", null);
    mutations.put("graph_sha256", "graph_sha256='" + "d".repeat(64) + "'");

    for (var mutation : mutations.entrySet()) {
      Fixture f = fixture();
      var p = plan(f);
      component().store(p);
      Request request = freeze(p);
      frozen().capture(request);
      String set = mutation.getValue();
      if (set == null) {
        String originalDigest = request.freeze().contentDigest();
        String changedDigest =
            (originalDigest.charAt(0) == '0' ? "1" : "0") + originalDigest.substring(1);
        assertThat(changedDigest)
            .as("replacement for %s differs from the actual schema-3 digest", mutation.getKey())
            .isNotEqualTo(originalDigest);
        set =
            "freeze_request_json=replace(freeze_request_json,'"
                + originalDigest
                + "','"
                + changedDigest
                + "')";
      }
      String before =
          dsl.resultQuery(
                  "SELECT to_jsonb(t)::text FROM world_canonical_frozen_topology t "
                      + "WHERE publication_fence=?",
                  request.freeze().publicationFence())
              .fetchOne(0, String.class);
      assertThat(before).as("original journal row exists for %s", mutation.getKey()).isNotNull();
      String update = "UPDATE world_canonical_frozen_topology SET " + set;
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      update + " WHERE publication_fence=?", request.freeze().publicationFence()))
          .as("origin trigger rejects direct update of %s", mutation.getKey())
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "DELETE FROM world_canonical_frozen_topology WHERE publication_fence=?",
                      request.freeze().publicationFence()))
          .as("origin trigger rejects direct delete while testing %s", mutation.getKey())
          .isInstanceOf(RuntimeException.class);
      bypass(update + " WHERE publication_fence=?", request.freeze().publicationFence());
      String after =
          dsl.resultQuery(
                  "SELECT to_jsonb(t)::text FROM world_canonical_frozen_topology t WHERE publication_fence=?",
                  request.freeze().publicationFence())
              .fetchOne(0, String.class);
      assertThat(after)
          .as("bypass mutation changes the complete original journal row for %s", mutation.getKey())
          .isNotNull()
          .isNotEqualTo(before);
      assertThatThrownBy(() -> frozen().readCommitted(request))
          .as("historical read rejects tampered %s", mutation.getKey())
          .isInstanceOf(RuntimeException.class);
      assertOrigin();
    }
    assertThatThrownBy(() -> dsl.execute("TRUNCATE world_canonical_frozen_topology"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void recomputedGraphChecksumCannotHideTypedEndpointOrCanonicalEntityTampering() {
    for (String replacement : List.of("\"to_room_id\": 0", "\"entity_template_id\": 123")) {
      // The replacement string is selected from test constants, never a caller-supplied selector.
      Fixture f = fixture();
      var p = plan(f);
      component().store(p);
      Request request = freeze(p);
      var captured = frozen().capture(request);
      String graph = new String(captured.graphBytes(), StandardCharsets.UTF_8);
      String bad =
          replacement.startsWith("\"to_room_id\"")
              ? graph.replaceAll("\"to_room_id\"\\s*:\\s*[0-9]+", "\"to_room_id\":0")
              : graph.replace("\"entity_template_id\":null", "\"entity_template_id\":123");
      assertThat(bad).isNotEqualTo(graph);
      byte[] badBytes = bad.getBytes(StandardCharsets.UTF_8);
      bypass(
          "UPDATE world_canonical_frozen_topology SET graph_bytes=?,graph_sha256=? WHERE publication_fence=?",
          badBytes,
          WorldAuthoredGraphSnapshotCapture.sha256(badBytes),
          request.freeze().publicationFence());
      assertThatThrownBy(() -> frozen().readCommitted(request))
          .hasMessageContaining("exact typed original payload");
    }
  }

  @Test
  void twoConcurrentCapturesReturnOneOriginalImmutableResult() throws Exception {
    Fixture f = fixture();
    var p = plan(f);
    component().store(p);
    Request request = freeze(p);
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> {
                assertThat(start.await(15, TimeUnit.SECONDS)).isTrue();
                return frozen().capture(request);
              });
      var second =
          pool.submit(
              () -> {
                assertThat(start.await(15, TimeUnit.SECONDS)).isTrue();
                return frozen().capture(request);
              });
      start.countDown();
      var one = first.get(20, TimeUnit.SECONDS);
      var two = second.get(20, TimeUnit.SECONDS);
      assertThat(one.captureId()).isEqualTo(two.captureId());
      assertThat(one.resultBytes()).containsExactly(two.resultBytes());
    }
    assertThat(countCaptures(request)).isEqualTo(1);
  }

  private WorldCanonicalFrozenTopologyService frozen() {
    return frozen(digestService);
  }

  private WorldCanonicalFrozenTopologyService frozen(
      net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService producer) {
    return new WorldCanonicalFrozenTopologyService(
        new WorldCanonicalFrozenTopologyRepository(dsl, snapshots, repository(), producer),
        manager);
  }

  @Test
  void v32PreservesOriginalSchema2FrozenJournalAndExactHistoricalRetries() throws Exception {
    Fixture f = fixture();
    var p = plan(f);
    component().store(p);
    Request current = freeze(p);
    var captured = frozen().capture(current);
    CaptureRequest r = current.freeze();
    Request historical =
        new Request(
            p,
            new CaptureRequest(
                r.targetNamespace(),
                r.canonicalTenantId(),
                r.canonicalVersionId(),
                r.intakeRequestId(),
                r.publicationFence(),
                r.publicationRequestId(),
                r.requestDigest(),
                r.versionStateEpoch(),
                r.publishWorkflowId(),
                r.appliedCommitId(),
                r.contentDigest(),
                2,
                r.suppliedOwnedAffectedTuples()));
    String schema = "retained_schema2_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("31"))
        .load()
        .migrate();
    Map<String, String> tables = new LinkedHashMap<>();
    for (String table :
        List.of(
            "world_authored_source_tenant_association",
            "world_authored_source_intake",
            "world_authored_version_identity",
            "world_design_publication_fence_attempt",
            "world_design_publication_fence_owner",
            "world_topology_draft_commit")) tables.put(table, "local_tenant_key");
    tables.put("world_authored_source_tenant_key_reservation", "tenant_key");
    try (var connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      DSLContext retained = DSL.using(connection, SQLDialect.POSTGRES);
      retained.execute("SET search_path TO " + schema + ", public");
      connection.setAutoCommit(false);
      retained.execute("SET LOCAL session_replication_role='replica'");
      for (var table : tables.entrySet()) {
        String override =
            table.getKey().equals("world_authored_version_identity")
                ? " OVERRIDING SYSTEM VALUE"
                : "";
        retained.execute(
            "INSERT INTO "
                + table.getKey()
                + override
                + " SELECT * FROM world_management_service."
                + table.getKey()
                + " WHERE "
                + table.getValue()
                + "=?",
            f.intake().localTenantKey());
      }
      // Synthetic original schema-2 evidence is installed before V32, never upgraded or rehashed by
      // it.
      retained.execute(
          "UPDATE world_design_publication_fence_attempt SET digest_schema_version=2 WHERE publication_fence=?",
          r.publicationFence());
      byte[] originalResult =
          new String(captured.resultBytes(), StandardCharsets.UTF_8)
              .replace("\"digestSchemaVersion\":3", "\"digestSchemaVersion\":2")
              .getBytes(StandardCharsets.UTF_8);
      retained.execute(
          "INSERT INTO world_canonical_frozen_topology SELECT (jsonb_populate_record(NULL::world_canonical_frozen_topology, "
              + "to_jsonb(t) || jsonb_build_object('freeze_request_json', replace(freeze_request_json, '\"digestSchemaVersion\":3', '\"digestSchemaVersion\":2'), "
              + "'result_bytes', ?::bytea))).* FROM world_management_service.world_canonical_frozen_topology t WHERE publication_fence=?",
          originalResult,
          r.publicationFence());
      retained.execute("SET LOCAL session_replication_role='origin'");
      connection.commit();
      connection.setAutoCommit(true);
      tables.put("world_canonical_frozen_topology", "publication_fence");
      var before = retainedRows(retained, tables);
      Flyway.configure()
          .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      assertThat(retainedRows(retained, tables)).isEqualTo(before);
      var repo =
          new WorldCanonicalFrozenTopologyRepository(
              retained,
              new WorldAuthoredGraphSnapshotRepository(retained),
              new WorldDraftTopologyCommitRepository(
                  retained,
                  new WorldDesignPublicationFenceRepository(
                      retained, new WorldAuthoredSourceIntakeRepository(retained)),
                  mapper),
              (tenant, version) -> {
                throw new AssertionError("Historical retry must never recompute content");
              });
      var service = new WorldCanonicalFrozenTopologyService(repo, manager);
      assertThat(service.readCommitted(historical).orElseThrow().resultBytes())
          .containsExactly(originalResult);
      assertThat(service.capture(historical).resultBytes()).containsExactly(originalResult);
      assertThat(service.capture(historical).request().freeze().digestSchemaVersion()).isEqualTo(2);
      assertThatThrownBy(() -> service.readCommitted(current))
          .hasMessageContaining("changed complete input");
      assertThat(retainedRows(retained, tables)).isEqualTo(before);
    } finally {
      dsl.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private Request freeze(WorldDraftTopologyCommitPlan p) {
    return freeze(p, 3);
  }

  private Request freeze(WorldDraftTopologyCommitPlan p, int schema) {
    var o = p.ownerBinding();
    String publicationRequest = "capture-" + UUID.randomUUID();
    var evidence =
        new WorldDesignPublicationFenceEvidence(
            o.targetNamespace(),
            o.canonicalTenantId(),
            o.canonicalVersionId(),
            o.versionIdentityOperationId(),
            o.gameDesignVersionId(),
            o.intakeRequestId(),
            o.intakeOperationId(),
            o.intakeRequestDigest(),
            o.sourceOperationId(),
            o.sourceEvidenceDigest(),
            o.intakeReceiptDigest(),
            publicationRequest,
            "a".repeat(64),
            1,
            "publish:" + o.canonicalTenantId() + ":publish-request:" + publicationRequest);
    // Compute content under the exact owner lock; selected commit is still not complete APPLIED.
    var attempt =
        Objects.requireNonNull(
            ownerTransaction()
                .execute(
                    status ->
                        fence.claimFreeze(
                            evidence,
                            () -> {
                              var keys =
                                  Objects.requireNonNull(
                                      dsl.fetchOne(
                                          "SELECT local_tenant_key, local_version_key FROM world_authored_version_identity WHERE operation_id=?",
                                          o.versionIdentityOperationId()),
                                      "expected owner version-identity row");
                              var digest =
                                  digestService.getDraftDesignDigest(
                                      Long.toString(keys.get("local_tenant_key", Long.class)),
                                      Long.toString(keys.get("local_version_key", Long.class)));
                              return new WorldDesignPublicationFenceEvidence.Checkpoint(
                                  p.binding().commitId().toString(),
                                  digest.contentDigest(),
                                  schema);
                            })));
    var tuples =
        p.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                u ->
                    new OwnedAffectedTuple(
                        u.owner().name(),
                        u.aggregateType(),
                        u.aggregateId(),
                        u.scopeType(),
                        u.scopeId(),
                        u.expectedEpoch()))
            .toList();
    return new Request(
        p,
        new CaptureRequest(
            o.targetNamespace(),
            o.canonicalTenantId(),
            o.canonicalVersionId(),
            o.intakeRequestId(),
            attempt.publicationFence(),
            publicationRequest,
            evidence.requestDigest(),
            evidence.versionStateEpoch(),
            evidence.publishWorkflowId(),
            attempt.checkpoint().appliedCommitId(),
            attempt.checkpoint().contentDigest(),
            attempt.checkpoint().digestSchemaVersion(),
            tuples));
  }

  private CaptureRequest copy(
      CaptureRequest r,
      String digest,
      String content,
      long epoch,
      String publication,
      String workflow) {
    return new CaptureRequest(
        r.targetNamespace(),
        r.canonicalTenantId(),
        r.canonicalVersionId(),
        r.intakeRequestId(),
        r.publicationFence(),
        publication,
        digest,
        epoch,
        workflow,
        r.appliedCommitId(),
        content,
        r.digestSchemaVersion(),
        r.suppliedOwnedAffectedTuples());
  }

  private void bypass(String sql, Object... values) {
    ownerTransaction()
        .execute(
            status -> {
              dsl.execute("SET LOCAL session_replication_role='replica'");
              try {
                dsl.execute(sql, values);
              } finally {
                dsl.execute("SET LOCAL session_replication_role='origin'");
              }
              return null;
            });
    assertOrigin();
  }

  private long countCaptures(Request request) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT count(*) FROM world_canonical_frozen_topology WHERE publication_fence=?",
                request.freeze().publicationFence())
            .fetchOne(0, Long.class));
  }

  private Fixture fixture() {
    UUID tenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
    String worldSlug = "world-" + UUID.randomUUID().toString().replace("-", "");
    long sourceRow = Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
    String sourceTenant = "gd-row-" + sourceRow;
    String digest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, tenantSlug, worldSlug, "Synthetic component world");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            sourceOperation,
            digest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic component world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            digest,
            tenant,
            tenantSlug,
            worldSlug,
            "Synthetic component world",
            sourceRow,
            sourceTenant,
            "NEW_GAME_ROW",
            evidenceDigest);
    UUID intakeRequest = UUID.randomUUID();
    ownerTransaction()
        .execute(status -> intakeRepository.acceptFresh(NAMESPACE, intakeRequest, source));
    WorldAuthoredSourceIntakeReceipt intake =
        intakeRepository.read(NAMESPACE, intakeRequest).orElseThrow();
    return Objects.requireNonNull(
        ownerTransaction()
            .execute(
                status -> {
                  UUID canonicalVersion = UUID.randomUUID();
                  AuthoredWorldVersionStateEvidence.Request stateRequest =
                      new AuthoredWorldVersionStateEvidence.Request(
                          1,
                          NAMESPACE,
                          UUID.randomUUID(),
                          tenant,
                          worldSlug,
                          sourceOperation,
                          evidenceDigest,
                          GAME_DESIGN_VERSION);
                  WorldAuthoredVersionIdentityReceipt version =
                      new WorldAuthoredVersionIdentityRepository(dsl)
                          .acceptFresh(
                              intake,
                              AuthoredWorldVersionStateEvidence.create(
                                  stateRequest,
                                  source,
                                  canonicalVersion,
                                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                                  1));
                  OwnerBinding owner =
                      new OwnerBinding(
                          NAMESPACE,
                          tenant,
                          canonicalVersion,
                          version.operationId(),
                          GAME_DESIGN_VERSION,
                          intakeRequest,
                          intake.operationId(),
                          intake.requestDigest(),
                          sourceOperation,
                          evidenceDigest,
                          intake.receiptDigest());
                  assertOrigin();
                  return new Fixture(intake, version, owner);
                }));
  }

  private WorldDraftTopologyCommitPlan plan(Fixture f) {
    UUID logical = UUID.randomUUID();
    UUID secondRoom = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    UUID commit = UUID.randomUUID();
    List<WorldDesignMutationRevision> values = new ArrayList<>();
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setRegion(
                RegionDesignMutation.newBuilder()
                    .setName("region")
                    .setWeather("rain")
                    .setShardId(7)
                    .setGenerationSeed(9_007_199_254_740_999L)
                    .setGeneratorType("synthetic")
                    .setGeneratorParams("{\"seed\":1}"))
            .build());
    values.add(
        mutation(
                commit, logical, logical, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
            .setZone(
                ZoneDesignMutation.newBuilder().setName("zone").setRegionId(logical.toString()))
            .build());
    values.add(
        mutation(
                commit, logical, logical, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder()
                    .setName("room")
                    .setZoneId(logical.toString())
                    .setDescription("description")
                    .setNameLocalizedVariantsJson("{\"en\":\"room\"}")
                    .setDescriptionLocalizedVariantsJson("{\"en\":\"description\"}"))
            .build());
    values.add(
        mutation(
                commit,
                secondRoom,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
            .setRoom(
                RoomDesignMutation.newBuilder().setName("second").setZoneId(logical.toString()))
            .build());
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT)
            .setRoomExit(
                RoomExitDesignMutation.newBuilder()
                    .setFromRoomId(logical.toString())
                    .setToRoomId(secondRoom.toString())
                    .setDirection("NORTH"))
            .build());
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE)
            .setGenerationRule(
                GenerationRuleDesignMutation.newBuilder().setName("rule").setValue("seeded"))
            .build());
    values.add(
        mutation(
                commit,
                logical,
                logical,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)
            .setWorldEntitySpawnBinding(
                WorldEntitySpawnBindingDesignMutation.newBuilder()
                    .setRoomId(logical.toString())
                    .setEntityTemplateType(
                        EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                    .setEntityTemplateId(entity.toString())
                    .setRespawnDelaySeconds(17))
            .build());
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> units = new ArrayList<>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.randomUUID(),
            Owner.ENTITY_MANAGEMENT,
            "synthetic opaque Entity input; existence and permission remain unverified"));
    units.add(
        new AffectedUnit(
            Owner.ENTITY_MANAGEMENT,
            "NPC",
            entity.toString(),
            "AGGREGATE",
            entity.toString(),
            "0"));
    // Original input order deliberately places children before their parents.
    for (var m : values.reversed()) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(m.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              json(m)));
      String family = m.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              m.getAggregateId(),
              "AGGREGATE",
              m.getAggregateId(),
              "0"));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              m.getAggregateId(),
              "REGION_SUBTREE",
              m.getScopeId(),
              "0"));
    }
    var s = f.intake().source();
    var target =
        new DraftCommitBinding.TargetProof(
            f.owner().canonicalTenantId(),
            f.owner().canonicalVersionId(),
            GAME_DESIGN_VERSION,
            s.sourceGameTenantKey(),
            s.sourceGameRowId(),
            s.sourceGameTenantKey(),
            s.provenanceKind());
    return WorldDraftTopologyCommitPlan.create(
        DraftCommitBinding.create(
            target, UUID.randomUUID(), commit, "retained-base", revisions, units),
        f.owner());
  }

  private WorldDesignMutationRevision.Builder mutation(
      UUID commit, UUID id, UUID scope, WorldDesignAggregateType type) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(id.toString())
        .setAggregateType(type)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(scope.toString());
  }

  private WorldDraftTopologyCommitService component() {
    return new WorldDraftTopologyCommitService(
        repository(),
        manager,
        p -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertOrigin();
          // Synthetic local-storage fixture only; no Account permission or Entity existence proof.
        });
  }

  private WorldDraftTopologyCommitRepository repository() {
    return new WorldDraftTopologyCommitRepository(dsl, fence, mapper);
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return tx;
  }

  private void assertOrigin() {
    assertThat(
            dsl.resultQuery("SELECT current_setting('session_replication_role')")
                .fetchOne(0, String.class))
        .isEqualTo("origin");
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(mutation);
    } catch (InvalidProtocolBufferException exception) {
      throw new AssertionError(exception);
    }
  }

  private record Fixture(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      OwnerBinding owner) {}
}
