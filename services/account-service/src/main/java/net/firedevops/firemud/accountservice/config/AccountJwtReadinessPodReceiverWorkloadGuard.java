package net.firedevops.firemud.accountservice.config;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Exact method-local mTLS authorization for Account's own-Pod readiness receiver. */
public final class AccountJwtReadinessPodReceiverWorkloadGuard {
  private static final String ACCOUNT_SERVICE = "account-service";

  private final Supplier<Optional<Binding>> materializerBinding;
  private final Supplier<AccountJwtJwksApiBinding.ParsedBinding> apiBinding;
  private final String localWorkloadNamespace;

  public AccountJwtReadinessPodReceiverWorkloadGuard(
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtJwksApiBinding apiBinding,
      String localWorkloadNamespace) {
    this(
        () -> Objects.requireNonNull(materializerTrustBinding).current(),
        () -> Objects.requireNonNull(apiBinding).current(),
        localWorkloadNamespace);
  }

  AccountJwtReadinessPodReceiverWorkloadGuard(
      Supplier<Optional<Binding>> materializerBinding,
      Supplier<AccountJwtJwksApiBinding.ParsedBinding> apiBinding,
      String localWorkloadNamespace) {
    this.materializerBinding = Objects.requireNonNull(materializerBinding);
    this.apiBinding = Objects.requireNonNull(apiBinding);
    this.localWorkloadNamespace = localWorkloadNamespace == null ? "" : localWorkloadNamespace;
  }

  /** Re-reads both protected Account bindings and requires the exact local Account SPIFFE peer. */
  public AuthenticatedCaller requireAccountReceiverCaller() {
    Binding account = materializerBinding.get().orElseThrow(ReceiverCallerDeniedException::new);
    AccountJwtJwksApiBinding.ParsedBinding api;
    try {
      api = apiBinding.get();
    } catch (RuntimeException unavailable) {
      throw new ReceiverCallerDeniedException();
    }
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (api == null
        || !sameProtectedAccountIdentity(account, api)
        || !GrpcPeerIdentity.isValidNamespace(localWorkloadNamespace)
        || !localWorkloadNamespace.equals(account.namespace())
        || peer == null
        || !ACCOUNT_SERVICE.equals(peer.service())
        || !account.namespace().equals(peer.namespace())
        || !("spiffe://firemud/ns/" + account.namespace() + "/sa/" + ACCOUNT_SERVICE)
            .equals(peer.uri())
        || hasSessionContext()) {
      throw new ReceiverCallerDeniedException();
    }
    return new AuthenticatedCaller(account, api);
  }

  /** Rejects protected trust changes across token verification and owner reread. */
  public void requireUnchanged(AuthenticatedCaller before) {
    Objects.requireNonNull(before, "Initial authenticated caller snapshot is required");
    AuthenticatedCaller after = requireAccountReceiverCaller();
    if (!before.accountBinding().equals(after.accountBinding())
        || !before.apiBinding().equals(after.apiBinding())) {
      throw new ReceiverCallerDeniedException();
    }
  }

  private static boolean sameProtectedAccountIdentity(
      Binding account, AccountJwtJwksApiBinding.ParsedBinding api) {
    return account.environmentId().equals(api.environmentId())
        && account.clusterId().equals(api.clusterId())
        && account.namespace().equals(api.namespace())
        && account.expectedClusterIncarnationUid().equals(api.expectedClusterIncarnationUid())
        && account.expectedNamespaceUid().equals(api.expectedNamespaceUid());
  }

  private static boolean hasSessionContext() {
    return SessionContext.getAccountId() != null
        || !SessionContext.getGlobalRoles().isEmpty()
        || !SessionContext.getScopedRolesMap().isEmpty()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null;
  }

  /** Immutable protected trust snapshot captured only after the authenticated peer check. */
  public record AuthenticatedCaller(
      Binding accountBinding, AccountJwtJwksApiBinding.ParsedBinding apiBinding) {
    public AuthenticatedCaller {
      Objects.requireNonNull(accountBinding);
      Objects.requireNonNull(apiBinding);
    }
  }

  public static final class ReceiverCallerDeniedException extends SecurityException {
    public ReceiverCallerDeniedException() {
      super("Authenticated same-namespace Account workload is required");
    }
  }
}
