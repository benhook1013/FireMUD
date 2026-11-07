package net.firedevops.firemud.accountservice.service.session;

import java.util.Objects;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksRequest;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksResponse;
import net.firedevops.firemud.accountservice.config.AccountPublicJwksReadWorkloadGuard;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public-only read through Account's actual current receipt-backed publication authority. */
public final class AccountPublicJwksReadService {
  private final AccountJwtJwksTrustedSource source;
  private final AccountPublicJwksReadWorkloadGuard guard;

  public AccountPublicJwksReadService(
      AccountJwtJwksTrustedSource source, AccountPublicJwksReadWorkloadGuard guard) {
    this.source = Objects.requireNonNull(source);
    this.guard = Objects.requireNonNull(guard);
  }

  public GetCurrentAccountPublicJwksResponse read(GetCurrentAccountPublicJwksRequest request) {
    var caller = guard.requireCaller();
    AccountPublicJwksReadCodec.requireRequest(request);
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new AccountPublicJwksCache.SourceUnavailableException();
    }
    var identity = source.sourceIdentity();
    caller.requireSource(identity);
    var snapshot = source.load();
    if (snapshot == null
        || !identity.equals(snapshot.sourceIdentity())
        || !identity.equals(source.sourceIdentity())) {
      throw new AccountPublicJwksCache.InvalidJwksException();
    }
    if (!caller.equals(guard.requireCaller())) {
      throw new AccountPublicJwksCache.InvalidJwksException();
    }
    return AccountPublicJwksReadCodec.response(snapshot);
  }
}
