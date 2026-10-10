package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

/** Actual V35 database denial proof; all external source/authentication data is synthetic. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldCanonicalInstancePreparationPostgresIntegrationTest {
  @Test
  void selectorGuardPreservesInertV1ShapeAndDeniesPromotionOrMissingV2BeforeAllocation() {
    long before =
        Objects.requireNonNull(dsl.fetchOne("SELECT count(*) FROM world_instance"))
            .get(0, Long.class);
    UUID absentCapture = UUID.randomUUID();
    dsl.fetchOne(
        "SELECT world_require_preparation_start_location(?::jsonb,?::jsonb,?)",
        "{\"schemaVersion\":1}",
        "{\"schemaVersion\":1}",
        absentCapture);
    for (String input :
        java.util.List.of(
            "{\"schemaVersion\":2}",
            "{\"schemaVersion\":1,\"worldStartLocationEvidenceBase64\":\"e30=\"}")) {
      assertThatThrownBy(
              () ->
                  dsl.fetchOne(
                      "SELECT world_require_preparation_start_location(?::jsonb,?::jsonb,?)",
                      input,
                      "{\"schemaVersion\":1}",
                      absentCapture))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO world_canonical_preparation_start_location (canonical_game_instance_id,world_instance_id,"
                        + "canonical_tenant_id,canonical_version_id,room_template_id,runtime_room_instance_id,receipt_digest,graph_digest,evidence_bytes) "
                        + "VALUES (?,1,?,?,?,1,?,?,?)",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "sha256:" + "a".repeat(64),
                    "sha256:" + "b".repeat(64),
                    new byte[] {1}))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            Objects.requireNonNull(dsl.fetchOne("SELECT count(*) FROM world_instance"))
                .get(0, Long.class))
        .isEqualTo(before);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT count(*) FROM world_canonical_preparation_start_location"))
                .get(0, Long.class))
        .isZero();
  }

  private static final String NAMESPACE = "firemud";

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
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void v35PreservesReservedTenantDenialAndExecutionEntrypointsRemainOwnerPrivate() {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID());
    WorldAuthoredSourceIntakeReceipt receipt =
        Objects.requireNonNull(
            ownerTransaction()
                .execute(
                    status ->
                        new WorldAuthoredSourceIntakeRepository(dsl)
                            .acceptFresh(NAMESPACE, UUID.randomUUID(), source)),
            "synthetic World source fixture returned no intake receipt");

    assertThatThrownBy(() -> insertInReservedCanonicalTenant(receipt.localTenantKey()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .hasMessageContaining(reservedTenantDenial());

    long legacyInstance = insertLegacyRow(8_700_000_000L + positiveLong() % 100_000L);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_instance SET tenant_id = ? WHERE id = ?",
                    receipt.localTenantKey(),
                    legacyInstance))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .hasMessageContaining(reservedTenantDenial());

    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT tenant_id FROM world_instance WHERE id = ?", legacyInstance),
                    "Legacy row missing after rejected canonical update")
                .get("tenant_id", Long.class))
        .isNotEqualTo(receipt.localTenantKey());
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT EXISTS (SELECT 1 FROM pg_proc p "
                            + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                            + "CROSS JOIN LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl "
                            + "WHERE n.nspname = 'world_management_service' "
                            + "AND p.proname IN ('world_prepare_canonical_instance', "
                            + "'world_prepare_canonical_instance_v61_impl', "
                            + "'world_persist_canonical_instance_execution_intent', "
                            + "'world_persist_canonical_instance_execution_intent_with_original_attempt', "
                            + "'world_claim_canonical_instance_execution', "
                            + "'world_claim_canonical_instance_execution_with_original_attempt', "
                            + "'world_begin_canonical_instance_execution', "
                            + "'world_begin_canonical_instance_execution_with_original_attempt', "
                            + "'world_commit_canonical_instance_execution', "
                            + "'world_abort_canonical_instance_execution', "
                            + "'world_abort_canonical_instance_execution_with_original_attempt', "
                            + "'world_require_active_canonical_instance_execution') "
                            + "AND acl.grantee = 0 AND acl.privilege_type = 'EXECUTE')"),
                    "Prepare-function access query returned no result")
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void freshWorldIntentCannotBeCreatedWithoutTheAccountOwnerObservation() {
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID participation = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    String request = "missing-owner-observation-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                dsl.fetchOne(
                    "INSERT INTO world_canonical_instance_execution ("
                        + "target_namespace,canonical_tenant_id,control_plane_request_id,"
                        + "canonical_game_instance_id,canonical_world_slug,game_template_id,"
                        + "original_post_authorization_tuple,original_post_authorization_tuple_sha256,"
                        + "account_world_participation_id,account_world_participation_fence,"
                        + "game_session_owner_attempt_id,game_session_owner_fence,"
                        + "preparation_input_digest,preparation_input_json,world_execution_fence,operation_state) "
                        + "VALUES (?, ?, ?, ?, 'test-world', 1, ?, encode(sha256(?), 'hex'), ?, 1, ?, 1, "
                        + "'sha256:' || encode(sha256(convert_to('{}','UTF8')), 'hex'), '{}', "
                        + "nextval('world_canonical_instance_execution_fence_seq'), 'PENDING')",
                    NAMESPACE,
                    tenant,
                    request,
                    instance,
                    new byte[] {1},
                    new byte[] {1},
                    participation,
                    attempt))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .hasMessageContaining("exact Account owner observation");
  }

  @Test
  void legacyTerminalFenceCannotAbortAnIntentWithAccountOwnerObservation() {
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID participation = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    UUID mutation = UUID.randomUUID();
    UUID observationRead = UUID.randomUUID();
    String request = "legacy-abort-owner-observation-" + UUID.randomUUID();
    byte[] tuple = new byte[] {1};
    byte[] observation = new byte[] {7, 8, 9};
    String inputJson = "{}";
    String inputDigest = "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
    dsl.fetchOne(
        "INSERT INTO world_canonical_instance_execution ("
            + "target_namespace,canonical_tenant_id,control_plane_request_id,"
            + "canonical_game_instance_id,canonical_world_slug,game_template_id,"
            + "original_post_authorization_tuple,original_post_authorization_tuple_sha256,"
            + "account_world_participation_id,account_world_participation_fence,"
            + "game_session_owner_attempt_id,game_session_owner_fence,"
            + "game_session_owner_mutation_id,original_attempt_read_request_id,"
            + "original_game_session_lease_expires_at,original_attempt_evidence_bytes,"
            + "original_attempt_evidence_sha256,preparation_input_digest,"
            + "preparation_input_json,world_execution_fence,operation_state) "
            + "VALUES (?, ?, ?, ?, 'test-world', 1, ?, encode(sha256(?), 'hex'), ?, 1, ?, 1, ?, ?, "
            + "clock_timestamp() + interval '1 minute', ?, 'sha256:' || encode(sha256(?), 'hex'), ?, ?, "
            + "nextval('world_canonical_instance_execution_fence_seq'), 'PENDING')",
        NAMESPACE,
        tenant,
        request,
        instance,
        tuple,
        tuple,
        participation,
        attempt,
        mutation,
        observationRead,
        observation,
        observation,
        inputDigest,
        inputJson);

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          dsl.fetchOne(
                              "SELECT set_config('world.canonical_abort_namespace', ?, TRUE)",
                              NAMESPACE);
                          dsl.fetchOne(
                              "SELECT set_config('world.canonical_abort_tenant', ?, TRUE)",
                              tenant.toString());
                          dsl.fetchOne(
                              "SELECT set_config('world.canonical_abort_request', ?, TRUE)",
                              request);
                          dsl.fetchOne(
                              "SELECT set_config('world.canonical_abort_target', ?, TRUE)",
                              instance.toString());
                          dsl.execute(
                              "UPDATE world_canonical_instance_execution "
                                  + "SET operation_state='ABORTED', terminal_at=clock_timestamp() "
                                  + "WHERE target_namespace=? AND canonical_tenant_id=? "
                                  + "AND control_plane_request_id=?",
                              NAMESPACE,
                              tenant,
                              request);
                          return null;
                        }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .hasMessageContaining("World ABORTED state requires its exact serialized terminal fence");

    assertThat(
            dsl.fetchValue(
                "SELECT operation_state FROM world_canonical_instance_execution "
                    + "WHERE target_namespace=? AND canonical_tenant_id=? AND control_plane_request_id=?",
                NAMESPACE,
                tenant,
                request))
        .isEqualTo("PENDING");
  }

  @Test
  void originalOwnerLeaseIsRecheckedAgainstDatabaseClockAtWorldAdmission() {
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID participation = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    UUID mutation = UUID.randomUUID();
    UUID observationRead = UUID.randomUUID();
    String request = "lease-expiry-admission-" + UUID.randomUUID();
    String inputJson = "{}";
    String inputDigest = "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
    byte[] tuple =
        ("{\"authorityEvidenceBundle\":{\"accountProjectionEvidence\":{"
                + "\"expiresAt\":\"2035-01-01T00:00:00Z\"}}}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] observation = new byte[] {7, 8, 9};

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          dsl.fetchOne(
                              "INSERT INTO world_canonical_instance_execution ("
                                  + "target_namespace,canonical_tenant_id,control_plane_request_id,"
                                  + "canonical_game_instance_id,canonical_world_slug,game_template_id,"
                                  + "original_post_authorization_tuple,original_post_authorization_tuple_sha256,"
                                  + "account_world_participation_id,account_world_participation_fence,"
                                  + "game_session_owner_attempt_id,game_session_owner_fence,"
                                  + "game_session_owner_mutation_id,original_attempt_read_request_id,"
                                  + "original_game_session_lease_expires_at,original_attempt_evidence_bytes,"
                                  + "original_attempt_evidence_sha256,preparation_input_digest,"
                                  + "preparation_input_json,world_execution_fence,operation_state) "
                                  + "VALUES (?, ?, ?, ?, 'test-world', 1, ?, encode(sha256(?), 'hex'), "
                                  + "?, 1, ?, 1, ?, ?, clock_timestamp() + interval '50 milliseconds', "
                                  + "?, 'sha256:' || encode(sha256(?), 'hex'), ?, ?, "
                                  + "nextval('world_canonical_instance_execution_fence_seq'), 'PENDING')",
                              NAMESPACE,
                              tenant,
                              request,
                              instance,
                              tuple,
                              tuple,
                              participation,
                              attempt,
                              mutation,
                              observationRead,
                              observation,
                              observation,
                              inputDigest,
                              inputJson);
                          dsl.fetchOne(
                              "SELECT set_config('world.canonical_claim_namespace', ?, TRUE)",
                              NAMESPACE);
                          dsl.fetchOne(
                              "SELECT set_config('world.canonical_claim_tenant', ?, TRUE)",
                              tenant.toString());
                          dsl.fetchOne(
                              "SELECT set_config('world.canonical_claim_request', ?, TRUE)",
                              request);
                          dsl.fetchOne(
                              "SELECT set_config('world.canonical_claim_target', ?, TRUE)",
                              instance.toString());
                          dsl.fetchOne(
                              "SELECT set_config('world.canonical_claim_fence', ?, TRUE)",
                              Objects.requireNonNull(
                                      dsl.fetchValue(
                                          "SELECT world_execution_fence FROM world_canonical_instance_execution WHERE target_namespace=? AND canonical_tenant_id=? AND control_plane_request_id=?",
                                          NAMESPACE,
                                          tenant,
                                          request),
                                      "world_execution_fence must exist for the exact preparation attempt")
                                  .toString());
                          dsl.execute(
                              "UPDATE world_canonical_instance_execution SET execution_claimed=TRUE "
                                  + "WHERE target_namespace=? AND canonical_tenant_id=? AND control_plane_request_id=?",
                              NAMESPACE,
                              tenant,
                              request);
                          for (String[] setting :
                              new String[][] {
                                {"world.canonical_execution_namespace", NAMESPACE},
                                {"world.canonical_execution_tenant", tenant.toString()},
                                {"world.canonical_execution_request", request},
                                {"world.canonical_execution_target", instance.toString()},
                                {
                                  "world.canonical_execution_fence",
                                  Objects.requireNonNull(
                                          dsl.fetchValue(
                                              "SELECT world_execution_fence FROM world_canonical_instance_execution WHERE target_namespace=? AND canonical_tenant_id=? AND control_plane_request_id=?",
                                              NAMESPACE,
                                              tenant,
                                              request),
                                          "world_execution_fence must exist for the exact preparation attempt")
                                      .toString()
                                },
                                {"world.canonical_execution_input_digest", inputDigest}
                              }) {
                            dsl.fetchOne("SELECT set_config(?, ?, TRUE)", setting[0], setting[1]);
                          }
                          dsl.execute(
                              "UPDATE world_canonical_instance_execution "
                                  + "SET operation_state='EXECUTING', active_transaction_id=txid_current() "
                                  + "WHERE target_namespace=? AND canonical_tenant_id=? AND control_plane_request_id=?",
                              NAMESPACE,
                              tenant,
                              request);
                          dsl.fetchOne("SELECT pg_sleep(0.1)");
                          dsl.fetchOne(
                              "SELECT world_require_active_canonical_instance_execution(?, ?)",
                              inputJson,
                              inputDigest);
                          return null;
                        }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .hasMessageContaining("Game Session lease expired before World execution admission");
  }

  private void insertInReservedCanonicalTenant(long tenantKey) {
    dsl.execute(
        "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
            + "control_plane_request_id, launch_descriptor_id, version_id, runtime_flags_json, "
            + "generation_config_revision, release_bundle_id, published_release_bundle_ref, "
            + "version_state_epoch, status) VALUES (?, ?, 71, 'direct-canonical-insert', "
            + "'direct-canonical-descriptor', 83, '{}', 'direct-config', 97, "
            + "'direct-release', 5, 'DIRECT_FIXTURE')",
        tenantKey,
        positiveLong());
  }

  private static String reservedTenantDenial() {
    return "World tenant key is reserved for a canonical authored source; "
        + "no exact transaction execution manifest";
  }

  private long insertLegacyRow(long tenantKey) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                    + "control_plane_request_id, launch_descriptor_id, version_id, "
                    + "runtime_flags_json, generation_config_revision, release_bundle_id, "
                    + "published_release_bundle_ref, version_state_epoch, status) "
                    + "VALUES (?, ?, 71, 'legacy-tenant-row', 'legacy-descriptor', 83, '{}', "
                    + "'legacy-config', 97, 'legacy-release', 5, 'LEGACY_FIXTURE') RETURNING id",
                tenantKey,
                positiveLong()),
            "synthetic unrelated legacy fixture insert returned no row")
        .get("id", Long.class);
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return transaction;
  }

  private static AuthoredWorldSourceEvidence source(UUID tenant) {
    UUID registration = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
    String worldSlug = "world-" + UUID.randomUUID().toString().replace("-", "");
    String display = "Synthetic guard fixture";
    long rowId = positiveLong();
    String sourceTenantKey = "gd-" + tenant.toString().replace("-", "").substring(0, 20);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, tenantSlug, worldSlug, display);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            operation,
            requestDigest,
            tenant,
            tenantSlug,
            worldSlug,
            display,
            rowId,
            sourceTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registration,
        operation,
        requestDigest,
        tenant,
        tenantSlug,
        worldSlug,
        display,
        rowId,
        sourceTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static long positiveLong() {
    return Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
  }
}
