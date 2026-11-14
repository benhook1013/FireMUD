package net.firedevops.firemud.springcloudgateway.filter;

import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.SourceContext;
import org.springframework.web.server.ServerWebExchange;

/** Process-private HTTP source proof produced by the Gateway header-trust filter. */
public final class VerifiedHttpClientOrigin {
  private static final String ATTRIBUTE = VerifiedHttpClientOrigin.class.getName();
  private final SourceContext sourceContext;

  private VerifiedHttpClientOrigin(SourceContext sourceContext) {
    this.sourceContext = sourceContext;
  }

  static void clear(ServerWebExchange exchange) {
    exchange.getAttributes().remove(ATTRIBUTE);
  }

  static void retain(ServerWebExchange exchange, String canonicalAddress) {
    try {
      exchange
          .getAttributes()
          .put(ATTRIBUTE, new VerifiedHttpClientOrigin(new SourceContext(canonicalAddress)));
    } catch (IllegalArgumentException invalidSource) {
      // Unresolved or noncanonical source is unavailable to credential receivers, never a fallback.
      clear(exchange);
    }
  }

  public static VerifiedHttpClientOrigin from(ServerWebExchange exchange) {
    Object value = exchange.getAttributes().get(ATTRIBUTE);
    return value instanceof VerifiedHttpClientOrigin origin ? origin : null;
  }

  public SourceContext sourceContext() {
    return sourceContext;
  }

  @Override
  public String toString() {
    return "VerifiedHttpClientOrigin[redacted]";
  }
}
