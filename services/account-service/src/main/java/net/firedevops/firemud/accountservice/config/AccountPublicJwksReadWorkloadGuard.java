package net.firedevops.firemud.accountservice.config;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Exact method-local workload authorization, independent of finite rotation-readiness plans. */
public final class AccountPublicJwksReadWorkloadGuard {
  private final Supplier<Optional<Binding>> materializer;
  private final Supplier<AccountJwtJwksApiBinding.ParsedBinding> api;
  private final String namespace;

  public AccountPublicJwksReadWorkloadGuard(
      AccountJwtSignerMaterializerTrustBinding materializer,
      AccountJwtJwksApiBinding api,
      String namespace) {
    this(
        Objects.requireNonNull(materializer)::current,
        Objects.requireNonNull(api)::current,
        namespace);
  }

  AccountPublicJwksReadWorkloadGuard(
      Supplier<Optional<Binding>> materializer,
      Supplier<AccountJwtJwksApiBinding.ParsedBinding> api,
      String namespace) {
    this.materializer = Objects.requireNonNull(materializer);
    this.api = Objects.requireNonNull(api);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Protected Account workload namespace required");
    }
    this.namespace = namespace;
  }

  /** Called before and after the owner source read; no header or bearer establishes identity. */
  public ProtectedCaller requireCaller() {
    try {
      Binding trust = materializer.get().orElseThrow(ReadDeniedException::new);
      var currentApi = Objects.requireNonNull(api.get());
      GrpcPeerIdentity peer = GrpcPeerIdentity.current();
      if (peer == null
          || !("spiffe://firemud/ns/" + namespace + "/sa/game-session-service").equals(peer.uri())
          || !namespace.equals(trust.namespace())
          || !namespace.equals(currentApi.namespace())
          || !trust.environmentId().equals(currentApi.environmentId())
          || !trust.clusterId().equals(currentApi.clusterId())
          || !trust
              .expectedClusterIncarnationUid()
              .equals(currentApi.expectedClusterIncarnationUid())
          || !trust.expectedNamespaceUid().equals(currentApi.expectedNamespaceUid())
          || SessionContext.getAccountId() != null
          || !SessionContext.getGlobalRoles().isEmpty()
          || !SessionContext.getScopedRolesMap().isEmpty()
          || SessionContext.isInternalService()
          || SessionContext.getServiceName() != null
          || SessionContext.getServiceInstanceId() != null) {
        throw new ReadDeniedException();
      }
      return new ProtectedCaller(trust, currentApi);
    } catch (RuntimeException denied) {
      throw new ReadDeniedException();
    }
  }

  /** Invocation-local protected correspondence, never transported to a consumer. */
  public record ProtectedCaller(Binding materializer, AccountJwtJwksApiBinding.ParsedBinding api) {
    public void requireSource(SourceIdentity source) {
      if (source == null
          || !materializer.environmentId().equals(source.environmentId())
          || !materializer.clusterId().equals(source.clusterId())
          || !materializer.namespace().equals(source.namespace())
          || !materializer.expectedClusterIncarnationUid().equals(source.clusterIncarnationUid())
          || !materializer.expectedNamespaceUid().equals(source.namespaceUid())
          || !api.configRevision().equals(source.bindingRevision())
          || !api.apiServer().toString().equals(source.apiServerOrigin())
          || !api.servingCaSha256().equals(source.servingCaSha256())) {
        throw new InvalidJwksException();
      }
    }
  }

  public static final class ReadDeniedException extends SecurityException {
    public ReadDeniedException() {
      super("Authenticated same-namespace Game Session public JWKS reader required");
    }
  }
}
