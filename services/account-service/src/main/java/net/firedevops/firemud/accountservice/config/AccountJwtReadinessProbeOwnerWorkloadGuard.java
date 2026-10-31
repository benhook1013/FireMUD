package net.firedevops.firemud.accountservice.config;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Exact method-local mTLS authorization for the read-only Game Session probe-owner lookup. */
public final class AccountJwtReadinessProbeOwnerWorkloadGuard {
  private static final String GAME_SESSION_SERVICE = "game-session-service";

  private final Supplier<Optional<Binding>> materializerBinding;
  private final Supplier<AccountJwtJwksApiBinding.ParsedBinding> apiBinding;
  private final String localWorkloadNamespace;

  public AccountJwtReadinessProbeOwnerWorkloadGuard(
      AccountJwtSignerMaterializerTrustBinding materializerBinding,
      AccountJwtJwksApiBinding apiBinding,
      String localWorkloadNamespace) {
    this(
        () -> Objects.requireNonNull(materializerBinding).current(),
        () -> Objects.requireNonNull(apiBinding).current(),
        localWorkloadNamespace);
  }

  AccountJwtReadinessProbeOwnerWorkloadGuard(
      Supplier<Optional<Binding>> materializerBinding,
      Supplier<AccountJwtJwksApiBinding.ParsedBinding> apiBinding,
      String localWorkloadNamespace) {
    this.materializerBinding = Objects.requireNonNull(materializerBinding);
    this.apiBinding = Objects.requireNonNull(apiBinding);
    this.localWorkloadNamespace = localWorkloadNamespace == null ? "" : localWorkloadNamespace;
  }

  /**
   * Reads both protected Account bindings and checks them against local config and peer TLS SAN.
   */
  public AuthenticatedCaller requireGameSessionOwnerReadCaller() {
    Binding account = materializerBinding.get().orElseThrow(OwnerReadDeniedException::new);
    AccountJwtJwksApiBinding.ParsedBinding api;
    try {
      api = apiBinding.get();
    } catch (RuntimeException unavailable) {
      throw new OwnerReadDeniedException();
    }
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (api == null
        || !sameProtectedAccountIdentity(account, api)
        || !GrpcPeerIdentity.isValidNamespace(localWorkloadNamespace)
        || !localWorkloadNamespace.equals(account.namespace())
        || peer == null
        || !GAME_SESSION_SERVICE.equals(peer.service())
        || !account.namespace().equals(peer.namespace())
        || !("spiffe://firemud/ns/" + account.namespace() + "/sa/" + GAME_SESSION_SERVICE)
            .equals(peer.uri())
        || hasSessionContext()) {
      throw new OwnerReadDeniedException();
    }
    return new AuthenticatedCaller(account);
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

  /** Opaque binding snapshot returned only after the gRPC peer check passes. */
  public static final class AuthenticatedCaller {
    private final Binding accountBinding;

    private AuthenticatedCaller(Binding accountBinding) {
      this.accountBinding = accountBinding;
    }

    public Binding accountBinding() {
      return accountBinding;
    }
  }

  public static final class OwnerReadDeniedException extends SecurityException {
    public OwnerReadDeniedException() {
      super("Authenticated same-namespace Game Session workload is required");
    }
  }
}
