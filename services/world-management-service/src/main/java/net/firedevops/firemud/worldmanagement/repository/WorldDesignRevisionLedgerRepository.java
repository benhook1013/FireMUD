package net.firedevops.firemud.worldmanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignRevisionLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorldDesignRevisionLedgerRepository
    extends JpaRepository<WorldDesignRevisionLedger, Long> {
  Optional<WorldDesignRevisionLedger>
      findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
          Long tenantId,
          Long versionId,
          String commitId,
          String revisionId,
          String operationType,
          String aggregateType,
          String requestedAggregateId);
}
