package net.firedevops.firemud.gamesession.repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Validates and reads the V1006 association; it never creates admission authority. */
public final class AccountInitialAdmissionProtectionBindingRepository {
  private static final DateTimeFormatter MICROS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
  private final DSLContext dsl;

  public AccountInitialAdmissionProtectionBindingRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Locks and validates the actual retained intent, launch tuple, and original owner attempt that
   * one Account protection value names. V1039 requires the protected owner reservation to persist
   * these complete canonical bytes before returning PENDING.
   */
  ValidatedSource requireExactSource(
      CanonicalInitialAdmissionRequest admission,
      AccountStartSessionAdmissionProtectionEvidence protection) {
    Objects.requireNonNull(admission, "admission");
    Objects.requireNonNull(protection, "protection");
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account-protected initial admission requires a writable owner transaction");
    }

    var protectionRequest = protection.request();
    var hold = protectionRequest.worldAdmissionHoldIdentity();
    var world = hold.request();
    CanonicalInitialAdmissionRequest protectedAdmission =
        new CanonicalInitialAdmissionRequest(
            world.targetNamespace(),
            world.canonicalTenantId(),
            world.worldSlug(),
            world.realmId(),
            world.playableStateNamespaceId(),
            world.playableStateScope(),
            world.canonicalGameInstanceId(),
            world.canonicalVersionId(),
            world.activeLifecycleEpoch(),
            world.expectedCatalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.valueOf(
                world.initialAdmissionOrigin().name()),
            world.expectedPriorPointerVersion(),
            world.initialAdmissionRequestId(),
            world.initialAdmissionRequestDigest(),
            hold.holdId(),
            hold.holdFence(),
            hold.holdBindingDigest());
    if (!admission.equals(protectedAdmission)) {
      throw unavailable("Account protection names a different canonical admission request");
    }

    Record row =
        dsl.fetchOne(
            "SELECT i.state AS intent_state, i.hold_identity_bytes AS intent_hold, "
                + "i.canonical_hold_request_bytes AS intent_request, "
                + "i.original_start_session_tuple AS intent_tuple, "
                + "i.control_plane_request_id AS intent_original_request, "
                + "l.original_start_session_tuple AS launch_tuple, "
                + "o.post_authorization_execution_tuple AS original_tuple, "
                + "o.account_redemption_projection AS original_projection, "
                + "o.owner_attempt_id AS retained_attempt, "
                + "o.owner_mutation_id AS retained_mutation, o.owner_fence AS retained_fence, "
                + "o.lease_expires_at AS retained_lease, o.phase_state AS original_phase, "
                + "o.canonical_tenant_id AS original_tenant, "
                + "o.control_plane_request_id AS original_request "
                + "FROM game_session_canonical_initial_admission_intent i "
                + "JOIN game_session_canonical_instance_launch l "
                + "ON l.target_namespace = i.target_namespace "
                + "AND l.control_plane_request_id = i.control_plane_request_id "
                + "AND l.canonical_tenant_id = i.canonical_tenant_id "
                + "AND l.canonical_realm_id = i.realm_id "
                + "AND l.game_instance_uuid = i.canonical_game_instance_id "
                + "AND l.game_session_tenant_id = i.game_session_tenant_id "
                + "AND l.game_instance_id = i.game_instance_id "
                + "JOIN game_session_start_session_operator_attempt o "
                + "ON o.target_namespace = i.target_namespace "
                + "AND o.control_plane_request_id = i.control_plane_request_id "
                + "AND o.owner_attempt_id = ? "
                + "WHERE i.target_namespace = ? AND i.initial_admission_request_id = ? "
                + "AND i.request_digest = ? AND i.canonical_tenant_id = ? AND i.world_slug = ? "
                + "AND i.realm_id = ? AND i.playable_state_namespace_id = ? "
                + "AND i.playable_state_scope = ? AND i.canonical_game_instance_id = ? "
                + "AND i.canonical_version_id = ? AND i.catalog_revision = ? "
                + "FOR UPDATE OF i, l, o",
            protectionRequest.gameSessionOwnerAttemptId(),
            admission.targetNamespace(),
            admission.initialAdmissionRequestId(),
            admission.requestDigest(),
            admission.canonicalTenantId(),
            admission.worldSlug(),
            admission.realmId(),
            admission.playableStateNamespaceId(),
            admission.playableStateScope(),
            admission.canonicalGameInstanceId(),
            admission.canonicalVersionId(),
            admission.expectedCatalogRevision());
    if (row == null) {
      throw unavailable(
          "Account protection has no exact retained intent, launch, and original owner join");
    }

    byte[] originalTupleBytes = required(row, "original_tuple", byte[].class);
    StartSessionPostAuthorizationExecutionTuple original =
        StartSessionPostAuthorizationExecutionTuple.decode(originalTupleBytes);
    Instant retainedLease = required(row, "retained_lease", OffsetDateTime.class).toInstant();
    if (!"HOLD_ATTACHED".equals(required(row, "intent_state", String.class))
        || !Arrays.equals(hold.canonicalBytes(), required(row, "intent_hold", byte[].class))
        || !Arrays.equals(
            hold.canonicalRequestBytes(), required(row, "intent_request", byte[].class))
        || !Arrays.equals(
            protectionRequest.originalPostAuthorizationTupleBytes(), originalTupleBytes)
        || !Arrays.equals(originalTupleBytes, required(row, "intent_tuple", byte[].class))
        || !Arrays.equals(originalTupleBytes, required(row, "launch_tuple", byte[].class))
        || !Arrays.equals(
            protectionRequest.accountRedemptionProjectionBytes(),
            required(row, "original_projection", byte[].class))
        || !protectionRequest
            .gameSessionOwnerAttemptId()
            .equals(required(row, "retained_attempt", UUID.class))
        || !protectionRequest
            .gameSessionOwnerMutationId()
            .equals(required(row, "retained_mutation", UUID.class))
        || protectionRequest.gameSessionOwnerFence() != required(row, "retained_fence", Long.class)
        || !protectionRequest.originalLeaseExpiresAt().equals(retainedLease)
        || !"OWNER_EXECUTION_PENDING".equals(required(row, "original_phase", String.class))
        || !admission.canonicalTenantId().equals(required(row, "original_tenant", UUID.class))
        || !original
            .controlPlaneRequestId()
            .equals(required(row, "intent_original_request", String.class))
        || !original
            .controlPlaneRequestId()
            .equals(required(row, "original_request", String.class))) {
      throw unavailable(
          "Account protection differs from the locked original tuple, projection, owner fence, lease, or World hold");
    }
    if (!retainedLease.isAfter(Instant.now())) {
      throw unavailable("Account protection original owner lease is no longer live");
    }
    return new ValidatedSource(
        protection.accountProtectionId(),
        protection.accountProtectionFence(),
        protectionRequest.gameSessionOwnerAttemptId(),
        protectionRequest.gameSessionOwnerMutationId(),
        protectionRequest.gameSessionOwnerFence(),
        protection.canonicalBytes());
  }

  /** Locks the full live source and its exact PENDING reservation for Human runtime completion. */
  ValidatedSource requireExactHumanStartingCompletionSource(
      CanonicalInitialAdmissionRequest admission,
      AccountStartSessionAdmissionProtectionEvidence protection) {
    ValidatedSource source = requireExactSource(admission, protection);
    requireExactPendingReservation(
        admission.targetNamespace(), admission.initialAdmissionRequestId(), protection);
    return source;
  }

  /**
   * Requires an existing PENDING reservation to retain exactly the independently valid evidence.
   */
  void requireExactPendingReservation(
      String namespace,
      String initialAdmissionRequestId,
      AccountStartSessionAdmissionProtectionEvidence protection) {
    Record row =
        dsl.fetchOne(
            "SELECT status, account_protection_evidence_version, account_protection_id, "
                + "account_protection_fence, original_owner_attempt_id, "
                + "original_owner_mutation_id, original_owner_fence, "
                + "account_protection_evidence_bytes FROM "
                + "game_session_canonical_initial_admission_attempt "
                + "WHERE target_namespace = ? AND initial_admission_request_id = ? FOR UPDATE",
            namespace,
            initialAdmissionRequestId);
    var request = protection.request();
    if (row == null
        || !"PENDING".equals(row.get("status", String.class))
        || !Integer.valueOf(1).equals(row.get("account_protection_evidence_version", Integer.class))
        || !protection.accountProtectionId().equals(row.get("account_protection_id", UUID.class))
        || protection.accountProtectionFence()
            != required(row, "account_protection_fence", Long.class)
        || !request
            .gameSessionOwnerAttemptId()
            .equals(row.get("original_owner_attempt_id", UUID.class))
        || !request
            .gameSessionOwnerMutationId()
            .equals(row.get("original_owner_mutation_id", UUID.class))
        || request.gameSessionOwnerFence() != required(row, "original_owner_fence", Long.class)
        || !Arrays.equals(
            protection.canonicalBytes(),
            row.get("account_protection_evidence_bytes", byte[].class))) {
      throw unavailable(
          "PENDING admission reservation retains different Account protection identity, fences, or bytes");
    }
  }

  /**
   * Empty means an absent operation or an entirely legacy association, never settlement. Every
   * present protected result independently joins retained owner, intent, launch and hold bytes. May
   * also run during the existing owner-local locked current-pointer read; performs no RPC.
   */
  public Optional<RetainedProtection> read(String namespace, String operation) {
    Record row =
        dsl.fetchOne(
            "SELECT a.*, i.original_start_session_tuple AS intent_tuple, "
                + "i.hold_identity_bytes AS intent_hold, i.canonical_hold_request_bytes AS intent_request, "
                + "i.state AS intent_state, i.request_digest AS intent_digest, "
                + "i.control_plane_request_id AS intent_original_request, "
                + "o.post_authorization_execution_tuple AS original_tuple, "
                + "o.account_redemption_projection AS original_projection, "
                + "o.owner_attempt_id AS retained_attempt, o.owner_mutation_id AS retained_mutation, "
                + "o.owner_fence AS retained_fence, o.lease_expires_at AS retained_lease, "
                + "o.canonical_tenant_id AS original_tenant, "
                + "o.control_plane_request_id AS original_request, "
                + "l.original_start_session_tuple AS launch_tuple "
                + "FROM game_session_canonical_initial_admission_attempt a "
                + "LEFT JOIN game_session_canonical_initial_admission_intent i "
                + "ON i.target_namespace = a.target_namespace AND i.initial_admission_request_id = a.initial_admission_request_id "
                + "AND i.request_digest = a.request_digest AND i.canonical_tenant_id = a.canonical_tenant_id "
                + "AND i.world_slug = a.world_slug AND i.realm_id = a.realm_id "
                + "AND i.playable_state_namespace_id = a.playable_state_namespace_id AND i.playable_state_scope = a.playable_state_scope "
                + "AND i.canonical_game_instance_id = a.canonical_game_instance_id AND i.canonical_version_id = a.canonical_version_id "
                + "AND i.game_session_tenant_id = a.game_session_tenant_id AND i.game_instance_id = a.game_instance_id "
                + "AND i.runtime_version_id = a.runtime_version_id AND i.catalog_revision = a.expected_catalog_revision "
                + "AND i.terminal_at = a.terminal_at "
                + "LEFT JOIN game_session_start_session_operator_attempt o "
                + "ON o.target_namespace = a.target_namespace AND o.owner_attempt_id = a.original_owner_attempt_id "
                + "LEFT JOIN game_session_canonical_instance_launch l "
                + "ON l.target_namespace = a.target_namespace AND l.control_plane_request_id = i.control_plane_request_id "
                + "AND l.canonical_tenant_id = a.canonical_tenant_id AND l.canonical_realm_id = a.realm_id "
                + "AND l.game_instance_uuid = a.canonical_game_instance_id "
                + "AND l.game_session_tenant_id = a.game_session_tenant_id AND l.game_instance_id = a.game_instance_id "
                + "WHERE a.target_namespace = ? AND a.initial_admission_request_id = ?",
            namespace,
            operation);
    if (row == null) return Optional.empty();
    String[] protectionColumns = {
      "account_protection_evidence_version", "account_protection_id", "account_protection_fence",
      "original_owner_attempt_id", "original_owner_mutation_id", "original_owner_fence",
      "account_protection_evidence_bytes"
    };
    boolean legacy = Arrays.stream(protectionColumns).allMatch(name -> row.get(name) == null);
    if (legacy) return Optional.empty();
    var protection =
        AccountStartSessionAdmissionProtectionEvidence.decode(
            required(row, "account_protection_evidence_bytes", byte[].class));
    var request = protection.request();
    var hold = request.worldAdmissionHoldIdentity();
    var world = hold.request();
    var original =
        StartSessionPostAuthorizationExecutionTuple.decode(
            required(row, "original_tuple", byte[].class));
    if (required(row, "account_protection_evidence_version", Integer.class) != 1
        || !protection.accountProtectionId().equals(row.get("account_protection_id"))
        || protection.accountProtectionFence()
            != required(row, "account_protection_fence", Long.class)
        || !request.gameSessionOwnerAttemptId().equals(row.get("original_owner_attempt_id"))
        || !request.gameSessionOwnerAttemptId().equals(row.get("retained_attempt"))
        || !request.gameSessionOwnerMutationId().equals(row.get("original_owner_mutation_id"))
        || !request.gameSessionOwnerMutationId().equals(row.get("retained_mutation"))
        || request.gameSessionOwnerFence() != required(row, "original_owner_fence", Long.class)
        || request.gameSessionOwnerFence() != required(row, "retained_fence", Long.class)
        || !request
            .originalLeaseExpiresAt()
            .equals(required(row, "retained_lease", OffsetDateTime.class).toInstant())
        || !Arrays.equals(request.originalPostAuthorizationTupleBytes(), original.canonicalBytes())
        || !Arrays.equals(original.canonicalBytes(), required(row, "original_tuple", byte[].class))
        || !Arrays.equals(original.canonicalBytes(), required(row, "intent_tuple", byte[].class))
        || !Arrays.equals(original.canonicalBytes(), required(row, "launch_tuple", byte[].class))
        || !Arrays.equals(
            request.accountRedemptionProjectionBytes(),
            required(row, "original_projection", byte[].class))
        || !Arrays.equals(hold.canonicalBytes(), required(row, "intent_hold", byte[].class))
        || !Arrays.equals(
            hold.canonicalRequestBytes(), required(row, "intent_request", byte[].class))
        || !"TERMINAL".equals(row.get("intent_state"))
        || !world.initialAdmissionRequestDigest().equals(row.get("intent_digest"))
        || !original.controlPlaneRequestId().equals(row.get("intent_original_request"))
        || !original.controlPlaneRequestId().equals(row.get("original_request"))
        || !world.canonicalTenantId().equals(row.get("original_tenant"))) {
      throw unavailable(
          "Retained Account protection differs from the complete original owner association");
    }
    // Validate the actual stored operation digest, then compare every field to the retained hold.
    var actual =
        new CanonicalInitialAdmissionRequest(
            required(row, "target_namespace", String.class),
            required(row, "canonical_tenant_id", java.util.UUID.class),
            required(row, "world_slug", String.class),
            required(row, "realm_id", java.util.UUID.class),
            required(row, "playable_state_namespace_id", java.util.UUID.class),
            required(row, "playable_state_scope", String.class),
            required(row, "canonical_game_instance_id", java.util.UUID.class),
            required(row, "canonical_version_id", java.util.UUID.class),
            required(row, "active_lifecycle_epoch", Long.class),
            required(row, "expected_catalog_revision", Long.class),
            CanonicalInitialAdmissionRequest.OriginKind.valueOf(
                required(row, "origin_kind", String.class)),
            row.get("expected_prior_pointer_version", Long.class),
            required(row, "initial_admission_request_id", String.class),
            required(row, "request_digest", String.class),
            required(row, "hold_id", java.util.UUID.class),
            required(row, "hold_fence", java.util.UUID.class),
            required(row, "hold_binding_digest", String.class));
    var expected =
        new CanonicalInitialAdmissionRequest(
            world.targetNamespace(),
            world.canonicalTenantId(),
            world.worldSlug(),
            world.realmId(),
            world.playableStateNamespaceId(),
            world.playableStateScope(),
            world.canonicalGameInstanceId(),
            world.canonicalVersionId(),
            world.activeLifecycleEpoch(),
            world.expectedCatalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.valueOf(
                world.initialAdmissionOrigin().name()),
            world.expectedPriorPointerVersion(),
            world.initialAdmissionRequestId(),
            world.initialAdmissionRequestDigest(),
            hold.holdId(),
            hold.holdFence(),
            hold.holdBindingDigest());
    if (!actual.equals(expected))
      throw unavailable("Stored admission operation differs from its retained World hold");
    String status = required(row, "status", String.class);
    if (!"COMMITTED".equals(status) && !"ABORTED".equals(status)) {
      throw unavailable("Protected admission has no immutable terminal result");
    }
    Instant terminal = required(row, "terminal_at", OffsetDateTime.class).toInstant();
    String digest =
        terminalDigest(
            protection.canonicalBytes(),
            actual.requestDigest(),
            status,
            row.get("pointer_id", Long.class),
            row.get("pointer_version", Long.class),
            row.get("audit_event_id", Long.class),
            terminal,
            row.get("abort_reason", String.class));
    if (!digest.equals(
        row.get("COMMITTED".equals(status) ? "commit_proof_digest" : "abort_proof_digest"))) {
      throw unavailable(
          "Stored protection-bound terminal digest disagrees with its immutable fields");
    }
    return Optional.of(new RetainedProtection(protection, digest));
  }

  /** Matches V1006's exact int32-length-framed terminal preimage, including UTC microseconds. */
  static String terminalDigest(
      byte[] protection,
      String requestDigest,
      String status,
      Long pointerId,
      Long pointerVersion,
      Long audit,
      Instant terminal,
      String abortReason) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      add(
          digest,
          "game-session-canonical-initial-admission-terminal/v1".getBytes(StandardCharsets.UTF_8));
      add(digest, protection);
      for (String field :
          new String[] {
            requestDigest,
            status,
            text(pointerId),
            text(pointerVersion),
            text(audit),
            MICROS.format(terminal),
            abortReason == null ? "" : abortReason
          }) {
        add(digest, field.getBytes(StandardCharsets.UTF_8));
      }
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String text(Long value) {
    return value == null ? "" : value.toString();
  }

  private static void add(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }

  private static <T> T required(Record row, String name, Class<T> type) {
    T value = row.get(name, type);
    if (value == null) throw unavailable("Retained admission association is incomplete");
    return value;
  }

  private static IllegalStateException unavailable(String reason) {
    return new IllegalStateException(reason);
  }

  public record RetainedProtection(
      AccountStartSessionAdmissionProtectionEvidence protection, String terminalProofDigest) {}

  record ValidatedSource(
      UUID accountProtectionId,
      long accountProtectionFence,
      UUID originalOwnerAttemptId,
      UUID originalOwnerMutationId,
      long originalOwnerFence,
      byte[] protectionBytes) {
    ValidatedSource {
      protectionBytes = protectionBytes.clone();
    }

    @Override
    public byte[] protectionBytes() {
      return protectionBytes.clone();
    }
  }
}
