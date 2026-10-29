package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountStartSessionAdmissionProtectionServiceGrpc;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/**
 * Explicit Game Session-to-Account client; no runtime registration or owner authority is created.
 */
public final class GrpcAccountStartSessionAdmissionProtectionClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountStartSessionAdmissionProtectionServiceGrpc
            .AccountStartSessionAdmissionProtectionServiceBlockingStub>
    implements AccountStartSessionAdmissionProtectionAcquisitionClient {
  private static final long CALL_DEADLINE_SECONDS = 180L;

  private final String workloadNamespace;

  public GrpcAccountStartSessionAdmissionProtectionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "Account admission-protection acquisition"),
        channelFactory,
        GrpcAccountStartSessionAdmissionProtectionClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Admission-protection client is closed", "Admission-protection client is not initialized");
  }

  @Override
  public AccountStartSessionAdmissionProtectionEvidence acquire(
      AccountStartSessionAdmissionProtectionRequest request) {
    requireNoAmbientSql(
        "Account admission-protection acquisition requires no ambient SQL transaction");
    Objects.requireNonNull(request, "Complete Account admission-protection request required");
    var tupleScope = request.originalTuple().preAuthorizationTuple().action().scope();
    var hold = request.worldAdmissionHoldIdentity().request();
    if (!workloadNamespace.equals(tupleScope.targetNamespace())
        || !workloadNamespace.equals(hold.targetNamespace())
        || !tupleScope.tenantId().equals(hold.canonicalTenantId())) {
      throw new IllegalArgumentException(
          "Account admission-protection request is outside this Game Session namespace");
    }
    var wireRequest = AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(request);
    var wireResponse =
        requireInitializedStub("Admission-protection client is not initialized")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .acquireStartSessionAdmissionProtection(wireRequest);
    try {
      return AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
          request, wireResponse);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Account returned invalid admission-protection evidence", malformed);
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
  protected AccountStartSessionAdmissionProtectionServiceGrpc
          .AccountStartSessionAdmissionProtectionServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountStartSessionAdmissionProtectionServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip");
  }
}
