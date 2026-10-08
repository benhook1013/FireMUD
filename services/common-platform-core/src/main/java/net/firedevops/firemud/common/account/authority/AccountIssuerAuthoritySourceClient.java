package net.firedevops.firemud.common.account.authority;

import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceServiceGrpc;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered, fail-closed mTLS client for Account's issuer-authority source read. */
public final class AccountIssuerAuthoritySourceClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountIssuerAuthoritySourceServiceGrpc.AccountIssuerAuthoritySourceServiceBlockingStub> {
  private static final Duration RPC_DEADLINE = Duration.ofSeconds(5);
  private static final int DEFAULT_PORT = 6565;

  private final String configuredNamespace;
  private final String clientWorkloadUri;
  private final String accountWorkloadUri;
  private final CommonGrpcClientProperties tlsPropertiesForValidation;
  private volatile boolean initialized;
  private volatile boolean clientClosed;

  public AccountIssuerAuthoritySourceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String configuredNamespace) {
    super(
        Objects.requireNonNull(endpoints, "endpoints is required"),
        requireFileBackedMutualTls(tlsProperties),
        Objects.requireNonNull(channelFactory, "channelFactory is required"),
        AccountIssuerAuthoritySourceClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(configuredNamespace)) {
      throw new IllegalArgumentException("configuredNamespace must be one canonical DNS label");
    }
    this.configuredNamespace = configuredNamespace;
    this.clientWorkloadUri =
        "spiffe://firemud/ns/" + configuredNamespace + "/sa/game-session-service";
    this.accountWorkloadUri = "spiffe://firemud/ns/" + configuredNamespace + "/sa/account-service";
    this.tlsPropertiesForValidation = tlsProperties.copy();
  }

  /**
   * Initializes this explicitly constructed client; it is not component-scanned or auto-configured.
   */
  @PostConstruct
  public synchronized void init() throws SSLException, IOException {
    if (clientClosed) {
      throw new IllegalStateException("issuer-source client is closed");
    }
    if (initialized) {
      return;
    }
    try {
      validateFileBackedMutualTls();
      initReloadingClient();
      initialized = true;
    } catch (IOException | RuntimeException | Error failure) {
      clientClosed = true;
      throw failure;
    }
  }

  /**
   * Reads and independently validates the exact Account issuer snapshot for the supplied request.
   */
  public AccountIssuerSourceSnapshotEvidence read(
      AccountIssuerSourceSnapshotGrpcCodec.ReadRequest request) {
    requireReadableLifecycle();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "issuer-source read cannot run inside an ambient transaction or synchronization");
    }
    Objects.requireNonNull(request, "request is required");

    // A hot-reloaded certificate must retain the exact client workload identity before any RPC is
    // sent.
    validateFileBackedMutualTls();
    var wireRequest =
        AccountIssuerSourceSnapshotGrpcCodec.toRequest(
            request, configuredNamespace, clientWorkloadUri);
    AccountIssuerAuthoritySourceSnapshot wireResponse =
        stub()
            .withDeadlineAfter(RPC_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
            .readCurrentIssuerAuthoritySource(wireRequest);
    return decodeResponse(wireResponse, request, clientWorkloadUri);
  }

  @Override
  public synchronized void close() throws IOException {
    clientClosed = true;
    initialized = false;
    super.close();
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
  protected int defaultPort() {
    return DEFAULT_PORT;
  }

  @Override
  protected AccountIssuerAuthoritySourceServiceGrpc.AccountIssuerAuthoritySourceServiceBlockingStub
      buildStub(ManagedChannel channel) {
    return AccountIssuerAuthoritySourceServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(accountWorkloadUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(accountWorkloadUri));
  }

  static AccountIssuerSourceSnapshotEvidence decodeResponse(
      AccountIssuerAuthoritySourceSnapshot wireResponse,
      AccountIssuerSourceSnapshotGrpcCodec.ReadRequest request,
      String authenticatedCallerWorkload) {
    return AccountIssuerSourceSnapshotGrpcCodec.fromResponse(
        wireResponse, request, authenticatedCallerWorkload);
  }

  private void requireReadableLifecycle() {
    if (clientClosed) {
      throw new IllegalStateException("issuer-source client is closed");
    }
    if (!initialized || stub() == null) {
      throw new IllegalStateException("issuer-source client is not initialized");
    }
  }

  private void validateFileBackedMutualTls() {
    requireFileBackedMutualTls(tlsPropertiesForValidation);
    validateClientCertificateIdentity();
  }

  private static CommonGrpcClientProperties requireFileBackedMutualTls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "issuer-source client requires TLS with file-backed client mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certChain");
    requireReadableFile(tlsProperties.getPrivateKey(), "privateKey");
    requireReadableFile(tlsProperties.getCaCert(), "caCert");
    return tlsProperties;
  }

  private void validateClientCertificateIdentity() {
    CommonGrpcClientProperties tlsProperties = currentTlsProperties();
    String certificatePath = tlsProperties.getCertChain();
    if (certificatePath == null || certificatePath.isBlank()) {
      throw new IllegalStateException("issuer-source client certificate chain is not configured");
    }
    Path path = Path.of(certificatePath);
    try (InputStream input = Files.newInputStream(path)) {
      Collection<? extends java.security.cert.Certificate> certificates =
          CertificateFactory.getInstance("X.509").generateCertificates(input);
      if (certificates.isEmpty()
          || !(certificates.iterator().next() instanceof X509Certificate leaf)) {
        throw new IllegalStateException("issuer-source client certificate chain has no X.509 leaf");
      }
      GrpcPeerIdentity identity =
          GrpcPeerIdentity.fromCertificate(leaf)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "issuer-source client leaf must contain one canonical workload URI SAN"));
      if (!clientWorkloadUri.equals(identity.uri())) {
        throw new IllegalStateException(
            "issuer-source client leaf SAN must match the configured Game Session workload");
      }
    } catch (IOException | java.security.cert.CertificateException failure) {
      throw new IllegalStateException(
          "issuer-source client certificate chain cannot be read", failure);
    }
  }

  private static void requireReadableFile(String configuredPath, String name) {
    if (configuredPath == null
        || configuredPath.isBlank()
        || configuredPath.trim().startsWith("classpath:")) {
      throw new IllegalArgumentException("issuer-source client " + name + " file is required");
    }
    Path path;
    try {
      path = Path.of(configuredPath);
    } catch (RuntimeException invalidPath) {
      throw new IllegalArgumentException(
          "issuer-source client " + name + " must be a file-backed path", invalidPath);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "issuer-source client " + name + " must be a readable regular file");
    }
  }

  private CommonGrpcClientProperties currentTlsProperties() {
    return tlsPropertiesForValidation;
  }
}
