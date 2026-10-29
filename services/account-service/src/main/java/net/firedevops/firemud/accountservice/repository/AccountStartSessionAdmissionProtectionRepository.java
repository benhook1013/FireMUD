package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionAuthorityCapture;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionSettlementEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable Account storage for protection through one exact original StartSession admission. */
@Repository
public class AccountStartSessionAdmissionProtectionRepository {
  private static final String PROTECTIONS = "account_start_session_admission_protections";
  private static final String SOURCES = "account_start_session_admission_protection_sources";
  private static final String SETTLEMENTS =
      "account_start_session_admission_protection_settlements";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Set<String> PROTECTION_COLUMNS =
      Set.of(
          "protection_id",
          "protection_fence",
          "control_plane_request_id",
          "original_post_authorization_tuple",
          "account_redemption_projection",
          "game_session_owner_mutation_id",
          "game_session_owner_attempt_id",
          "game_session_owner_fence",
          "original_lease_expires_at",
          "account_world_participation_id",
          "account_world_participation_fence",
          "target_namespace",
          "canonical_tenant_id",
          "canonical_game_instance_id",
          "capture_source_version",
          "capture_source_fence",
          "capture_sha256",
          "world_admission_hold_identity_bytes",
          "request_binding_bytes",
          "request_binding_digest",
          "producer_xid",
          "created_at");
  private static final Set<String> SOURCE_COLUMNS =
      Set.of("protection_id", "source_key", "source_evidence");
  private static final Set<String> SETTLEMENT_COLUMNS =
      Set.of("protection_id", "outcome", "terminal_bytes", "terminal_digest", "settled_at");

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve the injected DSLContext precondition; this transactional Account repository owns no resources and must remain proxyable.")
  public AccountStartSessionAdmissionProtectionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Inserts or returns the exact immutable protection inside the existing Account currentness
   * transaction. V1003's insert guard independently checks the complete retained source bindings,
   * source-lock order, expiry, and original Account owner rows.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredProtection acquireOrReadExact(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAuthorityCapture capture,
      StoredParticipation participation) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(request, "exact Account admission request is required");
    Objects.requireNonNull(capture, "exact current Account capture is required");
    Objects.requireNonNull(participation, "exact retained Account participation is required");
    requireRequestBinding(request, capture, participation);

    byte[] requestBytes = request.canonicalBytes();
    List<SourceEvidence> sources = canonicalSources(participation.sourceVector());
    UUID proposedId = UUID.randomUUID();
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + PROTECTIONS
                + " (protection_id, control_plane_request_id, original_post_authorization_tuple, "
                + "account_redemption_projection, game_session_owner_mutation_id, "
                + "game_session_owner_attempt_id, game_session_owner_fence, original_lease_expires_at, "
                + "account_world_participation_id, account_world_participation_fence, target_namespace, "
                + "canonical_tenant_id, canonical_game_instance_id, capture_source_version, "
                + "capture_source_fence, capture_sha256, world_admission_hold_identity_bytes, "
                + "request_binding_bytes, request_binding_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (control_plane_request_id) DO NOTHING",
            proposedId,
            request.originalTuple().controlPlaneRequestId(),
            request.originalPostAuthorizationTupleBytes(),
            request.accountRedemptionProjectionBytes(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerFence(),
            OffsetDateTime.ofInstant(request.originalLeaseExpiresAt(), ZoneOffset.UTC),
            request.accountWorldParticipationId(),
            request.accountWorldParticipationFence(),
            request.worldAdmissionHoldIdentity().request().targetNamespace(),
            request.worldAdmissionHoldIdentity().request().canonicalTenantId(),
            request.worldAdmissionHoldIdentity().request().canonicalGameInstanceId(),
            capture.sourceVersion(),
            capture.sourceFence(),
            capture.canonicalSha256(),
            request.worldAdmissionHoldIdentityBytes(),
            requestBytes,
            digestPrefixed(requestBytes));

    if (inserted == 1) {
      for (SourceEvidence source : sources) {
        dsl.execute(
            "INSERT INTO "
                + SOURCES
                + " (protection_id, source_key, source_evidence) "
                + "SELECT protection_id, ?, ? FROM "
                + PROTECTIONS
                + " WHERE control_plane_request_id = ?",
            source.key(),
            source.canonicalBytes(),
            request.originalTuple().controlPlaneRequestId());
      }
    }

    Record row = selectByRequestId(request.originalTuple().controlPlaneRequestId());
    StoredProtection stored = decodeProtection(row, readSources(protectionId(row)));
    requireSameOperation(request, capture, participation, stored);
    return stored;
  }

  /** Exact current readback; V1003 rechecks all live owners and the database-clock lease. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredProtection> findCurrentExact(
      UUID protectionId, long protectionFence, byte[] exactRequestBytes) {
    requireWritableReadCommittedTransaction();
    requireNonnil(protectionId, "protectionId");
    requirePositive(protectionFence, "protectionFence");
    Objects.requireNonNull(exactRequestBytes, "exact request bytes are required");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
            protectionId,
            protectionFence,
            exactRequestBytes);
    if (row == null) return Optional.empty();
    return Optional.of(decodeProtection(row, readSources(protectionId)));
  }

  /** Historical identity lookup used only for settlement and exact retry. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredProtection> findHistoricalExact(UUID protectionId, long protectionFence) {
    requireWritableReadCommittedTransaction();
    requireNonnil(protectionId, "protectionId");
    requirePositive(protectionFence, "protectionFence");
    Record row = selectById(protectionId);
    if (row == null) return Optional.empty();
    StoredProtection stored = decodeProtection(row, readSources(protectionId));
    if (stored.evidence().accountProtectionFence() != protectionFence) return Optional.empty();
    return Optional.of(stored);
  }

  /** Lookup-only immutable settlement read; it never creates or renews protection. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredSettlement> findSettlementExact(UUID protectionId, long protectionFence) {
    requireWritableReadCommittedTransaction();
    Optional<StoredProtection> protection = findHistoricalExact(protectionId, protectionFence);
    if (protection.isEmpty()) return Optional.empty();
    Record row =
        dsl.fetchOne("SELECT * FROM " + SETTLEMENTS + " WHERE protection_id = ?", protectionId);
    if (row == null) return Optional.empty();
    return Optional.of(decodeSettlement(row, protection.orElseThrow()));
  }

  /**
   * Appends an exact independent Game Session terminal read or returns its immutable winner.
   * Source-key locks always precede the protection-row lock, matching V1003 writer exclusion.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredSettlement settleExact(AccountInitialAdmissionSettlementReadEvidence readEvidence) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(readEvidence, "authenticated Game Session terminal read is required");
    AccountStartSessionAdmissionProtectionEvidence evidence = readEvidence.protection();
    StoredProtection protection =
        findHistoricalExact(evidence.accountProtectionId(), evidence.accountProtectionFence())
            .orElseThrow(AccountStartSessionAdmissionProtectionRepository::unavailable);
    requireSameEvidence(protection.evidence(), evidence);
    lockOriginalSourceRows(protection.evidence());
    Record locked =
        dsl.fetchOne(
            "SELECT * FROM " + PROTECTIONS + " WHERE protection_id = ? FOR UPDATE NOWAIT",
            evidence.accountProtectionId());
    if (locked == null) throw unavailable();
    protection = decodeProtection(locked, readSources(evidence.accountProtectionId()));
    requireSameEvidence(protection.evidence(), evidence);

    AccountStartSessionAdmissionProtectionSettlementEvidence settlement;
    try {
      settlement =
          AccountStartSessionAdmissionProtectionSettlementEvidence.create(
              evidence, readEvidence.terminal());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Exact Game Session terminal does not bind Account protection", malformed);
    }
    byte[] terminalBytes = settlement.canonicalBytes();
    dsl.execute(
        "INSERT INTO "
            + SETTLEMENTS
            + " (protection_id, outcome, terminal_bytes, terminal_digest) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (protection_id) DO NOTHING",
        evidence.accountProtectionId(),
        settlement.outcome().name(),
        terminalBytes,
        settlement.digest());

    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + SETTLEMENTS + " WHERE protection_id = ?",
            evidence.accountProtectionId());
    if (row == null) throw unavailable();
    StoredSettlement stored = decodeSettlement(row, protection);
    if (!Arrays.equals(stored.canonicalBytes(), terminalBytes)) throw conflict();
    return stored;
  }

  private void lockOriginalSourceRows(AccountStartSessionAdmissionProtectionEvidence evidence) {
    List<String> expectedKeys =
        evidence.sourceEvidenceVector().stream().map(SourceEvidence::key).toList();
    Set<String> expectedKeySet = new HashSet<>(expectedKeys);
    if (expectedKeys.isEmpty() || expectedKeySet.size() != expectedKeys.size()) throw unavailable();
    var locked =
        dsl.fetch(
            "SELECT source_key FROM account_draft_authorization_source_locks "
                + "WHERE source_key = ANY(?::text[]) "
                + "ORDER BY account_publication_authorization_source_sort_key(source_key) "
                + "FOR UPDATE NOWAIT",
            (Object) expectedKeys.toArray(String[]::new));
    Set<String> lockedKeys = new HashSet<>();
    for (Record row : locked) {
      if (!lockedKeys.add(text(row, "source_key"))) throw unavailable();
    }
    if (locked.size() != expectedKeys.size() || !lockedKeys.equals(expectedKeySet)) {
      throw unavailable();
    }
  }

  private Record selectByRequestId(String requestId) {
    return dsl.fetchOne(
        "SELECT * FROM " + PROTECTIONS + " WHERE control_plane_request_id = ?", requestId);
  }

  private Record selectById(UUID protectionId) {
    return dsl.fetchOne("SELECT * FROM " + PROTECTIONS + " WHERE protection_id = ?", protectionId);
  }

  private List<SourceEvidence> readSources(UUID protectionId) {
    var rows =
        dsl.fetch(
            "SELECT * FROM " + SOURCES + " WHERE protection_id = ? ORDER BY source_key",
            protectionId);
    List<SourceEvidence> sources = new ArrayList<>(rows.size());
    for (Record row : rows) {
      requireColumns(row, SOURCE_COLUMNS, "admission source");
      if (!protectionId.equals(uuid(row, "protection_id"))) throw unavailable();
      SourceEvidence source = SourceEvidence.fromStored(bytes(row, "source_evidence"));
      if (!source.key().equals(text(row, "source_key"))) throw unavailable();
      sources.add(source);
    }
    if (sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
      throw unavailable();
    }
    return List.copyOf(sources);
  }

  private StoredProtection decodeProtection(Record row, List<SourceEvidence> sources) {
    requireColumns(row, PROTECTION_COLUMNS, "admission protection");
    UUID id = uuid(row, "protection_id");
    long fence = positive(number(row, "protection_fence"), "protection_fence");
    byte[] requestBytes = bytes(row, "request_binding_bytes");
    AccountStartSessionAdmissionProtectionRequest request =
        AccountStartSessionAdmissionProtectionRequest.decode(requestBytes);
    if (!Arrays.equals(requestBytes, request.canonicalBytes())
        || !text(row, "request_binding_digest").equals(digestPrefixed(requestBytes))) {
      throw unavailable();
    }
    requireStoredRequestFields(row, request);
    Record capture =
        dsl.fetchOne(
            "SELECT source_version, source_fence, canonical_sha256, canonical_capture_bytes "
                + "FROM account_start_session_authority_captures WHERE control_plane_request_id = ?",
            request.originalTuple().controlPlaneRequestId());
    if (capture == null
        || number(capture, "source_version") != number(row, "capture_source_version")
        || number(capture, "source_fence") != number(row, "capture_source_fence")
        || !text(capture, "canonical_sha256").equals(text(row, "capture_sha256"))) {
      throw unavailable();
    }
    byte[] captureReferenceBytes = bytes(capture, "canonical_capture_bytes");
    AccountStartSessionAdmissionProtectionEvidence evidence =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request, id, fence, captureReferenceBytes, text(row, "capture_sha256"), sources);
    return new StoredProtection(evidence, positive(number(row, "producer_xid"), "producer_xid"));
  }

  private static void requireStoredRequestFields(
      Record row, AccountStartSessionAdmissionProtectionRequest request) {
    var tuple = request.originalTuple();
    var scope = tuple.preAuthorizationTuple().action().scope();
    var hold = request.worldAdmissionHoldIdentity().request();
    if (!text(row, "control_plane_request_id").equals(tuple.controlPlaneRequestId())
        || !Arrays.equals(
            bytes(row, "original_post_authorization_tuple"),
            request.originalPostAuthorizationTupleBytes())
        || !Arrays.equals(
            bytes(row, "account_redemption_projection"), request.accountRedemptionProjectionBytes())
        || !uuid(row, "game_session_owner_mutation_id").equals(request.gameSessionOwnerMutationId())
        || !uuid(row, "game_session_owner_attempt_id").equals(request.gameSessionOwnerAttemptId())
        || number(row, "game_session_owner_fence") != request.gameSessionOwnerFence()
        || !timestamp(row, "original_lease_expires_at")
            .toInstant()
            .equals(request.originalLeaseExpiresAt())
        || !uuid(row, "account_world_participation_id")
            .equals(request.accountWorldParticipationId())
        || number(row, "account_world_participation_fence")
            != request.accountWorldParticipationFence()
        || !text(row, "target_namespace").equals(hold.targetNamespace())
        || !uuid(row, "canonical_tenant_id").equals(hold.canonicalTenantId())
        || !uuid(row, "canonical_game_instance_id").equals(hold.canonicalGameInstanceId())
        || !Arrays.equals(
            bytes(row, "world_admission_hold_identity_bytes"),
            request.worldAdmissionHoldIdentityBytes())
        || !scope.targetNamespace().equals(hold.targetNamespace())
        || !scope.tenantId().equals(hold.canonicalTenantId())) {
      throw unavailable();
    }
  }

  private static StoredSettlement decodeSettlement(Record row, StoredProtection protection) {
    requireColumns(row, SETTLEMENT_COLUMNS, "admission settlement");
    if (!protection.evidence().accountProtectionId().equals(uuid(row, "protection_id"))) {
      throw unavailable();
    }
    byte[] terminalBytes = bytes(row, "terminal_bytes");
    String digest = text(row, "terminal_digest");
    if (!digest.equals(digestPrefixed(terminalBytes))) throw unavailable();
    AccountStartSessionAdmissionProtectionSettlementEvidence settlement =
        AccountStartSessionAdmissionProtectionSettlementEvidence.decode(terminalBytes);
    requireSameEvidence(protection.evidence(), settlement.protection());
    if (!text(row, "outcome").equals(settlement.outcome().name())) throw unavailable();
    return new StoredSettlement(
        protection.evidence(),
        settlement.terminal(),
        settlement.outcome(),
        terminalBytes,
        digest,
        timestamp(row, "settled_at").toInstant());
  }

  private static void requireRequestBinding(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAuthorityCapture capture,
      StoredParticipation participation) {
    var hold = request.worldAdmissionHoldIdentity().request();
    if (!request
            .originalTuple()
            .controlPlaneRequestId()
            .equals(participation.controlPlaneRequestId())
        || !Arrays.equals(
            request.originalPostAuthorizationTupleBytes(),
            participation.originalPostAuthorizationTuple())
        || !request.accountWorldParticipationId().equals(participation.participationId())
        || request.accountWorldParticipationFence() != participation.participationFence()
        || !request.originalTuple().controlPlaneRequestId().equals(capture.controlPlaneRequestId())
        || !request.gameSessionOwnerAttemptId().equals(participation.gameSessionOwnerAttemptId())
        || request.gameSessionOwnerFence() != participation.gameSessionOwnerFence()
        || !hold.targetNamespace().equals(participation.targetNamespace())
        || !hold.canonicalTenantId().equals(participation.canonicalTenantId())
        || !hold.canonicalGameInstanceId().equals(participation.canonicalGameInstanceId())) {
      throw conflict();
    }
  }

  private static void requireSameOperation(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAuthorityCapture capture,
      StoredParticipation participation,
      StoredProtection stored) {
    requireRequestBinding(request, capture, participation);
    AccountStartSessionAdmissionProtectionEvidence evidence = stored.evidence();
    if (!Arrays.equals(request.canonicalBytes(), evidence.request().canonicalBytes())
        || !Arrays.equals(capture.canonicalBytes(), evidence.originalSourceCaptureReferenceBytes())
        || !capture.canonicalSha256().equals(evidence.originalSourceCaptureReferenceSha256())
        || !sameSources(participation.sourceVector(), evidence.sourceEvidenceVector())) {
      throw conflict();
    }
  }

  private static List<SourceEvidence> canonicalSources(List<SourceEvidence> sources) {
    List<SourceEvidence> ordered =
        Objects.requireNonNull(sources, "source vector is required").stream()
            .sorted(java.util.Comparator.comparing(SourceEvidence::key))
            .toList();
    if (ordered.isEmpty()
        || ordered.stream().map(SourceEvidence::key).distinct().count() != ordered.size()) {
      throw unavailable();
    }
    return List.copyOf(ordered);
  }

  private static boolean sameSources(List<SourceEvidence> left, List<SourceEvidence> right) {
    return canonicalSources(left).equals(canonicalSources(right));
  }

  private static void requireSameEvidence(
      AccountStartSessionAdmissionProtectionEvidence expected,
      AccountStartSessionAdmissionProtectionEvidence actual) {
    if (!Arrays.equals(expected.canonicalBytes(), actual.canonicalBytes())) throw conflict();
  }

  private static UUID protectionId(Record row) {
    if (row == null) throw unavailable();
    return uuid(row, "protection_id");
  }

  private static void requireColumns(Record row, Set<String> expected, String kind) {
    if (row == null) throw unavailable();
    Set<String> actual = new HashSet<>();
    Arrays.stream(row.fields()).forEach(field -> actual.add(field.getName()));
    if (!actual.equals(expected)) throw unavailable();
  }

  private static UUID uuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || value.equals(NIL_UUID)) throw unavailable();
    return value;
  }

  private static String text(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static byte[] bytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0) throw unavailable();
    return value.clone();
  }

  private static OffsetDateTime timestamp(Record row, String field) {
    OffsetDateTime value = row.get(field, OffsetDateTime.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static long number(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null) throw unavailable();
    return value;
  }

  private static long positive(long value, String field) {
    if (value <= 0L) throw unavailable();
    return value;
  }

  private static void requireNonnil(UUID value, String field) {
    if (value == null || value.equals(NIL_UUID))
      throw new IllegalArgumentException(field + " must be nonnil");
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0L) throw new IllegalArgumentException(field + " must be positive");
  }

  private static String digestPrefixed(byte[] bytes) {
    return "sha256:" + java.util.HexFormat.of().formatHex(sha256(bytes));
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account READ_COMMITTED transaction required");
    }
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException("Writable Account READ_COMMITTED transaction required");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("Writable Account READ_COMMITTED transaction required");
          }
        });
  }

  private static ImmutableBindingConflictException conflict() {
    return new ImmutableBindingConflictException(
        "Original StartSession admission protection conflicts with its immutable binding");
  }

  public static final class ImmutableBindingConflictException extends IllegalStateException {
    public ImmutableBindingConflictException(String message) {
      super(message);
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Exact original StartSession admission protection unavailable");
  }

  public record StoredProtection(
      AccountStartSessionAdmissionProtectionEvidence evidence, long producerTransactionId) {
    public StoredProtection {
      Objects.requireNonNull(evidence);
      if (producerTransactionId <= 0L)
        throw new IllegalArgumentException("producer XID must be positive");
    }
  }

  public record StoredSettlement(
      AccountStartSessionAdmissionProtectionEvidence protection,
      net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof terminal,
      net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof.Outcome
          outcome,
      byte[] canonicalBytes,
      String digest,
      Instant settledAt) {
    public StoredSettlement {
      Objects.requireNonNull(protection);
      Objects.requireNonNull(terminal);
      Objects.requireNonNull(outcome);
      canonicalBytes = Objects.requireNonNull(canonicalBytes).clone();
      Objects.requireNonNull(digest);
      Objects.requireNonNull(settledAt);
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }
}
