package net.firedevops.firemud.common.security.sourceintake;

import java.io.IOException;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;

/** Caller-specific identities for selected-source command loopback mTLS proofs. */
final class SelectedSourceIntakeCommandTestPki {
  private SelectedSourceIntakeCommandTestPki() {}

  static Pki create(
      Path directory,
      String caCommonName,
      String serverUri,
      String wrongServerUri,
      String clientUri)
      throws Exception {
    var shared =
        LoopbackMtlsTestPki.create(
            directory,
            caCommonName,
            new LoopbackMtlsTestPki.IdentitySpec("entity-server", serverUri, true),
            new LoopbackMtlsTestPki.IdentitySpec("wrong-entity-server", wrongServerUri, true),
            new LoopbackMtlsTestPki.IdentitySpec("game-design-client", clientUri, false));
    return new Pki(
        shared.caCertificate(),
        adapt(shared.identity("entity-server")),
        adapt(shared.identity("wrong-entity-server")),
        adapt(shared.identity("game-design-client")),
        shared);
  }

  static CommonGrpcClientProperties placeholderTls(Path directory) throws IOException {
    return LoopbackMtlsTestPki.placeholderTls(directory);
  }

  private static Identity adapt(LoopbackMtlsTestPki.Identity identity) {
    return new Identity(identity.privateKey(), identity.certificate());
  }

  record Identity(PrivateKey privateKey, X509Certificate certificate) {}

  record Pki(
      X509Certificate caCertificate,
      Identity server,
      Identity wrongServer,
      Identity gameDesignClient,
      LoopbackMtlsTestPki.Pki shared) {
    CommonGrpcClientProperties clientProperties(Path directory, Identity identity)
        throws IOException, CertificateEncodingException {
      var sharedIdentity =
          new LoopbackMtlsTestPki.Identity(identity.privateKey(), identity.certificate());
      return shared.clientProperties(directory, sharedIdentity);
    }
  }
}
