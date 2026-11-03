package net.firedevops.firemud.entitymanagement.bootstrap;

import java.util.Objects;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterActor;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadRequest;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Manually composed and unregistered Account discovery; no actor or receipt writes. */
public final class AccountBootstrapRosterReadService {
  private final GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter owner;
  private final CharacterRepository characters;
  private final TransactionTemplate reads;

  public AccountBootstrapRosterReadService(
      GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter owner,
      CharacterRepository characters,
      PlatformTransactionManager transactionManager) {
    this.owner = Objects.requireNonNull(owner, "owner");
    this.characters = Objects.requireNonNull(characters, "characters");
    reads =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    reads.setReadOnly(true);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    reads.setTimeout(5);
  }

  public AccountBootstrapRosterReadResponse read(AccountBootstrapRosterReadRequest request) {
    requireNoAmbientTransaction();
    CanonicalGameplayRosterTarget target =
        AccountBootstrapRosterReadContract.requireRequest(request);
    if (target.playableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || target.entryPolicy() != CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY) {
      throw new UnsupportedOperationException("Unsupported bootstrap roster target");
    }
    requireCurrent(request, target);
    var actors =
        Objects.requireNonNull(
            reads.execute(
                ignored ->
                    characters.readAccountBootstrapRoster(
                        AccountBootstrapRosterReadContract.uuid(request.getCanonicalAccountUuid()),
                        target)));
    // Source RPCs run outside SQL. This detects source movement over the local projection;
    // it does not claim a distributed hold or protect a later admission/selection.
    requireCurrent(request, target);
    var response = AccountBootstrapRosterReadResponse.newBuilder().setRequest(request);
    for (var actor : actors) {
      response.addActors(
          AccountBootstrapRosterActor.newBuilder()
              .setCharacterUuid(actor.characterUuid().toString())
              .setDisplayName(actor.displayName())
              .setLevel(actor.level()));
    }
    return AccountBootstrapRosterReadContract.requireResponse(request, response.build());
  }

  private void requireCurrent(
      AccountBootstrapRosterReadRequest request, CanonicalGameplayRosterTarget target) {
    requireNoAmbientTransaction();
    var current =
        owner.resolveCurrentTarget(
            AccountBootstrapRosterReadContract.uuid(request.getRequestUuid()),
            AccountBootstrapRosterReadContract.uuid(request.getCanonicalAccountUuid()),
            target);
    if (current == null
        || !current.requestUuid().toString().equals(request.getRequestUuid())
        || !current.canonicalAccountUuid().toString().equals(request.getCanonicalAccountUuid())
        || !target.equals(current.target())) {
      throw new TargetChangedException();
    }
  }

  public static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new TargetChangedException();
    }
  }

  public static final class TargetChangedException extends IllegalStateException {
    public TargetChangedException() {
      super("Bootstrap roster target precondition failed");
    }
  }
}
