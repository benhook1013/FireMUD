package net.firedevops.firemud.springcloudgateway.websocket;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;

/** Applies the bounded client-visible close and handshake observability contract. */
@Component
public final class GameplayWebSocketObservability {
  public static final String GAMEPLAY_ROUTE = "/ws/game/**";
  private static final String LOGOUT = "logout";
  private static final String IDLE_TIMEOUT = "idle_timeout";
  private static final String POLICY_VIOLATION = "policy_violation";
  private static final String INTERNAL_ERROR = "internal_error";
  private static final String BACKEND_UNAVAILABLE = "backend_unavailable";
  private static final String NO_SUBREASON = "none";
  private static final String EDGE_BACKPRESSURE = "edge_backpressure";
  private static final String SUBREASON_PREFIX = ";subreason=";
  private static final Set<String> SUPPORTED_SUBREASONS =
      Set.of("user_logout", "takeover", "gateway_restart", "admin_termination", EDGE_BACKPRESSURE);

  @Nullable private final MeterRegistry meterRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected registry is only used to record bounded gateway metrics.")
  public GameplayWebSocketObservability(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  private GameplayWebSocketObservability() {
    this.meterRegistry = null;
  }

  public static GameplayWebSocketObservability disabled() {
    return new GameplayWebSocketObservability();
  }

  public void recordHandshakeRejection(int status, String errorClass) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry
        .counter(
            "gateway.websocket.handshake.rejected",
            "route",
            GAMEPLAY_ROUTE,
            "status",
            Integer.toString(status),
            "error_class",
            errorClass)
        .increment();
  }

  public void recordClose(CloseClassification close) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry
        .counter(
            "gateway.websocket.closes", "reason", close.reason(), "subreason", close.subreason())
        .increment();
    if (close.slowClient()) {
      meterRegistry.counter("gateway.websocket.slow_client_closes").increment();
    }
  }

  public CloseClassification classify(CloseStatus upstreamStatus) {
    if (upstreamStatus == null) {
      return close(INTERNAL_ERROR, NO_SUBREASON, false);
    }
    return switch (upstreamStatus.getCode()) {
      case 1000 -> close(LOGOUT, supportedSubreason(upstreamStatus.getReason()), false);
      case 1001 -> close(IDLE_TIMEOUT, NO_SUBREASON, false);
      case 1008 -> close(POLICY_VIOLATION, NO_SUBREASON, false);
      case 1013 -> close(BACKEND_UNAVAILABLE, NO_SUBREASON, false);
      default -> close(INTERNAL_ERROR, NO_SUBREASON, false);
    };
  }

  public CloseClassification slowClientClose() {
    return close(POLICY_VIOLATION, EDGE_BACKPRESSURE, true);
  }

  private static CloseClassification close(String reason, String subreason, boolean slowClient) {
    int statusCode =
        switch (reason) {
          case LOGOUT -> 1000;
          case IDLE_TIMEOUT -> 1001;
          case POLICY_VIOLATION -> 1008;
          case BACKEND_UNAVAILABLE -> 1013;
          default -> 1011;
        };
    String wireReason =
        NO_SUBREASON.equals(subreason) ? reason : reason + SUBREASON_PREFIX + subreason;
    return new CloseClassification(
        new CloseStatus(statusCode, wireReason), reason, subreason, slowClient);
  }

  private static String supportedSubreason(String reason) {
    if (reason == null) {
      return NO_SUBREASON;
    }
    int index = reason.indexOf(SUBREASON_PREFIX);
    if (index < 0) {
      return NO_SUBREASON;
    }
    String subreason = reason.substring(index + SUBREASON_PREFIX.length());
    return SUPPORTED_SUBREASONS.contains(subreason) ? subreason : NO_SUBREASON;
  }

  public record CloseClassification(
      CloseStatus status, String reason, String subreason, boolean slowClient) {}
}
