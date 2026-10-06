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

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.ResultQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class WorldDraftTopologyCommitRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final WorldDesignPublicationFenceRepository fence =
      mock(WorldDesignPublicationFenceRepository.class);
  private final ObjectMapper mapper = mock(ObjectMapper.class);
  private final WorldDraftTopologyCommitRepository repository =
      new WorldDraftTopologyCommitRepository(dsl, fence, mapper);
  private final WorldDraftTopologyCommitPlan plan = mock(WorldDraftTopologyCommitPlan.class);

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

  @Test
  void typedProjectionNormalizesParsedSpellingsWithoutReadingOrReplacingRawBinding()
      throws InvalidProtocolBufferException {
    UUID template = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    UUID commit = UUID.randomUUID();
    var mutation =
        WorldDesignMutationRevision.newBuilder()
            .setLogicalRevisionId(revision.toString())
            .setCommitId(commit.toString())
            .setAggregateId(template.toString())
            .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
            .setScopeId(template.toString())
            .setRegion(RegionDesignMutation.newBuilder().setName("region"))
            .build();
    String original =
        JsonFormat.printer()
            .preservingProtoFieldNames()
            .printingEnumsAsInts()
            .omittingInsignificantWhitespace()
            .print(mutation);
    var parsed = WorldDesignMutationRevision.newBuilder();
    JsonFormat.parser().merge(original, parsed);
    var graph =
        new WorldDraftTopologyInputGraph(
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(
                new WorldDraftTopologyInputGraph.Node(
                    "4", revision, template, template, parsed.build(), null)),
            null);
    when(plan.graph()).thenReturn(graph);
    var projection = repository.executionRevisions(plan);
    assertThat(projection).hasSize(1);
    var execution = projection.getFirst();
    assertThat(execution.revisionId()).isEqualTo(revision);
    assertThat(execution.revisionOrder()).isEqualTo("4");
    assertThat(execution.owner()).isEqualTo(DraftCommitBinding.Owner.WORLD_MANAGEMENT);
    assertThat(original).contains("\"aggregate_type\":");
    assertThat(execution.payload())
        .contains("\"aggregateType\":\"WORLD_DESIGN_AGGREGATE_TYPE_REGION\"")
        .isNotEqualTo(original);
    var decoded = WorldDesignMutationRevision.newBuilder();
    JsonFormat.parser().merge(execution.payload(), decoded);
    assertThat(decoded.build()).isEqualTo(mutation);
    assertThat(decoded.getRegion().getSpacingMultiplier()).isZero();
    verifyNoInteractions(dsl, fence, mapper);
  }
}
