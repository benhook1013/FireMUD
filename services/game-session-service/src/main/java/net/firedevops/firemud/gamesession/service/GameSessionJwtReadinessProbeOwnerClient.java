package net.firedevops.firemud.gamesession.service;

import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountJwtReadinessProbeOwnerServiceGrpc;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** Direct mTLS-only client for the exact Account readiness owner read; attaches no bearer token. */
public final class GameSessionJwtReadinessProbeOwnerClient
    implements GameSessionJwtReadinessProbeOwnerReadPort {
  private static final int DEFAULT_ACCOUNT_PORT = 6565;
  private static final long DEADLINE_SECONDS = 5L;
  private static final long SHUTDOWN_SECONDS = 2L;

  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProperties;
  private final GrpcChannelFactory channelFactory;
  private final String workloadNamespace;

  public GameSessionJwtReadinessProbeOwnerClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    this.endpoints = Objects.requireNonNull(endpoints).copy();
    this.tlsProperties = Objects.requireNonNull(tlsProperties).copy();
    this.channelFactory = Objects.requireNonNull(channelFactory);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || this.tlsProperties.isPlaintext()
        || blank(this.tlsProperties.getCertChain())
        || blank(this.tlsProperties.getPrivateKey())
        || blank(this.tlsProperties.getCaCert())) {
      throw new IllegalArgumentException("Protected Account mTLS client configuration is required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public GetCurrentReadinessProbeOwnerResponse readCurrent(
      GetCurrentReadinessProbeOwnerRequest request) {
    Objects.requireNonNull(request, "Current Account owner read request is required");
    ManagedChannel channel = null;
    try {
      String target = endpoints.getAccountService();
      if (blank(target)) {
        target = "account-service:" + DEFAULT_ACCOUNT_PORT;
      }
      channel = channelFactory.buildChannel(target, DEFAULT_ACCOUNT_PORT, tlsProperties, false);
      String expectedAccountUri =
          "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
      var accountChannel =
          ClientInterceptors.intercept(
              channel, new GrpcServerPeerIdentityClientInterceptor(expectedAccountUri));
      return AccountJwtReadinessProbeOwnerServiceGrpc.newBlockingStub(accountChannel)
          .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
          .getCurrentReadinessProbeOwner(request);
    } catch (RuntimeException | javax.net.ssl.SSLException unavailable) {
      throw new OwnerReadUnavailableException();
    } finally {
      if (channel != null) {
        channel.shutdownNow();
        try {
          if (!channel.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
            throw new OwnerReadUnavailableException();
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new OwnerReadUnavailableException();
        }
      }
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public static final class OwnerReadUnavailableException extends RuntimeException {
    public OwnerReadUnavailableException() {
      super("Current Account readiness owner evidence is unavailable");
    }
  }
}
