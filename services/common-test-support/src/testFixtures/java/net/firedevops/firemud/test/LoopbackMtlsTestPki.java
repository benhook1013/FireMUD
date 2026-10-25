package net.firedevops.firemud.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;

/** Test-only PKI for loopback mTLS proofs. */
public final class LoopbackMtlsTestPki {
  private static final String STORE_PASSWORD = "test-only-store-password";

  private LoopbackMtlsTestPki() {}

  public static Pki create(Path directory, String caCommonName, IdentitySpec... identities)
      throws Exception {
    Path caStore = directory.resolve("test-ca.p12");
    keytool(
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
    Path caFile = directory.resolve("test-ca.crt");
    keytool(
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
    var issued = new java.util.LinkedHashMap<String, Identity>();
    for (IdentitySpec identity : identities) {
      if (issued.putIfAbsent(identity.alias(), issueIdentity(directory, caStore, caFile, identity))
          != null) {
        throw new IllegalArgumentException("duplicate test certificate alias: " + identity.alias());
      }
    }
    return new Pki(caCertificate, Map.copyOf(issued));
  }

  public static CommonGrpcClientProperties placeholderTls(Path directory) throws IOException {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "synthetic cert").toString());
    tls.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "synthetic key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "synthetic CA").toString());
    return tls;
  }

  public record IdentitySpec(String alias, String workloadUri, boolean server) {}

  public record Identity(PrivateKey privateKey, X509Certificate certificate) {}

  public record Pki(X509Certificate caCertificate, Map<String, Identity> identities) {
    public Pki {
      identities = Map.copyOf(identities);
    }

    public Identity identity(String alias) {
      Identity identity = identities.get(alias);
      if (identity == null) {
        throw new IllegalArgumentException("unknown test certificate alias: " + alias);
      }
      return identity;
    }

    /** Creates fresh server key managers using the existing issued identity and CA chain. */
    public KeyManagerFactory keyManagerFactory(Identity identity)
        throws GeneralSecurityException, IOException {
      char[] password = STORE_PASSWORD.toCharArray();
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, password);
      store.setKeyEntry(
          "identity",
          identity.privateKey(),
          password,
          new X509Certificate[] {identity.certificate(), caCertificate});
      KeyManagerFactory factory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      factory.init(store, password);
      return factory;
    }

    /** Creates fresh trust managers containing only this fixture's CA. */
    public TrustManagerFactory trustManagerFactory() throws GeneralSecurityException, IOException {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, null);
      store.setCertificateEntry("test-ca", caCertificate);
      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      return factory;
    }

    public CommonGrpcClientProperties clientProperties(Path directory, Identity identity)
        throws IOException, CertificateEncodingException {
      Path clientDirectory = Files.createTempDirectory(directory, "loopback-mtls-client-");
      Path certificate =
          writePem(
              clientDirectory.resolve("client.crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              clientDirectory.resolve("client.key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      Path caFile =
          writePem(
              clientDirectory.resolve("client-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
      properties.setCaCert(caFile.toString());
      return properties;
    }
  }

  private static Identity issueIdentity(
      Path directory, Path caStore, Path caFile, IdentitySpec identity) throws Exception {
    String alias = identity.alias();
    String san = "URI:" + identity.workloadUri() + ",DNS:localhost,IP:127.0.0.1";
    Path store = directory.resolve(alias + ".p12");
    Path request = directory.resolve(alias + ".csr");
    Path certificate = directory.resolve(alias + ".crt");
    keytool(
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
        "EKU=" + (identity.server() ? "serverAuth" : "clientAuth"),
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
    keytool(
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
    keytool(
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
        "EKU=" + (identity.server() ? "serverAuth" : "clientAuth"),
        "-ext",
        "SAN=" + san);
    keytool(
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
    keytool(
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

  private static void keytool(String... arguments) throws Exception {
    Path executable =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("windows")
                ? "keytool.exe"
                : "keytool");
    var command = new ArrayList<String>();
    command.add(executable.toString());
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      throw new IllegalStateException("keytool timed out");
    }
    String output;
    try (var stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (process.exitValue() != 0) {
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
