package net.firedevops.firemud.common.web;

import io.opentelemetry.api.trace.Span;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import org.slf4j.Logger;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Shared reactive request logging with runtime identity and correlation context. */
public class ReactiveRequestLoggingFilter implements WebFilter, Ordered {
  private static final Logger logger = LoggingUtil.getLogger(ReactiveRequestLoggingFilter.class);

  private final RuntimeIdentity runtimeIdentity;

  public ReactiveRequestLoggingFilter(RuntimeIdentity runtimeIdentity) {
    this.runtimeIdentity = runtimeIdentity;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String correlationId =
        resolveCorrelationId(request.getHeaders().getFirst(RequestLoggingHeaders.CORRELATION_ID));
    String traceId = currentTraceId();
    long start = System.currentTimeMillis();
    exchange.getResponse().getHeaders().set(RequestLoggingHeaders.CORRELATION_ID, correlationId);

    logger.info(
        "HTTP request started method={} path={} service={} serviceInstanceId={} correlationId={} traceId={}",
        request.getMethod(),
        request.getPath().value(),
        runtimeIdentity.service(),
        runtimeIdentity.serviceInstanceId(),
        correlationId,
        valueOrUnknown(traceId));

    return chain
        .filter(exchange)
        .doFinally(
            signalType -> {
              long duration = System.currentTimeMillis() - start;
              logger.info(
                  "HTTP request completed method={} path={} status={} durationMs={} service={} serviceInstanceId={} correlationId={} traceId={}",
                  request.getMethod(),
                  request.getPath().value(),
                  exchange.getResponse().getStatusCode() != null
                      ? exchange.getResponse().getStatusCode().value()
                      : 200,
                  duration,
                  runtimeIdentity.service(),
                  runtimeIdentity.serviceInstanceId(),
                  correlationId,
                  valueOrUnknown(traceId));
            });
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
}
