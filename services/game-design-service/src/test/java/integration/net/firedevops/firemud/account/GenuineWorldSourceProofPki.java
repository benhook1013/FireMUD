package net.firedevops.firemud.accountservice.service.session;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Ephemeral same-namespace test identities, never production signer or author authorization. */
final class GenuineWorldSourceProofPki {
  private final Path ca;
  private final Map<String, Identity> servers = new HashMap<>();
  private final Map<String, Identity> clients = new HashMap<>();

  GenuineWorldSourceProofPki(Path root) throws Exception {
    Files.createDirectories(root);
    var keys = keys();
    var now = Instant.now();
    var name = new X500Name("CN=Genuine GD World source test CA");
    var builder =
        new JcaX509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(3600)),
            name,
            keys.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign));
    var certificate =
        new JcaX509CertificateConverter()
            .getCertificate(
                builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    ca = pem(root.resolve("ca.pem"), certificate);
    for (var service :
        java.util.List.of("account-service", "game-design-service", "world-management-service")) {
      servers.put(service, issue(root, service, true, keys, certificate));
      clients.put(service, issue(root, service, false, keys, certificate));
    }
  }

  Path ca() {
    return ca;
  }

  Identity server(String service) {
    return java.util.Objects.requireNonNull(servers.get(service));
  }

  CommonGrpcClientProperties client(String service) {
    var identity = java.util.Objects.requireNonNull(clients.get(service));
    var properties = new CommonGrpcClientProperties();
    properties.setPlaintext(false);
    properties.setCertChain(identity.certificate().toString());
    properties.setPrivateKey(identity.key().toString());
    properties.setCaCert(ca.toString());
    return properties;
  }

  private static Identity issue(
      Path root, String service, boolean server, KeyPair caKeys, X509Certificate caCertificate)
      throws Exception {
    var keys = keys();
    var now = Instant.now();
    var builder =
        new JcaX509v3CertificateBuilder(
            caCertificate,
            new BigInteger(120, new SecureRandom()),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(3600)),
            new X500Name("CN=" + service),
            keys.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(
        Extension.keyUsage,
        false,
        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    builder.addExtension(
        Extension.extendedKeyUsage,
        false,
        new ExtendedKeyUsage(
            server ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
    builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        new GeneralNames(
            new GeneralName[] {
              new GeneralName(
                  GeneralName.uniformResourceIdentifier,
                  "spiffe://firemud/ns/firemud/sa/" + service),
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            }));
    var certificate =
        new JcaX509CertificateConverter()
            .getCertificate(
                builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate())));
    var prefix = service + (server ? "-server" : "-client");
    return new Identity(
        pem(root.resolve(prefix + ".crt"), certificate),
        pem(root.resolve(prefix + ".key"), keys.getPrivate()));
  }

  private static KeyPair keys() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static Path pem(Path path, Object value) throws Exception {
    try (var writer = new JcaPEMWriter(Files.newBufferedWriter(path))) {
      writer.writeObject(value);
    }
    return path;
  }

  record Identity(Path certificate, Path key) {}
}
