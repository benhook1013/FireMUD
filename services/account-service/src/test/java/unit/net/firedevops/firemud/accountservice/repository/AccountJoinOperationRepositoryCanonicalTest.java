package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinTerminalProof;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountJoinOperationRepositoryCanonicalTest {
  private static final String STREAM =
      "account:auth-authority:v1:membership/f4df3b0a-5fc6-4f86-9efc-0f053103b200/"
          + "4347218b-6914-4d50-a5ce-1b4836034f45";
  private static final String SHA256 = "sha256:" + "1".repeat(64);

  @Test
  void terminalProofBindsFirstSequenceAndExactMembershipRevision() {
    CanonicalJoinTerminalProof proof = validProof();

    assertThat(proof.eventStreamKey()).isEqualTo(STREAM);
    assertThat(proof.eventSequence()).isEqualTo(1L);
    assertThat(proof.membershipId()).isEqualTo(73L);
    assertThat(proof.membershipVersion()).isEqualTo(2L);
    assertThat(proof.membershipAuthorityGeneration()).isEqualTo(1L);
  }

  @Test
  void terminalProofRetainsLaterMembershipEventAndRevisionWithoutClaimingFirstJoin() {
    var later = proof(3L, 4L);
    assertThat(later.eventSequence()).isEqualTo(3L);
    assertThat(later.membershipVersion()).isEqualTo(4L);
    // Actual first-JOIN writes retain their distinct exact 1/2/1 repository and SQL checks.
  }

  @Test
  void firstJoinRejectsNonFirstEventSequenceBeforeOwnerStorageReads() {
    DSLContext dsl = mock(DSLContext.class);
    AccountConnectScopeRepository scopes = mock(AccountConnectScopeRepository.class);
    var repository = new AccountJoinOperationRepository(dsl, scopes);

    assertThatThrownBy(
            () ->
                repository.commitCanonicalFirstJoin(
                    "request-v2", mock(CanonicalJoinScopeV2.class), "caller", proof(2L, 2L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exact first-JOIN terminal proof required");
    verifyNoInteractions(dsl, scopes);
  }

  @Test
  void firstJoinRejectsNonFirstMembershipRevisionBeforeOwnerStorageReads() {
    DSLContext dsl = mock(DSLContext.class);
    AccountConnectScopeRepository scopes = mock(AccountConnectScopeRepository.class);
    var repository = new AccountJoinOperationRepository(dsl, scopes);

    assertThatThrownBy(
            () ->
                repository.commitCanonicalFirstJoin(
                    "request-v2", mock(CanonicalJoinScopeV2.class), "caller", proof(1L, 3L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exact first-JOIN terminal proof required");
    verifyNoInteractions(dsl, scopes);
  }

  @Test
  void terminalProofRejectsNonpositiveEventOrMembershipRevision() {
    assertThatThrownBy(() -> proof(0L, 2L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> proof(1L, 0L)).isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalJoinTerminalProof proof(long sequence, long version) {
    return new CanonicalJoinTerminalProof(
        STREAM,
        sequence,
        "event-1",
        SHA256,
        UUID.fromString("75c70a9e-beb4-3ee2-9d9d-50e28f1123d6"),
        SHA256,
        Instant.parse("2026-10-04T00:00:00Z"),
        5L,
        73L,
        version,
        1L);
  }

  @Test
  void boundPendingDiagnosticsRemainSeparateFromTheAvailablePolicy() {
    var unavailable =
        evidence(true, "PENDING", null, "UNAVAILABLE", "ENTITLEMENT_UNAVAILABLE", null, null);
    assertThat(unavailable.entitlementAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(unavailable.entitlementVersion()).isEqualTo(5);
    assertThat(unavailable.lastAttemptAuthorityAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(unavailable.lastAttemptFailureCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThatThrownBy(() -> evidence(true, "PENDING", null, "UNAVAILABLE", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                evidence(true, "PENDING", null, "AVAILABLE", "ENTITLEMENT_UNAVAILABLE", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failedEvidenceMayRetainPolicyButCannotCarrySuccessProofOrRetryableOutcome() {
    var prePolicy =
        evidence(false, "FAILED", "CONNECT_SCOPE_INVALID", "NOT_EVALUATED", null, null, null);
    var afterPolicy =
        evidence(true, "FAILED", "IDEMPOTENCY_CONFLICT", "AVAILABLE", null, null, null);
    assertThat(prePolicy.requestDigest()).isNull();
    assertThat(afterPolicy.entitlementVersion()).isEqualTo(5);
    assertThat(afterPolicy.membershipId()).isNull();
    assertThat(afterPolicy.terminalProof()).isNull();
    assertThatThrownBy(
            () -> evidence(true, "FAILED", "AUTH_UNAVAILABLE", "AVAILABLE", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                evidence(
                    true, "FAILED", "CONNECT_SCOPE_INVALID", "AVAILABLE", null, validProof(), 73L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                evidence(
                    true,
                    "FAILED",
                    "CONNECT_SCOPE_INVALID",
                    "UNAVAILABLE",
                    "ENTITLEMENT_UNAVAILABLE",
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Controlled journal content, not caller authentication or entitlement source evidence. */
  @Test
  void committedNullOutcomeIsClassifiedAsInconsistentTerminalEvidence() {
    var proof = validProof();
    var proofWithoutAudit =
        new CanonicalJoinTerminalProof(
            proof.eventStreamKey(),
            proof.eventSequence(),
            proof.eventId(),
            proof.eventDigest(),
            null,
            null,
            null,
            proof.entitlementVersion(),
            proof.membershipId(),
            proof.membershipVersion(),
            proof.membershipAuthorityGeneration());
    assertThat(evidence(true, "COMMITTED", "JOINED", "AVAILABLE", null, proof, 73L)).isNotNull();
    assertThat(
            evidence(
                true, "COMMITTED", "ALREADY_ACTIVE", "AVAILABLE", null, proofWithoutAudit, 73L))
        .isNotNull();
    assertThatThrownBy(
            () -> evidence(true, "COMMITTED", null, "AVAILABLE", null, proofWithoutAudit, 73L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Canonical JOIN terminal evidence is inconsistent");
  }

  @Test
  void nullCommitOutcomeIsClassifiedBeforeOwnerStorageReads() {
    DSLContext dsl = mock(DSLContext.class);
    AccountConnectScopeRepository scopes = mock(AccountConnectScopeRepository.class);
    var repository = new AccountJoinOperationRepository(dsl, scopes);
    boolean active = TransactionSynchronizationManager.isActualTransactionActive();
    boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    try {
      assertThatThrownBy(
              () ->
                  repository.commitCanonicalJoin(
                      "request-v2", mock(CanonicalJoinScopeV2.class), "caller", null, validProof()))
          .isInstanceOf(CanonicalJoinOperationConflictException.class)
          .hasMessage("Canonical JOIN outcome/proof conflict");
      verifyNoInteractions(dsl, scopes);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(active);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
    }
  }

  private static CanonicalJoinOperationEvidence evidence(
      boolean bound,
      String status,
      String outcome,
      String attemptAvailability,
      String attemptFailure,
      CanonicalJoinTerminalProof proof,
      Long membershipId) {
    UUID account = UUID.fromString("f4df3b0a-5fc6-4f86-9efc-0f053103b200");
    UUID tenant = UUID.fromString("4347218b-6914-4d50-a5ce-1b4836034f45");
    UUID realm = UUID.fromString("33333333-3333-4333-8333-333333333333");
    var scope =
        new CanonicalJoinScopeV2(
            "controlled-canonical-scope",
            account,
            tenant,
            realm,
            "tenant",
            "world",
            "production",
            realm,
            "SHARED",
            realm,
            7,
            3,
            "2026-11-02T00:00:00Z",
            "2026-11-02T00:02:00Z");
    var retained =
        new CanonicalConnectScopeEvidence(
            AccountJoinDigest.tokenHash(scope.connectScopeId()),
            17,
            "PUBLIC_PRODUCTION",
            account,
            tenant,
            realm,
            "tenant",
            "world",
            "production",
            realm,
            "SHARED",
            realm,
            7,
            3,
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt(),
            2,
            AccountJoinDigest.scopeV2(scope),
            new VerifiedTenantProvenance(
                null, TenantProvenanceKind.FRESH_GAME_DESIGN, realm, SHA256));
    return new CanonicalJoinOperationEvidence(
        "request-1",
        17,
        "caller-1",
        retained.scopeTokenHash(),
        retained.scopeDigest(),
        2,
        2,
        2,
        AccountJoinDigest.intentV2("request-1", scope, "caller-1"),
        bound ? "AVAILABLE" : "NOT_EVALUATED",
        bound ? true : null,
        bound ? 5L : null,
        bound ? 2 : null,
        bound
            ? AccountJoinDigest.requestV2(
                scope, "caller-1", AccountJoinDigest.EntitlementAvailabilityV2.AVAILABLE, true, 5L)
            : null,
        attemptAvailability,
        attemptFailure,
        false,
        status,
        outcome,
        membershipId,
        proof == null ? null : proof.membershipVersion(),
        proof == null ? null : proof.membershipAuthorityGeneration(),
        proof,
        retained);
  }

  private static CanonicalJoinTerminalProof validProof() {
    return new CanonicalJoinTerminalProof(
        STREAM,
        1L,
        "event-1",
        SHA256,
        UUID.fromString("75c70a9e-beb4-3ee2-9d9d-50e28f1123d6"),
        SHA256,
        Instant.parse("2026-10-04T00:00:00Z"),
        5L,
        73L,
        2L,
        1L);
  }
}
