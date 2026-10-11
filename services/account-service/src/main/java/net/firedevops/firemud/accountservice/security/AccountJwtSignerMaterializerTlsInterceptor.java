package net.firedevops.firemud.accountservice.security;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Service-local certificate identity gate for only the JWT signer materialization RPCs. */
@Component("accountJwtSignerMaterializerTlsInterceptor")
public final class AccountJwtSignerMaterializerTlsInterceptor implements ServerInterceptor {
  private static final Context.Key<Binding> AUTHENTICATED_BINDING =
      Context.key("account-jwt-signer-materializer-trust-binding");

  private final Supplier<Optional<Binding>> trustBindingProvider;

  @Autowired
  public AccountJwtSignerMaterializerTlsInterceptor(
      AccountJwtSignerMaterializerTrustBinding trustBindingProvider) {
    this(trustBindingProvider::current);
  }

  AccountJwtSignerMaterializerTlsInterceptor(Supplier<Optional<Binding>> trustBindingProvider) {
    this.trustBindingProvider = trustBindingProvider;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    Optional<Binding> current = trustBindingProvider.get();
    if (current.isEmpty()) {
      return deny(call, "JWT materializer trust binding is unavailable");
    }
    SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    if (sslSession == null) {
      return deny(call, "Authenticated JWT materializer workload is required");
    }
    Binding binding = current.orElseThrow();
    java.security.cert.Certificate[] peerCertificates;
    try {
      peerCertificates = sslSession.getPeerCertificates();
    } catch (SSLPeerUnverifiedException ex) {
      return deny(call, "Authenticated JWT materializer workload is required");
    }
    if (!AccountJwtSignerMaterializerTrustBinding.matchesPeer(peerCertificates, binding)) {
      return deny(call, "Authenticated JWT materializer workload is required");
    }
    Context authenticated = Context.current().withValue(AUTHENTICATED_BINDING, binding);
    return Contexts.interceptCall(authenticated, call, headers, next);
  }

  /** The service re-reads protected configuration and rejects a changed snapshot before storage. */
  public static Binding authenticatedBinding() {
    return AUTHENTICATED_BINDING.get();
  }

  private static <ReqT, RespT> ServerCall.Listener<ReqT> deny(
      ServerCall<ReqT, RespT> call, String description) {
    call.close(Status.PERMISSION_DENIED.withDescription(description), new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
