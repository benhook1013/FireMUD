package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered preliminary source-read producer; it grants no owner retention authority. */
public final class AccountSelectedOwnerIntakeSourceReservationService {
  private final AccountControlUiActorService actors;
  private final DraftAuthorizationFenceRepository fences;
  private final AccountSelectedOwnerIntakeSourceReservationRepository repository;
  private final String namespace;
  private final TransactionTemplate ownerTransaction;

  public AccountSelectedOwnerIntakeSourceReservationService(
      AccountControlUiActorService actors,
      DraftAuthorizationFenceRepository fences,
      AccountSelectedOwnerIntakeSourceReservationRepository repository,
      PlatformTransactionManager transactions,
      String namespace) {
    this.actors = Objects.requireNonNull(actors, "actors");
    this.fences = Objects.requireNonNull(fences, "fences");
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    this.namespace = namespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /**
   * Reserves one fresh source-read scope under the current creator, or recovers the exact existing
   * request by its original credential hash. An expired credential cannot create a reservation.
   */
  public SelectedOwnerIntakeSourceReadScope reserveSourceRead(
      String compactJwt,
      UUID intakeRequestId,
      Owner owner,
      DraftCommitBinding selected,
      CapturedEnvironmentBoundary environment) {
    requireGameDesignPeer();
    requireNoAmbientTransaction();
    requireOwner(owner);
    DraftAuthorizationFenceBinding.requireUuid(intakeRequestId);
    Objects.requireNonNull(selected, "selected");
    Objects.requireNonNull(environment, "current Account environment is required");

    Optional<SelectedOwnerIntakeSourceReadScope> retained =
        findScope(compactJwt, intakeRequestId, owner, selected);
    if (retained.isPresent()) {
      var recovery = recover(retained.orElseThrow());
      if (recovery.state() == AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED)
        throw Status.FAILED_PRECONDITION
            .withDescription("Original source-read reservation was aborted")
            .asRuntimeException();
      return recovery.scope();
    }

    return actors.withCurrent(
        compactJwt,
        selected.target().canonicalTenantId(),
        environment,
        current -> {
          fences.requireSelectedOwnerIntakeAdmission(current.source().sources(), selected);
          return repository.reserveSourceRead(
              intakeRequestId, owner, selected, current, namespace, () -> {});
        });
  }

  /** Historical exact recovery; it never renews authority or reopens an aborted reservation. */
  public AccountSelectedOwnerIntakeSourceReservationRepository.Recovery recover(
      SelectedOwnerIntakeSourceReadScope scope) {
    requireGameDesignPeer();
    requireScope(scope);
    requireNoAmbientTransaction();
    return Objects.requireNonNull(
        ownerTransaction.execute(ignored -> repository.recoverSourceRead(scope)));
  }

  /** Finds a lost first response from the original stable identity and exact credential hash. */
  public AccountSelectedOwnerIntakeSourceReservationRepository.Recovery recover(
      String originalCompactJwt, UUID intakeRequestId, Owner owner, DraftCommitBinding selected) {
    requireGameDesignPeer();
    requireNoAmbientTransaction();
    requireOwner(owner);
    Objects.requireNonNull(selected, "selected");
    var scope =
        findScope(originalCompactJwt, intakeRequestId, owner, selected)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Original source-read reservation is unavailable")
                        .asRuntimeException());
    return recover(scope);
  }

  /** A definitive abort locks only this reservation's sorted source participation. */
  public AccountSelectedOwnerIntakeSourceReservationRepository.Recovery abortSourceRead(
      SelectedOwnerIntakeSourceReadScope scope) {
    requireGameDesignPeer();
    requireScope(scope);
    requireNoAmbientTransaction();
    return Objects.requireNonNull(
        ownerTransaction.execute(ignored -> repository.abortSourceRead(scope)));
  }

  /** Aborts the exact original operation even after credential expiry; it does not renew it. */
  public AccountSelectedOwnerIntakeSourceReservationRepository.Recovery abortSourceRead(
      String originalCompactJwt, UUID intakeRequestId, Owner owner, DraftCommitBinding selected) {
    requireGameDesignPeer();
    requireNoAmbientTransaction();
    requireOwner(owner);
    Objects.requireNonNull(selected, "selected");
    var scope =
        findScope(originalCompactJwt, intakeRequestId, owner, selected)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Original source-read reservation is unavailable")
                        .asRuntimeException());
    return abortSourceRead(scope);
  }

  private Optional<SelectedOwnerIntakeSourceReadScope> findScope(
      String originalCompactJwt, UUID intakeRequestId, Owner owner, DraftCommitBinding selected) {
    requireGameDesignPeer();
    requireNoAmbientTransaction();
    DraftAuthorizationFenceBinding.requireUuid(intakeRequestId);
    requireOwner(owner);
    Objects.requireNonNull(selected, "selected");
    if (originalCompactJwt == null
        || originalCompactJwt.isEmpty()
        || originalCompactJwt.length() > 16384
        || !StandardCharsets.US_ASCII.newEncoder().canEncode(originalCompactJwt)) {
      throw Status.UNAUTHENTICATED.asRuntimeException();
    }
    String tokenHash =
        AccountControlUiIssuanceRepository.hash(
            originalCompactJwt.getBytes(StandardCharsets.US_ASCII));
    return Objects.requireNonNull(
        ownerTransaction.execute(
            ignored ->
                repository.findSourceReadScope(
                    intakeRequestId, owner, selected, namespace, tokenHash)));
  }

  private void requireScope(SelectedOwnerIntakeSourceReadScope scope) {
    if (scope == null
        || !namespace.equals(scope.targetNamespace())
        || (scope.owner() != Owner.ENTITY_MANAGEMENT
            && scope.owner() != Owner.AUTOMATION_SCRIPTING))
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }

  private static void requireOwner(Owner owner) {
    if (owner != Owner.ENTITY_MANAGEMENT && owner != Owner.AUTOMATION_SCRIPTING)
      throw Status.INVALID_ARGUMENT
          .withDescription("Entity or Automation selected-source owner required")
          .asRuntimeException();
  }

  private void requireGameDesignPeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Exact same-namespace Game Design workload without end-user context required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Selected-owner Account reservation requires an independent transaction")
          .asRuntimeException();
    }
  }
}
