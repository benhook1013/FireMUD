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
    try {
      return currentResponse(caller);
    } catch (RuntimeException failure) {
      if (AccountJwtJwksTrustedSource.isSourceRejected(failure)
          || failure instanceof AccountPublicJwksReadCodec.InvalidFrameException) {
        throw new AccountPublicJwksCache.InvalidJwksException();
      }
      throw failure;
    }
  }

  private GetCurrentAccountPublicJwksResponse currentResponse(
      AccountPublicJwksReadWorkloadGuard.ProtectedCaller caller) {
    var snapshot = source.currentPublicRead();
    if (snapshot == null) {
      throw new AccountPublicJwksCache.InvalidJwksException();
    }
    var identity = snapshot.sourceIdentity();
    caller.requireSource(identity);
    if (!identity.equals(source.sourceIdentity())) {
      throw new AccountPublicJwksCache.InvalidJwksException();
    }
    if (!caller.equals(guard.requireCaller())) {
      throw new AccountPublicJwksCache.InvalidJwksException();
    }
    try {
      return AccountPublicJwksReadCodec.response(snapshot);
    } catch (AccountPublicJwksReadCodec.InvalidFrameException producerRejected) {
      // The caller's request already passed validation; this is our publication's failure.
      throw new AccountPublicJwksCache.InvalidJwksException();
    }
  }
}
