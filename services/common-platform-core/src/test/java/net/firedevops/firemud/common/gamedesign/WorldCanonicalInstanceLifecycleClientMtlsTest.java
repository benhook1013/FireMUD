package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleGrpcCodec;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstanceLifecycleReadServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical socket proof for the explicit Game Session to World lifecycle-read transport only. */
class WorldCanonicalInstanceLifecycleClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String OTHER_WORLD_URI =
      "spiffe://firemud/ns/other-test/sa/world-management-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

  @TempDir static Path tempDirectory;

  private static TestPki pki;
  private Server server;
  private WorldCanonicalInstanceLifecycleClient client;
  private AtomicInteger serverCallStarts;
  private AtomicInteger applicationCalls;
  private AtomicReference<String> receivedPeerUri;

  @BeforeAll
  static void createTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(tempDirectory);
  }

  @AfterEach
  void stopTransport() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  @Test
  void exactWorldWorkloadPeerReadsTheCompleteEchoedCaptureOverPhysicalMtls() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence expected =
        evidence("PREPARING", 7L, 10L, READ_REQUEST_ID);
    startServer(pki.worldServer(), ResponseMode.EXACT, expected);
    client = newClient(server);
    client.init();

    WorldCanonicalInstanceLifecycleEvidence actual = client.read(expected.request());

    assertThat(actual.canonicalBytes()).containsExactly(expected.canonicalBytes());
    assertThat(serverCallStarts).hasValue(1);
    assertThat(applicationCalls).hasValue(1);
    assertThat(receivedPeerUri).hasValue(GAME_SESSION_URI);
  }

  @Test
  void trustedWrongWorldWorkloadAndNamespaceCannotSendRequestMetadataOrBody() throws Exception {
    List<TestIdentity> wrongPeers = List.of(pki.wrongWorkloadServer(), pki.otherNamespaceServer());
    for (TestIdentity wrongPeer : wrongPeers) {
      WorldCanonicalInstanceLifecycleEvidence expected =
          evidence("PREPARING", 7L, 10L, READ_REQUEST_ID);
      startServer(wrongPeer, ResponseMode.EXACT, expected);
      client = newClient(server);
      client.init();

      assertThatThrownBy(() -> client.read(expected.request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(serverCallStarts).as(wrongPeer.alias()).hasValue(0);
      assertThat(applicationCalls).as(wrongPeer.alias()).hasValue(0);
      stopTransport();
    }
  }

  @Test
  void exactPeerResponseMustEchoTheRequestAndContainAValidCompleteCarrier() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence expected =
        evidence("PREPARING", 7L, 10L, READ_REQUEST_ID);

    startServer(pki.worldServer(), ResponseMode.WRONG_READ_REQUEST_ID, expected);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.read(expected.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical instance lifecycle evidence");
    assertThat(serverCallStarts).hasValue(1);
    assertThat(applicationCalls).hasValue(1);
    stopTransport();

    startServer(pki.worldServer(), ResponseMode.MALFORMED_EVIDENCE, expected);
    client = newClient(server);
    client.init();
    assertThatThrownBy(() -> client.read(expected.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid canonical instance lifecycle evidence");
    assertThat(serverCallStarts).hasValue(1);
    assertThat(applicationCalls).hasValue(1);
  }

  @Test
  void refusesPlaintextClasspathFilesAndCallsBeforeExplicitInitialization() throws Exception {
    serverCallStarts = new AtomicInteger();
    applicationCalls = new AtomicInteger();
    receivedPeerUri = new AtomicReference<>();
    var plaintext = pki.clientProperties(tempDirectory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceLifecycleClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    var classpath = pki.clientProperties(tempDirectory);
    classpath.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceLifecycleClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    WorldCanonicalInstanceLifecycleEvidence expected =
        evidence("PREPARING", 7L, 10L, READ_REQUEST_ID);
    startServer(pki.worldServer(), ResponseMode.EXACT, expected);
    client = newClient(server);
    assertThatThrownBy(() -> client.read(expected.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThat(serverCallStarts).hasValue(0);
    assertThat(applicationCalls).hasValue(0);
  }

  @Test
  void closeReleasesClientMonitorBeforeClosingCertificateWatcher() throws Exception {
    String previousReloadPolicy = System.getProperty("firemud.grpc.tls-reload.enabled");
    System.setProperty("firemud.grpc.tls-reload.enabled", "false");
    try {
      CountingChannelFactory channelFactory = new CountingChannelFactory();
      client =
          new WorldCanonicalInstanceLifecycleClient(
              new ServiceEndpointsProperties(),
              pki.clientProperties(tempDirectory),
              channelFactory,
              NAMESPACE);
      client.init();

      TlsCertificateWatcher watcher = mock(TlsCertificateWatcher.class);
      CountDownLatch callbackFinished = new CountDownLatch(1);
      AtomicBoolean callbackAcquiredMonitor = new AtomicBoolean();
      AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
      doAnswer(
              invocation -> {
                assertThat(Thread.holdsLock(client)).isFalse();
                Thread callback =
                    new Thread(
                        () -> {
                          try {
                            synchronized (client) {
                              callbackAcquiredMonitor.set(true);
                            }
                          } catch (Throwable failure) {
                            callbackFailure.set(failure);
                          } finally {
                            callbackFinished.countDown();
                          }
                        },
                        "world-canonical-lifecycle-client-test-callback");
                callback.start();
                if (!callbackFinished.await(1, TimeUnit.SECONDS)) {
                  callback.interrupt();
                  callback.join(TimeUnit.SECONDS.toMillis(1));
                  throw new AssertionError("Watcher callback could not acquire the client monitor");
                }
                callback.join(TimeUnit.SECONDS.toMillis(1));
                assertThat(callback.isAlive()).isFalse();
                assertThat(callbackAcquiredMonitor.get()).isTrue();
                assertThat(callbackFailure.get()).isNull();
                return null;
              })
          .when(watcher)
          .close();
      setWatcher(client, watcher);

      client.close();

      verify(watcher).close();
      assertThat(channelFactory.buildAttempts.get()).isEqualTo(1);
      assertThatThrownBy(client::init)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("World canonical lifecycle client is closed");
      assertThat(channelFactory.buildAttempts.get()).isEqualTo(1);
    } finally {
      if (previousReloadPolicy == null) {
        System.clearProperty("firemud.grpc.tls-reload.enabled");
      } else {
        System.setProperty("firemud.grpc.tls-reload.enabled", previousReloadPolicy);
      }
    }
  }

  private void startServer(
      TestIdentity identity,
      ResponseMode responseMode,
      WorldCanonicalInstanceLifecycleEvidence expected)
      throws Exception {
    serverCallStarts = new AtomicInteger();
    applicationCalls = new AtomicInteger();
    receivedPeerUri = new AtomicReference<>();
    var service =
        new WorldCanonicalInstanceLifecycleReadServiceGrpc
            .WorldCanonicalInstanceLifecycleReadServiceImplBase() {
          @Override
          public void readWorldCanonicalInstanceLifecycle(
              ReadWorldCanonicalInstanceLifecycleRequest request,
              StreamObserver<ReadWorldCanonicalInstanceLifecycleResponse> observer) {
            applicationCalls.incrementAndGet();
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            receivedPeerUri.set(peer == null ? null : peer.uri());
            WorldCanonicalInstanceLifecycleEvidence.Request decoded =
                WorldCanonicalInstanceLifecycleGrpcCodec.fromRequest(request);
            ReadWorldCanonicalInstanceLifecycleResponse response;
            if (responseMode == ResponseMode.MALFORMED_EVIDENCE) {
              response =
                  ReadWorldCanonicalInstanceLifecycleResponse.newBuilder()
                      .setCanonicalResponseBytes(ByteString.copyFrom(new byte[] {(byte) 0xff}))
                      .build();
            } else if (responseMode == ResponseMode.WRONG_READ_REQUEST_ID) {
              var changedRequest =
                  withReadRequestId(decoded, uuid("ffffffff-ffff-4fff-8fff-ffffffffffff"));
              var changedEvidence = withRequest(expected, changedRequest);
              response =
                  WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(
                      changedRequest, changedEvidence);
            } else {
              response = WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(decoded, expected);
            }
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    ServerInterceptor countApplicationHeaders =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            serverCallStarts.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, countApplicationHeaders, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private WorldCanonicalInstanceLifecycleClient newClient(Server target)
      throws IOException, CertificateEncodingException {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("localhost:" + target.getPort());
    return new WorldCanonicalInstanceLifecycleClient(
        endpoints, pki.clientProperties(tempDirectory), new GrpcChannelFactory(), NAMESPACE);
  }

  private static WorldCanonicalInstanceLifecycleEvidence evidence(
      String status, long epoch, long rowVersion, UUID readRequestId) throws Exception {
    WorldPublishedStartLocationEvidence selector =
        AuthoredWorldReleaseAttestationSelectorTest.selectorEvidence();
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldReleaseAttestationSelectorTest.descriptor(selector);
    AuthoredWorldReleaseAttestationEvidence release =
        AuthoredWorldReleaseAttestationSelectorTest.release(descriptor, selector);
    CompleteLaunchBindingEvidence binding = new CompleteLaunchBindingEvidence(descriptor, release);
    RoomTemplateRef startLocation =
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes()).startLocation();
    var request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            readRequestId,
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222"),
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            release.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        binding,
        startLocation,
        9_007_199_254_740_993L,
        status,
        epoch,
        rowVersion,
        uuid("33333333-3333-4333-8333-333333333333"),
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes())
            .graphDigest()
            .substring("sha256:".length()),
        "sha256:" + "e".repeat(64),
        java.util.Map.of(
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222")));
  }

  private static WorldCanonicalInstanceLifecycleEvidence withRequest(
      WorldCanonicalInstanceLifecycleEvidence evidence,
      WorldCanonicalInstanceLifecycleEvidence.Request request) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        evidence.launchBinding(),
        evidence.startLocation(),
        evidence.runtimeRoomInstanceId(),
        evidence.lifecycleStatus(),
        evidence.lifecycleEpoch(),
        evidence.rowVersion(),
        evidence.captureId(),
        evidence.graphSha256(),
        evidence.preparationInputDigest(),
        evidence.operationalRegionAssignments());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request withReadRequestId(
      WorldCanonicalInstanceLifecycleEvidence.Request source, UUID readRequestId) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        source.schemaVersion(),
        readRequestId,
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.worldSlug(),
        source.canonicalGameInstanceId(),
        source.playableStateNamespaceId(),
        source.playableStateScope(),
        source.publicProduction(),
        source.controlPlaneRequestId(),
        source.canonicalVersionId(),
        source.expectedDescriptorRequestDigest(),
        source.expectedDescriptorResultDigest(),
        source.expectedReleaseAttestationDigest());
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static void setWatcher(
      WorldCanonicalInstanceLifecycleClient client, TlsCertificateWatcher watcher)
      throws ReflectiveOperationException {
    Field watcherField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    watcherField.setAccessible(true);
    watcherField.set(client, watcher);
  }

  private static final class CountingChannelFactory extends GrpcChannelFactory {
    private final AtomicInteger buildAttempts = new AtomicInteger();

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      buildAttempts.incrementAndGet();
      return mock(ManagedChannel.class);
    }
  }

  private enum ResponseMode {
    EXACT,
    WRONG_READ_REQUEST_ID,
    MALFORMED_EVIDENCE
  }

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity worldServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity otherNamespaceServer,
      TestIdentity gameSessionClient) {
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
          "CN=FireMUD lifecycle test CA",
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
          issueIdentity(directory, caStore, caFile, "world-server", WORLD_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-workload-server", WRONG_WORKLOAD_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-namespace-server", OTHER_WORLD_URI, true),
          issueIdentity(
              directory, caStore, caFile, "game-session-client", GAME_SESSION_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory)
        throws IOException, CertificateEncodingException {
      Path clientCertificate =
          writePem(
              directory.resolve("game-session-client.crt"),
              "CERTIFICATE",
              gameSessionClient.certificate().getEncoded());
      Path clientPrivateKey =
          writePem(
              directory.resolve("game-session-client.key"),
              "PRIVATE KEY",
              gameSessionClient.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(clientCertificate.toString());
      properties.setPrivateKey(clientPrivateKey.toString());
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
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
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
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
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
      var command = new java.util.ArrayList<String>();
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
