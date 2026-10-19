package net.firedevops.firemud.accountservice.testsupport;

import java.io.IOException;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;

/** Controlled loopback PKI for the original Logging claim-read client; no production authority. */
public final class AccountStartSessionReservationProofPki {
  private static final String PASSWORD = "test-only-reservation-pki-password";
  private final Path directory;
  private final X509Certificate ca;
  private final Map<String, Identity> servers;
  private final Identity account;

  private AccountStartSessionReservationProofPki(
      Path directory, X509Certificate ca, Map<String, Identity> servers, Identity account) {
    this.directory = directory;
    this.ca = ca;
    this.servers = servers;
    this.account = account;
  }

  public static AccountStartSessionReservationProofPki create(Path directory, String namespace)
      throws Exception {
    Files.createDirectories(directory);
    Path caStore = directory.resolve("reservation-test-ca.p12");
    Path caFile = directory.resolve("reservation-test-ca.crt");
    runKeytool(
        directory,
        "-genkeypair",
        "-alias",
        "reservation-test-ca",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-dname",
        "CN=Controlled Logging claim-read test CA",
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
    runKeytool(
        directory,
        "-exportcert",
        "-alias",
        "reservation-test-ca",
        "-keystore",
        caStore.toString(),
        "-storetype",
        "PKCS12",
        "-storepass",
        PASSWORD,
        "-file",
        caFile.toString(),
        "-rfc");
    Map<String, Identity> servers =
        Map.of(
            "logging",
                issue(
                    directory,
                    caStore,
                    caFile,
                    "logging-server",
                    "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
                    true),
            "wrong-workload",
                issue(
                    directory,
                    caStore,
                    caFile,
                    "wrong-workload-server",
                    "spiffe://firemud/ns/" + namespace + "/sa/game-session-service",
                    true),
            "missing-identity",
                issue(
                    directory,
                    caStore,
                    caFile,
                    "missing-identity-server",
                    "https://localhost/controlled-test",
                    true),
            "wrong-namespace",
                issue(
                    directory,
                    caStore,
                    caFile,
                    "wrong-namespace-server",
                    "spiffe://firemud/ns/other-runtime/sa/logging-admin-service",
                    true));
    Identity account =
        issue(
            directory,
            caStore,
            caFile,
            "account-client",
            "spiffe://firemud/ns/" + namespace + "/sa/account-service",
            false);
    return new AccountStartSessionReservationProofPki(
        directory, readCertificate(caFile), servers, account);
  }

  public Identity accountIdentity() {
    return account;
  }

  public X509Certificate caCertificate() {
    return ca;
  }

  public Identity server(String name) {
    return java.util.Objects.requireNonNull(servers.get(name));
  }

  public CommonGrpcClientProperties clientProperties() throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(
        writePem(
                directory.resolve("account-client.crt"),
                "CERTIFICATE",
                account.certificate().getEncoded())
            .toString());
    tls.setPrivateKey(
        writePem(
                directory.resolve("account-client.key"),
                "PRIVATE KEY",
                account.privateKey().getEncoded())
            .toString());
    tls.setCaCert(
        writePem(directory.resolve("account-client-trust.crt"), "CERTIFICATE", ca.getEncoded())
            .toString());
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
        "reservation-test-ca",
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
        "reservation-test-ca",
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

  public static void runKeytool(Path directory, String... args) throws Exception {
    Path keytool =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")
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

  private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
    return Files.writeString(
        path,
        "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
        StandardCharsets.US_ASCII);
  }

  public record Identity(String alias, PrivateKey privateKey, X509Certificate certificate) {}
}
