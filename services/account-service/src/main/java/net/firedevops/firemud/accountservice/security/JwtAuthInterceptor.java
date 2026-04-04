package net.firedevops.firemud.accountservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
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
    if (isPublicPath(request.getRequestURI())) {
      return true;
    }
    String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return false;
    }
    String token = authHeader.substring(7);
    try {
      Jws<Claims> claims = jwtUtil.parseToken(token);
      Claims payload = claims.getPayload();
      String accountId = payload.get("accountId", String.class);

      List<?> globalRolesRaw = payload.get("globalRoles", List.class);
      List<String> globalRoles =
          globalRolesRaw == null ? null : globalRolesRaw.stream().map(Object::toString).toList();

      Map<?, ?> scopedRolesRaw = payload.get("scopedRoles", Map.class);
      Map<String, List<String>> scopedRoles = null;
      if (scopedRolesRaw != null) {
        scopedRoles = new HashMap<>();
        for (Map.Entry<?, ?> entry : scopedRolesRaw.entrySet()) {
          Object value = entry.getValue();
          if (value instanceof List<?> list) {
            scopedRoles.put(
                String.valueOf(entry.getKey()), list.stream().map(Object::toString).toList());
          }
        }
      }

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

  private boolean isPublicPath(String requestUri) {
    return "/accounts".equals(requestUri)
        || "/accounts/".equals(requestUri)
        || "/auth/login".equals(requestUri)
        || "/auth/player-bootstrap".equals(requestUri)
        || "/auth/connect-token".equals(requestUri)
        || "/auth/request-password-reset".equals(requestUri)
        || "/auth/complete-password-reset".equals(requestUri)
        || "/auth/request-email-verification".equals(requestUri)
        || "/auth/verify-email".equals(requestUri)
        || "/auth/recover-username".equals(requestUri)
        || "/ping".equals(requestUri)
        || requestUri.startsWith("/.well-known/");
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
      throws Exception {
    SessionContext.clear();
  }
}
