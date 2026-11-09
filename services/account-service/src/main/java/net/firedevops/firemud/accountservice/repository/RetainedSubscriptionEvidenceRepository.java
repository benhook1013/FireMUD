package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_RETAINED_SUBSCRIPTION_EVIDENCE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.accountservice.entity.RetainedSubscriptionEvidence;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Historical evidence for Account retention/deletion checks only; no current-tenant selector. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RetainedSubscriptionEvidenceRepository {
  private final DSLContext dsl;

  public RetainedSubscriptionEvidenceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<RetainedSubscriptionEvidence> findHistoryByAccountId(Long accountId) {
    var evidence = ACCOUNT_RETAINED_SUBSCRIPTION_EVIDENCE;
    return dsl.selectFrom(evidence)
        .where(evidence.ACCOUNT_ID.eq(accountId))
        .orderBy(evidence.ID.asc())
        .fetch(
            row ->
                new RetainedSubscriptionEvidence(
                    row.get(evidence.ID),
                    row.get(evidence.ACCOUNT_ID),
                    row.get(evidence.PLAN_ID),
                    row.get(evidence.STATUS),
                    row.get(evidence.STARTED_AT),
                    row.get(evidence.ENDED_AT),
                    row.get(evidence.TENANT_ID),
                    row.get(evidence.ENTITLEMENT_VERSION),
                    row.get(evidence.TENANT_AUTHORITY_GENERATION)));
  }
}
