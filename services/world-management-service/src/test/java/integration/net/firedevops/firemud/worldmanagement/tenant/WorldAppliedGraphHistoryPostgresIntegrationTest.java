package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.HISTORY;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.test.TestContainerImages;
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
import tools.jackson.databind.ObjectMapper;

/** Historical APPLIED storage only; Account, Game Design and Entity authority are stipulated. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldAppliedGraphHistoryPostgresIntegrationTest {
  private static final String NAMESPACE = "world-applied-history";
  private static final long GAME_DESIGN_VERSION = 42L;
  private static final List<String> TABLES =
      List.of(
          "world_authored_source_tenant_key_reservation",
          "world_authored_source_tenant_association",
          "world_authored_source_intake",
          "world_authored_version_identity",
          "world_design_publication_fence_owner",
          "world_topology_draft_commit",
          "world_authored_topology_identity",
          "world_design_aggregate_epoch",
          "world_design_scope_epoch",
          "region",
          "zone",
          "room",
          "room_exit",
          "generation_rule",
          "world_entity_spawn_binding",
          "world_draft_graph_application",
          "world_draft_graph_terminal_identity");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void originalV1012AppliedBytesAndAbsentSelectorSurviveReceiptAndCurrentMigrations()
      throws Exception {
    String schema = "world_applied_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migration(source, schema, "1012").migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(source);
    var write = new TransactionTemplate(manager);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var intake = new WorldAuthoredSourceIntakeRepository(dsl);
    Fixture fixture = fixture(dsl, write, intake);
    WorldDraftTopologyCommitPlan plan = historicalPlan(fixture);
    assertThat(plan.graph().freshGraphDeclaration()).isEmpty();
    WorldDraftGraphApplication application = application(plan);
    var fence = new WorldDesignPublicationFenceRepository(dsl, intake);
    var mapper = new ObjectMapper();
    var topology = new WorldDraftTopologyCommitRepository(dsl, fence, mapper);

    // Execute the actual V1012 guarded topology/APPLIED insert in one physical owner transaction.
    // The complete Account binding is test evidence; no protected Account producer is claimed.
    WorldDraftGraphAppliedResult original =
        Objects.requireNonNull(
            write.execute(
                ignored -> {
                  assertThat(
                          Objects.requireNonNull(dsl.fetchOne("SHOW session_replication_role"))
                              .get(0, String.class))
                      .isEqualTo("origin");
                  var graph = topology.store(plan);
                  var result = WorldDraftGraphAppliedResult.create(application, graph.graphBytes());
                  var operation = application.operation();
                  dsl.execute(
                      "INSERT INTO world_draft_graph_application (operation_id,request_id,commit_id,authorization_fence_id,operation_bytes,account_binding_bytes,account_binding_digest,result_bytes,result_digest) VALUES (?,?,?,?,?,?,?,?,?)",
                      operation.operationId(),
                      operation.requestId(),
                      operation.commitId(),
                      operation.authorizationFenceId(),
                      operation.canonicalBytes(),
                      operation.accountBindingBytes(),
                      operation.accountBindingDigest(),
                      result.canonicalBytes(),
                      result.digest());
                  return result;
                }));
    assertThat(original.resultIdentity()).contains("/v1:");
    assertThat(original.startLocationReceipt()).isEmpty();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT a.application_transaction_id = c.application_transaction_id AS same_transaction FROM world_draft_graph_application a JOIN world_topology_draft_commit c USING (request_id,commit_id)"))
                .get(0, Boolean.class))
        .isTrue();
    Map<String, String> queries = originalQueries(dsl);
    Map<String, List<String>> rows = rows(dsl, queries);
    assertThat(rows.values()).allSatisfy(values -> assertThat(values).isNotEmpty());
    int rank1012 = historyRank(dsl);
    List<String> history1012 = history(dsl, rank1012);

    migration(source, schema, "1014").migrate();
    assertThat(history(dsl, rank1012)).isEqualTo(history1012);
    assertThat(rows(dsl, queries)).isEqualTo(rows);
    assertRetained(dsl, fence, mapper, manager, application, original);
    assertThat(rows(dsl, queries)).isEqualTo(rows);
    int rank1014 = historyRank(dsl);
    List<String> history1014 = history(dsl, rank1014);

    var current = migration(source, schema, null);
    current.migrate();
    assertThat(current.info().pending()).isEmpty();
    assertThat(historyRank(dsl)).isGreaterThan(rank1014);
    assertThat(history(dsl, rank1014)).isEqualTo(history1014);
    assertThat(rows(dsl, queries)).isEqualTo(rows);
    assertRetained(dsl, fence, mapper, manager, application, original);
    assertThatThrownBy(
            () -> WorldDraftStartLocationReceipt.create(application, original.graphBytes()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires an original graph declaration");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_draft_graph_application SET result_bytes = ? WHERE operation_id = ?",
                    new byte[] {99},
                    application.operation().operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM world_draft_graph_terminal_identity WHERE operation_id = ?",
                    application.operation().operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThat(rows(dsl, queries)).isEqualTo(rows);
  }

  private static void assertRetained(
      DSLContext dsl,
      WorldDesignPublicationFenceRepository fence,
      ObjectMapper mapper,
      DataSourceTransactionManager manager,
      WorldDraftGraphApplication application,
      WorldDraftGraphAppliedResult original) {
    var repository = new WorldDraftGraphApplicationRepository(dsl, fence, mapper);
    var exact = repository.readCommitted(application).orElseThrow();
    assertThat(exact.canonicalBytes()).containsExactly(original.canonicalBytes());
    assertThat(exact.graphBytes()).containsExactly(original.graphBytes());
    assertThat(exact.startLocationReceipt()).isEmpty();
    assertThat(
            repository
                .readCommitted(NAMESPACE, application.operation().accountBindingBytes())
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(original.canonicalBytes());
    // Default-denied service can recover an exact retained result without consulting a new
    // verifier.
    var retry = new WorldDraftGraphApplicationService(repository, manager).apply(application);
    assertThat(retry.canonicalBytes()).containsExactly(original.canonicalBytes());
    assertThat(retry.startLocationReceipt()).isEmpty();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT count(*) FROM world_draft_start_location_receipt"))
                .get(0, Long.class))
        .isZero();
  }

  private static Fixture fixture(
      DSLContext dsl,
      TransactionTemplate write,
      WorldAuthoredSourceIntakeRepository intakeRepository) {
    UUID tenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String slug = "world-" + suffix;
    String tenantSlug = "tenant-" + suffix;
    String display = "Stipulated historical world";
    long row = 910071;
    String sourceTenant = "source-" + suffix.substring(0, 16);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, tenantSlug, slug, display);
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            operation,
            requestDigest,
            tenant,
            tenantSlug,
            slug,
            display,
            row,
            sourceTenant,
            "NEW_GAME_ROW",
            AuthoredWorldSourceDigest.evidenceDigest(
                NAMESPACE,
                registration,
                operation,
                requestDigest,
                tenant,
                tenantSlug,
                slug,
                display,
                row,
                sourceTenant,
                "NEW_GAME_ROW"));
    UUID request = UUID.randomUUID();
    var intake =
        Objects.requireNonNull(
            write.execute(ignored -> intakeRepository.acceptFresh(NAMESPACE, request, source)));
    return Objects.requireNonNull(
        write.execute(
            ignored -> {
              var stateRequest =
                  new AuthoredWorldVersionStateEvidence.Request(
                      1,
                      NAMESPACE,
                      UUID.randomUUID(),
                      tenant,
                      slug,
                      operation,
                      source.evidenceDigest(),
                      GAME_DESIGN_VERSION);
              var version =
                  new WorldAuthoredVersionIdentityRepository(dsl)
                      .acceptFresh(
                          intake,
                          AuthoredWorldVersionStateEvidence.create(
                              stateRequest,
                              source,
                              UUID.randomUUID(),
                              VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                              1));
              var owner =
                  new OwnerBinding(
                      NAMESPACE,
                      tenant,
                      version.canonicalVersionId(),
                      version.operationId(),
                      GAME_DESIGN_VERSION,
                      request,
                      intake.operationId(),
                      intake.requestDigest(),
                      operation,
                      source.evidenceDigest(),
                      intake.receiptDigest());
              return new Fixture(intake, owner);
            }));
  }

  private record Fixture(WorldAuthoredSourceIntakeReceipt intake, OwnerBinding owner) {}

  private static WorldDraftTopologyCommitPlan historicalPlan(Fixture f) throws Exception {
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
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "1",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "synthetic Game Design control-plane input"));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "VERSION",
            f.owner().canonicalVersionId().toString(),
            "AGGREGATE",
            f.owner().canonicalVersionId().toString(),
            "0"));
    // Original input order deliberately places children before their parents.
    for (var m : values.reversed()) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(m.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().omittingInsignificantWhitespace().print(m)));
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

  private static WorldDraftGraphApplication application(WorldDraftTopologyCommitPlan p) {
    var account =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            p.binding().requestId(),
            p.binding().commitId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            p.binding().target().canonicalTenantId(),
            p.binding().target().canonicalVersionId(),
            p.binding().baseCommitId(),
            "0",
            p.binding().canonicalBytes(),
            p.binding().canonicalBytes(),
            p.binding().digest(),
            List.of(
                new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                    .SourceEvidence(
                    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                        .SourceKind.TENANT,
                    p.binding().target().canonicalTenantId().toString(),
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1, 2, 3})));
    return withAccount(p, account);
  }

  private static WorldDraftGraphApplication withAccount(
      WorldDraftTopologyCommitPlan p,
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding account) {
    var operation =
        new WorldDraftTerminalOperation(
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            account.tenantId(),
            account.versionId(),
            p.binding(),
            p.ownerBinding(),
            account.canonicalBytes());
    return new WorldDraftGraphApplication(operation, p);
  }

  private static WorldDesignMutationRevision.Builder mutation(
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

  private static Map<String, String> originalQueries(DSLContext dsl) {
    Map<String, String> queries = new LinkedHashMap<>();
    for (String table : TABLES) {
      List<String> columns =
          dsl.fetch(
                  "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position",
                  table)
              .getValues(0, String.class);
      assertThat(columns).isNotEmpty();
      String projection =
          String.join(",", columns.stream().map(name -> "\"" + name + "\"").toList());
      queries.put(
          table,
          "SELECT to_jsonb(original)::text FROM (SELECT "
              + projection
              + ",xmin::text AS row_xmin FROM "
              + table
              + ") original ORDER BY to_jsonb(original)::text");
    }
    return queries;
  }

  private static Map<String, List<String>> rows(DSLContext dsl, Map<String, String> queries) {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    queries.forEach((table, query) -> rows.put(table, dsl.fetch(query).getValues(0, String.class)));
    return rows;
  }

  private static int historyRank(DSLContext dsl) {
    return Objects.requireNonNull(dsl.fetchOne("SELECT max(installed_rank) FROM " + HISTORY))
        .get(0, Integer.class);
  }

  private static List<String> history(DSLContext dsl, int rank) {
    return dsl.fetch(
            "SELECT to_jsonb(h)::text FROM "
                + HISTORY
                + " h WHERE installed_rank <= ? ORDER BY installed_rank",
            rank)
        .getValues(0, String.class);
  }
}
