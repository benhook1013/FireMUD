package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldDesignRevisionLedger.WORLD_DESIGN_REVISION_LEDGER;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignRevisionLedger;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.WorldDesignRevisionLedgerRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class WorldDesignRevisionLedgerRepository {
  private final DSLContext dsl;

  public WorldDesignRevisionLedgerRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<WorldDesignRevisionLedger>
      findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
          Long tenantId,
          Long versionId,
          String commitId,
          String revisionId,
          String operationType,
          String aggregateType,
          String requestedAggregateId) {
    return dsl.selectFrom(WORLD_DESIGN_REVISION_LEDGER)
        .where(
            WORLD_DESIGN_REVISION_LEDGER
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_DESIGN_REVISION_LEDGER.VERSION_ID.eq(versionId))
                .and(WORLD_DESIGN_REVISION_LEDGER.COMMIT_ID.eq(commitId))
                .and(WORLD_DESIGN_REVISION_LEDGER.REVISION_ID.eq(revisionId))
                .and(WORLD_DESIGN_REVISION_LEDGER.OPERATION_TYPE.eq(operationType))
                .and(WORLD_DESIGN_REVISION_LEDGER.AGGREGATE_TYPE.eq(aggregateType))
                .and(WORLD_DESIGN_REVISION_LEDGER.REQUESTED_AGGREGATE_ID.eq(requestedAggregateId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<WorldDesignRevisionLedger> findById(Long id) {
    return dsl.selectFrom(WORLD_DESIGN_REVISION_LEDGER)
        .where(WORLD_DESIGN_REVISION_LEDGER.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public WorldDesignRevisionLedger save(WorldDesignRevisionLedger entity) {
    if (entity.getId() == null) {
      WorldDesignRevisionLedgerRecord record = dsl.newRecord(WORLD_DESIGN_REVISION_LEDGER);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(WORLD_DESIGN_REVISION_LEDGER)
            .set(WORLD_DESIGN_REVISION_LEDGER.TENANT_ID, entity.getTenantId())
            .set(WORLD_DESIGN_REVISION_LEDGER.VERSION_ID, entity.getVersionId())
            .set(WORLD_DESIGN_REVISION_LEDGER.COMMIT_ID, entity.getCommitId())
            .set(WORLD_DESIGN_REVISION_LEDGER.REVISION_ID, entity.getRevisionId())
            .set(WORLD_DESIGN_REVISION_LEDGER.OPERATION_TYPE, entity.getOperationType())
            .set(WORLD_DESIGN_REVISION_LEDGER.AGGREGATE_TYPE, entity.getAggregateType())
            .set(
                WORLD_DESIGN_REVISION_LEDGER.REQUESTED_AGGREGATE_ID,
                entity.getRequestedAggregateId())
            .set(WORLD_DESIGN_REVISION_LEDGER.APPLIED_AGGREGATE_ID, entity.getAppliedAggregateId())
            .set(WORLD_DESIGN_REVISION_LEDGER.RESULT, entity.getResult())
            .set(
                WORLD_DESIGN_REVISION_LEDGER.AGGREGATE_EPOCH_AFTER, entity.getAggregateEpochAfter())
            .set(WORLD_DESIGN_REVISION_LEDGER.SCOPE_EPOCH_AFTER, entity.getScopeEpochAfter())
            .set(WORLD_DESIGN_REVISION_LEDGER.CREATED_AT, entity.getCreatedAt())
            .where(WORLD_DESIGN_REVISION_LEDGER.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite(
          "world_design_revision_ledger", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private void populate(WorldDesignRevisionLedgerRecord record, WorldDesignRevisionLedger entity) {
    record.setTenantId(entity.getTenantId());
    record.setVersionId(entity.getVersionId());
    record.setCommitId(entity.getCommitId());
    record.setRevisionId(entity.getRevisionId());
    record.setOperationType(entity.getOperationType());
    record.setAggregateType(entity.getAggregateType());
    record.setRequestedAggregateId(entity.getRequestedAggregateId());
    record.setAppliedAggregateId(entity.getAppliedAggregateId());
    record.setResult(entity.getResult());
    record.setAggregateEpochAfter(entity.getAggregateEpochAfter());
    record.setScopeEpochAfter(entity.getScopeEpochAfter());
    record.setCreatedAt(
        entity.getCreatedAt() == null ? LocalDateTime.now() : entity.getCreatedAt());
  }

  private WorldDesignRevisionLedger toEntity(Record record) {
    WorldDesignRevisionLedger entity = new WorldDesignRevisionLedger();
    entity.setId(record.get(WORLD_DESIGN_REVISION_LEDGER.ID));
    entity.setTenantId(record.get(WORLD_DESIGN_REVISION_LEDGER.TENANT_ID));
    entity.setVersionId(record.get(WORLD_DESIGN_REVISION_LEDGER.VERSION_ID));
    entity.setCommitId(record.get(WORLD_DESIGN_REVISION_LEDGER.COMMIT_ID));
    entity.setRevisionId(record.get(WORLD_DESIGN_REVISION_LEDGER.REVISION_ID));
    entity.setOperationType(record.get(WORLD_DESIGN_REVISION_LEDGER.OPERATION_TYPE));
    entity.setAggregateType(record.get(WORLD_DESIGN_REVISION_LEDGER.AGGREGATE_TYPE));
    entity.setRequestedAggregateId(record.get(WORLD_DESIGN_REVISION_LEDGER.REQUESTED_AGGREGATE_ID));
    entity.setAppliedAggregateId(record.get(WORLD_DESIGN_REVISION_LEDGER.APPLIED_AGGREGATE_ID));
    entity.setResult(record.get(WORLD_DESIGN_REVISION_LEDGER.RESULT));
    entity.setAggregateEpochAfter(record.get(WORLD_DESIGN_REVISION_LEDGER.AGGREGATE_EPOCH_AFTER));
    entity.setScopeEpochAfter(record.get(WORLD_DESIGN_REVISION_LEDGER.SCOPE_EPOCH_AFTER));
    entity.setCreatedAt(record.get(WORLD_DESIGN_REVISION_LEDGER.CREATED_AT));
    return entity;
  }
}
