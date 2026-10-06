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
  void v35PreservesReservedTenantDenialAndDoesNotExposePrepareFunctionToPublic() {
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
                            + "AND p.proname = 'world_prepare_canonical_instance' "
                            + "AND acl.grantee = 0 AND acl.privilege_type = 'EXECUTE')"),
                    "Prepare-function access query returned no result")
                .get(0, Boolean.class))
        .isFalse();
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
