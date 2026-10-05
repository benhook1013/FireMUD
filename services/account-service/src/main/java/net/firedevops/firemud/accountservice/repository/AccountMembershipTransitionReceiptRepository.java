package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.CanonicalMembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
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
  private static final String ZERO_SHA256_DIGEST =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";

  private final DSLContext dsl;

  public AccountMembershipTransitionReceiptRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Appends a UUID-qualified provisional transition receipt on the shared receipt-head counter. */
  @Transactional(propagation = Propagation.MANDATORY)
  public CanonicalMembershipTransitionReceipt appendCanonicalTransition(
      UUID accountUuid, UUID tenantUuid, String transitionType, String requestId) {
    requireOwnerWriteTransaction();
    UUID canonicalAccountUuid =
        MembershipTransitionReceiptDigest.requireCanonicalUuid(accountUuid, "accountUuid");
    UUID canonicalTenantUuid =
        MembershipTransitionReceiptDigest.requireCanonicalUuid(tenantUuid, "tenantUuid");
    String canonicalRequestId = MembershipTransitionReceiptDigest.requireRequestIdV2(requestId);
    lockReceiptRequestFence(canonicalRequestId);
    CanonicalMembershipState membership =
        lockCanonicalMembership(canonicalAccountUuid, canonicalTenantUuid);
    if (membership == null) {
      throw new IllegalStateException("Canonical Account membership does not exist");
    }
    requireTransitionState(membership, transitionType);

    CanonicalReceiptHead head = lockReceiptHead(membership);
    Record requestRow = readReceiptRequest(canonicalRequestId);
    if (requestRow != null) {
      Short version = requestRow.get("receipt_version", Short.class);
      if (version == null || version != 2) {
        throw new IllegalStateException(
            "Account membership request ID is already retained in another receipt version");
      }
      if (head == null
          || !Objects.equals(requestRow.get("receipt_head_id", Long.class), head.receiptHeadId())) {
        throw new IllegalStateException(
            "Canonical Account membership request ID belongs to another receipt scope");
      }
      CanonicalMembershipTransitionReceipt original =
          decodeCanonicalReceipt(requestRow, membership, head);
      if (!sameCanonicalTransition(original, membership, transitionType, canonicalRequestId)) {
        throw new IllegalStateException(
            "Account membership request ID is already bound to different transition evidence");
      }
      return original;
    }

    long nextSequence;
    if (head == null) {
      if (("APPROVED_RETAINED".equals(membership.tenantProvenanceKind())
              && hasPriorMembershipHistory(membership.accountId(), membership.tenantId()))
          || hasPriorCanonicalOperationOrAudit(membership, canonicalRequestId)
          || hasPriorCanonicalMembershipEventHistory(membership, canonicalRequestId)) {
        throw new IllegalStateException(
            "Account membership history exists without its original receipt head");
      }
      requireFirstCanonicalJoinEvidence(membership, canonicalRequestId, transitionType);
      nextSequence = 1L;
    } else {
      requireHeadMatchesMembership(head, membership);
      if (head.lastReceiptSequence() <= 0L) {
        throw new IllegalStateException("Account membership receipt head counter is malformed");
      }
      Record latest = readHeadReceipt(head.receiptHeadId(), head.lastReceiptSequence());
      if (latest == null) {
        throw new IllegalStateException(
            "Account membership receipt head lacks its exact latest receipt");
      }
      validateReceiptForAppend(latest, membership, head);
      if (head.lastReceiptSequence() == Long.MAX_VALUE) {
        throw new IllegalStateException("Account membership receipt sequence is exhausted");
      }
      nextSequence = head.lastReceiptSequence() + 1L;
    }

    CanonicalMembershipTransitionReceipt receipt =
        createCanonicalReceipt(membership, nextSequence, transitionType, canonicalRequestId);
    if (head == null) {
      String storageKey =
          "APPROVED_RETAINED".equals(membership.tenantProvenanceKind())
              ? MembershipTransitionReceiptDigest.receiptStreamKey(
                  membership.accountId(), membership.tenantId())
              : receipt.receiptStreamKey();
      insertCanonicalHead(membership, storageKey, nextSequence);
    } else if (head.accountUuid() == null) {
      bindCanonicalHeadAndAdvance(head, membership, nextSequence);
    } else {
      advanceCanonicalHead(head, nextSequence);
    }
    insertCanonicalReceipt(membership, receipt);
    return receipt;
  }

  /** Reads one immutable canonical receipt by request ID, preserving historical receipt state. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CanonicalMembershipTransitionReceipt> findCanonicalByRequestId(String requestId) {
    requireOwnerWriteTransaction();
    String canonicalRequestId = MembershipTransitionReceiptDigest.requireRequestIdV2(requestId);
    Record row = readReceiptRequest(canonicalRequestId);
    if (row == null) {
      return Optional.empty();
    }
    Short version = row.get("receipt_version", Short.class);
    if (version == null || version == 1) {
      return Optional.empty();
    }
    if (version != 2) {
      throw new IllegalStateException("Account membership receipt representation is unsupported");
    }
    UUID accountUuid = row.get("account_uuid", UUID.class);
    UUID tenantUuid = row.get("tenant_uuid", UUID.class);
    CanonicalMembershipState membership = lockCanonicalMembership(accountUuid, tenantUuid);
    if (membership == null) {
      throw new IllegalStateException(
          "Canonical Account membership receipt has no persisted membership source");
    }
    Long headId = row.get("receipt_head_id", Long.class);
    CanonicalReceiptHead head = lockReceiptHeadById(headId);
    if (head == null) {
      throw new IllegalStateException("Canonical Account membership receipt head is absent");
    }
    return Optional.of(decodeCanonicalReceipt(row, membership, head));
  }

  /** Reads and validates the current latest canonical receipt without treating it as authority. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CanonicalMembershipTransitionReceipt> findLatestCanonicalReceipt(
      UUID accountUuid, UUID tenantUuid) {
    requireOwnerWriteTransaction();
    UUID canonicalAccountUuid =
        MembershipTransitionReceiptDigest.requireCanonicalUuid(accountUuid, "accountUuid");
    UUID canonicalTenantUuid =
        MembershipTransitionReceiptDigest.requireCanonicalUuid(tenantUuid, "tenantUuid");
    CanonicalMembershipState membership =
        lockCanonicalMembership(canonicalAccountUuid, canonicalTenantUuid);
    if (membership == null) {
      return Optional.empty();
    }
    CanonicalReceiptHead head = lockReceiptHead(membership);
    if (head == null) {
      throw new IllegalStateException(
          "Canonical Account membership exists without retained transition receipt history");
    }
    requireHeadMatchesMembership(head, membership);
    if (head.lastReceiptSequence() <= 0L) {
      throw new IllegalStateException("Account membership receipt head counter is malformed");
    }
    Record row = readHeadReceipt(head.receiptHeadId(), head.lastReceiptSequence());
    if (row == null) {
      throw new IllegalStateException(
          "Account membership receipt head lacks its exact latest receipt");
    }
    Short version = row.get("receipt_version", Short.class);
    if (version == null) {
      throw new IllegalStateException("Account membership receipt version is malformed");
    }
    if (version == 1) {
      validateVersion1Receipt(row, membership, head);
      if (!Objects.equals(
              row.get("membership_lifecycle_state", String.class), membership.lifecycleState())
          || !Objects.equals(
              row.get("gameplay_admission_allowed", Boolean.class),
              membership.gameplayAdmissionAllowed())
          || !Objects.equals(
              row.get("membership_version", Long.class), membership.membershipVersion())
          || !Objects.equals(
              row.get("membership_authority_generation", Long.class),
              membership.membershipAuthorityGeneration())
          || !Objects.equals(
              row.get("authority_provenance", String.class), membership.authorityProvenance())) {
        throw new IllegalStateException(
            "Canonical Account membership row does not match its latest V1 provisional receipt");
      }
      return Optional.empty();
    }
    if (version != 2) {
      throw new IllegalStateException("Account membership receipt representation is unsupported");
    }
    CanonicalMembershipTransitionReceipt receipt = decodeCanonicalReceipt(row, membership, head);
    if (!sameCanonicalCurrentState(receipt, membership)) {
      throw new IllegalStateException(
          "Canonical Account membership row does not match its latest provisional receipt");
    }
    return Optional.of(receipt);
  }

  private CanonicalMembershipState lockCanonicalMembership(UUID accountUuid, UUID tenantUuid) {
    Record account =
        dsl.fetchOne(
            "SELECT id, account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", accountUuid);
    if (account == null
        || account.get("id", Long.class) == null
        || account.get("id", Long.class) <= 0L
        || !accountUuid.equals(account.get("account_uuid", UUID.class))) {
      throw new IllegalStateException("Canonical Account UUID is not an exact persisted Account");
    }
    long accountId = account.get("id", Long.class);
    Record row =
        dsl.fetchOne(
            "SELECT id, account_id, tenant_id, tenant_uuid, tenant_provenance_kind, "
                + "tenant_source_operation_id, tenant_provenance_digest, lifecycle_state, "
                + "gameplay_admission_allowed, membership_version, "
                + "membership_authority_generation, authority_provenance "
                + "FROM account_tenant_membership "
                + "WHERE account_id = ? AND tenant_uuid = ? FOR UPDATE",
            accountId,
            tenantUuid);
    if (row == null) {
      return null;
    }
    Long membershipId = row.get("id", Long.class);
    Long storedAccountId = row.get("account_id", Long.class);
    Long tenantId = row.get("tenant_id", Long.class);
    UUID storedTenantUuid = row.get("tenant_uuid", UUID.class);
    String tenantKind = row.get("tenant_provenance_kind", String.class);
    UUID sourceOperationId = row.get("tenant_source_operation_id", UUID.class);
    String sourceDigest = row.get("tenant_provenance_digest", String.class);
    String lifecycleState = row.get("lifecycle_state", String.class);
    Boolean admissionAllowed = row.get("gameplay_admission_allowed", Boolean.class);
    Long membershipVersion = row.get("membership_version", Long.class);
    Long authorityGeneration = row.get("membership_authority_generation", Long.class);
    String authorityProvenance = row.get("authority_provenance", String.class);
    if (membershipId == null
        || membershipId <= 0L
        || !Long.valueOf(accountId).equals(storedAccountId)
        || !tenantUuid.equals(storedTenantUuid)
        || lifecycleState == null
        || admissionAllowed == null
        || membershipVersion == null
        || membershipVersion <= 0L
        || authorityGeneration == null
        || authorityGeneration <= 0L
        || authorityProvenance == null) {
      throw new IllegalStateException("Persisted canonical Account membership is malformed");
    }
    CanonicalMembershipState membership =
        new CanonicalMembershipState(
            accountId,
            membershipId,
            accountUuid,
            tenantUuid,
            tenantId,
            tenantKind,
            sourceOperationId,
            sourceDigest,
            lifecycleState,
            admissionAllowed,
            membershipVersion,
            authorityGeneration,
            authorityProvenance);
    requireExactTenantSource(membership);
    return membership;
  }

  private void requireExactTenantSource(CanonicalMembershipState membership) {
    MembershipTransitionReceiptDigest.requireCanonicalUuid(
        membership.tenantSourceOperationId(), "tenantSourceOperationId");
    MembershipTransitionReceiptDigest.requireSha256Digest(
        membership.tenantProvenanceDigest(), "tenantProvenanceDigest");
    Boolean matches;
    if ("APPROVED_RETAINED".equals(membership.tenantProvenanceKind())) {
      if (membership.tenantId() == null || membership.tenantId() <= 0L) {
        throw new IllegalStateException(
            "Approved retained canonical membership has no positive legacy tenant key");
      }
      matches =
          (Boolean)
              dsl.fetchValue(
                  "SELECT EXISTS ("
                      + "SELECT 1 FROM account_approved_legacy_tenant_associations retained "
                      + "JOIN account_canonical_tenant_identity_claims claim "
                      + "ON claim.canonical_tenant_id = retained.canonical_tenant_id "
                      + "AND claim.identity_kind = retained.identity_kind "
                      + "AND claim.source_operation_id = retained.operation_id "
                      + "AND claim.source_account_legacy_tenant_id = retained.legacy_tenant_id "
                      + "AND claim.source_target_namespace = retained.target_namespace "
                      + "AND claim.source_creation_request_id IS NULL "
                      + "AND claim.source_request_digest IS NULL "
                      + "AND claim.source_game_row_id = retained.source_game_row_id "
                      + "AND claim.source_game_tenant_key = retained.source_legacy_game_tenant_id "
                      + "AND claim.source_provenance_kind IS NULL "
                      + "AND claim.source_evidence_digest = retained.account_evidence_digest "
                      + "AND claim.source_manifest_digest = retained.manifest_digest "
                      + "WHERE retained.legacy_tenant_id = ? "
                      + "AND retained.canonical_tenant_id = ? "
                      + "AND retained.operation_id = ? "
                      + "AND retained.manifest_digest = ?)",
                  membership.tenantId(),
                  membership.tenantUuid(),
                  membership.tenantSourceOperationId(),
                  membership.tenantProvenanceDigest());
    } else if ("FRESH_GAME_DESIGN".equals(membership.tenantProvenanceKind())) {
      if (membership.tenantId() != null) {
        throw new IllegalStateException(
            "Fresh Game Design canonical membership must not carry a legacy tenant key");
      }
      matches =
          (Boolean)
              dsl.fetchValue(
                  "SELECT EXISTS ("
                      + "SELECT 1 FROM account_fresh_tenant_identity_associations fresh "
                      + "JOIN account_canonical_tenant_identity_claims claim "
                      + "ON claim.canonical_tenant_id = fresh.canonical_tenant_id "
                      + "AND claim.identity_kind = fresh.identity_kind "
                      + "AND claim.source_operation_id = fresh.operation_id "
                      + "AND claim.source_target_namespace = fresh.target_namespace "
                      + "AND claim.source_creation_request_id = fresh.creation_request_id "
                      + "AND claim.source_request_digest = fresh.request_digest "
                      + "AND claim.source_game_row_id = fresh.source_game_row_id "
                      + "AND claim.source_game_tenant_key = fresh.source_game_tenant_key "
                      + "AND claim.source_provenance_kind = fresh.provenance_kind "
                      + "AND claim.source_evidence_digest = fresh.evidence_digest "
                      + "AND claim.source_account_legacy_tenant_id IS NULL "
                      + "AND claim.source_manifest_digest IS NULL "
                      + "WHERE fresh.canonical_tenant_id = ? "
                      + "AND fresh.operation_id = ? "
                      + "AND fresh.evidence_digest = ?)",
                  membership.tenantUuid(),
                  membership.tenantSourceOperationId(),
                  membership.tenantProvenanceDigest());
    } else {
      throw new IllegalStateException("Canonical membership tenant provenance is unsupported");
    }
    if (!Boolean.TRUE.equals(matches)) {
      throw new IllegalStateException(
          "Canonical Account membership has no exact immutable tenant-source evidence");
    }
  }

  private CanonicalReceiptHead lockReceiptHead(CanonicalMembershipState membership) {
    Record row =
        dsl.fetchOne(
            "SELECT receipt_head_id, account_id, tenant_id, receipt_stream_key, "
                + "last_receipt_sequence, account_uuid, tenant_uuid, tenant_provenance_kind, "
                + "tenant_source_operation_id, tenant_provenance_digest "
                + "FROM account_membership_transition_receipt_stream_heads "
                + "WHERE account_id = ? AND tenant_uuid = ? FOR UPDATE",
            membership.accountId(),
            membership.tenantUuid());
    if (row == null && "APPROVED_RETAINED".equals(membership.tenantProvenanceKind())) {
      row =
          dsl.fetchOne(
              "SELECT receipt_head_id, account_id, tenant_id, receipt_stream_key, "
                  + "last_receipt_sequence, account_uuid, tenant_uuid, tenant_provenance_kind, "
                  + "tenant_source_operation_id, tenant_provenance_digest "
                  + "FROM account_membership_transition_receipt_stream_heads "
                  + "WHERE account_id = ? AND tenant_id = ? FOR UPDATE",
              membership.accountId(),
              membership.tenantId());
    }
    return row == null ? null : mapHead(row);
  }

  private CanonicalReceiptHead lockReceiptHeadById(Long receiptHeadId) {
    if (receiptHeadId == null || receiptHeadId <= 0L) {
      throw new IllegalStateException("Canonical Account membership receipt head ID is malformed");
    }
    Record row =
        dsl.fetchOne(
            "SELECT receipt_head_id, account_id, tenant_id, receipt_stream_key, "
                + "last_receipt_sequence, account_uuid, tenant_uuid, tenant_provenance_kind, "
                + "tenant_source_operation_id, tenant_provenance_digest "
                + "FROM account_membership_transition_receipt_stream_heads "
                + "WHERE receipt_head_id = ? FOR UPDATE",
            receiptHeadId);
    return row == null ? null : mapHead(row);
  }

  private static CanonicalReceiptHead mapHead(Record row) {
    Long id = row.get("receipt_head_id", Long.class);
    Long accountId = row.get("account_id", Long.class);
    Long tenantId = row.get("tenant_id", Long.class);
    String streamKey = row.get("receipt_stream_key", String.class);
    Long sequence = row.get("last_receipt_sequence", Long.class);
    if (id == null
        || id <= 0L
        || accountId == null
        || accountId <= 0L
        || streamKey == null
        || sequence == null
        || sequence <= 0L) {
      throw new IllegalStateException("Account membership receipt head is malformed");
    }
    return new CanonicalReceiptHead(
        id,
        accountId,
        tenantId,
        streamKey,
        sequence,
        row.get("account_uuid", UUID.class),
        row.get("tenant_uuid", UUID.class),
        row.get("tenant_provenance_kind", String.class),
        row.get("tenant_source_operation_id", UUID.class),
        row.get("tenant_provenance_digest", String.class));
  }

  private static void requireHeadMatchesMembership(
      CanonicalReceiptHead head, CanonicalMembershipState membership) {
    if (head.accountId() != membership.accountId()
        || !Objects.equals(head.tenantId(), membership.tenantId())) {
      throw new IllegalStateException(
          "Account membership receipt head storage scope is inconsistent");
    }
    if (head.accountUuid() == null) {
      if (!"APPROVED_RETAINED".equals(membership.tenantProvenanceKind())
          || head.tenantUuid() != null
          || head.tenantProvenanceKind() != null
          || head.tenantSourceOperationId() != null
          || head.tenantProvenanceDigest() != null
          || !MembershipTransitionReceiptDigest.receiptStreamKey(
                  membership.accountId(), membership.tenantId())
              .equals(head.receiptStreamKey())) {
        throw new IllegalStateException(
            "Unbound Account membership receipt head is not an exact retained V1 head");
      }
      return;
    }
    if (!membership.accountUuid().equals(head.accountUuid())
        || !membership.tenantUuid().equals(head.tenantUuid())
        || !membership.tenantProvenanceKind().equals(head.tenantProvenanceKind())
        || !membership.tenantSourceOperationId().equals(head.tenantSourceOperationId())
        || !membership.tenantProvenanceDigest().equals(head.tenantProvenanceDigest())) {
      throw new IllegalStateException(
          "Canonical Account membership receipt head differs from immutable tenant identity");
    }
    String expectedStorageKey =
        "APPROVED_RETAINED".equals(membership.tenantProvenanceKind())
            ? MembershipTransitionReceiptDigest.receiptStreamKey(
                membership.accountId(), membership.tenantId())
            : MembershipTransitionReceiptDigest.receiptStreamKeyV2(
                membership.accountUuid(), membership.tenantUuid());
    if (!expectedStorageKey.equals(head.receiptStreamKey())) {
      throw new IllegalStateException(
          "Canonical Account membership receipt head changed its retained storage key");
    }
  }

  private Record readReceiptRequest(String requestId) {
    return dsl.fetchOne(
        "SELECT receipt_stream_key, receipt_sequence, account_id, tenant_id, receipt_head_id, "
            + "receipt_version, account_uuid, tenant_uuid, tenant_provenance_kind, "
            + "tenant_source_operation_id, tenant_provenance_digest, evidence_status, "
            + "transition_type, request_id, membership_id, membership_lifecycle_state, "
            + "gameplay_admission_allowed, membership_version, "
            + "membership_authority_generation, authority_provenance, receipt_id, receipt_digest "
            + "FROM account_membership_transition_receipts WHERE request_id = ?",
        requestId);
  }

  private Record readHeadReceipt(long receiptHeadId, long sequence) {
    return dsl.fetchOne(
        "SELECT receipt_stream_key, receipt_sequence, account_id, tenant_id, receipt_head_id, "
            + "receipt_version, account_uuid, tenant_uuid, tenant_provenance_kind, "
            + "tenant_source_operation_id, tenant_provenance_digest, evidence_status, "
            + "transition_type, request_id, membership_id, membership_lifecycle_state, "
            + "gameplay_admission_allowed, membership_version, "
            + "membership_authority_generation, authority_provenance, receipt_id, receipt_digest "
            + "FROM account_membership_transition_receipts "
            + "WHERE receipt_head_id = ? AND receipt_sequence = ?",
        receiptHeadId,
        sequence);
  }

  private CanonicalMembershipTransitionReceipt decodeCanonicalReceipt(
      Record row, CanonicalMembershipState membership, CanonicalReceiptHead head) {
    Short version = row.get("receipt_version", Short.class);
    Long receiptHeadId = row.get("receipt_head_id", Long.class);
    Long sequence = row.get("receipt_sequence", Long.class);
    Long accountId = row.get("account_id", Long.class);
    Long tenantId = row.get("tenant_id", Long.class);
    Long membershipId = row.get("membership_id", Long.class);
    Long membershipVersion = row.get("membership_version", Long.class);
    Long authorityGeneration = row.get("membership_authority_generation", Long.class);
    UUID accountUuid = row.get("account_uuid", UUID.class);
    UUID tenantUuid = row.get("tenant_uuid", UUID.class);
    String streamKey = row.get("receipt_stream_key", String.class);
    String evidenceStatus = row.get("evidence_status", String.class);
    String transitionType = row.get("transition_type", String.class);
    String requestId = row.get("request_id", String.class);
    String lifecycleState = row.get("membership_lifecycle_state", String.class);
    Boolean admissionAllowed = row.get("gameplay_admission_allowed", Boolean.class);
    String authorityProvenance = row.get("authority_provenance", String.class);
    String tenantKind = row.get("tenant_provenance_kind", String.class);
    UUID sourceOperationId = row.get("tenant_source_operation_id", UUID.class);
    String sourceDigest = row.get("tenant_provenance_digest", String.class);
    UUID receiptId = row.get("receipt_id", UUID.class);
    String receiptDigest = row.get("receipt_digest", String.class);
    if (version == null
        || version != 2
        || receiptHeadId == null
        || receiptHeadId != head.receiptHeadId()
        || sequence == null
        || sequence <= 0L
        || sequence > head.lastReceiptSequence()
        || accountId == null
        || accountId != membership.accountId()
        || !Objects.equals(tenantId, membership.tenantId())
        || membershipId == null
        || membershipId != membership.membershipId()
        || !membership.accountUuid().equals(accountUuid)
        || !membership.tenantUuid().equals(tenantUuid)
        || membershipVersion == null
        || membershipVersion <= 0L
        || authorityGeneration == null
        || authorityGeneration <= 0L
        || lifecycleState == null
        || admissionAllowed == null
        || authorityProvenance == null
        || !MembershipTransitionReceiptDigest.EVIDENCE_STATUS.equals(evidenceStatus)
        || !requestIdMatchesReceipt(requestId)
        || !Objects.equals(tenantKind, membership.tenantProvenanceKind())
        || !Objects.equals(sourceOperationId, membership.tenantSourceOperationId())
        || !Objects.equals(sourceDigest, membership.tenantProvenanceDigest())
        || receiptId == null
        || receiptDigest == null) {
      throw new IllegalStateException("Canonical Account membership receipt scope is inconsistent");
    }
    requireHeadMatchesMembership(head, membership);
    if (!MembershipTransitionReceiptDigest.receiptStreamKeyV2(accountUuid, tenantUuid)
        .equals(streamKey)) {
      throw new IllegalStateException("Canonical Account membership receipt key is inconsistent");
    }
    CanonicalMembershipTransitionReceipt receipt;
    try {
      receipt =
          new CanonicalMembershipTransitionReceipt(
              streamKey,
              sequence,
              receiptId,
              receiptDigest,
              evidenceStatus,
              transitionType,
              requestId,
              accountUuid,
              tenantUuid,
              lifecycleState,
              admissionAllowed,
              Map.of(tenantUuid.toString(), Long.toString(membershipVersion)),
              authorityGeneration,
              authorityProvenance,
              tenantKind,
              sourceOperationId,
              sourceDigest);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Canonical Account membership receipt is malformed", malformed);
    }
    if (!MembershipTransitionReceiptDigest.transitionDigestV2(receipt).equals(receiptDigest)) {
      throw new IllegalStateException(
          "Canonical Account membership receipt digest is inconsistent");
    }
    return receipt;
  }

  private static boolean requestIdMatchesReceipt(String requestId) {
    try {
      MembershipTransitionReceiptDigest.requireRequestIdV2(requestId);
      return true;
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private void validateReceiptForAppend(
      Record row, CanonicalMembershipState membership, CanonicalReceiptHead head) {
    Short version = row.get("receipt_version", Short.class);
    if (version == null) {
      throw new IllegalStateException("Account membership receipt version is malformed");
    }
    if (version == 1) {
      validateVersion1Receipt(row, membership, head);
    } else if (version == 2) {
      decodeCanonicalReceipt(row, membership, head);
    } else {
      throw new IllegalStateException("Account membership receipt representation is unsupported");
    }
  }

  private void validateVersion1Receipt(
      Record row, CanonicalMembershipState membership, CanonicalReceiptHead head) {
    Long sequence = row.get("receipt_sequence", Long.class);
    Long accountId = row.get("account_id", Long.class);
    Long tenantId = row.get("tenant_id", Long.class);
    Long receiptHeadId = row.get("receipt_head_id", Long.class);
    Long membershipId = row.get("membership_id", Long.class);
    Long version = row.get("membership_version", Long.class);
    Long authorityGeneration = row.get("membership_authority_generation", Long.class);
    String streamKey = row.get("receipt_stream_key", String.class);
    String transitionType = row.get("transition_type", String.class);
    String requestId = row.get("request_id", String.class);
    String lifecycleState = row.get("membership_lifecycle_state", String.class);
    Boolean admissionAllowed = row.get("gameplay_admission_allowed", Boolean.class);
    String authorityProvenance = row.get("authority_provenance", String.class);
    String evidenceStatus = row.get("evidence_status", String.class);
    UUID receiptId = row.get("receipt_id", UUID.class);
    String digest = row.get("receipt_digest", String.class);
    if (sequence == null
        || sequence <= 0L
        || accountId == null
        || accountId != membership.accountId()
        || membership.tenantId() == null
        || !membership.tenantId().equals(tenantId)
        || receiptHeadId == null
        || receiptHeadId != head.receiptHeadId()
        || membershipId == null
        || membershipId != membership.membershipId()
        || version == null
        || version <= 0L
        || authorityGeneration == null
        || authorityGeneration <= 0L
        || !Objects.equals(streamKey, head.receiptStreamKey())
        || !MembershipTransitionReceiptDigest.EVIDENCE_STATUS.equals(evidenceStatus)
        || !requestIdMatchesReceiptV1(requestId)
        || lifecycleState == null
        || admissionAllowed == null
        || authorityProvenance == null
        || receiptId == null
        || digest == null
        || !receiptId.equals(MembershipTransitionReceiptDigest.receiptIdForRequest(requestId))) {
      throw new IllegalStateException("Retained V1 Account membership receipt is inconsistent");
    }
    String expected =
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
            version,
            authorityGeneration,
            authorityProvenance);
    if (!expected.equals(digest)) {
      throw new IllegalStateException(
          "Retained V1 Account membership receipt digest is inconsistent");
    }
  }

  private static boolean requestIdMatchesReceiptV1(String requestId) {
    return requestId != null && !requestId.isBlank() && requestId.length() <= 128;
  }

  private boolean hasPriorCanonicalMembershipEventHistory(
      CanonicalMembershipState membership, String currentRequestId) {
    String streamKey = canonicalMembershipEventStreamKey(membership);
    Boolean found =
        (Boolean)
            dsl.fetchValue(
                "SELECT EXISTS ("
                    + "SELECT 1 FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ? "
                    + "AND last_event_sequence > 1 "
                    + "UNION ALL SELECT 1 FROM account_authority_outbox_streams "
                    + "WHERE outbox_stream_key = ? AND last_sequence > 1 "
                    + "UNION ALL SELECT 1 FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ? "
                    + "AND (outbox_sequence <> 1 OR request_id <> ?))",
                membership.accountUuid(),
                membership.tenantUuid(),
                streamKey,
                streamKey,
                currentRequestId);
    return Boolean.TRUE.equals(found);
  }

  private boolean hasPriorCanonicalOperationOrAudit(
      CanonicalMembershipState membership, String currentRequestId) {
    Boolean found =
        (Boolean)
            dsl.fetchValue(
                "SELECT EXISTS ("
                    + "SELECT 1 FROM account_join_operations operation "
                    + "WHERE operation.operation_representation_version = 2 "
                    + "AND operation.account_uuid = ? AND operation.tenant_uuid = ? "
                    + "AND (operation.request_id <> ? OR operation.status <> 'PENDING' "
                    + "OR operation.outcome IS NOT NULL OR operation.membership_id IS NOT NULL) "
                    + "UNION ALL SELECT 1 FROM account_audit_outbox audit "
                    + "WHERE audit.producer_service = 'account-service' "
                    + "AND audit.event_type IN ('ACCOUNT_JOINED_PUBLIC_PRODUCTION', 'ACCOUNT_MEMBERSHIP_LEFT') "
                    + "AND audit.payload IS NOT NULL "
                    + "AND audit.payload::jsonb ->> 'accountId' = ? "
                    + "AND audit.payload::jsonb ->> 'tenantId' = ? "
                    + "AND (audit.payload::jsonb ->> 'requestId') IS DISTINCT FROM ?)",
                membership.accountUuid(),
                membership.tenantUuid(),
                currentRequestId,
                membership.accountUuid().toString(),
                membership.tenantUuid().toString(),
                currentRequestId);
    return Boolean.TRUE.equals(found);
  }

  private void requireFirstCanonicalJoinEvidence(
      CanonicalMembershipState membership, String requestId, String transitionType) {
    if (!MEMBERSHIP_JOINED.equals(transitionType)
        || !"ACTIVE".equals(membership.lifecycleState())
        || !membership.gameplayAdmissionAllowed()) {
      throw new IllegalStateException(
          "A missing canonical receipt head cannot restart retained transition history");
    }
    String eventStreamKey = canonicalMembershipEventStreamKey(membership);
    Record operation =
        dsl.fetchOne(
            "SELECT operation.operation_representation_version, operation.account_id, "
                + "operation.account_uuid, operation.tenant_uuid, operation.status, "
                + "operation.outcome, operation.membership_id "
                + "FROM account_join_operations operation "
                + "WHERE operation.request_id = ? "
                + "AND operation.operation_representation_version = 2 "
                + "AND operation.scope_digest_version = 2 "
                + "AND operation.account_id = ? AND operation.account_uuid = ? "
                + "AND operation.tenant_uuid = ? AND operation.target_class = 'PUBLIC_PRODUCTION' "
                + "AND operation.status = 'PENDING' AND operation.outcome IS NULL "
                + "AND operation.membership_id IS NULL "
                + "AND operation.entitlement_authority_availability = 'AVAILABLE' "
                + "AND operation.allow_public_join IS TRUE "
                + "AND operation.entitlement_version IS NOT NULL AND operation.entitlement_version > 0 "
                + "AND operation.request_digest_version = 2 "
                + "AND operation.request_digest IS NOT NULL "
                + "AND operation.request_digest ~ '^sha256:[0-9a-f]{64}$' "
                + "AND EXISTS (SELECT 1 FROM account_connect_scope_records scope "
                + "WHERE scope.scope_token_hash = operation.scope_token_hash "
                + "AND scope.scope_digest_version = 2 "
                + "AND scope.snapshot_digest = operation.connect_scope_digest "
                + "AND scope.account_id = operation.account_id "
                + "AND scope.account_uuid = operation.account_uuid "
                + "AND scope.target_class = operation.target_class "
                + "AND scope.tenant_id IS NULL AND scope.tenant_uuid = operation.tenant_uuid "
                + "AND scope.tenant_provenance_kind = ? "
                + "AND scope.tenant_provenance_legacy_tenant_id IS NOT DISTINCT FROM ? "
                + "AND scope.tenant_source_operation_id = ? "
                + "AND scope.tenant_provenance_digest = ? "
                + "AND scope.tenant_slug = operation.tenant_slug "
                + "AND scope.realm_id = operation.realm_id "
                + "AND scope.world_slug = operation.world_slug "
                + "AND scope.realm_slug = operation.realm_slug "
                + "AND scope.playable_state_namespace_uuid = operation.playable_state_namespace_uuid "
                + "AND scope.playable_state_scope = operation.playable_state_scope "
                + "AND scope.game_instance_uuid = operation.game_instance_uuid "
                + "AND scope.catalog_revision = operation.catalog_revision "
                + "AND scope.pointer_version = operation.pointer_version)",
            requestId,
            membership.accountId(),
            membership.accountUuid(),
            membership.tenantUuid(),
            membership.tenantProvenanceKind(),
            membership.tenantId(),
            membership.tenantSourceOperationId(),
            membership.tenantProvenanceDigest());
    if (operation == null
        || !Integer.valueOf(2)
            .equals(operation.get("operation_representation_version", Integer.class))
        || !Long.valueOf(membership.accountId()).equals(operation.get("account_id", Long.class))
        || !membership.accountUuid().equals(operation.get("account_uuid", UUID.class))
        || !membership.tenantUuid().equals(operation.get("tenant_uuid", UUID.class))
        || !"PENDING".equals(operation.get("status", String.class))
        || operation.get("outcome", String.class) != null
        || operation.get("membership_id", Long.class) != null) {
      throw new IllegalStateException(
          "First canonical membership receipt lacks its exact pending JOIN operation");
    }
    Record pair =
        dsl.fetchOne(
            "SELECT legacy_tenant_id, tenant_provenance_kind, tenant_source_operation_id, "
                + "tenant_provenance_digest, membership_exists, membership_version, "
                + "membership_authority_generation, last_event_sequence, last_event_id, "
                + "last_event_digest, last_transition_invalidated "
                + "FROM account_membership_pair_authority "
                + "WHERE account_uuid = ? AND tenant_uuid = ? FOR UPDATE",
            membership.accountUuid(),
            membership.tenantUuid());
    Record event =
        dsl.fetchOne(
            "SELECT event.outbox_sequence, event.request_id, event.event_id, event.event_digest, "
                + "event.payload, stream.last_sequence FROM account_authority_outbox_events event "
                + "JOIN account_authority_outbox_streams stream "
                + "ON stream.outbox_stream_key = event.outbox_stream_key "
                + "WHERE event.outbox_stream_key = ? AND event.outbox_sequence = 1 "
                + "AND event.request_id = ?",
            eventStreamKey,
            requestId);
    if (pair == null
        || event == null
        || !Objects.equals(pair.get("legacy_tenant_id", Long.class), membership.tenantId())
        || !Objects.equals(
            pair.get("tenant_provenance_kind", String.class), membership.tenantProvenanceKind())
        || !Objects.equals(
            pair.get("tenant_source_operation_id", UUID.class),
            membership.tenantSourceOperationId())
        || !Objects.equals(
            pair.get("tenant_provenance_digest", String.class), membership.tenantProvenanceDigest())
        || !Boolean.TRUE.equals(pair.get("membership_exists", Boolean.class))
        || !Objects.equals(
            pair.get("membership_version", Long.class), membership.membershipVersion())
        || !Objects.equals(
            pair.get("membership_authority_generation", Long.class),
            membership.membershipAuthorityGeneration())
        || !Long.valueOf(1L).equals(pair.get("last_event_sequence", Long.class))
        || !Objects.equals(
            pair.get("last_event_id", String.class), event.get("event_id", String.class))
        || !Objects.equals(
            pair.get("last_event_digest", String.class), event.get("event_digest", String.class))
        || !Boolean.FALSE.equals(pair.get("last_transition_invalidated", Boolean.class))
        || !Long.valueOf(1L).equals(event.get("outbox_sequence", Long.class))
        || !Long.valueOf(1L).equals(event.get("last_sequence", Long.class))) {
      throw new IllegalStateException(
          "First canonical membership receipt lacks exact sequence-one Account event evidence");
    }
    MembershipEvent decodedEvent;
    try {
      decodedEvent =
          MembershipAuthorityEventV1Codec.verify(
              strictEventText(event.get("payload", byte[].class)));
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "First canonical membership receipt Account event payload is invalid", malformed);
    }
    if (!MembershipAuthorityEventV1Codec.SCHEMA_VERSION.equals(decodedEvent.schemaVersion())
        || !MembershipAuthorityEventV1Codec.EVENT_TYPE.equals(decodedEvent.eventType())
        || !requestId.equals(decodedEvent.requestId())
        || !eventStreamKey.equals(decodedEvent.outboxStreamKey())
        || !"1".equals(decodedEvent.outboxSequence())
        || !membership.accountUuid().toString().equals(decodedEvent.accountId())
        || !membership.tenantUuid().toString().equals(decodedEvent.tenantId())
        || !"ACTIVE".equals(decodedEvent.membershipLifecycleState())
        || !Map.of(
                membership.tenantUuid().toString(), Long.toString(membership.membershipVersion()))
            .equals(decodedEvent.membershipVersion())
        || !Long.toString(membership.membershipAuthorityGeneration())
            .equals(decodedEvent.membershipAuthorityGeneration())
        || !decodedEvent.gameplayAdmissionAllowed()
        || decodedEvent.callerBoundAuthorityInvalidated()
        || !Objects.equals(event.get("event_id", String.class), decodedEvent.eventId())
        || !Objects.equals(event.get("event_digest", String.class), decodedEvent.eventDigest())) {
      throw new IllegalStateException(
          "First canonical membership receipt Account event differs from exact JOIN state");
    }
  }

  private static String strictEventText(byte[] payload) {
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("Account membership event payload is empty");
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(payload))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(
          "Account membership event payload is not strict UTF-8", malformed);
    }
  }

  private static String canonicalMembershipEventStreamKey(CanonicalMembershipState membership) {
    return "account:auth-authority:v1:membership/"
        + membership.accountUuid()
        + "/"
        + membership.tenantUuid();
  }

  private CanonicalMembershipTransitionReceipt createCanonicalReceipt(
      CanonicalMembershipState membership, long sequence, String transitionType, String requestId) {
    String streamKey =
        MembershipTransitionReceiptDigest.receiptStreamKeyV2(
            membership.accountUuid(), membership.tenantUuid());
    UUID receiptId = MembershipTransitionReceiptDigest.receiptIdForRequestV2(requestId);
    Map<String, String> membershipVersion =
        Map.of(membership.tenantUuid().toString(), Long.toString(membership.membershipVersion()));
    CanonicalMembershipTransitionReceipt draft =
        new CanonicalMembershipTransitionReceipt(
            streamKey,
            sequence,
            receiptId,
            ZERO_SHA256_DIGEST,
            MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
            transitionType,
            requestId,
            membership.accountUuid(),
            membership.tenantUuid(),
            membership.lifecycleState(),
            membership.gameplayAdmissionAllowed(),
            membershipVersion,
            membership.membershipAuthorityGeneration(),
            membership.authorityProvenance(),
            membership.tenantProvenanceKind(),
            membership.tenantSourceOperationId(),
            membership.tenantProvenanceDigest());
    return new CanonicalMembershipTransitionReceipt(
        streamKey,
        sequence,
        receiptId,
        MembershipTransitionReceiptDigest.transitionDigestV2(draft),
        MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
        transitionType,
        requestId,
        membership.accountUuid(),
        membership.tenantUuid(),
        membership.lifecycleState(),
        membership.gameplayAdmissionAllowed(),
        membershipVersion,
        membership.membershipAuthorityGeneration(),
        membership.authorityProvenance(),
        membership.tenantProvenanceKind(),
        membership.tenantSourceOperationId(),
        membership.tenantProvenanceDigest());
  }

  private void insertCanonicalHead(
      CanonicalMembershipState membership, String streamKey, long sequence) {
    Long receiptHeadId =
        dsl.resultQuery(
                "INSERT INTO account_membership_transition_receipt_stream_heads "
                    + "(account_id, tenant_id, receipt_stream_key, last_receipt_sequence, "
                    + "account_uuid, tenant_uuid, tenant_provenance_kind, "
                    + "tenant_source_operation_id, tenant_provenance_digest, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) "
                    + "RETURNING receipt_head_id",
                membership.accountId(),
                membership.tenantId(),
                streamKey,
                sequence,
                membership.accountUuid(),
                membership.tenantUuid(),
                membership.tenantProvenanceKind(),
                membership.tenantSourceOperationId(),
                membership.tenantProvenanceDigest())
            .fetchOne(0, Long.class);
    if (receiptHeadId == null || receiptHeadId <= 0L) {
      throw new IllegalStateException("Canonical Account membership receipt head was not inserted");
    }
  }

  private void bindCanonicalHeadAndAdvance(
      CanonicalReceiptHead head, CanonicalMembershipState membership, long sequence) {
    int changed =
        dsl.execute(
            "UPDATE account_membership_transition_receipt_stream_heads "
                + "SET last_receipt_sequence = ?, account_uuid = ?, tenant_uuid = ?, "
                + "tenant_provenance_kind = ?, tenant_source_operation_id = ?, "
                + "tenant_provenance_digest = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE receipt_head_id = ? AND account_id = ? AND tenant_id = ? "
                + "AND account_uuid IS NULL AND last_receipt_sequence = ?",
            sequence,
            membership.accountUuid(),
            membership.tenantUuid(),
            membership.tenantProvenanceKind(),
            membership.tenantSourceOperationId(),
            membership.tenantProvenanceDigest(),
            head.receiptHeadId(),
            membership.accountId(),
            membership.tenantId(),
            head.lastReceiptSequence());
    if (changed != 1) {
      throw new IllegalStateException(
          "Approved retained Account membership receipt head changed before canonical binding");
    }
  }

  private void advanceCanonicalHead(CanonicalReceiptHead head, long sequence) {
    int changed =
        dsl.execute(
            "UPDATE account_membership_transition_receipt_stream_heads "
                + "SET last_receipt_sequence = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE receipt_head_id = ? AND last_receipt_sequence = ? "
                + "AND account_uuid = ? AND tenant_uuid = ? "
                + "AND tenant_source_operation_id = ? AND tenant_provenance_digest = ?",
            sequence,
            head.receiptHeadId(),
            head.lastReceiptSequence(),
            head.accountUuid(),
            head.tenantUuid(),
            head.tenantSourceOperationId(),
            head.tenantProvenanceDigest());
    if (changed != 1) {
      throw new IllegalStateException("Canonical Account membership receipt head is stale");
    }
  }

  private void insertCanonicalReceipt(
      CanonicalMembershipState membership, CanonicalMembershipTransitionReceipt receipt) {
    int inserted =
        dsl.execute(
            "INSERT INTO account_membership_transition_receipts "
                + "(receipt_stream_key, receipt_sequence, account_id, tenant_id, receipt_head_id, "
                + "receipt_version, account_uuid, tenant_uuid, tenant_provenance_kind, "
                + "tenant_source_operation_id, tenant_provenance_digest, evidence_status, "
                + "transition_type, request_id, membership_id, membership_lifecycle_state, "
                + "gameplay_admission_allowed, membership_version, "
                + "membership_authority_generation, authority_provenance, receipt_id, receipt_digest) "
                + "VALUES (?, ?, ?, ?, (SELECT receipt_head_id "
                + "FROM account_membership_transition_receipt_stream_heads "
                + "WHERE account_id = ? AND tenant_uuid = ?), 2, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            receipt.receiptStreamKey(),
            receipt.receiptSequence(),
            membership.accountId(),
            membership.tenantId(),
            membership.accountId(),
            membership.tenantUuid(),
            receipt.accountId(),
            receipt.tenantId(),
            receipt.tenantProvenanceKind(),
            receipt.tenantSourceOperationId(),
            receipt.tenantProvenanceDigest(),
            receipt.evidenceStatus(),
            receipt.transitionType(),
            receipt.requestId(),
            membership.membershipId(),
            receipt.membershipLifecycleState(),
            receipt.gameplayAdmissionAllowed(),
            Long.parseLong(receipt.membershipVersion().get(receipt.tenantId().toString())),
            receipt.membershipAuthorityGeneration(),
            receipt.authorityProvenance(),
            receipt.receiptId(),
            receipt.receiptDigest());
    if (inserted != 1) {
      throw new IllegalStateException("Canonical Account membership receipt was not inserted");
    }
  }

  private static boolean sameCanonicalTransition(
      CanonicalMembershipTransitionReceipt receipt,
      CanonicalMembershipState membership,
      String transitionType,
      String requestId) {
    return receipt.requestId().equals(requestId)
        && receipt.accountId().equals(membership.accountUuid())
        && receipt.tenantId().equals(membership.tenantUuid())
        && receipt.transitionType().equals(transitionType)
        && sameCanonicalCurrentState(receipt, membership);
  }

  private static boolean sameCanonicalCurrentState(
      CanonicalMembershipTransitionReceipt receipt, CanonicalMembershipState membership) {
    return receipt.membershipLifecycleState().equals(membership.lifecycleState())
        && receipt.gameplayAdmissionAllowed() == membership.gameplayAdmissionAllowed()
        && receipt
            .membershipVersion()
            .equals(
                Map.of(
                    membership.tenantUuid().toString(),
                    Long.toString(membership.membershipVersion())))
        && receipt.membershipAuthorityGeneration() == membership.membershipAuthorityGeneration()
        && receipt.authorityProvenance().equals(membership.authorityProvenance())
        && receipt.tenantProvenanceKind().equals(membership.tenantProvenanceKind())
        && receipt.tenantSourceOperationId().equals(membership.tenantSourceOperationId())
        && receipt.tenantProvenanceDigest().equals(membership.tenantProvenanceDigest());
  }

  private static void requireTransitionState(
      CanonicalMembershipState membership, String transitionType) {
    if (MEMBERSHIP_JOINED.equals(transitionType) || MEMBERSHIP_REACTIVATED.equals(transitionType)) {
      if (!"ACTIVE".equals(membership.lifecycleState())
          || !membership.gameplayAdmissionAllowed()
          || !"EXPLICIT_JOIN".equals(membership.authorityProvenance())) {
        throw new IllegalArgumentException("JOIN receipt requires its committed active state");
      }
      return;
    }
    if (MEMBERSHIP_LEFT.equals(transitionType)) {
      if (!"INACTIVE".equals(membership.lifecycleState())
          || membership.gameplayAdmissionAllowed()
          || !"EXPLICIT_JOIN".equals(membership.authorityProvenance())) {
        throw new IllegalArgumentException(
            "LEAVE receipt requires its committed inactive non-admitting state");
      }
      return;
    }
    throw new IllegalArgumentException("Membership transition type is unsupported");
  }

  private void requireOwnerWriteTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical Account membership receipt access requires an active owner transaction with read-write access");
    }
  }

  /** Appends one provisional receipt inside the surrounding Account membership transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public MembershipTransitionReceipt appendTransition(
      AccountTenantMembership membership, String transitionType, String requestId) {
    requireOwnerWriteTransaction();
    if (membership == null
        || membership.getAccount() == null
        || membership.getAccount().getId() == null
        || membership.getId() == null) {
      throw new IllegalArgumentException("A persisted Account membership is required");
    }
    long accountId = membership.getAccount().getId();
    if (membership.getTenantId() == null || membership.getTenantId() <= 0L) {
      throw new IllegalArgumentException("A legacy Account membership tenant key is required");
    }
    long tenantId = membership.getTenantId();
    long membershipId = membership.getId();
    requireTransitionState(membership, transitionType);
    lockReceiptRequestFence(requestId);
    requireRequestIdAvailableV1(requestId);
    requireLatestReceiptRepresentation(accountId, tenantId);

    String streamKey = MembershipTransitionReceiptDigest.receiptStreamKey(accountId, tenantId);
    UUID receiptId = MembershipTransitionReceiptDigest.receiptIdForRequest(requestId);
    // Validate every caller-controlled V1 digest segment before allocating the shared counter.
    MembershipTransitionReceiptDigest.transitionDigest(
        streamKey,
        1L,
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
    Record headRecord =
        dsl.resultQuery(
                "INSERT INTO account_membership_transition_receipt_stream_heads "
                    + "(account_id, tenant_id, receipt_stream_key, last_receipt_sequence, updated_at) "
                    + "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP) "
                    + "ON CONFLICT (account_id, tenant_id) DO UPDATE SET "
                    + "last_receipt_sequence = "
                    + "account_membership_transition_receipt_stream_heads.last_receipt_sequence + 1, "
                    + "updated_at = CURRENT_TIMESTAMP "
                    + "RETURNING receipt_head_id, last_receipt_sequence",
                accountId,
                tenantId,
                streamKey)
            .fetchOne();
    Long headId = headRecord == null ? null : headRecord.get("receipt_head_id", Long.class);
    Long sequence = headRecord == null ? null : headRecord.get("last_receipt_sequence", Long.class);
    if (headId == null || headId <= 0L || sequence == null || sequence <= 0L) {
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
                + "(receipt_stream_key, receipt_sequence, account_id, tenant_id, receipt_head_id, "
                + "evidence_status, "
                + "transition_type, request_id, membership_id, membership_lifecycle_state, "
                + "gameplay_admission_allowed, membership_version, "
                + "membership_authority_generation, authority_provenance, receipt_id, receipt_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            streamKey,
            sequence,
            accountId,
            tenantId,
            headId,
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
                + "h.last_receipt_sequence AS head_receipt_sequence, r.receipt_version, "
                + "r.evidence_status, "
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
    Short receiptVersion = row.get("receipt_version", Short.class);
    if (receiptVersion == null || receiptVersion != 1) {
      throw new IllegalStateException(
          "Account membership receipt request uses an unsupported canonical representation");
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
            "SELECT h.receipt_head_id, h.receipt_stream_key, h.last_receipt_sequence, "
                + "m.id AS current_membership_id, "
                + "m.lifecycle_state AS current_lifecycle_state, "
                + "m.gameplay_admission_allowed AS current_admission_allowed, "
                + "m.membership_version AS current_membership_version, "
                + "m.membership_authority_generation AS current_authority_generation, "
                + "m.authority_provenance AS current_authority_provenance, "
                + "r.receipt_version, r.receipt_sequence AS row_receipt_sequence, "
                + "r.account_id AS receipt_account_id, "
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
                + "ON r.receipt_head_id = h.receipt_head_id "
                + "AND r.receipt_sequence = h.last_receipt_sequence ",
            accountId,
            tenantId,
            accountId,
            tenantId);
    if (row == null) {
      return null;
    }
    Short receiptVersion = row.get("receipt_version", Short.class);
    if (receiptVersion != null && receiptVersion != 1) {
      throw new IllegalStateException(
          "Latest Account membership receipt uses an unsupported canonical representation");
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
                    + "AND event_type IN ('ACCOUNT_JOINED_PUBLIC_PRODUCTION', 'ACCOUNT_MEMBERSHIP_LEFT') "
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

  private void requireLatestReceiptRepresentation(long accountId, long tenantId) {
    Record row =
        dsl.fetchOne(
            "SELECT head.last_receipt_sequence, receipt.receipt_version "
                + "FROM account_membership_transition_receipt_stream_heads head "
                + "LEFT JOIN account_membership_transition_receipts receipt "
                + "ON receipt.receipt_head_id = head.receipt_head_id "
                + "AND receipt.receipt_sequence = head.last_receipt_sequence "
                + "WHERE head.account_id = ? AND head.tenant_id = ? FOR UPDATE OF head",
            accountId,
            tenantId);
    if (row == null) {
      return;
    }
    Short version = row.get("receipt_version", Short.class);
    if (version == null) {
      throw new IllegalStateException(
          "Account membership receipt head lacks its exact latest receipt");
    }
    if (version != 1) {
      throw new IllegalStateException(
          "Latest Account membership receipt uses an unsupported canonical representation");
    }
    Long sequence = row.get("last_receipt_sequence", Long.class);
    if (sequence == null || sequence <= 0L) {
      throw new IllegalStateException("Account membership receipt head counter is malformed");
    }
    if (sequence == Long.MAX_VALUE) {
      throw new IllegalStateException("Account membership receipt sequence is exhausted");
    }
  }

  private void lockReceiptRequestFence(String requestId) {
    if (requestId == null) {
      throw new IllegalArgumentException("Account membership request ID is required");
    }
    dsl.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", requestId);
  }

  private void requireRequestIdAvailableV1(String requestId) {
    Record row = readReceiptRequest(requestId);
    if (row != null) {
      throw new IllegalStateException(
          "Account membership request ID is already retained in another transition receipt");
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

  private record CanonicalMembershipState(
      long accountId,
      long membershipId,
      UUID accountUuid,
      UUID tenantUuid,
      Long tenantId,
      String tenantProvenanceKind,
      UUID tenantSourceOperationId,
      String tenantProvenanceDigest,
      String lifecycleState,
      boolean gameplayAdmissionAllowed,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String authorityProvenance) {}

  private record CanonicalReceiptHead(
      long receiptHeadId,
      long accountId,
      Long tenantId,
      String receiptStreamKey,
      long lastReceiptSequence,
      UUID accountUuid,
      UUID tenantUuid,
      String tenantProvenanceKind,
      UUID tenantSourceOperationId,
      String tenantProvenanceDigest) {}

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
