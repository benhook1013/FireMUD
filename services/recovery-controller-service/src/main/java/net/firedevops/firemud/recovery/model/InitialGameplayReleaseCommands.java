package net.firedevops.firemud.recovery.model;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Phase;

/** Typed inputs for the narrow initial gameplay release owner. */
public final class InitialGameplayReleaseCommands {
  private InitialGameplayReleaseCommands() {}

  public record ContinueRecovery(
      UUID operationId,
      Phase expectedPhase,
      MaintenanceLockCredential maintenanceLock,
      String evidenceRef) {
    public ContinueRecovery {
      operationId = InitialGameplayReleaseModel.requiredUuid(operationId, "operationId");
      Objects.requireNonNull(expectedPhase, "expectedPhase must not be null");
      Objects.requireNonNull(maintenanceLock, "maintenanceLock must not be null");
      evidenceRef = InitialGameplayReleaseModel.requiredText(evidenceRef, "evidenceRef");
      if (expectedPhase != Phase.READY_TO_REOPEN) {
        throw new IllegalArgumentException("continueRecovery requires ready_to_reopen");
      }
    }
  }

  public record Resume(
      UUID operationId,
      Phase expectedPhase,
      MaintenanceLockCredential maintenanceLock,
      String evidenceRef) {
    public Resume {
      operationId = InitialGameplayReleaseModel.requiredUuid(operationId, "operationId");
      Objects.requireNonNull(expectedPhase, "expectedPhase must not be null");
      Objects.requireNonNull(maintenanceLock, "maintenanceLock must not be null");
      evidenceRef = InitialGameplayReleaseModel.requiredText(evidenceRef, "evidenceRef");
      if (expectedPhase != Phase.AWAITING_RESUME) {
        throw new IllegalArgumentException("resume requires awaiting_resume");
      }
    }
  }
}
