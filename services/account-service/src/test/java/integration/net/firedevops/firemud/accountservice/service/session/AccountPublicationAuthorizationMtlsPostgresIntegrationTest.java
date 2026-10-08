package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Genuine Account issuance/current actor, publication producer/storage, loopback mutual TLS and
 * production read client. Trust provisioning, legal sources, synchronized Draft and World APPLIED
 * descriptors are explicitly stipulated test inputs. No real World handoff, Game Design release,
 * deployed workload trust, public wiring, or activation is established by this isolated proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountPublicationAuthorizationMtlsPostgresIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("publication-mtls-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "publication-mtls-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void genuineProducerToMtlsReaderReplaysExactlyAndRejectsTrustedPeerAndBindingSubstitution()
      throws Exception {
    var pki = new TestPki(Files.createDirectories(temporary.resolve("pki")));
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            Files.createDirectories(temporary.resolve("owner")))) {
      var issued = fixture.issueCreator();
      var sources = issued.sources();
      var descriptor =
          AccountPublicationAuthorizationPostgresIntegrationTest.proof(
              sources, issued.environment());
      var repository = new AccountPublicationAuthorizationRepository(sources.dsl);
      var producer =
          new AccountPublicationAuthorizationService(issued.actors(), sources.fences, repository);
      var original =
          producer.authorize(
              issued.compact(), descriptor.selection(), descriptor.world(), issued.environment());
      var request = AccountPublicationAuthorizationReadEvidence.Request.create(NAMESPACE, original);
      var endpoint =
          new AccountPublicationAuthorizationReadGrpcService(
              new AccountPublicationAuthorizationReadService(
                  repository, sources.manager, NAMESPACE),
              NAMESPACE);
      Server server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .sslContext(
                  GrpcSslContexts.forServer(
                          pki.server.certificate().toFile(), pki.server.key().toFile())
                      .trustManager(pki.ca.toFile())
                      .clientAuth(ClientAuth.REQUIRE)
                      .build())
              .addService(ServerInterceptors.intercept(endpoint, new GrpcPeerIdentityInterceptor()))
              .build()
              .start();
      try {
        try (var client = client(server.getPort(), pki.gameDesign, pki.ca)) {
          var first = client.read(request);
          var retry = client.read(request);
          assertThat(first.request()).isEqualTo(request);
          assertThat(retry.request()).isEqualTo(first.request());
          assertThat(first.request().originalPublicationAuthorizationBinding())
              .isEqualTo(original.canonicalBytes());
          var freshRead =
              AccountPublicationAuthorizationReadEvidence.Request.create(NAMESPACE, original);
          assertThat(client.read(freshRead).request()).isEqualTo(freshRead);
          var unknown =
              new AccountPublicationAuthorizationBinding(
                  UUID.randomUUID(), original.fenceId(), original.input(), original.sources());
          var changedFence =
              new AccountPublicationAuthorizationBinding(
                  original.operationId(), UUID.randomUUID(), original.input(), original.sources());
          for (var rejected : List.of(unknown, changedFence)) {
            assertCode(
                Status.Code.FAILED_PRECONDITION,
                () ->
                    client.read(
                        AccountPublicationAuthorizationReadEvidence.Request.create(
                            NAMESPACE, rejected)));
          }
          // A negative read cannot change the exact held owner row or prevent its next read.
          assertThat(client.read(request).request()).isEqualTo(request);
        }
        for (var wrongPeer : List.of(pki.wrongWorkload, pki.otherNamespace)) {
          try (var client = client(server.getPort(), wrongPeer, pki.ca)) {
            assertCode(Status.Code.PERMISSION_DENIED, () -> client.read(request));
          }
        }
        assertThat(
                sources.dsl.fetchCount(
                    org.jooq.impl.DSL.table("account_selected_publication_authorizations")))
            .isEqualTo(1);
        assertThat(
                sources.dsl.fetchCount(
                    org.jooq.impl.DSL.table("account_draft_authorization_fences")))
            .isZero();
        assertThat(
                java.util.Objects.requireNonNull(
                        sources.dsl.fetchOne(
                            "SELECT binding FROM account_selected_publication_authorizations WHERE operation_id = ?",
                            original.operationId()),
                        "Expected exact retained publication after transport reads")
                    .get("binding", byte[].class))
            .isEqualTo(original.canonicalBytes());
      } finally {
        server.shutdownNow();
        assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static AccountPublicationAuthorizationReadClient client(
      int port, TestIdentity identity, Path ca) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + port);
    var client =
        new AccountPublicationAuthorizationReadClient(
            endpoints, identity.properties(ca), new GrpcChannelFactory(), NAMESPACE);
    try {
      client.init();
      return client;
    } catch (Exception failure) {
      client.close();
      throw failure;
    }
  }

  private static void assertCode(Status.Code expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private record TestIdentity(Path certificate, Path key) {
    CommonGrpcClientProperties properties(Path ca) {
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(key.toString());
      properties.setCaCert(ca.toString());
      return properties;
    }
  }

  /** Ephemeral keytool PKI, following Account's existing loopback test; no runtime credentials. */
  private static final class TestPki {
    private static final String PASSWORD = "test-only-publication-mtls-password";
    final Path ca;
    final TestIdentity server, gameDesign, wrongWorkload, otherNamespace;

    TestPki(Path root) throws Exception {
      Path caStore = root.resolve("ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=Account publication test CA",
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
          PASSWORD,
          "-keypass",
          PASSWORD);
      ca = root.resolve("ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-file",
          ca.toString(),
          "-rfc");
      server = issue(root, caStore, "account-server", NAMESPACE, "account-service", true);
      gameDesign =
          issue(root, caStore, "game-design-client", NAMESPACE, "game-design-service", false);
      wrongWorkload =
          issue(root, caStore, "world-client", NAMESPACE, "world-management-service", false);
      otherNamespace =
          issue(
              root,
              caStore,
              "other-game-design-client",
              "other-test",
              "game-design-service",
              false);
    }

    private static TestIdentity issue(
        Path root, Path caStore, String alias, String namespace, String workload, boolean server)
        throws Exception {
      Path store = root.resolve(alias + ".p12"),
          request = root.resolve(alias + ".csr"),
          certificate = root.resolve(alias + ".crt");
      String san =
          "URI:spiffe://firemud/ns/"
              + namespace
              + "/sa/"
              + workload
              + ",DNS:localhost,IP:127.0.0.1";
      String eku = server ? "serverAuth" : "clientAuth";
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
          "EKU=" + eku,
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          PASSWORD,
          "-keypass",
          PASSWORD);
      runKeytool(
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
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
          PASSWORD,
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
          "EKU=" + eku,
          "-ext",
          "SAN=" + san);
      var keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, PASSWORD.toCharArray());
      }
      byte[] privateKey =
          java.util.Objects.requireNonNull(
              java.util.Objects.requireNonNull(
                      keyStore.getKey(alias, PASSWORD.toCharArray()),
                      "Expected ephemeral test private key")
                  .getEncoded(),
              "Expected encoded ephemeral test private key");
      Path key = root.resolve(alias + ".key");
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(privateKey);
      Files.writeString(
          key,
          "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n",
          StandardCharsets.US_ASCII);
      java.util.Arrays.fill(privateKey, (byte) 0);
      return new TestIdentity(certificate, key);
    }

    private static void runKeytool(String... arguments) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      var command = new ArrayList<String>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Ephemeral test certificate generation timed out");
      }
      try (var output = process.getInputStream()) {
        String details = new String(output.readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0)
          throw new IllegalStateException("Test keytool failed: " + details);
      }
    }
  }
}
