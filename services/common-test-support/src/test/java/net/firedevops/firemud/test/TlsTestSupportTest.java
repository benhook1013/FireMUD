package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TlsTestSupportTest {
  @Test
  void ignoresSslExceptionsWithNullMessages() {
    assertThat(TlsTestSupport.isTlsHandshakeRejection(new SSLException((String) null))).isFalse();
    assertThat(TlsTestSupport.isTlsHandshakeRejection(new SSLException((Throwable) null)))
        .isFalse();
  }

  @Test
  void recognizesPrematureCloseExceptionDirectlyAndInCauseChain() {
    Throwable prematureCloseException = prematureCloseExceptionTestInstance();
    assertThat(TlsTestSupport.isTlsHandshakeRejection(prematureCloseException)).isTrue();
    assertThat(
            TlsTestSupport.isTlsHandshakeRejection(
                new IllegalStateException("client request failed", prematureCloseException)))
        .isTrue();
  }

  private static Throwable prematureCloseExceptionTestInstance() {
    try {
      return (Throwable)
          Class.forName("reactor.netty.http.client.PrematureCloseException")
              .getField("TEST_EXCEPTION")
              .get(null);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("Reactor Netty TEST_EXCEPTION is unavailable", exception);
    }
  }

  @ParameterizedTest
  @MethodSource("knownClientCertificateRejections")
  void recognizesExplicitClientCertificateRejections(String message) {
    assertThat(TlsTestSupport.isTlsHandshakeRejection(new SSLException(message))).isTrue();
  }

  @ParameterizedTest
  @MethodSource("unrelatedSslFailures")
  void ignoresUnrelatedSslFailures(String message) {
    assertThat(TlsTestSupport.isTlsHandshakeRejection(new SSLException(message))).isFalse();
  }

  @ParameterizedTest
  @MethodSource("knownClientCertificateRejections")
  void recognizesClientCertificateRejectionInCauseChain(String message) {
    assertThat(
            TlsTestSupport.isTlsHandshakeRejection(
                new IllegalStateException("server validation failed", new SSLException(message))))
        .isTrue();
  }

  private static Stream<Arguments> knownClientCertificateRejections() {
    return Stream.of(
        Arguments.of("Received fatal alert: certificate_required"),
        Arguments.of("Received fatal alert: bad_certificate"),
        Arguments.of("SSL alert: certificate required"),
        Arguments.of("SSL alert: bad certificate"),
        Arguments.of("Empty client certificate chain"),
        Arguments.of("No required SSL certificate was sent"),
        Arguments.of("Peer did not return a certificate"),
        Arguments.of("Received fatal alert: unknown_ca"),
        Arguments.of("Received fatal alert: certificate_unknown"),
        Arguments.of("Client certificate required by server"),
        Arguments.of("Client certificate rejected by server"),
        Arguments.of("Client certificate unknown to server"));
  }

  private static Stream<Arguments> unrelatedSslFailures() {
    return Stream.of(
        Arguments.of("protocol_version"),
        Arguments.of("handshake_failure"),
        Arguments.of("Received fatal alert: internal_error"),
        Arguments.of("server certificate validation failed"),
        Arguments.of("hostname verification failed"),
        Arguments.of("certificate issue while connecting to server"));
  }
}
