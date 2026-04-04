package net.firedevops.firemud.gamedesign.security;

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
    try {
      Jws<Claims> claims = jwtUtil.parseToken(authHeader.substring(7));
      Claims payload = claims.getPayload();
      if (!hasAdminRole(payload)) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return false;
      }
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
      SessionContext.setContext(payload.get("accountId", String.class), globalRoles, scopedRoles);
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

  private boolean isPublicPath(String requestUri) {
    return "/ping".equals(requestUri) || "/ping/".equals(requestUri);
  }

  private boolean hasAdminRole(Claims payload) {
    List<?> rawGlobalRoles = payload.get("globalRoles", List.class);
    if (rawGlobalRoles != null) {
      for (Object role : rawGlobalRoles) {
        if (isAdminRole(String.valueOf(role))) {
          return true;
        }
      }
    }
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
    for (List<String> roles : scopedRoles.values()) {
      for (String role : roles) {
        if (isAdminRole(role)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isAdminRole(String role) {
    return "platformAdmin".equals(role) || "moderator".equals(role) || "admin".equals(role);
  }
}
