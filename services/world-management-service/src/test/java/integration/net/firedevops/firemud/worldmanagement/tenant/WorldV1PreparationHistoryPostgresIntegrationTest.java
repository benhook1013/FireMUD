package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.HISTORY;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.JsonFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Participant;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.ReleaseContent;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Historical schema-1 associations for V1/V2 preparations; foreign authority is stipulated. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldV1PreparationHistoryPostgresIntegrationTest {
  private static final String NAMESPACE = "world-v1-history";
  private static final long GAME_DESIGN_VERSION = 42L;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @ParameterizedTest
  @CsvSource({"1,false", "2,false", "2,true"})
  void originalLegacyPreparationRetainsExactIdentityAcrossCurrentMigrations(
      int inputSchema, boolean retainLegacyActivation) throws Exception {
    String schema = "world_v1_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migration(source, schema, inputSchema == 1 ? "1014" : "1018").migrate();
    var sql = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var write = new TransactionTemplate(new DataSourceTransactionManager(source));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var mapper = new ObjectMapper();
    var fence =
        new WorldDesignPublicationFenceRepository(
            sql, new WorldAuthoredSourceIntakeRepository(sql));
    var historyFixture = frozenHistory(sql, write);
    var f = historyFixture.fixture();
    var frozen = historyFixture.frozen();
    var captures = historyFixture.captures();
    var captureRequest = frozen.request().freeze();
    var selection = historyFixture.selection();
    var selector =
        inputSchema == 1
            ? null
            : publishedEvidence(
                new WorldPublishedStartLocationRepository(
                        sql, captures, new WorldDraftGraphApplicationRepository(sql, fence, mapper))
                    .readCommitted(captureRequest)
                    .orElseThrow());
    var input = preparationInput(sql, write, f, frozen, selector);
    if (inputSchema == 2) retainHistoricalPublicationTerminal(sql, write, input, selection);
    var historicalJson =
        (tools.jackson.databind.node.ObjectNode)
            mapper.readTree(WorldCanonicalInstancePreparationRepository.inputJson(input));
    historicalJson.put("schemaVersion", inputSchema);
    ((tools.jackson.databind.node.ObjectNode) historicalJson.get("identity"))
        .remove(List.of("canonicalRealmId", "catalogRevision"));
    ((tools.jackson.databind.node.ObjectNode) historicalJson.get("gameSessionReadEvidence"))
        .remove(List.of("canonicalRealmId", "catalogRevision"));
    String exactInput = mapper.writeValueAsString(historicalJson);
    if (inputSchema == 1) assertThat(exactInput).doesNotContain("worldStartLocationEvidence");
    else assertThat(exactInput).contains("worldStartLocationEvidenceBase64");
    var original = prepare(sql, write, exactInput);
    assertThat(original).containsKeys("world_instance_id", "private_game_instance_key");
    assertThat(sql.fetchSingle("SELECT status FROM world_instance").get(0, String.class))
        .isEqualTo("PREPARING");
    var queries = originalQueries(sql, inputSchema == 2);
    var preparationBefore = rows(sql, queries);
    assertThat(prepare(sql, write, exactInput)).isEqualTo(original);
    assertThat(rows(sql, queries)).isEqualTo(preparationBefore);
    net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Result legacy = null;
    Map<String, Object> activationGuardBefore = null;
    if (retainLegacyActivation) {
      assertThat(inputSchema).isEqualTo(2);
      var priorHistory = history(sql);
      migration(source, schema, "1025").migrate();
      assertThat(rows(sql, queries)).isEqualTo(preparationBefore);
      assertThat(history(sql)).containsAll(priorHistory);
      legacy = retainEvolvedLegacyActivation(sql, write, input, selector);
      queries.putAll(
          originalQueries(sql, List.of("world_canonical_instance_activation_operation")));
      activationGuardBefore = activationGuard(sql);
    }
    var before = rows(sql, queries);
    var historyBeforeUpgrade = history(sql);

    migration(source, schema, inputSchema == 1 ? "1015" : "1037").migrate();
    assertThat(rows(sql, queries)).isEqualTo(before);
    assertThat(history(sql)).containsAll(historyBeforeUpgrade);
    if (inputSchema == 1) {
      assertThat(prepare(sql, write, exactInput)).isEqualTo(original);
      // Read the original frozen graph's actual selector after V1015. Supplying it now cannot
      // reinterpret the already retained selector-null schema-1 preparation as schema 2.
      var originalSelector =
          publishedEvidence(
              new WorldPublishedStartLocationRepository(
                      sql, captures, new WorldDraftGraphApplicationRepository(sql, fence, mapper))
                  .readCommitted(captureRequest)
                  .orElseThrow());
      var changed = (tools.jackson.databind.node.ObjectNode) mapper.readTree(exactInput);
      changed.put("schemaVersion", 2);
      changed.put(
          "worldStartLocationEvidenceBase64",
          java.util.Base64.getEncoder().encodeToString(originalSelector.canonicalBytes()));
      String promoted = mapper.writeValueAsString(changed);
      assertThatThrownBy(
              () ->
                  write.executeWithoutResult(
                      ignored ->
                          sql.fetchSingle(
                              "SELECT * FROM world_prepare_canonical_instance(?,?)",
                              promoted,
                              DraftAuthorizationFenceBinding.digest(
                                  promoted.getBytes(StandardCharsets.UTF_8)))))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining(
              "V2 preparation requires original complete World selector evidence");
      assertThat(rows(sql, queries)).isEqualTo(before);
      assertSelector(sql, null);
    }
    assertThat(rows(sql, queries)).isEqualTo(before);
    assertSelector(sql, selector);
    var historyAfterUpgrade = history(sql);
    if (inputSchema == 2) {
      migration(source, schema, "1039").migrate();
      assertThat(rows(sql, queries)).isEqualTo(before);
      assertThat(history(sql)).containsAll(historyAfterUpgrade);
      assertSelector(sql, selector);
      historyAfterUpgrade = history(sql);
      if (retainLegacyActivation) assertThat(activationGuard(sql)).isEqualTo(activationGuardBefore);
    }

    var current = migration(source, schema, null);
    current.migrate();
    current.validate();
    assertThat(rows(sql, queries)).isEqualTo(before);
    assertThat(history(sql)).containsAll(historyAfterUpgrade);
    assertSelector(sql, selector);
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
    if (retainLegacyActivation) {
      assertThat(activationGuard(sql)).isEqualTo(activationGuardBefore);
      var manager = new DataSourceTransactionManager(source);
      var association =
          new WorldCanonicalInstanceAssociationRepository(
              sql,
              new WorldCompleteLaunchBindingRepository(sql),
              new WorldAuthoredSourceIntakeRepository(sql),
              new WorldAuthoredVersionIdentityRepository(sql));
      var repository =
          new WorldCanonicalInstanceActivationRepository(
              sql,
              manager,
              new WorldCanonicalInstanceLifecycleReadRepository(sql, manager, association));
      var service = new WorldCanonicalInstanceActivationService(repository);
      var request = Objects.requireNonNull(legacy).request();
      assertThat(repository.readResult(request).orElseThrow().canonicalBytes())
          .containsExactly(legacy.canonicalBytes());
      assertThat(service.activate(request).canonicalBytes())
          .containsExactly(legacy.canonicalBytes());
      var fresh =
          new net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Request(
              UUID.randomUUID(), request.preparing());
      assertThatThrownBy(() -> service.activate(fresh))
          .isInstanceOf(WorldCanonicalInstanceActivationService.ActivationDeniedException.class);
      assertThat(sql.fetchCount(DSL.table("world_canonical_instance_activation_fence"))).isZero();
      assertThat(sql.fetchCount(DSL.table("world_canonical_activation_execution_manifest")))
          .isZero();
      assertThatThrownBy(
              () ->
                  sql.execute(
                      "UPDATE world_canonical_instance_activation_operation SET result_bytes=result_bytes"))
          .isInstanceOf(DataAccessException.class);
      assertThat(rows(sql, queries)).isEqualTo(before);
    }
  }

  private static Map<String, Object> activationGuard(DSLContext sql) {
    return sql.fetchSingle(
            "SELECT p.oid::bigint AS function_oid,p.proacl::text AS function_acl "
                + "FROM pg_proc p WHERE p.oid=to_regprocedure "
                + "(current_schema() || '.world_validate_canonical_activation_operation()')")
        .intoMap();
  }

  /** Original V1025 evolved twelve-field carrier; no Account protection is invented. */
  private static net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Result
      retainEvolvedLegacyActivation(
          DSLContext sql,
          TransactionTemplate write,
          WorldCanonicalInstancePreparation.Input input,
          WorldPublishedStartLocationEvidence selector) {
    var owner = input.gameSessionReadEvidence();
    var descriptor = input.completeLaunchBinding().descriptor();
    var release = input.completeLaunchBinding().evidence().releaseAttestation();
    var request =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request(
            1,
            UUID.randomUUID(),
            owner.targetNamespace(),
            owner.canonicalTenantId(),
            owner.worldSlug(),
            owner.canonicalGameInstanceId(),
            owner.playableStateNamespaceId(),
            owner.playableStateScope(),
            owner.publicProduction(),
            owner.controlPlaneRequestId(),
            release.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var retained =
        sql.fetchSingle(
            "SELECT wi.id,wi.status,wi.lifecycle_epoch,wi.row_version,p.capture_id,p.graph_sha256,p.input_digest,"
                + "s.runtime_room_instance_id FROM world_instance wi "
                + "JOIN world_canonical_instance_preparation p USING(canonical_game_instance_id) "
                + "JOIN world_canonical_preparation_start_location s USING(canonical_game_instance_id)");
    assertThat(retained.get("status", String.class)).isEqualTo("PREPARING");
    var regions = new LinkedHashMap<UUID, UUID>();
    for (var region :
        sql.fetch(
            "SELECT m.runtime_identity,r.operational_region_id FROM world_canonical_instance_topology_identity m "
                + "JOIN region_instance r ON r.id=m.runtime_row_id WHERE m.family='REGION' ORDER BY m.runtime_identity")) {
      assertThat(
              regions.put(
                  region.get("runtime_identity", UUID.class),
                  region.get("operational_region_id", UUID.class)))
          .isNull();
    }
    assertThat(regions).isNotEmpty();
    var start =
        net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence.fromStored(
                Objects.requireNonNull(selector).selectorReceiptBytes())
            .startLocation();
    var preparing =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence(
            request,
            input.completeLaunchBinding().evidence(),
            start,
            retained.get("runtime_room_instance_id", Long.class),
            "PREPARING",
            retained.get("lifecycle_epoch", Long.class),
            retained.get("row_version", Long.class),
            retained.get("capture_id", UUID.class),
            retained.get("graph_sha256", String.class),
            retained.get("input_digest", String.class),
            regions);
    var activation =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Request(
            UUID.randomUUID(), preparing);
    var active =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence(
            request,
            preparing.launchBinding(),
            start,
            preparing.runtimeRoomInstanceId(),
            "ACTIVE",
            preparing.lifecycleEpoch() + 1,
            preparing.rowVersion() + 1,
            preparing.captureId(),
            preparing.graphSha256(),
            preparing.preparationInputDigest(),
            regions);
    var result =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Result(
            activation,
            net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Outcome.COMMITTED,
            null,
            active);
    write.executeWithoutResult(
        ignored -> {
          sql.execute(
              "INSERT INTO world_canonical_instance_activation_operation "
                  + "(activation_request_id,request_digest,request_bytes,preparing_evidence_bytes,"
                  + "canonical_game_instance_id,world_instance_id,expected_lifecycle_epoch,expected_row_version,"
                  + "outcome,terminal_code,result_lifecycle_epoch,result_row_version,result_bytes,result_digest) "
                  + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
              activation.activationRequestId(),
              activation.requestDigest(),
              activation.canonicalRequestBytes(),
              activation.preparingEvidenceBytes(),
              activation.canonicalGameInstanceId(),
              retained.get("id", Long.class),
              activation.expectedLifecycleEpoch(),
              activation.expectedRowVersion(),
              "COMMITTED",
              null,
              active.lifecycleEpoch(),
              active.rowVersion(),
              result.canonicalBytes(),
              DraftAuthorizationFenceBinding.digest(result.canonicalBytes()));
          assertThat(
                  sql.execute(
                      "UPDATE world_instance SET status='ACTIVE',lifecycle_epoch=lifecycle_epoch+1,"
                          + "row_version=row_version+1,updated_at=CURRENT_TIMESTAMP "
                          + "WHERE id=? AND status='PREPARING' AND lifecycle_epoch=? AND row_version=?",
                      retained.get("id", Long.class),
                      activation.expectedLifecycleEpoch(),
                      activation.expectedRowVersion()))
              .isEqualTo(1);
        });
    assertThat(sql.fetchCount(DSL.table("world_canonical_activation_execution_manifest"))).isZero();
    assertThat(sql.fetchSingle("SELECT status FROM world_instance").get(0, String.class))
        .isEqualTo("ACTIVE");
    return result;
  }

  @org.junit.jupiter.api.Test
  void unqualifiedFrozenHistoryCannotGainAccountOrderOrCompleteInventory() throws Exception {
    String schema = "world_unqualified_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migration(source, schema, "1025").migrate();
    var sql = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var write = new TransactionTemplate(new DataSourceTransactionManager(source));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var fixture = frozenHistory(sql, write);
    var queries =
        originalQueries(
            sql,
            List.of(
                "world_authored_source_intake",
                "world_authored_version_identity",
                "world_topology_draft_commit",
                "world_draft_graph_application",
                "world_draft_start_location_receipt",
                "world_canonical_frozen_topology",
                "world_design_publication_fence_owner",
                "world_design_publication_fence_attempt",
                "world_authored_topology_identity",
                "world_design_aggregate_epoch",
                "world_design_scope_epoch",
                "world_design_revision_ledger",
                "region",
                "zone",
                "room",
                "room_exit",
                "generation_rule",
                "world_entity_spawn_binding"));
    var before = rows(sql, queries);
    assertThat(before).hasSize(18);
    for (String table :
        List.of(
            "world_authored_source_intake",
            "world_authored_version_identity",
            "world_topology_draft_commit",
            "world_draft_graph_application",
            "world_draft_start_location_receipt",
            "world_canonical_frozen_topology",
            "world_design_publication_fence_owner",
            "world_design_publication_fence_attempt",
            "region",
            "zone",
            "room",
            "room_exit")) {
      assertThat(before.get(table)).as(table).isNotEmpty();
    }
    assertThat(before.get("generation_rule")).isEmpty();
    assertThat(before.get("world_entity_spawn_binding")).isEmpty();
    var priorHistory = history(sql);
    for (String target : new String[] {"1026", "1028", "1035", null}) {
      var upgrade = migration(source, schema, target);
      upgrade.migrate();
      upgrade.validate();
      assertThat(rows(sql, queries)).isEqualTo(before);
      assertThat(history(sql)).containsAll(priorHistory);
      priorHistory = history(sql);
      assertUnqualifiedFreeze(sql, fixture);
      if (target == null || !target.equals("1026")) {
        var account =
            isolatedPublicationAccountBinding(
                application(fixture.plan()).operation().accountBindingBytes(),
                fixture.selection(),
                fixture.attempt().request().publicationRequestId());
        assertThatThrownBy(
                () ->
                    new WorldSelectedPublicationArtifactInventoryRepository(sql)
                        .readCommitted(fixture.attempt(), account))
            .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
            .hasMessage("Exact frozen World publication has no complete artifact inventory");
        assertThat(sql.fetchCount(DSL.table("world_selected_publication_artifact_inventory")))
            .isZero();
      }
      assertThat(rows(sql, queries)).isEqualTo(before);
    }
    var account =
        isolatedPublicationAccountBinding(
            application(fixture.plan()).operation().accountBindingBytes(),
            fixture.selection(),
            fixture.attempt().request().publicationRequestId());
    // A plausible distinct Account order supplied now is not the missing original order.
    // Keep all installed triggers enabled and attempt the forbidden late attachment directly.
    assertThatThrownBy(
            () ->
                write.executeWithoutResult(
                    ignored ->
                        sql.execute(
                            "INSERT INTO world_design_publication_account_binding "
                                + "(publication_fence,account_operation_id,account_fence_id,account_binding_bytes,account_binding_digest) "
                                + "VALUES (?,?,?,?,?)",
                            fixture.attempt().publicationFence(),
                            account.operationId(),
                            account.fenceId(),
                            account.canonicalBytes(),
                            DraftAuthorizationFenceBinding.digest(account.canonicalBytes()))))
        .isInstanceOf(DataAccessException.class);
    assertUnqualifiedFreeze(sql, fixture);
    assertThat(rows(sql, queries)).isEqualTo(before);
    assertThat(sql.fetchCount(DSL.table("world_design_publication_terminal"))).isZero();
    assertThat(sql.fetchCount(DSL.table("world_instance"))).isZero();
    assertThat(sql.fetchValue("SHOW session_replication_role", String.class)).isEqualTo("origin");
  }

  private static void assertUnqualifiedFreeze(DSLContext sql, FrozenHistoryFixture fixture) {
    assertThat(
            sql.fetchSingle(
                    "SELECT attempt_created_full_xid IS NULL FROM world_design_publication_fence_attempt")
                .get(0, Boolean.class))
        .isTrue();
    assertThat(sql.fetchCount(DSL.table("world_design_publication_account_binding"))).isZero();
    assertThat(
            sql.fetchSingle(
                "SELECT owner_freeze_phase,current_publication_fence FROM world_design_publication_fence_owner"))
        .satisfies(
            row -> {
              assertThat(row.get("owner_freeze_phase", String.class)).isEqualTo("FROZEN");
              assertThat(row.get("current_publication_fence", UUID.class))
                  .isEqualTo(fixture.attempt().publicationFence());
            });
  }

  private static FrozenHistoryFixture frozenHistory(DSLContext sql, TransactionTemplate write)
      throws Exception {
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
    var selection = publicationSelection(plan, publication, 1L);
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
            selection.digest().substring("sha256:".length()),
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
    return new FrozenHistoryFixture(f, plan, selection, attempt, captures, frozen);
  }

  private record FrozenHistoryFixture(
      Fixture fixture,
      WorldDraftTopologyCommitPlan plan,
      AuthoredDraftPublishSelectionBinding selection,
      WorldDesignPublicationFenceEvidence.FrozenAttempt attempt,
      WorldCanonicalFrozenTopologyRepository captures,
      WorldCanonicalFrozenTopology frozen) {}

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

  private static void assertSelector(DSLContext sql, WorldPublishedStartLocationEvidence selector) {
    if (selector == null) {
      assertThat(sql.fetchCount(DSL.table(DSL.name("world_canonical_preparation_start_location"))))
          .isZero();
      assertThat(
              sql.fetchSingle("SELECT input_json FROM world_canonical_instance_preparation")
                  .get(0, String.class))
          .doesNotContain("worldStartLocationEvidence");
      return;
    }
    var row = sql.fetchSingle("SELECT * FROM world_canonical_preparation_start_location");
    assertThat(row.get("evidence_bytes", byte[].class)).containsExactly(selector.canonicalBytes());
    assertThat(row.get("runtime_room_instance_id", Long.class)).isPositive();
    var receipt =
        net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence.fromStored(
            selector.selectorReceiptBytes());
    assertThat(row.get("room_template_id", UUID.class))
        .isEqualTo(receipt.startLocation().roomTemplateId());
  }

  private static AuthoredDraftPublishSelectionBinding publicationSelection(
      WorldDraftTopologyCommitPlan plan, String publicationRequest, long freezeEpoch) {
    var binding = plan.binding();
    var intent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            publicationRequest,
            Long.toString(freezeEpoch),
            "isolated-world-terminal-fixture",
            binding.requestId(),
            binding.commitId(),
            binding.digest());
    return AuthoredDraftPublishSelectionBinding.capture(
        intent,
        binding.target(),
        binding,
        new AuthoredDraftPublishSelectionBinding.VisibilityFence(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            binding.digest(),
            "[]",
            OffsetDateTime.parse("2026-01-01T00:00:00Z")));
  }

  private static AccountPublicationAuthorizationBinding isolatedPublicationAccountBinding(
      byte[] originalDraftAccountBinding,
      AuthoredDraftPublishSelectionBinding selection,
      String publicationRequestId) {
    var originalAccount = DraftAuthorizationFenceBinding.fromStored(originalDraftAccountBinding);
    String stableIdentity =
        selection.target().canonicalTenantId()
            + ":"
            + selection.target().canonicalVersionId()
            + ":"
            + publicationRequestId;
    // Fixture-only distinct Account order, retained before the first freeze; not producer proof.
    return new AccountPublicationAuthorizationBinding(
        UUID.nameUUIDFromBytes(
            (stableIdentity + "/account-operation").getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes(
            (stableIdentity + "/account-fence").getBytes(StandardCharsets.UTF_8)),
        new AccountPublicationAuthorizationBinding.PreallocationInput(
            originalAccount.actorAccountId(), selection),
        List.of(
            new DraftAuthorizationFenceBinding.SourceEvidence(
                DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                originalAccount.actorAccountId().toString(),
                "1",
                "1",
                null,
                null,
                new byte[] {1})));
  }

  private static ReleaseContent isolatedReleaseContent(
      CompleteLaunchBindingEvidence launchEvidence) {
    var release = launchEvidence.releaseAttestation();
    var world = Objects.requireNonNull(release.worldStartLocationEvidence());
    var participants =
        release.participantDigests().stream()
            .map(
                participant ->
                    new Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionIdPresent() ? participant.baseVersionId() : null,
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        participant.digestSchemaVersion(),
                        participant.abilitySchemaDigestPresent()
                            ? participant.abilitySchemaDigest()
                            : null,
                        null,
                        null))
            .toList();
    return new ReleaseContent(
        release.canonicalTenantId(),
        release.canonicalVersionId(),
        release.publishedReleaseBundleRef(),
        1,
        switch (release.schemaVersion()) {
          case AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION -> "v2";
          case AuthoredWorldReleaseAttestationEvidence.CLOSURE_SELECTOR_SCHEMA_VERSION -> "v3";
          default ->
              throw new IllegalArgumentException("Release has no paired terminal content profile");
        },
        release.publishWorkflowId(),
        release.manifestHash(),
        release.manifestSchemaVersion(),
        release.artifactDigests(),
        release.requiredManifestAssetKeys(),
        participants,
        release.commandDefinitions(),
        release.generationConfigRevision(),
        world);
  }

  private static void retainHistoricalPublicationTerminal(
      DSLContext sql,
      TransactionTemplate write,
      WorldCanonicalInstancePreparation.Input input,
      AuthoredDraftPublishSelectionBinding selection) {
    var world =
        Objects.requireNonNull(
            input
                .completeLaunchBinding()
                .evidence()
                .releaseAttestation()
                .worldStartLocationEvidence());
    var account =
        isolatedPublicationAccountBinding(
            world.originalAccountBindingBytes(), selection, world.request().publicationRequestId());
    var original = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(original, "game-design-publication-operation/v1");
    DraftAuthorizationFenceBinding.frame(original, account.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(original, world.canonicalBytes());
    var historical = GameDesignPublicationOperationBinding.fromStored(original.toByteArray());
    assertThat(historical.canonicalBytes()).containsExactly(original.toByteArray());
    assertThatThrownBy(historical::inventory)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("HISTORICAL_PUBLICATION_INVENTORY_UNAVAILABLE");
    // The old owner requires its V1017 terminal and PUBLISHED phase before V1018 preparation.
    // Account/Game Design terminal authority is stipulated; real World source guards stay active.
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            historical.canonicalBytes(),
            Outcome.PUBLISHED,
            isolatedReleaseContent(input.completeLaunchBinding().evidence()),
            input.completeLaunchBinding().evidence().releaseAttestation().versionStateEpoch());
    var request = WorldPublicationTerminal.Request.fromStored(terminal.canonicalBytes());
    write.executeWithoutResult(
        ignored -> {
          insertPublicationTerminal(sql, request);
          var attempt =
              sql.fetchSingle(
                  "SELECT local_tenant_key,version_id FROM world_design_publication_fence_attempt "
                      + "WHERE publication_fence=? AND target_namespace=? "
                      + "AND canonical_tenant_id=? AND canonical_version_id=?",
                  world.request().publicationFence(),
                  world.request().targetNamespace(),
                  world.request().canonicalTenantId(),
                  world.request().canonicalVersionId());
          assertThat(
                  sql.execute(
                      "UPDATE world_design_publication_fence_owner SET owner_freeze_phase='PUBLISHED' "
                          + "WHERE target_namespace=? AND canonical_tenant_id=? AND local_tenant_key=? "
                          + "AND version_id=? AND owner_freeze_phase='FROZEN' AND current_publication_fence=?",
                      world.request().targetNamespace(),
                      world.request().canonicalTenantId(),
                      attempt.get("local_tenant_key", Long.class),
                      attempt.get("version_id", Long.class),
                      world.request().publicationFence()))
              .isEqualTo(1);
        });
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void insertPublicationTerminal(
      DSLContext sql, WorldPublicationTerminal.Request request) {
    var world = request.worldEvidence().request();
    sql.execute(
        "INSERT INTO world_design_publication_terminal (publication_fence,target_namespace,"
            + "canonical_tenant_id,canonical_version_id,publication_request_id,request_digest,"
            + "publish_workflow_id,freeze_version_state_epoch,applied_commit_id,content_digest,"
            + "digest_schema_version,outcome,publication_version_state_epoch,"
            + "published_release_bundle_ref,published_release_bundle_digest,operation_bytes,"
            + "terminal_evidence_bytes,terminal_evidence_digest,world_evidence_bytes,world_request_json,"
            + "selector_receipt_bytes,original_account_binding_bytes,applied_result_bytes,release_content_bytes) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        world.publicationFence(),
        world.targetNamespace(),
        world.canonicalTenantId(),
        world.canonicalVersionId(),
        world.publicationRequestId(),
        world.requestDigest(),
        world.publishWorkflowId(),
        world.versionStateEpoch(),
        world.appliedCommitId(),
        world.contentDigest(),
        world.digestSchemaVersion(),
        request.evidence().outcome().name(),
        request.publicationVersionStateEpoch(),
        request.releaseBundleRef(),
        request.releaseBundleDigest(),
        request.operationBytes(),
        request.terminalBytes(),
        HexFormat.of().formatHex(sha256(request.terminalBytes())),
        request.worldEvidenceBytes(),
        request.worldRequestJson(),
        request.selectorReceiptBytes(),
        request.originalAccountBindingBytes(),
        request.appliedResultBytes(),
        request.releaseContentBytes());
  }

  private static WorldPublishedStartLocationEvidence publishedEvidence(
      WorldPublishedStartLocationSource source) {
    var freeze = source.frozenTopology().request().freeze();
    return new WorldPublishedStartLocationEvidence(
        new WorldPublishedStartLocationEvidence.Request(
            freeze.targetNamespace(),
            freeze.canonicalTenantId(),
            freeze.canonicalVersionId(),
            freeze.intakeRequestId(),
            freeze.publicationFence(),
            freeze.publicationRequestId(),
            freeze.requestDigest(),
            freeze.versionStateEpoch(),
            freeze.publishWorkflowId(),
            freeze.appliedCommitId(),
            freeze.contentDigest(),
            freeze.digestSchemaVersion(),
            freeze.suppliedOwnedAffectedTuples().stream()
                .map(
                    tuple ->
                        new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                            tuple.owner(),
                            tuple.aggregateType(),
                            tuple.aggregateId(),
                            tuple.scopeType(),
                            tuple.scopeId(),
                            tuple.expectedEpoch()))
                .toList()),
        source.selectorReceipt().canonicalBytes(),
        source.appliedResult().application().operation().accountBindingBytes(),
        source.appliedResult().canonicalBytes());
  }

  private static WorldCanonicalInstancePreparation.Input preparationInput(
      DSLContext sql,
      TransactionTemplate write,
      Fixture f,
      WorldCanonicalFrozenTopology frozen,
      WorldPublishedStartLocationEvidence selector) {
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
                            owner,
                            selector == null
                                ? AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION
                                : AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "b".repeat(64) : null))
            .toList();
    // Complete legacy release/GS values are constructed test evidence, never live producers.
    var release =
        selector == null
            ? AuthoredWorldReleaseAttestationEvidence.create(
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
                descriptor.generationConfigRevision())
            : AuthoredWorldReleaseAttestationEvidence.create(
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
                descriptor.generationConfigRevision(),
                selector);
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

  private static Map<String, String> originalQueries(DSLContext sql, boolean withSelector) {
    var tables =
        new ArrayList<>(
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
                "world_canonical_instance_preparation"));
    if (withSelector) {
      tables.addAll(
          List.of(
              "world_canonical_preparation_start_location",
              "world_design_publication_fence_owner",
              "world_design_publication_fence_attempt",
              "world_design_publication_terminal"));
    }
    return originalQueries(sql, tables);
  }

  private static Map<String, String> originalQueries(DSLContext sql, List<String> tables) {
    var result = new LinkedHashMap<String, String>();
    for (String table : tables) {
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
