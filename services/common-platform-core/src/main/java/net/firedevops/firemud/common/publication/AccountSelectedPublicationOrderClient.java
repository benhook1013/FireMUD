package net.firedevops.firemud.common.publication;

import io.grpc.CompositeCallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountSelectedPublicationOrderServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** Strict explicit mTLS client; never registers or enables the Account producer. */
public final class AccountSelectedPublicationOrderClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedPublicationOrderServiceGrpc
            .AccountSelectedPublicationOrderServiceBlockingStub> {
  private final String namespace;

  public AccountSelectedPublicationOrderClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory factory,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Account selected-publication"),
        factory,
        AccountSelectedPublicationOrderClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account namespace required");
    this.namespace = namespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account selected-publication client is closed",
        "Account selected-publication stub unavailable");
  }

  public AccountPublicationAuthorizationBinding authorize(
      AccountSelectedPublicationOrderGrpcCodec.Request request) {
    requireNoAmbientSql("Account selected-publication transport requires no owner transaction");
    Objects.requireNonNull(request);
    if (!namespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Account selected-publication namespace mismatch");
    var current = requireInitializedStub("Account selected-publication client unavailable");
    try {
      var response =
          current
              .withCallCredentials(
                  new CompositeCallCredentials(
                      new GrpcServerPeerIdentityCallCredentials(
                          "spiffe://firemud/ns/" + namespace + "/sa/account-service"),
                      AccountSelectedPublicationOrderCredentials.forCall(
                          request.originalCreatorCredential())))
              .withDeadlineAfter(5, TimeUnit.SECONDS)
              .authorizeSelectedPublication(
                  AccountSelectedPublicationOrderGrpcCodec.toRequest(request));
      return AccountSelectedPublicationOrderGrpcCodec.fromResponse(request, response);
    } catch (StatusRuntimeException unavailable) {
      // Remote diagnostics are not trusted to exclude credential-bearing request data.
      throw Status.fromCode(unavailable.getStatus().getCode())
          .withDescription("Account selected-publication order denied or unavailable")
          .asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Account returned invalid selected-publication order evidence");
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
  protected AccountSelectedPublicationOrderServiceGrpc
          .AccountSelectedPublicationOrderServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/account-service";
    return AccountSelectedPublicationOrderServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip")
        .withMaxInboundMessageSize(
            AccountSelectedPublicationOrderGrpcCodec.MAX_RESPONSE_MESSAGE_BYTES);
  }
}
