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
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.Binding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.PeerIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Independently pins the readiness-harness TLS caller for the readiness RPC alone. */
@Component("accountJwtReadinessTlsInterceptor")
public final class AccountJwtReadinessTlsInterceptor implements ServerInterceptor {
  private static final Context.Key<AuthenticatedCaller> AUTHENTICATED_CALLER =
      Context.key("account-jwt-readiness-authenticated-caller");

  private final Supplier<Optional<Binding>> trustBindingProvider;

  @Autowired
  public AccountJwtReadinessTlsInterceptor(AccountJwtReadinessTrustBinding trustBindingProvider) {
    this(trustBindingProvider::current);
  }

  AccountJwtReadinessTlsInterceptor(Supplier<Optional<Binding>> trustBindingProvider) {
    this.trustBindingProvider = trustBindingProvider;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    Optional<Binding> current = trustBindingProvider.get();
    if (current.isEmpty()) {
      return deny(call);
    }
    Binding binding = current.orElseThrow();
    if (!binding.isCurrentAt(java.time.Instant.now().getEpochSecond())) {
      return deny(call);
    }
    SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    if (sslSession == null) {
      return deny(call);
    }

    java.security.cert.Certificate[] peerCertificates;
    try {
      peerCertificates = sslSession.getPeerCertificates();
    } catch (SSLPeerUnverifiedException ex) {
      return deny(call);
    }
    PeerIdentity peer;
    try {
      peer = AccountJwtReadinessTrustBinding.identifyPeer(peerCertificates);
    } catch (RuntimeException rejectedIdentity) {
      return deny(call);
    }
    if (!binding.matches(peer)) {
      return deny(call);
    }

    Context authenticated =
        Context.current().withValue(AUTHENTICATED_CALLER, new AuthenticatedCaller(binding, peer));
    return Contexts.interceptCall(authenticated, call, headers, next);
  }

  /** Returns only identity established by this interceptor; callers cannot construct the value. */
  public static AuthenticatedCaller authenticatedCaller() {
    return AUTHENTICATED_CALLER.get();
  }

  private static <ReqT, RespT> ServerCall.Listener<ReqT> deny(ServerCall<ReqT, RespT> call) {
    call.close(
        Status.PERMISSION_DENIED.withDescription(
            "Authenticated Account readiness harness is required"),
        new Metadata());
    return new ServerCall.Listener<>() {};
  }

  /** Non-forgeable in-process handle for the TLS identity captured on this RPC. */
  public static final class AuthenticatedCaller {
    private final Binding binding;
    private final PeerIdentity peer;

    private AuthenticatedCaller(Binding binding, PeerIdentity peer) {
      this.binding = binding;
      this.peer = peer;
    }

    public Binding binding() {
      return binding;
    }

    public PeerIdentity peer() {
      return peer;
    }
  }
}
