package net.firedevops.firemud.accountservice.hostedterms;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-local PostgreSQL persistence; it authenticates no publisher, signer or legal evidence. */
public final class HostedTermsRepository {
  private static final String SCOPES = "account_hosted_terms_scopes";
  private static final String VERSIONS = "account_hosted_terms_catalog_versions";
  private static final String PUBLICATIONS = "account_hosted_terms_publication_operations";
  private static final String ACCEPTANCES = "account_individual_hosted_terms_acceptances";
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The owner DSLContext is retained privately and never exposed.")
  public HostedTermsRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public enum PublicationStatus {
    RECEIVED,
    SCHEDULED,
    PENDING_OWNER_SETTLEMENT,
    COMMITTED
  }

  public record PublicationOperation(
      UUID requestId,
      UUID hostedScopeId,
      byte[] requestPayload,
      String requestDigest,
      PublicationStatus status,
      UUID candidateVersionId,
      byte[] sourceChangeBinding,
      byte[] resultPayload,
      String resultDigest,
      OffsetDateTime committedAt) {
    public PublicationOperation {
      requestPayload = requestPayload.clone();
      sourceChangeBinding = sourceChangeBinding == null ? null : sourceChangeBinding.clone();
      resultPayload = resultPayload == null ? null : resultPayload.clone();
    }

    @Override
    public byte[] requestPayload() {
      return requestPayload.clone();
    }

    @Override
    public byte[] sourceChangeBinding() {
      return sourceChangeBinding == null ? null : sourceChangeBinding.clone();
    }

    @Override
    public byte[] resultPayload() {
      return resultPayload == null ? null : resultPayload.clone();
    }
  }

  public record ScopeSnapshot(
      UUID hostedScopeId,
      HostedTermsCatalogVersion current,
      OffsetDateTime databaseNow,
      PublicationOperation unsettledPublication) {}

  /** Creates only an empty serialization row; it never seeds a catalog/source baseline. */
  public void ensureScope(UUID scopeId) {
    requireWriteTransaction();
    HostedTermsEncoding.requireUuid(scopeId, "hosted scope");
    if (dsl.fetchOne("SELECT 1 FROM " + SCOPES + " WHERE hosted_scope_id = ?", scopeId) != null) {
      return;
    }
    dsl.execute(
        "INSERT INTO " + SCOPES + " (hosted_scope_id) VALUES (?) ON CONFLICT DO NOTHING", scopeId);
  }

  /** Claims a stable request identity before scope locking, then returns its immutable intent. */
  public PublicationOperation claimPublication(
      UUID requestId, UUID scopeId, byte[] requestPayload) {
    requireWriteTransaction();
    HostedTermsEncoding.requireUuid(requestId, "publication request");
    HostedTermsEncoding.requireUuid(scopeId, "hosted scope");
    byte[] bytes = HostedTermsEncoding.requireBytes(requestPayload);
    String digest = HostedTermsEncoding.digest(bytes);
    dsl.execute(
        "INSERT INTO "
            + PUBLICATIONS
            + " (request_id, hosted_scope_id, request_payload, request_digest, status)"
            + " VALUES (?, ?, ?, ?, 'RECEIVED') ON CONFLICT (request_id) DO NOTHING",
        requestId,
        scopeId,
        bytes,
        digest);
    PublicationOperation operation =
        readPublication(requestId, true)
            .orElseThrow(
                () -> new IllegalStateException("Publication request readback is unavailable"));
    if (!operation.hostedScopeId().equals(scopeId)
        || !Arrays.equals(operation.requestPayload(), bytes)
        || !operation.requestDigest().equals(digest)) {
      throw new IllegalArgumentException(
          "Publication request identity conflicts with prior evidence");
    }
    return operation;
  }

  /** Locks the canonical hosted-scope source before any sorted Draft-fence participation. */
  public ScopeSnapshot lockScope(UUID scopeId) {
    requireWriteTransaction();
    HostedTermsEncoding.requireUuid(scopeId, "hosted scope");
    Record scope =
        dsl.fetchOne("SELECT * FROM " + SCOPES + " WHERE hosted_scope_id = ? FOR UPDATE", scopeId);
    if (scope == null) {
      throw new IllegalStateException("Hosted terms scope serialization row is unavailable");
    }
    UUID currentId = scope.get("current_version_id", UUID.class);
    Long sourceVersion = scope.get("current_source_version", Long.class);
    Long generation = scope.get("current_material_generation", Long.class);
    if ((currentId == null) != (sourceVersion == null)
        || (currentId == null) != (generation == null)) {
      throw new IllegalStateException("Hosted terms current source is incomplete");
    }
    HostedTermsCatalogVersion current = currentId == null ? null : readCatalog(scopeId, currentId);
    if (current != null
        && (current.sourceVersion() != sourceVersion
            || current.materialGeneration() != generation)) {
      throw new IllegalStateException(
          "Hosted terms scope head differs from immutable catalog evidence");
    }
    OffsetDateTime now = databaseNow();
    PublicationOperation pending = readUnsettledPublication(scopeId).orElse(null);
    return new ScopeSnapshot(scopeId, current, now, pending);
  }

  public Optional<PublicationOperation> readPublication(UUID requestId, boolean forUpdate) {
    requireTransaction();
    HostedTermsEncoding.requireUuid(requestId, "publication request");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + PUBLICATIONS
                + " WHERE request_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            requestId);
    return row == null ? Optional.empty() : Optional.of(publication(row));
  }

  public Optional<PublicationOperation> readUnsettledPublication(UUID scopeId) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + PUBLICATIONS
                + " WHERE hosted_scope_id = ? AND status IN ('SCHEDULED', 'PENDING_OWNER_SETTLEMENT')"
                + " ORDER BY requested_at, request_id LIMIT 1",
            scopeId);
    return row == null ? Optional.empty() : Optional.of(publication(row));
  }

  public HostedTermsCatalogVersion readCandidate(PublicationOperation operation) {
    requireTransaction();
    Objects.requireNonNull(operation);
    if (operation.candidateVersionId() == null) {
      throw new IllegalStateException("Publication candidate is absent");
    }
    return readCatalog(operation.hostedScopeId(), operation.candidateVersionId());
  }

  public HostedTermsCatalogVersion readCatalog(UUID scopeId, UUID versionId) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + VERSIONS + " WHERE hosted_scope_id = ? AND version_id = ?",
            scopeId,
            versionId);
    if (row == null) {
      throw new IllegalStateException("Exact hosted terms catalog version is unavailable");
    }
    HostedTermsCatalogVersion version = catalog(row);
    byte[] payload = HostedTermsEncoding.catalog(version);
    if (!Arrays.equals(payload, row.get("version_payload", byte[].class))
        || !HostedTermsEncoding.digest(payload).equals(row.get("version_digest", String.class))) {
      throw new IllegalStateException(
          "Hosted terms catalog payload or digest conflicts with exact columns");
    }
    return version;
  }

  public void insertCandidate(HostedTermsCatalogVersion candidate) {
    requireWriteTransaction();
    Objects.requireNonNull(candidate);
    byte[] payload = HostedTermsEncoding.catalog(candidate);
    dsl.execute(
        "INSERT INTO "
            + VERSIONS
            + " (version_id, hosted_scope_id, predecessor_version_id, operator_legal_identity, "
            + "operator_identity_version, document_bytes, document_digest, source_version, "
            + "material_generation, materiality, materiality_evidence_reference, "
            + "materiality_evidence_version, publication_evidence_reference, "
            + "publication_evidence_version, notice_evidence_reference, notice_evidence_version, "
            + "effective_at, version_payload, version_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?, ?)",
        candidate.versionId(),
        candidate.hostedScopeId(),
        candidate.predecessorVersionId(),
        candidate.operatorLegalIdentity(),
        candidate.operatorIdentityVersion(),
        candidate.documentBytes(),
        candidate.documentDigest(),
        candidate.sourceVersion(),
        candidate.materialGeneration(),
        candidate.materiality().name(),
        candidate.materialityEvidenceReference(),
        candidate.materialityEvidenceVersion(),
        candidate.publicationEvidenceReference(),
        candidate.publicationEvidenceVersion(),
        candidate.noticeEvidenceReference(),
        candidate.noticeEvidenceVersion(),
        OffsetDateTime.ofInstant(candidate.effectiveAt(), java.time.ZoneOffset.UTC),
        payload,
        HostedTermsEncoding.digest(payload));
  }

  public void markScheduled(UUID requestId, HostedTermsCatalogVersion candidate) {
    transitionPublication(requestId, candidate, "SCHEDULED", null);
  }

  public void markOwnerSettlementPending(
      UUID requestId, HostedTermsCatalogVersion candidate, byte[] sourceChangeBinding) {
    transitionPublication(
        requestId,
        candidate,
        "PENDING_OWNER_SETTLEMENT",
        HostedTermsEncoding.requireBytes(sourceChangeBinding));
  }

  private void transitionPublication(
      UUID requestId,
      HostedTermsCatalogVersion candidate,
      String status,
      byte[] sourceChangeBinding) {
    requireWriteTransaction();
    Objects.requireNonNull(candidate);
    int updated =
        dsl.execute(
            "UPDATE "
                + PUBLICATIONS
                + " SET candidate_version_id = ?, source_change_binding = ?, status = ?"
                + " WHERE request_id = ? AND status IN ('RECEIVED', 'SCHEDULED')",
            candidate.versionId(),
            sourceChangeBinding,
            status,
            requestId);
    if (updated != 1) {
      PublicationOperation prior =
          readPublication(requestId, true)
              .orElseThrow(() -> new IllegalStateException("Publication intent disappeared"));
      if (!Objects.equals(prior.candidateVersionId(), candidate.versionId())
          || prior.status() != PublicationStatus.valueOf(status)
          || !Arrays.equals(prior.sourceChangeBinding(), sourceChangeBinding)) {
        throw new IllegalStateException(
            "Publication candidate transition differs from exact intent");
      }
    }
  }

  /** Advances operative head only after effectiveAt and every required owner terminal result. */
  public void completePublication(
      UUID requestId,
      HostedTermsCatalogVersion candidate,
      byte[] resultPayload,
      byte[] sourceChangeBinding) {
    requireWriteTransaction();
    Objects.requireNonNull(candidate);
    byte[] result = HostedTermsEncoding.requireBytes(resultPayload);
    if (!Arrays.equals(result, HostedTermsEncoding.catalog(candidate))) {
      throw new IllegalArgumentException(
          "Publication result must be exact candidate catalog bytes");
    }
    int headUpdated =
        dsl.execute(
            "UPDATE "
                + SCOPES
                + " SET current_version_id = ?, current_source_version = ?, "
                + "current_material_generation = ? WHERE hosted_scope_id = ? "
                + "AND current_version_id IS NOT DISTINCT FROM ? "
                + "AND current_source_version IS NOT DISTINCT FROM ? "
                + "AND current_material_generation IS NOT DISTINCT FROM ?",
            candidate.versionId(),
            candidate.sourceVersion(),
            candidate.materialGeneration(),
            candidate.hostedScopeId(),
            candidate.predecessorVersionId(),
            candidate.predecessorVersionId() == null ? null : candidate.sourceVersion() - 1,
            candidate.predecessorVersionId() == null
                ? null
                : candidate.materialGeneration()
                    - (candidate.materiality() == HostedTermsCatalogVersion.Materiality.MATERIAL
                        ? 1
                        : 0));
    if (headUpdated != 1) {
      throw new IllegalStateException("Hosted terms operative head changed from exact predecessor");
    }
    int operationUpdated =
        dsl.execute(
            "UPDATE "
                + PUBLICATIONS
                + " SET candidate_version_id = ?, source_change_binding = ?, status = 'COMMITTED', "
                + "result_payload = ?, result_digest = ?, committed_at = CURRENT_TIMESTAMP "
                + "WHERE request_id = ? AND status IN ('RECEIVED', 'SCHEDULED', 'PENDING_OWNER_SETTLEMENT')",
            candidate.versionId(),
            sourceChangeBinding,
            result,
            HostedTermsEncoding.digest(result),
            requestId);
    if (operationUpdated != 1) {
      throw new IllegalStateException("Publication operation did not commit exactly once");
    }
  }

  public UUID lockPartyAccount(UUID creatorPartyId) {
    requireWriteTransaction();
    HostedTermsEncoding.requireUuid(creatorPartyId, "creator party");
    Record row =
        dsl.fetchOne(
            "SELECT account_uuid FROM account_individual_creator_party_sources "
                + "WHERE creator_party_id = ? FOR UPDATE",
            creatorPartyId);
    if (row == null) {
      throw new IllegalStateException("Current individual creator party source is unavailable");
    }
    UUID accountId = row.get("account_uuid", UUID.class);
    return HostedTermsEncoding.requireUuid(accountId, "party-owning Account");
  }

  public Optional<IndividualHostedTermsAcceptance> readAcceptanceByRequest(UUID actionRequestId) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + ACCEPTANCES + " WHERE action_request_id = ?", actionRequestId);
    return row == null ? Optional.empty() : Optional.of(acceptance(row));
  }

  /** Latest acceptance on the active catalog ancestry for this party and material generation. */
  public Optional<IndividualHostedTermsAcceptance> readCurrentAcceptance(
      UUID creatorPartyId, UUID hostedScopeId, HostedTermsCatalogVersion current) {
    requireTransaction();
    Record row =
        dsl.fetchOne(
            "WITH RECURSIVE active_terms(version_id, predecessor_version_id) AS ("
                + " SELECT version_id, predecessor_version_id FROM "
                + VERSIONS
                + " WHERE hosted_scope_id = ? AND version_id = ?"
                + " UNION ALL SELECT prior.version_id, prior.predecessor_version_id FROM "
                + VERSIONS
                + " prior JOIN active_terms active ON prior.version_id = active.predecessor_version_id"
                + " WHERE prior.hosted_scope_id = ?)"
                + " SELECT acceptance.* FROM "
                + ACCEPTANCES
                + " acceptance JOIN active_terms active ON active.version_id = acceptance.terms_version_id"
                + " WHERE acceptance.creator_party_id = ? AND acceptance.hosted_scope_id = ?"
                + " AND acceptance.material_generation = ? AND acceptance.operator_legal_identity = ?"
                + " AND acceptance.operator_identity_version = ?"
                + " ORDER BY acceptance.accepted_at DESC, acceptance.evidence_id LIMIT 1",
            hostedScopeId,
            current.versionId(),
            hostedScopeId,
            creatorPartyId,
            hostedScopeId,
            current.materialGeneration(),
            current.operatorLegalIdentity(),
            current.operatorIdentityVersion());
    return row == null ? Optional.empty() : Optional.of(acceptance(row));
  }

  /** Inserts acceptance from owner-read sources; accepted_at is assigned by PostgreSQL. */
  public IndividualHostedTermsAcceptance insertAcceptance(
      UUID evidenceId,
      AccountHostedTermsService.AcceptanceAction action,
      IndividualCreatorPartySource party,
      HostedTermsCatalogVersion shownVersion,
      byte[] actionEvidence) {
    requireWriteTransaction();
    HostedTermsEncoding.requireUuid(evidenceId, "acceptance evidence");
    Objects.requireNonNull(action);
    Objects.requireNonNull(party);
    Objects.requireNonNull(shownVersion);
    byte[] sourceBytes = HostedTermsEncoding.individualParty(party);
    byte[] actionBytes = HostedTermsEncoding.requireBytes(actionEvidence);
    dsl.execute(
        "INSERT INTO "
            + ACCEPTANCES
            + " (evidence_id, action_request_id, creator_party_id, account_uuid, hosted_scope_id, "
            + "terms_version_id, document_digest, operator_legal_identity, operator_identity_version, "
            + "source_version, material_generation, individual_party_source, "
            + "individual_party_source_digest, affirmative_action_evidence, affirmative_action_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (action_request_id) DO NOTHING",
        evidenceId,
        action.actionRequestId(),
        party.creatorPartyId(),
        party.accountId(),
        shownVersion.hostedScopeId(),
        shownVersion.versionId(),
        shownVersion.documentDigest(),
        shownVersion.operatorLegalIdentity(),
        shownVersion.operatorIdentityVersion(),
        shownVersion.sourceVersion(),
        shownVersion.materialGeneration(),
        sourceBytes,
        HostedTermsEncoding.digest(sourceBytes),
        actionBytes,
        HostedTermsEncoding.digest(actionBytes));
    IndividualHostedTermsAcceptance stored =
        readAcceptanceByRequest(action.actionRequestId())
            .orElseThrow(() -> new IllegalStateException("Affirmative acceptance did not persist"));
    if (!stored.creatorPartyId().equals(party.creatorPartyId())
        || !stored.accountId().equals(party.accountId())
        || !stored.hostedScopeId().equals(shownVersion.hostedScopeId())
        || !stored.termsVersionId().equals(shownVersion.versionId())
        || !stored.documentDigest().equals(shownVersion.documentDigest())
        || !stored.operatorLegalIdentity().equals(shownVersion.operatorLegalIdentity())
        || stored.operatorIdentityVersion() != shownVersion.operatorIdentityVersion()
        || stored.sourceVersion() != shownVersion.sourceVersion()
        || stored.materialGeneration() != shownVersion.materialGeneration()
        || !Arrays.equals(stored.individualPartySource(), sourceBytes)
        || !stored.individualPartySourceDigest().equals(HostedTermsEncoding.digest(sourceBytes))
        || !Arrays.equals(stored.affirmativeActionEvidence(), actionBytes)
        || !stored.affirmativeActionDigest().equals(HostedTermsEncoding.digest(actionBytes))) {
      throw new IllegalArgumentException(
          "Acceptance request identity conflicts with stored evidence");
    }
    return stored;
  }

  private OffsetDateTime databaseNow() {
    // Transaction timestamp can predate a row-lock wait and incorrectly grandfather an expired
    // terms version. Sample PostgreSQL wall time only after the source locks are held.
    Record row = dsl.fetchOne("SELECT clock_timestamp() AS database_now");
    if (row == null || row.get("database_now", OffsetDateTime.class) == null) {
      throw new IllegalStateException("Account database time is unavailable");
    }
    return row.get("database_now", OffsetDateTime.class);
  }

  private PublicationOperation publication(Record row) {
    byte[] request = row.get("request_payload", byte[].class);
    String requestDigest = row.get("request_digest", String.class);
    if (!HostedTermsEncoding.digest(request).equals(requestDigest)) {
      throw new IllegalStateException("Stored publication request digest conflicts");
    }
    PublicationOperation operation =
        new PublicationOperation(
            row.get("request_id", UUID.class),
            row.get("hosted_scope_id", UUID.class),
            request,
            requestDigest,
            PublicationStatus.valueOf(row.get("status", String.class)),
            row.get("candidate_version_id", UUID.class),
            row.get("source_change_binding", byte[].class),
            row.get("result_payload", byte[].class),
            row.get("result_digest", String.class),
            row.get("committed_at", OffsetDateTime.class));
    if (operation.status() == PublicationStatus.COMMITTED) {
      HostedTermsCatalogVersion candidate = readCandidate(operation);
      if (!Arrays.equals(operation.resultPayload(), HostedTermsEncoding.catalog(candidate))
          || !HostedTermsEncoding.digest(operation.resultPayload())
              .equals(operation.resultDigest())) {
        throw new IllegalStateException(
            "Committed publication result differs from immutable catalog");
      }
    }
    return operation;
  }

  private HostedTermsCatalogVersion catalog(Record row) {
    HostedTermsCatalogVersion version =
        new HostedTermsCatalogVersion(
            row.get("version_id", UUID.class),
            row.get("hosted_scope_id", UUID.class),
            row.get("predecessor_version_id", UUID.class),
            row.get("operator_legal_identity", String.class),
            row.get("operator_identity_version", Long.class),
            row.get("document_bytes", byte[].class),
            row.get("document_digest", String.class),
            row.get("source_version", Long.class),
            row.get("material_generation", Long.class),
            HostedTermsCatalogVersion.Materiality.valueOf(row.get("materiality", String.class)),
            row.get("materiality_evidence_reference", String.class),
            row.get("materiality_evidence_version", Long.class),
            row.get("publication_evidence_reference", String.class),
            row.get("publication_evidence_version", Long.class),
            row.get("notice_evidence_reference", String.class),
            row.get("notice_evidence_version", Long.class),
            row.get("effective_at", OffsetDateTime.class).toInstant());
    return version;
  }

  private IndividualHostedTermsAcceptance acceptance(Record row) {
    byte[] individualPartySource = row.get("individual_party_source", byte[].class);
    String individualPartySourceDigest = row.get("individual_party_source_digest", String.class);
    byte[] affirmativeActionEvidence = row.get("affirmative_action_evidence", byte[].class);
    String affirmativeActionDigest = row.get("affirmative_action_digest", String.class);
    if (!HostedTermsEncoding.digest(affirmativeActionEvidence).equals(affirmativeActionDigest)
        || !HostedTermsEncoding.digest(individualPartySource).equals(individualPartySourceDigest)) {
      throw new IllegalStateException("Stored acceptance evidence digest conflicts");
    }
    return new IndividualHostedTermsAcceptance(
        row.get("evidence_id", UUID.class),
        row.get("action_request_id", UUID.class),
        row.get("creator_party_id", UUID.class),
        row.get("account_uuid", UUID.class),
        row.get("hosted_scope_id", UUID.class),
        row.get("terms_version_id", UUID.class),
        row.get("document_digest", String.class),
        row.get("operator_legal_identity", String.class),
        row.get("operator_identity_version", Long.class),
        row.get("source_version", Long.class),
        row.get("material_generation", Long.class),
        individualPartySource,
        individualPartySourceDigest,
        affirmativeActionEvidence,
        affirmativeActionDigest,
        row.get("accepted_at", OffsetDateTime.class));
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Hosted terms repository requires an owner transaction");
    }
  }

  private void requireWriteTransaction() {
    requireTransaction();
    Record isolation = dsl.fetchOne("SHOW transaction_isolation");
    if (isolation == null || !"read committed".equals(isolation.get(0, String.class))) {
      throw new IllegalStateException("Hosted terms owner writes require READ COMMITTED isolation");
    }
  }
}
