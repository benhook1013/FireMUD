package net.firedevops.firemud.gamedesign.service.impl;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository.AuthoredWorldVersionStateSnapshot;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads current version state only through the exact persisted authored-world source owner. */
@Service
public final class AuthoredWorldVersionStateService {
  private final GameAuthoredWorldSourceRepository sourceRepository;
  private final TransactionTemplate ownerSnapshot;

  public AuthoredWorldVersionStateService(
      PlatformTransactionManager transactionManager,
      GameAuthoredWorldSourceRepository sourceRepository) {
    this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository");
    this.ownerSnapshot = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.ownerSnapshot.setName("authored-world-version-state-read");
    this.ownerSnapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.ownerSnapshot.setReadOnly(true);
    this.ownerSnapshot.setTimeout(5);
  }

  /** Reads source provenance, selector binding, and exact version state from one fresh snapshot. */
  public AuthoredWorldVersionStateEvidence read(AuthoredWorldVersionStateEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    Optional<AuthoredWorldVersionStateSnapshot> snapshot =
        ownerSnapshot.execute(
            status ->
                sourceRepository.readVersionStateSnapshot(
                    request.targetNamespace(),
                    request.readRequestId(),
                    request.canonicalTenantId(),
                    request.worldSlug(),
                    request.sourceOperationId(),
                    request.expectedSourceEvidenceDigest(),
                    request.versionId()));
    if (snapshot == null || snapshot.isEmpty()) {
      throw new NotFoundException(
          "No authored-world source or version exists for the exact requested owner tuple");
    }
    AuthoredWorldVersionStateSnapshot result = snapshot.orElseThrow();
    return AuthoredWorldVersionStateEvidence.create(
        request,
        result.sourceEvidence(),
        result.canonicalVersionId(),
        toProtoState(result.versionState()),
        result.versionStateEpoch());
  }

  private static VersionLifecycleState toProtoState(
      net.firedevops.firemud.gamedesign.model.VersionLifecycleState state) {
    Objects.requireNonNull(state, "state");
    try {
      return VersionLifecycleState.valueOf("VERSION_LIFECYCLE_STATE_" + state.name());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Persisted version state has no closed wire enum", exception);
    }
  }

  public static final class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
      super(message);
    }
  }
}
