package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Retains provisional Account membership-transition receipts without claiming complete authority
 * proof.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountMembershipTransitionReceiptRepository {
  private static final String MEMBERSHIP_JOINED = "MEMBERSHIP_JOINED";
  private static final String MEMBERSHIP_REACTIVATED = "MEMBERSHIP_REACTIVATED";
  private static final String MEMBERSHIP_LEFT = "MEMBERSHIP_LEFT";

  private final DSLContext dsl;

  public AccountMembershipTransitionReceiptRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Appends one provisional receipt inside the surrounding Account membership transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public MembershipTransitionReceipt appendTransition(
      AccountTenantMembership membership, String transitionType, String requestId) {
    requireOwnerTransaction();
    if (membership == null
        || membership.getAccount() == null
        || membership.getAccount().getId() == null
        || membership.getId() == null) {
      throw new IllegalArgumentException("A persisted Account membership is required");
    }
    long accountId = membership.getAccount().getId();
    long tenantId = membership.getTenantId();
    long membershipId = membership.getId();
    requireTransitionState(membership, transitionType);

    String streamKey = MembershipTransitionReceiptDigest.receiptStreamKey(accountId, tenantId);
    UUID receiptId = MembershipTransitionReceiptDigest.receiptIdForRequest(requestId);
    Long sequence =
        dsl.resultQuery(
                "INSERT INTO account_membership_transition_receipt_stream_heads "
                    + "(account_id, tenant_id, receipt_stream_key, last_receipt_sequence, updated_at) "
                    + "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP) "
                    + "ON CONFLICT (account_id, tenant_id) DO UPDATE SET "
                    + "last_receipt_sequence = "
                    + "account_membership_transition_receipt_stream_heads.last_receipt_sequence + 1, "
                    + "updated_at = CURRENT_TIMESTAMP "
                    + "RETURNING last_receipt_sequence",
                accountId,
                tenantId,
                streamKey)
            .fetchOne(0, Long.class);
    if (sequence == null || sequence <= 0L) {
      throw new IllegalStateException("Account membership receipt sequence was not allocated");
    }
    String digest =
        MembershipTransitionReceiptDigest.transitionDigest(
            streamKey,
            sequence,
            receiptId,
            transitionType,
            requestId,
            accountId,
            tenantId,
            membershipId,
            membership.getLifecycleState(),
            membership.isGameplayAdmissionAllowed(),
            membership.getMembershipVersion(),
            membership.getMembershipAuthorityGeneration(),
            membership.getAuthorityProvenance());
    int inserted =
        dsl.execute(
            "INSERT INTO account_membership_transition_receipts "
                + "(receipt_stream_key, receipt_sequence, account_id, tenant_id, evidence_status, "
                + "transition_type, request_id, membership_id, membership_lifecycle_state, "
                + "gameplay_admission_allowed, membership_version, "
                + "membership_authority_generation, authority_provenance, receipt_id, receipt_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            streamKey,
            sequence,
            accountId,
            tenantId,
            MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
            transitionType,
            requestId,
            membershipId,
            membership.getLifecycleState(),
            membership.isGameplayAdmissionAllowed(),
            membership.getMembershipVersion(),
            membership.getMembershipAuthorityGeneration(),
            membership.getAuthorityProvenance(),
            receiptId,
            digest);
    if (inserted != 1) {
      throw new IllegalStateException("Account membership transition receipt was not inserted");
    }
    return new MembershipTransitionReceipt(
        streamKey,
        sequence,
        receiptId,
        digest,
        MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
        transitionType,
        requestId,
        membershipId);
  }

  /**
   * Reads and validates the latest provisional receipt. Empty does not prove a canonical
   * sequence-zero stream; existing rows without a receipt fail closed.
   */
  // A contradictory read is an expected fail-closed JOIN/reconciliation outcome. The caller
  // records that outcome in its existing transaction; this read performs no mutation to undo.
  @Transactional(readOnly = true, noRollbackFor = IllegalStateException.class)
  public Optional<MembershipTransitionReceipt> findLatestReceipt(long accountId, long tenantId) {
    ReceiptSnapshot snapshot = readLatestReceiptSnapshot(accountId, tenantId);
    if (snapshot == null) {
      return Optional.empty();
    }
    MembershipTransitionReceipt receipt = snapshot.receipt();
    if (receipt == null) {
      return Optional.empty();
    }
    Long currentMembershipId = snapshot.currentMembershipId();
    if (currentMembershipId != null
        && (currentMembershipId.longValue() != receipt.membershipId()
            || !receiptStateMatches(
                receipt.transitionType(),
                snapshot.currentLifecycleState(),
                snapshot.currentAdmissionAllowed())
            || snapshot.currentMembershipVersion() != snapshot.receiptMembershipVersion()
            || snapshot.currentAuthorityGeneration() != snapshot.receiptAuthorityGeneration()
            || !"EXPLICIT_JOIN".equals(snapshot.currentAuthorityProvenance()))) {
      throw new IllegalStateException(
          "Account membership row does not match its latest provisional receipt");
    }
    return Optional.of(receipt);
  }

  /**
   * Reads one immutable transition receipt by its globally unique request ID without requiring it
   * to remain the current receipt. This supports exact, non-authorizing replay after a later
   * membership transition; callers must separately prove the matching event and audit envelope.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<TransitionReceiptEvidence> findByRequestId(String requestId) {
    requireOwnerTransaction();
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("Account membership request ID is required");
    }
    Record row =
        dsl.fetchOne(
            "SELECT r.receipt_stream_key, r.receipt_sequence, r.account_id, r.tenant_id, "
                + "h.receipt_stream_key AS head_stream_key, "
                + "h.last_receipt_sequence AS head_receipt_sequence, r.evidence_status, "
                + "r.transition_type, r.request_id, r.membership_id, r.membership_lifecycle_state, "
                + "r.gameplay_admission_allowed, r.membership_version, "
                + "r.membership_authority_generation, r.authority_provenance, r.receipt_id, r.receipt_digest "
                + "FROM account_membership_transition_receipts r "
                + "JOIN account_membership_transition_receipt_stream_heads h "
                + "ON h.account_id = r.account_id AND h.tenant_id = r.tenant_id "
                + "WHERE r.request_id = ?",
            requestId);
    if (row == null) {
      return Optional.empty();
    }
    String streamKey = row.get("receipt_stream_key", String.class);
    Long sequence = row.get("receipt_sequence", Long.class);
    Long headSequence = row.get("head_receipt_sequence", Long.class);
    String headStreamKey = row.get("head_stream_key", String.class);
    Long accountId = row.get("account_id", Long.class);
    Long tenantId = row.get("tenant_id", Long.class);
    String evidenceStatus = row.get("evidence_status", String.class);
    String transitionType = row.get("transition_type", String.class);
    String retainedRequestId = row.get("request_id", String.class);
    Long membershipId = row.get("membership_id", Long.class);
    String lifecycleState = row.get("membership_lifecycle_state", String.class);
    Boolean admissionAllowed = row.get("gameplay_admission_allowed", Boolean.class);
    Long membershipVersion = row.get("membership_version", Long.class);
    Long authorityGeneration = row.get("membership_authority_generation", Long.class);
    String authorityProvenance = row.get("authority_provenance", String.class);
    UUID receiptId = row.get("receipt_id", UUID.class);
    String receiptDigest = row.get("receipt_digest", String.class);
    if (sequence == null
        || sequence <= 0L
        || headSequence == null
        || headSequence < sequence
        || accountId == null
        || accountId <= 0L
        || tenantId == null
        || tenantId <= 0L
        || !requestId.equals(retainedRequestId)
        || membershipId == null
        || membershipId <= 0L
        || lifecycleState == null
        || admissionAllowed == null
        || membershipVersion == null
        || membershipVersion <= 0L
        || authorityGeneration == null
        || authorityGeneration <= 0L
        || authorityProvenance == null
        || receiptId == null
        || receiptDigest == null
        || !MembershipTransitionReceiptDigest.receiptStreamKey(accountId, tenantId)
            .equals(streamKey)
        || !streamKey.equals(headStreamKey)
        || !MembershipTransitionReceiptDigest.EVIDENCE_STATUS.equals(evidenceStatus)
        || !receiptId.equals(MembershipTransitionReceiptDigest.receiptIdForRequest(requestId))) {
      throw new IllegalStateException(
          "Account membership transition receipt scope is inconsistent");
    }
    String expectedDigest =
        MembershipTransitionReceiptDigest.transitionDigest(
            streamKey,
            sequence,
            receiptId,
            transitionType,
            requestId,
            accountId,
            tenantId,
            membershipId,
            lifecycleState,
            admissionAllowed,
            membershipVersion,
            authorityGeneration,
            authorityProvenance);
    if (!expectedDigest.equals(receiptDigest)) {
      throw new IllegalStateException(
          "Account membership transition receipt digest is inconsistent");
    }
    MembershipTransitionReceipt receipt =
        new MembershipTransitionReceipt(
            streamKey,
            sequence,
            receiptId,
            receiptDigest,
            evidenceStatus,
            transitionType,
            requestId,
            membershipId);
    return Optional.of(
        new TransitionReceiptEvidence(
            receipt,
            accountId,
            tenantId,
            lifecycleState,
            admissionAllowed,
            membershipVersion,
            authorityGeneration,
            authorityProvenance));
  }

  /**
   * Fail-closes a new row when prior history exists or no versioned restoration path is defined.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public void assertNewMembershipTransitionCanStart(long accountId, long tenantId) {
    ReceiptSnapshot snapshot = readLatestReceiptSnapshot(accountId, tenantId);
    if (snapshot == null || snapshot.currentMembershipId() != null) {
      throw new IllegalStateException("New Account membership transition scope is inconsistent");
    }
    if (snapshot.receipt() != null) {
      throw new IllegalStateException(
          "Retained Account membership history has no supported restoration path");
    }
    if (hasPriorMembershipHistory(accountId, tenantId)) {
      throw new IllegalStateException(
          "Prior Account membership history exists without a provisional transition receipt");
    }
  }

  /** Requires positive retained history before reactivating an existing inactive membership. */
  @Transactional(
      propagation = Propagation.MANDATORY,
      readOnly = true,
      noRollbackFor = IllegalStateException.class)
  public MembershipTransitionReceipt requireInactiveMembershipHistory(
      AccountTenantMembership membership) {
    if (membership == null
        || membership.getId() == null
        || membership.getAccount() == null
        || membership.getAccount().getId() == null
        || membership.getTenantId() <= 0L
        || !"INACTIVE".equals(membership.getLifecycleState())) {
      throw new IllegalArgumentException("A persisted inactive Account membership is required");
    }
    long accountId = membership.getAccount().getId();
    long tenantId = membership.getTenantId();
    ReceiptSnapshot snapshot = readLatestReceiptSnapshot(accountId, tenantId);
    if (snapshot == null
        || snapshot.currentMembershipId() == null
        || !snapshot.currentMembershipId().equals(membership.getId())
        || !"INACTIVE".equals(snapshot.currentLifecycleState())
        || snapshot.currentAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(snapshot.currentAuthorityProvenance())
        || snapshot.receipt() == null
        || snapshot.receipt().membershipId() != membership.getId()
        || snapshot.currentMembershipVersion() < snapshot.receiptMembershipVersion()
        || snapshot.currentAuthorityGeneration() < snapshot.receiptAuthorityGeneration()) {
      throw new IllegalStateException(
          "Inactive Account membership lacks matching positive transition receipt history");
    }
    return snapshot.receipt();
  }

  private ReceiptSnapshot readLatestReceiptSnapshot(long accountId, long tenantId) {
    if (accountId <= 0L || tenantId <= 0L) {
      throw new IllegalArgumentException("Account and tenant IDs must be positive");
    }
    Record row =
        dsl.fetchOne(
            "SELECT h.receipt_stream_key, h.last_receipt_sequence, "
                + "m.id AS current_membership_id, "
                + "m.lifecycle_state AS current_lifecycle_state, "
                + "m.gameplay_admission_allowed AS current_admission_allowed, "
                + "m.membership_version AS current_membership_version, "
                + "m.membership_authority_generation AS current_authority_generation, "
                + "m.authority_provenance AS current_authority_provenance, "
                + "r.receipt_sequence AS row_receipt_sequence, r.account_id AS receipt_account_id, "
                + "r.tenant_id AS receipt_tenant_id, r.evidence_status, r.transition_type, "
                + "r.request_id, r.membership_id AS receipt_membership_id, "
                + "r.membership_lifecycle_state AS receipt_lifecycle_state, "
                + "r.gameplay_admission_allowed AS receipt_admission_allowed, "
                + "r.membership_version AS receipt_membership_version, "
                + "r.membership_authority_generation AS receipt_authority_generation, "
                + "r.authority_provenance AS receipt_authority_provenance, "
                + "r.receipt_id, r.receipt_digest "
                + "FROM (SELECT 1) scope "
                + "LEFT JOIN account_membership_transition_receipt_stream_heads h "
                + "ON h.account_id = ? AND h.tenant_id = ? "
                + "LEFT JOIN account_tenant_membership m "
                + "ON m.account_id = ? AND m.tenant_id = ? "
                + "LEFT JOIN account_membership_transition_receipts r "
                + "ON r.receipt_stream_key = h.receipt_stream_key "
                + "AND r.receipt_sequence = h.last_receipt_sequence ",
            accountId,
            tenantId,
            accountId,
            tenantId);
    if (row == null) {
      return null;
    }
    Long sequence = row.get("last_receipt_sequence", Long.class);
    Long currentMembershipId = row.get("current_membership_id", Long.class);
    if (sequence == null) {
      if (currentMembershipId != null) {
        throw new IllegalStateException(
            "Account membership exists without a provisional transition receipt");
      }
      return new ReceiptSnapshot(null, null, null, false, 0L, 0L, null, 0L, 0L);
    }
    Long rowSequence = row.get("row_receipt_sequence", Long.class);
    UUID receiptId = row.get("receipt_id", UUID.class);
    String receiptDigest = row.get("receipt_digest", String.class);
    if (sequence <= 0L
        || !sequence.equals(rowSequence)
        || receiptId == null
        || receiptDigest == null) {
      throw new IllegalStateException(
          "Account membership receipt head lacks its exact retained receipt");
    }

    String streamKey = row.get("receipt_stream_key", String.class);
    Long receiptAccountId = row.get("receipt_account_id", Long.class);
    Long receiptTenantId = row.get("receipt_tenant_id", Long.class);
    String evidenceStatus = row.get("evidence_status", String.class);
    String transitionType = row.get("transition_type", String.class);
    String requestId = row.get("request_id", String.class);
    Long membershipId = row.get("receipt_membership_id", Long.class);
    String lifecycleState = row.get("receipt_lifecycle_state", String.class);
    Boolean admissionAllowed = row.get("receipt_admission_allowed", Boolean.class);
    Long membershipVersion = row.get("receipt_membership_version", Long.class);
    Long authorityGeneration = row.get("receipt_authority_generation", Long.class);
    String authorityProvenance = row.get("receipt_authority_provenance", String.class);
    if (!MembershipTransitionReceiptDigest.receiptStreamKey(accountId, tenantId).equals(streamKey)
        || !Long.valueOf(accountId).equals(receiptAccountId)
        || !Long.valueOf(tenantId).equals(receiptTenantId)
        || !MembershipTransitionReceiptDigest.EVIDENCE_STATUS.equals(evidenceStatus)
        || membershipId == null
        || lifecycleState == null
        || admissionAllowed == null
        || membershipVersion == null
        || authorityGeneration == null
        || authorityProvenance == null
        || requestId == null
        || !receiptId.equals(MembershipTransitionReceiptDigest.receiptIdForRequest(requestId))) {
      throw new IllegalStateException("Account membership receipt scope is inconsistent");
    }
    String expectedDigest =
        MembershipTransitionReceiptDigest.transitionDigest(
            streamKey,
            sequence,
            receiptId,
            transitionType,
            requestId,
            accountId,
            tenantId,
            membershipId,
            lifecycleState,
            admissionAllowed,
            membershipVersion,
            authorityGeneration,
            authorityProvenance);
    if (!expectedDigest.equals(receiptDigest)) {
      throw new IllegalStateException("Account membership receipt digest is inconsistent");
    }
    return new ReceiptSnapshot(
        new MembershipTransitionReceipt(
            streamKey,
            sequence,
            receiptId,
            receiptDigest,
            evidenceStatus,
            transitionType,
            requestId,
            membershipId),
        currentMembershipId,
        row.get("current_lifecycle_state", String.class),
        Boolean.TRUE.equals(row.get("current_admission_allowed", Boolean.class)),
        row.get("current_membership_version", Long.class) == null
            ? 0L
            : row.get("current_membership_version", Long.class),
        row.get("current_authority_generation", Long.class) == null
            ? 0L
            : row.get("current_authority_generation", Long.class),
        row.get("current_authority_provenance", String.class),
        membershipVersion,
        authorityGeneration);
  }

  private boolean hasPriorMembershipHistory(long accountId, long tenantId) {
    // A minimized JOIN audit no longer identifies its account, so it conservatively blocks this
    // tenant's new stream when no retained receipt proves the membership history.
    Boolean hasHistory =
        (Boolean)
            dsl.fetchValue(
                "SELECT EXISTS ("
                    + "SELECT 1 FROM account_legacy_membership_sources "
                    + "WHERE account_id = ? AND tenant_id = ? "
                    + "UNION ALL SELECT 1 FROM account_join_operations "
                    + "WHERE account_id = ? AND tenant_id = ? "
                    + "AND status = 'COMMITTED' AND outcome IN ('JOINED', 'ALREADY_ACTIVE') "
                    + "UNION ALL SELECT 1 FROM account_audit_outbox "
                    + "WHERE scope = 'tenant' AND tenant_id = ? "
                    + "AND producer_service = 'account-service' "
                    + "AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION' "
                    + "AND (payload IS NULL OR payload::jsonb ->> 'accountId' = CAST(? AS TEXT))"
                    + ")",
                accountId,
                tenantId,
                accountId,
                tenantId,
                tenantId,
                accountId);
    return Boolean.TRUE.equals(hasHistory);
  }

  private static void requireTransitionState(
      AccountTenantMembership membership, String transitionType) {
    if (MEMBERSHIP_JOINED.equals(transitionType) || MEMBERSHIP_REACTIVATED.equals(transitionType)) {
      if (!"ACTIVE".equals(membership.getLifecycleState())
          || !membership.isGameplayAdmissionAllowed()
          || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
        throw new IllegalArgumentException("JOIN receipt requires its committed active state");
      }
      return;
    }
    if (MEMBERSHIP_LEFT.equals(transitionType)) {
      if (!"INACTIVE".equals(membership.getLifecycleState())
          || membership.isGameplayAdmissionAllowed()
          || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
        throw new IllegalArgumentException(
            "LEAVE receipt requires its committed inactive non-admitting state");
      }
      return;
    }
    throw new IllegalArgumentException("Membership transition type is unsupported");
  }

  private static boolean receiptStateMatches(
      String transitionType, String lifecycleState, boolean gameplayAdmissionAllowed) {
    return ((MEMBERSHIP_JOINED.equals(transitionType)
                || MEMBERSHIP_REACTIVATED.equals(transitionType))
            && "ACTIVE".equals(lifecycleState)
            && gameplayAdmissionAllowed)
        || (MEMBERSHIP_LEFT.equals(transitionType)
            && "INACTIVE".equals(lifecycleState)
            && !gameplayAdmissionAllowed);
  }

  private void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account membership receipt access requires an active owner transaction");
    }
  }

  private record ReceiptSnapshot(
      MembershipTransitionReceipt receipt,
      Long currentMembershipId,
      String currentLifecycleState,
      boolean currentAdmissionAllowed,
      long currentMembershipVersion,
      long currentAuthorityGeneration,
      String currentAuthorityProvenance,
      long receiptMembershipVersion,
      long receiptAuthorityGeneration) {}

  /** Immutable receipt bytes plus the state needed to bind it to its original event. */
  public record TransitionReceiptEvidence(
      MembershipTransitionReceipt receipt,
      long accountId,
      long tenantId,
      String lifecycleState,
      boolean gameplayAdmissionAllowed,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String authorityProvenance) {}
}
