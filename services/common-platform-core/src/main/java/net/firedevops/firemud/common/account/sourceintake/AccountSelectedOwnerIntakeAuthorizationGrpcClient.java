package net.firedevops.firemud.common.account.sourceintake;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeAuthorizationReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** File-backed mTLS client for Account's distinct selected-owner HELD read. */
public final class AccountSelectedOwnerIntakeAuthorizationGrpcClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedOwnerIntakeAuthorizationReadServiceGrpc
            .AccountSelectedOwnerIntakeAuthorizationReadServiceBlockingStub>
    implements SelectedOwnerIntakeAuthorizationReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public AccountSelectedOwnerIntakeAuthorizationGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Account selected-owner authorization read"),
        channelFactory,
        AccountSelectedOwnerIntakeAuthorizationGrpcClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account selected-owner authorization client is closed",
        "Account selected-owner authorization stub unavailable");
  }

  /** Returns only Account's exact echoed HELD result, outside ambient SQL and synchronization. */
  @Override
  public SelectedOwnerIntakeAuthorizationReadEvidence read(
      SelectedOwnerIntakeAuthorizationReadEvidence.Request request) {
    requireNoAmbientSql("Account selected-owner authorization read must run outside SQL");
    Objects.requireNonNull(request, "authorization-read request is required");
    if (!workloadNamespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException(
          "Account selected-owner authorization read must use the configured namespace");
    var response =
        requireInitializedStub(
                "Account selected-owner authorization client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readHeldSelectedOwnerIntakeAuthorization(
                SelectedOwnerIntakeAuthorizationReadGrpcCodec.toRequest(request));
    try {
      return SelectedOwnerIntakeAuthorizationReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid selected-owner HELD authorization evidence", invalid);
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
  protected AccountSelectedOwnerIntakeAuthorizationReadServiceGrpc
          .AccountSelectedOwnerIntakeAuthorizationReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountSelectedOwnerIntakeAuthorizationReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(SelectedOwnerIntakeAuthorizationReadGrpcCodec.MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
