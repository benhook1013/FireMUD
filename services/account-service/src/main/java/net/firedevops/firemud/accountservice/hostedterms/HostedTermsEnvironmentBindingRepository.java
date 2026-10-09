package net.firedevops.firemud.accountservice.hostedterms;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding.PublicationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-local immutable binding receipt/head and exact retry storage. */
public final class HostedTermsEnvironmentBindingRepository {
  private static final String HEADS = "account_hosted_terms_environment_binding_heads";
  private static final String BINDINGS = "account_hosted_terms_environment_bindings";
  private static final String PUBLICATIONS =
      "account_hosted_terms_environment_binding_publications";
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Owner DSLContext is retained privately and never exposed.")
  public HostedTermsEnvironmentBindingRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public enum PublicationStatus {
    RECEIVED,
    PENDING_OWNER_SETTLEMENT,
    COMMITTED
  }

  public record PublicationOperation(
      UUID requestId,
      String environmentBoundary,
      byte[] requestPayload,
      String requestDigest,
      PublicationStatus status,
      UUID candidateBindingId,
      byte[] sourceChangeBinding,
      byte[] resultPayload,
      String resultDigest) {
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

  public record HeadSnapshot(
      String environmentBoundary,
      HostedTermsEnvironmentBinding current,
      PublicationOperation unsettledPublication) {}

  /** Creates only an empty serialization row; it does not invent a binding baseline. */
  public void ensureHead(String environmentBoundary) {
    requireWriteTransaction();
    String boundary = requireBoundary(environmentBoundary);
    if (dsl.fetchOne("SELECT 1 FROM " + HEADS + " WHERE environment_boundary = ?", boundary)
        != null) {
      return;
    }
    dsl.execute(
        "INSERT INTO " + HEADS + " (environment_boundary) VALUES (?) ON CONFLICT DO NOTHING",
        boundary);
  }

  /** Stable publication request identity binds every owner-authenticated field and predecessor. */
  public PublicationOperation claimPublication(
      UUID requestId, String environmentBoundary, byte[] exactRequestPayload) {
    requireWriteTransaction();
    HostedTermsEncoding.requireUuid(requestId, "environment binding publication request");
    String boundary = requireBoundary(environmentBoundary);
    byte[] request = HostedTermsEncoding.requireBytes(exactRequestPayload);
    String digest = HostedTermsEncoding.digest(request);
    dsl.execute(
        "INSERT INTO "
            + PUBLICATIONS
            + " (request_id, environment_boundary, request_payload, request_digest, status) "
            + "VALUES (?, ?, ?, ?, 'RECEIVED') ON CONFLICT (request_id) DO NOTHING",
        requestId,
        boundary,
        request,
        digest);
    PublicationOperation stored =
        readPublication(requestId, true)
            .orElseThrow(
                () -> new IllegalStateException("Binding publication intent readback is absent"));
    if (!stored.environmentBoundary().equals(boundary)
        || !Arrays.equals(stored.requestPayload(), request)
        || !stored.requestDigest().equals(digest)) {
      throw new IllegalArgumentException(
          "Environment binding request identity conflicts with exact prior evidence");
    }
    return stored;
  }

  /** Locks the canonical environment head before catalog and Draft source participation locks. */
  public HeadSnapshot lockHead(String environmentBoundary) {
    requireWriteTransaction();
    String boundary = requireBoundary(environmentBoundary);
    Record head =
        dsl.fetchOne(
            "SELECT * FROM " + HEADS + " WHERE environment_boundary = ? FOR UPDATE", boundary);
    if (head == null) {
      throw new IllegalStateException("Environment binding head is unavailable");
    }
    UUID bindingId = head.get("current_binding_id", UUID.class);
    Long sourceVersion = head.get("current_source_version", Long.class);
    if ((bindingId == null) != (sourceVersion == null)) {
      throw new IllegalStateException("Environment binding head is incomplete");
    }
    HostedTermsEnvironmentBinding current =
        bindingId == null ? null : readBinding(boundary, bindingId);
    if (current != null && current.sourceVersion() != sourceVersion) {
      throw new IllegalStateException("Environment binding head differs from immutable history");
    }
    return new HeadSnapshot(boundary, current, readUnsettledPublication(boundary).orElse(null));
  }

  public Optional<PublicationOperation> readPublication(UUID requestId, boolean forUpdate) {
    requireTransaction();
    HostedTermsEncoding.requireUuid(requestId, "environment binding publication request");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + PUBLICATIONS
                + " WHERE request_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            requestId);
    return row == null ? Optional.empty() : Optional.of(publication(row));
  }

  public Optional<PublicationOperation> readUnsettledPublication(String environmentBoundary) {
    requireTransaction();
    String boundary = requireBoundary(environmentBoundary);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + PUBLICATIONS
                + " WHERE environment_boundary = ? AND status IN ('RECEIVED', 'PENDING_OWNER_SETTLEMENT')"
                + " ORDER BY requested_at, request_id LIMIT 1",
            boundary);
    return row == null ? Optional.empty() : Optional.of(publication(row));
  }

  public HostedTermsEnvironmentBinding readCandidate(PublicationOperation operation) {
    requireTransaction();
    Objects.requireNonNull(operation);
    if (operation.candidateBindingId() == null) {
      throw new IllegalStateException("Environment binding candidate is absent");
    }
    return readBinding(operation.environmentBoundary(), operation.candidateBindingId());
  }

  public HostedTermsEnvironmentBinding readBinding(String environmentBoundary, UUID bindingId) {
    requireTransaction();
    String boundary = requireBoundary(environmentBoundary);
    HostedTermsEncoding.requireUuid(bindingId, "environment binding receipt");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + BINDINGS + " WHERE environment_boundary = ? AND binding_id = ?",
            boundary,
            bindingId);
    if (row == null) {
      throw new IllegalStateException("Exact environment binding receipt is unavailable");
    }
    HostedTermsEnvironmentBinding binding = binding(row);
    byte[] payload = HostedTermsEnvironmentBindingEncoding.receipt(binding);
    if (!Arrays.equals(payload, row.get("binding_payload", byte[].class))
        || !HostedTermsEncoding.digest(payload).equals(row.get("binding_digest", String.class))) {
      throw new IllegalStateException("Environment binding payload or digest conflicts");
    }
    return binding;
  }

  /** Candidate insertion follows the exact current head, and SQL independently enforces it. */
  public HostedTermsEnvironmentBinding insertCandidate(
      UUID bindingId,
      UUID requestId,
      PublicationEvidence evidence,
      HostedTermsEnvironmentBinding predecessor) {
    requireWriteTransaction();
    HostedTermsEncoding.requireUuid(bindingId, "environment binding receipt");
    HostedTermsEncoding.requireUuid(requestId, "environment binding publication request");
    Objects.requireNonNull(evidence);
    if (!Objects.equals(
            evidence.predecessorBindingId(), predecessor == null ? null : predecessor.bindingId())
        || !Objects.equals(
            evidence.predecessorSourceVersion(),
            predecessor == null ? null : predecessor.sourceVersion())) {
      throw new IllegalArgumentException(
          "Candidate must extend the exact authenticated environment predecessor");
    }
    byte[] request = HostedTermsEnvironmentBindingEncoding.publication(requestId, evidence);
    long version = predecessor == null ? 1 : Math.addExact(predecessor.sourceVersion(), 1);
    HostedTermsEnvironmentBinding binding =
        new HostedTermsEnvironmentBinding(
            bindingId,
            requestId,
            evidence.environmentBoundary(),
            evidence.hostedScopeId(),
            evidence.operatorLegalIdentity(),
            evidence.operatorIdentityVersion(),
            evidence.catalogVersionId(),
            evidence.catalogSourceVersion(),
            evidence.authenticatedPublisherIdentity(),
            evidence.publicationEventIdentity(),
            HostedTermsEncoding.digest(request),
            predecessor == null ? null : predecessor.bindingId(),
            predecessor == null ? null : predecessor.sourceVersion(),
            version);
    byte[] payload = HostedTermsEnvironmentBindingEncoding.receipt(binding);
    dsl.execute(
        "INSERT INTO "
            + BINDINGS
            + " (binding_id, publication_request_id, environment_boundary, hosted_scope_id, operator_legal_identity, "
            + "operator_identity_version, catalog_version_id, catalog_source_version, "
            + "authenticated_publisher_identity, publication_event_identity, "
            + "publication_evidence_digest, predecessor_binding_id, predecessor_source_version, "
            + "source_version, binding_payload, binding_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.bindingId(),
        binding.publicationRequestId(),
        binding.environmentBoundary(),
        binding.hostedScopeId(),
        binding.operatorLegalIdentity(),
        binding.operatorIdentityVersion(),
        binding.catalogVersionId(),
        binding.catalogSourceVersion(),
        binding.authenticatedPublisherIdentity(),
        binding.publicationEventIdentity(),
        binding.publicationEvidenceDigest(),
        binding.predecessorBindingId(),
        binding.predecessorSourceVersion(),
        binding.sourceVersion(),
        payload,
        HostedTermsEncoding.digest(payload));
    return readBinding(binding.environmentBoundary(), binding.bindingId());
  }

  public void markOwnerSettlementPending(
      UUID requestId, HostedTermsEnvironmentBinding candidate, byte[] sourceChangeBinding) {
    transitionPublication(
        requestId,
        candidate,
        "PENDING_OWNER_SETTLEMENT",
        HostedTermsEncoding.requireBytes(sourceChangeBinding));
  }

  private void transitionPublication(
      UUID requestId,
      HostedTermsEnvironmentBinding candidate,
      String status,
      byte[] sourceChangeBinding) {
    requireWriteTransaction();
    Objects.requireNonNull(candidate);
    int updated =
        dsl.execute(
            "UPDATE "
                + PUBLICATIONS
                + " SET candidate_binding_id = ?, source_change_binding = ?, status = ?"
                + " WHERE request_id = ? AND status = 'RECEIVED'",
            candidate.bindingId(),
            sourceChangeBinding,
            status,
            requestId);
    if (updated != 1) {
      PublicationOperation prior =
          readPublication(requestId, true)
              .orElseThrow(() -> new IllegalStateException("Binding publication disappeared"));
      if (!Objects.equals(prior.candidateBindingId(), candidate.bindingId())
          || prior.status() != PublicationStatus.valueOf(status)
          || !Arrays.equals(prior.sourceChangeBinding(), sourceChangeBinding)) {
        throw new IllegalStateException("Binding publication transition differs from intent");
      }
    }
  }

  /** Commits the head and exact result in the same transaction as Draft source settlement. */
  public void completePublication(
      UUID requestId,
      HostedTermsEnvironmentBinding candidate,
      byte[] sourceChangeBinding,
      HostedTermsEnvironmentBinding predecessor) {
    requireWriteTransaction();
    Objects.requireNonNull(candidate);
    byte[] payload = HostedTermsEnvironmentBindingEncoding.receipt(candidate);
    int operationUpdated =
        dsl.execute(
            "UPDATE "
                + PUBLICATIONS
                + " SET candidate_binding_id = ?, source_change_binding = ?, status = 'COMMITTED', "
                + "result_payload = ?, result_digest = ?, committed_at = clock_timestamp() "
                + "WHERE request_id = ? AND status IN ('RECEIVED', 'PENDING_OWNER_SETTLEMENT')",
            candidate.bindingId(),
            sourceChangeBinding,
            payload,
            HostedTermsEncoding.digest(payload),
            requestId);
    if (operationUpdated != 1) {
      throw new IllegalStateException("Binding publication did not commit exactly once");
    }
    int headUpdated =
        dsl.execute(
            "UPDATE "
                + HEADS
                + " SET current_binding_id = ?, current_source_version = ? "
                + "WHERE environment_boundary = ? AND current_binding_id IS NOT DISTINCT FROM ? "
                + "AND current_source_version IS NOT DISTINCT FROM ?",
            candidate.bindingId(),
            candidate.sourceVersion(),
            candidate.environmentBoundary(),
            predecessor == null ? null : predecessor.bindingId(),
            predecessor == null ? null : predecessor.sourceVersion());
    if (headUpdated != 1) {
      throw new IllegalStateException("Environment binding head changed from exact predecessor");
    }
  }

  private PublicationOperation publication(Record row) {
    byte[] request = row.get("request_payload", byte[].class);
    String digest = row.get("request_digest", String.class);
    if (!HostedTermsEncoding.digest(request).equals(digest)) {
      throw new IllegalStateException("Stored binding publication request digest conflicts");
    }
    PublicationOperation operation =
        new PublicationOperation(
            row.get("request_id", UUID.class),
            row.get("environment_boundary", String.class),
            request,
            digest,
            PublicationStatus.valueOf(row.get("status", String.class)),
            row.get("candidate_binding_id", UUID.class),
            row.get("source_change_binding", byte[].class),
            row.get("result_payload", byte[].class),
            row.get("result_digest", String.class));
    if ((operation.candidateBindingId() == null)
        != (operation.status() == PublicationStatus.RECEIVED)) {
      throw new IllegalStateException("Stored binding publication candidate state conflicts");
    }
    if (operation.status() == PublicationStatus.COMMITTED) {
      HostedTermsEnvironmentBinding result = readCandidate(operation);
      if (!Arrays.equals(
              operation.resultPayload(), HostedTermsEnvironmentBindingEncoding.receipt(result))
          || !HostedTermsEncoding.digest(operation.resultPayload())
              .equals(operation.resultDigest())) {
        throw new IllegalStateException("Stored binding publication result conflicts");
      }
    }
    return operation;
  }

  private HostedTermsEnvironmentBinding binding(Record row) {
    HostedTermsEnvironmentBinding binding =
        new HostedTermsEnvironmentBinding(
            row.get("binding_id", UUID.class),
            row.get("publication_request_id", UUID.class),
            row.get("environment_boundary", String.class),
            row.get("hosted_scope_id", UUID.class),
            row.get("operator_legal_identity", String.class),
            row.get("operator_identity_version", Long.class),
            row.get("catalog_version_id", UUID.class),
            row.get("catalog_source_version", Long.class),
            row.get("authenticated_publisher_identity", String.class),
            row.get("publication_event_identity", String.class),
            row.get("publication_evidence_digest", String.class),
            row.get("predecessor_binding_id", UUID.class),
            row.get("predecessor_source_version", Long.class),
            row.get("source_version", Long.class));
    if (!binding.environmentBoundary().equals(row.get("environment_boundary", String.class))
        || !binding.hostedScopeId().equals(row.get("hosted_scope_id", UUID.class))
        || !Objects.equals(
            binding.predecessorBindingId(), row.get("predecessor_binding_id", UUID.class))) {
      throw new IllegalStateException("Environment binding columns conflict with exact receipt");
    }
    return binding;
  }

  private static String requireBoundary(String boundary) {
    return HostedTermsEncoding.requireText(boundary, 512);
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Environment binding repository requires an owner transaction");
    }
  }

  private void requireWriteTransaction() {
    requireTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable owner transaction required");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("Writable READ_COMMITTED Account transaction required");
          }
        });
  }
}
