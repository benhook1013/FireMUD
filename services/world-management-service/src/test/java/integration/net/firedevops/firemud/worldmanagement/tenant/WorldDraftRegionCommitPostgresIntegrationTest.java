package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
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
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
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
class WorldDraftRegionCommitPostgresIntegrationTest {
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
  void twoRegionsAdvanceFourTuplesWithFullHistoryAndExactLostAckReplay() {
    Fixture f = fixture();
    WorldDraftRegionCommitPlan firstPlan = plan(f, List.of(f.first(), f.second()), 0, "first");
    WorldDraftRegionCommitEvidence first = component().store(firstPlan);
    assertThat(name(f.first())).isEqualTo("first-" + f.first());
    assertThat(name(f.second())).isEqualTo("first-" + f.second());
    assertEpochs(f, 1, 1);
    assertThat(first.status()).isEqualTo("STORED_PERMISSION_UNVERIFIED");
    String graph = new String(first.graphBytes(), StandardCharsets.UTF_8);
    assertThat(graph)
        .contains(
            "\"regions\"",
            "\"zones\"",
            "\"rooms\"",
            "\"roomExits\"",
            "\"generationRules\"",
            "\"worldEntitySpawnBindings\"");
    assertThat(new String(first.resultBytes(), StandardCharsets.UTF_8))
        .contains(firstPlan.binding().digest(), "\"affectedEpochsAfter\"", "\"epoch\":\"1\"")
        .doesNotContain("\"APPLIED\"", "permissionFenceReleased");
    WorldDraftRegionCommitEvidence lostAck = component().store(firstPlan);
    assertThat(lostAck.graphBytes()).containsExactly(first.graphBytes());
    assertThat(lostAck.resultBytes()).containsExactly(first.resultBytes());
    component().store(plan(f, List.of(f.first(), f.second()), 1, "later"));
    freeze(f);
    // Historical retry precedes OPEN admission and preserves the original visibility snapshot.
    WorldDraftRegionCommitEvidence historical = component().store(firstPlan);
    assertThat(historical.graphBytes()).containsExactly(first.graphBytes());
    assertThat(historical.resultBytes()).containsExactly(first.resultBytes());
    assertThat(name(f.first())).startsWith("later-");
    assertEpochs(f, 2, 2);
    assertThat(count("world_region_draft_execution_manifest")).isZero();
  }

  @Test
  void lastScopeConflictRollsBackEarlierRegionPayloadEpochsAndEntireHistory() {
    Fixture f = fixture();
    seedScope(f, f.second(), 3);
    long countBefore = count("world_region_draft_commit");
    WorldDraftRegionCommitPlan plan = plan(f, List.of(f.first(), f.second()), 0, "conflicting");
    assertThatThrownBy(() -> component().store(plan))
        .hasStackTraceContaining("DRAFT_WRITE_CONFLICT: REGION_SUBTREE");
    assertThat(name(f.first())).isEqualTo("original-first");
    assertThat(name(f.second())).isEqualTo("original-second");
    assertThat(epochCount(f, "world_design_aggregate_epoch")).isZero();
    assertThat(epochCount(f, "world_design_scope_epoch")).isEqualTo(1);
    assertThat(count("world_region_draft_commit")).isEqualTo(countBefore);
    assertThat(count("world_region_draft_execution_manifest")).isZero();
  }

  @Test
  void disjointTupleCommitsSucceedFromTheSameRetainedBaseWithoutRebasing() {
    Fixture f = fixture();
    WorldDraftRegionCommitEvidence first =
        component().store(plan(f, List.of(f.first()), 0, "first"));
    WorldDraftRegionCommitEvidence second =
        component().store(plan(f, List.of(f.second()), 0, "second"));
    assertThat(first.binding().baseCommitId()).isEqualTo(second.binding().baseCommitId());
    assertEpochs(f, 1, 1);
    assertThat(new String(first.graphBytes(), StandardCharsets.UTF_8)).contains("original-second");
    assertThat(new String(second.graphBytes(), StandardCharsets.UTF_8))
        .contains("first-", "second-");
  }

  @Test
  void immutableFullInputRejectsChangedBaseOtherOwnerPayloadOrderAndTuples() {
    Fixture f = fixture();
    WorldDraftRegionCommitPlan original = plan(f, List.of(f.first(), f.second()), 0, "original");
    component().store(original);
    DraftCommitBinding binding = original.binding();
    assertChanged(
        original,
        binding.baseCommitId() + "-changed",
        binding.revisions(),
        binding.affectedUnits());
    List<DraftCommitBinding.RevisionPayload> changed = new ArrayList<>(binding.revisions());
    DraftCommitBinding.RevisionPayload other = changed.get(changed.size() - 1);
    changed.set(
        changed.size() - 1,
        new DraftCommitBinding.RevisionPayload(
            other.revisionOrder(), other.revisionId(), other.owner(), other.payload() + "changed"));
    assertChanged(original, binding.baseCommitId(), changed, binding.affectedUnits());
    List<DraftCommitBinding.RevisionPayload> reordered =
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                changed.get(1).revisionId(),
                Owner.WORLD_MANAGEMENT,
                binding.revisions().get(1).payload()),
            new DraftCommitBinding.RevisionPayload(
                "1",
                changed.get(0).revisionId(),
                Owner.WORLD_MANAGEMENT,
                binding.revisions().get(0).payload()),
            other);
    assertChanged(original, binding.baseCommitId(), reordered, binding.affectedUnits());
    assertThatThrownBy(
            () ->
                WorldDraftRegionCommitPlan.create(
                    DraftCommitBinding.create(
                        binding.target(),
                        binding.requestId(),
                        binding.commitId(),
                        binding.baseCommitId(),
                        binding.revisions(),
                        binding.affectedUnits().subList(1, binding.affectedUnits().size())),
                    original.ownerBinding()))
        .isInstanceOf(IllegalArgumentException.class);
    // Omitting a complete other-owner subset preserves World semantics but must conflict on replay.
    assertChanged(
        original,
        binding.baseCommitId(),
        binding.revisions().subList(0, 2),
        binding.affectedUnits(Owner.WORLD_MANAGEMENT));
    assertEpochs(f, 1, 1);
  }

  @Test
  void firstClaimAndExistingEpochContendersHaveOnlyOneWinner() throws Exception {
    Fixture f = fixture();
    race(f, 0);
    assertEpochs(f, 1, 1);
    race(f, 1);
    assertEpochs(f, 2, 2);
  }

  @Test
  void concurrentExactFirstClaimReturnsOneOriginalResult() throws Exception {
    Fixture f = fixture();
    WorldDraftRegionCommitPlan plan = plan(f, List.of(f.first(), f.second()), 0, "same");
    CountDownLatch begin = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<WorldDraftRegionCommitEvidence> first =
          executor.submit(
              () -> {
                await(begin);
                return component().store(plan);
              });
      Future<WorldDraftRegionCommitEvidence> second =
          executor.submit(
              () -> {
                await(begin);
                return component().store(plan);
              });
      begin.countDown();
      WorldDraftRegionCommitEvidence one = first.get(20, TimeUnit.SECONDS);
      WorldDraftRegionCommitEvidence two = second.get(20, TimeUnit.SECONDS);
      assertThat(two.resultBytes()).containsExactly(one.resultBytes());
      assertThat(two.graphBytes()).containsExactly(one.graphBytes());
      assertEpochs(f, 1, 1);
    } finally {
      begin.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void freezeFirstDeniesFreshStorageAndWriterFirstCompletesBeforeFreeze() throws Exception {
    Fixture frozen = fixture();
    freeze(frozen);
    assertThatThrownBy(() -> component().store(plan(frozen, List.of(frozen.first()), 0, "denied")))
        .hasMessageContaining("not open");
    assertThat(name(frozen.first())).isEqualTo("original-first");
    Fixture writer = fixture();
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch freezing = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      WorldDraftRegionCommitPlan writerPlan =
          plan(writer, List.of(writer.first(), writer.second()), 0, "writer-first");
      WorldDraftRegionCommitRepository pausedRepository =
          new WorldDraftRegionCommitRepository(dsl, fence, mapper) {
            @Override
            WorldDraftRegionCommitEvidence store(WorldDraftRegionCommitPlan plan) {
              WorldDraftRegionCommitEvidence evidence = super.store(plan);
              locked.countDown();
              await(release);
              return evidence;
            }
          };
      Future<?> first =
          executor.submit(
              () ->
                  new WorldDraftRegionCommitService(pausedRepository, manager, plan -> {})
                      .store(writerPlan));
      assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
      Future<?> freeze =
          executor.submit(
              () -> {
                freezing.countDown();
                freeze(writer);
              });
      assertThat(freezing.await(15, TimeUnit.SECONDS)).isTrue();
      assertThat(freeze.isDone()).isFalse();
      // Other connections still see the precommit complete owner graph and no result history.
      assertThat(name(writer.first())).isEqualTo("original-first");
      assertThat(name(writer.second())).isEqualTo("original-second");
      assertThat(
              dsl.resultQuery(
                      "SELECT COUNT(*) FROM world_region_draft_commit WHERE request_id = ?",
                      writerPlan.binding().requestId())
                  .fetchOne(0, Long.class))
          .isZero();
      release.countDown();
      first.get(20, TimeUnit.SECONDS);
      freeze.get(20, TimeUnit.SECONDS);
      assertThat(name(writer.first())).startsWith("writer-first-");
      assertThat(name(writer.second())).startsWith("writer-first-");
      assertEpochs(writer, 1, 1);
      assertThatThrownBy(
              () -> component().store(plan(writer, List.of(writer.first()), 0, "after-freeze")))
          .hasMessageContaining("not open");
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void productionGuardRejectsUnmanifestedPayloadMoveInsertDeleteEpochAndOtherFamilyWrites() {
    Fixture f = fixture();
    component().store(plan(f, List.of(f.first()), 0, "stored"));
    List<String> denied =
        List.of(
            "UPDATE region SET name = 'legacy' WHERE id = " + f.first(),
            "UPDATE region SET tenant_id = 123456789 WHERE id = " + f.first(),
            "DELETE FROM region WHERE id = " + f.second(),
            "INSERT INTO region(name,tenant_id,version_id) VALUES ('new',"
                + f.intake().localTenantKey()
                + ","
                + f.version().localVersionKey()
                + ")",
            "UPDATE world_design_aggregate_epoch SET draft_revision_epoch = 99 WHERE tenant_id = "
                + f.intake().localTenantKey(),
            "UPDATE zone SET name = 'unmanifested' WHERE tenant_id = "
                + f.intake().localTenantKey(),
            "TRUNCATE world_design_scope_epoch",
            "TRUNCATE world_region_draft_commit");
    for (String sql : denied) {
      assertThatThrownBy(() -> ownerTransaction().execute(status -> dsl.execute(sql)))
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(name(f.first())).startsWith("stored-");
    assertThat(name(f.second())).isEqualTo("original-second");
    assertThat(count("world_region_draft_execution_manifest")).isZero();
  }

  @Test
  void defaultPermissionVerifierFailsBeforeTransactionOrOwnerClaim() {
    Fixture f = fixture();
    WorldDraftRegionCommitService denied = new WorldDraftRegionCommitService(repository(), manager);
    assertThatThrownBy(() -> denied.store(plan(f, List.of(f.first()), 0, "denied")))
        .hasMessageContaining("permission is unavailable");
    assertThat(epochCount(f, "world_design_aggregate_epoch")).isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM world_design_publication_fence_owner WHERE local_tenant_key = ?",
                    f.intake().localTenantKey())
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void corruptCompleteResultIsRejectedOnExactReplayWithoutRewritingContent() {
    Fixture f = fixture();
    WorldDraftRegionCommitPlan plan = plan(f, List.of(f.first(), f.second()), 0, "retained");
    WorldDraftRegionCommitEvidence evidence = component().store(plan);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_region_draft_commit SET result_bytes = ? WHERE request_id = ?",
                    new byte[] {1},
                    plan.binding().requestId()))
        .hasStackTraceContaining("history is immutable");
    byte[] corrupt =
        new String(evidence.resultBytes(), StandardCharsets.UTF_8)
            .replace("\"epoch\":\"1\"", "\"epoch\":\"2\"")
            .getBytes(StandardCharsets.UTF_8);
    ownerTransaction()
        .execute(
            status -> {
              // Deliberate retained-row corruption fixture only; production mutation guards stay
              // intact.
              dsl.execute("SET LOCAL session_replication_role = 'replica'");
              try {
                dsl.execute(
                    "UPDATE world_region_draft_commit SET result_bytes = ? WHERE request_id = ?",
                    corrupt,
                    plan.binding().requestId());
              } finally {
                dsl.execute("SET LOCAL session_replication_role = 'origin'");
              }
              return null;
            });
    assertThatThrownBy(() -> component().store(plan))
        .hasMessageContaining("immutable result differs");
    assertEpochs(f, 1, 1);
    assertThat(name(f.first())).startsWith("retained-");
  }

  @Test
  void staleTransactionAndWrongPayloadExecutionManifestsCannotAuthorizeRows() {
    Fixture f = fixture();
    WorldDraftRegionCommitPlan plan = plan(f, List.of(f.first()), 0, "manifest");
    ownerTransaction()
        .execute(
            status -> {
              fence.lockOpen(f.owner());
              return null;
            });
    for (boolean staleTransaction : List.of(true, false)) {
      assertThatThrownBy(
              () ->
                  ownerTransaction()
                      .execute(
                          status -> {
                            // Test-only synthetic manifests under the schema-owner administration
                            // role. The production
                            // function creates exact manifests internally; arbitrary roles receive
                            // no table privilege.
                            dsl.execute(
                                "INSERT INTO world_region_draft_execution_manifest "
                                    + "SELECT txid_current() + ?, ?, 'region', r.id, 'UPDATE', ?, r.tenant_id, r.version_id, ?::jsonb, ?::jsonb, to_jsonb(r), to_jsonb(r) || '{\"name\":\"expected-only\"}'::jsonb FROM region r WHERE id = ?",
                                staleTransaction ? 1 : 0,
                                plan.binding().commitId(),
                                f.version().operationId(),
                                mapper.writeValueAsString(f.owner()),
                                plan.binding().canonicalJson(),
                                f.first());
                            return dsl.execute(
                                "UPDATE region SET name = ? WHERE id = ?",
                                staleTransaction ? "expected-only" : "wrong-payload",
                                f.first());
                          }))
          .hasStackTraceContaining("no exact transaction execution manifest");
    }
    assertThat(name(f.first())).isEqualTo("original-first");
    assertThat(count("world_region_draft_execution_manifest")).isZero();
  }

  @Test
  void v29UpgradePreservesRetainedV28FrozenSourceGraphFenceAndEpochHistory() throws Exception {
    Fixture f = fixture();
    WorldDraftRegionCommitEvidence prior =
        component().store(plan(f, List.of(f.first(), f.second()), 0, "retained-v28"));
    freeze(f);
    String schema = "region_commit_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var oldConfig =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("28"));
    oldConfig.load().migrate();
    try (Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      connection.setSchema(schema);
      DSLContext retained = DSL.using(connection, SQLDialect.POSTGRES);
      connection.setAutoCommit(false);
      retained.execute("SET LOCAL session_replication_role = 'replica'");
      Map<String, String> scopes = new LinkedHashMap<>();
      scopes.put("world_authored_source_tenant_association", "local_tenant_key");
      scopes.put("world_authored_source_tenant_key_reservation", "tenant_key");
      scopes.put("world_authored_source_intake", "local_tenant_key");
      scopes.put("world_authored_version_identity", "local_tenant_key");
      scopes.put("world_design_publication_fence_attempt", "local_tenant_key");
      scopes.put("world_design_publication_fence_owner", "local_tenant_key");
      for (String table :
          List.of(
              "region",
              "zone",
              "room",
              "room_exit",
              "generation_rule",
              "world_entity_spawn_binding",
              "world_design_aggregate_epoch",
              "world_design_scope_epoch")) {
        scopes.put(table, "tenant_id");
      }
      try {
        // Copy source-qualified synthetic retained rows into the schema while it still has V28.
        for (Map.Entry<String, String> scope : scopes.entrySet()) {
          String override =
              scope.getKey().equals("world_authored_version_identity")
                  ? " OVERRIDING SYSTEM VALUE"
                  : "";
          retained.execute(
              "INSERT INTO "
                  + scope.getKey()
                  + override
                  + " SELECT * FROM world_management_service."
                  + scope.getKey()
                  + " WHERE "
                  + scope.getValue()
                  + " = ?",
              f.intake().localTenantKey());
        }
        retained.execute(
            "INSERT INTO world_authored_graph_snapshot (snapshot_id,capture_request_digest,publication_fence,target_namespace,canonical_tenant_id,canonical_version_id,version_identity_operation_id,world_slug,game_design_version_id,local_version_key,local_tenant_key,owner_binding_schema_version,intake_operation_id,intake_request_id,intake_request_digest,source_operation_id,source_evidence_digest,intake_receipt_digest,publication_request_id,request_digest,version_state_epoch,publish_workflow_id,applied_commit_id,content_digest,digest_schema_version,supplied_owned_affected_tuples_json,owner_revision_evidence_json,owner_commit_proof_status,graph_bytes,graph_sha256) "
                + "SELECT ?,?,a.publication_fence,a.target_namespace,a.canonical_tenant_id,a.canonical_version_id,a.version_identity_operation_id,v.world_slug,a.game_design_version_id,a.version_id,a.local_tenant_key,a.owner_binding_schema_version,a.intake_operation_id,a.intake_request_id,a.intake_request_digest,a.source_operation_id,a.source_evidence_digest,a.intake_receipt_digest,a.publication_request_id,a.request_digest,a.version_state_epoch,a.publish_workflow_id,a.applied_commit_id,a.content_digest,a.digest_schema_version,?,?,'CAPTURED_UNVERIFIED',?,? "
                + "FROM world_design_publication_fence_attempt a JOIN world_authored_version_identity v ON v.operation_id = a.version_identity_operation_id",
            UUID.randomUUID(),
            "a".repeat(64),
            mapper.writeValueAsString(prior.binding().affectedUnits(Owner.WORLD_MANAGEMENT)),
            "[\"synthetic-v28-unverified-history\"]",
            prior.graphBytes(),
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(prior.graphBytes())));
        retained.execute("SET LOCAL session_replication_role = 'origin'");
        connection.commit();
      } catch (RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
      connection.setAutoCommit(true);
      scopes.put("world_authored_graph_snapshot", "local_tenant_key");
      Map<String, List<String>> before = retainedRows(retained, scopes.keySet().stream().toList());
      assertThat(
              retained
                  .resultQuery(
                      "SELECT owner_commit_proof_status FROM world_authored_graph_snapshot")
                  .fetchOne(0, String.class))
          .isEqualTo("CAPTURED_UNVERIFIED");
      assertThat(
              retained
                  .resultQuery(
                      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'world_region_draft_commit'",
                      schema)
                  .fetchOne(0, Long.class))
          .isZero();
      Flyway.configure()
          .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      assertThat(retainedRows(retained, scopes.keySet().stream().toList())).isEqualTo(before);
      SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
      DataSourceTransactionManager retainedManager = new DataSourceTransactionManager(dataSource);
      DriverManagerDataSource committedReadDataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      committedReadDataSource.setSchema(schema);
      // Match the normal repository proxy's NOT_SUPPORTED read boundary. A separate connection
      // reads this exact schema's committed intake while the owner transaction is suspended.
      TransactionInterceptor readTransactions = new TransactionInterceptor();
      readTransactions.setTransactionManager(retainedManager);
      readTransactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
      ProxyFactory intakeProxy =
          new ProxyFactory(
              new WorldAuthoredSourceIntakeRepository(
                  DSL.using(committedReadDataSource, SQLDialect.POSTGRES)));
      intakeProxy.setProxyTargetClass(true);
      intakeProxy.addAdvice(readTransactions);
      WorldAuthoredSourceIntakeRepository retainedIntake =
          (WorldAuthoredSourceIntakeRepository) intakeProxy.getProxy();
      assertThat(retainedIntake.read(NAMESPACE, f.intake().intakeRequestId()).orElseThrow())
          .isEqualTo(f.intake());
      WorldDesignPublicationFenceRepository retainedFence =
          new WorldDesignPublicationFenceRepository(retained, retainedIntake);
      WorldDraftRegionCommitService deniedFrozen =
          new WorldDraftRegionCommitService(
              new WorldDraftRegionCommitRepository(retained, retainedFence, mapper),
              retainedManager,
              plan -> {});
      assertThatThrownBy(
              () -> deniedFrozen.store(plan(f, List.of(f.first()), 1, "must-remain-frozen")))
          .hasMessageContaining("not open");
      assertThat(retainedRows(retained, scopes.keySet().stream().toList())).isEqualTo(before);
      assertThat(
              retained
                  .resultQuery("SELECT COUNT(*) FROM world_region_draft_commit")
                  .fetchOne(0, Long.class))
          .isZero();
    } finally {
      // This uniquely named schema is owned exclusively by this migration test.
      dsl.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private Map<String, List<String>> retainedRows(DSLContext retained, List<String> tables) {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table : tables) {
      rows.put(
          table,
          retained
              .resultQuery(
                  "SELECT to_jsonb(t)::TEXT FROM " + table + " t ORDER BY to_jsonb(t)::TEXT")
              .fetch(0, String.class));
    }
    return rows;
  }

  private void race(Fixture f, long epoch) throws Exception {
    CountDownLatch begin = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      WorldDraftRegionCommitPlan one = plan(f, List.of(f.first(), f.second()), epoch, "one");
      WorldDraftRegionCommitPlan two = plan(f, List.of(f.first(), f.second()), epoch, "two");
      Future<Boolean> first = executor.submit(() -> contend(one, begin));
      Future<Boolean> second = executor.submit(() -> contend(two, begin));
      begin.countDown();
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      begin.countDown();
      executor.shutdownNow();
    }
  }

  private boolean contend(WorldDraftRegionCommitPlan plan, CountDownLatch begin) {
    await(begin);
    try {
      component().store(plan);
      return true;
    } catch (RuntimeException exception) {
      assertThat(exception).hasStackTraceContaining("DRAFT_WRITE_CONFLICT");
      return false;
    }
  }

  private void assertChanged(
      WorldDraftRegionCommitPlan original,
      String base,
      List<DraftCommitBinding.RevisionPayload> revisions,
      List<AffectedUnit> units) {
    DraftCommitBinding prior = original.binding();
    WorldDraftRegionCommitPlan changed =
        WorldDraftRegionCommitPlan.create(
            DraftCommitBinding.create(
                prior.target(), prior.requestId(), prior.commitId(), base, revisions, units),
            original.ownerBinding());
    assertThatThrownBy(() -> component().store(changed)).hasMessageContaining("changed full input");
  }

  private WorldDraftRegionCommitPlan plan(Fixture f, List<Long> regions, long epoch, String name) {
    UUID request = UUID.randomUUID();
    UUID commit = UUID.randomUUID();
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> units = new ArrayList<>();
    for (long region : regions) {
      UUID revision = UUID.randomUUID();
      WorldDesignMutationRevision mutation =
          WorldDesignMutationRevision.newBuilder()
              .setLogicalRevisionId(revision.toString())
              .setCommitId(commit.toString())
              .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
              .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
              .setAggregateId(Long.toString(region))
              .setExpectedDraftRevisionEpoch(epoch)
              .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
              .setScopeId(Long.toString(region))
              .setExpectedDraftScopeRevisionEpoch(epoch)
              .setRegion(
                  RegionDesignMutation.newBuilder()
                      .setName(name + "-" + region)
                      .setShardId(7)
                      .setWeather("rain")
                      .setGenerationSeed(9_007_199_254_740_999L)
                      .setGeneratorType("synthetic")
                      .setGeneratorParams("{\"seed\":1}")
                      .setSpacingMultiplier(2.0))
              .build();
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisions.size()),
              revision,
              Owner.WORLD_MANAGEMENT,
              json(mutation)));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              "REGION",
              Long.toString(region),
              "AGGREGATE",
              Long.toString(region),
              Long.toString(epoch)));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              "REGION",
              Long.toString(region),
              "REGION_SUBTREE",
              Long.toString(region),
              Long.toString(epoch)));
    }
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.ENTITY_MANAGEMENT,
            "synthetic opaque other owner payload"));
    units.add(new AffectedUnit(Owner.ENTITY_MANAGEMENT, "ITEM", "17", "AGGREGATE", "17", "4"));
    AuthoredWorldSourceEvidence source = f.intake().source();
    DraftCommitBinding.TargetProof target =
        new DraftCommitBinding.TargetProof(
            f.owner().canonicalTenantId(),
            f.owner().canonicalVersionId(),
            GAME_DESIGN_VERSION,
            source.sourceGameTenantKey(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());
    return WorldDraftRegionCommitPlan.create(
        DraftCommitBinding.create(target, request, commit, "retained-base", revisions, units),
        f.owner());
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
                  // Only this setup disables V24. The tested writer always runs with production
                  // guards restored.
                  dsl.execute("SET LOCAL session_replication_role = 'replica'");
                  long first;
                  long second;
                  try {
                    first =
                        insertId(
                            "INSERT INTO region(name,tenant_id,version_id) VALUES ('original-first',?,?) RETURNING id",
                            intake.localTenantKey(),
                            version.localVersionKey());
                    second =
                        insertId(
                            "INSERT INTO region(name,tenant_id,version_id) VALUES ('original-second',?,?) RETURNING id",
                            intake.localTenantKey(),
                            version.localVersionKey());
                    long zone =
                        insertId(
                            "INSERT INTO zone(region_id,name,tenant_id,version_id) VALUES (?,'zone',?,?) RETURNING id",
                            first,
                            intake.localTenantKey(),
                            version.localVersionKey());
                    long room =
                        insertId(
                            "INSERT INTO room(zone_id,name,tenant_id,version_id) VALUES (?,'room',?,?) RETURNING id",
                            zone,
                            intake.localTenantKey(),
                            version.localVersionKey());
                    dsl.execute(
                        "INSERT INTO room_exit(tenant_id,version_id,from_room_id,to_room_id,direction,cost) VALUES (?,?,?,?,'NORTH',1)",
                        intake.localTenantKey(),
                        version.localVersionKey(),
                        room,
                        room);
                    dsl.execute(
                        "INSERT INTO generation_rule(tenant_id,version_id,name,scope_type,scope_id,value) VALUES (?,?,'rule','REGION_SUBTREE',?,'seeded')",
                        intake.localTenantKey(),
                        version.localVersionKey(),
                        Long.toString(first));
                    dsl.execute(
                        "INSERT INTO world_entity_spawn_binding(tenant_id,version_id,room_id,entity_template_type,entity_template_id,spawn_count,respawn_delay_seconds) VALUES (?,?,?,'NPC',1,1,0)",
                        intake.localTenantKey(),
                        version.localVersionKey(),
                        room);
                  } finally {
                    dsl.execute("SET LOCAL session_replication_role = 'origin'");
                  }
                  assertOrigin();
                  return new Fixture(intake, version, owner, first, second);
                }));
  }

  private void seedScope(Fixture f, long region, long epoch) {
    ownerTransaction()
        .execute(
            status -> {
              dsl.execute("SET LOCAL session_replication_role = 'replica'");
              try {
                dsl.execute(
                    "INSERT INTO world_design_scope_epoch(tenant_id,version_id,scope_type,scope_id,draft_scope_revision_epoch) VALUES (?,?,'REGION_SUBTREE',?,?)",
                    f.intake().localTenantKey(),
                    f.version().localVersionKey(),
                    Long.toString(region),
                    epoch);
              } finally {
                dsl.execute("SET LOCAL session_replication_role = 'origin'");
              }
              return null;
            });
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

  private WorldDraftRegionCommitService component() {
    return new WorldDraftRegionCommitService(
        repository(),
        manager,
        plan -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertOrigin();
          // Synthetic prerequisite only. No authenticated permission, APPLIED, or release evidence.
        });
  }

  private WorldDraftRegionCommitRepository repository() {
    return new WorldDraftRegionCommitRepository(dsl, fence, mapper);
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
    return transaction;
  }

  private void assertOrigin() {
    assertThat(
            dsl.resultQuery("SELECT current_setting('session_replication_role')")
                .fetchOne(0, String.class))
        .isEqualTo("origin");
  }

  private String name(long region) {
    return dsl.resultQuery("SELECT name FROM region WHERE id = ?", region)
        .fetchOne(0, String.class);
  }

  private long insertId(String sql, Object... values) {
    return Objects.requireNonNull(dsl.resultQuery(sql, values).fetchOne(0, Long.class));
  }

  private long count(String table) {
    return Objects.requireNonNull(
        dsl.resultQuery("SELECT COUNT(*) FROM " + table).fetchOne(0, Long.class));
  }

  private long epochCount(Fixture f, String table) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ? AND version_id = ?",
                f.intake().localTenantKey(),
                f.version().localVersionKey())
            .fetchOne(0, Long.class));
  }

  private void assertEpochs(Fixture f, long aggregate, long scope) {
    assertThat(
            dsl.resultQuery(
                    "SELECT draft_revision_epoch FROM world_design_aggregate_epoch WHERE tenant_id = ? AND version_id = ? ORDER BY aggregate_id",
                    f.intake().localTenantKey(),
                    f.version().localVersionKey())
                .fetch(0, Long.class))
        .containsExactly(aggregate, aggregate);
    assertThat(
            dsl.resultQuery(
                    "SELECT draft_scope_revision_epoch FROM world_design_scope_epoch WHERE tenant_id = ? AND version_id = ? ORDER BY scope_id",
                    f.intake().localTenantKey(),
                    f.version().localVersionKey())
                .fetch(0, Long.class))
        .containsExactly(scope, scope);
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
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Storage barrier timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private record Fixture(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldAuthoredVersionIdentityReceipt version,
      OwnerBinding owner,
      long first,
      long second) {}
}
