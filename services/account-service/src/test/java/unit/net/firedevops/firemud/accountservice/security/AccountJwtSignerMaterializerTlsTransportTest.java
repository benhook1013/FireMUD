package net.firedevops.firemud.accountservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.StringValue;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerTransportFilter;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Physical loopback gRPC mTLS transport proof for Account's JWT materializer interceptor only. The
 * test-only echo RPC does not prove Secret generation, database readiness, or live provisioning.
 */
class AccountJwtSignerMaterializerTlsTransportTest {
  private static final String SERVICE_NAME = "firemud.account.v1.JwtSignerMaterializerTransport";
  private static final String EXPECTED_PEER_URI =
      "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
  private static final String OTHER_PEER_URI =
      "spiffe://firemud/ns/firemud-prod/sa/other-materializer";
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final MethodDescriptor<StringValue, StringValue> PROBE_METHOD =
      MethodDescriptor.<StringValue, StringValue>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Probe"))
          .setRequestMarshaller(ProtoUtils.marshaller(StringValue.getDefaultInstance()))
          .setResponseMarshaller(ProtoUtils.marshaller(StringValue.getDefaultInstance()))
          .build();

  @TempDir private static Path temporaryDirectory;

  private static TestPki pki;

  private final List<ManagedChannel> channels = new ArrayList<>();
  private final AtomicReference<Binding> authenticatedBindingAtHandler = new AtomicReference<>();
  private final AtomicInteger handlerCalls = new AtomicInteger();
  private final AtomicInteger serverTransports = new AtomicInteger();

  private Server server;

  @BeforeAll
  static void generateEphemeralTestCertificates() throws Exception {
    Path trustedDirectory = Files.createDirectories(temporaryDirectory.resolve("trusted-pki"));
    TestAuthority trustedAuthority = createAuthority(trustedDirectory, "firemud-test-ca");
    TestCertificate serverCertificate =
        issueLeaf(
            trustedDirectory,
            trustedAuthority,
            "account-materializer-test-server",
            "spiffe://firemud/ns/firemud-prod/sa/account-service",
            true);
    TestCertificate pinnedMaterializer =
        issueLeaf(
            trustedDirectory,
            trustedAuthority,
            "materializer-cluster-one",
            EXPECTED_PEER_URI,
            false);
    TestCertificate sameUriOtherCluster =
        issueLeaf(
            trustedDirectory,
            trustedAuthority,
            "materializer-cluster-two",
            EXPECTED_PEER_URI,
            false);
    TestCertificate wrongUri =
        issueLeaf(
            trustedDirectory, trustedAuthority, "wrong-materializer-uri", OTHER_PEER_URI, false);

    Path unknownDirectory = Files.createDirectories(temporaryDirectory.resolve("unknown-pki"));
    TestAuthority unknownAuthority = createAuthority(unknownDirectory, "untrusted-test-ca");
    TestCertificate unknownCaMaterializer =
        issueLeaf(
            unknownDirectory,
            unknownAuthority,
            "unknown-ca-materializer",
            EXPECTED_PEER_URI,
            false);

    pki =
        new TestPki(
            trustedAuthority.certificate(),
            serverCertificate,
            pinnedMaterializer,
            sameUriOtherCluster,
            wrongUri,
            unknownCaMaterializer);
  }

  @AfterEach
  void stopTransport() throws InterruptedException {
    List<ManagedChannel> terminatingChannels =
        channels.stream().map(ManagedChannel::shutdownNow).toList();
    if (server != null) {
      server.shutdownNow();
    }
    for (ManagedChannel channel : terminatingChannels) {
      assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
    if (server != null) {
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void pinnedCertificateReachesHandlerWithTheAuthenticatedBindingOverLoopbackMtls()
      throws Exception {
    Binding binding = binding("prod-cluster-one", pki.pinnedMaterializer(), "revision-1");
    startServer(() -> Optional.of(binding));
    ManagedChannel channel = channel(pki.pinnedMaterializer());

    assertThat(invoke(channel).getValue()).isEqualTo("materializer transport probe");
    assertThat(authenticatedBindingAtHandler.get()).isEqualTo(binding);
    assertThat(handlerCalls.get()).isEqualTo(1);
    assertThat(serverTransports.get()).isEqualTo(1);
  }

  @Test
  void sameUriCertificateWithAnotherClustersKeyFailsTheIndependentSpkiPin() throws Exception {
    Binding binding = binding("prod-cluster-one", pki.pinnedMaterializer(), "revision-1");
    startServer(() -> Optional.of(binding));
    ManagedChannel channel = channel(pki.sameUriOtherCluster());

    assertPermissionDenied(() -> invoke(channel));
    assertThat(handlerCalls.get()).isZero();
    // The certificate chains to the server's trusted CA, so denial came from URI+SPKI binding.
    assertThat(serverTransports.get()).isEqualTo(1);
  }

  @Test
  void wrongUriFailsEvenWhenTheProtectedBindingPinsThatCertificatesKey() throws Exception {
    Binding binding = binding("prod-cluster-one", pki.wrongUri(), "revision-wrong-uri");
    startServer(() -> Optional.of(binding));
    ManagedChannel channel = channel(pki.wrongUri());

    assertPermissionDenied(() -> invoke(channel));
    assertThat(handlerCalls.get()).isZero();
    assertThat(serverTransports.get()).isEqualTo(1);
  }

  @Test
  void absentClientCertificateIsRejectedByTheTlsHandshake() throws Exception {
    Binding binding = binding("prod-cluster-one", pki.pinnedMaterializer(), "revision-1");
    startServer(() -> Optional.of(binding));
    ManagedChannel channel = channel(null);

    assertTlsHandshakeDenied(() -> invoke(channel));
    assertThat(handlerCalls.get()).isZero();
  }

  @Test
  void clientCertificateFromAnUnknownCaIsRejectedByTheTlsHandshake() throws Exception {
    Binding binding = binding("prod-cluster-one", pki.unknownCaMaterializer(), "revision-1");
    startServer(() -> Optional.of(binding));
    ManagedChannel channel = channel(pki.unknownCaMaterializer());

    assertTlsHandshakeDenied(() -> invoke(channel));
    assertThat(handlerCalls.get()).isZero();
  }

  @Test
  void trustWithdrawalDeniesTheNextRpcOnTheExistingTlsConnection() throws Exception {
    Binding binding = binding("prod-cluster-one", pki.pinnedMaterializer(), "revision-1");
    AtomicReference<Optional<Binding>> protectedBinding =
        new AtomicReference<>(Optional.of(binding));
    startServer(protectedBinding::get);
    ManagedChannel channel = channel(pki.pinnedMaterializer());

    invoke(channel);
    assertThat(handlerCalls.get()).isEqualTo(1);
    assertThat(serverTransports.get()).isEqualTo(1);

    protectedBinding.set(Optional.empty());
    assertPermissionDenied(() -> invoke(channel));
    assertThat(handlerCalls.get()).isEqualTo(1);
    assertThat(serverTransports.get()).isEqualTo(1);
  }

  @Test
  void changedClusterBindingDeniesTheOldPeerOnTheExistingTlsConnection() throws Exception {
    Binding original = binding("prod-cluster-one", pki.pinnedMaterializer(), "revision-1");
    Binding changed = binding("prod-cluster-two", pki.sameUriOtherCluster(), "revision-2");
    AtomicReference<Optional<Binding>> protectedBinding =
        new AtomicReference<>(Optional.of(original));
    startServer(protectedBinding::get);
    ManagedChannel channel = channel(pki.pinnedMaterializer());

    invoke(channel);
    assertThat(handlerCalls.get()).isEqualTo(1);
    assertThat(serverTransports.get()).isEqualTo(1);

    protectedBinding.set(Optional.of(changed));
    assertPermissionDenied(() -> invoke(channel));
    assertThat(handlerCalls.get()).isEqualTo(1);
    assertThat(serverTransports.get()).isEqualTo(1);
  }

  private void startServer(Supplier<Optional<Binding>> bindingProvider) throws Exception {
    AccountJwtSignerMaterializerTlsInterceptor interceptor =
        new AccountJwtSignerMaterializerTlsInterceptor(bindingProvider);
    ServerServiceDefinition testOnlyHandler =
        ServerServiceDefinition.builder(SERVICE_NAME)
            .addMethod(
                PROBE_METHOD,
                ServerCalls.asyncUnaryCall(
                    (StringValue request, StreamObserver<StringValue> responseObserver) -> {
                      authenticatedBindingAtHandler.set(
                          AccountJwtSignerMaterializerTlsInterceptor.authenticatedBinding());
                      handlerCalls.incrementAndGet();
                      responseObserver.onNext(request);
                      responseObserver.onCompleted();
                    }))
            .build();
    SslContext tlsContext =
        GrpcSslContexts.configure(
                SslContextBuilder.forServer(
                    pki.serverCertificate().certificate().toFile(),
                    pki.serverCertificate().privateKey().toFile()))
            .trustManager(pki.trustedCa().toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(tlsContext)
            .addTransportFilter(
                new ServerTransportFilter() {
                  @Override
                  public Attributes transportReady(Attributes attributes) {
                    serverTransports.incrementAndGet();
                    return attributes;
                  }
                })
            .addService(ServerInterceptors.intercept(testOnlyHandler, interceptor))
            .build()
            .start();
  }

  private ManagedChannel channel(TestCertificate clientCertificate) throws Exception {
    SslContextBuilder tlsBuilder =
        GrpcSslContexts.forClient().trustManager(pki.trustedCa().toFile());
    if (clientCertificate != null) {
      tlsBuilder.keyManager(
          clientCertificate.certificate().toFile(), clientCertificate.privateKey().toFile());
    }
    ManagedChannel channel =
        NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
            .sslContext(tlsBuilder.build())
            .build();
    channels.add(channel);
    return channel;
  }

  private static StringValue invoke(ManagedChannel channel) {
    CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS);
    return ClientCalls.blockingUnaryCall(
        channel, PROBE_METHOD, options, StringValue.of("materializer transport probe"));
  }

  private static void assertPermissionDenied(Runnable rpc) {
    Throwable failure = failureOf(rpc);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isFalse();
  }

  private static void assertTlsHandshakeDenied(Runnable rpc) {
    Throwable failure = failureOf(rpc);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isNotEqualTo(Status.Code.DEADLINE_EXCEEDED);
    assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue();
  }

  private static Throwable failureOf(Runnable rpc) {
    try {
      rpc.run();
      return null;
    } catch (Throwable failure) {
      return failure;
    }
  }

  private static Binding binding(String clusterId, TestCertificate certificate, String revision)
      throws Exception {
    String pin = spkiSha256(certificate.certificate());
    List<String> pins = List.of(pin);
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision,
            "prod",
            clusterId,
            "firemud-prod",
            CLUSTER_UID,
            NAMESPACE_UID,
            EXPECTED_PEER_URI,
            pins);
    return new Binding(
        "prod",
        clusterId,
        "firemud-prod",
        CLUSTER_UID,
        NAMESPACE_UID,
        EXPECTED_PEER_URI,
        pins,
        revision,
        digest);
  }

  private static String spkiSha256(Path certificatePath) throws Exception {
    X509Certificate certificate;
    try (InputStream input = Files.newInputStream(certificatePath)) {
      certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded()));
  }

  private static TestAuthority createAuthority(Path directory, String commonName) throws Exception {
    Path privateKey = directory.resolve(commonName + ".key");
    Path certificate = directory.resolve(commonName + ".crt");
    runOpenSsl(
        directory,
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        privateKey.toString(),
        "-out",
        certificate.toString(),
        "-subj",
        "/CN=" + commonName,
        "-days",
        "2",
        "-sha256",
        "-addext",
        "basicConstraints=critical,CA:TRUE",
        "-addext",
        "keyUsage=critical,keyCertSign,cRLSign");
    return new TestAuthority(privateKey, certificate);
  }

  private static TestCertificate issueLeaf(
      Path directory,
      TestAuthority authority,
      String commonName,
      String workloadUri,
      boolean serverCertificate)
      throws Exception {
    Path privateKey = directory.resolve(commonName + ".key");
    Path request = directory.resolve(commonName + ".csr");
    Path certificate = directory.resolve(commonName + ".crt");
    Path extensions = directory.resolve(commonName + ".ext");
    String extendedKeyUsage = serverCertificate ? "serverAuth" : "clientAuth";
    Files.writeString(
        extensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage="
            + extendedKeyUsage
            + "\n"
            + "subjectAltName=URI:"
            + workloadUri
            + ",DNS:localhost,IP:127.0.0.1\n");
    runOpenSsl(
        directory,
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        privateKey.toString(),
        "-out",
        request.toString(),
        "-subj",
        "/CN=" + commonName);
    runOpenSsl(
        directory,
        "x509",
        "-req",
        "-in",
        request.toString(),
        "-CA",
        authority.certificate().toString(),
        "-CAkey",
        authority.privateKey().toString(),
        "-CAcreateserial",
        "-out",
        certificate.toString(),
        "-days",
        "2",
        "-sha256",
        "-extfile",
        extensions.toString());
    return new TestCertificate(privateKey, certificate);
  }

  private static void runOpenSsl(Path directory, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("openssl");
    command.addAll(List.of(arguments));
    Process process;
    try {
      process =
          new ProcessBuilder(command)
              .directory(directory.toFile())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException exception) {
      throw new IllegalStateException("OpenSSL is required for the ephemeral TLS test fixture");
    }
    try {
      if (!process.waitFor(20, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
        throw new IllegalStateException("Ephemeral TLS fixture generation timed out");
      }
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Ephemeral TLS fixture generation was interrupted");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("Ephemeral TLS fixture generation failed");
    }
  }

  private record TestAuthority(Path privateKey, Path certificate) {}

  private record TestCertificate(Path privateKey, Path certificate) {}

  private record TestPki(
      Path trustedCa,
      TestCertificate serverCertificate,
      TestCertificate pinnedMaterializer,
      TestCertificate sameUriOtherCluster,
      TestCertificate wrongUri,
      TestCertificate unknownCaMaterializer) {}
}
