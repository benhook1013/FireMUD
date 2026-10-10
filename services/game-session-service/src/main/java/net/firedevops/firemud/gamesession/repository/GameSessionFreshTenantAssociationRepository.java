package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.client.GameDesignFreshTenantCreationClient;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Game Session owner composition for importing one exact fresh Game Design tenant
 * receipt into the numeric tenant scope. It reads the remote owner before SQL and independently
 * reads the committed immutable association after the write transaction.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Trusted internal client, DSL context, and transaction manager are retained privately.")
public final class GameSessionFreshTenantAssociationRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> EVIDENCE_FIELDS =
      Set.of(
          "schemaVersion",
          "targetNamespace",
          "creationRequestId",
          "operationId",
          "requestDigest",
          "canonicalTenantId",
          "sourceGameRowId",
          "sourceGameTenantKey",
          "provenanceKind",
          "evidenceDigest");
  private static final String ASSOCIATION_SELECT =
      "SELECT fresh.target_namespace, fresh.association_request_id, "
          + "fresh.source_schema_version, fresh.source_target_namespace, "
          + "fresh.source_request_id, fresh.source_canonical_tenant_id, "
          + "fresh.canonical_tenant_id, fresh.legacy_game_session_tenant_id, "
          + "fresh.source_game_row_id, fresh.source_game_tenant_key, fresh.provenance_kind, "
          + "fresh.association_kind, fresh.association_operation_id, "
          + "fresh.complete_source_evidence::text AS complete_source_evidence, "
          + "claim.target_namespace AS claim_namespace, "
          + "claim.canonical_tenant_id AS claim_canonical_tenant_id, "
          + "claim.legacy_game_session_tenant_id AS claim_tenant_id, "
          + "claim.association_kind AS claim_kind, "
          + "claim.reservation_kind AS claim_reservation_kind, "
          + "claim.association_operation_id AS claim_operation_id, "
          + "claim.association_request_id AS claim_request_id, "
          + "reservation.reservation_kind AS reservation_kind "
          + "FROM game_session_fresh_tenant_association fresh "
          + "LEFT JOIN game_session_tenant_canonical_claim claim "
          + "ON claim.target_namespace = fresh.target_namespace "
          + "AND claim.canonical_tenant_id = fresh.canonical_tenant_id "
          + "LEFT JOIN game_session_tenant_scope_reservation reservation "
          + "ON reservation.game_session_tenant_id = fresh.legacy_game_session_tenant_id ";

  private final DSLContext dsl;
  private final GameDesignFreshTenantCreationClient gameDesignClient;
  private final String workloadNamespace;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;

  public GameSessionFreshTenantAssociationRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      GameDesignFreshTenantCreationClient gameDesignClient,
      String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl);
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is missing or invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.writeTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.readTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction.setReadOnly(true);
  }

  /**
   * Resolves the exact authenticated Game Design receipt outside SQL, atomically claims a new Game
   * Session numeric tenant ID and association, then independently reads the committed row. Exact
   * retries return the original allocation; a changed source receipt or incomplete legacy
   * association fails closed.
   */
  public FreshTenantAssociation associateFreshTenant(
      UUID creationRequestId, String expectedRequestDigest) {
    requireNoActiveOwnerTransaction();
    requireExactSelector(creationRequestId, expectedRequestDigest);

    FreshTenantCreationEvidence sourceEvidence =
        Objects.requireNonNull(
            gameDesignClient.resolveCreation(creationRequestId, expectedRequestDigest),
            "Authenticated Game Design fresh-tenant evidence is required");
    requireExactSource(sourceEvidence, creationRequestId, expectedRequestDigest);

    FreshTenantAssociation committed =
        writeTransaction.execute(status -> commitAssociation(sourceEvidence));
    if (committed == null) {
      throw new InvalidFreshTenantAssociationException(
          "Game Session fresh tenant association transaction returned no result");
    }

    FreshTenantAssociation independentReadback =
        readTransaction.execute(
            status ->
                readByCanonicalTenantIdInTransaction(sourceEvidence.canonicalTenantId())
                    .orElse(null));
    if (independentReadback == null
        || !sourceEvidence.equals(independentReadback.sourceEvidence())
        || !committed.equals(independentReadback)) {
      throw new InvalidFreshTenantAssociationException(
          "Committed Game Session fresh tenant association failed independent exact readback");
    }
    return independentReadback;
  }

  /** Reads only complete, internally consistent stored evidence; it never calls Game Design. */
  public Optional<FreshTenantAssociation> readByCanonicalTenantId(UUID canonicalTenantId) {
    requireNoActiveOwnerTransaction();
    if (canonicalTenantId == null || isNil(canonicalTenantId)) {
      throw new IllegalArgumentException("Canonical tenant UUID must be nonnil");
    }
    Optional<FreshTenantAssociation> result =
        readTransaction.execute(status -> readByCanonicalTenantIdInTransaction(canonicalTenantId));
    return Objects.requireNonNull(result, "Game Session read transaction returned no result");
  }

  private FreshTenantAssociation commitAssociation(FreshTenantCreationEvidence sourceEvidence) {
    requireOwnerTransaction();
    lockSourceIdentity(sourceEvidence);

    Optional<FreshTenantAssociation> prior =
        readByRequestIdInTransaction(sourceEvidence.creationRequestId());
    if (prior.isPresent()) {
      requireExactSource(sourceEvidence, prior.orElseThrow().sourceEvidence());
      return prior.orElseThrow();
    }

    rejectOrphanOrRetainedClaim(sourceEvidence);
    long tenantId = allocateFreshTenantId();
    UUID associationOperationId = newAssociationOperationId(sourceEvidence.operationId());

    int claimInsertCount =
        dsl.execute(
            "INSERT INTO game_session_tenant_canonical_claim"
                + " (target_namespace, canonical_tenant_id, legacy_game_session_tenant_id,"
                + " association_kind, reservation_kind, association_operation_id,"
                + " association_request_id) VALUES (?, ?, ?, 'FRESH_SOURCE_BOUND',"
                + " 'FRESH_SOURCE_BOUND', ?, ?)",
            sourceEvidence.targetNamespace(),
            sourceEvidence.canonicalTenantId(),
            tenantId,
            associationOperationId,
            sourceEvidence.creationRequestId());
    if (claimInsertCount != 1) {
      throw new InvalidFreshTenantAssociationException(
          "Game Session canonical tenant claim insert did not affect exactly one row");
    }

    int associationInsertCount =
        dsl.execute(
            "INSERT INTO game_session_fresh_tenant_association"
                + " (association_operation_id, target_namespace, association_request_id,"
                + " source_schema_version, source_target_namespace, source_request_id,"
                + " source_canonical_tenant_id, canonical_tenant_id,"
                + " legacy_game_session_tenant_id, source_game_row_id,"
                + " source_game_tenant_key, provenance_kind, association_kind,"
                + " complete_source_evidence)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FRESH_SOURCE_BOUND', ?::jsonb)",
            associationOperationId,
            sourceEvidence.targetNamespace(),
            sourceEvidence.creationRequestId(),
            sourceEvidence.schemaVersion(),
            sourceEvidence.targetNamespace(),
            sourceEvidence.creationRequestId(),
            sourceEvidence.canonicalTenantId(),
            sourceEvidence.canonicalTenantId(),
            tenantId,
            sourceEvidence.sourceGameRowId(),
            sourceEvidence.sourceGameTenantKey(),
            sourceEvidence.provenanceKind(),
            serializeEvidence(sourceEvidence));
    if (associationInsertCount != 1) {
      throw new InvalidFreshTenantAssociationException(
          "Game Session fresh tenant association insert did not affect exactly one row");
    }

    FreshTenantAssociation readback =
        readByCanonicalTenantIdInTransaction(sourceEvidence.canonicalTenantId())
            .orElseThrow(
                () ->
                    new InvalidFreshTenantAssociationException(
                        "Game Session fresh tenant association did not read back in its owner transaction"));
    requireExactSource(sourceEvidence, readback.sourceEvidence());
    return readback;
  }

  private Optional<FreshTenantAssociation> readByCanonicalTenantIdInTransaction(
      UUID canonicalTenantId) {
    requireOwnerTransaction();
    if (canonicalTenantId == null || isNil(canonicalTenantId)) {
      throw new IllegalArgumentException("Canonical tenant UUID must be nonnil");
    }
    Record row =
        dsl.fetchOne(
            ASSOCIATION_SELECT
                + "WHERE fresh.target_namespace = ? AND fresh.canonical_tenant_id = ?",
            workloadNamespace,
            canonicalTenantId);
    if (row == null) {
      rejectClaimWithoutAssociation(canonicalTenantId, null);
      return Optional.empty();
    }
    return Optional.of(toAssociation(row));
  }

  private Optional<FreshTenantAssociation> readByRequestIdInTransaction(UUID requestId) {
    requireOwnerTransaction();
    Record row =
        dsl.fetchOne(
            ASSOCIATION_SELECT
                + "WHERE fresh.target_namespace = ? AND fresh.association_request_id = ?",
            workloadNamespace,
            requestId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(toAssociation(row));
  }

  private FreshTenantAssociation toAssociation(Record row) {
    try {
      String namespace = required(row, "target_namespace", String.class);
      UUID canonicalTenantId = required(row, "canonical_tenant_id", UUID.class);
      UUID requestId = required(row, "association_request_id", UUID.class);
      UUID associationOperationId = required(row, "association_operation_id", UUID.class);
      long tenantId = required(row, "legacy_game_session_tenant_id", Long.class);
      FreshTenantCreationEvidence sourceEvidence =
          parseEvidence(required(row, "complete_source_evidence", String.class));

      if (!workloadNamespace.equals(namespace)
          || !namespace.equals(required(row, "source_target_namespace", String.class))
          || !requestId.equals(required(row, "source_request_id", UUID.class))
          || !canonicalTenantId.equals(required(row, "source_canonical_tenant_id", UUID.class))
          || required(row, "source_schema_version", Integer.class) != sourceEvidence.schemaVersion()
          || required(row, "source_game_row_id", Long.class) != sourceEvidence.sourceGameRowId()
          || !required(row, "source_game_tenant_key", String.class)
              .equals(sourceEvidence.sourceGameTenantKey())
          || !required(row, "provenance_kind", String.class).equals(sourceEvidence.provenanceKind())
          || !"FRESH_SOURCE_BOUND".equals(required(row, "association_kind", String.class))
          || !sourceEvidence.targetNamespace().equals(namespace)
          || !sourceEvidence.creationRequestId().equals(requestId)
          || !sourceEvidence.canonicalTenantId().equals(canonicalTenantId)
          || isNil(associationOperationId)
          || associationOperationId.equals(sourceEvidence.operationId())
          || !"FRESH_SOURCE_BOUND".equals(required(row, "claim_kind", String.class))
          || !"FRESH_SOURCE_BOUND".equals(required(row, "claim_reservation_kind", String.class))
          || !"FRESH_SOURCE_BOUND".equals(required(row, "reservation_kind", String.class))
          || !namespace.equals(required(row, "claim_namespace", String.class))
          || !canonicalTenantId.equals(required(row, "claim_canonical_tenant_id", UUID.class))
          || tenantId != required(row, "claim_tenant_id", Long.class)
          || !associationOperationId.equals(required(row, "claim_operation_id", UUID.class))
          || !requestId.equals(required(row, "claim_request_id", UUID.class))) {
        throw new InvalidFreshTenantAssociationException(
            "Game Session fresh tenant association differs from its complete source receipt or canonical claim");
      }
      return new FreshTenantAssociation(
          namespace, canonicalTenantId, tenantId, associationOperationId, sourceEvidence);
    } catch (IllegalArgumentException exception) {
      throw new InvalidFreshTenantAssociationException(
          "Game Session fresh tenant association contains invalid complete source evidence",
          exception);
    }
  }

  private void rejectOrphanOrRetainedClaim(FreshTenantCreationEvidence evidence) {
    rejectClaimWithoutAssociation(evidence.canonicalTenantId(), evidence.creationRequestId());
  }

  private void rejectClaimWithoutAssociation(UUID canonicalTenantId, UUID requestId) {
    Record claim =
        dsl.fetchOne(
            "SELECT target_namespace, canonical_tenant_id, legacy_game_session_tenant_id,"
                + " association_kind, association_request_id FROM game_session_tenant_canonical_claim"
                + " WHERE target_namespace = ? AND (canonical_tenant_id = ? OR association_request_id = ?)",
            workloadNamespace,
            canonicalTenantId,
            requestId);
    if (claim != null) {
      throw new InvalidFreshTenantAssociationException(
          "Game Session canonical tenant claim exists without its exact fresh association; retained or partial identity cannot be adopted");
    }
  }

  private long allocateFreshTenantId() {
    Record reservation =
        dsl.fetchOne(
            "INSERT INTO game_session_tenant_scope_reservation (reservation_kind)"
                + " VALUES ('FRESH_SOURCE_BOUND') RETURNING game_session_tenant_id, reservation_kind");
    if (reservation == null
        || !"FRESH_SOURCE_BOUND".equals(required(reservation, "reservation_kind", String.class))) {
      throw new InvalidFreshTenantAssociationException(
          "Game Session numeric tenant reservation did not allocate a fresh source-bound key");
    }
    Long tenantId = required(reservation, "game_session_tenant_id", Long.class);
    if (tenantId <= 0) {
      throw new InvalidFreshTenantAssociationException(
          "Game Session numeric tenant reservation is not positive");
    }
    return tenantId;
  }

  private void lockSourceIdentity(FreshTenantCreationEvidence evidence) {
    List<String> lockKeys =
        new ArrayList<>(
            List.of(
                "fresh-tenant-canonical:" + workloadNamespace + ":" + evidence.canonicalTenantId(),
                "fresh-tenant-request:" + workloadNamespace + ":" + evidence.creationRequestId()));
    lockKeys.sort(String::compareTo);
    for (String key : lockKeys) {
      dsl.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", key);
    }
  }

  private static UUID newAssociationOperationId(UUID sourceOperationId) {
    UUID operationId;
    do {
      operationId = UUID.randomUUID();
    } while (operationId.equals(sourceOperationId) || isNil(operationId));
    return operationId;
  }

  private static String serializeEvidence(FreshTenantCreationEvidence evidence) {
    ObjectNode json = JSON.createObjectNode();
    json.put("schemaVersion", evidence.schemaVersion());
    json.put("targetNamespace", evidence.targetNamespace());
    json.put("creationRequestId", evidence.creationRequestId().toString());
    json.put("operationId", evidence.operationId().toString());
    json.put("requestDigest", evidence.requestDigest());
    json.put("canonicalTenantId", evidence.canonicalTenantId().toString());
    json.put("sourceGameRowId", evidence.sourceGameRowId());
    json.put("sourceGameTenantKey", evidence.sourceGameTenantKey());
    json.put("provenanceKind", evidence.provenanceKind());
    json.put("evidenceDigest", evidence.evidenceDigest());
    try {
      return JSON.writeValueAsString(json);
    } catch (JsonProcessingException exception) {
      throw new InvalidFreshTenantAssociationException(
          "Complete Game Design source evidence could not be serialized", exception);
    }
  }

  private static FreshTenantCreationEvidence parseEvidence(String serializedEvidence) {
    try {
      JsonNode json = JSON.readTree(serializedEvidence);
      if (json == null || !json.isObject() || json.size() != EVIDENCE_FIELDS.size()) {
        throw new IllegalArgumentException(
            "Complete source evidence must be one closed JSON object");
      }
      Set<String> fields = new HashSet<>();
      json.fieldNames().forEachRemaining(fields::add);
      if (!fields.equals(EVIDENCE_FIELDS)) {
        throw new IllegalArgumentException(
            "Complete source evidence has an unknown or missing field");
      }
      return new FreshTenantCreationEvidence(
          requiredInt(json, "schemaVersion"),
          requiredText(json, "targetNamespace"),
          parseUuid(requiredText(json, "creationRequestId")),
          parseUuid(requiredText(json, "operationId")),
          requiredText(json, "requestDigest"),
          parseUuid(requiredText(json, "canonicalTenantId")),
          requiredLong(json, "sourceGameRowId"),
          requiredText(json, "sourceGameTenantKey"),
          requiredText(json, "provenanceKind"),
          requiredText(json, "evidenceDigest"));
    } catch (IOException | IllegalArgumentException exception) {
      throw new InvalidFreshTenantAssociationException(
          "Stored Game Session complete source evidence is malformed or has an invalid digest",
          exception);
    }
  }

  private static String requiredText(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Complete source evidence field must be text: " + field);
    }
    return value.textValue();
  }

  private static int requiredInt(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(
          "Complete source evidence field must be an integer: " + field);
    }
    return value.intValue();
  }

  private static long requiredLong(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(
          "Complete source evidence field must be an integer: " + field);
    }
    return value.longValue();
  }

  private static UUID parseUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value) || isNil(parsed)) {
      throw new IllegalArgumentException(
          "Complete source evidence UUID must be canonical and nonnil");
    }
    return parsed;
  }

  private static void requireExactSelector(UUID requestId, String expectedDigest) {
    if (requestId == null || isNil(requestId)) {
      throw new IllegalArgumentException("Canonical nonnil creation request ID is required");
    }
    if (!GameTenantCreationDigest.isDigest(expectedDigest)) {
      throw new IllegalArgumentException("Canonical expected request digest is required");
    }
  }

  private void requireExactSource(
      FreshTenantCreationEvidence evidence, UUID requestId, String expectedDigest) {
    if (evidence == null
        || !workloadNamespace.equals(evidence.targetNamespace())
        || !requestId.equals(evidence.creationRequestId())
        || !expectedDigest.equals(evidence.requestDigest())) {
      throw new InvalidFreshTenantAssociationException(
          "Authenticated Game Design receipt differs from the exact Game Session request");
    }
  }

  private static void requireExactSource(
      FreshTenantCreationEvidence expected, FreshTenantCreationEvidence actual) {
    if (!expected.equals(actual)) {
      throw new InvalidFreshTenantAssociationException(
          "Game Session association retry differs from the complete original Game Design receipt");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) {
      throw new InvalidFreshTenantAssociationException(
          "Game Session fresh tenant association is missing required stored evidence: " + field);
    }
    return value;
  }

  private static void requireNoActiveOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Design fresh tenant evidence must be resolved before opening a Game Session transaction");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Session fresh tenant association storage access requires an active owner transaction");
    }
  }

  private static boolean isNil(UUID value) {
    return value != null && new UUID(0L, 0L).equals(value);
  }

  /** Exact immutable Game Session allocation and its complete Game Design owner receipt. */
  public record FreshTenantAssociation(
      String targetNamespace,
      UUID canonicalTenantId,
      long gameSessionTenantId,
      UUID associationOperationId,
      FreshTenantCreationEvidence sourceEvidence) {
    public FreshTenantAssociation {
      Objects.requireNonNull(targetNamespace);
      Objects.requireNonNull(canonicalTenantId);
      Objects.requireNonNull(associationOperationId);
      Objects.requireNonNull(sourceEvidence);
      if (gameSessionTenantId <= 0
          || isNil(canonicalTenantId)
          || isNil(associationOperationId)
          || associationOperationId.equals(sourceEvidence.operationId())) {
        throw new IllegalArgumentException("Stored fresh tenant association identity is invalid");
      }
    }
  }

  /** Stored association, canonical claim, or source receipt is incomplete or contradictory. */
  public static final class InvalidFreshTenantAssociationException extends IllegalStateException {
    public InvalidFreshTenantAssociationException(String message) {
      super(message);
    }

    public InvalidFreshTenantAssociationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
