package net.firedevops.firemud.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Intercepts requests to validate JWTs and roles. */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {
  private final JwtUtil jwtUtil;

  public JwtAuthInterceptor(JwtUtil jwtUtil) {
    this.jwtUtil = jwtUtil;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return false;
    }
    String token = authHeader.substring(7);
    try {
      Jws<Claims> claims = jwtUtil.parseToken(token);
      String accountId = claims.getBody().get("accountId", String.class);
      List<String> globalRoles = claims.getBody().get("globalRoles", List.class);
      Map<String, List<String>> scopedRoles = claims.getBody().get("scopedRoles", Map.class);

      boolean allowed = false;
      if (globalRoles != null) {
        allowed = globalRoles.contains("platformAdmin") || globalRoles.contains("moderator");
      }
      if (!allowed && scopedRoles != null) {
        for (List<String> roles : scopedRoles.values()) {
          if (roles.contains("admin") || roles.contains("moderator")) {
            allowed = true;
            break;
          }
        }
      }

      if (!allowed) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return false;
      }
      SessionContext.setContext(accountId, globalRoles, scopedRoles);
      return true;
    } catch (JwtException ex) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return false;
    }
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
      throws Exception {
    SessionContext.clear();
  }
}
