package net.firedevops.firemud.common.security.sourceintake;

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
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;

/** Test-only PKI shared by the selected-source command loopback mTLS proofs. */
final class SelectedSourceIntakeCommandTestPki {
  private static final String STORE_PASSWORD = "synthetic-test-store-password";

  private SelectedSourceIntakeCommandTestPki() {}

  static Pki create(
      Path directory,
      String caCommonName,
      String serverUri,
      String wrongServerUri,
      String clientUri)
      throws Exception {
    Path caStore = directory.resolve("entity-command-test-ca.p12");
    runKeytool(
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
    Path caFile = directory.resolve("entity-command-test-ca.crt");
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
    var caCertificate = readCertificate(caFile);
    return new Pki(
        caCertificate,
        issueIdentity(directory, caStore, caFile, "entity-server", serverUri, true),
        issueIdentity(directory, caStore, caFile, "wrong-entity-server", wrongServerUri, true),
        issueIdentity(directory, caStore, caFile, "game-design-client", clientUri, false));
  }

  static CommonGrpcClientProperties placeholderTls(Path directory) throws IOException {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "synthetic cert").toString());
    tls.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "synthetic key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "synthetic CA").toString());
    return tls;
  }

  record Identity(PrivateKey privateKey, X509Certificate certificate) {}

  record Pki(
      X509Certificate caCertificate,
      Identity server,
      Identity wrongServer,
      Identity gameDesignClient) {
    CommonGrpcClientProperties clientProperties(Path directory, Identity identity)
        throws Exception {
      Path certificate =
          writePem(
              directory.resolve("game-design-client.crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve("game-design-client.key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("entity-command-client-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
      properties.setCaCert(caFile.toString());
      return properties;
    }
  }

  private static Identity issueIdentity(
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
    return new Identity(
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
