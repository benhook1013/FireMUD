package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamedesign.entity.Game;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-local persistence for immutable fresh Game Design tenant creation operations. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected jOOQ and repository collaborators are internal Spring components.")
public class GameTenantCreationRepository {
  private static final int SCHEMA_VERSION = 1;
  private static final String COMPLETED = "COMPLETED";
  private static final String PENDING = "PENDING";
  private static final String NEW_GAME_ROW = "NEW_GAME_ROW";
  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Table<?> OPERATION_TABLE =
      DSL.table(DSL.name("game_tenant_creation_operations"));
  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<Integer> SCHEMA_VERSION_FIELD =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CREATION_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final Field<String> DESCRIPTION = DSL.field(DSL.name("description"), String.class);
  private static final Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> PROVENANCE_KIND =
      DSL.field(DSL.name("provenance_kind"), String.class);
  private static final Field<String> EVIDENCE_DIGEST =
      DSL.field(DSL.name("evidence_digest"), String.class);

  private final DSLContext dsl;
  private final GameRepository gameRepository;

  public GameTenantCreationRepository(DSLContext dsl, GameRepository gameRepository) {
    this.dsl = dsl;
    this.gameRepository = gameRepository;
  }

  /**
   * Claims and completes a fresh tenant operation in the caller's owner transaction.
   *
   * <p>The runtime transaction check protects direct construction as well as Spring-proxied use;
   * the database's deferred completion trigger also prevents a claim-only transaction from
   * committing.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public GameTenantCreationReceipt createCandidate(
      String targetNamespace,
      UUID creationRequestId,
      String sourceGameTenantKey,
      String name,
      String description) {
    requireActiveOwnerTransaction();
    validateInput(targetNamespace, creationRequestId, sourceGameTenantKey, name, description);

    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            targetNamespace, creationRequestId, sourceGameTenantKey, name, description);
    UUID operationId = newNonNilUuid();
    int inserted =
        dsl.insertInto(OPERATION_TABLE)
            .set(OPERATION_ID, operationId)
            .set(SCHEMA_VERSION_FIELD, SCHEMA_VERSION)
            .set(TARGET_NAMESPACE, targetNamespace)
            .set(CREATION_REQUEST_ID, creationRequestId)
            .set(REQUEST_DIGEST, requestDigest)
            .set(SOURCE_GAME_TENANT_KEY, sourceGameTenantKey)
            .set(NAME, name)
            .set(DESCRIPTION, description)
            .set(STATUS, PENDING)
            .onConflictDoNothing()
            .execute();

    if (inserted == 0) {
      Record existing =
          dsl.selectFrom(OPERATION_TABLE)
              .where(
                  TARGET_NAMESPACE
                      .eq(targetNamespace)
                      .and(CREATION_REQUEST_ID.eq(creationRequestId)))
              .fetchOne();
      if (existing == null) {
        throw new CreationRequestConflictException(
            "Fresh tenant creation source key is already claimed by another operation");
      }
      if (!sameRequest(existing, sourceGameTenantKey, name, description, requestDigest)) {
        throw new CreationRequestConflictException(
            "Fresh tenant creation request identity was reused with changed input");
      }
      return toReceipt(existing);
    }

    if (gameRepository.findByTenantId(sourceGameTenantKey) != null) {
      throw new CreationRequestConflictException(
          "Fresh tenant creation source key already belongs to a game row");
    }

    Game candidate = new Game();
    candidate.setTenantId(sourceGameTenantKey);
    candidate.setName(name);
    candidate.setDescription(description);
    Game saved = gameRepository.save(candidate);
    requireNewGameSource(saved, sourceGameTenantKey);

    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            targetNamespace,
            creationRequestId,
            operationId,
            requestDigest,
            saved.getCanonicalTenantId(),
            saved.getId(),
            sourceGameTenantKey,
            NEW_GAME_ROW);
    int completed =
        dsl.update(OPERATION_TABLE)
            .set(STATUS, COMPLETED)
            .set(CANONICAL_TENANT_ID, saved.getCanonicalTenantId())
            .set(SOURCE_GAME_ROW_ID, saved.getId())
            .set(PROVENANCE_KIND, NEW_GAME_ROW)
            .set(EVIDENCE_DIGEST, evidenceDigest)
            .where(OPERATION_ID.eq(operationId).and(STATUS.eq(PENDING)))
            .execute();
    if (completed != 1) {
      throw new IllegalStateException("Fresh tenant creation operation claim was lost");
    }

    Record completedRecord =
        dsl.selectFrom(OPERATION_TABLE).where(OPERATION_ID.eq(operationId)).fetchOne();
    if (completedRecord == null) {
      throw new IllegalStateException("Completed fresh tenant creation operation is missing");
    }
    return toReceipt(completedRecord);
  }

  /** Reads only a complete operation whose persisted source tuple still matches its game row. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<GameTenantCreationReceipt> read(UUID creationRequestId, String targetNamespace) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh tenant creation read requires a committed-outcome owner read");
    }
    validateSelector(targetNamespace, creationRequestId);
    Record record =
        dsl.selectFrom(OPERATION_TABLE)
            .where(
                TARGET_NAMESPACE.eq(targetNamespace).and(CREATION_REQUEST_ID.eq(creationRequestId)))
            .fetchOne();
    return record == null ? Optional.empty() : Optional.of(toReceipt(record));
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh tenant creation requires an active Game Design owner transaction");
    }
  }

  private void validateInput(
      String targetNamespace,
      UUID creationRequestId,
      String sourceGameTenantKey,
      String name,
      String description) {
    validateSelector(targetNamespace, creationRequestId);
    Objects.requireNonNull(sourceGameTenantKey, "sourceGameTenantKey");
    Objects.requireNonNull(name, "name");
    if (sourceGameTenantKey.length() > 72) {
      throw new IllegalArgumentException("sourceGameTenantKey must fit the game tenant key bound");
    }
    if (name.length() > 200) {
      throw new IllegalArgumentException("name must fit the game name bound");
    }
    if (description != null && description.length() > 510) {
      throw new IllegalArgumentException("description must fit the game description bound");
    }
    if (sourceGameTenantKey.isBlank()
        || sourceGameTenantKey.codePointCount(0, sourceGameTenantKey.length()) > 36) {
      throw new IllegalArgumentException("sourceGameTenantKey must fit the game tenant key bound");
    }
    if (name.codePointCount(0, name.length()) > 100) {
      throw new IllegalArgumentException("name must fit the game name bound");
    }
    if (description != null && description.codePointCount(0, description.length()) > 255) {
      throw new IllegalArgumentException("description must fit the game description bound");
    }
    if (description != null && GameTenantCreationDigest.utf8ByteLength(description) > 1020) {
      throw new IllegalArgumentException("description must fit the game description byte bound");
    }
  }

  private void validateSelector(String targetNamespace, UUID creationRequestId) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(creationRequestId, "creationRequestId");
    if (!DNS_LABEL.matcher(targetNamespace).matches()) {
      throw new IllegalArgumentException("targetNamespace must be one lowercase DNS label");
    }
    if (creationRequestId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("creationRequestId must not be nil");
    }
  }

  private void requireNewGameSource(Game saved, String sourceGameTenantKey) {
    if (saved == null
        || saved.getId() == null
        || saved.getId() <= 0
        || saved.getCanonicalTenantId() == null
        || saved.getCanonicalTenantId().equals(new UUID(0L, 0L))
        || !sourceGameTenantKey.equals(saved.getTenantId())) {
      throw new IllegalStateException("Game Design did not issue a complete new game source row");
    }
    GameTenantIdentity source;
    try {
      source =
          gameRepository
              .findTenantIdentityByLegacyTenantId(sourceGameTenantKey)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "New game source identity did not read back after insert"));
    } catch (IllegalStateException exception) {
      throw new IllegalStateException("New game source identity did not read back", exception);
    }
    if (source.provenanceKind() != GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW
        || !source.canonicalTenantId().equals(saved.getCanonicalTenantId())
        || !source.sourceGameId().equals(saved.getId())
        || !sourceGameTenantKey.equals(source.sourceLegacyTenantId())) {
      throw new IllegalStateException("New game source identity readback did not match issuance");
    }
  }

  private GameTenantCreationReceipt toReceipt(Record record) {
    Integer schemaVersion = record.get(SCHEMA_VERSION_FIELD);
    String namespace = record.get(TARGET_NAMESPACE);
    UUID creationRequestId = record.get(CREATION_REQUEST_ID);
    UUID operationId = record.get(OPERATION_ID);
    String requestDigest = record.get(REQUEST_DIGEST);
    String sourceGameTenantKey = record.get(SOURCE_GAME_TENANT_KEY);
    String name = record.get(NAME);
    String description = record.get(DESCRIPTION);
    String status = record.get(STATUS);
    UUID canonicalTenantId = record.get(CANONICAL_TENANT_ID);
    Long sourceGameRowId = record.get(SOURCE_GAME_ROW_ID);
    String provenanceKind = record.get(PROVENANCE_KIND);
    String evidenceDigest = record.get(EVIDENCE_DIGEST);

    if (schemaVersion == null || schemaVersion != SCHEMA_VERSION) {
      throw invalidEvidence("Stored creation operation has an unsupported schema version");
    }
    if (!COMPLETED.equals(status)) {
      throw invalidEvidence("Stored creation operation is not complete");
    }
    if (!validStoredInput(namespace, creationRequestId, sourceGameTenantKey, name, description)) {
      throw invalidEvidence("Stored creation operation has invalid immutable input");
    }
    if (operationId == null
        || operationId.equals(new UUID(0L, 0L))
        || canonicalTenantId == null
        || canonicalTenantId.equals(new UUID(0L, 0L))
        || sourceGameRowId == null
        || sourceGameRowId <= 0
        || !NEW_GAME_ROW.equals(provenanceKind)
        || !GameTenantCreationDigest.isDigest(requestDigest)
        || !GameTenantCreationDigest.isDigest(evidenceDigest)) {
      throw invalidEvidence("Stored creation operation evidence is incomplete or malformed");
    }

    String expectedRequestDigest;
    try {
      expectedRequestDigest =
          GameTenantCreationDigest.requestDigest(
              namespace, creationRequestId, sourceGameTenantKey, name, description);
    } catch (IllegalArgumentException exception) {
      throw invalidEvidence("Stored creation operation input is not valid Unicode", exception);
    }
    if (!expectedRequestDigest.equals(requestDigest)) {
      throw invalidEvidence("Stored creation operation request digest does not match its input");
    }

    Optional<GameTenantIdentity> source;
    try {
      source = gameRepository.findTenantIdentityByLegacyTenantId(sourceGameTenantKey);
    } catch (IllegalStateException exception) {
      throw invalidEvidence("Stored creation operation source row is corrupt", exception);
    }
    if (source.isEmpty()) {
      throw invalidEvidence("Stored creation operation source row is missing");
    }
    GameTenantIdentity identity = source.get();
    if (identity.provenanceKind() != GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW
        || !identity.canonicalTenantId().equals(canonicalTenantId)
        || !identity.sourceGameId().equals(sourceGameRowId)
        || !sourceGameTenantKey.equals(identity.sourceLegacyTenantId())) {
      throw invalidEvidence(
          "Stored creation operation source tuple no longer matches its game row");
    }

    String expectedEvidenceDigest;
    try {
      expectedEvidenceDigest =
          GameTenantCreationDigest.evidenceDigest(
              namespace,
              creationRequestId,
              operationId,
              requestDigest,
              canonicalTenantId,
              sourceGameRowId,
              sourceGameTenantKey,
              provenanceKind);
    } catch (IllegalArgumentException exception) {
      throw invalidEvidence("Stored creation operation evidence cannot be digested", exception);
    }
    if (!expectedEvidenceDigest.equals(evidenceDigest)) {
      throw invalidEvidence("Stored creation operation evidence digest does not match its tuple");
    }

    return new GameTenantCreationReceipt(
        schemaVersion,
        namespace,
        creationRequestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        evidenceDigest);
  }

  private boolean validStoredInput(
      String namespace,
      UUID creationRequestId,
      String sourceGameTenantKey,
      String name,
      String description) {
    if (namespace == null
        || creationRequestId == null
        || sourceGameTenantKey == null
        || name == null) {
      return false;
    }
    try {
      validateInput(namespace, creationRequestId, sourceGameTenantKey, name, description);
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private boolean sameRequest(
      Record record,
      String sourceGameTenantKey,
      String name,
      String description,
      String requestDigest) {
    return requestDigest.equals(record.get(REQUEST_DIGEST))
        && sourceGameTenantKey.equals(record.get(SOURCE_GAME_TENANT_KEY))
        && name.equals(record.get(NAME))
        && Objects.equals(description, record.get(DESCRIPTION));
  }

  private UUID newNonNilUuid() {
    UUID id;
    do {
      id = UUID.randomUUID();
    } while (id.equals(new UUID(0L, 0L)));
    return id;
  }

  private InvalidCreationEvidenceException invalidEvidence(String message) {
    return new InvalidCreationEvidenceException(message);
  }

  private InvalidCreationEvidenceException invalidEvidence(String message, Throwable cause) {
    return new InvalidCreationEvidenceException(message, cause);
  }

  /** Indicates a stored operation or source tuple that cannot be trusted as creation evidence. */
  public static final class InvalidCreationEvidenceException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public InvalidCreationEvidenceException(String message) {
      super(message);
    }

    public InvalidCreationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Indicates an expected creation-request identity or private source-key collision. */
  public static final class CreationRequestConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public CreationRequestConflictException(String message) {
      super(message);
    }
  }
}
