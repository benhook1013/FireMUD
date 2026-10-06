package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.client.OwnerApprovedAccountTenantAssociation;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovedLegacyTenantAssociationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String NAMESPACE = "firemud";
  private static final String MANIFEST_DIGEST = "sha256:" + "b".repeat(64);

  private final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();

  private DriverManagerDataSource dataSource;
  private DSLContext dsl;
  private TransactionTemplate transaction;
  private ApprovedLegacyTenantAssociationRepository associations;
  private LegacyTenantSourceEvidence sourceEvidence;

  @BeforeAll
  void migrate() {
    postgres.start();
    String schema = "approved_legacy_association_" + UUID.randomUUID().toString().replace("-", "");
    dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    sourceEvidence = new LegacyTenantSourceEvidence(dsl);
    associations = new ApprovedLegacyTenantAssociationRepository(dsl, sourceEvidence, NAMESPACE);
  }

  @AfterAll
  void stopPostgres() {
    postgres.stop();
  }

  @Test
  void exactOwnerClaimUsesSourceCaptureAndSurvivesPayloadExpiryWithoutRenewal() {
    long legacyId = 89001;
    insertLegacySource(insertAccount("association-live"), legacyId, Instant.now());
    var projection = sourceEvidence.projection(legacyId);
    var evidence = evidence(legacyId, projection);
    var association =
        transaction.execute(status -> associations.importOwnerApproved(legacyId, evidence));

    assertThat(association.canonicalTenantId()).isEqualTo(evidence.canonicalTenantId());
    assertThat(association.sourceCapturedAt()).isEqualTo(projection.capturedAt());
    assertThat(
            dsl.fetchOne(
                "SELECT operation_id FROM account_approved_legacy_tenant_association_payload "
                    + "WHERE operation_id = ?",
                evidence.operationId()))
        .isNotNull();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name = 'account_approved_legacy_tenant_associations' "
                            + "AND column_name IN ('account_evidence_digest', 'manifest_digest')"))
                .get(0, Long.class))
        .isZero();

    OwnerApprovedAccountTenantAssociation changedCapture =
        withCapture(evidence, evidence.sourceCapturedAt().plusNanos(1_000));
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> associations.importOwnerApproved(legacyId, changedCapture)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs");

    OwnerApprovedAccountTenantAssociation changedApproval =
        new OwnerApprovedAccountTenantAssociation(
            evidence.legacyAccountTenantId(),
            evidence.canonicalTenantId(),
            evidence.sourceLegacyGameTenantId(),
            evidence.sourceGameRowId(),
            evidence.accountEvidenceDigest(),
            evidence.sourceCapturedAt(),
            evidence.operationId(),
            "sha256:" + "c".repeat(64),
            evidence.manifestSignature(),
            evidence.targetNamespace(),
            evidence.signerKeyId(),
            evidence.approvedBy(),
            evidence.approvalReference(),
            evidence.signedAt(),
            evidence.operationEntryCount(),
            evidence.manifestSchemaVersion());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> associations.importOwnerApproved(legacyId, changedApproval)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");

    long duplicateLegacyId = 89004;
    insertLegacySource(insertAccount("association-duplicate"), duplicateLegacyId, Instant.now());
    var duplicateProjection = sourceEvidence.projection(duplicateLegacyId);
    var duplicateEvidence =
        evidence(duplicateLegacyId, duplicateProjection, evidence.canonicalTenantId());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        associations.importOwnerApproved(duplicateLegacyId, duplicateEvidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another claim");
    assertThat(associations.findByLegacyTenantId(duplicateLegacyId)).isEmpty();
    assertThat(
            dsl.fetchOne(
                "SELECT operation_id FROM account_approved_legacy_tenant_association_payload "
                    + "WHERE operation_id = ?",
                duplicateEvidence.operationId()))
        .isNull();

    assertThatThrownBy(
            () -> dsl.execute("TRUNCATE account_approved_legacy_tenant_associations CASCADE"))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_approved_legacy_tenant_associations "
                        + "SET canonical_tenant_id = ? WHERE legacy_tenant_id = ?",
                    UUID.randomUUID(),
                    legacyId))
        .hasMessageContaining("immutable");

    long expiredLegacyId = 89002;
    Instant expiredAt =
        Instant.now().minus(Duration.ofDays(31)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    insertLegacySource(insertAccount("association-expired"), expiredLegacyId, expiredAt);
    var expiredProjection = sourceEvidence.projection(expiredLegacyId);
    var expiredEvidence = evidence(expiredLegacyId, expiredProjection);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> associations.importOwnerApproved(expiredLegacyId, expiredEvidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expired");

    long retainedLegacyId = 89003;
    Instant retainedCapture = Instant.now().minus(Duration.ofDays(31));
    insertLegacySource(insertAccount("association-retained"), retainedLegacyId, retainedCapture);
    var retainedProjection = sourceEvidence.projection(retainedLegacyId);
    var retainedEvidence = evidence(retainedLegacyId, retainedProjection);
    insertExpiredClaimAndPayload(retainedEvidence);

    long referencedLegacyId = 89005;
    long referencedAccountId = insertAccount("association-referenced");
    Instant referencedCapture = Instant.now().minus(Duration.ofDays(31));
    insertLegacySource(referencedAccountId, referencedLegacyId, referencedCapture);
    var referencedProjection = sourceEvidence.projection(referencedLegacyId);
    var referencedEvidence = evidence(referencedLegacyId, referencedProjection);
    insertExpiredClaimAndPayload(referencedEvidence);
    String referencedScopeId =
        insertApprovedRetainedReferences(referencedAccountId, referencedEvidence);

    assertThat(associations.deleteExpiredApprovalPayloads(10)).isEqualTo(1);
    assertThat(
            dsl.fetchOne(
                "SELECT operation_id FROM account_approved_legacy_tenant_association_payload "
                    + "WHERE operation_id = ?",
                retainedEvidence.operationId()))
        .isNull();

    assertThat(
            dsl.fetchOne(
                "SELECT operation_id FROM account_approved_legacy_tenant_association_payload "
                    + "WHERE operation_id = ?",
                referencedEvidence.operationId()))
        .isNotNull();
    AccountConnectScopeRepository scopes = newConnectScopeRepository();
    var scopeReadback =
        transaction.execute(
            status ->
                scopes
                    .findCanonicalEvidenceByTokenHash(
                        AccountJoinDigest.tokenHash(referencedScopeId))
                    .orElseThrow());
    assertThat(scopeReadback.tenantProvenance().sourceOperationId())
        .isEqualTo(referencedEvidence.operationId());
    assertThat(scopeReadback.tenantProvenance().digest()).isEqualTo(MANIFEST_DIGEST);

    AccountTenantIdentityResolver resolver =
        new AccountTenantIdentityResolver(associations, NAMESPACE);
    assertThat(resolver.resolve(retainedEvidence.canonicalTenantId()).canonicalTenantId())
        .isEqualTo(retainedEvidence.canonicalTenantId());

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> associations.importOwnerApproved(retainedLegacyId, retainedEvidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expired");
    assertThat(
            dsl.fetchOne(
                "SELECT operation_id FROM account_approved_legacy_tenant_association_payload "
                    + "WHERE operation_id = ?",
                retainedEvidence.operationId()))
        .isNull();
  }

  @Test
  void sourceProjectionRejectsConflictMixedCaptureAndImportRejectsFutureCapture() {
    long conflictLegacyId = 89101;
    long conflictAccountId = insertAccount("association-conflicting");
    insertLegacySource(conflictAccountId, conflictLegacyId, Instant.now());
    dsl.execute(
        "UPDATE account_legacy_tenant_sources SET disposition = 'CONFLICT' WHERE account_id = ?",
        conflictAccountId);
    assertThatThrownBy(() -> sourceEvidence.projection(conflictLegacyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicting");

    long mixedLegacyId = 89102;
    insertLegacySource(insertAccount("association-mixed-a"), mixedLegacyId, Instant.now());
    insertLegacySource(
        insertAccount("association-mixed-b"), mixedLegacyId, Instant.now().plusSeconds(1));
    assertThatThrownBy(() -> sourceEvidence.projection(mixedLegacyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("capture times are mixed");

    long futureLegacyId = 89103;
    insertLegacySource(
        insertAccount("association-future"), futureLegacyId, Instant.now().plusSeconds(60));
    var futureProjection = sourceEvidence.projection(futureLegacyId);
    var futureEvidence = evidence(futureLegacyId, futureProjection);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> associations.importOwnerApproved(futureLegacyId, futureEvidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("future");
  }

  @Test
  void approvalPayloadReferenceInsertAndCleanupRaceCannotCommitDanglingEvidence() throws Exception {
    long writerFirstLegacyId = 89201;
    long writerFirstAccountId = insertAccount("association-writer-first");
    insertLegacySource(
        writerFirstAccountId, writerFirstLegacyId, Instant.now().minus(Duration.ofDays(31)));
    var writerFirstEvidence =
        evidence(writerFirstLegacyId, sourceEvidence.projection(writerFirstLegacyId));
    insertExpiredClaimAndPayload(writerFirstEvidence);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch writerFirstInserted = new CountDownLatch(1);
    CountDownLatch allowWriterFirstCommit = new CountDownLatch(1);
    String writerFirstApplication =
        "approval_writer_first_" + UUID.randomUUID().toString().replace("-", "");
    try {
      Future<?> writerFirst =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        setLocalApplicationName(writerFirstApplication);
                        insertApprovedMembership(writerFirstAccountId, writerFirstEvidence);
                        writerFirstInserted.countDown();
                        awaitLatch(allowWriterFirstCommit, "writer-first Account reference");
                      }));
      assertThat(writerFirstInserted.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(associations.deleteExpiredApprovalPayloads(1)).isZero();
      allowWriterFirstCommit.countDown();
      writerFirst.get(5, TimeUnit.SECONDS);
      assertThat(payloadExists(writerFirstEvidence.operationId())).isTrue();
      assertThat(membershipReferenceCount(writerFirstEvidence.operationId())).isEqualTo(1L);
      assertThat(associations.deleteExpiredApprovalPayloads(1)).isZero();
    } finally {
      allowWriterFirstCommit.countDown();
    }

    long deleteFirstLegacyId = 89202;
    long deleteFirstAccountId = insertAccount("association-delete-first");
    insertLegacySource(
        deleteFirstAccountId, deleteFirstLegacyId, Instant.now().minus(Duration.ofDays(31)));
    var deleteFirstEvidence =
        evidence(deleteFirstLegacyId, sourceEvidence.projection(deleteFirstLegacyId));
    insertExpiredClaimAndPayload(deleteFirstEvidence);

    CountDownLatch payloadDeletedInTransaction = new CountDownLatch(1);
    CountDownLatch allowCleanupCommit = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    String deleteFirstApplication =
        "approval_delete_first_" + UUID.randomUUID().toString().replace("-", "");
    Future<Integer> cleanup =
        executor.submit(
            () ->
                transaction.execute(
                    status -> {
                      int removed = associations.deleteExpiredApprovalPayloads(1);
                      payloadDeletedInTransaction.countDown();
                      awaitLatch(allowCleanupCommit, "payload cleanup commit");
                      return removed;
                    }));
    try {
      assertThat(payloadDeletedInTransaction.await(5, TimeUnit.SECONDS)).isTrue();
      Future<RuntimeException> writer =
          executor.submit(
              () -> {
                try {
                  transaction.executeWithoutResult(
                      status -> {
                        setLocalApplicationName(deleteFirstApplication);
                        writerStarted.countDown();
                        insertApprovedMembership(deleteFirstAccountId, deleteFirstEvidence);
                      });
                  return null;
                } catch (RuntimeException failure) {
                  return failure;
                }
              });
      assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(awaitTransactionLock(deleteFirstApplication)).isTrue();

      allowCleanupCommit.countDown();
      assertThat(cleanup.get(5, TimeUnit.SECONDS)).isEqualTo(1);
      RuntimeException writeFailure = writer.get(5, TimeUnit.SECONDS);
      assertThat(writeFailure).isNotNull();
      assertThat(rootCauseMessage(writeFailure))
          .contains("account_tenant_membership_approval_payload_fk");
      assertThat(payloadExists(deleteFirstEvidence.operationId())).isFalse();
      assertThat(membershipReferenceCount(deleteFirstEvidence.operationId())).isZero();
    } finally {
      allowCleanupCommit.countDown();
      executor.shutdownNow();
    }
  }

  private long insertAccount(String username) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                username,
                username + "@example.test",
                "test-hash"))
        .get(0, Long.class);
  }

  private String insertApprovedRetainedReferences(
      long accountId, OwnerApprovedAccountTenantAssociation evidence) {
    UUID accountUuid =
        Objects.requireNonNull(
                dsl.fetchOne("SELECT account_uuid FROM accounts WHERE id = ?", accountId))
            .get(0, UUID.class);
    String connectScopeId = "approval-retained-scope-" + UUID.randomUUID();
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            evidence.legacyAccountTenantId(),
            TenantProvenanceKind.APPROVED_RETAINED,
            evidence.operationId(),
            evidence.manifestDigest());
    CanonicalJoinScopeV2 scope =
        new CanonicalJoinScopeV2(
            connectScopeId,
            accountUuid,
            evidence.canonicalTenantId(),
            UUID.randomUUID(),
            "retained-tenant",
            "world",
            "private",
            UUID.randomUUID(),
            "SHARED",
            UUID.randomUUID(),
            1L,
            1L,
            Instant.now().minusSeconds(120).toString(),
            Instant.now().minusSeconds(60).toString());
    AccountConnectScopeRepository scopes = newConnectScopeRepository();
    transaction.executeWithoutResult(
        status -> {
          insertApprovedMembership(accountId, evidence);
          scopes.insertCanonical(accountId, scope, provenance);
        });
    return connectScopeId;
  }

  private AccountConnectScopeRepository newConnectScopeRepository() {
    return new AccountConnectScopeRepository(
        dsl,
        new AccountTenantIdentityResolver(associations, NAMESPACE),
        new FreshTenantIdentityAssociationRepository(dsl, NAMESPACE));
  }

  private void insertApprovedMembership(
      long accountId, OwnerApprovedAccountTenantAssociation evidence) {
    dsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, tenant_uuid, tenant_provenance_kind, "
            + "tenant_source_operation_id, tenant_provenance_digest, "
            + "gameplay_admission_allowed, lifecycle_state, membership_version, "
            + "membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, ?, 'APPROVED_RETAINED', ?, ?, TRUE, 'ACTIVE', 1, 1, 'EXPLICIT_JOIN')",
        accountId,
        evidence.legacyAccountTenantId(),
        evidence.canonicalTenantId(),
        evidence.operationId(),
        evidence.manifestDigest());
  }

  private boolean payloadExists(UUID operationId) {
    return dsl.fetchOne(
            "SELECT 1 FROM account_approved_legacy_tenant_association_payload WHERE operation_id = ?",
            operationId)
        != null;
  }

  private long membershipReferenceCount(UUID operationId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM account_tenant_membership "
                    + "WHERE approved_tenant_payload_operation_id = ?",
                operationId))
        .get(0, Long.class);
  }

  private void setLocalApplicationName(String applicationName) {
    dsl.execute("SET LOCAL application_name = '" + applicationName + "'");
  }

  private boolean awaitTransactionLock(String applicationName) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      long waiting =
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT COUNT(*) FROM pg_stat_activity "
                          + "WHERE application_name = ? AND wait_event_type = 'Lock'",
                      applicationName))
              .get(0, Long.class);
      if (waiting > 0) {
        return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private static String rootCauseMessage(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());
  }

  private static void awaitLatch(CountDownLatch latch, String operation) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for " + operation);
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for " + operation, failure);
    }
  }

  private void insertLegacySource(long accountId, long legacyTenantId, Instant capturedAt) {
    LocalDateTime capturedUtc =
        LocalDateTime.ofInstant(capturedAt, ZoneOffset.UTC)
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    dsl.execute(
        "INSERT INTO account_legacy_tenant_sources "
            + "(account_id, legacy_tenant_id, profile_tenant_count, matching_profile_count, "
            + "disposition, captured_at) VALUES (?, ?, 0, 0, 'UNVERIFIED', ?)",
        accountId,
        legacyTenantId,
        capturedUtc);
  }

  private OwnerApprovedAccountTenantAssociation evidence(
      long legacyId, LegacyTenantSourceEvidence.SourceProjection projection) {
    return evidence(legacyId, projection, UUID.randomUUID());
  }

  private OwnerApprovedAccountTenantAssociation evidence(
      long legacyId,
      LegacyTenantSourceEvidence.SourceProjection projection,
      UUID canonicalTenantId) {
    return new OwnerApprovedAccountTenantAssociation(
        legacyId,
        canonicalTenantId,
        "legacy-game-" + legacyId,
        legacyId,
        projection.digest(),
        projection.capturedAt(),
        UUID.randomUUID(),
        MANIFEST_DIGEST,
        Base64.getEncoder().encodeToString(new byte[64]),
        NAMESPACE,
        "signer-key",
        "approved-operator",
        "approval-reference-" + legacyId,
        "2026-10-01T00:00:00Z",
        1,
        1);
  }

  private OwnerApprovedAccountTenantAssociation withCapture(
      OwnerApprovedAccountTenantAssociation source, Instant capturedAt) {
    return new OwnerApprovedAccountTenantAssociation(
        source.legacyAccountTenantId(),
        source.canonicalTenantId(),
        source.sourceLegacyGameTenantId(),
        source.sourceGameRowId(),
        source.accountEvidenceDigest(),
        capturedAt,
        source.operationId(),
        source.manifestDigest(),
        source.manifestSignature(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.operationEntryCount(),
        source.manifestSchemaVersion());
  }

  private void insertExpiredClaimAndPayload(OwnerApprovedAccountTenantAssociation evidence) {
    boolean claimCaptureWindowDisabled = false;
    boolean payloadExpiryGuardDisabled = false;
    try {
      dsl.execute(
          "ALTER TABLE account_approved_legacy_tenant_associations "
              + "DISABLE TRIGGER account_approved_tenant_claim_capture_window");
      claimCaptureWindowDisabled = true;
      try {
        dsl.execute(
            "ALTER TABLE account_approved_legacy_tenant_association_payload "
                + "DISABLE TRIGGER account_approved_tenant_payload_immutable_expiry");
        payloadExpiryGuardDisabled = true;
        dsl.execute(
            "INSERT INTO account_approved_legacy_tenant_associations "
                + "(legacy_tenant_id, canonical_tenant_id, source_legacy_game_tenant_id, "
                + "source_game_row_id, operation_id, target_namespace, source_captured_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE))",
            evidence.legacyAccountTenantId(),
            evidence.canonicalTenantId(),
            evidence.sourceLegacyGameTenantId(),
            evidence.sourceGameRowId(),
            evidence.operationId(),
            evidence.targetNamespace(),
            evidence.sourceCapturedAt());
        insertPayload(evidence);
      } finally {
        if (payloadExpiryGuardDisabled) {
          dsl.execute(
              "ALTER TABLE account_approved_legacy_tenant_association_payload "
                  + "ENABLE TRIGGER account_approved_tenant_payload_immutable_expiry");
        }
      }
    } finally {
      if (claimCaptureWindowDisabled) {
        dsl.execute(
            "ALTER TABLE account_approved_legacy_tenant_associations "
                + "ENABLE TRIGGER account_approved_tenant_claim_capture_window");
      }
    }
  }

  private void insertPayload(OwnerApprovedAccountTenantAssociation evidence) {
    dsl.execute(
        "INSERT INTO account_approved_legacy_tenant_association_payload "
            + "(operation_id, account_evidence_digest, manifest_digest, signer_key_id, "
            + "approved_by, approval_reference, signed_at, manifest_signature, "
            + "operation_entry_count, manifest_schema_version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        evidence.operationId(),
        evidence.accountEvidenceDigest(),
        evidence.manifestDigest(),
        evidence.signerKeyId(),
        evidence.approvedBy(),
        evidence.approvalReference(),
        evidence.signedAt(),
        evidence.manifestSignature(),
        evidence.operationEntryCount(),
        evidence.manifestSchemaVersion());
  }
}
