package net.firedevops.firemud.accountservice.client;

import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcomeReadServiceGrpc;

/** Caller-owned Account candidate for exact terminal original tenant-creation readback. */
public final class GameDesignOriginalTenantCreationOutcomeClient
    extends AbstractReloadingBlockingGrpcClient<
        OriginalTenantCreationOutcomeReadServiceGrpc
            .OriginalTenantCreationOutcomeReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private final GrpcServerPeerIdentityClientInterceptor serverPeerIdentityInterceptor;

  public GameDesignOriginalTenantCreationOutcomeClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireAccountMtls(tlsProps),
        channelFactory,
        GameDesignOriginalTenantCreationOutcomeClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Account workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
    this.serverPeerIdentityInterceptor =
        new GrpcServerPeerIdentityClientInterceptor(gameDesignServerPeerUri(workloadNamespace));
  }

  /** Initializes the normal Account workload TLS client when the owner explicitly enables it. */
  public void init() throws SSLException, IOException {
    initReloadingClient();
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
  protected OriginalTenantCreationOutcomeReadServiceGrpc
          .OriginalTenantCreationOutcomeReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    return OriginalTenantCreationOutcomeReadServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(channel, serverPeerIdentityInterceptor))
        .withCompression("gzip");
  }

  private static String gameDesignServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/game-design-service";
  }

  /**
   * Reads the exact terminal outcome for one original binding. A returned value represents a
   * successful transport and strict codec validation; any absence, pending result or transport
   * failure propagates without inferring an outcome.
   */
  public VerifiedTerminal readOriginal(Binding binding) {
    Objects.requireNonNull(binding, "binding");
    if (!workloadNamespace.equals(binding.targetNamespace())) {
      throw new IllegalArgumentException("Original tenant outcome namespace differs from Account");
    }

    GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest request =
        new GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest(UUID.randomUUID(), binding);
    var response =
        stub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveOriginalTenantCreationOutcome(
                GameTenantCreationOriginalOutcomeGrpcCodec.toReadRequest(request));
    final GameTenantCreationOriginalOutcomeEvidence evidence;
    try {
      evidence = GameTenantCreationOriginalOutcomeGrpcCodec.fromReadResponse(request, response);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Game Design original tenant outcome does not match the exact read", malformed);
    }
    return new VerifiedTerminal(evidence);
  }

  private static CommonGrpcClientProperties requireAccountMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Account gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Original tenant outcome reads require Account workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Original tenant outcome reads require Account certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Original tenant outcome reads require file-backed Account workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /** A terminal evidence value minted only after this client's exact read and codec validation. */
  public static final class VerifiedTerminal {
    private final GameTenantCreationOriginalOutcomeEvidence evidence;

    private VerifiedTerminal(GameTenantCreationOriginalOutcomeEvidence evidence) {
      this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    public GameTenantCreationOriginalOutcomeEvidence evidence() {
      return evidence;
    }

    public Binding binding() {
      return evidence.binding();
    }
  }
}
