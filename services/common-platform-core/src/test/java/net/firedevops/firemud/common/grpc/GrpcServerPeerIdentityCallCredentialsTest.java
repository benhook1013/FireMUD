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
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class GrpcServerPeerIdentityCallCredentialsTest {
  private static final String EXPECTED = "spiffe://firemud/ns/test/sa/game-design-service";

  @Test
  void appliesOnlyEmptyMetadataAfterExactServerIdentityIsVerified() throws Exception {
    CallCredentials.MetadataApplier applier = mock(CallCredentials.MetadataApplier.class);
    var credentials = new GrpcServerPeerIdentityCallCredentials(EXPECTED);

    credentials.applyRequestMetadata(requestInfo(session(EXPECTED)), Runnable::run, applier);

    var metadata = org.mockito.ArgumentCaptor.forClass(Metadata.class);
    verify(applier).apply(metadata.capture());
    assertThat(metadata.getValue().keys()).isEmpty();
    verify(applier, never()).fail(any());
  }

  @Test
  void missingAmbiguousWrongNamespaceOrWrongWorkloadIdentityFailsWithoutMetadata()
      throws Exception {
    var credentials = new GrpcServerPeerIdentityCallCredentials(EXPECTED);
    List<CallCredentials.RequestInfo> requests = new ArrayList<>();
    requests.add(requestInfo(null));
    requests.add(requestInfo(session()));
    requests.add(requestInfo(session(EXPECTED, "spiffe://firemud/ns/test/sa/account-service")));
    requests.add(requestInfo(session("spiffe://firemud/ns/other/sa/game-design-service")));
    requests.add(requestInfo(session("spiffe://firemud/ns/test/sa/account-service")));

    for (CallCredentials.RequestInfo request : requests) {
      CallCredentials.MetadataApplier applier = mock(CallCredentials.MetadataApplier.class);
      credentials.applyRequestMetadata(request, Runnable::run, applier);

      verify(applier, never()).apply(any());
      var status = org.mockito.ArgumentCaptor.forClass(Status.class);
      verify(applier).fail(status.capture());
      assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    CallCredentials.MetadataApplier missingRequestInfoApplier =
        mock(CallCredentials.MetadataApplier.class);
    credentials.applyRequestMetadata(null, Runnable::run, missingRequestInfoApplier);
    verify(missingRequestInfoApplier, never()).apply(any());
    var missingRequestInfoStatus = org.mockito.ArgumentCaptor.forClass(Status.class);
    verify(missingRequestInfoApplier).fail(missingRequestInfoStatus.capture());
    assertThat(missingRequestInfoStatus.getValue().getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  @Test
  void constructorRequiresOneExactCanonicalWorkloadUri() {
    assertThatThrownBy(() -> new GrpcServerPeerIdentityCallCredentials(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GrpcServerPeerIdentityCallCredentials("not-a-workload"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrpcServerPeerIdentityCallCredentials(
                    "spiffe://FIREMUD/ns/test/sa/game-design-service"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrpcServerPeerIdentityCallCredentials(
                    "spiffe://firemud/ns/test/sa/not-a-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CallCredentials.RequestInfo requestInfo(SSLSession session) {
    CallCredentials.RequestInfo requestInfo = mock(CallCredentials.RequestInfo.class);
    Attributes attributes =
        session == null
            ? Attributes.EMPTY
            : Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();
    when(requestInfo.getTransportAttrs()).thenReturn(attributes);
    return requestInfo;
  }

  private static SSLSession session(String... peerUris) throws Exception {
    SSLSession session = mock(SSLSession.class);
    X509Certificate certificate = mock(X509Certificate.class);
    List<List<?>> alternatives = new ArrayList<>();
    for (String uri : peerUris) {
      alternatives.add(List.of(6, uri));
    }
    when(certificate.getSubjectAlternativeNames()).thenReturn(alternatives);
    when(session.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
    return session;
  }
}
