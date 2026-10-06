package net.firedevops.firemud.accountservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.Binding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountJwtReadinessTlsInterceptorTest {
  private static final String URI =
      "spiffe://firemud/ns/firemud-prod/sa/account-jwt-readiness-harness";
  private static final byte[] MATCHING_SPKI =
      "readiness harness leaf key".getBytes(StandardCharsets.US_ASCII);

  @Test
  void absentTlsOrProtectedBindingIsDeniedBeforeTheHandler() {
    Binding binding = binding(sha256(MATCHING_SPKI), "revision-1");
    assertDenied(
        new AccountJwtReadinessTlsInterceptor(() -> Optional.of(binding)), Attributes.EMPTY);
    assertDenied(new AccountJwtReadinessTlsInterceptor(Optional::empty), Attributes.EMPTY);
  }

  @Test
  void exactHarnessUriAndConfiguredSpkiPinEstablishOnlyAnInternalCallerContext() throws Exception {
    Binding binding = binding(sha256(MATCHING_SPKI), "revision-1");
    AccountJwtReadinessTlsInterceptor interceptor =
        new AccountJwtReadinessTlsInterceptor(() -> Optional.of(binding));
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    @SuppressWarnings("unchecked")
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    SSLSession session = mock(SSLSession.class);
    java.security.cert.Certificate peer = cert(URI);
    when(session.getPeerCertificates())
        .thenReturn(new java.security.cert.Certificate[] {peer});
    when(call.getAttributes())
        .thenReturn(Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());
    AtomicReference<AccountJwtReadinessTlsInterceptor.AuthenticatedCaller> captured =
        new AtomicReference<>();
    ServerCall.Listener<String> listener =
        new ServerCall.Listener<>() {
          @Override
          public void onMessage(String message) {
            captured.set(AccountJwtReadinessTlsInterceptor.authenticatedCaller());
          }
        };
    when(next.startCall(any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(AccountJwtReadinessTlsInterceptor.authenticatedCaller()).isNotNull();
              return listener;
            });

    ServerCall.Listener<String> wrapped = interceptor.interceptCall(call, new Metadata(), next);
    wrapped.onMessage("request");

    assertThat(captured.get()).isNotNull();
    assertThat(captured.get().binding()).isEqualTo(binding);
    assertThat(captured.get().peer().uri()).isEqualTo(URI);
    assertThat(captured.get().peer().spkiSha256()).isEqualTo(sha256(MATCHING_SPKI));
    verify(call, never()).close(any(), any());
  }

  @Test
  void wrongUriOrSpkiCannotUseHeaderOrRequestIdentityAsFallback() throws Exception {
    Binding binding = binding(sha256(MATCHING_SPKI), "revision-1");
    assertDenied(
        new AccountJwtReadinessTlsInterceptor(() -> Optional.of(binding)),
        tlsAttributes(cert("spiffe://firemud/ns/other/sa/account-jwt-readiness-harness")));
    assertDenied(
        new AccountJwtReadinessTlsInterceptor(() -> Optional.of(binding)),
        tlsAttributes(cert(URI, "different readiness key".getBytes(StandardCharsets.US_ASCII))));
  }

  private static void assertDenied(
      AccountJwtReadinessTlsInterceptor interceptor, Attributes attributes) {
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    @SuppressWarnings("unchecked")
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    when(call.getAttributes()).thenReturn(attributes);

    interceptor.interceptCall(call, new Metadata(), next);

    ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
    verify(call).close(status.capture(), any(Metadata.class));
    assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    verify(next, never()).startCall(any(), any());
  }

  private static Attributes tlsAttributes(java.security.cert.Certificate peer) throws Exception {
    SSLSession session = mock(SSLSession.class);
    when(session.getPeerCertificates()).thenReturn(new java.security.cert.Certificate[] {peer});
    return Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();
  }

  private static Binding binding(String pin, String revision) {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    List<String> pins = List.of(pin);
    String digest =
        AccountJwtReadinessTrustBinding.computeBindingDigest(
            revision,
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            clusterUid,
            namespaceUid,
            URI,
            pins,
            "account-validator-instance-7",
            1_900_000_000L);
    return new Binding(
        "prod",
        "prod-cluster-1",
        "firemud-prod",
        clusterUid,
        namespaceUid,
        URI,
        pins,
        revision,
        "account-service",
        "account-validator-instance-7",
        1_900_000_000L,
        digest);
  }

  private static java.security.cert.Certificate cert(String uri) throws Exception {
    return cert(uri, MATCHING_SPKI);
  }

  private static java.security.cert.Certificate cert(String uri, byte[] spki) throws Exception {
    java.security.cert.X509Certificate certificate = mock(java.security.cert.X509Certificate.class);
    java.security.PublicKey publicKey = mock(java.security.PublicKey.class);
    when(certificate.getPublicKey()).thenReturn(publicKey);
    when(publicKey.getEncoded()).thenReturn(spki);
    List<List<?>> alternativeNames = new ArrayList<>();
    alternativeNames.add(List.of(6, uri));
    when(certificate.getSubjectAlternativeNames()).thenReturn(alternativeNames);
    return certificate;
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new AssertionError(ex);
    }
  }
}
