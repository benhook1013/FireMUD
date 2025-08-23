package net.firedevops.firemud.filter;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Ensures the client IP is forwarded to downstream services. */
@Component
public class ClientIpHeaderFilter implements WebFilter, Ordered {
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String headerIp = exchange.getRequest().getHeaders().getFirst("X-Client-IP");
    if (headerIp == null) {
      var remote = exchange.getRequest().getRemoteAddress();
      if (remote != null) {
        var address = remote.getAddress();
        if (address != null) {
          headerIp = address.getHostAddress();
        }
      }
    }
    if (headerIp != null) {
      final String ip = headerIp;
      ServerWebExchange mutated =
          exchange.mutate().request(r -> r.headers(h -> h.set("X-Client-IP", ip))).build();
      return chain.filter(mutated);
    }
    return chain.filter(exchange);
  }

  @Override
  public int getOrder() {
    return -3;
  }
}
