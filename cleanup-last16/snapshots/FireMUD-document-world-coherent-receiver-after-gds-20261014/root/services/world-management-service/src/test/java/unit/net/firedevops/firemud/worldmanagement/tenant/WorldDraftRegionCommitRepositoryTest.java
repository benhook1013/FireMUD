package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.ResultQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class WorldDraftRegionCommitRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final WorldDesignPublicationFenceRepository fence =
      mock(WorldDesignPublicationFenceRepository.class);
  private final ObjectMapper mapper = mock(ObjectMapper.class);
  private final WorldDraftRegionCommitRepository repository =
      new WorldDraftRegionCommitRepository(dsl, fence, mapper);
  private final WorldDraftRegionCommitPlan plan = mock(WorldDraftRegionCommitPlan.class);

  @AfterEach
  void clearTransactionFixture() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void absentTransactionCannotResolveOrTouchHistory() {
    assertThatThrownBy(() -> repository.store(plan))
        .hasMessageContaining("writable READ COMMITTED");
    verifyNoInteractions(dsl, fence, plan);
  }

  @Test
  void readOnlyTransactionCannotResolveOrTouchHistory() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_READ_COMMITTED);
    assertThatThrownBy(() -> repository.store(plan))
        .hasMessageContaining("writable READ COMMITTED");
    verifyNoInteractions(dsl, fence, plan);
  }

  @Test
  void repeatableReadCannotUseReadCommittedReplayAndOwnerLockOrdering() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_REPEATABLE_READ);
    assertThatThrownBy(() -> repository.store(plan))
        .hasMessageContaining("writable READ COMMITTED");
    verifyNoInteractions(dsl, fence, plan);
  }

  @Test
  void committedReadbackRejectsActiveCallerBeforeStorageOrSourceResolution() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> repository.readCommitted(plan))
        .hasMessageContaining("no active caller transaction");
    verifyNoInteractions(dsl, fence, mapper, plan);
  }

  @Test
  @SuppressWarnings("unchecked")
  void missingCommittedOperationIsUnknownWithoutOwnerClaimOrMutation() {
    DraftCommitBinding binding = mock(DraftCommitBinding.class);
    WorldDesignPublicationFenceEvidence.OwnerBinding owner =
        mock(WorldDesignPublicationFenceEvidence.OwnerBinding.class);
    ResultQuery<Record> query = mock(ResultQuery.class);
    Result<Record> rows = mock(Result.class);
    when(plan.binding()).thenReturn(binding);
    when(plan.ownerBinding()).thenReturn(owner);
    when(binding.requestId()).thenReturn(UUID.randomUUID());
    when(binding.commitId()).thenReturn(UUID.randomUUID());
    when(mapper.writeValueAsString(owner)).thenReturn("synthetic-owner-json");
    when(dsl.resultQuery(anyString(), any(Object[].class))).thenReturn(query);
    when(query.fetch()).thenReturn(rows);
    when(rows.isEmpty()).thenReturn(true);
    assertThat(repository.readCommitted(plan)).isEmpty();
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dsl).resultQuery(sql.capture(), any(Object[].class));
    assertThat(sql.getValue())
        .startsWith("SELECT ")
        .doesNotContain("FOR UPDATE", "INSERT ", "UPDATE ", "DELETE ");
    verify(query).fetch();
    verifyNoMoreInteractions(dsl, query);
    verifyNoInteractions(fence);
  }
}
