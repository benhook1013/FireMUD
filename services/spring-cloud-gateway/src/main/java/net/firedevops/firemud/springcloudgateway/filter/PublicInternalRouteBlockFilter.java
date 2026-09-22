package net.firedevops.firemud.springcloudgateway.filter;

import java.util.Set;
import org.springframework.core.Ordered;
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

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    PathContainer pathWithinApplication = exchange.getRequest().getPath().pathWithinApplication();
    if (!targetsBlockedPath(path, pathWithinApplication)) {
      return chain.filter(exchange);
    }
    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
    return exchange.getResponse().setComplete();
  }

  @Override
  public int getOrder() {
    return -2;
  }

  private boolean targetsBlockedPath(String path, PathContainer pathWithinApplication) {
    String[] segments = path.split("/", -1);
    return targetsBlockedInternalPath(path, segments)
        || targetsUnavailableExternalAccountPath(pathWithinApplication);
  }

  private boolean targetsBlockedInternalPath(String path, String[] segments) {
    return path.startsWith("/api/")
        && segments.length >= 4
        && PUBLIC_FAMILIES.contains(segments[2])
        && BLOCKED_SERVICE_LOCAL_ROOTS.contains(segments[3]);
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
