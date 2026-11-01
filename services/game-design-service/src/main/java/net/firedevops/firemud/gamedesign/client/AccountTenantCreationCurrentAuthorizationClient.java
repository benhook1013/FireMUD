package net.firedevops.firemud.gamedesign.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.TenantCreationCurrentAuthorizationReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.CurrentTenantCreationAuthorizationGrpcCodec;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;

/**
 * Private Account current-authority callback client; only exact peer-verified responses mint proof.
 */
public final class AccountTenantCreationCurrentAuthorizationClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantCreationCurrentAuthorizationReadServiceGrpc
            .TenantCreationCurrentAuthorizationReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private final GrpcServerPeerIdentityClientInterceptor peerIdentityInterceptor;

  public AccountTenantCreationCurrentAuthorizationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Account tenant-creation authorization read"),
        channelFactory,
        AccountTenantCreationCurrentAuthorizationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Design workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
    this.peerIdentityInterceptor =
        new GrpcServerPeerIdentityClientInterceptor(accountServerPeerUri(workloadNamespace));
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account tenant-creation authorization client is closed",
        "Account tenant-creation authorization stub unavailable");
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
  protected TenantCreationCurrentAuthorizationReadServiceGrpc
          .TenantCreationCurrentAuthorizationReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeer = accountServerPeerUri(workloadNamespace);
    return TenantCreationCurrentAuthorizationReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(CurrentTenantCreationAuthorizationGrpcCodec.MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeer))
        .withInterceptors(peerIdentityInterceptor)
        .withCompression("gzip");
  }

  /**
   * Resolves one exact Account binding outside ambient SQL and returns its finite current grant.
   */
  public VerifiedAuthorization resolveCurrent(Binding binding) {
    requireNoAmbientSql("Account current-authorization RPC must run outside SQL");
    Objects.requireNonNull(binding, "binding");
    if (!workloadNamespace.equals(binding.targetNamespace())) {
      throw new IllegalArgumentException(
          "Current tenant-creation authorization namespace differs from Game Design");
    }
    UUID readRequestId = UUID.randomUUID();
    var request = CurrentTenantCreationAuthorizationGrpcCodec.request(readRequestId, binding);
    var response =
        requireInitializedStub(
                "Account tenant-creation authorization client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveCurrentTenantCreationAuthorization(request);
    final CurrentTenantCreationAuthorizationGrpcCodec.Authorization authorization;
    try {
      authorization =
          CurrentTenantCreationAuthorizationGrpcCodec.verifyResponse(
              readRequestId, binding, response);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Account current tenant-creation authorization response did not match its exact read",
          malformed);
    }
    if (authorization.validBeforeEpochMillis() <= System.currentTimeMillis()) {
      throw new IllegalStateException("Account current tenant-creation authorization has expired");
    }
    return new VerifiedAuthorization(
        readRequestId, authorization.binding(), authorization.validBeforeEpochMillis());
  }

  private static String accountServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/account-service";
  }

  /** Only this client can construct the per-call value accepted by the private owner service. */
  public static final class VerifiedAuthorization {
    private final UUID readRequestId;
    private final Binding binding;
    private final long validBeforeEpochMillis;

    private VerifiedAuthorization(
        UUID readRequestId, Binding binding, long validBeforeEpochMillis) {
      this.readRequestId = Objects.requireNonNull(readRequestId, "readRequestId");
      this.binding = Objects.requireNonNull(binding, "binding");
      this.validBeforeEpochMillis = validBeforeEpochMillis;
    }

    public UUID readRequestId() {
      return readRequestId;
    }

    public Binding binding() {
      return binding;
    }

    public long validBeforeEpochMillis() {
      return validBeforeEpochMillis;
    }
  }
}
