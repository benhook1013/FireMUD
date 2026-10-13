package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Synthetic source/permission fixtures prove component storage only, never authenticated APPLIED.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldDraftTopologyCommitPostgresIntegrationTest {
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
  @Autowired private ObjectMapper mapper;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void allSixFamiliesAllocateTypedUuidRowsAndPreserveExactInputWithDefaults() {
    Fixture f = fixture();
    var p = plan(f);
    var e = component().store(p);
    assertThat(e.binding()).isEqualTo(p.binding());
    assertThat(e.status()).isEqualTo("STORED_PERMISSION_UNVERIFIED");
    assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
    assertThat(count(f, "world_design_aggregate_epoch")).isEqualTo(7);
    assertThat(count(f, "world_design_scope_epoch")).isEqualTo(1);
    for (String t :
        List.of("region", "zone", "room_exit", "generation_rule", "world_entity_spawn_binding")) {
      assertThat(count(f, t)).isEqualTo(1);
    }
    assertThat(count(f, "room")).isEqualTo(2);
    var nodes = p.graph().nodes();
    UUID logical = nodes.getLast().templateId();
    assertThat(nodes.stream().filter(n -> n.templateId().equals(logical)).toList()).hasSize(6);
    for (var n : nodes) {
      String family =
          n.mutation().getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      assertThat(privateKey(p, family, n.templateId())).isPositive();
      assertThat(n.templateId().toString()).contains("-");
    }
    assertThat(
            dsl.resultQuery(
                    "SELECT spacing_multiplier FROM region WHERE tenant_id=?",
                    f.intake().localTenantKey())
                .fetchOne(0, Double.class))
        .isEqualTo(1.0);
    assertThat(
            dsl.resultQuery(
                    "SELECT cost FROM room_exit WHERE tenant_id=?", f.intake().localTenantKey())
                .fetchOne(0, Integer.class))
        .isEqualTo(1);
    var spawn = nodes.getFirst().entityReference();
    var row =
        dsl.resultQuery(
                "SELECT * FROM world_entity_spawn_binding WHERE tenant_id=?",
                f.intake().localTenantKey())
            .fetchOne();
    assertThat(row).isNotNull();
    assertThat(row.get("entity_template_id")).isNull();
    assertThat(row.get("entity_canonical_template_id", UUID.class)).isEqualTo(spawn.templateId());
    assertThat(row.get("entity_canonical_tenant_id", UUID.class)).isEqualTo(spawn.tenantId());
    assertThat(row.get("entity_canonical_version_id", UUID.class)).isEqualTo(spawn.versionId());
    assertThat(row.get("spawn_count", Integer.class)).isEqualTo(1);
    assertThat(new String(e.graphBytes(), StandardCharsets.UTF_8))
        .contains(
            "\"schemaVersion\":\"2\"",
            "entity_canonical_template_id",
            spawn.templateId().toString());
    assertThat(new String(e.resultBytes(), StandardCharsets.UTF_8))
        .contains(p.binding().digest(), "\"epoch\":\"1\"")
        .doesNotContain("\"APPLIED\"", "permissionFenceReleased");
    // The retained numeric graph/v1 decoder must fail closed on canonical Entity UUID references.
    assertThatThrownBy(
            () ->
                new WorldAuthoredGraphReader(dsl)
                    .readAndValidateGraph(
                        f.intake().localTenantKey(), f.version().localVersionKey()))
        .hasMessageContaining("entity_template_id");
    // No canonical Entity private mapping or authenticated Account proof was manufactured.
    assertThat(
            dsl.resultQuery("SELECT count(*) FROM world_region_draft_execution_manifest")
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void snakeCaseAndNumericEnumsStoreDefaultsAndRetainOriginalRawBindingAndRetry()
      throws InvalidProtocolBufferException {
    Fixture f = fixture();
    var printerPlan = plan(f);
    var original = printerPlan.binding();
    List<DraftCommitBinding.RevisionPayload> rawRevisions = new ArrayList<>();
    for (var revision : original.revisions()) {
      String raw = revision.payload();
      if (revision.owner() == Owner.WORLD_MANAGEMENT) {
        var node =
            printerPlan.graph().nodes().stream()
                .filter(n -> n.revisionId().equals(revision.revisionId()))
                .findFirst()
                .orElseThrow();
        raw =
            JsonFormat.printer()
                .preservingProtoFieldNames()
                .printingEnumsAsInts()
                .omittingInsignificantWhitespace()
                .print(node.mutation());
      }
      rawRevisions.add(
          new DraftCommitBinding.RevisionPayload(
              revision.revisionOrder(), revision.revisionId(), revision.owner(), raw));
    }
    var rawBinding =
        DraftCommitBinding.create(
            original.target(),
            original.requestId(),
            original.commitId(),
            original.baseCommitId(),
            rawRevisions,
            original.affectedUnits());
    var rawPlan = WorldDraftTopologyCommitPlan.create(rawBinding, printerPlan.ownerBinding());
    var rawFirst = rawBinding.revisions().get(1).payload();
    assertThat(rawFirst)
        .contains(
            "\"aggregate_type\":"
                + rawPlan.graph().nodes().getFirst().mutation().getAggregateType().getNumber(),
            "\"entity_template_type\":");
    var stored = component().store(rawPlan);
    assertThat(stored.binding().canonicalBytes()).containsExactly(rawBinding.canonicalBytes());
    assertThat(stored.binding().digest())
        .isEqualTo(rawBinding.digest())
        .isNotEqualTo(original.digest());
    var history =
        dsl.resultQuery(
                "SELECT binding_json,binding_digest FROM world_topology_draft_commit WHERE request_id=?",
                rawBinding.requestId())
            .fetchOne();
    assertThat(history).isNotNull();
    assertThat(history.get("binding_json", String.class)).isEqualTo(rawBinding.canonicalJson());
    assertThat(history.get("binding_digest", String.class)).isEqualTo(rawBinding.digest());
    assertThat(
            dsl.resultQuery(
                    "SELECT spacing_multiplier FROM region WHERE tenant_id=?",
                    f.intake().localTenantKey())
                .fetchOne(0, Double.class))
        .isEqualTo(1.0);
    assertThat(
            dsl.resultQuery(
                    "SELECT cost FROM room_exit WHERE tenant_id=?", f.intake().localTenantKey())
                .fetchOne(0, Integer.class))
        .isEqualTo(1);
    assertThat(
            dsl.resultQuery(
                    "SELECT spawn_count FROM world_entity_spawn_binding WHERE tenant_id=?",
                    f.intake().localTenantKey())
                .fetchOne(0, Integer.class))
        .isEqualTo(1);
    var retry = component().store(rawPlan);
    assertThat(retry.resultBytes()).containsExactly(stored.resultBytes());
    assertThat(retry.graphBytes()).containsExactly(stored.graphBytes());
    var readback =
        new WorldDraftTopologyCommitService(repository(), manager)
            .readCommitted(rawPlan)
            .orElseThrow();
    assertThat(readback.resultBytes()).containsExactly(stored.resultBytes());
    assertThat(readback.binding().revisions()).isEqualTo(rawRevisions);
    assertThat(new String(stored.resultBytes(), StandardCharsets.UTF_8))
        .contains(rawBinding.digest());
    // Same parsed semantics with different raw bytes remains a changed complete operation.
    rejectReplay(printerPlan);
    assertThat(count(f, "world_topology_draft_commit")).isEqualTo(1);
  }

  @Test
  void alteredTrustedProjectionSemanticsRollBackAgainstOriginalParsedPlan()
      throws InvalidProtocolBufferException {
    Fixture f = fixture();
    var p = plan(f);
    var tampered =
        new WorldDraftTopologyCommitRepository(dsl, fence, mapper) {
          @Override
          List<DraftCommitBinding.RevisionPayload> executionRevisions(
              WorldDraftTopologyCommitPlan input) {
            var revisions = new ArrayList<>(super.executionRevisions(input));
            var node = input.graph().nodes().getFirst();
            var changed =
                node.mutation().toBuilder()
                    .setWorldEntitySpawnBinding(
                        node.mutation().getWorldEntitySpawnBinding().toBuilder().setSpawnCount(2))
                    .build();
            var original = revisions.getFirst();
            revisions.set(
                0,
                new DraftCommitBinding.RevisionPayload(
                    original.revisionOrder(),
                    original.revisionId(),
                    original.owner(),
                    json(changed)));
            return revisions;
          }
        };
    assertThatThrownBy(
            () -> new WorldDraftTopologyCommitService(tampered, manager, x -> {}).store(p))
        .hasMessageContaining("every exact payload field");
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
            "world_topology_draft_commit")) {
      assertThat(count(f, table)).as(table).isZero();
    }
    assertThat(new WorldDraftTopologyCommitService(repository(), manager).readCommitted(p))
        .isEmpty();
    component().store(p);
    assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
  }

  @Test
  void lostAckAndIndependentFrozenReadbackReturnOriginalExactEvidence() {
    Fixture f = fixture();
    var p = plan(f);
    var denied = new WorldDraftTopologyCommitService(repository(), manager);
    assertThat(denied.readCommitted(p)).isEmpty();
    var first = component().store(p);
    var retry = component().store(p);
    assertThat(retry.graphBytes()).containsExactly(first.graphBytes());
    assertThat(retry.resultBytes()).containsExactly(first.resultBytes());
    freeze(f);
    var read = denied.readCommitted(p).orElseThrow();
    assertThat(read.graphBytes()).containsExactly(first.graphBytes());
    assertThat(read.resultBytes()).containsExactly(first.resultBytes());
    assertThat(component().store(p).resultBytes()).containsExactly(first.resultBytes());
    assertThat(count(f, "world_topology_draft_commit")).isEqualTo(1);
  }

  @Test
  void completeReplayRejectsChangedBasePayloadOrderAffectedAndSource() {
    Fixture f = fixture();
    var p = plan(f);
    component().store(p);
    var b = p.binding();
    var changedBase =
        WorldDraftTopologyCommitPlan.create(
            DraftCommitBinding.create(
                b.target(),
                b.requestId(),
                b.commitId(),
                b.baseCommitId() + "-changed",
                b.revisions(),
                b.affectedUnits()),
            p.ownerBinding());
    rejectReplay(changedBase);
    var t = b.target();
    var changedTarget =
        new DraftCommitBinding.TargetProof(
            t.canonicalTenantId(),
            t.canonicalVersionId(),
            t.gameDesignVersionRowId(),
            t.gameDesignVersionTenantKey(),
            t.sourceGameRowId() + 1,
            t.sourceGameTenantKey(),
            t.sourceProvenanceKind());
    rejectReplay(
        WorldDraftTopologyCommitPlan.create(
            DraftCommitBinding.create(
                changedTarget,
                b.requestId(),
                b.commitId(),
                b.baseCommitId(),
                b.revisions(),
                b.affectedUnits()),
            p.ownerBinding()));
    var revisions = new ArrayList<>(b.revisions());
    var other = revisions.getFirst();
    revisions.set(
        0,
        new DraftCommitBinding.RevisionPayload(
            other.revisionOrder(), other.revisionId(), other.owner(), other.payload() + "changed"));
    rejectReplay(
        WorldDraftTopologyCommitPlan.create(
            DraftCommitBinding.create(
                b.target(),
                b.requestId(),
                b.commitId(),
                b.baseCommitId(),
                revisions,
                b.affectedUnits()),
            p.ownerBinding()));
    revisions = new ArrayList<>(b.revisions());
    var one = revisions.get(1);
    var two = revisions.get(2);
    revisions.set(
        1,
        new DraftCommitBinding.RevisionPayload("1", two.revisionId(), two.owner(), two.payload()));
    revisions.set(
        2,
        new DraftCommitBinding.RevisionPayload("2", one.revisionId(), one.owner(), one.payload()));
    rejectReplay(
        WorldDraftTopologyCommitPlan.create(
            DraftCommitBinding.create(
                b.target(),
                b.requestId(),
                b.commitId(),
                b.baseCommitId(),
                revisions,
                b.affectedUnits()),
            p.ownerBinding()));
    assertThatThrownBy(
            () ->
                WorldDraftTopologyCommitPlan.create(
                    DraftCommitBinding.create(
                        b.target(),
                        b.requestId(),
                        b.commitId(),
                        b.baseCommitId(),
                        b.revisions(),
                        b.affectedUnits().subList(1, b.affectedUnits().size())),
                    p.ownerBinding()))
        .isInstanceOf(IllegalArgumentException.class);
    var o = p.ownerBinding();
    var source =
        new OwnerBinding(
            o.targetNamespace(),
            o.canonicalTenantId(),
            o.canonicalVersionId(),
            o.versionIdentityOperationId(),
            o.gameDesignVersionId(),
            o.intakeRequestId(),
            o.intakeOperationId(),
            o.intakeRequestDigest(),
            UUID.randomUUID(),
            o.sourceEvidenceDigest(),
            o.intakeReceiptDigest());
    rejectReplay(WorldDraftTopologyCommitPlan.create(b, source));
    assertThat(count(f, "world_topology_draft_commit")).isEqualTo(1);
  }

  private void rejectReplay(WorldDraftTopologyCommitPlan p) {
    assertThatThrownBy(() -> component().store(p))
        .hasMessageContaining("changed full input or source");
    assertThatThrownBy(
            () -> new WorldDraftTopologyCommitService(repository(), manager).readCommitted(p))
        .hasMessageContaining("changed full input or source");
  }

  @Test
  void lateFailureRollsBackAllFamiliesMappingsEpochsAndHistory() {
    Fixture f = fixture();
    var p = plan(f);
    var failing =
        new WorldDraftTopologyCommitRepository(dsl, fence, mapper) {
          @Override
          WorldDraftTopologyCommitEvidence store(WorldDraftTopologyCommitPlan input) {
            super.store(input);
            throw new IllegalStateException("late complete result conflict");
          }
        };
    assertThatThrownBy(
            () -> new WorldDraftTopologyCommitService(failing, manager, x -> {}).store(p))
        .hasMessage("late complete result conflict");
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
            "world_topology_draft_commit")) {
      assertThat(count(f, table)).as(table).isZero();
    }
    assertThat(new WorldDraftTopologyCommitService(repository(), manager).readCommitted(p))
        .isEmpty();
    component().store(p);
    assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
  }

  @Test
  void exactConcurrentFirstClaimReturnsOneOriginalResult() throws Exception {
    Fixture f = fixture();
    var p = plan(f);
    CountDownLatch begin = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<WorldDraftTopologyCommitEvidence> one =
          executor.submit(
              () -> {
                await(begin);
                return component().store(p);
              });
      Future<WorldDraftTopologyCommitEvidence> two =
          executor.submit(
              () -> {
                await(begin);
                return component().store(p);
              });
      begin.countDown();
      var first = one.get(20, TimeUnit.SECONDS);
      var second = two.get(20, TimeUnit.SECONDS);
      assertThat(second.resultBytes()).containsExactly(first.resultBytes());
      assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
      assertThat(count(f, "world_topology_draft_commit")).isEqualTo(1);
    } finally {
      begin.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void conflictingFirstClaimsCannotRemapPreviouslyAllocatedRows() throws Exception {
    Fixture f = fixture();
    var first = plan(f);
    var second = plan(f);
    CountDownLatch begin = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> one = executor.submit(() -> contend(first, begin));
      Future<Boolean> two = executor.submit(() -> contend(second, begin));
      begin.countDown();
      assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
      assertThat(count(f, "world_topology_draft_commit")).isEqualTo(1);
    } finally {
      begin.countDown();
      executor.shutdownNow();
    }
  }

  private boolean contend(WorldDraftTopologyCommitPlan p, CountDownLatch begin) {
    await(begin);
    try {
      component().store(p);
      return true;
    } catch (RuntimeException exception) {
      assertThat(exception).hasStackTraceContaining("conflicts with retained");
      return false;
    }
  }

  @Test
  void freezeFirstDeniesAndWriterFirstSerializesFreezeUntilAtomicCommit() throws Exception {
    Fixture frozen = fixture();
    freeze(frozen);
    assertThatThrownBy(() -> component().store(plan(frozen))).hasMessageContaining("not open");
    assertThat(count(frozen, "world_authored_topology_identity")).isZero();
    Fixture f = fixture();
    var p = plan(f);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch freezing = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var paused =
          new WorldDraftTopologyCommitRepository(dsl, fence, mapper) {
            @Override
            WorldDraftTopologyCommitEvidence store(WorldDraftTopologyCommitPlan input) {
              var result = super.store(input);
              locked.countDown();
              await(release);
              return result;
            }
          };
      Future<?> writer =
          executor.submit(
              () -> new WorldDraftTopologyCommitService(paused, manager, x -> {}).store(p));
      assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
      Future<?> freeze =
          executor.submit(
              () -> {
                freezing.countDown();
                freeze(f);
              });
      assertThat(freezing.await(15, TimeUnit.SECONDS)).isTrue();
      assertThat(freeze.isDone()).isFalse();
      assertThat(count(f, "region")).isZero();
      assertThat(count(f, "world_topology_draft_commit")).isZero();
      release.countDown();
      writer.get(20, TimeUnit.SECONDS);
      freeze.get(20, TimeUnit.SECONDS);
      assertThat(count(f, "world_authored_topology_identity")).isEqualTo(7);
      assertThat(new WorldDraftTopologyCommitService(repository(), manager).readCommitted(p))
          .isPresent();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void ordinaryWritesMappedMovesEpochTamperAndTruncateStayDenied() {
    Fixture f = fixture();
    var p = plan(f);
    component().store(p);
    long tenant = f.intake().localTenantKey();
    long version = f.version().localVersionKey();
    for (String table :
        List.of(
            "region",
            "zone",
            "room",
            "room_exit",
            "generation_rule",
            "world_entity_spawn_binding")) {
      assertThatThrownBy(
              () ->
                  ownerTransaction()
                      .execute(
                          s ->
                              dsl.execute(
                                  "INSERT INTO "
                                      + table
                                      + " SELECT (jsonb_populate_record(NULL::"
                                      + table
                                      + ",to_jsonb(t)||'{\"id\":-987654321}'::jsonb)).*"
                                      + " FROM "
                                      + table
                                      + " t WHERE tenant_id="
                                      + tenant)))
          .hasStackTraceContaining("no exact transaction execution manifest");
      for (String statement :
          List.of(
              "UPDATE " + table + " SET version=99 WHERE tenant_id=" + tenant,
              "UPDATE " + table + " SET tenant_id=123456789 WHERE tenant_id=" + tenant,
              "DELETE FROM " + table + " WHERE tenant_id=" + tenant,
              "TRUNCATE " + table)) {
        assertThatThrownBy(() -> ownerTransaction().execute(s -> dsl.execute(statement)))
            .isInstanceOf(RuntimeException.class);
      }
    }
    for (String statement :
        List.of(
            "INSERT INTO region(name,tenant_id,version_id) VALUES ('unmanifested',"
                + tenant
                + ","
                + version
                + ")",
            "UPDATE world_design_aggregate_epoch SET draft_revision_epoch=99 WHERE tenant_id="
                + tenant,
            "DELETE FROM world_design_scope_epoch WHERE tenant_id=" + tenant,
            "UPDATE world_authored_topology_identity SET private_row_key=999 WHERE tenant_id="
                + tenant,
            "DELETE FROM world_authored_topology_identity WHERE tenant_id=" + tenant,
            "TRUNCATE world_authored_topology_identity",
            "TRUNCATE world_topology_draft_commit",
            "TRUNCATE world_design_aggregate_epoch",
            "TRUNCATE world_design_scope_epoch")) {
      assertThatThrownBy(() -> ownerTransaction().execute(s -> dsl.execute(statement)))
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(new WorldDraftTopologyCommitService(repository(), manager).readCommitted(p))
        .isPresent();
  }

  @Test
  void staleOrWrongInsertManifestCannotAuthorizeOrdinaryFreshRows() {
    Fixture f = fixture();
    var p = plan(f);
    ownerTransaction()
        .execute(
            s -> {
              fence.lockOpen(f.owner());
              return null;
            });
    for (boolean stale : List.of(true, false)) {
      assertThatThrownBy(
              () ->
                  ownerTransaction()
                      .execute(
                          s -> {
                            dsl.execute(
                                "INSERT INTO world_region_draft_execution_manifest "
                                    + "SELECT txid_current()+?,?,'region',987654321,'INSERT',?,?,?,?::jsonb,?::jsonb,NULL,"
                                    + "to_jsonb(jsonb_populate_record(NULL::region,'{\"id\":987654321,\"name\":\"expected\"}'::jsonb))",
                                stale ? 1 : 0,
                                p.binding().commitId(),
                                f.version().operationId(),
                                f.intake().localTenantKey(),
                                f.version().localVersionKey(),
                                mapper.writeValueAsString(f.owner()),
                                p.binding().canonicalJson());
                            return dsl.execute(
                                "INSERT INTO region(id,name,tenant_id,version_id) VALUES (987654321,'wrong',?,?)",
                                f.intake().localTenantKey(),
                                f.version().localVersionKey());
                          }))
          .hasStackTraceContaining("no exact transaction execution manifest");
    }
    assertThat(count(f, "region")).isZero();
  }

  @Test
  void retainedRowsArePreservedUnmappedAndDenyFreshContent() {
    Fixture f = fixture();
    var p = plan(f);
    ownerTransaction()
        .execute(
            s -> {
              dsl.execute("SET LOCAL session_replication_role='replica'");
              try {
                dsl.execute(
                    "INSERT INTO region(name,tenant_id,version_id) VALUES ('retained',?,?)",
                    f.intake().localTenantKey(),
                    f.version().localVersionKey());
              } finally {
                dsl.execute("SET LOCAL session_replication_role='origin'");
              }
              return null;
            });
    assertOrigin();
    assertThatThrownBy(() -> component().store(p))
        .hasStackTraceContaining("conflicts with retained region");
    assertThat(count(f, "region")).isEqualTo(1);
    assertThat(count(f, "world_authored_topology_identity")).isZero();
    assertThat(count(f, "world_topology_draft_commit")).isZero();
  }

  @Test
  void deniedPermissionAndCallerTransactionCannotClaimOrReadOwnerStorage() {
    Fixture f = fixture();
    var p = plan(f);
    var denied = new WorldDraftTopologyCommitService(repository(), manager);
    assertThatThrownBy(() -> denied.store(p)).hasMessageContaining("permission is unavailable");
    assertThatThrownBy(() -> ownerTransaction().execute(s -> component().store(p)))
        .hasMessageContaining("existing transaction");
    assertThatThrownBy(() -> ownerTransaction().execute(s -> denied.readCommitted(p)))
        .hasMessageContaining("no active caller transaction");
    assertThat(count(f, "world_authored_topology_identity")).isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM world_design_publication_fence_owner WHERE local_tenant_key=?",
                    f.intake().localTenantKey())
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void v29ToV30PreservesNumericRowsSourceEpochsAndOriginalRegionHistory() throws Exception {
    Fixture f = fixture();
    long region =
        Objects.requireNonNull(
            ownerTransaction()
                .execute(
                    s -> {
                      dsl.execute("SET LOCAL session_replication_role='replica'");
                      try {
                        long r =
                            Objects.requireNonNull(
                                dsl.resultQuery(
                                        "INSERT INTO region(name,tenant_id,version_id) VALUES ('retained',?,?) RETURNING id",
                                        f.intake().localTenantKey(),
                                        f.version().localVersionKey())
                                    .fetchOne(0, Long.class),
                                "Missing returned region fixture key");
                        long z =
                            Objects.requireNonNull(
                                dsl.resultQuery(
                                        "INSERT INTO zone(region_id,name,tenant_id,version_id) VALUES (?,'zone',?,?) RETURNING id",
                                        r,
                                        f.intake().localTenantKey(),
                                        f.version().localVersionKey())
                                    .fetchOne(0, Long.class),
                                "Missing returned zone fixture key");
                        long room =
                            Objects.requireNonNull(
                                dsl.resultQuery(
                                        "INSERT INTO room(zone_id,name,tenant_id,version_id) VALUES (?,'room',?,?) RETURNING id",
                                        z,
                                        f.intake().localTenantKey(),
                                        f.version().localVersionKey())
                                    .fetchOne(0, Long.class),
                                "Missing returned room fixture key");
                        dsl.execute(
                            "INSERT INTO room_exit(tenant_id,version_id,from_room_id,to_room_id) VALUES (?,?,?,?)",
                            f.intake().localTenantKey(),
                            f.version().localVersionKey(),
                            room,
                            room);
                        dsl.execute(
                            "INSERT INTO generation_rule(tenant_id,version_id,name,scope_type,scope_id,value) VALUES (?,?,'rule','REGION_SUBTREE',?,'seeded')",
                            f.intake().localTenantKey(),
                            f.version().localVersionKey(),
                            Long.toString(r));
                        dsl.execute(
                            "INSERT INTO world_entity_spawn_binding(tenant_id,version_id,room_id,entity_template_type,entity_template_id) VALUES (?,?,?,'NPC',123)",
                            f.intake().localTenantKey(),
                            f.version().localVersionKey(),
                            room);
                        return r;
                      } finally {
                        dsl.execute("SET LOCAL session_replication_role='origin'");
                      }
                    }));
    UUID commit = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    var mutation =
        WorldDesignMutationRevision.newBuilder()
            .setCommitId(commit.toString())
            .setLogicalRevisionId(revision.toString())
            .setAggregateId(Long.toString(region))
            .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
            .setScopeId(Long.toString(region))
            .setRegion(RegionDesignMutation.newBuilder().setName("retained-v29"))
            .build();
    var source = f.intake().source();
    var target =
        new DraftCommitBinding.TargetProof(
            f.owner().canonicalTenantId(),
            f.owner().canonicalVersionId(),
            GAME_DESIGN_VERSION,
            source.sourceGameTenantKey(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            commit,
            "retained-base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0", revision, Owner.WORLD_MANAGEMENT, json(mutation))),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "REGION",
                    Long.toString(region),
                    "AGGREGATE",
                    Long.toString(region),
                    "0"),
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "REGION",
                    Long.toString(region),
                    "REGION_SUBTREE",
                    Long.toString(region),
                    "0")));
    var retainedPlan = WorldDraftRegionCommitPlan.create(binding, f.owner());
    var prior =
        new WorldDraftRegionCommitService(
                new WorldDraftRegionCommitRepository(dsl, fence, mapper), manager, x -> {})
            .store(retainedPlan);
    String schema = "uuid_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("29"))
        .load()
        .migrate();
    try (Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      connection.setSchema(schema);
      DSLContext retained = DSL.using(connection, SQLDialect.POSTGRES);
      connection.setAutoCommit(false);
      retained.execute("SET LOCAL session_replication_role='replica'");
      List<String> sourceTables =
          List.of(
              "world_authored_source_tenant_association",
              "world_authored_source_tenant_key_reservation",
              "world_authored_source_intake",
              "world_authored_version_identity",
              "world_design_publication_fence_owner",
              "world_region_draft_commit");
      List<String> contentTables =
          List.of(
              "region",
              "zone",
              "room",
              "room_exit",
              "generation_rule",
              "world_entity_spawn_binding",
              "world_design_aggregate_epoch",
              "world_design_scope_epoch");
      try {
        for (String table : sourceTables) {
          String key =
              table.equals("world_authored_source_tenant_key_reservation")
                  ? "tenant_key"
                  : "local_tenant_key";
          String override =
              table.equals("world_authored_version_identity") ? " OVERRIDING SYSTEM VALUE" : "";
          retained.execute(
              "INSERT INTO "
                  + table
                  + override
                  + " SELECT * FROM world_management_service."
                  + table
                  + " WHERE "
                  + key
                  + "=?",
              f.intake().localTenantKey());
        }
        for (String table : contentTables) {
          if (table.equals("world_entity_spawn_binding")) {
            retained.execute(
                "INSERT INTO world_entity_spawn_binding (id,tenant_id,version_id,room_id,entity_template_type,entity_template_id,spawn_count,respawn_delay_seconds,version) "
                    + "SELECT id,tenant_id,version_id,room_id,entity_template_type,entity_template_id,spawn_count,respawn_delay_seconds,version "
                    + "FROM world_management_service.world_entity_spawn_binding WHERE tenant_id=?",
                f.intake().localTenantKey());
          } else {
            retained.execute(
                "INSERT INTO "
                    + table
                    + " SELECT * FROM world_management_service."
                    + table
                    + " WHERE tenant_id=?",
                f.intake().localTenantKey());
          }
        }
        retained.execute("SET LOCAL session_replication_role='origin'");
        connection.commit();
      } catch (RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
      connection.setAutoCommit(true);
      List<String> all = new ArrayList<>(sourceTables);
      all.addAll(contentTables);
      Map<String, List<String>> before = retainedState(retained, all);
      Flyway.configure()
          .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      Map<String, List<String>> after = retainedState(retained, all);
      assertThat(after).isEqualTo(before);
      assertThat(
              retained
                  .resultQuery("SELECT count(*) FROM world_authored_topology_identity")
                  .fetchOne(0, Long.class))
          .isZero();
      assertThat(
              retained
                  .resultQuery("SELECT entity_template_id FROM world_entity_spawn_binding")
                  .fetchOne(0, Long.class))
          .isEqualTo(123);
      assertThat(
              retained
                  .resultQuery(
                      "SELECT entity_canonical_template_id FROM world_entity_spawn_binding")
                  .fetchOne(0, UUID.class))
          .isNull();
      assertThat(
              retained
                  .resultQuery("SELECT result_bytes FROM world_region_draft_commit")
                  .fetchOne(0, byte[].class))
          .containsExactly(prior.resultBytes());
      assertThatThrownBy(() -> retained.execute("UPDATE region SET name='denied'"))
          .isInstanceOf(RuntimeException.class);
    } finally {
      dsl.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private Map<String, List<String>> retainedState(DSLContext db, List<String> tables) {
    Map<String, List<String>> state = new LinkedHashMap<>();
    for (String table : tables) {
      String projection =
          table.equals("world_entity_spawn_binding")
              ? "to_jsonb(t)-'entity_canonical_tenant_id'-'entity_canonical_version_id'-'entity_canonical_template_id'"
              : "to_jsonb(t)";
      state.put(
          table,
          db.resultQuery(
                  "SELECT ("
                      + projection
                      + ")::text FROM "
                      + table
                      + " t ORDER BY ("
                      + projection
                      + ")::text")
              .fetch(0, String.class));
    }
    return state;
  }

  @Test
  void immutableHistoryAndEveryStoredPayloadAreVerifiedOnIndependentReadback() {
    for (String corrupt :
        List.of(
            "UPDATE region SET weather='tampered'",
            "UPDATE zone SET name='tampered'",
            "UPDATE room SET description_localized_variants_json='tampered'",
            "UPDATE room_exit SET cost=19",
            "UPDATE generation_rule SET value='tampered'",
            "UPDATE world_entity_spawn_binding SET entity_canonical_template_id='"
                + UUID.randomUUID()
                + "'",
            "UPDATE world_authored_topology_identity SET revision_order='99'",
            "UPDATE world_design_aggregate_epoch SET draft_revision_epoch=2",
            "UPDATE world_design_scope_epoch SET draft_scope_revision_epoch=2",
            "UPDATE world_topology_draft_commit SET result_bytes='tampered'::bytea")) {
      Fixture f = fixture();
      var p = plan(f);
      component().store(p);
      String column =
          corrupt.contains("world_topology_draft_commit") ? "local_tenant_key" : "tenant_id";
      String statement = corrupt + " WHERE " + column + "=" + f.intake().localTenantKey();
      assertThatThrownBy(() -> dsl.execute(statement)).isInstanceOf(RuntimeException.class);
      ownerTransaction()
          .execute(
              s -> {
                dsl.execute("SET LOCAL session_replication_role='replica'");
                try {
                  dsl.execute(statement);
                } finally {
                  dsl.execute("SET LOCAL session_replication_role='origin'");
                }
                return null;
              });
      assertOrigin();
      assertThatThrownBy(
              () -> new WorldDraftTopologyCommitService(repository(), manager).readCommitted(p))
          .isInstanceOf(RuntimeException.class);
    }
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

  private void freeze(Fixture f) {
    OwnerBinding o = f.owner();
    String request = "freeze-" + UUID.randomUUID();
    WorldDesignPublicationFenceEvidence evidence =
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
            request,
            "a".repeat(64),
            1,
            "publish:" + o.canonicalTenantId() + ":publish-request:" + request);
    ownerTransaction()
        .execute(
            status ->
                fence.claimFreeze(
                    evidence,
                    () ->
                        new WorldDesignPublicationFenceEvidence.Checkpoint(
                            "synthetic-unverified-checkpoint", "b".repeat(64), 2)));
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

  private long count(Fixture f, String table) {
    String tenant = table.equals("world_topology_draft_commit") ? "local_tenant_key" : "tenant_id";
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT count(*) FROM " + table + " WHERE " + tenant + "=?",
                f.intake().localTenantKey())
            .fetchOne(0, Long.class));
  }

  private long privateKey(WorldDraftTopologyCommitPlan p, String family, UUID id) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT private_row_key FROM world_authored_topology_identity "
                    + "WHERE request_id=? AND family=? AND template_id=?",
                p.binding().requestId(),
                family,
                id)
            .fetchOne(0, Long.class));
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(mutation);
    } catch (InvalidProtocolBufferException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private record Fixture(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      OwnerBinding owner) {}
}
