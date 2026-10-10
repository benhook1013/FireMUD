package net.firedevops.firemud.common.publication;

import io.grpc.CompositeCallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Strict explicit mTLS client; never registers or enables the Account producer. */
public final class AccountSelectedPublicationOrderClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountSelectedPublicationOrderServiceGrpc
            .AccountSelectedPublicationOrderServiceBlockingStub> {
  private final String namespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AccountSelectedPublicationOrderClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory factory,
      String namespace) {
    super(endpoints, requireMtls(tls), factory, AccountSelectedPublicationOrderClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account namespace required");
    this.namespace = namespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("Account selected-publication client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null)
      throw new IllegalStateException("Account selected-publication stub unavailable");
    initialized = true;
  }

  public AccountPublicationAuthorizationBinding authorize(
      AccountSelectedPublicationOrderGrpcCodec.Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException(
          "Account selected-publication transport requires no owner transaction");
    Objects.requireNonNull(request);
    if (!namespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Account selected-publication namespace mismatch");
    var current = stub();
    if (closed || !initialized || current == null)
      throw new IllegalStateException("Account selected-publication client unavailable");
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
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private static CommonGrpcClientProperties requireMtls(CommonGrpcClientProperties tls) {
    if (tls == null || tls.isPlaintext())
      throw new IllegalArgumentException("Account selected-publication requires workload mTLS");
    for (String configured :
        new String[] {tls.getCertChain(), tls.getPrivateKey(), tls.getCaCert()}) {
      if (configured == null || configured.isBlank() || configured.trim().startsWith("classpath:"))
        throw new IllegalArgumentException(
            "Account selected-publication requires readable file-backed TLS material");
      Path file;
      try {
        file = Path.of(configured.trim());
      } catch (RuntimeException malformed) {
        throw new IllegalArgumentException(
            "Account selected-publication requires file-backed TLS material");
      }
      if (!Files.isRegularFile(file) || !Files.isReadable(file))
        throw new IllegalArgumentException(
            "Account selected-publication requires readable file-backed TLS material");
    }
    return tls;
  }
}
