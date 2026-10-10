package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;

/** Ephemeral, test-only workload certificates for the physical World preparation adapter proof. */
final class WorldPreparationTestWorkloadPki {
  private static final String STORE_PASSWORD = "world-preparation-test-only";

  private final Path caCertificate;
  private final Path worldServerCertificate;
  private final Path worldServerPrivateKey;
  private final Path gameSessionCertificate;
  private final Path gameSessionPrivateKey;
  private final Path wrongWorkloadCertificate;
  private final Path wrongWorkloadPrivateKey;

  private WorldPreparationTestWorkloadPki(
      Path caCertificate,
      Path worldServerCertificate,
      Path worldServerPrivateKey,
      Path gameSessionCertificate,
      Path gameSessionPrivateKey,
      Path wrongWorkloadCertificate,
      Path wrongWorkloadPrivateKey) {
    this.caCertificate = caCertificate;
    this.worldServerCertificate = worldServerCertificate;
    this.worldServerPrivateKey = worldServerPrivateKey;
    this.gameSessionCertificate = gameSessionCertificate;
    this.gameSessionPrivateKey = gameSessionPrivateKey;
    this.wrongWorkloadCertificate = wrongWorkloadCertificate;
    this.wrongWorkloadPrivateKey = wrongWorkloadPrivateKey;
  }

  static WorldPreparationTestWorkloadPki create(Path directory) throws Exception {
    Files.createDirectories(directory);
    Path caStore = directory.resolve("test-ca.p12");
    Path caCertificate = directory.resolve("test-ca.crt");
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
        caCertificate.toString(),
        "-rfc");

    Identity server =
        issueIdentity(
            directory, caStore, caCertificate, "world-server", "world-management-service", true);
    Identity gameSession =
        issueIdentity(
            directory, caStore, caCertificate, "game-session", "game-session-service", false);
    Identity wrongWorkload =
        issueIdentity(
            directory, caStore, caCertificate, "wrong-workload", "game-design-service", false);
    return new WorldPreparationTestWorkloadPki(
        caCertificate,
        server.certificate(),
        server.privateKey(),
        gameSession.certificate(),
        gameSession.privateKey(),
        wrongWorkload.certificate(),
        wrongWorkload.privateKey());
  }

  Path caCertificate() {
    return caCertificate;
  }

  Path worldServerCertificate() {
    return worldServerCertificate;
  }

  Path worldServerPrivateKey() {
    return worldServerPrivateKey;
  }

  CommonGrpcClientProperties clientProperties(String workloadUri) throws Exception {
    Path cert;
    Path key;
    if ("spiffe://firemud/ns/firemud/sa/game-session-service".equals(workloadUri)) {
      cert = gameSessionCertificate;
      key = gameSessionPrivateKey;
    } else if ("spiffe://firemud/ns/firemud/sa/game-design-service".equals(workloadUri)) {
      cert = wrongWorkloadCertificate;
      key = wrongWorkloadPrivateKey;
    } else {
      throw new IllegalArgumentException("No test identity issued for " + workloadUri);
    }
    var properties = new CommonGrpcClientProperties();
    properties.setCertChain(cert.toString());
    properties.setPrivateKey(key.toString());
    properties.setCaCert(caCertificate.toString());
    return properties;
  }

  private static Identity issueIdentity(
      Path directory,
      Path caStore,
      Path caCertificate,
      String alias,
      String serviceName,
      boolean server)
      throws Exception {
    Path store = directory.resolve(alias + ".p12");
    Path request = directory.resolve(alias + ".csr");
    Path certificate = directory.resolve(alias + ".crt");
    Path privateKey = directory.resolve(alias + ".key");
    String workloadUri = "spiffe://firemud/ns/firemud/sa/" + serviceName;
    String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
    String extendedKeyUsage = server ? "serverAuth" : "clientAuth";
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
        "EKU=" + extendedKeyUsage,
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
        "EKU=" + extendedKeyUsage,
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
        caCertificate.toString(),
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
    var key = (java.security.PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray());
    var cert = keyStore.getCertificate(alias).getEncoded();
    writePem(certificate, "CERTIFICATE", cert);
    writePem(privateKey, "PRIVATE KEY", key.getEncoded());
    return new Identity(certificate, privateKey);
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
    Path outputFile = Files.createTempFile("firemud-world-preparation-keytool-", ".log");
    try {
      Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(outputFile.toFile())
              .start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        throw new IllegalStateException("keytool exceeded its 30-second test-fixture timeout");
      }
      String output = Files.readString(outputFile, StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new IllegalStateException("keytool failed: " + output);
      }
    } finally {
      Files.deleteIfExists(outputFile);
    }
  }

  private static Path writePem(Path path, String label, byte[] bytes) throws Exception {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
    return Files.writeString(
        path,
        "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
        StandardCharsets.US_ASCII);
  }

  private record Identity(Path certificate, Path privateKey) {}
}
