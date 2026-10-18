package net.firedevops.firemud.common.gamedesign;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignAuthoredInitialActorCoreReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client pinned to the same-namespace Game Design workload. */
public final class GrpcAuthoredInitialActorCoreReadClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignAuthoredInitialActorCoreReadServiceGrpc
            .GameDesignAuthoredInitialActorCoreReadServiceBlockingStub>
    implements AuthoredInitialActorCoreReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private final String workloadNamespace;

  public GrpcAuthoredInitialActorCoreReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcAuthoredInitialActorCoreReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce("Authored-core read client is closed", "Authored-core read client has no stub");
  }

  @Override
  public AuthoredInitialActorCoreReadEvidence.ReadResult read(
      AuthoredInitialActorCoreReadEvidence.ReadRequest request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Authored-core read requires no ambient transaction");
    }
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.associationRequest().targetNamespace())) {
      throw new IllegalArgumentException(
          "Authored-core read namespace differs from client identity");
    }
    var response =
        requireInitializedStub("Authored-core read client is not initialized")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readAuthoredInitialActorCore(AuthoredInitialActorCoreReadGrpcCodec.toRequest(request));
    try {
      return AuthoredInitialActorCoreReadGrpcCodec.fromResponse(
          request.associationRequest(), response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Design returned invalid authored-core evidence", invalid);
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
  protected GameDesignAuthoredInitialActorCoreReadServiceGrpc
          .GameDesignAuthoredInitialActorCoreReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expected = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service";
    return GameDesignAuthoredInitialActorCoreReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(AuthoredInitialActorCoreReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expected))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expected))
        .withCompression("gzip");
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties properties) {
    if (properties == null || properties.isPlaintext()) {
      throw new IllegalArgumentException("Authored-core reads require workload mTLS");
    }
    requireReadableFile(properties.getCertChain(), "certificate chain");
    requireReadableFile(properties.getPrivateKey(), "private key");
    requireReadableFile(properties.getCaCert(), "CA certificate");
    return properties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null
        || configuredPath.isBlank()
        || configuredPath.startsWith("classpath:")) {
      throw new IllegalArgumentException("Authored-core reads require file-backed TLS material");
    }
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid file-backed " + label, invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException("Authored-core " + label + " must be a readable file");
    }
  }
}
