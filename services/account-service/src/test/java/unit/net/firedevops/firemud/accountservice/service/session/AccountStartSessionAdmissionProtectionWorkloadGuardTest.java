package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountStartSessionAdmissionProtectionWorkloadGuardTest {
  private static final String NAMESPACE = "world-runtime";
  private final AccountStartSessionAdmissionProtectionWorkloadGuard guard =
      new AccountStartSessionAdmissionProtectionWorkloadGuard(NAMESPACE);

  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void requiresCanonicalNamespaceAtConstruction() {
    assertThatThrownBy(() -> new AccountStartSessionAdmissionProtectionWorkloadGuard("UPPER"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsOnlyExactSameNamespaceGameSessionPeerWithoutEndUserContext() {
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer(NAMESPACE, "game-session-service"))
        .run(() -> assertThatCode(guard::requireGameSessionCaller).doesNotThrowAnyException());
    assertDenied(null, Status.Code.UNAUTHENTICATED, () -> guard.requireGameSessionCaller());
    assertDenied(
        peer(NAMESPACE, "world-management-service"),
        Status.Code.PERMISSION_DENIED,
        () -> guard.requireGameSessionCaller());
    assertDenied(
        peer("another", "game-session-service"),
        Status.Code.PERMISSION_DENIED,
        () -> guard.requireGameSessionCaller());

    SessionContext.setContext("123", java.util.List.of(), java.util.Map.of());
    assertDenied(
        peer(NAMESPACE, "game-session-service"),
        Status.Code.PERMISSION_DENIED,
        () -> guard.requireGameSessionCaller());
  }

  private static void assertDenied(
      GrpcPeerIdentity peer, Status.Code expected, Runnable invocation) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    assertThatThrownBy(() -> context.run(invocation))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                org.assertj.core.api.Assertions.assertThat(
                        ((StatusRuntimeException) failure).getStatus().getCode())
                    .isEqualTo(expected));
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }
}
