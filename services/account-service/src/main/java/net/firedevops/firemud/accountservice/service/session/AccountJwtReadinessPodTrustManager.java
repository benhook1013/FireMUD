package net.firedevops.firemud.accountservice.service.session;

import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Adds an exact per-Pod URI-SAN and leaf-SPKI pin to Account's normal TLS trust decision. */
final class AccountJwtReadinessPodTrustManager extends X509ExtendedTrustManager {
  private final X509TrustManager delegate;
  private final String expectedServiceUri;
  private final String expectedSpkiSha256;
  private final AtomicReference<AuthenticatedPeer> authenticatedPeer = new AtomicReference<>();

  AccountJwtReadinessPodTrustManager(
      X509TrustManager delegate, String expectedServiceUri, String expectedSpkiSha256) {
    this.delegate = Objects.requireNonNull(delegate);
    this.expectedServiceUri = Objects.requireNonNull(expectedServiceUri);
    if (expectedSpkiSha256 == null || !expectedSpkiSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Expected Pod leaf SPKI digest is invalid");
    }
    GrpcPeerIdentity.parseUri(expectedServiceUri)
        .filter(peer -> peer.uri().equals(expectedServiceUri))
        .orElseThrow(() -> new IllegalArgumentException("Expected Pod service URI is invalid"));
    this.expectedSpkiSha256 = expectedSpkiSha256;
  }

  AuthenticatedPeer authenticatedPeer() {
    AuthenticatedPeer peer = authenticatedPeer.get();
    if (peer == null) {
      throw new SecurityException("The pinned Pod TLS handshake did not complete");
    }
    return peer;
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    delegate.checkClientTrusted(chain, authType);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    delegate.checkServerTrusted(chain, authType);
    authenticateServerLeaf(chain);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    if (delegate instanceof X509ExtendedTrustManager extended) {
      extended.checkClientTrusted(chain, authType, socket);
    } else {
      delegate.checkClientTrusted(chain, authType);
    }
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    if (delegate instanceof X509ExtendedTrustManager extended) {
      extended.checkServerTrusted(chain, authType, socket);
    } else {
      delegate.checkServerTrusted(chain, authType);
    }
    authenticateServerLeaf(chain);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    if (delegate instanceof X509ExtendedTrustManager extended) {
      extended.checkClientTrusted(chain, authType, engine);
    } else {
      delegate.checkClientTrusted(chain, authType);
    }
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    if (delegate instanceof X509ExtendedTrustManager extended) {
      extended.checkServerTrusted(chain, authType, engine);
    } else {
      delegate.checkServerTrusted(chain, authType);
    }
    authenticateServerLeaf(chain);
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    X509Certificate[] issuers = delegate.getAcceptedIssuers();
    return issuers == null ? new X509Certificate[0] : issuers.clone();
  }

  private void authenticateServerLeaf(X509Certificate[] chain) throws CertificateException {
    if (chain == null || chain.length == 0 || chain[0] == null) {
      throw new CertificateException("Pod TLS peer did not present a leaf certificate");
    }
    X509Certificate leaf = chain[0];
    GrpcPeerIdentity identity =
        GrpcPeerIdentity.fromCertificate(leaf)
            .orElseThrow(() -> new CertificateException("Pod TLS peer URI SAN is invalid"));
    if (!expectedServiceUri.equals(identity.uri())) {
      throw new CertificateException("Pod TLS peer URI SAN differs from the exact target");
    }
    final String spkiSha256;
    try {
      spkiSha256 =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(leaf.getPublicKey().getEncoded()));
    } catch (NoSuchAlgorithmException exception) {
      throw new CertificateException("SHA-256 is unavailable for Pod TLS peer pinning", exception);
    }
    if (!expectedSpkiSha256.equals(spkiSha256)) {
      throw new CertificateException("Pod TLS peer SPKI differs from the exact target");
    }
    authenticatedPeer.set(new AuthenticatedPeer(identity.uri(), spkiSha256));
  }

  record AuthenticatedPeer(String serviceUri, String spkiSha256) {}
}
