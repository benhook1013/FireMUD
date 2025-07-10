package net.firedevops.firemud.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import net.firedevops.firemud.common.security.JwtUtil;
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
    String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return false;
    }
    String token = authHeader.substring(7);
    try {
      Jws<Claims> claims = jwtUtil.parseToken(token);
      List<String> roles = claims.getBody().get("globalRoles", List.class);
      if (roles == null || (!roles.contains("platformAdmin") && !roles.contains("moderator"))) {
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
