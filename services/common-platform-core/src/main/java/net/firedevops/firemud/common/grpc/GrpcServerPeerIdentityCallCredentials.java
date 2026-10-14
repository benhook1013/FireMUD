package net.firedevops.firemud.common.grpc;

import io.grpc.CallCredentials;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.concurrent.Executor;

/** Authenticates the exact server workload before gRPC releases request headers or body. */
public final class GrpcServerPeerIdentityCallCredentials extends CallCredentials {
  private final String expectedPeerUri;
  private final String expectedLeafSha256;

  public GrpcServerPeerIdentityCallCredentials(String expectedPeerUri) {
    this(expectedPeerUri, null);
  }

  /** Pins an actual run-owned leaf in addition to the authenticated workload identity. */
  public GrpcServerPeerIdentityCallCredentials(String expectedPeerUri, String expectedLeafSha256) {
    if (expectedLeafSha256 != null && !expectedLeafSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Exact lowerhex server leaf fingerprint required");
    }
    this.expectedLeafSha256 = expectedLeafSha256;
    this.expectedPeerUri =
        GrpcPeerIdentity.parseUri(expectedPeerUri)
            .map(GrpcPeerIdentity::uri)
            .filter(uri -> uri.equals(expectedPeerUri))
            .orElseThrow(
                () -> new IllegalArgumentException("Exact server workload URI is required"));
  }

  @Override
  public void applyRequestMetadata(
      RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
    var sslSession =
        requestInfo == null || requestInfo.getTransportAttrs() == null
            ? null
            : requestInfo.getTransportAttrs().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    boolean exactPeer =
        GrpcPeerIdentity.fromSslSession(sslSession)
                .map(peer -> expectedPeerUri.equals(peer.uri()))
                .orElse(false)
            && (expectedLeafSha256 == null
                || GrpcPeerCertificateEvidence.fromSslSession(sslSession)
                    .filter(certificate -> expectedLeafSha256.equals(certificate.leafSha256()))
                    .isPresent());
    if (!exactPeer) {
      applier.fail(
          Status.UNAUTHENTICATED.withDescription(
              "Request requires the exact authenticated server workload identity"));
      return;
    }
    applier.apply(new Metadata());
  }
}
