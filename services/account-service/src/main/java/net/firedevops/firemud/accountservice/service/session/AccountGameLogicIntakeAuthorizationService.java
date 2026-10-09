package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered distinct intake producer. Genuine current creator and GD owner reads are mandatory.
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Internal Account owner transaction collaborators.")
public final class AccountGameLogicIntakeAuthorizationService {
  private final AccountControlUiActorService actors;
  private final DraftAuthorizationFenceRepository fences;
  private final AccountGameLogicIntakeAuthorizationRepository repository;
  private final GameplayRuleSourceReadClient sources;
  private final String namespace;
  private final TransactionTemplate transaction;

  public AccountGameLogicIntakeAuthorizationService(
      AccountControlUiActorService actors,
      DraftAuthorizationFenceRepository fences,
      AccountGameLogicIntakeAuthorizationRepository repository,
      GameplayRuleSourceReadClient sources,
      PlatformTransactionManager transactions,
      String namespace) {
    this.actors = Objects.requireNonNull(actors);
    this.fences = Objects.requireNonNull(fences);
    this.repository = Objects.requireNonNull(repository);
    this.sources = Objects.requireNonNull(sources);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public GameLogicIntakeAuthorizationBinding authorize(
      String compactJwt,
      UUID intakeRequestId,
      DraftCommitBinding selected,
      CapturedEnvironmentBoundary environment) {
    requirePeer();
    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.requireUuid(
        intakeRequestId);
    Objects.requireNonNull(selected);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION
          .withDescription("Source read must precede Account SQL")
          .asRuntimeException();
    var retained = findScope(compactJwt, intakeRequestId, selected);
    if (retained.isPresent()) {
      var original = recover(retained.orElseThrow());
      if (original.authorization().isPresent()) return original.authorization().orElseThrow();
      if (original.state() == AccountGameLogicIntakeSourceReadRecovery.State.ABORTED)
        throw Status.FAILED_PRECONDITION.asRuntimeException();
    }
    var scope = reserveSourceRead(compactJwt, intakeRequestId, selected, environment);
    var recovered = recover(scope);
    if (recovered.authorization().isPresent()) return recovered.authorization().orElseThrow();
    if (recovered.state() != AccountGameLogicIntakeSourceReadRecovery.State.RESERVED)
      throw Status.FAILED_PRECONDITION.asRuntimeException();
    var request = GameplayRuleSourceReadEvidence.Request.forAccountSourceScope(namespace, scope);
    var evidence = sources.read(request);
    if (evidence == null
        || !request.equals(evidence.request())
        || !selected.equals(evidence.source().binding()))
      throw new IllegalStateException("Exact authenticated complete GD source required");
    return actors.withCurrent(
        compactJwt,
        selected.target().canonicalTenantId(),
        environment,
        current -> {
          fences.lockProducerSourcesNowait(current.source().sources());
          return repository.finalizeSourceRead(
              scope,
              evidence.source(),
              current,
              () -> fences.requireGameLogicIntakeAdmission(current.source().sources(), selected));
        });
  }

  public GameLogicIntakeSourceReadScope reserveSourceRead(
      String compactJwt,
      UUID intakeRequestId,
      DraftCommitBinding selected,
      CapturedEnvironmentBoundary environment) {
    requirePeer();
    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.requireUuid(
        intakeRequestId);
    Objects.requireNonNull(selected);
    outsideSql();
    return actors.withCurrent(
        compactJwt,
        selected.target().canonicalTenantId(),
        environment,
        current -> {
          fences.lockProducerSourcesNowait(current.source().sources());
          return repository.reserveSourceRead(
              intakeRequestId,
              selected,
              current,
              namespace,
              () -> fences.requireGameLogicIntakeAdmission(current.source().sources(), selected));
        });
  }

  /** Exact original evidence, including after creator expiry; never resumes a source read. */
  public AccountGameLogicIntakeSourceReadRecovery recover(GameLogicIntakeSourceReadScope scope) {
    requirePeer();
    requireScope(scope);
    outsideSql();
    return transaction.execute(ignored -> repository.recoverSourceRead(scope));
  }

  /**
   * Recovers a lost first response using the exact original request and retained credential hash.
   */
  public AccountGameLogicIntakeSourceReadRecovery recover(
      String originalCompactJwt, UUID intakeRequestId, DraftCommitBinding selected) {
    return recover(
        findScope(originalCompactJwt, intakeRequestId, selected)
            .orElseThrow(
                () -> new IllegalArgumentException("Original source-read reservation absent")));
  }

  /** A definitive preliminary abort excludes any late finalization of this same operation. */
  public AccountGameLogicIntakeSourceReadRecovery abortSourceRead(
      GameLogicIntakeSourceReadScope scope) {
    requirePeer();
    requireScope(scope);
    outsideSql();
    return transaction.execute(ignored -> repository.abortSourceRead(scope));
  }

  public AccountGameLogicIntakeSourceReadRecovery abortSourceRead(
      String originalCompactJwt, UUID intakeRequestId, DraftCommitBinding selected) {
    return abortSourceRead(
        findScope(originalCompactJwt, intakeRequestId, selected)
            .orElseThrow(
                () -> new IllegalArgumentException("Original source-read reservation absent")));
  }

  private java.util.Optional<GameLogicIntakeSourceReadScope> findScope(
      String originalCompactJwt, UUID intakeRequestId, DraftCommitBinding selected) {
    requirePeer();
    outsideSql();
    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.requireUuid(
        intakeRequestId);
    Objects.requireNonNull(selected);
    if (originalCompactJwt == null
        || originalCompactJwt.isEmpty()
        || originalCompactJwt.length() > 16384
        || !java.nio.charset.StandardCharsets.US_ASCII.newEncoder().canEncode(originalCompactJwt))
      throw Status.UNAUTHENTICATED.asRuntimeException();
    var hash =
        AccountControlUiIssuanceRepository.hash(
            originalCompactJwt.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> repository.findSourceReadScope(intakeRequestId, selected, namespace, hash)));
  }

  private void requireScope(GameLogicIntakeSourceReadScope scope) {
    if (scope == null || !namespace.equals(scope.targetNamespace()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }

  private void requirePeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (!("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent Account intake phase required")
          .asRuntimeException();
  }
}
