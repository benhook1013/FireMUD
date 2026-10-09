package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Non-admitting PRESEEDED_ONLY roster discovery over persisted, owner-resolved actors. */
public final class CanonicalGameplayRosterService {
  private final CanonicalGameplayRosterOwnerEvidencePort ownerEvidencePort;
  private final CharacterRepository characterRepository;

  public CanonicalGameplayRosterService(
      CanonicalGameplayRosterOwnerEvidencePort ownerEvidencePort,
      CharacterRepository characterRepository) {
    this.ownerEvidencePort = Objects.requireNonNull(ownerEvidencePort, "ownerEvidencePort");
    this.characterRepository = Objects.requireNonNull(characterRepository, "characterRepository");
  }

  public CanonicalGameplayRosterSnapshot read(CanonicalGameplayRosterReadRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoAmbientTransaction();
    requirePreseededOnly(request.expectedTarget());
    if (request.playerExecutionContext().characterUuid() != null) {
      throw new IllegalArgumentException("Roster discovery must not select a character");
    }

    CanonicalGameplayRosterOwnerEvidence evidence = ownerEvidencePort.resolveCurrentTarget(request);
    if (evidence == null) {
      throw new CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException();
    }
    requirePreseededOnly(evidence.target());
    if (!request.requestUuid().equals(evidence.requestUuid())
        || !request.canonicalAccountUuid().equals(evidence.canonicalAccountUuid())
        || !request.expectedTarget().equals(evidence.target())) {
      throw new IllegalStateException("CANONICAL_GAMEPLAY_ROSTER_OWNER_TARGET_MISMATCH");
    }

    return characterRepository.captureCanonicalGameplayRosterSnapshot(request, evidence);
  }

  private static void requirePreseededOnly(CanonicalGameplayRosterTarget target) {
    if (target.entryPolicy() != CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY) {
      throw new UnsupportedOperationException("CANONICAL_GAMEPLAY_ROSTER_ENTRY_POLICY_UNSUPPORTED");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "CANONICAL_GAMEPLAY_ROSTER_OWNER_READ_REQUIRES_NO_AMBIENT_TRANSACTION");
    }
  }
}
