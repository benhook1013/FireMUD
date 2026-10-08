package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstancePreparationServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical socket proof with a transport-only World double, not owner/database proof. */
class CanonicalWorldInstancePreparationClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_SERVER_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String OTHER_NAMESPACE_URI =
      "spiffe://firemud/ns/other-test/sa/world-management-service";
  private static final String GAME_SESSION_CLIENT_URI = WRONG_WORKLOAD_URI;
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final long ABOVE_JAVASCRIPT_INTEGER = 9_007_199_254_740_993L;

  @TempDir static Path tempDirectory;
  private static TestPki pki;
  private Server server;
  private CanonicalWorldInstancePreparationClient client;
  private AtomicInteger headers;
  private AtomicInteger bodies;

  @BeforeAll
  static void createTrustedCertificates() throws Exception {
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
  void exactWorldPeerReturnsCompleteV2LifecycleReadback() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    Request request = request(evidence);
    startServer(pki.worldManagementServer(), request, response(request, evidence));
    client = newClient(server);

    assertThatThrownBy(() -> client.prepare(request, evidence.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);

    client.init();
    var prepared = client.prepare(request, evidence.request());
    assertThat(prepared).isEqualTo(evidence);
    assertThat(prepared.launchBinding().releaseAttestation().schemaVersion())
        .isEqualTo(AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
    assertThat(prepared.runtimeRoomInstanceId()).isEqualTo(ABOVE_JAVASCRIPT_INTEGER);
    assertThat(prepared.lifecycleEpoch()).isEqualTo(ABOVE_JAVASCRIPT_INTEGER + 1);
    assertThat(prepared.rowVersion()).isEqualTo(ABOVE_JAVASCRIPT_INTEGER + 2);
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);

    client.close();
    assertThatThrownBy(() -> client.prepare(request, evidence.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);
  }

  @Test
  void trustedWrongWorkloadOrNamespaceReleasesNoRequestHeadersOrBody() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    Request request = request(evidence);
    for (TestIdentity identity : List.of(pki.wrongWorkloadServer(), pki.otherNamespaceServer())) {
      startServer(identity, request, response(request, evidence));
      client = newClient(server);
      client.init();
      assertThatThrownBy(() -> client.prepare(request, evidence.request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              exception ->
                  assertThat(Status.fromThrowable(exception).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(headers).hasValue(0);
      assertThat(bodies).hasValue(0);
      stopTransport();
    }
  }

  @Test
  void rejectsChangedOuterAndInnerReadbackOverTheSocket() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    Request request = request(evidence);
    PrepareCanonicalWorldInstanceResponse valid = response(request, evidence);
    List<PrepareCanonicalWorldInstanceResponse> changedResponses =
        List.of(
            valid.toBuilder()
                .setRequest(valid.getRequest().toBuilder().setWorldSlug("other-world"))
                .build(),
            valid.toBuilder()
                .setLifecycle(
                    WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(
                        copyLifecycleRequest(
                            evidence.request(), uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")),
                        withRequest(
                            evidence,
                            copyLifecycleRequest(
                                evidence.request(), uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")))))
                .build());

    for (PrepareCanonicalWorldInstanceResponse changedResponse : changedResponses) {
      startServer(pki.worldManagementServer(), request, changedResponse);
      client = newClient(server);
      client.init();
      assertThatThrownBy(() -> client.prepare(request, evidence.request()))
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseInstanceOf(IllegalArgumentException.class);
      assertThat(headers).hasValue(1);
      assertThat(bodies).hasValue(1);
      stopTransport();
    }
  }

  @Test
  void rejectsUnsafeTlsAndMismatchedSelectorsBeforeSending(@TempDir Path directory)
      throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    Request request = request(evidence);
    startServer(pki.worldManagementServer(), request, response(request, evidence));

    var plaintext = pki.clientProperties(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new CanonicalWorldInstancePreparationClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var classpath = pki.clientProperties(directory);
    classpath.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new CanonicalWorldInstancePreparationClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    var absentCertificate = pki.clientProperties(directory);
    absentCertificate.setCertChain(directory.resolve("absent-client.crt").toString());
    assertThatThrownBy(
            () ->
                new CanonicalWorldInstancePreparationClient(
                    new ServiceEndpointsProperties(),
                    absentCertificate,
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file");

    client = newClient(server);
    assertThatThrownBy(() -> client.prepare(request, evidence.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    client.init();

    Request otherNamespaceRequest = copyAssociationRequest(request, "other-test");
    assertThatThrownBy(() -> client.prepare(otherNamespaceRequest, evidence.request()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured workload namespace");

    var differentLifecycleRequest =
        copyLifecycleRequest(evidence.request(), uuid("99999999-9999-4999-8999-999999999999"));
    assertThatThrownBy(() -> client.prepare(request, differentLifecycleRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expected World lifecycle request");
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);

    client.close();
    assertThatThrownBy(() -> client.prepare(request, evidence.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);
  }

  private void startServer(
      TestIdentity identity,
      Request expectedRequest,
      PrepareCanonicalWorldInstanceResponse response)
      throws Exception {
    headers = new AtomicInteger();
    bodies = new AtomicInteger();
    var service =
        new WorldCanonicalInstancePreparationServiceGrpc
            .WorldCanonicalInstancePreparationServiceImplBase() {
          @Override
          public void prepareCanonicalWorldInstance(
              PrepareCanonicalWorldInstanceRequest wire,
              StreamObserver<PrepareCanonicalWorldInstanceResponse> observer) {
            bodies.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(GAME_SESSION_CLIENT_URI);
            assertThat(CanonicalWorldInstancePreparationGrpcCodec.fromRequest(wire))
                .isEqualTo(expectedRequest);
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    ServerInterceptor countHeaders =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            headers.incrementAndGet();
            return next.startCall(call, metadata);
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
                    service, countHeaders, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private CanonicalWorldInstancePreparationClient newClient(Server target) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("localhost:" + target.getPort());
    return new CanonicalWorldInstancePreparationClient(
        endpoints, pki.clientProperties(tempDirectory), new GrpcChannelFactory(), NAMESPACE);
  }

  private static PrepareCanonicalWorldInstanceResponse response(
      Request request, WorldCanonicalInstanceLifecycleEvidence evidence) {
    return CanonicalWorldInstancePreparationGrpcCodec.toResponse(request, evidence);
  }

  private static Request request(WorldCanonicalInstanceLifecycleEvidence evidence) {
    var lifecycleRequest = evidence.request();
    return new Request(
        lifecycleRequest.readRequestId(),
        lifecycleRequest.targetNamespace(),
        lifecycleRequest.canonicalTenantId(),
        lifecycleRequest.worldSlug(),
        lifecycleRequest.canonicalGameInstanceId(),
        lifecycleRequest.controlPlaneRequestId(),
        evidence.launchBinding().descriptor().launchDescriptorId(),
        lifecycleRequest.expectedDescriptorRequestDigest(),
        lifecycleRequest.expectedDescriptorResultDigest(),
        lifecycleRequest.expectedReleaseAttestationDigest());
  }

  private static Request copyAssociationRequest(Request source, String targetNamespace) {
    return new Request(
        source.readRequestId(),
        targetNamespace,
        source.canonicalTenantId(),
        source.worldSlug(),
        source.gameInstanceUuid(),
        source.controlPlaneRequestId(),
        source.launchDescriptorId(),
        source.expectedDescriptorRequestDigest(),
        source.expectedDescriptorResultDigest(),
        source.expectedReleaseAttestationEvidenceDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request copyLifecycleRequest(
      WorldCanonicalInstanceLifecycleEvidence.Request source, UUID gameInstanceId) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        source.schemaVersion(),
        source.readRequestId(),
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.worldSlug(),
        gameInstanceId,
        source.playableStateNamespaceId(),
        source.playableStateScope(),
        source.publicProduction(),
        source.controlPlaneRequestId(),
        source.canonicalVersionId(),
        source.expectedDescriptorRequestDigest(),
        source.expectedDescriptorResultDigest(),
        source.expectedReleaseAttestationDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence withRequest(
      WorldCanonicalInstanceLifecycleEvidence source,
      WorldCanonicalInstanceLifecycleEvidence.Request request) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        source.launchBinding(),
        source.startLocation(),
        source.runtimeRoomInstanceId(),
        source.lifecycleStatus(),
        source.lifecycleEpoch(),
        source.rowVersion(),
        source.captureId(),
        source.graphSha256(),
        source.preparationInputDigest(),
        source.operationalRegionAssignments());
  }

  private static WorldCanonicalInstanceLifecycleEvidence evidence() throws Exception {
    WorldPublishedStartLocationEvidence selector =
        WorldPublishedStartLocationGrpcCodecTest.evidence();
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            selector.request().targetNamespace(),
            "world-lifecycle-control-request",
            selector.request().canonicalTenantId(),
            "synthetic-world",
            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "sha256:" + "a".repeat(64),
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "canonical-instance-launch-descriptor",
            42L,
            false,
            null,
            "{}",
            "generation-revision",
            9L,
            7L,
            "release-bundle",
            false,
            null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.request().appliedCommitId(),
                        selector.request().contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            selector.request().canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selector.request().publishWorkflowId(),
            selector.request().appliedCommitId(),
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision(),
            selector);
    CompleteLaunchBindingEvidence binding = new CompleteLaunchBindingEvidence(descriptor, release);
    WorldDraftStartLocationEvidence selectorReceipt =
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
    var request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
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
        selectorReceipt.startLocation(),
        ABOVE_JAVASCRIPT_INTEGER,
        "PREPARING",
        ABOVE_JAVASCRIPT_INTEGER + 1,
        ABOVE_JAVASCRIPT_INTEGER + 2,
        uuid("33333333-3333-4333-8333-333333333333"),
        selectorReceipt.graphDigest().substring("sha256:".length()),
        "sha256:" + "e".repeat(64),
        java.util.Map.of(
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222")));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity worldManagementServer,
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
          "CN=FireMUD World preparation test CA",
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
          issueIdentity(
              directory, caStore, caFile, "world-management-server", WORLD_SERVER_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-workload-server", WRONG_WORKLOAD_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-namespace-server", OTHER_NAMESPACE_URI, true),
          issueIdentity(
              directory, caStore, caFile, "game-session-client", GAME_SESSION_CLIENT_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      Path cert =
          writePem(
              directory.resolve("game-session-client.crt"),
              "CERTIFICATE",
              gameSessionClient.certificate().getEncoded());
      Path key =
          writePem(
              directory.resolve("game-session-client.key"),
              "PRIVATE KEY",
              gameSessionClient.privateKey().getEncoded());
      Path ca =
          writePem(
              directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setCertChain(cert.toString());
      properties.setPrivateKey(key.toString());
      properties.setCaCert(ca.toString());
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
      var command = new ArrayList<String>();
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
