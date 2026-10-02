package net.firedevops.firemud.worldmanagement.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldDto;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldRequest;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.service.InitialAdmissionBindHoldService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitialAdmissionBindHoldServiceImpl implements InitialAdmissionBindHoldService {
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_ACTIVE = "ACTIVE";
  private static final String STATUS_RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";
  private static final String STATUS_COMMITTED = "COMMITTED";
  private static final String STATUS_ABORTED = "ABORTED";
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
  private static final Duration DIAGNOSTIC_HOLD_AGE = Duration.ofMinutes(5);

  private final InitialAdmissionBindHoldRepository holdRepository;
  private final WorldInstanceRepository worldInstanceRepository;
  private final Clock clock;

  public InitialAdmissionBindHoldServiceImpl(
      InitialAdmissionBindHoldRepository holdRepository,
      WorldInstanceRepository worldInstanceRepository) {
    this(holdRepository, worldInstanceRepository, Clock.systemUTC());
  }

  InitialAdmissionBindHoldServiceImpl(
      InitialAdmissionBindHoldRepository holdRepository,
      WorldInstanceRepository worldInstanceRepository,
      Clock clock) {
    this.holdRepository = holdRepository;
    this.worldInstanceRepository = worldInstanceRepository;
    this.clock = clock;
  }

  @Override
  @Transactional
  public InitialAdmissionBindHoldDto acquire(InitialAdmissionBindHoldRequest request) {
    validateRequest(request);
    WorldInstance worldInstance =
        requireLockedWorldInstance(request.tenantId(), request.gameInstanceId());
    Optional<InitialAdmissionBindHold> existing =
        holdRepository.findByTenantIdAndRequestId(
            request.tenantId(), request.initialAdmissionRequestId());
    if (existing.isPresent()) {
      InitialAdmissionBindHold hold = existing.get();
      requireSameRequestIdentity(hold, request);
      if (isTerminal(hold.status())) {
        return toDto(hold);
      }
      requireActiveTarget(worldInstance, request);
      return toDto(hold);
    }

    requireActiveTarget(worldInstance, request);
    if (holdRepository.hasNonterminalForRealm(request.tenantId(), request.realmUuid())) {
      throw new IllegalArgumentException(
          "INITIAL_ADMISSION_BIND_HOLD_CONFLICT: realm has an unresolved initial admission hold");
    }

    Instant now = clock.instant();
    InitialAdmissionBindHold candidate =
        new InitialAdmissionBindHold(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            request.tenantId(),
            request.realmUuid(),
            request.playableStateNamespaceUuid(),
            request.playableStateScope(),
            request.gameInstanceId(),
            request.versionId(),
            request.activeLifecycleEpoch(),
            request.initialAdmissionRequestId(),
            request.requestDigest(),
            true,
            request.expectedCatalogRevision(),
            STATUS_PENDING,
            now.plus(DIAGNOSTIC_HOLD_AGE),
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null,
            0L);
    Optional<InitialAdmissionBindHold> inserted =
        holdRepository.insertIfNoUniqueConflict(candidate);
    if (inserted.isPresent()) {
      return toDto(inserted.get());
    }

    Optional<InitialAdmissionBindHold> racedRetry =
        holdRepository.findByTenantIdAndRequestId(
            request.tenantId(), request.initialAdmissionRequestId());
    if (racedRetry.isPresent()) {
      requireSameRequestIdentity(racedRetry.get(), request);
      return toDto(racedRetry.get());
    }
    throw new IllegalArgumentException(
        "INITIAL_ADMISSION_BIND_HOLD_CONFLICT: another unresolved hold owns this realm");
  }

  @Override
  @Transactional
  public InitialAdmissionBindHoldDto reconcileOwnerProof(
      String holdId, InitialAdmissionBindOwnerProof ownerProof) {
    InitialAdmissionBindHold hold = requireHold(holdId);
    if (ownerProof == null) {
      return requireReconciliation(holdId, "GS_OWNER_PROOF_MISSING");
    }
    String proofDigest = proofDigest(ownerProof);
    if (isTerminal(hold.status())) {
      if (isTerminal(ownerProof.outcome())
          && sameIdentity(hold, ownerProof)
          && hold.status().equals(statusFor(ownerProof.outcome()))
          && proofDigest.equals(hold.ownerProofDigest())) {
        return toDto(hold);
      }
      throw new IllegalArgumentException(
          "IDEMPOTENCY_CONFLICT: terminal initial admission proof changed");
    }

    if (!isTerminal(ownerProof.outcome())) {
      return markReconciliationRequired(hold, "GS_OWNER_" + outcomeName(ownerProof.outcome()));
    }
    if (!sameIdentity(hold, ownerProof)) {
      return markReconciliationRequired(hold, "GS_OWNER_PROOF_MISMATCH");
    }
    if (!hasDefinitiveTerminalEvidence(hold, ownerProof)) {
      return markReconciliationRequired(hold, "GS_OWNER_TERMINAL_PROOF_INCOMPLETE");
    }

    Optional<WorldInstance> maybeWorldInstance =
        worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(
            hold.tenantId(), hold.gameInstanceId());
    hold = requireLockedHold(holdId);
    if (isTerminal(hold.status())) {
      if (sameIdentity(hold, ownerProof)
          && hold.status().equals(statusFor(ownerProof.outcome()))
          && proofDigest.equals(hold.ownerProofDigest())) {
        return toDto(hold);
      }
      throw new IllegalArgumentException(
          "IDEMPOTENCY_CONFLICT: terminal initial admission proof changed");
    }
    if (!sameIdentity(hold, ownerProof) || !hasDefinitiveTerminalEvidence(hold, ownerProof)) {
      return markReconciliationRequired(hold, "GS_OWNER_PROOF_MISMATCH");
    }
    if (maybeWorldInstance.isEmpty()
        || !STATUS_ACTIVE.equals(maybeWorldInstance.get().getStatus())
        || !Objects.equals(hold.versionId(), maybeWorldInstance.get().getVersionId())
        || hold.activeLifecycleEpoch() != maybeWorldInstance.get().getLifecycleEpoch()) {
      return markReconciliationRequired(hold, "WORLD_LIFECYCLE_PROOF_MISMATCH");
    }

    String terminalStatus = statusFor(ownerProof.outcome());
    Optional<InitialAdmissionBindHold> updated =
        holdRepository.recordTerminalProof(
            hold,
            terminalStatus,
            ownerProof.ownerProofId(),
            proofDigest,
            terminalStatus.equals(STATUS_COMMITTED) ? ownerProof.pointerAuditId() : null,
            terminalStatus.equals(STATUS_COMMITTED) ? ownerProof.pointerVersion() : null,
            clock.instant());
    if (updated.isEmpty()) {
      InitialAdmissionBindHold current = requireLockedHold(holdId);
      if (isTerminal(current.status())
          && current.status().equals(terminalStatus)
          && proofDigest.equals(current.ownerProofDigest())) {
        return toDto(current);
      }
      throw new IllegalStateException("INITIAL_ADMISSION_BIND_HOLD_STALE: terminal update lost");
    }
    return toDto(updated.get());
  }

  @Override
  @Transactional
  public InitialAdmissionBindHoldDto requireReconciliation(String holdId, String errorCode) {
    InitialAdmissionBindHold hold = requireLockedHold(holdId);
    if (isTerminal(hold.status())) {
      return toDto(hold);
    }
    return markReconciliationRequired(hold, normalizeErrorCode(errorCode));
  }

  private InitialAdmissionBindHoldDto markReconciliationRequired(
      InitialAdmissionBindHold hold, String errorCode) {
    String normalizedErrorCode = normalizeErrorCode(errorCode);
    return holdRepository
        .markReconciliationRequired(hold, normalizedErrorCode, clock.instant())
        .map(this::toDto)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "INITIAL_ADMISSION_BIND_HOLD_STALE: reconciliation update lost"));
  }

  private void validateRequest(InitialAdmissionBindHoldRequest request) {
    if (request == null
        || request.tenantId() <= 0L
        || request.gameInstanceId() <= 0L
        || request.versionId() <= 0L
        || request.activeLifecycleEpoch() <= 0L
        || request.expectedCatalogRevision() <= 0L
        || !isSupportedPlayableStateScope(request.playableStateScope())
        || !request.expectedNoPriorPointer()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: initial admission bind requires a supported scope, positive target and catalog revisions, and an absent prior pointer");
    }
    requireText(request.initialAdmissionRequestId(), "initialAdmissionRequestId", 128);
    if (!SHA_256.matcher(nullToEmpty(request.requestDigest())).matches()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: requestDigest must be lowercase SHA-256 hex");
    }
    requireCanonicalUuid(request.realmUuid(), "realmUuid");
    requireCanonicalUuid(request.playableStateNamespaceUuid(), "playableStateNamespaceUuid");
  }

  private boolean isSupportedPlayableStateScope(String scope) {
    return "SHARED".equals(scope) || "ISOLATED".equals(scope);
  }

  private void requireActiveTarget(
      WorldInstance worldInstance, InitialAdmissionBindHoldRequest request) {
    if (!STATUS_ACTIVE.equals(worldInstance.getStatus())
        || !Objects.equals(request.versionId(), worldInstance.getVersionId())
        || request.activeLifecycleEpoch() != worldInstance.getLifecycleEpoch()) {
      throw new IllegalArgumentException(
          "WORLD_LIFECYCLE_PROOF_MISMATCH: target is not ACTIVE at the requested version and epoch");
    }
  }

  private void requireSameRequestIdentity(
      InitialAdmissionBindHold hold, InitialAdmissionBindHoldRequest request) {
    if (hold.tenantId() != request.tenantId()
        || hold.gameInstanceId() != request.gameInstanceId()
        || hold.versionId() != request.versionId()
        || hold.activeLifecycleEpoch() != request.activeLifecycleEpoch()
        || !hold.initialAdmissionRequestId().equals(request.initialAdmissionRequestId())
        || !hold.requestDigest().equals(request.requestDigest())
        || !hold.realmUuid().equals(request.realmUuid())
        || !hold.playableStateNamespaceUuid().equals(request.playableStateNamespaceUuid())
        || !hold.playableStateScope().equals(request.playableStateScope())
        || !hold.expectedNoPriorPointer()
        || !request.expectedNoPriorPointer()
        || hold.expectedCatalogRevision() != request.expectedCatalogRevision()) {
      throw new IllegalArgumentException(
          "IDEMPOTENCY_CONFLICT: initial admission request identity changed");
    }
  }

  private boolean sameIdentity(
      InitialAdmissionBindHold hold, InitialAdmissionBindOwnerProof proof) {
    return proof != null
        && hold.holdId().equals(proof.holdId())
        && hold.holdFence().equals(proof.holdFence())
        && hold.tenantId() == proof.tenantId()
        && hold.realmUuid().equals(proof.realmUuid())
        && hold.playableStateNamespaceUuid().equals(proof.playableStateNamespaceUuid())
        && hold.playableStateScope().equals(proof.playableStateScope())
        && hold.gameInstanceId() == proof.gameInstanceId()
        && hold.versionId() == proof.versionId()
        && hold.activeLifecycleEpoch() == proof.activeLifecycleEpoch()
        && hold.initialAdmissionRequestId().equals(proof.initialAdmissionRequestId())
        && hold.requestDigest().equals(proof.requestDigest())
        && hold.expectedNoPriorPointer() == proof.expectedNoPriorPointer()
        && hold.expectedCatalogRevision() == proof.expectedCatalogRevision();
  }

  private boolean hasDefinitiveTerminalEvidence(
      InitialAdmissionBindHold hold, InitialAdmissionBindOwnerProof proof) {
    if (proof.ownerProofId() == null || proof.ownerProofId().isBlank()) {
      return false;
    }
    if (proof.outcome() == InitialAdmissionBindOwnerProof.Outcome.COMMITTED) {
      return proof.expectedNoPriorPointer()
          && proof.pointerVersion() == 1L
          && proof.pointerAuditId() != null
          && !proof.pointerAuditId().isBlank()
          && hold.requestDigest().equals(proof.pointerAuditRequestDigest());
    }
    return proof.outcome() == InitialAdmissionBindOwnerProof.Outcome.ABORTED
        && proof.futureCommitPrevented()
        && (proof.pointerAuditId() == null || proof.pointerAuditId().isBlank())
        && proof.pointerVersion() == 0L;
  }

  private InitialAdmissionBindHold requireLockedHold(String holdId) {
    requireCanonicalUuid(holdId, "holdId");
    return holdRepository
        .findByHoldIdForUpdate(holdId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "INITIAL_ADMISSION_BIND_HOLD_NOT_FOUND: hold not found"));
  }

  private InitialAdmissionBindHold requireHold(String holdId) {
    requireCanonicalUuid(holdId, "holdId");
    return holdRepository
        .findByHoldId(holdId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "INITIAL_ADMISSION_BIND_HOLD_NOT_FOUND: hold not found"));
  }

  private WorldInstance requireLockedWorldInstance(long tenantId, long gameInstanceId) {
    return worldInstanceRepository
        .findByTenantIdAndGameInstanceIdForUpdate(tenantId, gameInstanceId)
        .orElseThrow(
            () ->
                new IllegalArgumentException("WORLD_INSTANCE_NOT_FOUND: world instance not found"));
  }

  private boolean isTerminal(String status) {
    return STATUS_COMMITTED.equals(status) || STATUS_ABORTED.equals(status);
  }

  private boolean isTerminal(InitialAdmissionBindOwnerProof.Outcome outcome) {
    return outcome == InitialAdmissionBindOwnerProof.Outcome.COMMITTED
        || outcome == InitialAdmissionBindOwnerProof.Outcome.ABORTED;
  }

  private String statusFor(InitialAdmissionBindOwnerProof.Outcome outcome) {
    return switch (outcome) {
      case COMMITTED -> STATUS_COMMITTED;
      case ABORTED -> STATUS_ABORTED;
      default -> throw new IllegalArgumentException("Owner proof is not terminal");
    };
  }

  private String outcomeName(InitialAdmissionBindOwnerProof.Outcome outcome) {
    return outcome == null ? "PROOF_INVALID" : outcome.name();
  }

  private String proofDigest(InitialAdmissionBindOwnerProof proof) {
    String canonical =
        String.join(
            "\n",
            outcomeName(proof.outcome()),
            nullToEmpty(proof.holdId()),
            nullToEmpty(proof.holdFence()),
            Long.toString(proof.tenantId()),
            nullToEmpty(proof.realmUuid()),
            nullToEmpty(proof.playableStateNamespaceUuid()),
            nullToEmpty(proof.playableStateScope()),
            Long.toString(proof.gameInstanceId()),
            Long.toString(proof.versionId()),
            Long.toString(proof.activeLifecycleEpoch()),
            nullToEmpty(proof.initialAdmissionRequestId()),
            nullToEmpty(proof.requestDigest()),
            Boolean.toString(proof.expectedNoPriorPointer()),
            Long.toString(proof.expectedCatalogRevision()),
            nullToEmpty(proof.ownerProofId()),
            nullToEmpty(proof.pointerAuditId()),
            Long.toString(proof.pointerVersion()),
            nullToEmpty(proof.pointerAuditRequestDigest()),
            Boolean.toString(proof.futureCommitPrevented()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 is required by the Java platform", exception);
    }
  }

  private InitialAdmissionBindHoldDto toDto(InitialAdmissionBindHold hold) {
    return new InitialAdmissionBindHoldDto(
        hold.holdId(),
        hold.holdFence(),
        hold.tenantId(),
        hold.realmUuid(),
        hold.playableStateNamespaceUuid(),
        hold.playableStateScope(),
        hold.gameInstanceId(),
        hold.versionId(),
        hold.activeLifecycleEpoch(),
        hold.initialAdmissionRequestId(),
        hold.requestDigest(),
        hold.expectedNoPriorPointer(),
        hold.expectedCatalogRevision(),
        hold.status(),
        hold.diagnosticExpiresAt());
  }

  private String normalizeErrorCode(String errorCode) {
    if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,128}")) {
      return "GS_OWNER_READ_UNAVAILABLE";
    }
    return errorCode;
  }

  private void requireCanonicalUuid(String value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: " + fieldName + " must be a UUID");
    }
    try {
      if (!UUID.fromString(value).toString().equals(value)) {
        throw new IllegalArgumentException("noncanonical UUID");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: " + fieldName + " must be a UUID");
    }
  }

  private void requireText(String value, String fieldName, int maxLength) {
    if (value == null
        || value.isBlank()
        || value.length() > maxLength
        || !value.equals(value.trim())
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: " + fieldName + " is invalid");
    }
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
