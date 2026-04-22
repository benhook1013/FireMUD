package net.firedevops.firemud.springcloudgateway.filter;

import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Blocks service-local internal subtrees from leaking through public /api/{service}/** families.
 */
@Component
public class PublicInternalRouteBlockFilter implements WebFilter, Ordered {
  private static final Set<String> PUBLIC_FAMILIES =
      Set.of("account", "admin", "design", "session", "social");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (!targetsBlockedInternalPath(path)) {
      return chain.filter(exchange);
    }
    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
    return exchange.getResponse().setComplete();
  }

  @Override
  public int getOrder() {
    return -2;
  }

  private boolean targetsBlockedInternalPath(String path) {
    if (!path.startsWith("/api/")) {
      return false;
    }
    String[] segments = path.split("/");
    return segments.length >= 4
        && PUBLIC_FAMILIES.contains(segments[2])
        && "internal".equals(segments[3]);
  }
}
