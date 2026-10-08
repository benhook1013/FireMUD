package net.firedevops.firemud.common.grpc;

import io.grpc.CallCredentials;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.concurrent.Executor;

/** Authenticates the exact server workload before gRPC releases request headers or body. */
public final class GrpcServerPeerIdentityCallCredentials extends CallCredentials {
  private final String expectedPeerUri;

  public GrpcServerPeerIdentityCallCredentials(String expectedPeerUri) {
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
            .orElse(false);
    if (!exactPeer) {
      applier.fail(
          Status.UNAUTHENTICATED.withDescription(
              "Request requires the exact authenticated server workload identity"));
      return;
    }
    applier.apply(new Metadata());
  }
}
