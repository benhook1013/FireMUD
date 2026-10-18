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
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable Account storage for protection of one exact original StartSession activation. */
@Repository
public class AccountStartSessionActivationProtectionRepository {
  private static final String PROTECTIONS = "account_start_session_activation_protections";
  private static final String SOURCES = "account_start_session_activation_protection_sources";
  private static final String SETTLEMENTS =
      "account_start_session_activation_protection_settlements";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private static final Set<String> PROTECTION_COLUMNS =
      Set.of(
          "activation_protection_id",
          "activation_protection_fence",
          "control_plane_request_id",
          "account_world_participation_id",
          "account_world_participation_fence",
          "original_post_authorization_tuple",
          "account_redemption_projection",
          "game_session_owner_mutation_id",
          "game_session_owner_attempt_id",
          "game_session_owner_fence",
          "original_lease_expires_at",
          "target_namespace",
          "canonical_tenant_id",
          "canonical_game_instance_id",
          "world_activation_request_id",
          "game_session_launch_read_evidence",
          "world_preparing_evidence",
          "capture_source_version",
          "capture_source_fence",
          "capture_sha256",
          "capture_reference_bytes",
          "capture_reference_sha256",
          "operation_identity_bytes",
          "operation_identity_digest",
          "request_binding_bytes",
          "request_binding_digest",
          "producer_xid",
          "acquired_at");
  private static final Set<String> SOURCE_COLUMNS =
      Set.of("activation_protection_id", "source_key", "source_evidence");
  private static final Set<String> SETTLEMENT_COLUMNS =
      Set.of(
          "activation_protection_id",
          "activation_protection_fence",
          "world_activation_request_id",
          "world_activation_fence",
          "outcome",
          "account_protection_evidence_bytes",
          "terminal_bytes",
          "terminal_digest",
          "settled_at");

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve the injected DSLContext precondition; this transactional Account repository must remain proxyable and owns no resources.")
  public AccountStartSessionActivationProtectionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Inserts the first exact protection and complete source vector, or returns its immutable winner.
   *
   * <p>Call only inside the Account original-currentness callback, after the authenticated World
   * peer and independent Game Session observations have been checked. The database guards remain
   * the owner of source-key ordering, exact retained-row validation, and the finite-lease check.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredProtection acquireOrReadExact(
      AccountStartSessionActivationProtectionRequest request,
      AccountStartSessionAuthorityCapture capture,
      StoredParticipation participation) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(request, "exact Account activation request is required");
    Objects.requireNonNull(capture, "exact Account source capture is required");
    Objects.requireNonNull(participation, "committed Account World participation is required");
    requireRequestBinding(request, capture, participation);

    byte[] requestBytes = request.canonicalBytes();
    byte[] operationBytes = request.operationIdentityBytes();
    List<SourceEvidence> sources = canonicalSources(participation.sourceVector());
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + PROTECTIONS
                + " (control_plane_request_id, account_world_participation_id, "
                + "account_world_participation_fence, original_post_authorization_tuple, "
                + "account_redemption_projection, game_session_owner_mutation_id, "
                + "game_session_owner_attempt_id, game_session_owner_fence, original_lease_expires_at, "
                + "target_namespace, canonical_tenant_id, canonical_game_instance_id, "
                + "world_activation_request_id, game_session_launch_read_evidence, "
                + "world_preparing_evidence, capture_source_version, capture_source_fence, "
                + "capture_sha256, capture_reference_bytes, capture_reference_sha256, "
                + "operation_identity_bytes, operation_identity_digest, request_binding_bytes, "
                + "request_binding_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (control_plane_request_id) DO NOTHING",
            request.originalTuple().controlPlaneRequestId(),
            participation.participationId(),
            participation.participationFence(),
            request.originalPostAuthorizationTupleBytes(),
            request.accountRedemptionProjectionBytes(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerFence(),
            OffsetDateTime.ofInstant(request.originalLeaseExpiresAt(), ZoneOffset.UTC),
            participation.targetNamespace(),
            participation.canonicalTenantId(),
            request.worldActivationRequest().canonicalGameInstanceId(),
            request.worldActivationRequest().activationRequestId(),
            request.gameSessionLaunchReadEvidenceBytes(),
            request.worldActivationRequest().preparingEvidenceBytes(),
            capture.sourceVersion(),
            capture.sourceFence(),
            capture.canonicalSha256(),
            capture.canonicalBytes(),
            capture.canonicalSha256(),
            operationBytes,
            digestPrefixed(operationBytes),
            requestBytes,
            digestPrefixed(requestBytes));

    if (inserted == 1) {
      for (SourceEvidence source : sources) {
        dsl.execute(
            "INSERT INTO "
                + SOURCES
                + " (activation_protection_id, source_key, source_evidence) "
                + "SELECT activation_protection_id, ?, ? FROM "
                + PROTECTIONS
                + " WHERE control_plane_request_id = ?",
            source.key(),
            source.canonicalBytes(),
            request.originalTuple().controlPlaneRequestId());
      }
    }

    Record row = selectProtectionByRequestId(request.originalTuple().controlPlaneRequestId());
    StoredProtection stored = decodeProtection(row, readSources(protectionId(row)));
    requireSameOperation(request, capture, participation, stored);
    if (inserted == 1 && stored.producerTransactionId() <= 0L) throw unavailable();
    return stored;
  }

  /** Lookup-only exact historical read for World terminal settlement and recovery. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredProtection> findHistoricalExact(UUID protectionId, long protectionFence) {
    requireWritableReadCommittedTransaction();
    requireNonnil(protectionId, "protectionId");
    requirePositive(protectionFence, "protectionFence");
    Record row = selectProtectionById(protectionId);
    if (row == null) return Optional.empty();
    StoredProtection stored = decodeProtection(row, readSources(protectionId));
    if (stored.evidence().accountProtectionFence() != protectionFence) return Optional.empty();
    return Optional.of(stored);
  }

  /** Lookup-only readback of the exact immutable settlement; this never writes or checks expiry. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<StoredSettlement> findSettlementExact(UUID protectionId, long protectionFence) {
    requireWritableReadCommittedTransaction();
    Optional<StoredProtection> protection = findHistoricalExact(protectionId, protectionFence);
    if (protection.isEmpty()) return Optional.empty();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + SETTLEMENTS + " WHERE activation_protection_id = ?", protectionId);
    if (row == null) return Optional.empty();
    return Optional.of(decodeSettlement(row, protection.orElseThrow()));
  }

  /** Appends the exact authenticated World receipt or reads its immutable winner on retry. */
  @Transactional(propagation = Propagation.MANDATORY)
  public StoredSettlement settleExact(
      WorldOriginalStartSessionActivationTerminalEvidence terminal) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(terminal, "authenticated World activation terminal is required");
    var accountEvidence = terminal.accountProtectionEvidence();
    StoredProtection protection =
        findHistoricalExact(
                accountEvidence.accountProtectionId(), accountEvidence.accountProtectionFence())
            .orElseThrow(AccountStartSessionActivationProtectionRepository::unavailable);
    requireSameEvidence(protection.evidence(), accountEvidence);
    lockOriginalSourceRows(protection.evidence());
    Record locked =
        dsl.fetchOne(
            "SELECT * FROM "
                + PROTECTIONS
                + " WHERE activation_protection_id = ? FOR UPDATE NOWAIT",
            accountEvidence.accountProtectionId());
    if (locked == null) throw unavailable();
    protection = decodeProtection(locked, readSources(accountEvidence.accountProtectionId()));
    requireSameEvidence(protection.evidence(), accountEvidence);

    dsl.execute(
        "INSERT INTO "
            + SETTLEMENTS
            + " (activation_protection_id, activation_protection_fence, world_activation_request_id, "
            + "world_activation_fence, outcome, account_protection_evidence_bytes, terminal_bytes, "
            + "terminal_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (activation_protection_id) DO NOTHING",
        accountEvidence.accountProtectionId(),
        accountEvidence.accountProtectionFence(),
        accountEvidence.request().worldActivationRequest().activationRequestId(),
        terminal.worldActivationFence(),
        terminal.outcome().name(),
        accountEvidence.canonicalBytes(),
        terminal.canonicalBytes(),
        terminal.terminalDigest());

    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + SETTLEMENTS + " WHERE activation_protection_id = ?",
            accountEvidence.accountProtectionId());
    if (row == null) throw unavailable();
    StoredSettlement stored = decodeSettlement(row, protection);
    if (!Arrays.equals(stored.terminal().canonicalBytes(), terminal.canonicalBytes())) {
      throw conflict();
    }
    return stored;
  }

  private void lockOriginalSourceRows(AccountStartSessionActivationProtectionEvidence evidence) {
    for (SourceEvidence source :
        evidence.sourceEvidenceVector().stream()
            .sorted(java.util.Comparator.comparing(SourceEvidence::key))
            .toList()) {
      Record locked =
          dsl.fetchOne(
              "SELECT source_key FROM account_draft_authorization_source_locks "
                  + "WHERE source_key = ? FOR UPDATE NOWAIT",
              source.key());
      if (locked == null || !source.key().equals(text(locked, "source_key"))) {
        throw unavailable();
      }
    }
  }

  private Record selectProtectionByRequestId(String requestId) {
    return dsl.fetchOne(
        "SELECT * FROM " + PROTECTIONS + " WHERE control_plane_request_id = ?", requestId);
  }

  private Record selectProtectionById(UUID protectionId) {
    return dsl.fetchOne(
        "SELECT * FROM " + PROTECTIONS + " WHERE activation_protection_id = ?", protectionId);
  }

  private List<SourceEvidence> readSources(UUID protectionId) {
    var rows =
        dsl.fetch(
            "SELECT * FROM " + SOURCES + " WHERE activation_protection_id = ? ORDER BY source_key",
            protectionId);
    List<SourceEvidence> sources = new ArrayList<>(rows.size());
    for (Record row : rows) {
      requireColumns(row, SOURCE_COLUMNS, "activation source");
      if (!protectionId.equals(uuid(row, "activation_protection_id"))) throw unavailable();
      SourceEvidence source = SourceEvidence.fromStored(bytes(row, "source_evidence"));
      if (!source.key().equals(text(row, "source_key"))) throw unavailable();
      sources.add(source);
    }
    if (sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
      throw unavailable();
    }
    return List.copyOf(sources);
  }

  private static StoredProtection decodeProtection(Record row, List<SourceEvidence> sources) {
    requireColumns(row, PROTECTION_COLUMNS, "activation protection");
    UUID id = uuid(row, "activation_protection_id");
    long fence =
        positive(number(row, "activation_protection_fence"), "activation_protection_fence");
    byte[] requestBytes = bytes(row, "request_binding_bytes");
    AccountStartSessionActivationProtectionRequest request =
        AccountStartSessionActivationProtectionRequest.decode(requestBytes);
    byte[] operationBytes = bytes(row, "operation_identity_bytes");
    byte[] captureBytes = bytes(row, "capture_reference_bytes");
    String captureDigest = text(row, "capture_reference_sha256");
    Instant acquiredAt = timestamp(row, "acquired_at").toInstant();

    if (!Arrays.equals(requestBytes, request.canonicalBytes())
        || !Arrays.equals(operationBytes, request.operationIdentityBytes())
        || !text(row, "request_binding_digest").equals(digestPrefixed(requestBytes))
        || !text(row, "operation_identity_digest").equals(digestPrefixed(operationBytes))
        || !text(row, "capture_sha256").equals(captureDigest)
        || !captureDigest.equals(digestBare(captureBytes))) {
      throw unavailable();
    }
    requireStoredRequestFields(row, request);
    AccountStartSessionActivationProtectionEvidence evidence =
        AccountStartSessionActivationProtectionEvidence.create(
            request, id, fence, acquiredAt, captureBytes, captureDigest, sources);
    return new StoredProtection(evidence, positive(number(row, "producer_xid"), "producer_xid"));
  }

  private static StoredSettlement decodeSettlement(Record row, StoredProtection protection) {
    requireColumns(row, SETTLEMENT_COLUMNS, "activation settlement");
    AccountStartSessionActivationProtectionEvidence expected = protection.evidence();
    if (!expected.accountProtectionId().equals(uuid(row, "activation_protection_id"))
        || expected.accountProtectionFence() != number(row, "activation_protection_fence")
        || !expected
            .request()
            .worldActivationRequest()
            .activationRequestId()
            .equals(uuid(row, "world_activation_request_id"))) {
      throw unavailable();
    }
    byte[] evidenceBytes = bytes(row, "account_protection_evidence_bytes");
    if (!Arrays.equals(evidenceBytes, expected.canonicalBytes())) throw unavailable();
    byte[] terminalBytes = bytes(row, "terminal_bytes");
    String terminalDigest = text(row, "terminal_digest");
    if (!terminalDigest.equals(digestPrefixed(terminalBytes))) throw unavailable();
    WorldOriginalStartSessionActivationTerminalEvidence terminal =
        WorldOriginalStartSessionActivationTerminalEvidence.decode(terminalBytes);
    requireSameEvidence(expected, terminal.accountProtectionEvidence());
    long worldFence = positive(number(row, "world_activation_fence"), "world_activation_fence");
    if (terminal.worldActivationFence() != worldFence
        || !text(row, "outcome").equals(terminal.outcome().name())) throw unavailable();
    Instant settledAt = timestamp(row, "settled_at").toInstant();
    return new StoredSettlement(terminal, settledAt, terminalBytes, terminalDigest);
  }

  private static void requireRequestBinding(
      AccountStartSessionActivationProtectionRequest request,
      AccountStartSessionAuthorityCapture capture,
      StoredParticipation participation) {
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
        || request.gameSessionOwnerFence() != participation.gameSessionOwnerFence()) {
      throw conflict();
    }
  }

  private static void requireSameOperation(
      AccountStartSessionActivationProtectionRequest request,
      AccountStartSessionAuthorityCapture capture,
      StoredParticipation participation,
      StoredProtection stored) {
    requireRequestBinding(request, capture, participation);
    AccountStartSessionActivationProtectionEvidence evidence = stored.evidence();
    if (!Arrays.equals(
            request.operationIdentityBytes(), evidence.request().operationIdentityBytes())
        || !Arrays.equals(capture.canonicalBytes(), evidence.originalSourceCaptureReferenceBytes())
        || !capture.canonicalSha256().equals(evidence.originalSourceCaptureReferenceSha256())
        || !sameSources(participation.sourceVector(), evidence.sourceEvidenceVector())) {
      throw conflict();
    }
  }

  private static void requireStoredRequestFields(
      Record row, AccountStartSessionActivationProtectionRequest request) {
    var originalTuple = request.originalTuple();
    var activation = request.worldActivationRequest();
    if (!text(row, "control_plane_request_id").equals(originalTuple.controlPlaneRequestId())
        || !uuid(row, "account_world_participation_id")
            .equals(request.accountWorldParticipationId())
        || number(row, "account_world_participation_fence")
            != request.accountWorldParticipationFence()
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
        || !text(row, "target_namespace")
            .equals(originalTuple.preAuthorizationTuple().action().scope().targetNamespace())
        || !uuid(row, "canonical_tenant_id")
            .equals(originalTuple.preAuthorizationTuple().action().scope().tenantId())
        || !uuid(row, "canonical_game_instance_id").equals(activation.canonicalGameInstanceId())
        || !uuid(row, "world_activation_request_id").equals(activation.activationRequestId())
        || !Arrays.equals(
            bytes(row, "game_session_launch_read_evidence"),
            request.gameSessionLaunchReadEvidenceBytes())
        || !Arrays.equals(
            bytes(row, "world_preparing_evidence"), activation.preparingEvidenceBytes())) {
      throw unavailable();
    }
  }

  private static List<SourceEvidence> canonicalSources(List<SourceEvidence> sources) {
    List<SourceEvidence> ordered =
        Objects.requireNonNull(sources, "source evidence vector is required").stream()
            .sorted(java.util.Comparator.comparing(SourceEvidence::key))
            .toList();
    if (ordered.isEmpty()
        || ordered.stream().map(SourceEvidence::key).distinct().count() != ordered.size()) {
      throw unavailable();
    }
    return List.copyOf(ordered);
  }

  private static boolean sameSources(List<SourceEvidence> left, List<SourceEvidence> right) {
    List<SourceEvidence> orderedLeft = canonicalSources(left);
    List<SourceEvidence> orderedRight = canonicalSources(right);
    return orderedLeft.equals(orderedRight);
  }

  private static void requireSameEvidence(
      AccountStartSessionActivationProtectionEvidence expected,
      AccountStartSessionActivationProtectionEvidence actual) {
    if (!Arrays.equals(expected.canonicalBytes(), actual.canonicalBytes())) throw conflict();
  }

  private static UUID protectionId(Record row) {
    if (row == null) throw unavailable();
    return uuid(row, "activation_protection_id");
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
    return "sha256:" + digestBare(bytes);
  }

  private static String digestBare(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
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

  private static IllegalStateException conflict() {
    return new IllegalStateException(
        "Original StartSession activation protection conflicts with its immutable binding");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Exact original StartSession activation protection unavailable");
  }

  public record StoredProtection(
      AccountStartSessionActivationProtectionEvidence evidence, long producerTransactionId) {
    public StoredProtection {
      Objects.requireNonNull(evidence);
      if (producerTransactionId <= 0L)
        throw new IllegalArgumentException("producer XID must be positive");
    }
  }

  public record StoredSettlement(
      WorldOriginalStartSessionActivationTerminalEvidence terminal,
      Instant settledAt,
      byte[] terminalBytes,
      String terminalDigest) {
    public StoredSettlement {
      Objects.requireNonNull(terminal);
      Objects.requireNonNull(settledAt);
      terminalBytes = Objects.requireNonNull(terminalBytes).clone();
      Objects.requireNonNull(terminalDigest);
    }

    @Override
    public byte[] terminalBytes() {
      return terminalBytes.clone();
    }
  }
}
