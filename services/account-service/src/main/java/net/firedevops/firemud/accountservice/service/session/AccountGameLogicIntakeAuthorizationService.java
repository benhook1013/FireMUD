package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

  public AccountGameLogicIntakeAuthorizationService(
      AccountControlUiActorService actors,
      DraftAuthorizationFenceRepository fences,
      AccountGameLogicIntakeAuthorizationRepository repository,
      GameplayRuleSourceReadClient sources,
      String namespace) {
    this.actors = Objects.requireNonNull(actors);
    this.fences = Objects.requireNonNull(fences);
    this.repository = Objects.requireNonNull(repository);
    this.sources = Objects.requireNonNull(sources);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
  }

  public GameLogicIntakeAuthorizationBinding authorize(
      String compactJwt,
      UUID intakeRequestId,
      DraftCommitBinding selected,
      CapturedEnvironmentBoundary environment) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) throw Status.UNAUTHENTICATED.asRuntimeException();
    if (!("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.requireUuid(
        intakeRequestId);
    Objects.requireNonNull(selected);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw Status.FAILED_PRECONDITION
          .withDescription("Source read must precede Account SQL")
          .asRuntimeException();
    var request = GameplayRuleSourceReadEvidence.Request.create(namespace, selected);
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
          return repository.authorize(
              intakeRequestId,
              evidence.source(),
              current,
              () -> fences.requireGameLogicIntakeAdmission(current.source().sources(), selected));
        });
  }
}
