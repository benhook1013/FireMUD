package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.impl.OriginalStartSessionCurrentAttemptReadWorkloadGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OriginalStartSessionCurrentAttemptReadWorkloadGuardTest {
  private static final String NAMESPACE = "gameplay";
  private final OriginalStartSessionCurrentAttemptReadWorkloadGuard guard =
      new OriginalStartSessionCurrentAttemptReadWorkloadGuard(NAMESPACE);

  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void allowsOnlyExactSameNamespaceAccountWorkloadWithoutEndUserContext() {
    assertThatCode(
            () ->
                withPeer(
                    "account-service",
                    NAMESPACE,
                    () -> {
                      guard.requireAccountCurrentAttemptReadCaller();
                      guard.requireConfiguredTargetNamespace(NAMESPACE);
                    }))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesWrongNamespaceWrongServiceEndUserContextAndTargetNamespace() {
    assertThatThrownBy(
            () ->
                withPeer("account-service", "other", guard::requireAccountCurrentAttemptReadCaller))
        .isInstanceOf(AdminAuthorizationException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    "world-management-service",
                    NAMESPACE,
                    guard::requireAccountCurrentAttemptReadCaller))
        .isInstanceOf(AdminAuthorizationException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    "account-service",
                    NAMESPACE,
                    () -> {
                      SessionContext.setContext(
                          "42", java.util.List.of("tenantAdmin"), java.util.Map.of());
                      guard.requireAccountCurrentAttemptReadCaller();
                    }))
        .isInstanceOf(AdminAuthorizationException.class);
    assertThatThrownBy(() -> guard.requireConfiguredTargetNamespace("other"))
        .isInstanceOf(AdminAuthorizationException.class);
  }

  private static void withPeer(String service, String namespace, Runnable action) {
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
      SessionContext.clear();
    }
  }
}
