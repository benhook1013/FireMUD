package net.firedevops.firemud.common.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

public class HttpJwtAuthInterceptor implements HandlerInterceptor {
  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  private final JwtUtil jwtUtil;
  private final HttpAuthProperties props;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected auth properties are treated as application configuration.")
  public HttpJwtAuthInterceptor(JwtUtil jwtUtil, HttpAuthProperties props) {
    this.jwtUtil = jwtUtil;
    this.props = props;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (isPublicRoute(request)) {
      return true;
    }

    String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return false;
    }
    try {
      SessionClaims claims = SessionClaims.fromJwt(jwtUtil.parseToken(authHeader.substring(7)));
      if (props.getRoleRequirement() == HttpAuthRoleRequirement.PRIVILEGED
          && !claims.hasPrivilegedRole()) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return false;
      }
      claims.applyToSession();
      return true;
    } catch (JwtException | IllegalArgumentException ex) {
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

  private boolean isPublicRoute(HttpServletRequest request) {
    List<HttpAuthProperties.HttpPublicRoute> publicRoutes = props.getPublicRoutes();
    if (publicRoutes.isEmpty()) {
      return false;
    }

    String method = request.getMethod();
    String path = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
      path = path.substring(contextPath.length());
    }
    if (!StringUtils.hasText(path)) {
      path = "/";
    }

    for (HttpAuthProperties.HttpPublicRoute route : publicRoutes) {
      if (route == null
          || !StringUtils.hasText(route.getMethod())
          || !StringUtils.hasText(route.getPathPattern())) {
        continue;
      }
      if (route.getMethod().trim().toUpperCase(Locale.ROOT).equals(method)
          && PATH_MATCHER.match(route.getPathPattern(), path)) {
        return true;
      }
    }
    return false;
  }
}
