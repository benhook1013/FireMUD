package net.firedevops.firemud.accountservice.service.session;

import java.util.Objects;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.accountservice.repository.AccountInitialGameplayLoginDisposalRepository;
import net.firedevops.firemud.accountservice.repository.AccountInitialGameplayLoginDisposalRepository.DisposalUnavailableException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered lifecycle-only owner; no credential, recovery, activation or admission authority.
 */
public final class AccountInitialGameplayLoginDisposalOwner {
  private final AccountInitialGameplayLoginDisposalRepository repository;
  private final AccountGameplayDelegationTokenRegistry registry;
  private final TransactionTemplate transaction;

  public AccountInitialGameplayLoginDisposalOwner(
      AccountInitialGameplayLoginDisposalRepository repository,
      AccountGameplayDelegationTokenRegistry registry,
      PlatformTransactionManager transactionManager) {
    this.repository = Objects.requireNonNull(repository);
    this.registry = Objects.requireNonNull(registry);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
  }

  public InitialGameplayLoginDisposalResult retire(
      InitialGameplayLoginDisposalRequest request, String verifiedOriginalGameSessionWorkload) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new DisposalUnavailableException();
    var intent =
        transaction.execute(
            status -> repository.begin(request, verifiedOriginalGameSessionWorkload));
    if (intent == null) throw new DisposalUnavailableException();
    // NO_TOKEN means positively qualified nonissuance, not Redis absence or "never signed".
    // The independent authoritative primary readback closes the guard/shape boundary; retained
    // binding-history/restore qualification is unavailable, so these originals remain PENDING.
    if (intent.tokenHash() == null) {
      var result = transaction.execute(status -> repository.readUnqualifiedNoBinding(intent));
      if (result == null) throw new DisposalUnavailableException();
      return result;
    }
    // Even RETIRED lifecycle replay reasserts the exact denied marker and requires physical ACK
    // and readback, so a reset/expired/missing key is not silently accepted as disposal proof.
    var receipt = registry.disposeInitialLogin(intent);
    var result = transaction.execute(status -> repository.finish(intent, receipt));
    if (result == null) throw new DisposalUnavailableException();
    return result;
  }
}
