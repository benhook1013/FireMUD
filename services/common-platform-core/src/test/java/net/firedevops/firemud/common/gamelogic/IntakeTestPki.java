package net.firedevops.firemud.common.gamelogic;

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
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;

/** Shared loopback-mTLS PKI fixture for the gameplay-rule intake transport tests. */
record IntakeTestPki(
    X509Certificate caCertificate,
    Identity server,
    Identity wrongServer,
    Identity client,
    Path caKeyStore,
    Path caCertificateFile,
    String filePrefix) {
  private static final String STORE_PASSWORD = "test-only-store-password";

  record Identity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  record IdentitySpec(String alias, String workloadUri, boolean server) {
    static IdentitySpec server(String alias, String workloadUri) {
      return new IdentitySpec(alias, workloadUri, true);
    }

    static IdentitySpec client(String alias, String workloadUri) {
      return new IdentitySpec(alias, workloadUri, false);
    }
  }

  static IntakeTestPki create(
      Path directory,
      String filePrefix,
      String caCommonName,
      IdentitySpec server,
      IdentitySpec wrongServer,
      IdentitySpec client)
      throws Exception {
    Path caStore = directory.resolve(filePrefix + "-test-ca.p12");
    runKeytool(
        directory,
        "-genkeypair",
        "-alias",
        "test-ca",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-dname",
        "CN=" + caCommonName,
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
    Path caFile = directory.resolve(filePrefix + "-test-ca.crt");
    runKeytool(
        directory,
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
    var caCertificate = readCertificate(caFile);
    return new IntakeTestPki(
        caCertificate,
        issueIdentity(directory, caStore, caFile, server),
        issueIdentity(directory, caStore, caFile, wrongServer),
        issueIdentity(directory, caStore, caFile, client),
        caStore,
        caFile,
        filePrefix);
  }

  CommonGrpcClientProperties clientProperties(Path directory, Identity identity) throws Exception {
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
            directory.resolve(filePrefix + "-client-ca.crt"),
            "CERTIFICATE",
            caCertificate.getEncoded());
    var properties = new CommonGrpcClientProperties();
    properties.setCertChain(certificate.toString());
    properties.setPrivateKey(privateKey.toString());
    properties.setCaCert(caFile.toString());
    return properties;
  }

  static Identity issueIdentity(
      Path directory, Path caStore, Path caFile, String alias, String workloadUri, boolean server)
      throws Exception {
    return issueIdentity(directory, caStore, caFile, new IdentitySpec(alias, workloadUri, server));
  }

  private static Identity issueIdentity(
      Path directory, Path caStore, Path caFile, IdentitySpec spec) throws Exception {
    String alias = spec.alias();
    Path store = directory.resolve(alias + ".p12");
    Path request = directory.resolve(alias + ".csr");
    Path certificate = directory.resolve(alias + ".crt");
    String san = "URI:" + spec.workloadUri() + ",DNS:localhost,IP:127.0.0.1";
    String eku = spec.server() ? "serverAuth" : "clientAuth";
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
        "EKU=" + eku,
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
        directory,
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
        directory,
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
        "EKU=" + eku,
        "-ext",
        "SAN=" + san);
    runKeytool(
        directory,
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
        directory,
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
    return new Identity(
        alias,
        (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray()),
        (X509Certificate) keyStore.getCertificate(alias));
  }

  private static void runKeytool(Path tempDirectory, String... arguments) throws Exception {
    Path keytool =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")
                ? "keytool.exe"
                : "keytool");
    var command = new ArrayList<String>();
    command.add(keytool.toString());
    command.addAll(List.of(arguments));
    Path outputFile = Files.createTempFile(tempDirectory, "keytool-", ".log");
    Process process = null;
    boolean cleanupWaitAttempted = false;
    try {
      process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(outputFile.toFile())
              .start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        cleanupWaitAttempted = true;
        boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
        throw new IllegalStateException(
            "keytool timed out after 30 seconds; terminated after forced stop="
                + terminated
                + ": "
                + readKeytoolOutput(outputFile));
      }
      String output = readKeytoolOutput(outputFile);
      if (process.exitValue() != 0) {
        throw new IllegalStateException("keytool failed: " + output);
      }
    } catch (InterruptedException interrupted) {
      if (process != null) {
        process.destroyForcibly();
        cleanupWaitAttempted = true;
        try {
          process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException cleanupInterrupted) {
          interrupted.addSuppressed(cleanupInterrupted);
        }
      }
      Thread.currentThread().interrupt();
      throw interrupted;
    } finally {
      if (process != null && process.isAlive() && !cleanupWaitAttempted) {
        process.destroyForcibly();
        try {
          process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      Files.deleteIfExists(outputFile);
    }
  }

  private static String readKeytoolOutput(Path outputFile) throws IOException {
    int maxOutputBytes = 64 * 1024;
    byte[] output;
    boolean truncated;
    try (var stream = Files.newInputStream(outputFile)) {
      output = stream.readNBytes(maxOutputBytes);
      truncated = stream.read() != -1;
    }
    String text = new String(output, StandardCharsets.UTF_8);
    return truncated ? text + "\n[keytool output truncated]" : text;
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
