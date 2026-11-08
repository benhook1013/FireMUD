package net.firedevops.firemud.worldmanagement.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountTenantOperationEntitlementReadServiceGrpc;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** Explicit, unregistered World consumer of Account's protected tenant-operation source read. */
public final class AccountTenantOperationEntitlementClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountTenantOperationEntitlementReadServiceGrpc
            .AccountTenantOperationEntitlementReadServiceBlockingStub> {
  private final String namespace;

  public AccountTenantOperationEntitlementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "Tenant-operation entitlement reads"),
        channelFactory,
        AccountTenantOperationEntitlementClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace required");
    }
    this.namespace = namespace;
  }

  public void init() throws IOException {
    initOnce(
        "Tenant-operation entitlement client is closed", "Tenant-operation client has no stub");
  }

  /** Samples actual Account evidence; the owner of a later action must enforce its currentness. */
  public ProtectedRead read(RuntimeTenantOperationEntitlementGrpcCodec.Request request) {
    requireNoAmbientSql("Account entitlement reads require no ambient World transaction");
    Objects.requireNonNull(request);
    if (!namespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Entitlement request must target the configured namespace");
    }
    var response =
        requireInitializedStub("Tenant-operation entitlement client is unavailable")
            .withDeadlineAfter(
                RuntimeTenantOperationEntitlementGrpcCodec.CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readTenantOperationEntitlement(
                RuntimeTenantOperationEntitlementGrpcCodec.toRequest(request));
    if (response == null) {
      throw new IllegalStateException("Account returned no tenant-operation evidence");
    }
    try {
      return new ProtectedRead(
          request, RuntimeTenantOperationEntitlementGrpcCodec.requireResponse(request, response));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid tenant-operation evidence", invalid);
    }
  }

  /**
   * Only a successful protected read can construct this provenance wrapper. Never admission
   * authority.
   */
  public static final class ProtectedRead {
    private final RuntimeTenantOperationEntitlementGrpcCodec.Request request;
    private final RuntimeTenantOperationEntitlementEvidence evidence;

    private ProtectedRead(
        RuntimeTenantOperationEntitlementGrpcCodec.Request request,
        RuntimeTenantOperationEntitlementEvidence evidence) {
      this.request = request;
      this.evidence = evidence;
    }

    public RuntimeTenantOperationEntitlementGrpcCodec.Request request() {
      return request;
    }

    public RuntimeTenantOperationEntitlementEvidence evidence() {
      return evidence;
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
  protected AccountTenantOperationEntitlementReadServiceGrpc
          .AccountTenantOperationEntitlementReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/account-service";
    return AccountTenantOperationEntitlementReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withMaxInboundMessageSize(
            RuntimeTenantOperationEntitlementGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withMaxOutboundMessageSize(
            RuntimeTenantOperationEntitlementGrpcCodec.MAX_REQUEST_WIRE_BYTES);
  }
}
