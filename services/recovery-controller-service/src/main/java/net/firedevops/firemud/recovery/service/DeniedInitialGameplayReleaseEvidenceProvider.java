package net.firedevops.firemud.recovery.service;

import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;

/** Safe default until the real authenticated peer and owner evidence adapters are registered. */
public class DeniedInitialGameplayReleaseEvidenceProvider
    implements InitialGameplayReleaseEvidenceProvider {
  @Override
  public EvidenceResolution read(Scope scope) {
    return EvidenceResolution.unavailable();
  }
}
