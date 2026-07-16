package net.firedevops.firemud.springcloudgateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.CloseStatus;

class GameplayWebSocketObservabilityTest {

  @Test
  void normalizesClientVisibleCloseReasonsToTheBoundedTaxonomy() {
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(new SimpleMeterRegistry());

    var plannedDrain =
        observability.classify(new CloseStatus(1000, "logout;subreason=gateway_restart"));
    var explicitNoSubreason =
        observability.classify(new CloseStatus(1000, "logout;subreason=none"));
    var logout = observability.classify(new CloseStatus(1000, "logout;subreason=takeover"));
    var unexpected = observability.classify(new CloseStatus(3999, "unbounded-peer-reason"));

    assertThat(plannedDrain.bridgeShutdownClass()).isEqualTo("planned_drain");
    assertThat(explicitNoSubreason.bridgeShutdownClass()).isEqualTo("upstream_logout");
    assertThat(explicitNoSubreason.subreason()).isEqualTo("none");
    assertThat(logout.status().getCode()).isEqualTo(1000);
    assertThat(logout.status().getReason()).isEqualTo("logout;subreason=takeover");
    assertThat(logout.reason()).isEqualTo("logout");
    assertThat(logout.subreason()).isEqualTo("takeover");
    assertThat(logout.bridgeShutdownClass()).isEqualTo("upstream_logout");
    assertThat(unexpected.status().getCode()).isEqualTo(1011);
    assertThat(unexpected.status().getReason()).isEqualTo("internal_error");
    assertThat(unexpected.subreason()).isEqualTo("none");
    assertThat(unexpected.bridgeShutdownClass()).isEqualTo("unattributed_failure");
  }

  @Test
  void attributesOnlyCanonicalLogoutCloseReasons() {
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(new SimpleMeterRegistry());

    var cleanLogout = observability.classify(new CloseStatus(1000, "logout"));
    var plannedDrain =
        observability.classify(new CloseStatus(1000, "logout;subreason=gateway_restart"));
    var missingReason = observability.classify(new CloseStatus(1000, null));
    var noncanonicalReason =
        observability.classify(new CloseStatus(1000, "shutdown;subreason=gateway_restart"));
    var malformedSubreason =
        observability.classify(new CloseStatus(1000, "logout;subreason=gateway_restart-extra"));

    assertThat(cleanLogout.bridgeShutdownClass()).isEqualTo("upstream_logout");
    assertThat(cleanLogout.status().getReason()).isEqualTo("logout");
    assertThat(plannedDrain.bridgeShutdownClass()).isEqualTo("planned_drain");
    assertUnattributedFailure(missingReason);
    assertUnattributedFailure(noncanonicalReason);
    assertUnattributedFailure(malformedSubreason);
  }

  @Test
  void recordsSlowClientClosesAsAPolicyViolationSubset() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(meterRegistry);

    var slowClientClose = observability.slowClientClose();
    observability.recordClose(slowClientClose);

    assertThat(slowClientClose.status().getCode()).isEqualTo(1008);
    assertThat(slowClientClose.status().getReason())
        .isEqualTo("policy_violation;subreason=edge_backpressure");
    assertThat(
            meterRegistry
                .get("gateway.websocket.closes")
                .tags(
                    "reason",
                    "policy_violation",
                    "subreason",
                    "edge_backpressure",
                    "bridge_shutdown_class",
                    "unattributed_failure")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.get("gateway.websocket.slow_client_closes").counter().count())
        .isEqualTo(1.0);
  }

  private static void assertUnattributedFailure(
      GameplayWebSocketObservability.CloseClassification classification) {
    assertThat(classification.status().getCode()).isEqualTo(1011);
    assertThat(classification.status().getReason()).isEqualTo("internal_error");
    assertThat(classification.reason()).isEqualTo("internal_error");
    assertThat(classification.subreason()).isEqualTo("none");
    assertThat(classification.bridgeShutdownClass()).isEqualTo("unattributed_failure");
  }
}
