package net.firedevops.firemud.common.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/** Rejects response content unless its TLS session proves one exact server workload URI. */
public final class GrpcServerPeerIdentityClientInterceptor implements ClientInterceptor {
  private final String expectedPeerUri;
  private final String expectedLeafSha256;

  public GrpcServerPeerIdentityClientInterceptor(String expectedPeerUri) {
    this(expectedPeerUri, null);
  }

  /** Pins an actual run-owned leaf in addition to the authenticated workload identity. */
  public GrpcServerPeerIdentityClientInterceptor(
      String expectedPeerUri, String expectedLeafSha256) {
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
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
    ClientCall<ReqT, RespT> call = next.newCall(method, options);
    return new SimpleForwardingClientCall<>(call) {
      @Override
      public void start(Listener<RespT> listener, Metadata headers) {
        super.start(
            new SimpleForwardingClientCallListener<>(listener) {
              private boolean verified;
              private boolean closed;

              private boolean requireVerifiedPeer() {
                if (closed) {
                  return false;
                }
                if (verified) {
                  return true;
                }
                verified =
                    GrpcPeerIdentity.fromSslSession(
                                call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
                            .filter(peer -> expectedPeerUri.equals(peer.uri()))
                            .isPresent()
                        && (expectedLeafSha256 == null
                            || GrpcPeerCertificateEvidence.fromSslSession(
                                    call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
                                .filter(
                                    certificate ->
                                        expectedLeafSha256.equals(certificate.leafSha256()))
                                .isPresent());
                if (!verified) {
                  closed = true;
                  listener.onClose(
                      Status.UNAUTHENTICATED.withDescription(
                          "Response lacks the exact authenticated server workload identity"),
                      new Metadata());
                  call.cancel("Server workload identity mismatch", null);
                }
                return verified;
              }

              @Override
              public void onHeaders(Metadata responseHeaders) {
                if (requireVerifiedPeer()) {
                  super.onHeaders(responseHeaders);
                }
              }

              @Override
              public void onMessage(RespT message) {
                if (requireVerifiedPeer()) {
                  super.onMessage(message);
                }
              }

              @Override
              public void onClose(Status status, Metadata trailers) {
                if (closed || (status.isOk() && !requireVerifiedPeer())) {
                  return;
                }
                closed = true;
                super.onClose(status, trailers);
              }
            },
            headers);
      }
    };
  }
}
