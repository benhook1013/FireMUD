package net.firedevops.firemud.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
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
      Claims payload = claims.getPayload();
      List<?> rawGlobalRoles = payload.get("globalRoles", List.class);
      List<String> globalRoles =
          rawGlobalRoles == null
              ? List.of()
              : rawGlobalRoles.stream().map(String::valueOf).toList();
      Map<?, ?> rawScopedRoles = payload.get("scopedRoles", Map.class);
      Map<String, List<String>> scopedRoles = new HashMap<>();
      if (rawScopedRoles != null) {
        for (Map.Entry<?, ?> entry : rawScopedRoles.entrySet()) {
          if (entry.getValue() instanceof List<?> rolesList) {
            scopedRoles.put(
                String.valueOf(entry.getKey()), rolesList.stream().map(String::valueOf).toList());
          }
        }
      }

      boolean allowed = false;
      allowed = globalRoles.contains("platformAdmin") || globalRoles.contains("moderator");
      if (!allowed) {
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
      return true;
    } catch (JwtException ex) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return false;
    }
  }
}
