package net.firedevops.firemud.springcloudgateway.filter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Blocks service-local internal subtrees and unavailable Account routes from public API families.
 */
@Component
public class PublicInternalRouteBlockFilter implements WebFilter, Ordered {
  private static final Set<String> PUBLIC_FAMILIES =
      Set.of("account", "admin", "design", "session", "social");
  private static final Set<String> BLOCKED_SERVICE_LOCAL_ROOTS = Set.of("internal", "actuator");
  private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();
  private static final PathPattern UNAVAILABLE_EXTERNAL_ACCOUNT_ROUTE =
      PATH_PATTERN_PARSER.parse("/api/account/accounts/{accountId}/external");
  private static final PathPattern UNAVAILABLE_PUBLIC_JOIN_ROUTE =
      PATH_PATTERN_PARSER.parse("/api/account/auth/bootstrap/join");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    PathContainer pathWithinApplication = exchange.getRequest().getPath().pathWithinApplication();
    if (!targetsBlockedPath(exchange.getRequest().getMethod(), pathWithinApplication)) {
      return chain.filter(exchange);
    }
    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
    return exchange.getResponse().setComplete();
  }

  @Override
  public int getOrder() {
    return -2;
  }

  private boolean targetsBlockedPath(HttpMethod method, PathContainer pathWithinApplication) {
    return targetsBlockedInternalPath(pathWithinApplication)
        || targetsUnavailableExternalAccountPath(pathWithinApplication)
        || targetsUnavailablePublicJoinRoute(method, pathWithinApplication.value());
  }

  private boolean targetsUnavailablePublicJoinRoute(HttpMethod method, String path) {
    if (!HttpMethod.POST.equals(method)) {
      return false;
    }
    String canonicalPath = canonicalizePublicRoutePath(path);
    return UNAVAILABLE_PUBLIC_JOIN_ROUTE.matches(PathContainer.parsePath(canonicalPath));
  }

  private String canonicalizePublicRoutePath(String path) {
    String decodedPath = decodePath(path);
    StringBuilder canonicalPath = new StringBuilder(decodedPath.length());
    String[] rawSegments = decodedPath.replace('\\', '/').split("/", -1);
    for (String rawSegment : rawSegments) {
      String segment = rawSegment;
      int matrixParameterStart = segment.indexOf(';');
      if (matrixParameterStart >= 0) {
        segment = segment.substring(0, matrixParameterStart);
      }
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        int previousSeparator = canonicalPath.lastIndexOf("/");
        if (previousSeparator >= 0) {
          canonicalPath.setLength(previousSeparator);
        }
        continue;
      }
      canonicalPath.append('/').append(segment);
    }
    return canonicalPath.length() == 0 ? "/" : canonicalPath.toString();
  }

  private String decodePath(String path) {
    String decodedPath = path;
    for (int pass = 0; pass < 8; pass++) {
      try {
        String nextPath =
            URLDecoder.decode(decodedPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        if (nextPath.equals(decodedPath)) {
          return decodedPath;
        }
        decodedPath = nextPath;
      } catch (IllegalArgumentException exception) {
        return path;
      }
    }
    return decodedPath;
  }

  private boolean targetsBlockedInternalPath(PathContainer path) {
    List<String> segments =
        path.elements().stream()
            .filter(PathContainer.PathSegment.class::isInstance)
            .map(PathContainer.PathSegment.class::cast)
            .map(PathContainer.PathSegment::valueToMatch)
            .toList();
    return segments.size() >= 3
        && "api".equals(segments.get(0))
        && PUBLIC_FAMILIES.contains(segments.get(1))
        && BLOCKED_SERVICE_LOCAL_ROOTS.contains(segments.get(2));
  }

  private boolean targetsUnavailableExternalAccountPath(PathContainer path) {
    PathContainer canonicalPath = canonicalizePath(path);
    PathPattern.PathMatchInfo matchInfo =
        UNAVAILABLE_EXTERNAL_ACCOUNT_ROUTE.matchAndExtract(canonicalPath);
    if (matchInfo == null) {
      return false;
    }
    if (hasMatrixParameters(path)) {
      return true;
    }
    String accountId = matchInfo.getUriVariables().get("accountId");
    return accountId != null && !accountId.isEmpty();
  }

  private boolean hasMatrixParameters(PathContainer path) {
    return path.elements().stream()
        .filter(PathContainer.PathSegment.class::isInstance)
        .map(PathContainer.PathSegment.class::cast)
        .anyMatch(segment -> !segment.parameters().isEmpty());
  }

  private PathContainer canonicalizePath(PathContainer path) {
    StringBuilder canonicalPath = new StringBuilder(path.value().length());
    boolean previousElementWasSeparator = false;
    for (PathContainer.Element element : path.elements()) {
      if (element instanceof PathContainer.Separator) {
        if (!previousElementWasSeparator) {
          canonicalPath.append(element.value());
        }
        previousElementWasSeparator = true;
      } else {
        canonicalPath.append(element.value());
        previousElementWasSeparator = false;
      }
    }
    while (canonicalPath.length() > 1 && canonicalPath.charAt(canonicalPath.length() - 1) == '/') {
      canonicalPath.setLength(canonicalPath.length() - 1);
    }
    return PathContainer.parsePath(canonicalPath.toString());
  }
}
