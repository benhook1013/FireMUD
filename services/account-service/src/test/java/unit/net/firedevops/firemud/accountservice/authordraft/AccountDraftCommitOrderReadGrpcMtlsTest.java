package unit.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountDraftCommitOrderReadServiceGrpc;
import net.firedevops.firemud.account.v1.HeldOriginalCommitOrderStatus;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderRequest;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderResponse;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadGrpcService;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadService;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadGrpcCodec;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical loopback mTLS proof for the Account held-COMMIT_ORDER gRPC handler boundary. */
class AccountDraftCommitOrderReadGrpcMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String OTHER_NAMESPACE_WORLD_URI =
      "spiffe://firemud/ns/other-test/sa/world-management-service";
  private static final String STORE_PASSWORD = "test-only-store-password";

  @TempDir static Path tempDirectory;

  private static TestPki pki;

  @BeforeAll
  static void createTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(tempDirectory);
  }

  @Test
  void exactSameNamespaceWorldPeerReachesHeldOwnerAndGetsExactHeldResponse() throws Exception {
    DraftCommitOrderReadEvidence.Request request = validRequest();
    TestOnlyHeldOwner owner = new TestOnlyHeldOwner();
    try (TransportServer server = startServer(owner);
        ClientTransport client = newClient(server.port(), pki.worldClient())) {
      ReadHeldOriginalCommitOrderResponse response =
          client.read(DraftCommitOrderReadGrpcCodec.toRequest(request));

      assertThat(response).isEqualTo(DraftCommitOrderReadGrpcCodec.toHeldResponse(request));
      assertThat(response.getStatus())
          .isEqualTo(HeldOriginalCommitOrderStatus.HELD_ORIGINAL_COMMIT_ORDER_STATUS_HELD);
      assertThat(server.handlerCalls()).hasValue(1);
      assertThat(owner.reads()).hasValue(1);
      assertThat(owner.peerUri().get()).isEqualTo(WORLD_URI);
      verify(owner.service()).requireHeld(request);
    }
  }

  @Test
  void trustedWrongWorkloadAndNamespaceAreDeniedBeforeOwnerReads() throws Exception {
    DraftCommitOrderReadEvidence.Request request = validRequest();
    for (TestIdentity identity : List.of(pki.wrongWorkloadClient(), pki.otherNamespaceClient())) {
      TestOnlyHeldOwner owner = new TestOnlyHeldOwner();
      try (TransportServer server = startServer(owner);
          ClientTransport client = newClient(server.port(), identity)) {
        assertStatus(
            Status.Code.PERMISSION_DENIED,
            () -> client.read(DraftCommitOrderReadGrpcCodec.toRequest(request)));

        assertThat(server.handlerCalls()).as(identity.alias()).hasValue(1);
        assertThat(owner.reads()).as(identity.alias()).hasValue(0);
        verifyNoInteractions(owner.service());
      }
    }
  }

  @Test
  void missingClientCertificateIsRejectedBeforeHandlerOrOwnerRead() throws Exception {
    TestOnlyHeldOwner owner = new TestOnlyHeldOwner();
    try (TransportServer server = startServer(owner);
        ClientTransport client = newClientWithoutIdentity(server.port())) {
      assertThatThrownBy(() -> client.read(DraftCommitOrderReadGrpcCodec.toRequest(validRequest())))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue());

      assertThat(server.handlerCalls()).hasValue(0);
      assertThat(owner.reads()).hasValue(0);
      verifyNoInteractions(owner.service());
    }
  }

  @Test
  void missingOrMalformedBindingFromExactWorldPeerFailsParsingWithoutHeldResponse()
      throws Exception {
    TestOnlyHeldOwner owner = new TestOnlyHeldOwner();
    try (TransportServer server = startServer(owner);
        ClientTransport client = newClient(server.port(), pki.worldClient())) {
      for (ReadHeldOriginalCommitOrderRequest malformed : malformedRequests()) {
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.read(malformed));
      }

      assertThat(server.handlerCalls()).hasValue(2);
      assertThat(owner.reads()).hasValue(0);
      verifyNoInteractions(owner.service());
    }
  }

  private static DraftCommitOrderReadEvidence.Request validRequest() {
    return AccountDraftCommitOrderReadServiceTest.request(
        AccountDraftCommitOrderReadServiceTest.binding(1));
  }

  private static List<ReadHeldOriginalCommitOrderRequest> malformedRequests() {
    String readRequestId = "33333333-3333-4333-8333-333333333333";
    ReadHeldOriginalCommitOrderRequest missingBinding =
        ReadHeldOriginalCommitOrderRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setReadRequestId(readRequestId)
            .build();
    ReadHeldOriginalCommitOrderRequest malformedBinding =
        missingBinding.toBuilder()
            .setOriginalAccountBinding(ByteString.copyFrom(new byte[] {1}))
            .build();
    return List.of(missingBinding, malformedBinding);
  }

  private static void assertStatus(
      Status.Code expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
    assertThatThrownBy(operation)
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(expected);
  }

  private static TransportServer startServer(TestOnlyHeldOwner owner) throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    AccountDraftCommitOrderReadGrpcService service =
        new AccountDraftCommitOrderReadGrpcService(owner.service(), NAMESPACE);
    ServerInterceptor countHandlerCalls =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            handlerCalls.incrementAndGet();
            return next.startCall(call, metadata);
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.accountServer().privateKey(), pki.accountServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, countHandlerCalls, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new TransportServer(server, handlerCalls);
  }

  private static ClientTransport newClient(int port, TestIdentity identity) throws Exception {
    return newClient(port, pki.clientProperties(tempDirectory, identity));
  }

  private static ClientTransport newClientWithoutIdentity(int port) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCaCert(pki.caCertificatePem().toString());
    return newClient(port, tls);
  }

  private static ClientTransport newClient(int port, CommonGrpcClientProperties tls)
      throws Exception {
    ManagedChannel channel =
        new GrpcChannelFactory().buildChannel("127.0.0.1:" + port, 6565, tls, false);
    return new ClientTransport(channel);
  }

  /** Mockito owner used only to prove the production handler's transport and caller boundary. */
  private static final class TestOnlyHeldOwner {
    private final AccountDraftCommitOrderReadService service =
        mock(AccountDraftCommitOrderReadService.class);
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicReference<String> peerUri = new AtomicReference<>();

    private TestOnlyHeldOwner() {
      doAnswer(
              invocation -> {
                reads.incrementAndGet();
                GrpcPeerIdentity peer = GrpcPeerIdentity.current();
                peerUri.set(peer == null ? null : peer.uri());
                return null;
              })
          .when(service)
          .requireHeld(any(DraftCommitOrderReadEvidence.Request.class));
    }

    private AccountDraftCommitOrderReadService service() {
      return service;
    }

    private AtomicInteger reads() {
      return reads;
    }

    private AtomicReference<String> peerUri() {
      return peerUri;
    }
  }

  private record TransportServer(Server server, AtomicInteger handlerCalls)
      implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account gRPC server did not terminate");
      }
    }
  }

  private record ClientTransport(ManagedChannel channel) implements AutoCloseable {
    private AccountDraftCommitOrderReadServiceGrpc.AccountDraftCommitOrderReadServiceBlockingStub
        stub() {
      return AccountDraftCommitOrderReadServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(3, TimeUnit.SECONDS);
    }

    private ReadHeldOriginalCommitOrderResponse read(ReadHeldOriginalCommitOrderRequest request) {
      return stub().readHeldOriginalCommitOrder(request);
    }

    @Override
    public void close() throws InterruptedException {
      assertThat(channel.shutdownNow()).isNotNull();
      if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account gRPC client did not terminate");
      }
    }
  }

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      Path caCertificatePem,
      TestIdentity accountServer,
      TestIdentity worldClient,
      TestIdentity wrongWorkloadClient,
      TestIdentity otherNamespaceClient) {
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
          "CN=FireMUD Account COMMIT_ORDER test CA",
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
          issueIdentity(directory, caStore, caFile, "world-client", WORLD_URI, false),
          issueIdentity(
              directory, caStore, caFile, "wrong-workload-client", WRONG_WORKLOAD_URI, false),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "other-namespace-client",
              OTHER_NAMESPACE_WORLD_URI,
              false));
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
      CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
      tls.setCertChain(certificate.toString());
      tls.setPrivateKey(privateKey.toString());
      tls.setCaCert(caFile.toString());
      tls.setPlaintext(false);
      return tls;
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
