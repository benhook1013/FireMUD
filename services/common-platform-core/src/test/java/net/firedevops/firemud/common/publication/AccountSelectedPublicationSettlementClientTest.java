package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountSelectedPublicationSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationRequest;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical mTLS proof that Account settlement may complete after the former five-second window. */
class AccountSelectedPublicationSettlementClientTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String STORE_PASSWORD = "test-only-store-password";

  @Test
  void requiresFileBackedMtlsWithAccountSettlementContext(@TempDir Path directory) {
    var endpoints = new ServiceEndpointsProperties();
    var factory = mock(GrpcChannelFactory.class);
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AccountSelectedPublicationSettlementClient(
                    endpoints, plaintext, factory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Account publication settlement")
        .hasNoCause();

    var classpath = new CommonGrpcClientProperties();
    classpath.setPlaintext(false);
    classpath.setCertChain("classpath:client.crt");
    classpath.setPrivateKey(directory.resolve("client.key").toString());
    classpath.setCaCert(directory.resolve("ca.crt").toString());
    assertThatThrownBy(
            () ->
                new AccountSelectedPublicationSettlementClient(
                    endpoints, classpath, factory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Account publication settlement")
        .hasMessageNotContaining("classpath:client.crt")
        .hasNoCause();
    verifyNoInteractions(factory);
  }

  @Test
  void returnsCanonicalReceiptFromAuthenticatedAccountPeerAfterFormerFiveSecondWindow(
      @TempDir Path directory) throws Exception {
    TestPki pki = TestPki.create(directory);
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var terminal =
        WorldPublicationTerminalTestFixtures.terminal(
            operation, GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED);
    var request =
        AccountSelectedPublicationSettlementGrpcCodec.Request.create(
            NAMESPACE, operation.canonicalBytes());
    byte[] receipt = receipt(operation, terminal);
    var response = AccountSelectedPublicationSettlementGrpcCodec.toResponse(request, receipt);
    AtomicReference<SettleSelectedPublicationRequest> received = new AtomicReference<>();
    var service =
        new AccountSelectedPublicationSettlementServiceGrpc
            .AccountSelectedPublicationSettlementServiceImplBase() {
          @Override
          public void settleSelectedPublication(
              SettleSelectedPublicationRequest wireRequest,
              StreamObserver<SettleSelectedPublicationResponse> observer) {
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            if (peer == null || !GAME_DESIGN_URI.equals(peer.uri())) {
              observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
              return;
            }
            received.set(wireRequest);
            try {
              TimeUnit.SECONDS.sleep(6L);
              observer.onNext(response);
              observer.onCompleted();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              observer.onError(Status.CANCELLED.withCause(interrupted).asRuntimeException());
            }
          }
        };

    try (TransportServer server = startServer(pki, service);
        AccountSelectedPublicationSettlementClient client = client(pki, server.port(), directory)) {
      client.init();
      long started = System.nanoTime();
      byte[] actualReceipt = client.settle(request);
      long elapsedNanos = System.nanoTime() - started;

      assertThat(elapsedNanos).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(6L));
      assertThat(actualReceipt).containsExactly(receipt);
      assertThat(received.get())
          .isEqualTo(AccountSelectedPublicationSettlementGrpcCodec.toRequest(request));
    }
  }

  private static byte[] receipt(
      GameDesignPublicationOperationBinding operation,
      GameDesignPublicationTerminalEvidence terminal) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "account-selected-publication-settlement/v1");
    DraftAuthorizationFenceBinding.frame(out, operation.account().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, terminal.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(
        out,
        terminal.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
            ? "PUBLISHED"
            : "ABORTED");
    DraftAuthorizationFenceBinding.frame(out, terminal.canonicalBytes());
    return out.toByteArray();
  }

  private static TransportServer startServer(TestPki pki, io.grpc.BindableService service)
      throws Exception {
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.accountServer().privateKey(), pki.accountServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(service)
            .intercept(new GrpcPeerIdentityInterceptor())
            .build()
            .start();
    return new TransportServer(server);
  }

  private static AccountSelectedPublicationSettlementClient client(
      TestPki pki, int port, Path directory) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + port);
    return new AccountSelectedPublicationSettlementClient(
        endpoints, pki.clientProperties(directory), new GrpcChannelFactory(), NAMESPACE);
  }

  private record TransportServer(Server server) implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Account settlement loopback server did not terminate");
      }
    }
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate, TestIdentity accountServer, TestIdentity gameDesignClient) {
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
          "CN=FireMUD Account settlement test CA",
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
      return new TestPki(
          readCertificate(caFile),
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(directory, caStore, caFile, "game-design-client", GAME_DESIGN_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      Path certificate =
          writePem(
              directory.resolve("game-design-client.crt"),
              "CERTIFICATE",
              gameDesignClient.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve("game-design-client.key"),
              "PRIVATE KEY",
              gameDesignClient.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
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
          "EKU=" + usage,
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
          "EKU=" + usage,
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
      Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
        throw new IllegalStateException("test keytool operation timed out");
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "test keytool operation failed with exit " + process.exitValue());
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
