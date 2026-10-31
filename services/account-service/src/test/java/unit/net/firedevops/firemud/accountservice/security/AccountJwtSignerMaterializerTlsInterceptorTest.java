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
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountJwtSignerMaterializerTlsInterceptorTest {
  private static final String URI = "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
  private static final byte[] MATCHING_SPKI =
      "materializer leaf key".getBytes(StandardCharsets.US_ASCII);

  @Test
  void noTlsOrHeaderOnlyIdentityIsDeniedBeforeTheServiceHandler() {
    Binding binding = binding("a".repeat(64), "revision-1", "firemud-prod");
    AccountJwtSignerMaterializerTlsInterceptor interceptor =
        new AccountJwtSignerMaterializerTlsInterceptor(() -> Optional.of(binding));
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    @SuppressWarnings("unchecked")
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    when(call.getAttributes()).thenReturn(Attributes.EMPTY);
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer header-only");

    interceptor.interceptCall(call, headers, next);

    ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
    verify(call).close(status.capture(), any(Metadata.class));
    assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    verify(next, never()).startCall(any(), any());
  }

  @Test
  void absentDisabledOrWithdrawnProtectedBindingDeniesAccess() {
    AccountJwtSignerMaterializerTrustBinding disabled =
        new AccountJwtSignerMaterializerTrustBinding(false, "/absent");
    AccountJwtSignerMaterializerTlsInterceptor noBindingInterceptor =
        new AccountJwtSignerMaterializerTlsInterceptor(disabled);
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    @SuppressWarnings("unchecked")
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

    noBindingInterceptor.interceptCall(call, new Metadata(), next);

    ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
    verify(call).close(status.capture(), any(Metadata.class));
    assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    verify(next, never()).startCall(any(), any());
  }

  @Test
  void exactUriAndOneConfiguredLeafSpkiPinAreRequired() throws Exception {
    String pin = sha256(MATCHING_SPKI);
    Binding binding = binding(pin, "revision-1", "firemud-prod");

    assertThat(
            AccountJwtSignerMaterializerTrustBinding.matchesPeer(
                new java.security.cert.Certificate[] {certificate(URI, MATCHING_SPKI)}, binding))
        .isTrue();
    assertThat(
            AccountJwtSignerMaterializerTrustBinding.matchesPeer(
                new java.security.cert.Certificate[] {
                  certificate("spiffe://firemud/ns/other/sa/jwt-signer-materializer", MATCHING_SPKI)
                },
                binding))
        .isFalse();
    assertThat(
            AccountJwtSignerMaterializerTrustBinding.matchesPeer(
                new java.security.cert.Certificate[] {
                  certificate(URI, "different materializer key".getBytes(StandardCharsets.US_ASCII))
                },
                binding))
        .isFalse();
    assertThat(
            AccountJwtSignerMaterializerTrustBinding.matchesPeer(
                new java.security.cert.Certificate[] {
                  certificate(List.of(URI, URI), MATCHING_SPKI)
                },
                binding))
        .isFalse();
  }

  @Test
  void sameUriCertificateFailsAfterItsConfiguredKeyPinIsWithdrawn() throws Exception {
    String oldPin = sha256(MATCHING_SPKI);
    Binding oldBinding = binding(oldPin, "revision-1", "firemud-prod");
    Binding rotatedBinding =
        binding(
            sha256("replacement key".getBytes(StandardCharsets.US_ASCII)),
            "revision-2",
            "firemud-prod");
    java.security.cert.Certificate[] peer = {certificate(URI, MATCHING_SPKI)};

    assertThat(AccountJwtSignerMaterializerTrustBinding.matchesPeer(peer, oldBinding)).isTrue();
    assertThat(AccountJwtSignerMaterializerTrustBinding.matchesPeer(peer, rotatedBinding))
        .isFalse();
  }

  @Test
  void matchingTlsPeerReachesOnlyTheLocallyProtectedServiceContext() throws Exception {
    Binding binding = binding(sha256(MATCHING_SPKI), "revision-1", "firemud-prod");
    AccountJwtSignerMaterializerTlsInterceptor interceptor =
        new AccountJwtSignerMaterializerTlsInterceptor(() -> Optional.of(binding));
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    @SuppressWarnings("unchecked")
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    SSLSession session = mock(SSLSession.class);
    java.security.cert.Certificate[] peerCertificates = {certificate(URI, MATCHING_SPKI)};
    when(session.getPeerCertificates()).thenReturn(peerCertificates);
    when(call.getAttributes())
        .thenReturn(Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());
    AtomicReference<Binding> bindingDuringCallback = new AtomicReference<>();
    ServerCall.Listener<String> listener =
        new ServerCall.Listener<>() {
          @Override
          public void onMessage(String message) {
            bindingDuringCallback.set(
                AccountJwtSignerMaterializerTlsInterceptor.authenticatedBinding());
          }
        };
    when(next.startCall(any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(AccountJwtSignerMaterializerTlsInterceptor.authenticatedBinding())
                  .isEqualTo(binding);
              return listener;
            });

    ServerCall.Listener<String> intercepted = interceptor.interceptCall(call, new Metadata(), next);
    assertThat(intercepted).isNotSameAs(listener);
    intercepted.onMessage("request");
    assertThat(bindingDuringCallback.get()).isEqualTo(binding);
    assertThat(AccountJwtSignerMaterializerTlsInterceptor.authenticatedBinding()).isNull();
    verify(next).startCall(any(), any());
    verify(call, never()).close(any(), any());
  }

  private static Binding binding(String pin, String revision, String namespace) {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String expectedUri = "spiffe://firemud/ns/" + namespace + "/sa/jwt-signer-materializer";
    List<String> pins = List.of(pin);
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision,
            "prod",
            "prod-cluster-1",
            namespace,
            clusterUid,
            namespaceUid,
            expectedUri,
            pins);
    return new Binding(
        "prod",
        "prod-cluster-1",
        namespace,
        clusterUid,
        namespaceUid,
        expectedUri,
        pins,
        revision,
        digest);
  }

  private static java.security.cert.Certificate certificate(String uri, byte[] publicKeyBytes)
      throws Exception {
    return certificate(List.of(uri), publicKeyBytes);
  }

  private static java.security.cert.Certificate certificate(
      List<String> uris, byte[] publicKeyBytes) throws Exception {
    java.security.cert.X509Certificate certificate = mock(java.security.cert.X509Certificate.class);
    java.security.PublicKey publicKey = mock(java.security.PublicKey.class);
    when(certificate.getPublicKey()).thenReturn(publicKey);
    when(publicKey.getEncoded()).thenReturn(publicKeyBytes);
    List<List<?>> alternativeNames = new ArrayList<>();
    for (String uri : uris) {
      alternativeNames.add(List.of(6, uri));
    }
    when(certificate.getSubjectAlternativeNames()).thenReturn(alternativeNames);
    return certificate;
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
