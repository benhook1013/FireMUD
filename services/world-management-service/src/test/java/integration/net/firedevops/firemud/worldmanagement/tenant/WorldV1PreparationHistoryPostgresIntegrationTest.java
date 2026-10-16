package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.HISTORY;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
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

/** Historical schema-1 owner-function materialization only; all foreign authority is stipulated. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldV1PreparationHistoryPostgresIntegrationTest {
  private static final String NAMESPACE = "world-v1-history";
  private static final long GAME_DESIGN_VERSION = 42L;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void originalSelectorNullV1PreparationSurvivesSelectorAndCurrentMigrations() throws Exception {
    String schema = "world_v1_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migration(source, schema, "1014").migrate();
    var sql = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var write = new TransactionTemplate(new DataSourceTransactionManager(source));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var intake = new WorldAuthoredSourceIntakeRepository(sql);
    Fixture f = fixture(sql, write, intake);
    var plan = historicalPlan(f);
    assertThat(plan.graph().freshGraphDeclaration()).isPresent();
    assertThat(plan.graph().freshGraphDeclaration().orElseThrow().inboundSourceClosure()).isEmpty();
    var mapper = new ObjectMapper();
    var fence = new WorldDesignPublicationFenceRepository(sql, intake);
    var topology = new WorldDraftTopologyCommitRepository(sql, fence, mapper);
    var app = application(plan);
    write.executeWithoutResult(
        ignored -> {
          assertThat(sql.fetchValue("SHOW session_replication_role", String.class))
              .isEqualTo("origin");
          var stored = topology.store(plan);
          var applied = WorldDraftGraphAppliedResult.create(app, stored.graphBytes());
          new WorldDraftStartLocationReceiptRepository(sql)
              .store(
                  applied.startLocationReceipt().orElseThrow(),
                  app.operation().accountBindingBytes());
          var operation = app.operation();
          sql.execute(
              "INSERT INTO world_draft_graph_application (operation_id,request_id,commit_id,authorization_fence_id,operation_bytes,account_binding_bytes,account_binding_digest,result_bytes,result_digest) VALUES (?,?,?,?,?,?,?,?,?)",
              operation.operationId(),
              operation.requestId(),
              operation.commitId(),
              operation.authorizationFenceId(),
              operation.canonicalBytes(),
              operation.accountBindingBytes(),
              operation.accountBindingDigest(),
              applied.canonicalBytes(),
              applied.digest());
        });
    var digestService =
        new net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImpl(
            new net.firedevops.firemud.worldmanagement.repository.RegionRepository(sql),
            new net.firedevops.firemud.worldmanagement.repository.ZoneRepository(sql),
            new net.firedevops.firemud.worldmanagement.repository.RoomRepository(sql),
            new net.firedevops.firemud.worldmanagement.repository.RoomExitRepository(sql),
            new net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository(sql),
            new net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository(
                sql));
    var owner = f.owner();
    String publication = "historical-freeze-" + UUID.randomUUID();
    var freeze =
        new WorldDesignPublicationFenceEvidence(
            NAMESPACE,
            owner.canonicalTenantId(),
            owner.canonicalVersionId(),
            owner.versionIdentityOperationId(),
            GAME_DESIGN_VERSION,
            owner.intakeRequestId(),
            owner.intakeOperationId(),
            owner.intakeRequestDigest(),
            owner.sourceOperationId(),
            owner.sourceEvidenceDigest(),
            owner.intakeReceiptDigest(),
            publication,
            "a".repeat(64),
            1L,
            net.firedevops.firemud.common.publication.PublicationDigestRequestBinding.full(
                    owner.canonicalTenantId().toString(),
                    Long.toString(GAME_DESIGN_VERSION),
                    publication)
                .derivedWorkflowIdentity());
    var attempt =
        Objects.requireNonNull(
            write.execute(
                ignored ->
                    fence.claimFreeze(
                        freeze,
                        () -> {
                          var digest =
                              digestService.getDraftDesignDigest(
                                  Long.toString(f.intake().localTenantKey()),
                                  Long.toString(f.version().localVersionKey()));
                          return new WorldDesignPublicationFenceEvidence.Checkpoint(
                              plan.binding().commitId().toString(), digest.contentDigest(), 3);
                        })));
    var tuples =
        plan.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var captureRequest =
        new WorldAuthoredGraphSnapshot.CaptureRequest(
            NAMESPACE,
            owner.canonicalTenantId(),
            owner.canonicalVersionId(),
            owner.intakeRequestId(),
            attempt.publicationFence(),
            publication,
            freeze.requestDigest(),
            1L,
            freeze.publishWorkflowId(),
            plan.binding().commitId().toString(),
            attempt.checkpoint().contentDigest(),
            3,
            tuples);
    var captures =
        new WorldCanonicalFrozenTopologyRepository(
            sql, new WorldAuthoredGraphSnapshotRepository(sql), topology, digestService);
    var frozen =
        Objects.requireNonNull(
            write.execute(
                ignored ->
                    captures.capture(
                        new WorldCanonicalFrozenTopology.Request(plan, captureRequest))));
    var input = preparationInput(sql, write, f, frozen);
    var historicalJson =
        (tools.jackson.databind.node.ObjectNode)
            mapper.readTree(WorldCanonicalInstancePreparationRepository.inputJson(input));
    historicalJson.put("schemaVersion", 1);
    ((tools.jackson.databind.node.ObjectNode) historicalJson.get("identity"))
        .remove(List.of("canonicalRealmId", "catalogRevision"));
    ((tools.jackson.databind.node.ObjectNode) historicalJson.get("gameSessionReadEvidence"))
        .remove(List.of("canonicalRealmId", "catalogRevision"));
    String exactInput = mapper.writeValueAsString(historicalJson);
    assertThat(exactInput).doesNotContain("worldStartLocationEvidence");
    var original = prepare(sql, write, exactInput);
    assertThat(original).containsKeys("world_instance_id", "private_game_instance_key");
    assertThat(sql.fetchSingle("SELECT status FROM world_instance").get(0, String.class))
        .isEqualTo("PREPARING");
    var queries = originalQueries(sql);
    var before = rows(sql, queries);
    var history1014 = history(sql);
    assertThat(prepare(sql, write, exactInput)).isEqualTo(original);
    assertThat(rows(sql, queries)).isEqualTo(before);

    migration(source, schema, "1015").migrate();
    assertThat(rows(sql, queries)).isEqualTo(before);
    assertThat(history(sql)).containsAll(history1014);
    assertThat(prepare(sql, write, exactInput)).isEqualTo(original);
    assertThat(rows(sql, queries)).isEqualTo(before);
    assertNoSelector(sql);
    var history1015 = history(sql);

    var current = migration(source, schema, null);
    current.migrate();
    current.validate();
    assertThat(rows(sql, queries)).isEqualTo(before);
    assertThat(history(sql)).containsAll(history1015);
    assertNoSelector(sql);
    assertThat(
            sql.fetchSingle("SELECT input_json FROM world_canonical_instance_preparation")
                .get(0, String.class))
        .isEqualTo(exactInput);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "UPDATE world_canonical_instance_preparation SET input_json = ?",
                    exactInput + " "))
        .isInstanceOf(DataAccessException.class);
    assertThat(rows(sql, queries)).isEqualTo(before);
    assertThat(sql.fetchCount(DSL.table(DSL.name("world_canonical_instance_execution")))).isZero();
    assertThat(
            sql.fetchSingle(
                    "SELECT canonical_realm_id IS NULL AND catalog_revision IS NULL FROM world_instance")
                .get(0, Boolean.class))
        .isTrue();
  }

  private static Map<String, Object> prepare(
      DSLContext sql, TransactionTemplate write, String input) throws Exception {
    String digest =
        "sha256:"
            + HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(input.getBytes(StandardCharsets.UTF_8)));
    var parsed = new ObjectMapper().readTree(input);
    UUID versionIdentity =
        UUID.fromString(parsed.get("versionIdentity").get("operationId").textValue());
    UUID instance =
        UUID.fromString(parsed.get("identity").get("canonicalGameInstanceId").textValue());
    return Objects.requireNonNull(
        write.execute(
            ignored -> {
              var row =
                  sql.fetchSingle(
                      "SELECT * FROM world_prepare_canonical_instance(?,?)", input, digest);
              long allocatedWorldInstanceId =
                  Objects.requireNonNull(row.get("world_instance_id", Long.class));
              // The historical owner caller retains its exact schema-1 association before the
              // deferred
              // preparation FK is checked at this same physical transaction's commit.
              String associationSelect =
                  "SELECT 1::SMALLINT AS schema_version,wi.canonical_game_instance_id,"
                      + "wi.canonical_target_namespace,wi.canonical_tenant_id,wi.canonical_world_slug,"
                      + "wi.playable_state_namespace_id,wi.playable_state_scope,wi.public_production,"
                      + "wi.playtest_lifecycle_id,wi.playtest_state_generation,"
                      + "wi.control_plane_request_id,wi.id AS world_instance_id,"
                      + "b.binding_operation_id AS canonical_launch_binding_operation_id,"
                      + "v.operation_id AS version_identity_operation_id,b.canonical_version_id,"
                      + "wi.tenant_id AS local_tenant_key,wi.game_instance_id AS private_game_instance_key,"
                      + "wi.game_template_id,wi.launch_descriptor_id,wi.version_id AS local_version_key,"
                      + "wi.script_patch_version,wi.runtime_flags_json,wi.generation_config_revision,"
                      + "wi.release_bundle_id,wi.published_release_bundle_ref,wi.version_state_epoch,"
                      + "wi.remap_set_id,b.intake_operation_id,b.intake_request_id,b.source_operation_id,"
                      + "b.source_evidence_digest,s.request_digest AS intake_request_digest,"
                      + "b.intake_receipt_digest,b.descriptor_request_digest,b.descriptor_result_digest,"
                      + "b.release_attestation_digest FROM world_instance wi "
                      + "JOIN world_complete_launch_binding b "
                      + "ON b.binding_operation_id=wi.canonical_launch_binding_operation_id "
                      + "JOIN world_authored_version_identity v ON v.operation_id=? "
                      + "JOIN world_authored_source_intake s ON s.operation_id=b.intake_operation_id "
                      + "WHERE wi.id=?";
              var expectedAssociation =
                  Objects.requireNonNull(
                      sql.fetchOne(associationSelect, versionIdentity, allocatedWorldInstanceId),
                      "V34 historical fixture could not resolve its exact association fields");
              org.jooq.Record retainedAssociation =
                  sql.fetchOne(
                      "SELECT * FROM world_canonical_instance_association "
                          + "WHERE canonical_game_instance_id = ?",
                      instance);
              if (retainedAssociation == null) {
                int inserted =
                    sql.execute(
                        "INSERT INTO world_canonical_instance_association ("
                            + "schema_version,canonical_game_instance_id,canonical_target_namespace,"
                            + "canonical_tenant_id,canonical_world_slug,playable_state_namespace_id,"
                            + "playable_state_scope,public_production,playtest_lifecycle_id,"
                            + "playtest_state_generation,control_plane_request_id,world_instance_id,"
                            + "canonical_launch_binding_operation_id,version_identity_operation_id,"
                            + "canonical_version_id,local_tenant_key,private_game_instance_key,"
                            + "game_template_id,launch_descriptor_id,local_version_key,script_patch_version,"
                            + "runtime_flags_json,generation_config_revision,release_bundle_id,"
                            + "published_release_bundle_ref,version_state_epoch,remap_set_id,"
                            + "intake_operation_id,intake_request_id,source_operation_id,source_evidence_digest,"
                            + "intake_request_digest,intake_receipt_digest,descriptor_request_digest,"
                            + "descriptor_result_digest,release_attestation_digest) "
                            + associationSelect,
                        versionIdentity,
                        allocatedWorldInstanceId);
                if (inserted != 1) {
                  throw new IllegalStateException(
                      "V34 historical fixture failed to retain its schema-1 association");
                }
                retainedAssociation =
                    Objects.requireNonNull(
                        sql.fetchOne(
                            "SELECT * FROM world_canonical_instance_association "
                                + "WHERE canonical_game_instance_id = ?",
                            instance),
                        "V34 historical fixture association was not retained");
              }
              if (!java.util.Arrays.equals(
                  expectedAssociation.intoArray(), retainedAssociation.intoArray())) {
                throw new IllegalStateException(
                    "V34 historical fixture retry differs from its full retained schema-1 association");
              }
              return row.intoMap();
            }));
  }

  private static void assertNoSelector(DSLContext sql) {
    assertThat(sql.fetchCount(DSL.table(DSL.name("world_canonical_preparation_start_location"))))
        .isZero();
    assertThat(
            sql.fetchSingle(
                    "SELECT to_jsonb(p)->>'input_json' FROM world_canonical_instance_preparation p")
                .get(0, String.class))
        .doesNotContain("worldStartLocationEvidence");
  }

  private static WorldCanonicalInstancePreparation.Input preparationInput(
      DSLContext sql, TransactionTemplate write, Fixture f, WorldCanonicalFrozenTopology frozen) {
    var s = f.intake().source();
    String control = "historical-prepare-" + UUID.randomUUID();
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            new AuthoredWorldLaunchDescriptorEvidence.Request(
                NAMESPACE,
                control,
                s.canonicalTenantId(),
                s.worldSlug(),
                s.operationId(),
                s.evidenceDigest(),
                71L,
                false,
                null,
                false,
                null,
                false,
                null,
                false,
                null),
            "ld-" + UUID.randomUUID(),
            GAME_DESIGN_VERSION,
            false,
            null,
            "{}",
            "generation-free",
            2L,
            97L,
            "release-" + frozen.request().freeze().publicationRequestId(),
            false,
            null);
    var freeze = frozen.request().freeze();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(GAME_DESIGN_VERSION),
                        false,
                        null,
                        freeze.appliedCommitId(),
                        "WORLD_MANAGEMENT".equals(owner) ? freeze.contentDigest() : "a".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "b".repeat(64) : null))
            .toList();
    // Complete legacy release/GS values are constructed test evidence, never live producers.
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            s.canonicalTenantId(),
            f.version().canonicalVersionId(),
            s.worldSlug(),
            s.operationId(),
            s.evidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            2L,
            freeze.publishWorkflowId(),
            freeze.appliedCommitId(),
            participants,
            "sha256:" + "c".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of("LOOK"),
            descriptor.generationConfigRevision());
    var launch =
        Objects.requireNonNull(
            write.execute(
                ignored ->
                    new WorldCompleteLaunchBindingRepository(sql)
                        .acceptFresh(
                            NAMESPACE,
                            f.intake(),
                            new CompleteLaunchBindingEvidence(descriptor, release))));
    UUID read = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    var request =
        new WorldCanonicalInstanceAssociation.GameSessionReadRequest(
            read,
            NAMESPACE,
            s.canonicalTenantId(),
            s.worldSlug(),
            instance,
            control,
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var evidence =
        new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
            read,
            NAMESPACE,
            s.canonicalTenantId(),
            s.worldSlug(),
            instance,
            control,
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest(),
            UUID.randomUUID(),
            "SHARED",
            true,
            UUID.randomUUID(),
            1L,
            "PREPARING",
            1L,
            descriptor,
            release);
    return new WorldCanonicalInstancePreparation.Input(
        request,
        evidence,
        UUID.randomUUID(),
        1L,
        launch,
        f.version(),
        WorldCanonicalInstanceTopologyPlan.create(frozen));
  }

  private static Map<String, String> originalQueries(DSLContext sql) {
    var result = new LinkedHashMap<String, String>();
    for (String table :
        List.of(
            "world_authored_source_intake",
            "world_authored_version_identity",
            "world_complete_launch_binding",
            "world_topology_draft_commit",
            "world_draft_graph_application",
            "world_draft_start_location_receipt",
            "world_canonical_frozen_topology",
            "world_instance",
            "world_canonical_instance_association",
            "region_instance",
            "zone_instance",
            "room_instance",
            "room_instance_exit",
            "world_canonical_instance_topology_identity",
            "world_canonical_instance_preparation")) {
      var columns =
          sql.fetch(
                  "SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=? ORDER BY ordinal_position",
                  table)
              .getValues(0, String.class);
      assertThat(columns).isNotEmpty();
      String fields =
          columns.stream()
              .map(column -> "r." + DSL.name(column))
              .collect(java.util.stream.Collectors.joining(","));
      result.put(
          table,
          "SELECT to_jsonb(projected)::text FROM (SELECT "
              + fields
              + ",r.xmin::text AS xmin FROM "
              + table
              + " r) projected ORDER BY to_jsonb(projected)::text");
    }
    return result;
  }

  private static Map<String, List<String>> rows(DSLContext sql, Map<String, String> queries) {
    var result = new LinkedHashMap<String, List<String>>();
    queries.forEach(
        (table, query) -> result.put(table, sql.fetch(query).getValues(0, String.class)));
    return result;
  }

  private static List<Map<String, Object>> history(DSLContext sql) {
    return sql.fetch("SELECT * FROM " + HISTORY + " ORDER BY installed_rank").intoMaps();
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
              return new Fixture(intake, owner, version);
            }));
  }

  private record Fixture(
      WorldAuthoredSourceIntakeReceipt intake,
      OwnerBinding owner,
      WorldAuthoredVersionIdentityReceipt version) {}

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
    values.removeIf(value -> value.hasGenerationRule() || value.hasWorldEntitySpawnBinding());
    var declaration =
        net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration.newBuilder()
            .setTenantId(f.owner().canonicalTenantId().toString())
            .setVersionId(f.owner().canonicalVersionId().toString())
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                    .setTenantId(f.owner().canonicalTenantId().toString())
                    .setVersionId(f.owner().canonicalVersionId().toString())
                    .setRoomTemplateId(logical.toString()));
    for (var family :
        List.of(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)) {
      declaration.addFamilyCounts(
          net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount.newBuilder()
              .setFamily(family)
              .setCount(
                  Math.toIntExact(
                      values.stream()
                          .filter(value -> value.getAggregateType() == family)
                          .count())));
    }
    values.set(0, values.getFirst().toBuilder().setFreshGraphDeclaration(declaration).build());
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
}
