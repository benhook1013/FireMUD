package net.firedevops.firemud.springcloudgateway.filter;

import io.jsonwebtoken.JwtException;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionClaims;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Global filter ensuring route-management/admin routes include a valid privileged JWT.
 *
 * <p>The gateway validates the token locally because route management is a gateway-owned
 * platform-level control surface.
 */
@Component
public class JwtAuthFilter implements WebFilter, Ordered {
  private final JwtUtil jwtUtil;

  public JwtAuthFilter(JwtUtil jwtUtil) {
    this.jwtUtil = jwtUtil;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (!requiresAdminAuth(path)) {
      return chain.filter(exchange);
    }

    String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      return exchange.getResponse().setComplete();
    }

    try {
      if (!hasPrivilegedGlobalRole(authHeader.substring(7))) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
      }
    } catch (JwtException | IllegalArgumentException ex) {
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      return exchange.getResponse().setComplete();
    }

    return chain.filter(exchange);
  }

  @Override
  public int getOrder() {
    return -1;
  }

  private boolean requiresAdminAuth(String path) {
    return path.startsWith("/routes") || path.startsWith("/api/admin");
  }

  private boolean hasPrivilegedGlobalRole(String token) {
    return SessionClaims.fromJwt(jwtUtil.parseToken(token)).hasPrivilegedRole();
  }
}
