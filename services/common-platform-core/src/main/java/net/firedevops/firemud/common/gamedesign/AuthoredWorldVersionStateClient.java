package net.firedevops.firemud.common.gamedesign;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldVersionStateServiceGrpc;

/** Explicit mTLS client for exact source-qualified current Game Design version-state reads. */
public final class AuthoredWorldVersionStateClient
    extends AbstractReloadingBlockingGrpcClient<
        AuthoredWorldVersionStateServiceGrpc.AuthoredWorldVersionStateServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AuthoredWorldVersionStateClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        AuthoredWorldVersionStateClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning service explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Authored-world version-state client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException(
          "Authored-world version-state client did not initialize a gRPC stub");
    }
    initialized = true;
  }

  /** Reads current state for the exact already-intaken source and source-owned version. */
  public AuthoredWorldVersionStateEvidence read(AuthoredWorldVersionStateEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    requireNamespace(request.targetNamespace());
    AuthoredWorldVersionStateServiceGrpc.AuthoredWorldVersionStateServiceBlockingStub currentStub =
        requireStub();
    var response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getAuthoredWorldVersionState(AuthoredWorldVersionStateGrpcCodec.toRequest(request));
    try {
      return AuthoredWorldVersionStateGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design returned invalid authored-world version-state evidence", exception);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected AuthoredWorldVersionStateServiceGrpc.AuthoredWorldVersionStateServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedGameDesignPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service";
    return AuthoredWorldVersionStateServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedGameDesignPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedGameDesignPeerUri))
        .withCompression("gzip");
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      closed = true;
      initialized = false;
    }
    super.close();
  }

  private AuthoredWorldVersionStateServiceGrpc.AuthoredWorldVersionStateServiceBlockingStub
      requireStub() {
    AuthoredWorldVersionStateServiceGrpc.AuthoredWorldVersionStateServiceBlockingStub currentStub =
        stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Authored-world version-state client is not initialized and available");
    }
    return currentStub;
  }

  private void requireNamespace(String targetNamespace) {
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Version-state request must use the configured workload namespace");
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    return FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        "Version-state reads",
        "gRPC mTLS configuration is required",
        "Version-state reads require workload mTLS");
  }
}
