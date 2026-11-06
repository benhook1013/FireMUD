package unit.net.firedevops.firemud.common.runtimecatalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogReadGuard;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogReadGuard.Refusal;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RuntimeFamilyCatalogReadGuardTest {
  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void authenticationAndWorkloadRefusalsPrecedeTransactionRefusal() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThat(RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(null, "test"))
        .contains(Refusal.UNAUTHENTICATED);
    assertThat(
            RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(
                peer("account-service", "test"), "test"))
        .contains(Refusal.PERMISSION_DENIED);
    assertThat(
            RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(
                peer("game-session-service", "other"), "test"))
        .contains(Refusal.PERMISSION_DENIED);
    assertThat(
            RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(
                peer("game-session-service", "test"), "test"))
        .contains(Refusal.FAILED_PRECONDITION);
  }

  @Test
  void everyPropagatedAccountRoleOrInternalContextRefusesAnOtherwiseValidPeer() {
    var peer = peer("game-session-service", "test");
    SessionContext.setContext(UUID.randomUUID().toString(), List.of(), Map.of());
    assertThat(RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(peer, "test"))
        .contains(Refusal.PERMISSION_DENIED);
    SessionContext.setContext(null, List.of("platformAdmin"), Map.of());
    assertThat(RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(peer, "test"))
        .contains(Refusal.PERMISSION_DENIED);
    SessionContext.setContext(
        null, List.of(), Map.of(UUID.randomUUID().toString(), List.of("tenantAdmin")));
    assertThat(RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(peer, "test"))
        .contains(Refusal.PERMISSION_DENIED);
    SessionContext.setContext(
        null, List.of(), Map.of(), true, "game-session-service", "controlled");
    assertThat(RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(peer, "test"))
        .contains(Refusal.PERMISSION_DENIED);
  }

  @Test
  void transactionSynchronizationAloneRefusesAndCleanPeerIsAllowed() {
    var peer = peer("game-session-service", "test");
    assertThat(RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(peer, "test")).isEmpty();
    TransactionSynchronizationManager.initSynchronization();
    assertThat(RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(peer, "test"))
        .contains(Refusal.FAILED_PRECONDITION);
  }

  @Test
  void requestNamespaceMustMatchTheFixedReceiverExactly() {
    assertThat(RuntimeFamilyCatalogReadGuard.matchesNamespace("test", "test")).isTrue();
    assertThat(RuntimeFamilyCatalogReadGuard.matchesNamespace("test", "other")).isFalse();
    assertThat(RuntimeFamilyCatalogReadGuard.matchesNamespace("test", null)).isFalse();
    assertThat(RuntimeFamilyCatalogReadGuard.matchesNamespace(null, "test")).isFalse();
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }
}
