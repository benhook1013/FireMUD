package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.gamedesign.v1.GameDesignDraftTerminalReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadServiceGrpc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical mTLS loopback proof for large exact terminal-read responses from both owners. */
class DraftTerminalReadClientMtlsTest {
  private static final int MAX_KEYTOOL_FAILURE_OUTPUT_BYTES = 64 * 1024;
  private static final long KEYTOOL_TIMEOUT_SECONDS = 30L;
  private static final long KEYTOOL_TERMINATION_TIMEOUT_SECONDS = 2L;
  private static final String NAMESPACE = "test";
  private static final String GAME_DESIGN_URI =
      "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String STORE_PASSWORD = "test-only-store-password";

  @TempDir static Path temporaryDirectory;
  private static TestPki pki;

  @BeforeAll
  static void createTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @Test
  void gameDesignClientAcceptsBoundedResponseAboveOldFourMiBTransportLimit() throws Exception {
    var base = AuthoringFixtures.freshGraphRequest();
    var request =
        new GameDesignDraftTerminalReadEvidence.Request(
            1,
            NAMESPACE,
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            base.originalAccountBinding());
    var readback = largeAbort(request.accountBinding(), Owner.GAME_DESIGN, request.originalAccountBinding());
    var response = GameDesignDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(readback));
    AtomicReference<ReadGameDesignDraftTerminalOutcomeRequest> received = new AtomicReference<>();

    try (TransportServer server = startGameDesignServer(response, received);
        GameDesignDraftTerminalReadClient client = gameDesignClient(server.port())) {
      client.init();
      var configuredStub = gameDesignStub(client);

      assertThatThrownBy(
              () ->
                  configuredStub
                      .withMaxInboundMessageSize(4 * 1024 * 1024)
                      .readGameDesignDraftTerminalOutcome(
                          GameDesignDraftTerminalReadGrpcCodec.toRequest(request)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));

      var actual = client.read(request);
      assertThat(actual.ownerReadback().orElseThrow().canonicalBytes())
          .containsExactly(readback.canonicalBytes());
      assertThat(received.get()).isEqualTo(GameDesignDraftTerminalReadGrpcCodec.toRequest(request));
      assertThat(configuredStub.getCallOptions().getMaxInboundMessageSize())
          .isEqualTo(DraftTerminalReadGrpcLimits.MAX_RESPONSE_WIRE_BYTES);
    }
  }

  @Test
  void worldClientAcceptsBoundedResponseAboveOldFourMiBTransportLimit() throws Exception {
    var request = AuthoringFixtures.freshGraphRequest();
    var readback = largeAbort(request.accountBinding(), Owner.WORLD, request.originalAccountBinding());
    var response = WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(readback));
    AtomicReference<ReadWorldDraftTerminalOutcomeRequest> received = new AtomicReference<>();

    try (TransportServer server = startWorldServer(response, received);
        WorldDraftTerminalReadClient client = worldClient(server.port())) {
      client.init();
      var configuredStub = worldStub(client);

      assertThatThrownBy(
              () ->
                  configuredStub
                      .withMaxInboundMessageSize(4 * 1024 * 1024)
                      .readWorldDraftTerminalOutcome(
                          WorldDraftTerminalReadGrpcCodec.toRequest(request)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));

      var actual = client.read(request);
      assertThat(actual.ownerReadback().orElseThrow().canonicalBytes())
          .containsExactly(readback.canonicalBytes());
      assertThat(received.get()).isEqualTo(WorldDraftTerminalReadGrpcCodec.toRequest(request));
      assertThat(configuredStub.getCallOptions().getMaxInboundMessageSize())
          .isEqualTo(DraftTerminalReadGrpcLimits.MAX_RESPONSE_WIRE_BYTES);
    }
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback largeAbort(
      DraftAuthorizationFenceBinding binding, Owner owner, byte[] originalBinding) {
    return new DraftAuthorizationFenceBinding.OwnerReadback(
        owner,
        Outcome.DEFINITIVELY_ABORTED,
        binding.operationId(),
        binding.commitId(),
        binding.fenceId(),
        binding.inputDigest(),
        originalBinding,
        new byte[DraftTerminalReadGrpcLimits.MAX_BINDING_BYTES + 1]);
  }

  private static TransportServer startGameDesignServer(
      ReadGameDesignDraftTerminalOutcomeResponse response,
      AtomicReference<ReadGameDesignDraftTerminalOutcomeRequest> received)
      throws Exception {
    var service =
        new GameDesignDraftTerminalReadServiceGrpc.GameDesignDraftTerminalReadServiceImplBase() {
          @Override
          public void readGameDesignDraftTerminalOutcome(
              ReadGameDesignDraftTerminalOutcomeRequest request,
              StreamObserver<ReadGameDesignDraftTerminalOutcomeResponse> observer) {
            received.set(request);
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    return startServer(pki.gameDesignServer(), service);
  }

  private static TransportServer startWorldServer(
      ReadWorldDraftTerminalOutcomeResponse response,
      AtomicReference<ReadWorldDraftTerminalOutcomeRequest> received)
      throws Exception {
    var service =
        new WorldDraftTerminalReadServiceGrpc.WorldDraftTerminalReadServiceImplBase() {
          @Override
          public void readWorldDraftTerminalOutcome(
              ReadWorldDraftTerminalOutcomeRequest request,
              StreamObserver<ReadWorldDraftTerminalOutcomeResponse> observer) {
            received.set(request);
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    return startServer(pki.worldServer(), service);
  }

  private static TransportServer startServer(
      TestIdentity identity, io.grpc.BindableService service) throws Exception {
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(service)
            .build()
            .start();
    return new TransportServer(server);
  }

  private static GameDesignDraftTerminalReadClient gameDesignClient(int port) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("127.0.0.1:" + port);
    return new GameDesignDraftTerminalReadClient(
        endpoints,
        pki.clientProperties(temporaryDirectory),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static WorldDraftTerminalReadClient worldClient(int port) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + port);
    return new WorldDraftTerminalReadClient(
        endpoints,
        pki.clientProperties(temporaryDirectory),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static GameDesignDraftTerminalReadServiceGrpc.GameDesignDraftTerminalReadServiceBlockingStub
      gameDesignStub(GameDesignDraftTerminalReadClient client) throws ReflectiveOperationException {
    var field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (GameDesignDraftTerminalReadServiceGrpc.GameDesignDraftTerminalReadServiceBlockingStub)
        field.get(client);
  }

  private static WorldDraftTerminalReadServiceGrpc.WorldDraftTerminalReadServiceBlockingStub
      worldStub(WorldDraftTerminalReadClient client) throws ReflectiveOperationException {
    var field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    return (WorldDraftTerminalReadServiceGrpc.WorldDraftTerminalReadServiceBlockingStub)
        field.get(client);
  }

  private record TransportServer(Server server) implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Draft terminal read loopback server did not terminate");
      }
    }
  }

  private record TestIdentity(
      String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity gameDesignServer,
      TestIdentity worldServer,
      TestIdentity accountClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("test-ca.p12");
      runKeytool(
          "-genkeypair", "-alias", "test-ca", "-keyalg", "RSA", "-keysize", "2048",
          "-dname", "CN=FireMUD Draft terminal read test CA", "-validity", "30", "-ext",
          "BC=ca:true", "-ext", "KU=keyCertSign,cRLSign", "-storetype", "PKCS12",
          "-keystore", caStore.toString(), "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
      Path caFile = directory.resolve("test-ca.crt");
      runKeytool(
          "-exportcert", "-alias", "test-ca", "-keystore", caStore.toString(), "-storetype",
          "PKCS12", "-storepass", STORE_PASSWORD, "-file", caFile.toString(), "-rfc");
      X509Certificate caCertificate = readCertificate(caFile);
      return new TestPki(
          caCertificate,
          issueIdentity(directory, caStore, caFile, "game-design-server", GAME_DESIGN_URI, true),
          issueIdentity(directory, caStore, caFile, "world-server", WORLD_URI, true),
          issueIdentity(directory, caStore, caFile, "account-client", ACCOUNT_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      Path certificate = writePem(directory.resolve("account-client.crt"), "CERTIFICATE", accountClient.certificate().getEncoded());
      Path privateKey = writePem(directory.resolve("account-client.key"), "PRIVATE KEY", accountClient.privateKey().getEncoded());
      Path caFile = writePem(directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
      properties.setCaCert(caFile.toString());
      return properties;
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, Path caFile, String alias, String uri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + uri + ",DNS:localhost,IP:127.0.0.1";
      String usage = server ? "serverAuth" : "clientAuth";
      runKeytool(
          "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname",
          "CN=" + alias, "-validity", "30", "-ext", "KU=digitalSignature,keyEncipherment",
          "-ext", "EKU=" + usage, "-ext", "SAN=" + san, "-storetype", "PKCS12", "-keystore",
          store.toString(), "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
      runKeytool(
          "-certreq", "-alias", alias, "-keystore", store.toString(), "-storetype", "PKCS12",
          "-storepass", STORE_PASSWORD, "-file", request.toString(), "-ext", "SAN=" + san);
      runKeytool(
          "-gencert", "-alias", "test-ca", "-keystore", caStore.toString(), "-storetype",
          "PKCS12", "-storepass", STORE_PASSWORD, "-infile", request.toString(), "-outfile",
          certificate.toString(), "-validity", "30", "-rfc", "-ext", "BC=ca:false", "-ext",
          "KU=digitalSignature,keyEncipherment", "-ext", "EKU=" + usage, "-ext", "SAN=" + san);
      runKeytool(
          "-importcert", "-alias", "test-ca", "-keystore", store.toString(), "-storetype",
          "PKCS12", "-storepass", STORE_PASSWORD, "-file", caFile.toString(), "-noprompt");
      runKeytool(
          "-importcert", "-alias", alias, "-keystore", store.toString(), "-storetype", "PKCS12",
          "-storepass", STORE_PASSWORD, "-file", certificate.toString(), "-noprompt");
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
      var command = new ArrayList<String>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Path outputFile = Files.createTempFile("firemud-draft-terminal-keytool-", ".log");
      final Process process;
      try {
        process =
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
      } catch (IOException startupFailure) {
        Files.deleteIfExists(outputFile);
        throw startupFailure;
      }
      try {
        final boolean completed;
        try {
          completed = process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          boolean reaped = terminateAndReap(process);
          interrupted.addSuppressed(
              new IllegalStateException(
                  "keytool interrupted; process reaped="
                      + reaped
                      + "; "
                      + readFailureOutput(outputFile)));
          Thread.currentThread().interrupt();
          throw interrupted;
        }
        if (!completed) {
          boolean reaped = terminateAndReap(process);
          throw new IllegalStateException(
              "keytool timed out; process reaped="
                  + reaped
                  + "; "
                  + readFailureOutput(outputFile));
        }
        String output = readFailureOutput(outputFile);
        if (process.exitValue() != 0) {
          throw new IllegalStateException(
              "keytool failed with exit " + process.exitValue() + ": " + output);
        }
      } finally {
        if (process.isAlive()) {
          terminateAndReap(process);
        }
        Files.deleteIfExists(outputFile);
      }
    }

    private static boolean terminateAndReap(Process process) {
      boolean interrupted = Thread.interrupted();
      process.destroy();
      long deadline =
          System.nanoTime() + TimeUnit.SECONDS.toNanos(KEYTOOL_TERMINATION_TIMEOUT_SECONDS);
      interrupted = waitUntilDeadline(process, deadline, interrupted);
      if (process.isAlive()) {
        process.destroyForcibly();
        deadline =
            System.nanoTime() + TimeUnit.SECONDS.toNanos(KEYTOOL_TERMINATION_TIMEOUT_SECONDS);
        interrupted = waitUntilDeadline(process, deadline, interrupted);
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      return !process.isAlive();
    }

    private static boolean waitUntilDeadline(
        Process process, long deadline, boolean interrupted) {
      while (process.isAlive()) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
          break;
        }
        long waitMillis =
            Math.max(1L, Math.min(100L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        try {
          process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruption) {
          interrupted = true;
          process.destroyForcibly();
        }
      }
      return interrupted;
    }

    private static String readFailureOutput(Path outputFile) throws IOException {
      try (var input = Files.newInputStream(outputFile)) {
        byte[] output = input.readNBytes(MAX_KEYTOOL_FAILURE_OUTPUT_BYTES);
        String text = new String(output, StandardCharsets.UTF_8);
        return Files.size(outputFile) > output.length ? text + " [output truncated]" : text;
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
