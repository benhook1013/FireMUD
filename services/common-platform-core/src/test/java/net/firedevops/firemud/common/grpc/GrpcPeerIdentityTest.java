package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class GrpcPeerIdentityTest {
  private static final String GAME_DESIGN_URI =
      "spiffe://firemud/ns/firemud/sa/game-design-service";

  @Test
  void extractsOneAllowedUriSanFromAuthenticatedLeaf() throws Exception {
    assertThat(GrpcPeerIdentity.fromSslSession(sessionWithUriSans(GAME_DESIGN_URI)))
        .get()
        .extracting(GrpcPeerIdentity::uri, GrpcPeerIdentity::namespace, GrpcPeerIdentity::service)
        .containsExactly(GAME_DESIGN_URI, "firemud", "game-design-service");
  }

  @Test
  void normalizesCanonicalEquivalentUriSanBeforeValidatingWorkloadIdentity() {
    assertThat(
            GrpcPeerIdentity.parseUri(
                "spiffe://FIREMUD/ns/%66iremud/sa/game-%64esign-service"))
        .get()
        .extracting(GrpcPeerIdentity::uri, GrpcPeerIdentity::namespace, GrpcPeerIdentity::service)
        .containsExactly(GAME_DESIGN_URI, "firemud", "game-design-service");
  }

  @Test
  void rejectsReservedEscapesAndDotSegmentsBeforeWorkloadValidation() {
    assertThat(GrpcPeerIdentity.parseUri("spiffe://firemud/ns/fire%2Fmud/sa/game-design-service"))
        .isEmpty();
    assertThat(GrpcPeerIdentity.parseUri("spiffe://firemud/ns/./sa/game-design-service"))
        .isEmpty();
    assertThat(GrpcPeerIdentity.parseUri("spiffe://firemud/ns/%2e%2e/sa/game-design-service"))
        .isEmpty();
  }

  @Test
  void canonicalUriParserNormalizesGenericAuthorityAndBoundaryPorts() {
    assertThat(CanonicalUri.parse("SPIFFE://FIREMUD/ns/default/sa/service").orElseThrow().value())
        .isEqualTo("spiffe://firemud/ns/default/sa/service");
    assertThat(CanonicalUri.parse("https://[2001:DB8::1]:0001/path")).isEmpty();
    assertThat(CanonicalUri.parse("https://[2001:DB8::1]:1/path").orElseThrow().value())
        .isEqualTo("https://[2001:db8::1]:1/path");
    assertThat(CanonicalUri.parse("https://[2001:DB8::1]:65535/path").orElseThrow().value())
        .isEqualTo("https://[2001:db8::1]:65535/path");
    assertThat(CanonicalUri.parse("https://firemud").orElseThrow().value())
        .isEqualTo("https://firemud/");
  }

  @Test
  void canonicalUriParserNormalizesOnlyUnreservedPathEscapes() {
    assertThat(
            CanonicalUri.parse("https://FIREMUD/ns/%64efault/sa/game~service")
                .orElseThrow()
                .value())
        .isEqualTo("https://firemud/ns/default/sa/game~service");
    assertThat(CanonicalUri.parse("https://firemud/ns/%2F/sa/service")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud/ns/%3F/sa/service")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud/ns/%C3%A9/sa/service")).isEmpty();
  }

  @Test
  void canonicalUriParserRejectsAmbiguousOrUnsafeForms() {
    assertThat(CanonicalUri.parse("https://user@firemud/path")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud/path?query")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud/path#fragment")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud/./path")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud/%2e%2e/path")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud/path/%")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud:0/path")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud:65536/path")).isEmpty();
    assertThat(CanonicalUri.parse("https://firemud:/path")).isEmpty();
    assertThat(CanonicalUri.parse("https://[fe80::1%25eth0]/path")).isEmpty();
  }

  @Test
  void missingSslSessionLeavesPeerIdentityAbsent() throws Exception {
    assertThat(interceptAndObserve(null)).isNull();
  }

  @Test
  void wrongPeerServiceIsRejected() throws Exception {
    assertThat(
            GrpcPeerIdentity.fromSslSession(
                sessionWithUriSans("spiffe://firemud/ns/firemud/sa/world-management-service")))
        .get()
        .extracting(GrpcPeerIdentity::service)
        .isEqualTo("world-management-service");
    assertThat(interceptAndObserve(sessionWithUriSans("spiffe://firemud/ns/firemud/sa/unknown")))
        .isNull();
  }

  @Test
  void sharedLegacyCertificateWithoutUriSanIsRejected() throws Exception {
    assertThat(interceptAndObserve(sessionWithDnsSan("grpc.internal.svc.cluster.local"))).isNull();
  }

  @Test
  void ambiguousUriSansAreRejected() throws Exception {
    assertThat(interceptAndObserve(sessionWithUriSans(GAME_DESIGN_URI, GAME_DESIGN_URI))).isNull();
  }

  @Test
  void dnsAndCommonNameAreNotIdentityFallbacks() throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(2, "game-design-service.firemud.svc")));
    assertThat(GrpcPeerIdentity.fromCertificate(certificate)).isEmpty();
  }

  private static GrpcPeerIdentity interceptAndObserve(SSLSession session) throws Exception {
    @SuppressWarnings("unchecked")
    ServerCall<Object, Object> call = mock(ServerCall.class);
    Attributes.Builder attributes = Attributes.newBuilder();
    if (session != null) {
      attributes.set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session);
    }
    when(call.getAttributes()).thenReturn(attributes.build());

    AtomicReference<GrpcPeerIdentity> observed = new AtomicReference<>();
    ServerCallHandler<Object, Object> next =
        (ignoredCall, ignoredHeaders) -> {
          observed.set(GrpcPeerIdentity.current());
          return new ServerCall.Listener<>() {};
        };
    ServerInterceptor interceptor = new GrpcPeerIdentityInterceptor();
    interceptor.interceptCall(call, new Metadata(), next);
    return observed.get();
  }

  private static SSLSession sessionWithUriSans(String... uris) throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    List<List<?>> sans = new ArrayList<>();
    for (String uri : uris) {
      sans.add(List.of(6, uri));
    }
    when(certificate.getSubjectAlternativeNames()).thenReturn(sans);
    return sessionFor(certificate);
  }

  private static SSLSession sessionWithDnsSan(String dnsName) throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(2, dnsName)));
    return sessionFor(certificate);
  }

  private static SSLSession sessionFor(X509Certificate certificate) throws Exception {
    SSLSession session = mock(SSLSession.class);
    when(session.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
    return session;
  }
}
