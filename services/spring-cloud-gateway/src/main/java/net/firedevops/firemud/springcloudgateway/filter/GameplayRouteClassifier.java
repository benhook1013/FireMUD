package net.firedevops.firemud.springcloudgateway.filter;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Classifies paths using the same PathPattern semantics as the gameplay handler mapping. */
final class GameplayRouteClassifier {
  private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();
  private static final PathPattern EXACT_GAMEPLAY_ROUTE = PATH_PATTERN_PARSER.parse("/ws/game");
  private static final PathPattern WILDCARD_GAMEPLAY_ROUTE =
      PATH_PATTERN_PARSER.parse("/ws/game/**");
  private static final PathPattern SESSION_API_ROUTE =
      PATH_PATTERN_PARSER.parse("/api/session/**");

  private GameplayRouteClassifier() {}

  static Classification classify(PathContainer path) {
    boolean gameplayRoute =
        EXACT_GAMEPLAY_ROUTE.matches(path) || WILDCARD_GAMEPLAY_ROUTE.matches(path);
    boolean matrixParameter =
        gameplayRoute
            && path.elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(PathContainer.PathSegment.class::cast)
                .anyMatch(segment -> segment.value().indexOf(';') >= 0);
    return new Classification(gameplayRoute, matrixParameter);
  }

  static boolean sessionApiRoute(PathContainer path) {
    return SESSION_API_ROUTE.matches(path);
  }

  record Classification(boolean gameplayRoute, boolean matrixParameter) {}
}
