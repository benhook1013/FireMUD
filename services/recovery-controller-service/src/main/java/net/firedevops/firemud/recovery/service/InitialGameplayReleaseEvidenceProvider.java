package net.firedevops.firemud.recovery.service;

import java.util.Objects;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.InitialGameplayReleaseEvidence;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;

/**
 * Reads immutable evidence from the actual peer, storage, Account, World, Entity, and lease owners.
 */
public interface InitialGameplayReleaseEvidenceProvider {
  EvidenceResolution read(Scope scope);

  record EvidenceResolution(ResolutionStatus status, InitialGameplayReleaseEvidence evidence) {
    public EvidenceResolution {
      Objects.requireNonNull(status, "status");
      if ((status == ResolutionStatus.AVAILABLE) != (evidence != null)) {
        throw new IllegalArgumentException("evidence must be present exactly for AVAILABLE");
      }
    }

    public static EvidenceResolution available(InitialGameplayReleaseEvidence evidence) {
      return new EvidenceResolution(ResolutionStatus.AVAILABLE, evidence);
    }

    public static EvidenceResolution denied() {
      return new EvidenceResolution(ResolutionStatus.DENIED, null);
    }

    public static EvidenceResolution notFound() {
      return new EvidenceResolution(ResolutionStatus.NOT_FOUND, null);
    }

    public static EvidenceResolution unavailable() {
      return new EvidenceResolution(ResolutionStatus.UNAVAILABLE, null);
    }
  }

  enum ResolutionStatus {
    AVAILABLE,
    DENIED,
    NOT_FOUND,
    UNAVAILABLE
  }
}
