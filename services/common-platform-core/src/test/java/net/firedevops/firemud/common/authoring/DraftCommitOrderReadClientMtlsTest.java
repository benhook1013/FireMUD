package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountDraftCommitOrderReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderRequest;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical loopback mTLS proof for the Account COMMIT_ORDER client transport only. */
class DraftCommitOrderReadClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String OTHER_NAMESPACE_ACCOUNT_URI =
      "spiffe://firemud/ns/other-test/sa/account-service";
  private static final String STORE_PASSWORD = "test-only-store-password";

  @TempDir static Path tempDirectory;

  private static TestPki pki;

  @BeforeAll
  static void createTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(tempDirectory);
  }

  @Test
  void exactAccountPeerReturnsTheExactHeldResponseOverPhysicalMtls() throws Exception {
    DraftCommitOrderReadEvidence.Request request = DraftCommitOrderReadGrpcCodecTest.request();
    try (TransportServer server = startServer(pki.accountServer(), request);
        DraftCommitOrderReadClient client = newClient(server.port())) {
      client.init();

      DraftCommitOrderReadEvidence actual = client.read(request);

      assertThat(actual.request()).isEqualTo(request);
      assertThat(server.rpcStarts()).hasValue(1);
      assertThat(server.ownerReads()).hasValue(1);
      assertThat(server.clientPeerUri()).hasValue(WORLD_URI);
    }
  }

  @Test
  void trustedWrongAccountWorkloadAndNamespaceCannotReachTheHeldOwner() throws Exception {
    DraftCommitOrderReadEvidence.Request request = DraftCommitOrderReadGrpcCodecTest.request();
    for (TestIdentity identity : List.of(pki.wrongWorkloadServer(), pki.otherNamespaceServer())) {
      try (TransportServer server = startServer(identity, request);
          DraftCommitOrderReadClient client = newClient(server.port())) {
        client.init();

        assertThatThrownBy(() -> client.read(request))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(server.rpcStarts()).as(identity.alias()).hasValue(0);
        assertThat(server.ownerReads()).as(identity.alias()).hasValue(0);
        assertThat(server.clientPeerUri().get()).isNull();
      }
    }
  }

  @Test
  void requiresAClientCertificateAndRejectsPlaintextOrMissingCertificateConfiguration()
      throws Exception {
    DraftCommitOrderReadEvidence.Request request = DraftCommitOrderReadGrpcCodecTest.request();
    try (TransportServer server = startServer(pki.accountServer(), request)) {
      CommonGrpcClientProperties noClientCertificate = new CommonGrpcClientProperties();
      noClientCertificate.setCaCert(pki.caCertificatePem().toString());
      ManagedChannel channel =
          new GrpcChannelFactory()
              .buildChannel("127.0.0.1:" + server.port(), 6565, noClientCertificate, false);
      try {
        assertThatThrownBy(
                () ->
                    AccountDraftCommitOrderReadServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .readHeldOriginalCommitOrder(
                            DraftCommitOrderReadGrpcCodec.toRequest(request)))
            .satisfies(
                failure -> assertThat(isMissingClientCertificateHandshake(failure)).isTrue());
        assertThat(server.rpcStarts()).hasValue(0);
        assertThat(server.ownerReads()).hasValue(0);
      } finally {
        channel.shutdownNow();
        assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }

      CommonGrpcClientProperties plaintext = pki.clientProperties(tempDirectory, pki.worldClient());
      plaintext.setPlaintext(true);
      assertThatThrownBy(
              () ->
                  new DraftCommitOrderReadClient(
                      endpoints(server.port()), plaintext, new GrpcChannelFactory(), NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("workload mTLS");

      CommonGrpcClientProperties missingCertificate =
          pki.clientProperties(tempDirectory, pki.worldClient());
      missingCertificate.setCertChain("");
      assertThatThrownBy(
              () ->
                  new DraftCommitOrderReadClient(
                      endpoints(server.port()),
                      missingCertificate,
                      new GrpcChannelFactory(),
                      NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("file-backed");
    }
  }

  private static TransportServer startServer(
      TestIdentity identity, DraftCommitOrderReadEvidence.Request expectedRequest)
      throws Exception {
    AtomicInteger rpcStarts = new AtomicInteger();
    AtomicInteger ownerReads = new AtomicInteger();
    AtomicReference<String> clientPeerUri = new AtomicReference<>();
    var owner = new TestOnlyHeldOwnerService(expectedRequest, ownerReads, clientPeerUri);
    ServerInterceptor countRpcStarts =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            rpcStarts.incrementAndGet();
            return next.startCall(call, metadata);
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    owner, countRpcStarts, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new TransportServer(server, rpcStarts, ownerReads, clientPeerUri);
  }

  private static DraftCommitOrderReadClient newClient(int port) throws Exception {
    return new DraftCommitOrderReadClient(
        endpoints(port),
        pki.clientProperties(tempDirectory, pki.worldClient()),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static ServiceEndpointsProperties endpoints(int port) {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + port);
    return endpoints;
  }

  private static boolean isMissingClientCertificateHandshake(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SSLException && cause.getMessage() != null) {
        String message = cause.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("certificate_required")
            || message.contains("certificate required")
            || message.contains("empty client certificate chain")
            || message.contains("peer did not return a certificate")) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns HELD for transport proof only; this does not query or represent the Account producer.
   */
  private static final class TestOnlyHeldOwnerService
      extends AccountDraftCommitOrderReadServiceGrpc.AccountDraftCommitOrderReadServiceImplBase {
    private final DraftCommitOrderReadEvidence.Request expectedRequest;
    private final AtomicInteger ownerReads;
    private final AtomicReference<String> clientPeerUri;

    private TestOnlyHeldOwnerService(
        DraftCommitOrderReadEvidence.Request expectedRequest,
        AtomicInteger ownerReads,
        AtomicReference<String> clientPeerUri) {
      this.expectedRequest = expectedRequest;
      this.ownerReads = ownerReads;
      this.clientPeerUri = clientPeerUri;
    }

    @Override
    public void readHeldOriginalCommitOrder(
        ReadHeldOriginalCommitOrderRequest wire,
        StreamObserver<ReadHeldOriginalCommitOrderResponse> observer) {
      ownerReads.incrementAndGet();
      GrpcPeerIdentity peer = GrpcPeerIdentity.current();
      if (peer != null) {
        clientPeerUri.set(peer.uri());
      }
      DraftCommitOrderReadEvidence.Request decoded =
          DraftCommitOrderReadGrpcCodec.fromRequest(wire);
      if (peer == null || !WORLD_URI.equals(peer.uri())) {
        observer.onError(
            Status.PERMISSION_DENIED
                .withDescription("Exact World test caller required")
                .asRuntimeException());
        return;
      }
      if (!expectedRequest.equals(decoded)) {
        observer.onError(
            Status.INVALID_ARGUMENT
                .withDescription("Unexpected test request")
                .asRuntimeException());
        return;
      }
      observer.onNext(DraftCommitOrderReadGrpcCodec.toHeldResponse(decoded));
      observer.onCompleted();
    }
  }

  private record TransportServer(
      Server server,
      AtomicInteger rpcStarts,
      AtomicInteger ownerReads,
      AtomicReference<String> clientPeerUri)
      implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account test server did not terminate");
      }
    }
  }

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      Path caCertificatePem,
      TestIdentity accountServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity otherNamespaceServer,
      TestIdentity worldClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD COMMIT_ORDER test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          caStore.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      Path caFile = directory.resolve("test-ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-rfc");
      X509Certificate caCertificate = readCertificate(caFile);
      return new TestPki(
          caCertificate,
          caFile,
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-workload-server", WRONG_WORKLOAD_URI, true),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "other-namespace-server",
              OTHER_NAMESPACE_ACCOUNT_URI,
              true),
          issueIdentity(directory, caStore, caFile, "world-client", WORLD_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      Path certificate =
          writePem(
              directory.resolve(identity.alias() + ".crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve(identity.alias() + ".key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
      properties.setCaCert(caFile.toString());
      return properties;
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, Path caFile, String alias, String workloadUri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
      String extendedKeyUsage = server ? "serverAuth" : "clientAuth";
      runKeytool(
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=" + alias,
          "-validity",
          "30",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + extendedKeyUsage,
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      runKeytool(
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          request.toString(),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-gencert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-infile",
          request.toString(),
          "-outfile",
          certificate.toString(),
          "-validity",
          "30",
          "-rfc",
          "-ext",
          "BC=ca:false",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + extendedKeyUsage,
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-importcert",
          "-alias",
          "test-ca",
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-noprompt");
      runKeytool(
          "-importcert",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          certificate.toString(),
          "-noprompt");
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, STORE_PASSWORD.toCharArray());
      }
      return new TestIdentity(
          alias,
          (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray()),
          (X509Certificate) keyStore.getCertificate(alias));
    }

    private static void runKeytool(String... arguments) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase().contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      ArrayList<String> command = new ArrayList<>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output;
      try (var stream = process.getInputStream()) {
        output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool failed: " + output);
      }
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
      try (var input = Files.newInputStream(path)) {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      }
    }

    private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      return Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
          StandardCharsets.US_ASCII);
    }
  }
}
