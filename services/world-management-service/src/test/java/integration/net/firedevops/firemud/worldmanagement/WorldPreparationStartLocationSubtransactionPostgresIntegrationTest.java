package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the installed schema-4 preparation selector against isolated copies of its installed
 * table shapes and function bodies. The controlled rows establish SQL predicate behavior only; they
 * do not establish Account authorization or a genuine production owner handoff.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
@SuppressWarnings("resource")
class WorldPreparationStartLocationSubtransactionPostgresIntegrationTest {
  private static final String FORGED_FULL_XID = "9223372036854775807";
  private static final long WORLD_ID = 71001;
  private static final long TENANT_KEY = 71002;
  private static final long GAME_INSTANCE_KEY = 71003;
  private static final long TEMPLATE_ROOM_KEY = 71004;
  private static final long RUNTIME_ROOM_KEY = 71005;

  private static final List<String> PROBE_TABLES =
      List.of(
          "world_instance",
          "room",
          "room_instance",
          "world_complete_launch_binding",
          "world_draft_start_location_receipt",
          "world_canonical_frozen_topology",
          "world_draft_graph_application",
          "world_topology_draft_commit",
          "world_authored_topology_identity",
          "world_canonical_instance_topology_identity",
          "world_canonical_instance_preparation",
          "world_canonical_preparation_start_location");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void installedSchema4GuardAcceptsStampedSavepointPreparationAndRejectsLateOrHistoricalRows()
      throws Exception {
    String installedSchema = dsl.fetchSingle("SELECT current_schema()").get(0, String.class);
    String probeSchema = "world_preparation_guard_" + UUID.randomUUID().toString().replace("-", "");
    Fixture fixture = fixture();
    try {
      dsl.execute("CREATE SCHEMA " + DSL.name(probeSchema));
      cloneProbeTables(installedSchema, probeSchema);
      seedSelectorEvidence(probeSchema, fixture);
      seedHistoricalPreparation(probeSchema, fixture);
      copyInstalledFunctions(installedSchema, probeSchema);
      installProbeTriggers(probeSchema);

      var outer = new TransactionTemplate(transactionManager);
      outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      var savepoint = new TransactionTemplate(transactionManager);
      savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
      savepoint.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      XidObservation observation =
          outer.execute(
              status -> {
                savepoint.executeWithoutResult(
                    nested ->
                        insertPreparation(probeSchema, fixture, 2, fixture.freshInstanceId()));
                var row =
                    dsl.fetchSingle(
                        "SELECT preparation_inserted_full_xid::text, xmin::text, "
                            + "pg_current_xact_id()::text, "
                            + "(txid_current() % 4294967296)::text "
                            + "FROM "
                            + DSL.name(probeSchema, "world_canonical_instance_preparation")
                            + " WHERE canonical_game_instance_id = ?",
                        fixture.freshInstanceId());
                return new XidObservation(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class));
              });

      assertThat(observation).isNotNull();
      assertThat(observation.serverStamp())
          .as("a caller-supplied full XID is overwritten by the server stamp")
          .isNotEqualTo(FORGED_FULL_XID)
          .isEqualTo(observation.topLevelFullXid());
      assertThat(observation.childXmin())
          .as("the row inserted under the savepoint has a child xmin distinct from the top XID")
          .isNotEqualTo(observation.topLevelXid32());
      assertThat(
              scalarLong(
                  "SELECT count(*) FROM "
                      + DSL.name(probeSchema, "world_canonical_preparation_start_location")))
          .as("the installed retainer reaches the installed schema-4 selector guard")
          .isEqualTo(1L);

      assertSqlState(
          () -> insertStartLocation(probeSchema, fixture, fixture.freshInstanceId()), "P0002");
      assertSqlState(
          () -> insertStartLocation(probeSchema, fixture, fixture.historicalInstanceId()), "P0002");
      assertThat(
              scalarText(
                  "SELECT preparation_inserted_full_xid::text FROM "
                      + DSL.name(probeSchema, "world_canonical_instance_preparation")
                      + " WHERE canonical_game_instance_id = ?",
                  fixture.historicalInstanceId()))
          .as("the controlled old preparation models a pre-migration NULL stamp")
          .isNull();
    } finally {
      dsl.execute("DROP SCHEMA IF EXISTS " + DSL.name(probeSchema) + " CASCADE");
    }
  }

  private void cloneProbeTables(String installedSchema, String probeSchema) {
    for (String table : PROBE_TABLES)
      dsl.execute(
          "CREATE TABLE "
              + DSL.name(probeSchema, table)
              + " AS TABLE "
              + DSL.name(installedSchema, table)
              + " WITH NO DATA");
  }

  private void copyInstalledFunctions(String installedSchema, String probeSchema) {
    for (String signature :
        List.of(
            "world_require_preparation_start_location(jsonb,jsonb,uuid)",
            "world_guard_preparation_start_location()",
            "world_retain_preparation_start_location()",
            "world_stamp_preparation_insert_full_xid()")) {
      String definition =
          scalarText(
              "SELECT pg_get_functiondef(to_regprocedure(?))", installedSchema + "." + signature);
      definition =
          definition
              .replace("\"" + installedSchema + "\"", "\"" + probeSchema + "\"")
              .replace(installedSchema + ".", probeSchema + ".")
              .replace("'" + installedSchema + "'", "'" + probeSchema + "'");
      dsl.execute(definition);
    }
  }

  private void installProbeTriggers(String probeSchema) {
    dsl.execute(
        "CREATE TRIGGER trg_world_preparation_full_xid_stamp BEFORE INSERT ON "
            + DSL.name(probeSchema, "world_canonical_instance_preparation")
            + " FOR EACH ROW EXECUTE FUNCTION "
            + DSL.name(probeSchema, "world_stamp_preparation_insert_full_xid")
            + "()");
    dsl.execute(
        "CREATE TRIGGER trg_world_preparation_retain_start_location AFTER INSERT ON "
            + DSL.name(probeSchema, "world_canonical_instance_preparation")
            + " FOR EACH ROW EXECUTE FUNCTION "
            + DSL.name(probeSchema, "world_retain_preparation_start_location")
            + "()");
    dsl.execute(
        "CREATE TRIGGER trg_world_preparation_start_location_guard "
            + "BEFORE INSERT OR UPDATE OR DELETE ON "
            + DSL.name(probeSchema, "world_canonical_preparation_start_location")
            + " FOR EACH ROW EXECUTE FUNCTION "
            + DSL.name(probeSchema, "world_guard_preparation_start_location")
            + "()");
    dsl.execute(
        "CREATE TRIGGER trg_world_preparation_start_location_no_truncate "
            + "BEFORE TRUNCATE ON "
            + DSL.name(probeSchema, "world_canonical_preparation_start_location")
            + " FOR EACH STATEMENT EXECUTE FUNCTION "
            + DSL.name(probeSchema, "world_guard_preparation_start_location")
            + "()");
  }

  private void seedHistoricalPreparation(String schema, Fixture fixture) throws Exception {
    insertPreparation(schema, fixture, 1, fixture.historicalInstanceId(), null);
  }

  private void insertPreparation(String schema, Fixture fixture, long rowId, UUID gameInstanceId) {
    insertPreparation(schema, fixture, rowId, gameInstanceId, FORGED_FULL_XID);
  }

  private void insertPreparation(
      String schema, Fixture fixture, long rowId, UUID gameInstanceId, String callerStamp) {
    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "world_canonical_instance_preparation")
            + " (id, canonical_game_instance_id, target_namespace, canonical_tenant_id, "
            + "world_slug, control_plane_request_id, canonical_version_id, "
            + "version_identity_operation_id, capture_id, graph_bytes, graph_sha256, input_digest, "
            + "input_json, world_instance_id, private_game_instance_key, region_count, zone_count, "
            + "room_count, exit_count, storage_status, preparation_inserted_full_xid) "
            + "VALUES (?, ?, 'dev', ?, 'probe-world', 'probe-op', ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "1, 0, 1, 0, 'MATERIALIZED_UNVERIFIED', ?::xid8)",
        rowId,
        gameInstanceId,
        fixture.tenantId(),
        fixture.versionId(),
        fixture.versionIdentityOperationId(),
        fixture.captureId(),
        fixture.graphBytes(),
        fixture.graphSha256(),
        fixture.inputDigest(),
        fixture.inputJson(),
        WORLD_ID,
        GAME_INSTANCE_KEY,
        callerStamp);
  }

  private void seedSelectorEvidence(String schema, Fixture fixture) throws Exception {
    var names = fixture.names();
    var source = DSL.name(schema, "world_instance");
    dsl.execute(
        "INSERT INTO " + source + " (id, tenant_id, game_instance_id) VALUES (?, ?, ?)",
        WORLD_ID,
        TENANT_KEY,
        GAME_INSTANCE_KEY);
    dsl.execute(
        "INSERT INTO " + DSL.name(schema, "room") + " (id, tenant_id, version_id) VALUES (?, ?, ?)",
        TEMPLATE_ROOM_KEY,
        TENANT_KEY,
        fixture.localVersionKey());
    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "room_instance")
            + " (id, tenant_id, game_instance_id, room_instance_row_id) VALUES (?, ?, ?, ?)",
        RUNTIME_ROOM_KEY,
        TENANT_KEY,
        GAME_INSTANCE_KEY,
        RUNTIME_ROOM_KEY);
    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "world_canonical_instance_topology_identity")
            + " (world_instance_id, canonical_game_instance_id, family, template_id, "
            + "template_private_row_key, runtime_row_id, runtime_room_instance_id) "
            + "VALUES (?, ?, 'ROOM', ?, ?, ?, ?)",
        WORLD_ID,
        fixture.freshInstanceId(),
        fixture.roomTemplateId(),
        TEMPLATE_ROOM_KEY,
        RUNTIME_ROOM_KEY,
        RUNTIME_ROOM_KEY);

    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "world_complete_launch_binding")
            + " (binding_operation_id, release_attestation_json) VALUES (?, ?)",
        fixture.bindingOperationId(),
        names.releaseAttestationJson());
    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "world_topology_draft_commit")
            + " (request_id, commit_id, target_namespace, canonical_tenant_id, "
            + "canonical_version_id, local_tenant_key, local_version_key, binding_json, "
            + "owner_binding_json, binding_digest, graph_bytes, graph_sha256, result_bytes, "
            + "storage_status) VALUES (?, ?, 'dev', ?, ?, ?, ?, '{}', '{}', ?, ?, ?, ?, "
            + "'STORED_PERMISSION_UNVERIFIED')",
        fixture.requestId(),
        fixture.commitId(),
        fixture.tenantId(),
        fixture.versionId(),
        TENANT_KEY,
        fixture.localVersionKey(),
        fixture.bindingDigest(),
        fixture.graphBytes(),
        fixture.graphSha256(),
        fixture.appliedResultBytes());
    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "world_canonical_frozen_topology")
            + " (capture_id, publication_fence, request_id, commit_id, "
            + "version_identity_operation_id, freeze_request_json, owner_binding_json, "
            + "binding_json, binding_digest, identity_json, intake_json, graph_bytes, "
            + "graph_sha256, storage_result_bytes, result_bytes, capture_status) "
            + "VALUES (?, ?, ?, ?, ?, ?, '{}', '{}', ?, '{}', '{}', ?, ?, ?, ?, "
            + "'CAPTURED_UNVERIFIED')",
        fixture.captureId(),
        fixture.publicationFence(),
        fixture.requestId(),
        fixture.commitId(),
        fixture.versionIdentityOperationId(),
        names.freezeRequestJson(),
        fixture.bindingDigest(),
        fixture.graphBytes(),
        fixture.graphSha256(),
        fixture.appliedResultBytes(),
        fixture.appliedResultBytes());
    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "world_draft_graph_application")
            + " (operation_id, request_id, commit_id, authorization_fence_id, operation_bytes, "
            + "account_binding_bytes, account_binding_digest, result_bytes, result_digest, "
            + "application_transaction_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 41)",
        fixture.applicationOperationId(),
        fixture.requestId(),
        fixture.commitId(),
        fixture.publicationFence(),
        fixture.appliedResultBytes(),
        fixture.accountBindingBytes(),
        fixture.accountBindingDigest(),
        fixture.appliedResultBytes(),
        fixture.resultDigest());
    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "world_draft_start_location_receipt")
            + " (operation_id, request_id, commit_id, authorization_fence_id, target_namespace, "
            + "account_binding_bytes, account_binding_digest, binding_digest, canonical_tenant_id, "
            + "canonical_version_id, room_template_id, graph_digest, receipt_digest, receipt_bytes) "
            + "VALUES (?, ?, ?, ?, 'dev', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        fixture.applicationOperationId(),
        fixture.requestId(),
        fixture.commitId(),
        fixture.publicationFence(),
        fixture.accountBindingBytes(),
        fixture.accountBindingDigest(),
        fixture.bindingDigest(),
        fixture.tenantId(),
        fixture.versionId(),
        fixture.roomTemplateId(),
        fixture.graphDigest(),
        fixture.receiptDigest(),
        fixture.selectorReceiptBytes());
    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "world_authored_topology_identity")
            + " (target_namespace, canonical_tenant_id, canonical_version_id, family, template_id, "
            + "private_row_key, tenant_id, version_id, version_identity_operation_id, request_id, "
            + "commit_id, revision_id, revision_order) VALUES ('dev', ?, ?, 'ROOM', ?, ?, ?, ?, ?, ?, ?, ?, '0')",
        fixture.tenantId(),
        fixture.versionId(),
        fixture.roomTemplateId(),
        TEMPLATE_ROOM_KEY,
        TENANT_KEY,
        fixture.localVersionKey(),
        fixture.versionIdentityOperationId(),
        fixture.requestId(),
        fixture.commitId(),
        UUID.randomUUID());
  }

  private void insertStartLocation(String schema, Fixture fixture, UUID gameInstanceId) {
    dsl.execute(
        "INSERT INTO "
            + DSL.name(schema, "world_canonical_preparation_start_location")
            + " (canonical_game_instance_id, world_instance_id, canonical_tenant_id, "
            + "canonical_version_id, room_template_id, runtime_room_instance_id, receipt_digest, "
            + "graph_digest, evidence_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        gameInstanceId,
        WORLD_ID,
        fixture.tenantId(),
        fixture.versionId(),
        fixture.roomTemplateId(),
        RUNTIME_ROOM_KEY,
        fixture.receiptDigest(),
        fixture.graphDigest(),
        fixture.evidenceBytes());
  }

  private Fixture fixture() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID commitId = UUID.randomUUID();
    UUID publicationFence = UUID.randomUUID();
    UUID captureId = UUID.randomUUID();
    UUID roomTemplateId = UUID.randomUUID();
    UUID bindingOperationId = UUID.randomUUID();
    byte[] graphBytes =
        "{\"schemaVersion\":3,\"rows\":[{\"family\":\"ROOM\"}]}".getBytes(StandardCharsets.UTF_8);
    String graphSha256 = digestHex(graphBytes);
    String graphDigest = "sha256:" + graphSha256;
    String bindingDigest = "sha256:" + "b".repeat(64);
    byte[] accountBindingBytes =
        "{\"accountBinding\":\"controlled\"}".getBytes(StandardCharsets.UTF_8);
    String accountBindingDigest = "sha256:" + digestHex(accountBindingBytes);
    byte[] selectorReceiptBytes = "{\"receipt\":\"controlled\"}".getBytes(StandardCharsets.UTF_8);
    byte[] appliedResultBytes =
        ("{\"schema\":\"world-draft-graph-applied/v2\",\"status\":\"APPLIED\","
                + "\"graphDigest\":\""
                + graphDigest
                + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    String resultDigest = "sha256:" + digestHex(appliedResultBytes);

    Map<String, Object> selectedRequest = new LinkedHashMap<>();
    selectedRequest.put("targetNamespace", "dev");
    selectedRequest.put("canonicalTenantId", tenantId.toString());
    selectedRequest.put("canonicalVersionId", versionId.toString());
    selectedRequest.put("intakeRequestId", UUID.randomUUID().toString());
    selectedRequest.put("publicationFence", publicationFence.toString());
    selectedRequest.put("publicationRequestId", "controlled-publication");
    selectedRequest.put("requestDigest", "c".repeat(64));
    selectedRequest.put("versionStateEpoch", "7");
    selectedRequest.put("publishWorkflowId", "controlled-workflow");
    selectedRequest.put("appliedCommitId", "controlled-commit");
    selectedRequest.put("contentDigest", "sha256:" + "d".repeat(64));
    selectedRequest.put("digestSchemaVersion", "4");
    selectedRequest.put("worldAffectedTuples", List.of());
    Map<String, Object> retainedRequest = new LinkedHashMap<>(selectedRequest);
    retainedRequest.put("versionStateEpoch", 7L);

    Map<String, Object> freezeRequest = new LinkedHashMap<>();
    selectedRequest.forEach(
        (key, value) -> {
          if (!key.equals("worldAffectedTuples")) freezeRequest.put(key, value);
        });
    freezeRequest.remove("versionStateEpoch");
    freezeRequest.put("versionStateEpoch", 7L);
    freezeRequest.put("suppliedOwnedAffectedTuples", List.of());

    String receiptBytes64 = Base64.getEncoder().encodeToString(selectorReceiptBytes);
    String accountBytes64 = Base64.getEncoder().encodeToString(accountBindingBytes);
    String resultBytes64 = Base64.getEncoder().encodeToString(appliedResultBytes);
    Map<String, Object> evidence =
        Map.of(
            "schema", "world-published-start-location-evidence/v1",
            "request", selectedRequest,
            "selectorReceiptBytesBase64", receiptBytes64,
            "originalAccountBindingBytesBase64", accountBytes64,
            "appliedResultBytesBase64", resultBytes64);
    byte[] evidenceBytes = json(evidence).getBytes(StandardCharsets.UTF_8);
    Map<String, Object> retainedEvidence =
        Map.of(
            "request", retainedRequest,
            "selectorReceiptBytes", receiptBytes64,
            "originalAccountBindingBytes", accountBytes64,
            "appliedResultBytes", resultBytes64);
    String releaseAttestationJson =
        json(Map.of("schemaVersion", 4, "worldStartLocationEvidence", retainedEvidence));
    Map<String, Object> input =
        Map.of(
            "schemaVersion", 4,
            "launchBinding", Map.of("operationId", bindingOperationId.toString()),
            "worldStartLocationEvidenceBase64", Base64.getEncoder().encodeToString(evidenceBytes));

    return new Fixture(
        tenantId,
        versionId,
        operationId,
        requestId,
        commitId,
        publicationFence,
        captureId,
        roomTemplateId,
        bindingOperationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        WORLD_ID,
        TENANT_KEY,
        GAME_INSTANCE_KEY,
        graphBytes,
        graphSha256,
        graphDigest,
        "sha256:" + digestHex(selectorReceiptBytes),
        bindingDigest,
        accountBindingBytes,
        accountBindingDigest,
        selectorReceiptBytes,
        appliedResultBytes,
        resultDigest,
        evidenceBytes,
        "sha256:" + "e".repeat(64),
        json(input),
        TENANT_KEY + 20,
        new FixtureNames(json(freezeRequest), releaseAttestationJson));
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  private static String digestHex(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private long scalarLong(String sql, Object... bindings) {
    return dsl.fetchSingle(sql, bindings).get(0, Long.class);
  }

  private String scalarText(String sql, Object... bindings) {
    return dsl.fetchSingle(sql, bindings).get(0, String.class);
  }

  private static void assertSqlState(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state) {
    assertThatThrownBy(action)
        .satisfies(
            failure -> {
              Throwable cause = failure;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(PSQLException.class);
              assertThat(((PSQLException) cause).getSQLState()).isEqualTo(state);
            });
  }

  private record XidObservation(
      String serverStamp, String childXmin, String topLevelFullXid, String topLevelXid32) {}

  private record FixtureNames(String freezeRequestJson, String releaseAttestationJson) {}

  private record Fixture(
      UUID tenantId,
      UUID versionId,
      UUID versionIdentityOperationId,
      UUID requestId,
      UUID commitId,
      UUID publicationFence,
      UUID captureId,
      UUID roomTemplateId,
      UUID bindingOperationId,
      UUID freshInstanceId,
      UUID historicalInstanceId,
      long worldId,
      long tenantKey,
      long privateGameInstanceKey,
      byte[] graphBytes,
      String graphSha256,
      String graphDigest,
      String receiptDigest,
      String bindingDigest,
      byte[] accountBindingBytes,
      String accountBindingDigest,
      byte[] selectorReceiptBytes,
      byte[] appliedResultBytes,
      String resultDigest,
      byte[] evidenceBytes,
      String inputDigest,
      String inputJson,
      long localVersionKey,
      FixtureNames names) {}
}
