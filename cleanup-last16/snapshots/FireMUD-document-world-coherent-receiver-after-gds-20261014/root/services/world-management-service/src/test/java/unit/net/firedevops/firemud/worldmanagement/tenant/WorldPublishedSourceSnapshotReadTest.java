package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class WorldPublishedSourceSnapshotReadTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final WorldCanonicalFrozenTopologyRepository frozen =
      new WorldCanonicalFrozenTopologyRepository(
          dsl,
          mock(WorldAuthoredGraphSnapshotRepository.class),
          mock(WorldDraftTopologyCommitRepository.class),
          mock(net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService.class));
  private final WorldDraftGraphApplicationRepository applications =
      new WorldDraftGraphApplicationRepository(
          dsl, mock(WorldDesignPublicationFenceRepository.class), mock(ObjectMapper.class));
  private final WorldPublishedStartLocationRepository published =
      new WorldPublishedStartLocationRepository(dsl, frozen, applications);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void allOwnedSnapshotSeamsRejectMissingTransaction() {
    assertThatThrownBy(() -> frozen.readInOwnedSnapshot(null))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThatThrownBy(() -> applications.readInOwnedSnapshot("test", new byte[0]))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThatThrownBy(() -> published.readInOwnedSnapshot(null))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
  }

  @Test
  void allOwnedSnapshotSeamsRejectWritableTransactions() {
    setTransaction(false, Connection.TRANSACTION_REPEATABLE_READ);
    assertSnapshotSeamsReject();
  }

  @Test
  void allOwnedSnapshotSeamsRejectWrongTransactionIsolation() {
    setTransaction(true, Connection.TRANSACTION_READ_COMMITTED);
    assertSnapshotSeamsReject();
  }

  @Test
  void allOwnedSnapshotSeamsRejectWrongActualJdbcIsolation() {
    setTransaction(true, Connection.TRANSACTION_REPEATABLE_READ);
    when(dsl.connectionResult(any())).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    assertSnapshotSeamsReject();
  }

  @Test
  void existingCommittedApisContinueRejectingAmbientTransactions() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> frozen.readCommitted((WorldAuthoredGraphSnapshot.CaptureRequest) null))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThatThrownBy(() -> applications.readCommitted("test", new byte[0]))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThatThrownBy(() -> published.readCommitted(null))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
  }

  private void assertSnapshotSeamsReject() {
    assertThatThrownBy(() -> frozen.readInOwnedSnapshot(null))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThatThrownBy(() -> applications.readInOwnedSnapshot("test", new byte[0]))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    assertThatThrownBy(() -> published.readInOwnedSnapshot(null))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
  }

  private static void setTransaction(boolean readOnly, int isolation) {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
  }
}
