package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetPublicationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class VersionAssetPublicationServiceImplTest {
  private static final String TENANT_ID = "owner-key";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void commitsSnapshotInRequiresNewThenReadsItBackInIndependentRepeatableReadTransaction() {
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    VersionAssetPublicationRepository repository = mock(VersionAssetPublicationRepository.class);
    ExportSnapshot persisted = snapshot(bytes("exact source"));
    when(repository.freezeOrReadSnapshot(TENANT_ID, 3)).thenReturn(persisted);
    when(repository.readFrozenSnapshot(TENANT_ID, 3)).thenReturn(snapshot(bytes("exact source")));
    VersionAssetPublicationServiceImpl service =
        new VersionAssetPublicationServiceImpl(transactionManager, repository);

    ExportSnapshot result = service.freezeOrReadSnapshot(TENANT_ID, 3);

    assertThat(result.items().get(0).bytes()).isEqualTo(bytes("exact source"));
    assertThat(transactionManager.commitCount).isEqualTo(2);
    assertThat(transactionManager.definitions).hasSize(2);
    assertThat(transactionManager.definitions)
        .allSatisfy(
            definition ->
                assertThat(definition.getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    assertThat(transactionManager.definitions.get(0).isReadOnly()).isFalse();
    assertThat(transactionManager.definitions.get(1).isReadOnly()).isTrue();
    assertThat(transactionManager.definitions.get(1).getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    verify(repository).freezeOrReadSnapshot(TENANT_ID, 3);
    verify(repository).readFrozenSnapshot(TENANT_ID, 3);
  }

  @Test
  void failsClosedWhenIndependentReadbackDiffersFromCommittedSnapshot() {
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    VersionAssetPublicationRepository repository = mock(VersionAssetPublicationRepository.class);
    when(repository.freezeOrReadSnapshot(TENANT_ID, 3)).thenReturn(snapshot(bytes("first")));
    when(repository.readFrozenSnapshot(TENANT_ID, 3)).thenReturn(snapshot(bytes("changed")));
    VersionAssetPublicationServiceImpl service =
        new VersionAssetPublicationServiceImpl(transactionManager, repository);

    assertThatThrownBy(() -> service.freezeOrReadSnapshot(TENANT_ID, 3))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not match independent readback");
    assertThat(transactionManager.commitCount).isEqualTo(2);
  }

  @Test
  void associationUsesItsOwnCommittedOwnerTransaction() {
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    VersionAssetPublicationRepository repository = mock(VersionAssetPublicationRepository.class);
    VersionAssetPublicationServiceImpl service =
        new VersionAssetPublicationServiceImpl(transactionManager, repository);

    service.associateDraftAsset(TENANT_ID, 17L, 29L, "logo");

    verify(repository).associateDraftAsset(TENANT_ID, 17L, 29L, "logo");
    assertThat(transactionManager.commitCount).isEqualTo(1);
    assertThat(transactionManager.definitions).hasSize(1);
    assertThat(transactionManager.definitions.get(0).getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  void snapshotAndAssetSelectionDefensivelyCopyBytesAndFreezeTheItemList() {
    byte[] source = bytes("immutable");
    AssetSelection item = new AssetSelection("logo.png", 29L, source, "image/png", "sha256:abc");
    source[0] = 0;
    byte[] exposed = item.bytes();
    exposed[1] = 0;
    List<AssetSelection> items = new ArrayList<>(List.of(item));
    ExportSnapshot snapshot =
        new ExportSnapshot(TENANT_ID, 17L, 3, 4L, CANONICAL_TENANT_ID, CANONICAL_VERSION_ID, items);
    items.clear();

    assertThat(snapshot.items()).hasSize(1);
    assertThat(snapshot.items().get(0).bytes()).isEqualTo(bytes("immutable"));
    assertThatThrownBy(() -> snapshot.items().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static ExportSnapshot snapshot(byte[] bytes) {
    return new ExportSnapshot(
        TENANT_ID,
        17L,
        3,
        4L,
        CANONICAL_TENANT_ID,
        CANONICAL_VERSION_ID,
        List.of(new AssetSelection("logo.png", 29L, bytes, "image/png", "sha256:abc")));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static final class RecordingTransactionManager
      extends AbstractPlatformTransactionManager {
    private final List<TransactionDefinition> definitions = new ArrayList<>();
    private int commitCount;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      definitions.add(definition);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commitCount++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
