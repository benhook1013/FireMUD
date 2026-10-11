package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.impl.HistoricalOriginalStartSessionOwnerEvidenceReadWorkloadGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HistoricalOriginalStartSessionOwnerEvidenceReadWorkloadGuardTest {
  private static final String NAMESPACE = "gameplay";
  private final HistoricalOriginalStartSessionOwnerEvidenceReadWorkloadGuard guard =
      new HistoricalOriginalStartSessionOwnerEvidenceReadWorkloadGuard(NAMESPACE);

  @AfterEach
  void clearCallerContexts() {
    SessionContext.clear();
  }

  @Test
  void allowsOnlyExactSameNamespaceWorldAndAccountWorkloads() {
    assertThatCode(
            () ->
                withPeer(
                    "world-management-service",
                    NAMESPACE,
                    () -> {
                      guard.requireHistoricalOwnerReadCaller();
                      guard.requireConfiguredTargetNamespace(NAMESPACE);
                    }))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                withPeer(
                    "account-service",
                    NAMESPACE,
                    () -> {
                      guard.requireHistoricalOwnerReadCaller();
                      guard.requireConfiguredTargetNamespace(NAMESPACE);
                    }))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesOtherNamespacesWrongServicesAndAuthenticatedEndUserContext() {
    assertThatThrownBy(
            () ->
                withPeer(
                    "world-management-service", "other", guard::requireHistoricalOwnerReadCaller))
        .isInstanceOf(AdminAuthorizationException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    "entity-management-service",
                    NAMESPACE,
                    guard::requireHistoricalOwnerReadCaller))
        .isInstanceOf(AdminAuthorizationException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    "world-management-service",
                    NAMESPACE,
                    () -> {
                      SessionContext.setContext(
                          "42", java.util.List.of("tenantAdmin"), java.util.Map.of());
                      guard.requireHistoricalOwnerReadCaller();
                    }))
        .isInstanceOf(AdminAuthorizationException.class);
  }

  @Test
  void rejectsInconsistentPeerIdentityBeforeGuardInvocation() {
    assertThatThrownBy(
            () ->
                new GrpcPeerIdentity(
                    "spiffe://firemud/ns/other/sa/world-management-service",
                    NAMESPACE,
                    "world-management-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAConfiguredTargetNamespaceDifferentFromTheProducerNamespace() {
    assertThatThrownBy(() -> guard.requireConfiguredTargetNamespace("other"))
        .isInstanceOf(AdminAuthorizationException.class);
  }

  private static void withPeer(String service, String namespace, Runnable action) {
    withPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service, action);
  }

  private static void withPeerIdentity(
      String uri, String namespace, String service, Runnable action) {
    GrpcPeerIdentity peer = new GrpcPeerIdentity(uri, namespace, service);
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
