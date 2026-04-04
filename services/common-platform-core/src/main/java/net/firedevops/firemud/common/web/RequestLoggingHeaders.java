package net.firedevops.firemud.common.web;

/** Shared HTTP headers used by request logging filters across servlet and reactive stacks. */
public final class RequestLoggingHeaders {
  public static final String CORRELATION_ID = "X-Correlation-Id";

  private RequestLoggingHeaders() {}
}
