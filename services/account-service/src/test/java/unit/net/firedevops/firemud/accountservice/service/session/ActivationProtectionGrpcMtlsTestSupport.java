package unit.net.firedevops.firemud.accountservice.service.session;

import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
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
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;

/** Shared controlled PKI for the two activation receiver socket tests. */
final class ActivationProtectionGrpcMtlsTestSupport {
  private static final String PASSWORD = "test-only-activation-pki-password";
  private static final String WORLD_URI =
      "spiffe://firemud/ns/world-runtime/sa/world-management-service";

  private ActivationProtectionGrpcMtlsTestSupport() {}

  static ServerResource startServer(TestPki pki, BindableService service, AtomicInteger handlers)
      throws Exception {
    ServerInterceptor count =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            handlers.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.server().privateKey(), pki.server().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(service, count, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new ServerResource(server, handlers);
  }

  static ClientResource client(TestPki pki, Path directory, int port, Identity identity)
      throws Exception {
    return new ClientResource(
        new GrpcChannelFactory()
            .buildChannel(
                "127.0.0.1:" + port, 6565, pki.clientProperties(directory, identity), false));
  }

  static ClientResource clientWithoutIdentity(TestPki pki, Path directory, int port)
      throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCaCert(
        writePem(
                directory.resolve("trust-only-ca.crt"),
                "CERTIFICATE",
                pki.caCertificate().getEncoded())
            .toString());
    return new ClientResource(
        new GrpcChannelFactory().buildChannel("127.0.0.1:" + port, 6565, tls, false));
  }

  static final class TestPki {
    private final X509Certificate caCertificate;
    private final Identity server;
    private final Identity worldClient;
    private final Identity wrongWorkloadClient;
    private final Identity otherNamespaceClient;

    private TestPki(
        X509Certificate caCertificate,
        Identity server,
        Identity worldClient,
        Identity wrongWorkloadClient,
        Identity otherNamespaceClient) {
      this.caCertificate = caCertificate;
      this.server = server;
      this.worldClient = worldClient;
      this.wrongWorkloadClient = wrongWorkloadClient;
      this.otherNamespaceClient = otherNamespaceClient;
    }

    static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("activation-test-ca.p12");
      runKeytool(
          directory,
          "-genkeypair",
          "-alias",
          "activation-test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD controlled activation test CA",
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
      Path caFile = directory.resolve("activation-test-ca.crt");
      runKeytool(
          directory,
          "-exportcert",
          "-alias",
          "activation-test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-file",
          caFile.toString(),
          "-rfc");
      var ca = readCertificate(caFile);
      return new TestPki(
          ca,
          issue(
              directory,
              caStore,
              caFile,
              "activation-server",
              "spiffe://firemud/ns/world-runtime/sa/account-service",
              true),
          issue(directory, caStore, caFile, "world-client", WORLD_URI, false),
          issue(
              directory,
              caStore,
              caFile,
              "wrong-client",
              "spiffe://firemud/ns/world-runtime/sa/game-session-service",
              false),
          issue(
              directory,
              caStore,
              caFile,
              "other-namespace-client",
              "spiffe://firemud/ns/other-runtime/sa/world-management-service",
              false));
    }

    X509Certificate caCertificate() {
      return caCertificate;
    }

    Identity server() {
      return server;
    }

    Identity worldClient() {
      return worldClient;
    }

    Identity wrongWorkloadClient() {
      return wrongWorkloadClient;
    }

    Identity otherNamespaceClient() {
      return otherNamespaceClient;
    }

    CommonGrpcClientProperties clientProperties(Path directory, Identity identity)
        throws Exception {
      CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
      tls.setCertChain(
          writePem(
                  directory.resolve(identity.alias() + ".crt"),
                  "CERTIFICATE",
                  identity.certificate().getEncoded())
              .toString());
      tls.setPrivateKey(
          writePem(
                  directory.resolve(identity.alias() + ".key"),
                  "PRIVATE KEY",
                  identity.privateKey().getEncoded())
              .toString());
      tls.setCaCert(
          writePem(
                  directory.resolve("activation-client-trust.crt"),
                  "CERTIFICATE",
                  caCertificate.getEncoded())
              .toString());
      tls.setPlaintext(false);
      return tls;
    }

    private static Identity issue(
        Path directory, Path caStore, Path caFile, String alias, String uri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path csr = directory.resolve(alias + ".csr");
      Path cert = directory.resolve(alias + "-signed.crt");
      String san = server ? "URI:" + uri + ",DNS:localhost,IP:127.0.0.1" : "URI:" + uri;
      String usage = server ? "serverAuth" : "clientAuth";
      runKeytool(
          directory,
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
          PASSWORD,
          "-keypass",
          PASSWORD);
      runKeytool(
          directory,
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
          csr.toString(),
          "-ext",
          "SAN=" + san);
      runKeytool(
          directory,
          "-gencert",
          "-alias",
          "activation-test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-infile",
          csr.toString(),
          "-outfile",
          cert.toString(),
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
          directory,
          "-importcert",
          "-alias",
          "activation-test-ca",
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-file",
          caFile.toString(),
          "-noprompt");
      runKeytool(
          directory,
          "-importcert",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-file",
          cert.toString(),
          "-noprompt");
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, PASSWORD.toCharArray());
      }
      return new Identity(
          alias,
          (PrivateKey) keyStore.getKey(alias, PASSWORD.toCharArray()),
          (X509Certificate) keyStore.getCertificate(alias));
    }

    private static void runKeytool(Path directory, String... args) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase().contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      ArrayList<String> command = new ArrayList<>();
      command.add(keytool.toString());
      command.addAll(List.of(args));
      Path output = Files.createTempFile(directory, "keytool-output-", ".log");
      try {
        Process process =
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        awaitKeytoolExit(process, 30, TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
          throw new IllegalStateException("test keytool failed: " + Files.readString(output));
        }
      } finally {
        Files.deleteIfExists(output);
      }
    }

    /** Waits before reading child output, then terminates and reaps on timeout or interruption. */
    static void awaitKeytoolExit(Process process, long timeout, TimeUnit unit)
        throws InterruptedException {
      try {
        if (!process.waitFor(timeout, unit)) {
          throw new IllegalStateException("test keytool exceeded its execution deadline");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw interrupted;
      } finally {
        boolean restoreInterrupt = Thread.interrupted();
        try {
          if (process.isAlive()) process.destroyForcibly();
          long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          boolean reaped = false;
          while (!reaped) {
            long remaining = cleanupDeadline - System.nanoTime();
            if (remaining <= 0) {
              throw new IllegalStateException("test keytool could not be reaped after termination");
            }
            try {
              reaped = process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
              restoreInterrupt = true;
            }
          }
        } finally {
          if (restoreInterrupt) Thread.currentThread().interrupt();
        }
      }
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
      try (var input = Files.newInputStream(path)) {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      }
    }
  }

  record Identity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  record ServerResource(Server server, AtomicInteger handlers) implements AutoCloseable {
    int getPort() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Activation test gRPC server did not stop");
      }
    }
  }

  record ClientResource(ManagedChannel channel) implements AutoCloseable {
    @Override
    public void close() throws InterruptedException {
      channel.shutdownNow();
      if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Activation test gRPC client did not stop");
      }
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
