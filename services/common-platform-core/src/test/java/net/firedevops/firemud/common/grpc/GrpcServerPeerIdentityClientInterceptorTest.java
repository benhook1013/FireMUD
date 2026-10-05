package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Status;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class GrpcServerPeerIdentityClientInterceptorTest {
  private static final String EXPECTED = "spiffe://firemud/ns/firemud/sa/game-design-service";

  @Test
  void deliversOnlyTheExactAuthenticatedServerResponse() throws Exception {
    Fixture fixture = fixture(session(EXPECTED));
    fixture.call.listener.onHeaders(new Metadata());
    fixture.call.listener.onMessage("owner evidence");
    fixture.call.listener.onClose(Status.OK, new Metadata());
    assertThat(fixture.headers).hasSize(1);
    assertThat(fixture.messages).containsExactly("owner evidence");
    assertThat(fixture.statuses).containsExactly(Status.OK);
    assertThat(fixture.call.cancelled).isFalse();
  }

  @Test
  void missingAmbiguousDifferentWorkloadOrNamespaceCannotDeliverHeadersOrContent()
      throws Exception {
    var sessions = new ArrayList<SSLSession>();
    sessions.add(null);
    sessions.add(session());
    sessions.add(session(EXPECTED, "spiffe://firemud/ns/firemud/sa/account-service"));
    sessions.add(session("spiffe://firemud/ns/firemud/sa/account-service"));
    sessions.add(session("spiffe://firemud/ns/other/sa/game-design-service"));
    sessions.add(session("not-a-workload-identity"));
    for (SSLSession session : sessions) {
      Fixture fixture = fixture(session);
      fixture.call.listener.onHeaders(new Metadata());
      fixture.call.listener.onMessage("untrusted evidence");
      fixture.call.listener.onClose(Status.OK, new Metadata());
      assertThat(fixture.headers).isEmpty();
      assertThat(fixture.messages).isEmpty();
      assertThat(fixture.statuses)
          .singleElement()
          .extracting(Status::getCode)
          .isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThat(fixture.call.cancelled).isTrue();
    }
  }

  @Test
  void contentWithoutHeadersStillRequiresExactPeer() throws Exception {
    Fixture unverified = fixture(null);
    unverified.call.listener.onMessage("unexpected content");
    assertThat(unverified.messages).isEmpty();
    assertThat(unverified.statuses)
        .singleElement()
        .extracting(Status::getCode)
        .isEqualTo(Status.Code.UNAUTHENTICATED);

    Fixture verified = fixture(session(EXPECTED));
    verified.call.listener.onMessage("owner evidence");
    assertThat(verified.messages).containsExactly("owner evidence");
  }

  @Test
  void emptySuccessfulReplyCannotBypassPeerValidation() throws Exception {
    Fixture fixture = fixture(null);
    fixture.call.listener.onClose(Status.OK, new Metadata());
    assertThat(fixture.statuses)
        .singleElement()
        .extracting(Status::getCode)
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(fixture.call.cancelled).isTrue();
  }

  @Test
  void transportFailureRemainsUnavailableWithoutInventingAuthenticatedEvidence() throws Exception {
    Fixture fixture = fixture(null);
    fixture.call.listener.onClose(Status.UNAVAILABLE, new Metadata());
    assertThat(fixture.statuses).containsExactly(Status.UNAVAILABLE);
    assertThat(fixture.headers).isEmpty();
    assertThat(fixture.messages).isEmpty();
    assertThat(fixture.call.cancelled).isFalse();
  }

  @Test
  void unverifiedCertificateDeniesResponseWithoutThrowingIntoConsumer() throws Exception {
    SSLSession session = mock(SSLSession.class);
    when(session.getPeerCertificates())
        .thenThrow(new javax.net.ssl.SSLPeerUnverifiedException("no peer"));
    Fixture fixture = fixture(session);
    fixture.call.listener.onHeaders(new Metadata());
    fixture.call.listener.onClose(Status.OK, new Metadata());
    assertThat(fixture.statuses)
        .singleElement()
        .extracting(Status::getCode)
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(fixture.messages).isEmpty();
  }

  @Test
  void configuredIdentityMustBeOneExactCanonicalWorkloadUri() {
    assertThatThrownBy(() -> new GrpcServerPeerIdentityClientInterceptor(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GrpcServerPeerIdentityClientInterceptor("not-a-workload"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrpcServerPeerIdentityClientInterceptor(
                    "spiffe://FIREMUD/ns/firemud/sa/game-design-service"))
        .isInstanceOf(IllegalArgumentException.class);
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

  private static Fixture fixture(SSLSession session) {
    var fixture = new Fixture();
    fixture.call.attributes =
        session == null
            ? Attributes.EMPTY
            : Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();
    Channel channel = mock(Channel.class);
    when(channel.<Object, Object>newCall(null, CallOptions.DEFAULT)).thenReturn(fixture.call);
    ClientCall<Object, Object> intercepted =
        new GrpcServerPeerIdentityClientInterceptor(EXPECTED)
            .interceptCall(null, CallOptions.DEFAULT, channel);
    intercepted.start(
        new ClientCall.Listener<>() {
          @Override
          public void onHeaders(Metadata headers) {
            fixture.headers.add(headers);
          }

          @Override
          public void onMessage(Object message) {
            fixture.messages.add(message);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            fixture.statuses.add(status);
          }
        },
        new Metadata());
    return fixture;
  }

  private static final class Fixture {
    private final TestCall call = new TestCall();
    private final List<Metadata> headers = new ArrayList<>();
    private final List<Object> messages = new ArrayList<>();
    private final List<Status> statuses = new ArrayList<>();
  }

  private static final class TestCall extends ClientCall<Object, Object> {
    private Listener<Object> listener;
    private Attributes attributes;
    private boolean cancelled;

    @Override
    public void start(Listener<Object> listener, Metadata headers) {
      this.listener = listener;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Override
    public void request(int count) {}

    @Override
    public void cancel(String message, Throwable cause) {
      cancelled = true;
    }

    @Override
    public void halfClose() {}

    @Override
    public void sendMessage(Object message) {}
  }
}
