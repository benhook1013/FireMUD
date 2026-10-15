package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.jooq.DSLContext;
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

/**
 * Native PostgreSQL proof for same-top-level-transaction guards across savepoints.
 *
 * <p>Source/version evidence is stipulated through the existing owner repositories. This test
 * proves the SQL boundary and does not claim authenticated Account or Game Design producers.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldSameTransactionGuardSubtransactionPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final String REQUEST_DIGEST = "f".repeat(64);
  private static final String FUTURE_FULL_XID = "9223372036854775807";

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
  @Autowired private WorldAuthoredSourceIntakeRepository intakeRepository;
  @Autowired private WorldDesignPublicationFenceRepository fenceRepository;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void childSubtransactionAttemptAcceptsTopLevelAccountBindingAndInventory() {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "savepoint-world");
    WorldAuthoredVersionIdentityReceipt identity = identity(receipt, 9_000_000_042L);
    WorldDesignPublicationFenceEvidence request =
        evidence(receipt, identity, "savepoint-publication");
    var childTransaction = new TransactionTemplate(transactionManager);
    childTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    childTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

    UUID publicationFence =
        ownerTransaction()
            .execute(
                status -> {
                  var frozen =
                      childTransaction.execute(
                          nestedStatus ->
                              fenceRepository.claimFreeze(
                                  request,
                                  () ->
                                      new WorldDesignPublicationFenceEvidence.Checkpoint(
                                          "savepoint-commit", "c".repeat(64), 3)));
                  assertThat(frozen).isNotNull();
                  var insertedAttempt =
                      dsl.fetchSingle(
                          "SELECT attempt_created_full_xid::text AS created_full_xid, "
                              + "attempt_inserted_full_xid::text AS inserted_full_xid, "
                              + "xmin::text AS tuple_xid, "
                              + "(attempt_created_full_xid = pg_current_xact_id()) AS top_created_stamp, "
                              + "(attempt_inserted_full_xid = pg_current_xact_id()) AS top_inserted_stamp, "
                              + "(xmin::text = (txid_current() % 4294967296)::text) AS top_xmin "
                              + "FROM world_design_publication_fence_attempt WHERE publication_fence = ?",
                          frozen.publicationFence());
                  assertThat(insertedAttempt.get("top_created_stamp", Boolean.class)).isTrue();
                  assertThat(insertedAttempt.get("top_inserted_stamp", Boolean.class)).isTrue();
                  assertThat(insertedAttempt.get("top_xmin", Boolean.class))
                      .as("the tuple was inserted by the committed child transaction")
                      .isFalse();

                  UUID accountOperationId = UUID.randomUUID();
                  UUID accountFenceId = UUID.randomUUID();
                  byte[] accountBytes =
                      "controlled account binding".getBytes(StandardCharsets.UTF_8);
                  dsl.execute(
                      "INSERT INTO world_design_publication_account_binding "
                          + "(publication_fence, account_operation_id, account_fence_id, "
                          + "account_binding_bytes, account_binding_digest) VALUES (?, ?, ?, ?, ?)",
                      frozen.publicationFence(),
                      accountOperationId,
                      accountFenceId,
                      accountBytes,
                      "sha256:" + sha256(accountBytes));

                  byte[] inventoryBytes =
                      "controlled selected inventory".getBytes(StandardCharsets.UTF_8);
                  dsl.execute(
                      "INSERT INTO world_selected_publication_artifact_inventory "
                          + "(publication_fence, target_namespace, canonical_tenant_id, canonical_version_id, "
                          + "local_tenant_key, local_version_key, applied_commit_id, account_operation_id, "
                          + "account_fence_id, inventory_schema_version, inventory_bytes, inventory_digest) "
                          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                      frozen.publicationFence(),
                      request.targetNamespace(),
                      request.canonicalTenantId(),
                      request.canonicalVersionId(),
                      receipt.localTenantKey(),
                      identity.localVersionKey(),
                      frozen.checkpoint().appliedCommitId(),
                      accountOperationId,
                      accountFenceId,
                      inventoryBytes,
                      "sha256:" + sha256(inventoryBytes));
                  return frozen.publicationFence();
                });

    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM world_design_publication_account_binding WHERE publication_fence = ?",
                    publicationFence)
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM world_selected_publication_artifact_inventory WHERE publication_fence = ?",
                    publicationFence)
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM world_design_publication_fence_attempt WHERE publication_fence = ?",
                    publicationFence))
        .hasMessageContaining("World publication-fence attempt history is immutable");
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM world_design_publication_fence_attempt WHERE publication_fence = ?",
                    publicationFence)
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void callerSuppliedFutureStampIsOverwrittenAndCommittedAttemptCannotBeAttachedLater() {
    WorldAuthoredSourceIntakeReceipt receipt = intake(UUID.randomUUID(), "stamp-world");
    WorldAuthoredVersionIdentityReceipt identity = identity(receipt, 9_000_000_043L);
    WorldDesignPublicationFenceEvidence request = evidence(receipt, identity, "future-stamp-probe");
    UUID publicationFence = UUID.randomUUID();
    ownerTransaction()
        .executeWithoutResult(status -> fenceRepository.lockOpen(request.ownerBinding()));

    ownerTransaction()
        .executeWithoutResult(
            status -> {
              dsl.execute(
                  "INSERT INTO world_design_publication_fence_attempt "
                      + "(publication_fence, target_namespace, canonical_tenant_id, local_tenant_key, "
                      + "version_id, intake_operation_id, intake_request_id, source_operation_id, "
                      + "source_evidence_digest, intake_receipt_digest, publication_request_id, "
                      + "request_digest, version_state_epoch, publish_workflow_id, applied_commit_id, "
                      + "content_digest, digest_schema_version, owner_binding_schema_version, "
                      + "canonical_version_id, version_identity_operation_id, game_design_version_id, "
                      + "intake_request_digest, attempt_created_full_xid, attempt_inserted_full_xid) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 3, 1, ?, ?, ?, ?, ?::xid8, ?::xid8)",
                  publicationFence,
                  request.targetNamespace(),
                  request.canonicalTenantId(),
                  receipt.localTenantKey(),
                  identity.localVersionKey(),
                  receipt.operationId(),
                  receipt.intakeRequestId(),
                  receipt.sourceOperationId(),
                  receipt.sourceEvidenceDigest(),
                  receipt.receiptDigest(),
                  request.publicationRequestId(),
                  request.requestDigest(),
                  request.versionStateEpoch(),
                  request.publishWorkflowId(),
                  "caller-stamp-probe",
                  "d".repeat(64),
                  request.canonicalVersionId(),
                  request.versionIdentityOperationId(),
                  request.gameDesignVersionId(),
                  receipt.requestDigest(),
                  FUTURE_FULL_XID,
                  FUTURE_FULL_XID);
              assertThat(
                      dsl.execute(
                          "UPDATE world_design_publication_fence_owner "
                              + "SET owner_freeze_phase = 'FROZEN', current_publication_fence = ? "
                              + "WHERE target_namespace = ? AND canonical_tenant_id = ? "
                              + "AND local_tenant_key = ? AND version_id = ?",
                          publicationFence,
                          request.targetNamespace(),
                          request.canonicalTenantId(),
                          receipt.localTenantKey(),
                          identity.localVersionKey()))
                  .isEqualTo(1);
              var stored =
                  dsl.fetchSingle(
                      "SELECT attempt_created_full_xid::text AS created_full_xid, "
                          + "attempt_inserted_full_xid::text AS inserted_full_xid, "
                          + "pg_current_xact_id()::text AS current_full_xid "
                          + "FROM world_design_publication_fence_attempt WHERE publication_fence = ?",
                      publicationFence);
              assertThat(stored.get("created_full_xid", String.class))
                  .isEqualTo(stored.get("current_full_xid", String.class))
                  .isNotEqualTo(FUTURE_FULL_XID);
              assertThat(stored.get("inserted_full_xid", String.class))
                  .isEqualTo(stored.get("current_full_xid", String.class))
                  .isNotEqualTo(FUTURE_FULL_XID);
            });

    var ownerBeforeLateAttachment =
        dsl.fetchSingle(
            "SELECT owner_freeze_phase, current_publication_fence "
                + "FROM world_design_publication_fence_owner WHERE target_namespace = ? "
                + "AND canonical_tenant_id = ? AND local_tenant_key = ? AND version_id = ?",
            request.targetNamespace(),
            request.canonicalTenantId(),
            receipt.localTenantKey(),
            identity.localVersionKey());
    byte[] accountBytes = "late account binding".getBytes(StandardCharsets.UTF_8);
    assertSqlState(
        () ->
            ownerTransaction()
                .executeWithoutResult(
                    status ->
                        dsl.execute(
                            "INSERT INTO world_design_publication_account_binding "
                                + "(publication_fence, account_operation_id, account_fence_id, "
                                + "account_binding_bytes, account_binding_digest) VALUES (?, ?, ?, ?, ?)",
                            publicationFence,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            accountBytes,
                            "sha256:" + sha256(accountBytes))),
        "P0002",
        "query returned no rows");
    assertThat(
            dsl.fetchSingle(
                "SELECT owner_freeze_phase, current_publication_fence "
                    + "FROM world_design_publication_fence_owner WHERE target_namespace = ? "
                    + "AND canonical_tenant_id = ? AND local_tenant_key = ? AND version_id = ?",
                request.targetNamespace(),
                request.canonicalTenantId(),
                receipt.localTenantKey(),
                identity.localVersionKey()))
        .as("a late binding fails at the installed SELECT INTO STRICT attempt lookup")
        .isEqualTo(ownerBeforeLateAttachment);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM world_design_publication_fence_attempt WHERE publication_fence = ?",
                    publicationFence)
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM world_design_publication_account_binding WHERE publication_fence = ?",
                    publicationFence)
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM world_selected_publication_artifact_inventory WHERE publication_fence = ?",
                    publicationFence)
                .get(0, Long.class))
        .isZero();
  }

  private WorldAuthoredSourceIntakeReceipt intake(UUID tenantId, String worldSlug) {
    String tenantSlug = "tenant-" + tenantId.toString().replace("-", "");
    UUID registrationRequestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    long sourceRowId = (UUID.randomUUID().getMostSignificantBits() & 0x7fffffffL) + 1L;
    String displayName = "Stipulated subtransaction proof world";
    String sourceTenantKey = "gd-row-" + sourceRowId;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, tenantId, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceRowId,
            sourceTenantKey,
            "NEW_GAME_ROW");
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceRowId,
            sourceTenantKey,
            "NEW_GAME_ROW",
            evidenceDigest);
    return ownerTransaction()
        .execute(status -> intakeRepository.acceptFresh(NAMESPACE, UUID.randomUUID(), source));
  }

  private WorldAuthoredVersionIdentityReceipt identity(
      WorldAuthoredSourceIntakeReceipt receipt, long gameDesignVersionId) {
    UUID canonicalVersionId = UUID.randomUUID();
    var stateRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            NAMESPACE,
            UUID.randomUUID(),
            receipt.canonicalTenantId(),
            receipt.worldSlug(),
            receipt.sourceOperationId(),
            receipt.sourceEvidenceDigest(),
            gameDesignVersionId);
    var state =
        AuthoredWorldVersionStateEvidence.create(
            stateRequest,
            receipt.source(),
            canonicalVersionId,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            1L);
    return ownerTransaction()
        .execute(
            status -> new WorldAuthoredVersionIdentityRepository(dsl).acceptFresh(receipt, state));
  }

  private static WorldDesignPublicationFenceEvidence evidence(
      WorldAuthoredSourceIntakeReceipt receipt,
      WorldAuthoredVersionIdentityReceipt identity,
      String publicationRequestId) {
    var binding =
        PublicationDigestRequestBinding.full(
            receipt.canonicalTenantId().toString(),
            Long.toString(identity.gameDesignVersionId()),
            publicationRequestId);
    return new WorldDesignPublicationFenceEvidence(
        NAMESPACE,
        receipt.canonicalTenantId(),
        identity.canonicalVersionId(),
        identity.operationId(),
        identity.gameDesignVersionId(),
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.requestDigest(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest(),
        receipt.receiptDigest(),
        publicationRequestId,
        REQUEST_DIGEST,
        1L,
        binding.derivedWorkflowIdentity());
  }

  private TransactionTemplate ownerTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setReadOnly(false);
    return transaction;
  }

  private static void assertSqlState(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state, String message) {
    assertThatThrownBy(action)
        .satisfies(
            failure -> {
              Throwable cause = failure;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(PSQLException.class);
              PSQLException postgresFailure = (PSQLException) cause;
              assertThat(postgresFailure.getSQLState()).isEqualTo(state);
              assertThat(postgresFailure.getMessage()).contains(message);
            });
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
