package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PublishedRealmAdmissionOwnerReadWorkloadGuardTest {
  private static final String NAMESPACE = "gameplay";
  private static final String ENTITY_SERVICE = "entity-management-service";

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void acceptsOnlyTheConfiguredEntityManagementWorkloadPeer() {
    PublishedRealmAdmissionOwnerReadWorkloadGuard guard =
        new PublishedRealmAdmissionOwnerReadWorkloadGuard(NAMESPACE);

    assertThatCode(
            () ->
                runAsPeer(
                    ENTITY_SERVICE,
                    NAMESPACE,
                    () -> {
                      guard.requireEntityManagementOwnerReadCaller();
                      guard.requireConfiguredTargetNamespace(NAMESPACE);
                    }))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsOtherWorkloadsNamespacesMissingPeersAndInvalidConfiguration() {
    PublishedRealmAdmissionOwnerReadWorkloadGuard guard =
        new PublishedRealmAdmissionOwnerReadWorkloadGuard(NAMESPACE);

    assertDenied(
        () ->
            runAsPeer(
                "world-management-service",
                NAMESPACE,
                guard::requireEntityManagementOwnerReadCaller));
    assertDenied(
        () ->
            runAsPeer(
                ENTITY_SERVICE,
                "other-namespace",
                guard::requireEntityManagementOwnerReadCaller));
    assertDenied(() -> guard.requireEntityManagementOwnerReadCaller());
    assertDenied(
        () ->
            runAsPeer(
                ENTITY_SERVICE,
                NAMESPACE,
                () -> guard.requireConfiguredTargetNamespace("other-namespace")));
    assertDenied(
        () ->
            runAsPeer(
                ENTITY_SERVICE,
                NAMESPACE,
                () ->
                    new PublishedRealmAdmissionOwnerReadWorkloadGuard("Invalid_Namespace")
                        .requireEntityManagementOwnerReadCaller()));
  }

  @Test
  void rejectsAuthenticatedCallerContextEvenWithTheExpectedPeerCertificate() {
    PublishedRealmAdmissionOwnerReadWorkloadGuard guard =
        new PublishedRealmAdmissionOwnerReadWorkloadGuard(NAMESPACE);
    SessionContext.setContext("account", List.of("player"), Map.of());

    assertDenied(
        () ->
            runAsPeer(
                ENTITY_SERVICE,
                NAMESPACE,
                guard::requireEntityManagementOwnerReadCaller));
  }

  private static void assertDenied(Runnable action) {
    assertThatThrownBy(() -> action.run()).isInstanceOf(AdminAuthorizationException.class);
  }

  private static void runAsPeer(String service, String namespace, Runnable action) {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service))
        .run(action);
  }
}
