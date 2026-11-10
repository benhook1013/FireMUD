package net.firedevops.firemud.springcloudgateway.client;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountControlUiAuthenticationServiceGrpc;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Request;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Result;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** File-backed Gateway mTLS client for Account's narrow Control-UI authentication RPC. */
public final class AccountControlUiAuthenticationClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountControlUiAuthenticationServiceGrpc
            .AccountControlUiAuthenticationServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AccountControlUiAuthenticationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(
            tlsProperties, "Account Control-UI authentication requires file-backed workload mTLS"),
        nonRetryingChannelFactory(channelFactory),
        AccountControlUiAuthenticationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when the isolated receiving profile explicitly enables this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("Account authentication client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Account authentication stub is unavailable");
    }
    initialized = true;
  }

  /** Sends one exact authentication request without a retry or credential-bearing diagnostics. */
  public Result login(Request request) {
    Objects.requireNonNull(request, "Control-UI authentication request is required");
    if (!workloadNamespace.equals(request.callerNamespace())) {
      throw new IllegalArgumentException("Control-UI caller namespace does not match Gateway");
    }
    var wireRequest = AccountControlUiAuthenticationGrpcCodec.toRequest(request);
    try {
      var wireResponse =
          requireStub()
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
              .authLogin(wireRequest);
      return AccountControlUiAuthenticationGrpcCodec.requireResponse(request, wireResponse);
    } catch (StatusRuntimeException unavailable) {
      if (unavailable.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
        throw new AuthenticationConflictException();
      }
      if (unavailable.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
        throw new AuthenticationInvalidRequestException();
      }
      throw new AuthenticationUnavailableException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Account authentication response was invalid");
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected AccountControlUiAuthenticationServiceGrpc
          .AccountControlUiAuthenticationServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountControlUiAuthenticationServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(AccountControlUiAuthenticationGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withMaxOutboundMessageSize(AccountControlUiAuthenticationGrpcCodec.MAX_REQUEST_WIRE_BYTES)
        // CallCredentials runs before HTTP/2 releases request headers or the primary-secret body.
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri));
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      closed = true;
      initialized = false;
    }
    super.close();
  }

  private AccountControlUiAuthenticationServiceGrpc
          .AccountControlUiAuthenticationServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new AuthenticationUnavailableException();
    }
    return currentStub;
  }

  private static GrpcChannelFactory nonRetryingChannelFactory(GrpcChannelFactory delegate) {
    Objects.requireNonNull(delegate, "Account authentication requires a gRPC channel factory");
    return new GrpcChannelFactory() {
      @Override
      public ManagedChannel buildChannel(
          String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
          throws SSLException {
        return delegate.buildNonRetryingChannel(target, defaultPort, properties, keepAlive);
      }
    };
  }

  /** Safe for public error handling; it deliberately has no credential-bearing cause. */
  public static final class AuthenticationUnavailableException extends RuntimeException {
    private AuthenticationUnavailableException() {
      super("Account authentication is unavailable");
    }
  }

  /** Safe conflict signal for a changed input under a retained idempotency key. */
  public static final class AuthenticationConflictException extends RuntimeException {
    public AuthenticationConflictException() {
      super("Account authentication request conflicts");
    }
  }

  /** Safe invalid-request signal that carries no upstream gRPC details or cause. */
  public static final class AuthenticationInvalidRequestException extends RuntimeException {
    public AuthenticationInvalidRequestException() {
      super("Account authentication request is invalid");
    }
  }
}
