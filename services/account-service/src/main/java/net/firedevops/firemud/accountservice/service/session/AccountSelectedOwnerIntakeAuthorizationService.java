package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceClient;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered distinct Account producer for selected Entity and Automation intake authority. */
public final class AccountSelectedOwnerIntakeAuthorizationService {
  private static final int MAX_CREATOR_CREDENTIAL_LENGTH = 16384;

  private final AccountControlUiActorService actors;
  private final DraftAuthorizationFenceRepository fences;
  private final AccountSelectedOwnerIntakeSourceReservationRepository repository;
  private final SelectedOwnerIntakeSourceClient sources;
  private final String namespace;
  private final TransactionTemplate ownerTransaction;

  public AccountSelectedOwnerIntakeAuthorizationService(
      AccountControlUiActorService actors,
      DraftAuthorizationFenceRepository fences,
      AccountSelectedOwnerIntakeSourceReservationRepository repository,
      SelectedOwnerIntakeSourceClient sources,
      PlatformTransactionManager transactions,
      String namespace) {
    this.actors = Objects.requireNonNull(actors, "actors");
    this.fences = Objects.requireNonNull(fences, "fences");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.sources = Objects.requireNonNull(sources, "sources");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    this.namespace = namespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /**
   * Reserves one distinct source-read operation, obtains exact selected content outside SQL, and
   * finalizes that same operation under a freshly captured current creator and held-source vector.
   * Exact final recovery happens before the mutable environment supplier is invoked.
   */
  public SelectedOwnerIntakeAuthorizationBinding authorizeWithEnvironmentCapture(
      String originalCreatorCredential,
      UUID intakeRequestId,
      Owner owner,
      DraftCommitBinding selected,
      Supplier<CapturedEnvironmentBoundary> environmentCapture) {
    requireGameDesignPeer();
    outsideSql();
    DraftAuthorizationFenceBinding.requireUuid(intakeRequestId);
    requireOwner(owner);
    Objects.requireNonNull(selected, "selected Draft binding is required");
    Objects.requireNonNull(environmentCapture, "current Account environment capture is required");

    Optional<SelectedOwnerIntakeSourceReadScope> retained =
        findScope(originalCreatorCredential, intakeRequestId, owner, selected);
    SelectedOwnerIntakeSourceReadScope scope = null;
    if (retained.isPresent()) {
      scope = retained.orElseThrow();
      var recovery = recover(scope);
      if (recovery.state() == AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED)
        throw denied("Original selected-owner intake reservation was aborted");
      if (recovery.state()
          == AccountSelectedOwnerIntakeSourceReservationRepository.State.FINALIZED) {
        var original =
            findFinalAuthorization(scope)
                .orElseThrow(
                    () -> denied("Finalized selected-owner intake authorization is unavailable"));
        return readFinalAuthorization(original);
      }
    } else {
      var initialEnvironment = requireEnvironment(environmentCapture.get());
      scope =
          reserveSourceRead(
              originalCreatorCredential, intakeRequestId, owner, selected, initialEnvironment);
      var recovery = recover(scope);
      if (recovery.state() == AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED)
        throw denied("Original selected-owner intake reservation was aborted");
      if (recovery.state()
          == AccountSelectedOwnerIntakeSourceReservationRepository.State.FINALIZED) {
        var original =
            findFinalAuthorization(scope)
                .orElseThrow(
                    () -> denied("Finalized selected-owner intake authorization is unavailable"));
        return readFinalAuthorization(original);
      }
    }

    var request = SelectedOwnerIntakeSourceReadEvidence.Request.create(namespace, scope);
    SelectedOwnerIntakeSourceReadScope reservationScope = scope;
    outsideSql();
    // The authenticated Game Design client enforces exact request echo, wire closure, six-family
    // content integrity and the absence of SQL or transaction synchronization during this call.
    var received = sources.read(request);
    var content = requireExactContent(request, received);

    var finalizationEnvironment = requireEnvironment(environmentCapture.get());
    SelectedOwnerIntakeAuthorizationBinding finalized;
    try {
      finalized =
          actors.withCurrent(
              originalCreatorCredential,
              selected.target().canonicalTenantId(),
              finalizationEnvironment,
              current ->
                  repository.finalizeSourceRead(
                      reservationScope,
                      content,
                      current,
                      () ->
                          fences.requireSelectedOwnerIntakeAdmission(
                              current.source().sources(), selected)));
    } catch (RuntimeException finalizationFailure) {
      // A commit acknowledgement can be lost after the immutable authorization commits. Recover
      // only the exact same content and operation; never allocate a replacement scope or fence.
      Optional<SelectedOwnerIntakeAuthorizationBinding> committed;
      try {
        committed = findFinalAuthorization(reservationScope);
      } catch (RuntimeException readFailure) {
        if (readFailure != finalizationFailure) finalizationFailure.addSuppressed(readFailure);
        throw finalizationFailure;
      }
      if (committed.isEmpty()
          || !java.util.Arrays.equals(
              committed.orElseThrow().content().canonicalBytes(), content.canonicalBytes())) {
        throw finalizationFailure;
      }
      finalized = committed.orElseThrow();
    }
    return readFinalAuthorization(finalized);
  }

  private SelectedOwnerIntakeSourceReadScope reserveSourceRead(
      String originalCreatorCredential,
      UUID intakeRequestId,
      Owner owner,
      DraftCommitBinding selected,
      CapturedEnvironmentBoundary environment) {
    return actors.withCurrent(
        originalCreatorCredential,
        selected.target().canonicalTenantId(),
        environment,
        current ->
            repository.reserveSourceRead(
                intakeRequestId,
                owner,
                selected,
                current,
                namespace,
                () ->
                    fences.requireSelectedOwnerIntakeAdmission(
                        current.source().sources(), selected)));
  }

  private Optional<SelectedOwnerIntakeSourceReadScope> findScope(
      String originalCreatorCredential,
      UUID intakeRequestId,
      Owner owner,
      DraftCommitBinding selected) {
    requireGameDesignPeer();
    outsideSql();
    if (originalCreatorCredential == null
        || originalCreatorCredential.isEmpty()
        || originalCreatorCredential.length() > MAX_CREATOR_CREDENTIAL_LENGTH
        || !StandardCharsets.US_ASCII.newEncoder().canEncode(originalCreatorCredential)) {
      throw Status.UNAUTHENTICATED.asRuntimeException();
    }
    String tokenHash =
        AccountControlUiIssuanceRepository.hash(
            originalCreatorCredential.getBytes(StandardCharsets.US_ASCII));
    return Objects.requireNonNull(
        ownerTransaction.execute(
            ignored ->
                repository.findSourceReadScope(
                    intakeRequestId, owner, selected, namespace, tokenHash)));
  }

  private AccountSelectedOwnerIntakeSourceReservationRepository.Recovery recover(
      SelectedOwnerIntakeSourceReadScope scope) {
    requireScope(scope);
    outsideSql();
    return Objects.requireNonNull(
        ownerTransaction.execute(ignored -> repository.recoverSourceRead(scope)));
  }

  private Optional<SelectedOwnerIntakeAuthorizationBinding> findFinalAuthorization(
      SelectedOwnerIntakeSourceReadScope scope) {
    requireScope(scope);
    outsideSql();
    return Objects.requireNonNull(
        ownerTransaction.execute(ignored -> repository.findFinalAuthorization(scope)));
  }

  private SelectedOwnerIntakeAuthorizationBinding readFinalAuthorization(
      SelectedOwnerIntakeAuthorizationBinding requested) {
    Objects.requireNonNull(requested, "finalized selected-owner authorization is required");
    requireScope(requested.content().scope());
    outsideSql();
    ownerTransaction.execute(
        ignored -> {
          repository.readFinalAuthorization(requested);
          return null;
        });
    return requested;
  }

  private SelectedOwnerIntakeSourceContent requireExactContent(
      SelectedOwnerIntakeSourceReadEvidence.Request request,
      SelectedOwnerIntakeSourceContent received) {
    if (received == null || !request.scope().equals(received.scope()))
      throw denied("Game Design returned substituted selected-owner source content");
    try {
      var checked =
          SelectedOwnerIntakeSourceContent.fromStored(
              received.canonicalBytes(), request.scope(), received.digest());
      for (String family :
          java.util.List.of(
              "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
        if (checked.snapshotBytes(family).length == 0)
          throw new IllegalArgumentException("Selected-owner source family is empty");
      }
      return checked;
    } catch (IllegalArgumentException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact complete selected-owner source content is required")
          .withCause(invalid)
          .asRuntimeException();
    }
  }

  private void requireScope(SelectedOwnerIntakeSourceReadScope scope) {
    if (scope == null
        || !namespace.equals(scope.targetNamespace())
        || (scope.owner() != Owner.ENTITY_MANAGEMENT
            && scope.owner() != Owner.AUTOMATION_SCRIPTING))
      throw Status.PERMISSION_DENIED.asRuntimeException();
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

  private static void requireOwner(Owner owner) {
    if (owner != Owner.ENTITY_MANAGEMENT && owner != Owner.AUTOMATION_SCRIPTING)
      throw Status.INVALID_ARGUMENT
          .withDescription("Entity or Automation selected-source owner required")
          .asRuntimeException();
  }

  private static CapturedEnvironmentBoundary requireEnvironment(
      CapturedEnvironmentBoundary environment) {
    return Objects.requireNonNull(environment, "current Account environment is required");
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Selected-owner Account phases require independent transactions")
          .asRuntimeException();
    }
  }

  private static RuntimeException denied(String description) {
    return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
  }
}
