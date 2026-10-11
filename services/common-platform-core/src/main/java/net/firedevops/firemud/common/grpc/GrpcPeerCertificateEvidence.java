package net.firedevops.firemud.common.grpc;

import io.grpc.Context;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/** The SHA-256 evidence for the leaf presented by the serving gRPC TLS peer. */
public final class GrpcPeerCertificateEvidence {
  public static final Context.Key<GrpcPeerCertificateEvidence> CONTEXT_KEY =
      Context.key("firemud-grpc-peer-certificate-evidence");

  private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

  private final String leafSha256;

  private GrpcPeerCertificateEvidence(String leafSha256) {
    if (leafSha256 == null || !SHA256_PATTERN.matcher(leafSha256).matches()) {
      throw new IllegalArgumentException("Leaf SHA-256 fingerprint must be lowercase hexadecimal");
    }
    this.leafSha256 = leafSha256;
  }

  /** Returns the evidence currently attached to the serving gRPC context, if verified. */
  public static GrpcPeerCertificateEvidence current() {
    return CONTEXT_KEY.get();
  }

  /** Extracts the presented leaf's DER SHA-256 fingerprint from the authenticated TLS session. */
  public static Optional<GrpcPeerCertificateEvidence> fromSslSession(SSLSession sslSession) {
    if (sslSession == null) {
      return Optional.empty();
    }
    try {
      Certificate[] peerCertificates = sslSession.getPeerCertificates();
      if (peerCertificates == null
          || peerCertificates.length == 0
          || !(peerCertificates[0] instanceof X509Certificate leaf)) {
        return Optional.empty();
      }
      return fromCertificate(leaf);
    } catch (SSLPeerUnverifiedException | RuntimeException exception) {
      return Optional.empty();
    }
  }

  /** Extracts only leaf-instance evidence; this fingerprint is not a workload principal. */
  public static Optional<GrpcPeerCertificateEvidence> fromCertificate(X509Certificate leaf) {
    if (leaf == null) {
      return Optional.empty();
    }
    try {
      byte[] encoded = leaf.getEncoded();
      if (encoded == null || encoded.length == 0) {
        return Optional.empty();
      }
      return Optional.of(new GrpcPeerCertificateEvidence(sha256(encoded)));
    } catch (CertificateEncodingException | NoSuchAlgorithmException | RuntimeException exception) {
      return Optional.empty();
    }
  }

  public String leafSha256() {
    return leafSha256;
  }

  private static String sha256(byte[] certificateDer) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificateDer));
  }
}
