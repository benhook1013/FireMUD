package net.firedevops.firemud.gamesession.service.impl;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository.PreparedExpectedClosed;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionService;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;

/**
 * Durable owner operation; remote World proof is acquired before entering its short SQL
 * transaction.
 */
public final class DatabaseCanonicalInitialAdmissionService
    implements CanonicalInitialAdmissionService {
  private final CanonicalInitialAdmissionRepository repository;
  private final GameSessionCanonicalAdmissionPointerRepository pointerRepository;
  private final CanonicalInitialAdmissionWorldVerifier worldVerifier;

  public DatabaseCanonicalInitialAdmissionService(
      CanonicalInitialAdmissionRepository repository,
      GameSessionCanonicalAdmissionPointerRepository pointerRepository,
      CanonicalInitialAdmissionWorldVerifier worldVerifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.pointerRepository = Objects.requireNonNull(pointerRepository, "pointerRepository");
    this.worldVerifier = Objects.requireNonNull(worldVerifier, "worldVerifier");
  }

  @Override
  public CanonicalInitialAdmissionOwnerProof bind(CanonicalInitialAdmissionRequest request) {
    Objects.requireNonNull(request, "request");
    repository.reserve(request);
    CanonicalInitialAdmissionOwnerProof beforeRemote =
        requireRead(request.targetNamespace(), request.initialAdmissionRequestId());
    if (beforeRemote.outcome() != CanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      return beforeRemote;
    }

    PreparedExpectedClosed preparedOrigin = prepareExpectedClosed(request);
    // This owner call is intentionally outside the repository transaction and can be retried by
    // the stable durable request identity after an unavailable or ambiguous response.
    CanonicalInitialAdmissionWorldProof worldProof = worldVerifier.verify(request);
    if (worldProof == null) {
      throw new IllegalStateException("World initial-admission verifier returned no owner proof");
    }
    worldProof.requireMatches(request);
    repository.commit(request, worldProof, preparedOrigin);

    CanonicalInitialAdmissionOwnerProof result =
        requireRead(request.targetNamespace(), request.initialAdmissionRequestId());
    if (result.outcome() == CanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      throw new CanonicalInitialAdmissionRepository
          .CanonicalInitialAdmissionReconciliationRequiredException(
          "Initial-admission owner transaction did not produce an exact terminal result readback");
    }
    return result;
  }

  @Override
  public CanonicalInitialAdmissionOwnerProof abort(
      CanonicalInitialAdmissionRequest request, String reason) {
    Objects.requireNonNull(request, "request");
    repository.reserve(request);
    CanonicalInitialAdmissionOwnerProof beforeAbort =
        requireRead(request.targetNamespace(), request.initialAdmissionRequestId());
    if (beforeAbort.outcome() != CanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      return beforeAbort;
    }
    PreparedExpectedClosed preparedOrigin = prepareExpectedClosed(request);
    repository.abort(request, preparedOrigin, reason);
    return requireRead(request.targetNamespace(), request.initialAdmissionRequestId());
  }

  @Override
  public CanonicalInitialAdmissionOwnerProof read(String targetNamespace, String requestId) {
    return requireRead(targetNamespace, requestId);
  }

  private PreparedExpectedClosed prepareExpectedClosed(CanonicalInitialAdmissionRequest request) {
    if (request.originKind() == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER) {
      return null;
    }
    return pointerRepository.prepareExpectedClosedForRealm(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.realmId(),
        request.expectedPriorPointerVersion(),
        request.expectedCatalogRevision());
  }

  private CanonicalInitialAdmissionOwnerProof requireRead(
      String targetNamespace, String requestId) {
    return repository
        .read(targetNamespace, requestId)
        .orElseThrow(
            () ->
                new CanonicalInitialAdmissionRepository
                    .CanonicalInitialAdmissionReconciliationRequiredException(
                    "Initial-admission attempt has no durable owner result"));
  }
}
