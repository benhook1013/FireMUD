package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GrpcServerPeerIdentityCallCredentialsTest {
  private static final String EXPECTED_PEER_URI =
      "spiffe://firemud/ns/firemud/sa/game-design-service";

  @Test
  void appliesMetadataForTheExactWorkloadAndPresentedLeaf() throws Exception {
    RequestFixture fixture =
        requestFixture(EXPECTED_PEER_URI, "expected leaf DER".getBytes(StandardCharsets.US_ASCII));
    assertThat(GrpcPeerIdentity.fromSslSession(fixture.sslSession()))
        .get()
        .extracting(GrpcPeerIdentity::uri, GrpcPeerIdentity::namespace, GrpcPeerIdentity::service)
        .containsExactly(EXPECTED_PEER_URI, "firemud", "game-design-service");
    assertThat(GrpcPeerCertificateEvidence.fromSslSession(fixture.sslSession()))
        .get()
        .extracting(GrpcPeerCertificateEvidence::leafSha256)
        .isEqualTo(fixture.leafSha256());

    CallCredentials.MetadataApplier applier = mock(CallCredentials.MetadataApplier.class);
    new GrpcServerPeerIdentityCallCredentials(EXPECTED_PEER_URI, fixture.leafSha256())
        .applyRequestMetadata(fixture.requestInfo(), Runnable::run, applier);

    verify(applier).apply(any(Metadata.class));
    verify(applier, never()).fail(any(Status.class));
  }

  @Test
  void sameWorkloadWithDifferentPresentedLeafFailsUnauthenticatedWithoutApplyingMetadata()
      throws Exception {
    RequestFixture pinnedLeaf =
        requestFixture(EXPECTED_PEER_URI, "pinned leaf DER".getBytes(StandardCharsets.US_ASCII));
    RequestFixture presentedLeaf =
        requestFixture(EXPECTED_PEER_URI, "different leaf DER".getBytes(StandardCharsets.US_ASCII));
    assertThat(GrpcPeerIdentity.fromSslSession(presentedLeaf.sslSession()))
        .get()
        .extracting(GrpcPeerIdentity::uri)
        .isEqualTo(EXPECTED_PEER_URI);
    assertThat(GrpcPeerCertificateEvidence.fromSslSession(presentedLeaf.sslSession()))
        .get()
        .extracting(GrpcPeerCertificateEvidence::leafSha256)
        .isNotEqualTo(pinnedLeaf.leafSha256());

    CallCredentials.MetadataApplier applier = mock(CallCredentials.MetadataApplier.class);
    new GrpcServerPeerIdentityCallCredentials(EXPECTED_PEER_URI, pinnedLeaf.leafSha256())
        .applyRequestMetadata(presentedLeaf.requestInfo(), Runnable::run, applier);

    ArgumentCaptor<Status> failure = ArgumentCaptor.forClass(Status.class);
    verify(applier).fail(failure.capture());
    assertThat(failure.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    verify(applier, never()).apply(any(Metadata.class));
  }

  @Test
  void rejectsMalformedLeafFingerprint() {
    assertThatThrownBy(
            () -> new GrpcServerPeerIdentityCallCredentials(EXPECTED_PEER_URI, "A".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RequestFixture requestFixture(String peerUri, byte[] leafDer) throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, peerUri)));
    when(certificate.getEncoded()).thenReturn(leafDer);

    SSLSession sslSession = mock(SSLSession.class);
    when(sslSession.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
    CallCredentials.RequestInfo requestInfo = mock(CallCredentials.RequestInfo.class);
    when(requestInfo.getTransportAttrs())
        .thenReturn(
            Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build());
    String leafSha256 =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leafDer));
    return new RequestFixture(requestInfo, sslSession, leafSha256);
  }

  private record RequestFixture(
      CallCredentials.RequestInfo requestInfo, SSLSession sslSession, String leafSha256) {}
}
