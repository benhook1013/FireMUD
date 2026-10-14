package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalCompletionClient;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalCompletionGrpcCodec;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.test.TlsTestSupport;
import net.firedevops.firemud.worldmanagement.tenant.WorldGameDesignPublicationTerminalVerifierTest;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalCompletionGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalService;
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalCompletionServiceGrpc;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real loopback mTLS transport proof; World storage is mocked except for default-deny checks. */
class WorldPublicationTerminalCompletionGrpcMtlsTest {
  private static final java.util.concurrent.atomic.AtomicLong CERTIFICATE_SERIAL =
      new java.util.concurrent.atomic.AtomicLong(1);
  @TempDir Path temporaryDirectory;

  @Test
  void sameNamespaceGameDesignCertificateReturnsExactStoredTerminalOnRetry() throws Exception {
    var operation = WorldGameDesignPublicationTerminalVerifierTest.operation();
    var terminal =
        WorldGameDesignPublicationTerminalVerifierTest.terminal(operation, Outcome.PUBLISHED);
    var request =
        WorldPublicationTerminalCompletionGrpcCodec.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());
    var owner = mock(WorldPublicationTerminalService.class);
    when(owner.complete(any(), any())).thenReturn(terminal);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var pki = TestPki.create(temporaryDirectory);
    var handlerCalls = new AtomicInteger();
    var capturedResponse = new AtomicReference<Object>();

    try (var server = startServer(pki, service, handlerCalls, false, capturedResponse::set);
        var client = worldClient(pki, server.port())) {
      client.init();
      var first = client.complete(request);
      var retry = client.complete(request);

      assertThat(first.canonicalBytes()).containsExactly(terminal.canonicalBytes());
      assertThat(retry.canonicalBytes()).containsExactly(terminal.canonicalBytes());
      assertThat(handlerCalls).hasValue(2);
      assertThat(capturedResponse.get())
          .isEqualTo(WorldPublicationTerminalCompletionGrpcCodec.toResponse(request, terminal));

      var operationCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
      var terminalCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
      verify(owner, times(2)).complete(operationCaptor.capture(), terminalCaptor.capture());
      assertThat(operationCaptor.getAllValues())
          .allSatisfy(bytes -> assertThat(bytes).containsExactly(operation.canonicalBytes()));
      assertThat(terminalCaptor.getAllValues())
          .allSatisfy(bytes -> assertThat(bytes).containsExactly(terminal.canonicalBytes()));
    }
  }

  @Test
  void wrongWorkloadAndNamespaceCertificatesAreDeniedBeforeOwner() throws Exception {
    var input = request();
    var pki = TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server = startServer(pki, service, handlerCalls, false, null)) {
      for (var identity :
          java.util.List.of(pki.accountClient(), pki.otherNamespaceGameDesignClient())) {
        try (var channel = managedChannel(pki, server.port(), identity)) {
          assertThatThrownBy(
                  () ->
                      WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(
                              channel.channel())
                          .withDeadlineAfter(5, TimeUnit.SECONDS)
                          .completeVersionPublication(input.wire()))
              .isInstanceOf(StatusRuntimeException.class)
              .satisfies(
                  failure ->
                      assertThat(Status.fromThrowable(failure).getCode())
                          .isEqualTo(Status.Code.PERMISSION_DENIED));
        }
      }
    }

    assertThat(handlerCalls).hasValue(2);
    verifyNoInteractions(owner);
  }

  @Test
  void requestNamespaceMustMatchTheAuthenticatedWorldOwner() throws Exception {
    var input = request();
    var pki = TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "other");
    var handlerCalls = new AtomicInteger();

    try (var server =
            startServer(pki, pki.otherNamespaceWorldServer(), service, handlerCalls, false, null);
        var channel = managedChannel(pki, server.port(), pki.otherNamespaceGameDesignClient())) {
      assertThatThrownBy(
              () ->
                  WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(channel.channel())
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .completeVersionPublication(input.wire()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    assertThat(handlerCalls).hasValue(1);
    verifyNoInteractions(owner);
  }

  @Test
  void clientPinsExactWorldPeerBeforeSendingRequest() throws Exception {
    var input = request();
    var pki = TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
            startServer(pki, pki.otherNamespaceWorldServer(), service, handlerCalls, false, null);
        var client = worldClient(pki, server.port())) {
      client.init();
      assertThatThrownBy(() -> client.complete(input.request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    assertThat(handlerCalls).hasValue(0);
    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedEndUserContextIsDeniedBeforeOwner() throws Exception {
    var input = request();
    var pki = TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server = startServer(pki, service, handlerCalls, true, null);
        var channel = managedChannel(pki, server.port(), pki.gameDesignClient())) {
      assertThatThrownBy(
              () ->
                  WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(channel.channel())
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .completeVersionPublication(input.wire()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    assertThat(handlerCalls).hasValue(1);
    verifyNoInteractions(owner);
  }

  @Test
  void defaultMissingVerifierFailsClosedWithoutOwnerStorage() throws Exception {
    var input = request();
    var pki = TestPki.create(temporaryDirectory);
    var repository = mock(WorldPublicationTerminalRepository.class);
    var owner = new WorldPublicationTerminalService(repository);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server = startServer(pki, service, handlerCalls, false, null);
        var channel = managedChannel(pki, server.port(), pki.gameDesignClient())) {
      assertThatThrownBy(
              () ->
                  WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(channel.channel())
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .completeVersionPublication(input.wire()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
    }

    assertThat(handlerCalls).hasValue(1);
    verifyNoInteractions(repository);
  }

  @Test
  void missingClientCertificateIsRejectedDuringTlsBeforeHandlerOrOwner() throws Exception {
    var input = request();
    var pki = TestPki.create(temporaryDirectory);
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server = startServer(pki, service, handlerCalls, false, null);
        var unauthenticatedClient = clientWithoutIdentity(pki, server.port())) {
      assertThatThrownBy(
              () ->
                  WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(
                          unauthenticatedClient.channel())
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .completeVersionPublication(input.wire()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue());
    }

    assertThat(handlerCalls).hasValue(0);
    verifyNoInteractions(owner);
  }

  private static RequestFixture request() throws Exception {
    GameDesignPublicationOperationBinding operation =
        WorldGameDesignPublicationTerminalVerifierTest.operation();
    GameDesignPublicationTerminalEvidence terminal =
        WorldGameDesignPublicationTerminalVerifierTest.terminal(operation, Outcome.PUBLISHED);
    var request =
        WorldPublicationTerminalCompletionGrpcCodec.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());
    return new RequestFixture(
        request, WorldPublicationTerminalCompletionGrpcCodec.toRequest(request));
  }

  private static ManagedChannel channel(TestPki pki, int port, TestIdentity identity)
      throws Exception {
    return NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(pki.caCertificate())
                .keyManager(identity.privateKey(), identity.certificate())
                .build())
        .build();
  }

  private static ManagedChannelResource managedChannel(TestPki pki, int port, TestIdentity identity)
      throws Exception {
    return new ManagedChannelResource(channel(pki, port, identity));
  }

  private WorldPublicationTerminalCompletionClient worldClient(TestPki pki, int port)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + port);
    return new WorldPublicationTerminalCompletionClient(
        endpoints,
        pki.clientProperties(temporaryDirectory, pki.gameDesignClient()),
        new GrpcChannelFactory(),
        "test");
  }

  private record RequestFixture(
      WorldPublicationTerminalCompletionGrpcCodec.Request request,
      net.firedevops.firemud.worldmanagement.v1.CompleteVersionPublicationRequest wire) {}

  private record ManagedChannelResource(ManagedChannel channel) implements AutoCloseable {
    @Override
    public void close() throws InterruptedException {
      channel.shutdownNow();
      if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback gRPC client did not terminate");
      }
    }
  }

  // Test-local TLS material proves transport identity only; it grants no publication authority.
  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity worldServer,
      TestIdentity otherNamespaceWorldServer,
      TestIdentity gameDesignClient,
      TestIdentity accountClient,
      TestIdentity otherNamespaceGameDesignClient) {
    static TestPki create(Path directory) throws Exception {
      if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
        Security.addProvider(new BouncyCastleProvider());
      var key = newRsaKeyPair();
      var name = new X500Name("CN=World Publication Terminal Test CA, O=FireMUD Test");
      var ca = issueCertificate(name, key.getPublic(), name, key.getPrivate(), true, null);
      return new TestPki(
          ca,
          issueLeaf(
              name,
              key.getPrivate(),
              "world",
              "spiffe://firemud/ns/test/sa/world-management-service"),
          issueLeaf(
              name,
              key.getPrivate(),
              "other-world",
              "spiffe://firemud/ns/other/sa/world-management-service"),
          issueLeaf(
              name, key.getPrivate(), "design", "spiffe://firemud/ns/test/sa/game-design-service"),
          issueLeaf(
              name, key.getPrivate(), "account", "spiffe://firemud/ns/test/sa/account-service"),
          issueLeaf(
              name,
              key.getPrivate(),
              "other-design",
              "spiffe://firemud/ns/other/sa/game-design-service"));
    }

    CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      var tls = new CommonGrpcClientProperties();
      tls.setCertChain(
          Files.writeString(
                  directory.resolve("client.crt"),
                  pem("CERTIFICATE", identity.certificate().getEncoded()))
              .toString());
      tls.setPrivateKey(
          Files.writeString(
                  directory.resolve("client.key"),
                  pem("PRIVATE KEY", identity.privateKey().getEncoded()))
              .toString());
      tls.setCaCert(
          Files.writeString(
                  directory.resolve("ca.crt"), pem("CERTIFICATE", caCertificate.getEncoded()))
              .toString());
      return tls;
    }
  }

  private record ServerResource(io.grpc.Server server) implements AutoCloseable {
    int port() {
      return server.getPort();
    }

    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS))
        throw new IllegalStateException("Loopback gRPC server did not terminate");
    }
  }

  private static ServerResource startServer(
      TestPki pki,
      WorldPublicationTerminalCompletionGrpcService service,
      AtomicInteger calls,
      boolean endUser,
      java.util.function.Consumer<Object> capture)
      throws Exception {
    return startServer(pki, pki.worldServer(), service, calls, endUser, capture);
  }

  private static ServerResource startServer(
      TestPki pki,
      TestIdentity identity,
      WorldPublicationTerminalCompletionGrpcService service,
      AtomicInteger calls,
      boolean endUser,
      java.util.function.Consumer<Object> capture)
      throws Exception {
    io.grpc.ServerInterceptor observation =
        new io.grpc.ServerInterceptor() {
          public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
              io.grpc.ServerCall<ReqT, RespT> call,
              io.grpc.Metadata headers,
              io.grpc.ServerCallHandler<ReqT, RespT> next) {
            var forwarding =
                new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  public void sendMessage(RespT response) {
                    if (capture != null) capture.accept(response);
                    super.sendMessage(response);
                  }
                };
            var listener = next.startCall(forwarding, headers);
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<
                ReqT>(listener) {
              public void onHalfClose() {
                calls.incrementAndGet();
                if (endUser)
                  SessionContext.setContext("test-user", java.util.List.of(), java.util.Map.of());
                try {
                  super.onHalfClose();
                } finally {
                  SessionContext.clear();
                }
              }
            };
          }
        };
    var definition =
        io.grpc.ServerInterceptors.intercept(
            service, observation, new GrpcPeerIdentityInterceptor());
    var ssl =
        GrpcSslContexts.configure(
                io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder.forServer(
                    identity.privateKey(), identity.certificate()))
            .trustManager(pki.caCertificate())
            .clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.REQUIRE)
            .build();
    return new ServerResource(
        io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(
                new InetSocketAddress("127.0.0.1", 0))
            .sslContext(ssl)
            .addService(definition)
            .build()
            .start());
  }

  private static ManagedChannelResource clientWithoutIdentity(TestPki pki, int port)
      throws Exception {
    return new ManagedChannelResource(
        NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
            .sslContext(GrpcSslContexts.forClient().trustManager(pki.caCertificate()).build())
            .build());
  }

  private static String pem(String type, byte[] bytes) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  private static TestIdentity issueLeaf(
      X500Name caName, PrivateKey caPrivateKey, String commonName, String workloadUri)
      throws Exception {
    KeyPair keyPair = newRsaKeyPair();
    X500Name subject = new X500Name("CN=" + commonName + ", O=FireMUD Test");
    GeneralName[] subjectAltNames =
        workloadUri == null
            ? new GeneralName[] {
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            }
            : new GeneralName[] {
              new GeneralName(GeneralName.uniformResourceIdentifier, workloadUri),
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            };
    X509Certificate certificate =
        issueCertificate(
            subject,
            keyPair.getPublic(),
            caName,
            caPrivateKey,
            false,
            new GeneralNames(subjectAltNames));
    return new TestIdentity(keyPair.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerPrivateKey,
      boolean ca,
      GeneralNames subjectAltNames)
      throws Exception {
    Instant now = Instant.now().minusSeconds(60);
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement()),
            Date.from(now),
            Date.from(now.plusSeconds(60L * 60L * 24L * 14L)),
            subject,
            publicKey);
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    if (ca) {
      builder.addExtension(
          Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    } else {
      builder.addExtension(
          Extension.keyUsage,
          true,
          new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
      builder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);
    }
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(issuerPrivateKey)));
  }

  private static KeyPair newRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
