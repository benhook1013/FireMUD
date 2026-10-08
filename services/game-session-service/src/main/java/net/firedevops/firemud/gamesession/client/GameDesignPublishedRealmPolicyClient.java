package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryPolicyServiceGrpc;

/** Unwired Game Session receiver for complete immutable Game Design realm-policy sets. */
public final class GameDesignPublishedRealmPolicyClient
    extends AbstractReloadingBlockingGrpcClient<
        PublishedRealmEntryPolicyServiceGrpc.PublishedRealmEntryPolicyServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String workloadNamespace;

  public GameDesignPublishedRealmPolicyClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireGameSessionMtls(tlsProps),
        channelFactory,
        GameDesignPublishedRealmPolicyClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes the owner client only when an explicit caller owns this handoff. */
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
  protected PublishedRealmEntryPolicyServiceGrpc.PublishedRealmEntryPolicyServiceBlockingStub
      buildStub(ManagedChannel channel) {
    return PublishedRealmEntryPolicyServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service"))
        .withCompression("gzip");
  }

  /** Reads and verifies the complete bounded owner set for an exact published tenant version. */
  public PublishedRealmEntryPolicySetEvidence listPublishedRealmEntryPolicies(
      UUID canonicalTenantId, UUID canonicalVersionId) {
    requireNonNil(canonicalTenantId, "canonical tenant ID");
    requireNonNil(canonicalVersionId, "canonical version ID");
    var currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException(
          "Game Design published realm policy client is not initialized");
    }

    PublishedRealmEntryPolicyReadGrpcCodec.ListRequest request =
        new PublishedRealmEntryPolicyReadGrpcCodec.ListRequest(
            workloadNamespace, UUID.randomUUID(), canonicalTenantId, canonicalVersionId);
    var response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .listPublishedRealmEntryPolicies(
                PublishedRealmEntryPolicyReadGrpcCodec.toRequest(request));
    try {
      return PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Design published realm policy response is invalid", invalid);
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
  }

  private static CommonGrpcClientProperties requireGameSessionMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Game Session gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Published realm policy reads require Game Session workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Published realm policy reads require Game Session certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Published realm policy reads require file-backed Game Session workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
