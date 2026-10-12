package net.firedevops.firemud.gamesession.service.impl;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Safe default unless the typed authenticated World initial-admission owner is explicitly composed.
 * Caller context, local configuration, and supplied hold identifiers are never owner proof.
 */
@Component
@ConditionalOnProperty(
    prefix = "firemud.canonical-initial-admission-world-owner",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true)
public final class DefaultDeniedCanonicalInitialAdmissionWorldVerifier
    implements CanonicalInitialAdmissionWorldVerifier {
  @Override
  public CanonicalInitialAdmissionWorldProof verify(CanonicalInitialAdmissionRequest request) {
    Objects.requireNonNull(request, "request");
    throw new IllegalStateException(
        "Canonical initial admission is denied until authenticated World ACTIVE/hold verification is wired");
  }
}
