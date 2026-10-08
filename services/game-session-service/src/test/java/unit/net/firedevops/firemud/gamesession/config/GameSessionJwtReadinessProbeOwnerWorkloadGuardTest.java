package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GameSessionJwtReadinessProbeOwnerWorkloadGuardTest {
  private static final String NAMESPACE = "firemud-prod";
  private static final String METHOD =
      "game_session.v1.GameSessionJwtReadinessReceiverService/ReceiveReadinessProbe";

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void acceptsOnlyExactSameNamespaceAccountPeerForDedicatedMethod() {
    var guard = new GameSessionJwtReadinessProbeOwnerWorkloadGuard(NAMESPACE);

    assertDenied(() -> guard.requireAccountReceiverCaller(METHOD));
    withPeer(
        peer("other", "account-service"),
        () -> assertDenied(() -> guard.requireAccountReceiverCaller(METHOD)));
    withPeer(
        peer(NAMESPACE, "game-session-service"),
        () -> assertDenied(() -> guard.requireAccountReceiverCaller(METHOD)));
    withPeer(
        peer(NAMESPACE, "account-service"),
        () -> assertDenied(() -> guard.requireAccountReceiverCaller(METHOD + "Extra")));

    withPeer(
        peer(NAMESPACE, "account-service"),
        () -> {
          var caller = guard.requireAccountReceiverCaller(METHOD);
          assertThat(caller.peer().uri())
              .isEqualTo("spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service");
          guard.requireUnchanged(caller, METHOD);
        });
  }

  @Test
  void rejectsApplicationSessionContextAndCallerDrift() {
    var guard = new GameSessionJwtReadinessProbeOwnerWorkloadGuard(NAMESPACE);
    withPeer(
        peer(NAMESPACE, "account-service"),
        () -> {
          var caller = guard.requireAccountReceiverCaller(METHOD);
          SessionContext.setContext("user-1", java.util.List.of(), java.util.Map.of());
          assertDenied(() -> guard.requireUnchanged(caller, METHOD));
          SessionContext.clear();
        });

    var otherGuard = new GameSessionJwtReadinessProbeOwnerWorkloadGuard("other");
    withPeer(
        peer(NAMESPACE, "account-service"),
        () -> assertDenied(() -> otherGuard.requireAccountReceiverCaller(METHOD)));
  }

  private static void assertDenied(Runnable invocation) {
    assertThatThrownBy(invocation::run)
        .isInstanceOf(
            GameSessionJwtReadinessProbeOwnerWorkloadGuard.ReceiverCallerDeniedException.class);
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.ROOT.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
      SessionContext.clear();
    }
  }
}
