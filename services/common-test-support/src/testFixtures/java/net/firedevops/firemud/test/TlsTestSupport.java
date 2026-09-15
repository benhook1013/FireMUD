package net.firedevops.firemud.test;

import java.util.Locale;

/** Shared TLS assertions for tests that exercise certificate-handshake failures. */
public final class TlsTestSupport {
  private TlsTestSupport() {}

  public static boolean isTlsHandshakeRejection(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("certificate_required")
            || normalized.contains("bad_certificate")
            || normalized.contains("empty client certificate chain")
            || normalized.contains("no required ssl certificate was sent")
            || normalized.contains("peer did not return a certificate")
            || normalized.contains("client certificate required")
            || normalized.contains("client certificate rejected")
            || normalized.contains("client certificate unknown")) {
          return true;
        }
      }
    }
    return false;
  }
}
