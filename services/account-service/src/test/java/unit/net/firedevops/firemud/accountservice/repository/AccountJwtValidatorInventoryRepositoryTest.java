package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountJwtValidatorInventoryRepositoryTest {
  private static final Binding BINDING =
      new Binding(
          "staging", "cluster-a", "firemud", CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  private static final TrustFence TRUST =
      new TrustFence(
          "11111111-1111-4111-8111-111111111111",
          "22222222-2222-4222-8222-222222222222",
          "a".repeat(64),
          "trust-r1");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsUseOutsideAnOwningAccountTransactionWithoutDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtValidatorInventoryRepository repository =
        new AccountJwtValidatorInventoryRepository(dsl);

    assertThatThrownBy(() -> repository.persistOrReadback(snapshot("good"), BINDING, TRUST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owning Account transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void rejectsReadOnlyTransactionBeforeAnyInsert() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtValidatorInventoryRepository repository =
        new AccountJwtValidatorInventoryRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    assertThatThrownBy(() -> repository.persistOrReadback(snapshot("good"), BINDING, TRUST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("A writable Account transaction is required");

    verifyNoInteractions(dsl);
  }

  @Test
  void rejectsWrongClusterNamespaceOrIncarnationBeforeInsert() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtValidatorInventoryRepository repository =
        new AccountJwtValidatorInventoryRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(
            () -> repository.persistOrReadback(snapshot("other-cluster"), BINDING, TRUST))
        .isInstanceOf(
            AccountJwtValidatorInventoryRepository.InventorySnapshotUnavailableException.class)
        .hasNoCause();

    verifyNoInteractions(dsl);
  }

  @Test
  void rejectsCanonicalSnapshotBytesWhoseDigestDoesNotMatchBeforeInsert() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtValidatorInventoryRepository repository =
        new AccountJwtValidatorInventoryRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    InventorySnapshot candidate = snapshot("cluster-a");
    when(candidate.canonicalBytes())
        .thenReturn("{\"tampered\":true}".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> repository.persistOrReadback(candidate, BINDING, TRUST))
        .isInstanceOf(
            AccountJwtValidatorInventoryRepository.InventorySnapshotUnavailableException.class)
        .hasNoCause();

    verifyNoInteractions(dsl);
  }

  @Test
  void rejectsSnapshotDigestMismatchAndReturnsDefensiveByteCopies() {
    byte[] bytes = "{\"domain\":\"test\"}".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(bytes);
    AccountJwtValidatorInventoryRepository.StoredSnapshot snapshot =
        new AccountJwtValidatorInventoryRepository.StoredSnapshot(
            digest,
            "staging",
            "cluster-a",
            TRUST.expectedClusterIncarnationUid(),
            "firemud",
            TRUST.expectedNamespaceUid(),
            "api-r1",
            "b".repeat(64),
            "inventory-r1",
            "c".repeat(64),
            Instant.parse("2026-10-05T00:00:00Z"),
            bytes);
    byte[] returned = snapshot.canonicalBytes();
    returned[2] = 'x';

    assertThat(snapshot.canonicalBytes()).isEqualTo(bytes);
    assertThatThrownBy(
            () ->
                new AccountJwtValidatorInventoryRepository.StoredSnapshot(
                    "d".repeat(64),
                    "staging",
                    "cluster-a",
                    TRUST.expectedClusterIncarnationUid(),
                    "firemud",
                    TRUST.expectedNamespaceUid(),
                    "api-r1",
                    "b".repeat(64),
                    "inventory-r1",
                    "c".repeat(64),
                    Instant.parse("2026-10-05T00:00:00Z"),
                    bytes))
        .isInstanceOf(
            AccountJwtValidatorInventoryRepository.InventorySnapshotUnavailableException.class)
        .hasNoCause();
  }

  private static InventorySnapshot snapshot(String clusterId) {
    byte[] bytes = "{\"domain\":\"inventory\"}".getBytes(StandardCharsets.UTF_8);
    InventorySnapshot snapshot = mock(InventorySnapshot.class);
    when(snapshot.observedAt()).thenReturn(Instant.parse("2026-10-05T00:00:00Z"));
    when(snapshot.environmentId()).thenReturn("staging");
    when(snapshot.clusterId()).thenReturn(clusterId);
    when(snapshot.clusterIncarnationUid()).thenReturn(TRUST.expectedClusterIncarnationUid());
    when(snapshot.namespace()).thenReturn("firemud");
    when(snapshot.namespaceUid()).thenReturn(TRUST.expectedNamespaceUid());
    when(snapshot.apiBindingRevision()).thenReturn("api-r1");
    when(snapshot.apiBindingDigest()).thenReturn("b".repeat(64));
    when(snapshot.inventoryBindingRevision()).thenReturn("inventory-r1");
    when(snapshot.inventoryBindingDigest()).thenReturn("c".repeat(64));
    when(snapshot.canonicalBytes()).thenReturn(bytes);
    when(snapshot.digest()).thenReturn(sha256(bytes));
    return snapshot;
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
