package net.firedevops.firemud.gamesession.service;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountPublicJwksReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec.ReadResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Same-namespace mTLS current-public-state read, with no bearer or readiness-plan dependency. */
public final class AccountPublicJwksReadClient {
  private static final int ACCOUNT_PORT = 6565;
  private static final long DEADLINE_SECONDS = 3L;
  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tls;
  private final GrpcChannelFactory channels;
  private final String accountUri;

  public AccountPublicJwksReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    this.endpoints = Objects.requireNonNull(endpoints).copy();
    this.tls = Objects.requireNonNull(tls).copy();
    this.channels = Objects.requireNonNull(channels);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)
        || tls.isPlaintext()
        || blank(tls.getCertChain())
        || blank(tls.getPrivateKey())
        || blank(tls.getCaCert())
        || blank(this.endpoints.getAccountService())) {
      throw new IllegalArgumentException("Explicit Account endpoint and protected mTLS required");
    }
    this.accountUri = "spiffe://firemud/ns/" + namespace + "/sa/account-service";
  }

  public ReadResult readCurrent() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new SourceUnavailableException();
    }
    ManagedChannel channel = null;
    try {
      channel = channels.buildChannel(endpoints.getAccountService(), ACCOUNT_PORT, tls, false);
      var authenticated =
          ClientInterceptors.intercept(
              channel,
              new GrpcServerPeerIdentityClientInterceptor(accountUri),
              new EstablishedPeerFailureFence());
      // CallOptions' inbound limit is applied by gRPC's transport deframer, before protobuf parse.
      var response =
          AccountPublicJwksReadServiceGrpc.newBlockingStub(authenticated)
              .withMaxInboundMessageSize(AccountPublicJwksReadCodec.MAX_RESPONSE_BYTES)
              .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
              .getCurrentPublicJwks(AccountPublicJwksReadCodec.request());
      return AccountPublicJwksReadCodec.result(response);
    } catch (StatusRuntimeException failure) {
      Status.Code code = failure.getStatus().getCode();
      if (!hasTlsFailure(failure)
          && (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED)) {
        throw new SourceUnavailableException();
      }
      // Authentication, wire-schema and oversized-frame failures are never outage exceptions.
      throw new InvalidJwksException();
    } catch (javax.net.ssl.SSLException invalid) {
      throw new InvalidJwksException();
    } catch (SourceUnavailableException unavailable) {
      throw unavailable;
    } catch (RuntimeException invalid) {
      throw new InvalidJwksException();
    } finally {
      if (channel != null) {
        channel.shutdownNow();
      }
    }
  }

  /** The shared content fence permits unverified non-OK trailers; do not call those outages. */
  private final class EstablishedPeerFailureFence implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
      ClientCall<ReqT, RespT> call = next.newCall(method, options);
      return new SimpleForwardingClientCall<>(call) {
        @Override
        public void start(Listener<RespT> listener, Metadata headers) {
          super.start(
              new SimpleForwardingClientCallListener<>(listener) {
                @Override
                public void onClose(Status status, Metadata trailers) {
                  var session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
                  if (session != null
                      && GrpcPeerIdentity.fromSslSession(session)
                          .filter(peer -> accountUri.equals(peer.uri()))
                          .isEmpty()) {
                    super.onClose(Status.UNAUTHENTICATED, new Metadata());
                  } else {
                    super.onClose(status, trailers);
                  }
                }
              },
              headers);
        }
      };
    }
  }

  private static boolean hasTlsFailure(Throwable failure) {
    Throwable current = failure;
    // Bound traversal even if a transport implementation supplied a cyclic cause chain.
    for (int depth = 0; current != null && depth < 16; depth++) {
      if (current instanceof javax.net.ssl.SSLException) return true;
      current = current.getCause();
    }
    return false;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
