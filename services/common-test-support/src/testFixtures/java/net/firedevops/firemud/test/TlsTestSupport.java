package net.firedevops.firemud.test;

import java.util.Locale;
import javax.net.ssl.SSLException;

/** Shared TLS assertions for tests that exercise certificate-handshake failures. */
public final class TlsTestSupport {
  private TlsTestSupport() {}

  public static boolean isTlsHandshakeRejection(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SSLException) {
        return true;
      }
      String message = cause.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("certificate_required")
            || normalized.contains("bad_certificate")
            || normalized.contains("empty client certificate chain")
            || normalized.contains("connection prematurely closed before opening handshake")) {
          return true;
        }
      }
    }
    return false;
  }
}
