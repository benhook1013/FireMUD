package net.firedevops.firemud.common.web;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared servlet request logging with runtime identity and correlation context. */
public class ServletRequestLoggingFilter extends OncePerRequestFilter {
  public static final String CORRELATION_HEADER = "X-Correlation-Id";

  private static final Logger logger = LoggingUtil.getLogger(ServletRequestLoggingFilter.class);

  private final RuntimeIdentity runtimeIdentity;

  public ServletRequestLoggingFilter(RuntimeIdentity runtimeIdentity) {
    this.runtimeIdentity = runtimeIdentity;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_HEADER));
    String traceId = currentTraceId();
    long start = System.currentTimeMillis();
    response.setHeader(CORRELATION_HEADER, correlationId);

    try (MDC.MDCCloseable service = MDC.putCloseable("service", runtimeIdentity.service());
        MDC.MDCCloseable serviceInstance =
            MDC.putCloseable("serviceInstanceId", runtimeIdentity.serviceInstanceId());
        MDC.MDCCloseable correlation = MDC.putCloseable("correlationId", correlationId);
        MDCCloseableAdapter trace = new MDCCloseableAdapter("traceId", traceId)) {
      logger.info(
          "HTTP request started method={} path={} service={} serviceInstanceId={} correlationId={} traceId={}",
          request.getMethod(),
          request.getRequestURI(),
          runtimeIdentity.service(),
          runtimeIdentity.serviceInstanceId(),
          correlationId,
          valueOrUnknown(traceId));
      try {
        filterChain.doFilter(request, response);
      } finally {
        long duration = System.currentTimeMillis() - start;
        logger.info(
            "HTTP request completed method={} path={} status={} durationMs={} service={} serviceInstanceId={} correlationId={} traceId={}",
            request.getMethod(),
            request.getRequestURI(),
            response.getStatus(),
            duration,
            runtimeIdentity.service(),
            runtimeIdentity.serviceInstanceId(),
            correlationId,
            valueOrUnknown(traceId));
      }
    }
  }

  private static String resolveCorrelationId(String incoming) {
    return incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
  }

  private static String currentTraceId() {
    var spanContext = Span.current().getSpanContext();
    return spanContext.isValid() ? spanContext.getTraceId() : null;
  }

  private static String valueOrUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static final class MDCCloseableAdapter implements AutoCloseable {
    private final MDC.MDCCloseable closeable;

    MDCCloseableAdapter(String key, String value) {
      this.closeable = value == null || value.isBlank() ? null : MDC.putCloseable(key, value);
    }

    @Override
    public void close() {
      if (closeable != null) {
        closeable.close();
      }
    }
  }
}
