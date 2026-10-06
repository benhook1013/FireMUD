package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameInstanceLaunchAssociationReadWorkloadGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CanonicalGameInstanceLaunchAssociationReadWorkloadGuardTest {
  private static final String NAMESPACE = "gameplay";
  private static final String WORLD_SERVICE = "world-management-service";

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void acceptsOnlyTheExactSameNamespaceWorldManagementPeer() {
    var guard = new CanonicalGameInstanceLaunchAssociationReadWorkloadGuard(NAMESPACE);
    assertThatCode(
            () ->
                runAsPeer(
                    WORLD_SERVICE,
                    NAMESPACE,
                    () -> {
                      guard.requireWorldManagementOwnerReadCaller();
                      guard.requireConfiguredTargetNamespace(NAMESPACE);
                    }))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongPeerNamespaceTargetAndAuthenticatedCallerContext() {
    var guard = new CanonicalGameInstanceLaunchAssociationReadWorkloadGuard(NAMESPACE);
    denied(
        () ->
            runAsPeer(
                "entity-management-service",
                NAMESPACE,
                guard::requireWorldManagementOwnerReadCaller));
    denied(() -> runAsPeer(WORLD_SERVICE, "other", guard::requireWorldManagementOwnerReadCaller));
    denied(guard::requireWorldManagementOwnerReadCaller);
    denied(
        () ->
            runAsPeer(
                WORLD_SERVICE, NAMESPACE, () -> guard.requireConfiguredTargetNamespace("other")));
    SessionContext.setContext("account", List.of("player"), Map.of());
    denied(() -> runAsPeer(WORLD_SERVICE, NAMESPACE, guard::requireWorldManagementOwnerReadCaller));
  }

  private static void denied(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOf(AdminAuthorizationException.class);
  }

  private static void runAsPeer(String service, String namespace, Runnable action) {
    String uri = "spiffe://firemud/ns/" + namespace + "/sa/" + service;
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, new GrpcPeerIdentity(uri, namespace, service))
        .run(action);
  }
}
