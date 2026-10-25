package net.firedevops.firemud.common.account.sourceintake;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** File-backed mTLS client for Account's distinct World closure HELD read. */
public final class AccountSelectedOwnerIntakeWorldClosureAuthorizationGrpcClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc
            .AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceBlockingStub>
    implements SelectedOwnerIntakeWorldClosureAuthorizationReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public AccountSelectedOwnerIntakeWorldClosureAuthorizationGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Account World closure authorization read"),
        channelFactory,
        AccountSelectedOwnerIntakeWorldClosureAuthorizationGrpcClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account World closure authorization client is closed",
        "Account World closure authorization stub unavailable");
  }

  /** Returns only Account's exact echoed HELD result, outside ambient SQL and synchronization. */
  @Override
  public SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence readHeld(
      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request request) {
    requireNoAmbientSql("Account World closure authorization read must run outside SQL");
    Objects.requireNonNull(request, "World closure authorization-read request is required");
    if (!workloadNamespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException(
          "Account World closure authorization read must use the configured namespace");
    var response =
        requireInitializedStub(
                "Account World closure authorization client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readHeldSelectedOwnerWorldClosureAuthorization(
                SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toRequest(request));
    try {
      return SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromResponse(
          request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid selected-owner World closure HELD evidence", invalid);
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
  protected AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc
          .AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountSelectedOwnerIntakeWorldClosureAuthorizationReadServiceGrpc.newBlockingStub(
            channel)
        .withMaxInboundMessageSize(
            SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
