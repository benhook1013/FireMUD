package net.firedevops.firemud.accountservice.creatorparty;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered owner-local persistence prerequisite, not an authenticated association producer.
 *
 * <p>No provisioning method is provided. A future protected producer must authenticate the Account,
 * independently obtain exact Game Design committed readback and approved individual verification,
 * and participate in current source ordering before invoking this storage primitive. Supplied DTOs
 * and a V64 receipt alone do not authenticate a caller. These immutable initial sources cannot yet
 * participate in revocation/terms ordering; hosted authoring currentness remains denied.
 */
public final class IndividualCreatorPartyRepository {
  private final DSLContext dsl;
  private final FreshTenantIdentityAssociationRepository freshTenants;
  private final AccountTenantCreationBootstrapOperationRepository bootstraps;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Owner transaction collaborators are retained privately and never exposed.")
  public IndividualCreatorPartyRepository(
      DSLContext dsl,
      FreshTenantIdentityAssociationRepository freshTenants,
      AccountTenantCreationBootstrapOperationRepository bootstraps) {
    this.dsl = Objects.requireNonNull(dsl);
    this.freshTenants = Objects.requireNonNull(freshTenants);
    this.bootstraps = Objects.requireNonNull(bootstraps);
  }

  /** Reads complete exact source bytes; local VERIFIED status is not legal provisioning proof. */
  public IndividualCreatorPartySource readIndividualSource(UUID partyId, UUID accountId) {
    requireTransaction();
    CreatorPartyEncoding.requireUuid(partyId);
    CreatorPartyEncoding.requireUuid(accountId);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_individual_creator_party_sources "
                + "WHERE creator_party_id = ? FOR UPDATE",
            partyId);
    if (row == null) {
      throw new IllegalStateException("Individual party source is absent");
    }
    IndividualCreatorPartySource source =
        new IndividualCreatorPartySource(
            row.get("creator_party_id", UUID.class),
            row.get("account_uuid", UUID.class),
            VerificationStatus.valueOf(row.get("verification_status", String.class)),
            row.get("identity_version", Long.class),
            row.get("policy_reference", String.class),
            row.get("policy_version", Long.class),
            row.get("verification_evidence_reference", String.class),
            row.get("verification_evidence_version", Long.class),
            row.get("source_version", Long.class));
    byte[] payload = CreatorPartyEncoding.party(source);
    if (!accountId.equals(source.accountId())
        || !Arrays.equals(payload, row.get("source_payload", byte[].class))
        || !CreatorPartyEncoding.digest(payload).equals(row.get("source_digest", String.class))) {
      throw new IllegalStateException(
          "Individual party source identity or exact evidence conflicts");
    }
    return source;
  }

  /**
   * Stores an exact initial association under caller-owned Account transaction. The supplied
   * qualification must match persisted V64 and fresh provenance, but this method authenticates no
   * caller and is deliberately not registered or exposed. No record is inserted on negative paths.
   */
  public AssociationReceipt associateFresh(
      UUID requestId,
      FreshTenantCreatorEvidence creator,
      IndividualCreatorPartySource expectedParty) {
    requireTransaction();
    byte[] request = CreatorPartyEncoding.request(requestId, creator, expectedParty);
    UUID tenantId = creator.creationEvidence().canonicalTenantId();
    lockTenant(tenantId);
    Record prior = operation(requestId);
    if (prior != null) {
      if (!Arrays.equals(request, prior.get("request_payload", byte[].class))) {
        throw new AssociationConflictException("Association request identity changed its binding");
      }
      return verifyReceipt(prior, requestId, creator, expectedParty);
    }
    requireFreshCreation(creator);
    IndividualCreatorPartySource current =
        readIndividualSource(expectedParty.creatorPartyId(), creator.initiatingAccountId());
    if (!current.equals(expectedParty) || !current.locallyVerified()) {
      throw new IllegalStateException(
          "Exact independently verified own individual source is absent");
    }
    if (historyCount(tenantId) != 0
        || dsl.fetchOne(
                "SELECT request_id FROM account_fresh_creator_party_association_operations "
                    + "WHERE tenant_uuid = ?",
                tenantId)
            != null) {
      throw new AssociationConflictException(
          "Tenant has prior creator-party association or history");
    }
    UUID historyId = UUID.randomUUID();
    byte[] result = CreatorPartyEncoding.result(historyId, request);
    String resultDigest = CreatorPartyEncoding.digest(result);
    dsl.execute(
        "INSERT INTO account_tenant_creator_party_history "
            + "(history_id, tenant_uuid, origin, creator_party_id, source_version, "
            + "evidence_payload, evidence_digest) VALUES (?, ?, 'FRESH_INITIAL', ?, 1, ?, ?)",
        historyId,
        tenantId,
        current.creatorPartyId(),
        result,
        resultDigest);
    dsl.execute(
        "INSERT INTO account_fresh_creator_party_association_operations "
            + "(request_id, tenant_uuid, initiating_account_uuid, creator_party_id, "
            + "creation_operation_id, creator_evidence_payload, party_source_payload, "
            + "request_payload, request_digest, history_id, result_payload, result_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        requestId,
        tenantId,
        creator.initiatingAccountId(),
        current.creatorPartyId(),
        creator.creationEvidence().operationId(),
        CreatorPartyEncoding.creation(creator),
        CreatorPartyEncoding.party(current),
        request,
        CreatorPartyEncoding.digest(request),
        historyId,
        result,
        resultDigest);
    return verifyReceipt(operation(requestId), requestId, creator, current);
  }

  /**
   * Exact local initial association readback; later retained/tombstone state denies this reader.
   */
  public AssociationReceipt readInitialAssociation(
      UUID requestId,
      FreshTenantCreatorEvidence creator,
      IndividualCreatorPartySource expectedParty) {
    requireTransaction();
    lockTenant(creator.creationEvidence().canonicalTenantId());
    requireFreshCreation(creator);
    IndividualCreatorPartySource source =
        readIndividualSource(expectedParty.creatorPartyId(), creator.initiatingAccountId());
    if (!source.equals(expectedParty) || !source.locallyVerified()) {
      throw new IllegalStateException("Current exact individual source is unavailable");
    }
    AssociationReceipt receipt = verifyReceipt(operation(requestId), requestId, creator, source);
    if (historyCount(receipt.tenantId()) != 1) {
      throw new IllegalStateException(
          "Initial association reader cannot resolve later party history");
    }
    return receipt;
  }

  /** This slice never provides commit-bound party/terms authorization. */
  public void requireHostedAuthoringCurrentness() {
    throw new IllegalStateException(
        "Authenticated caller, approved provisioning, party source ordering and hosted terms are unavailable");
  }

  private void lockTenant(UUID tenantId) {
    CreatorPartyEncoding.requireUuid(tenantId);
    Record isolation = dsl.fetchOne("SHOW transaction_isolation");
    if (isolation == null) {
      throw new IllegalStateException("Account transaction isolation readback is unavailable");
    }
    if (!"read committed".equals(isolation.get(0, String.class))) {
      throw new IllegalStateException(
          "Initial party association requires READ COMMITTED owner transaction");
    }
    Record claim =
        dsl.fetchOne(
            "SELECT identity_kind FROM account_canonical_tenant_identity_claims "
                + "WHERE canonical_tenant_id = ? FOR UPDATE",
            tenantId);
    if (claim == null || !"FRESH_GAME_DESIGN".equals(claim.get("identity_kind", String.class))) {
      throw new IllegalStateException("Fresh canonical tenant provenance is absent");
    }
  }

  private void requireFreshCreation(FreshTenantCreatorEvidence creator) {
    if (!freshTenants
        .read(creator.creationEvidence().canonicalTenantId())
        .filter(creator.creationEvidence()::equals)
        .isPresent()) {
      throw new IllegalStateException("Exact committed fresh tenant provenance is absent");
    }
    StoredOperation bootstrap =
        bootstraps
            .findForUpdate(creator.accountAuthorizationOperationId())
            .orElseThrow(
                () -> new IllegalStateException("Persisted creator qualification is absent"));
    if (!"COMMITTED".equals(bootstrap.status())
        || !creator.initiatingAccountId().equals(bootstrap.initiatingAccountUuid())
        || !creator.creationEvidence().canonicalTenantId().equals(bootstrap.tenantUuid())
        || !creator.creationEvidence().creationRequestId().equals(bootstrap.creationRequestId())
        || !creator.creationEvidence().operationId().equals(bootstrap.creationOperationId())
        || !creator
            .accountAuthorizationOperationId()
            .equals(bootstrap.accountAuthorizationOperationId())
        || !creator.accountAuthorizationDigest().equals(bootstrap.accountAuthorizationDigest())
        || !creator.evidenceDigest().equals(bootstrap.creatorEvidenceDigest())
        || !Arrays.equals(
            CreatorPartyEncoding.creation(creator), bootstrap.creatorEvidencePayload())) {
      throw new IllegalStateException("Persisted committed creator qualification conflicts");
    }
  }

  private Record operation(UUID requestId) {
    CreatorPartyEncoding.requireUuid(requestId);
    return dsl.fetchOne(
        "SELECT * FROM account_fresh_creator_party_association_operations WHERE request_id = ?",
        requestId);
  }

  private long historyCount(UUID tenantId) {
    Record history =
        dsl.fetchOne(
            "SELECT count(*) AS count FROM account_tenant_creator_party_history WHERE tenant_uuid = ?",
            tenantId);
    if (history == null) {
      throw new IllegalStateException("Creator-party history count readback is unavailable");
    }
    Long count = history.get("count", Long.class);
    if (count == null || count < 0) {
      throw new IllegalStateException("Creator-party history count is incomplete or invalid");
    }
    return count;
  }

  private AssociationReceipt verifyReceipt(
      Record row,
      UUID requestId,
      FreshTenantCreatorEvidence creator,
      IndividualCreatorPartySource party) {
    if (row == null) {
      throw new IllegalStateException("Immutable association receipt is absent");
    }
    UUID historyId = row.get("history_id", UUID.class);
    byte[] request = CreatorPartyEncoding.request(requestId, creator, party);
    byte[] result = CreatorPartyEncoding.result(historyId, request);
    UUID tenantId = creator.creationEvidence().canonicalTenantId();
    String resultDigest = CreatorPartyEncoding.digest(result);
    Record history =
        dsl.fetchOne(
            "SELECT * FROM account_tenant_creator_party_history WHERE history_id = ?", historyId);
    if (!requestId.equals(row.get("request_id", UUID.class))
        || !tenantId.equals(row.get("tenant_uuid", UUID.class))
        || !creator.initiatingAccountId().equals(row.get("initiating_account_uuid", UUID.class))
        || !party.creatorPartyId().equals(row.get("creator_party_id", UUID.class))
        || !creator
            .creationEvidence()
            .operationId()
            .equals(row.get("creation_operation_id", UUID.class))
        || !Arrays.equals(
            CreatorPartyEncoding.creation(creator),
            row.get("creator_evidence_payload", byte[].class))
        || !Arrays.equals(
            CreatorPartyEncoding.party(party), row.get("party_source_payload", byte[].class))
        || !Arrays.equals(request, row.get("request_payload", byte[].class))
        || !CreatorPartyEncoding.digest(request).equals(row.get("request_digest", String.class))
        || !Arrays.equals(result, row.get("result_payload", byte[].class))
        || !resultDigest.equals(row.get("result_digest", String.class))
        || row.get("committed_at") == null
        || history == null
        || !"FRESH_INITIAL".equals(history.get("origin", String.class))
        || !tenantId.equals(history.get("tenant_uuid", UUID.class))
        || !party.creatorPartyId().equals(history.get("creator_party_id", UUID.class))
        || !Long.valueOf(1).equals(history.get("source_version", Long.class))
        || !Arrays.equals(result, history.get("evidence_payload", byte[].class))
        || !resultDigest.equals(history.get("evidence_digest", String.class))) {
      throw new IllegalStateException(
          "Immutable association readback is incomplete or contradictory");
    }
    return new AssociationReceipt(
        requestId, tenantId, party.creatorPartyId(), historyId, resultDigest);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Caller-owned writable Account transaction is required");
    }
  }

  /** Historical immutable result only; grants no caller, content or gameplay permission. */
  public record AssociationReceipt(
      UUID requestId, UUID tenantId, UUID creatorPartyId, UUID historyId, String resultDigest) {}

  public static final class AssociationConflictException extends IllegalStateException {
    public AssociationConflictException(String message) {
      super(message);
    }
  }
}
