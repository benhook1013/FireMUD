package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
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
  private static final Table<?> CREATOR_QUALIFICATION_TABLE =
      DSL.table(DSL.name("game_tenant_creation_creator_qualifications"));
  private static final Table<?> RESERVATION_TABLE =
      DSL.table(DSL.name("game_tenant_creation_reservations"));
  private static final Table<?> GAME_TABLE = DSL.table(DSL.name("game"));
  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<String> RESERVATION_TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> RESERVATION_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final Field<Integer> RESERVATION_SCHEMA_VERSION =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final Field<String> RESERVATION_REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<UUID> RESERVATION_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<UUID> RESERVATION_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> RESERVATION_SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> RESERVATION_NAME = DSL.field(DSL.name("name"), String.class);
  private static final Field<String> RESERVATION_DESCRIPTION =
      DSL.field(DSL.name("description"), String.class);
  private static final Field<Long> GAME_ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> GAME_TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<UUID> GAME_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> GAME_NAME = DSL.field(DSL.name("name"), String.class);
  private static final Field<String> GAME_DESCRIPTION =
      DSL.field(DSL.name("description"), String.class);
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
  private static final Field<Boolean> CREATOR_QUALIFICATION_REQUIRED =
      DSL.field(DSL.name("creator_qualification_required"), Boolean.class);
  private static final Field<UUID> CREATOR_QUALIFICATION_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<Integer> CREATOR_QUALIFICATION_SCHEMA_VERSION =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final Field<UUID> INITIATING_ACCOUNT_ID =
      DSL.field(DSL.name("initiating_account_id"), UUID.class);
  private static final Field<UUID> ACCOUNT_AUTHORIZATION_OPERATION_ID =
      DSL.field(DSL.name("account_authorization_operation_id"), UUID.class);
  private static final Field<String> ACCOUNT_AUTHORIZATION_DIGEST =
      DSL.field(DSL.name("account_authorization_digest"), String.class);
  private static final Field<String> CREATOR_EVIDENCE_DIGEST =
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
  public FreshTenantCreationEvidence createCandidate(
      String targetNamespace,
      UUID creationRequestId,
      String sourceGameTenantKey,
      String name,
      String description) {
    return createCandidateInternal(
        targetNamespace, creationRequestId, sourceGameTenantKey, name, description, false);
  }

  /**
   * Candidate-only storage producer for a distinct creator qualification.
   *
   * <p>The UUID and digest values are structurally validated but are not authenticated by this
   * repository. A protected Account capture adapter must establish their authority before calling
   * this method. The source operation and creator qualification are committed atomically.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public FreshTenantCreatorEvidence createCandidateWithCreator(
      String targetNamespace,
      UUID creationRequestId,
      String sourceGameTenantKey,
      String name,
      String description,
      UUID initiatingAccountId,
      UUID accountAuthorizationOperationId,
      String accountAuthorizationDigest) {
    return createCandidateWithCreatorInternal(
        null,
        targetNamespace,
        creationRequestId,
        sourceGameTenantKey,
        name,
        description,
        initiatingAccountId,
        accountAuthorizationOperationId,
        accountAuthorizationDigest);
  }

  /**
   * Completes creation from one exact persisted non-authoritative reservation.
   *
   * <p>The reservation's generated tenant UUID and operation identity are preserved verbatim.
   * Structural creator and Account authorization fields are data only; this repository does not
   * authenticate their producer or authorize creation.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public FreshTenantCreatorEvidence createReservedCandidateWithCreator(
      FreshTenantCreationReservation reservation,
      UUID initiatingAccountId,
      UUID accountAuthorizationOperationId,
      String accountAuthorizationDigest) {
    Objects.requireNonNull(reservation, "reservation");
    return createCandidateWithCreatorInternal(
        reservation,
        reservation.targetNamespace(),
        reservation.creationRequestId(),
        reservation.sourceGameTenantKey(),
        reservation.name(),
        reservation.description(),
        initiatingAccountId,
        accountAuthorizationOperationId,
        accountAuthorizationDigest);
  }

  private FreshTenantCreatorEvidence createCandidateWithCreatorInternal(
      FreshTenantCreationReservation reservation,
      String targetNamespace,
      UUID creationRequestId,
      String sourceGameTenantKey,
      String name,
      String description,
      UUID initiatingAccountId,
      UUID accountAuthorizationOperationId,
      String accountAuthorizationDigest) {
    requireActiveOwnerTransaction();
    validateInput(targetNamespace, creationRequestId, sourceGameTenantKey, name, description);
    validateCreatorBinding(
        initiatingAccountId, accountAuthorizationOperationId, accountAuthorizationDigest);

    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            targetNamespace, creationRequestId, sourceGameTenantKey, name, description);
    if (reservation != null) {
      requirePersistedReservation(reservation, requestDigest);
    }
    UUID operationId = reservation == null ? newNonNilUuid() : reservation.operationId();
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
            .set(CREATOR_QUALIFICATION_REQUIRED, true)
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
      FreshTenantCreationEvidence sourceEvidence = toReceipt(existing);
      requireReservationOutcomeMatches(reservation, sourceEvidence);
      if (!Boolean.TRUE.equals(existing.get(CREATOR_QUALIFICATION_REQUIRED))) {
        throw new CreationRequestConflictException(
            "Creator qualification cannot be attached to an existing source-only operation");
      }
      FreshTenantCreatorEvidence existingQualification =
          readCreatorQualificationForOperation(sourceEvidence);
      if (!existingQualification.initiatingAccountId().equals(initiatingAccountId)
          || !existingQualification
              .accountAuthorizationOperationId()
              .equals(accountAuthorizationOperationId)
          || !existingQualification
              .accountAuthorizationDigest()
              .equals(accountAuthorizationDigest)) {
        throw new CreationRequestConflictException(
            "Fresh tenant creator binding was reused with changed Account authority");
      }
      return existingQualification;
    }

    if (gameRepository.findByTenantId(sourceGameTenantKey) != null) {
      throw new CreationRequestConflictException(
          "Fresh tenant creation source key already belongs to a game row");
    }

    Game candidate = new Game();
    candidate.setTenantId(sourceGameTenantKey);
    candidate.setName(name);
    candidate.setDescription(description);
    Game saved =
        reservation == null
            ? gameRepository.save(candidate)
            : insertReservedGame(candidate, reservation.canonicalTenantId());
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
    FreshTenantCreationEvidence sourceEvidence = toReceipt(completedRecord);
    String creatorEvidenceDigest =
        FreshTenantCreatorDigest.evidenceDigest(
            1,
            sourceEvidence,
            initiatingAccountId,
            accountAuthorizationOperationId,
            accountAuthorizationDigest);
    dsl.insertInto(CREATOR_QUALIFICATION_TABLE)
        .set(CREATOR_QUALIFICATION_OPERATION_ID, operationId)
        .set(CREATOR_QUALIFICATION_SCHEMA_VERSION, 1)
        .set(INITIATING_ACCOUNT_ID, initiatingAccountId)
        .set(ACCOUNT_AUTHORIZATION_OPERATION_ID, accountAuthorizationOperationId)
        .set(ACCOUNT_AUTHORIZATION_DIGEST, accountAuthorizationDigest)
        .set(CREATOR_EVIDENCE_DIGEST, creatorEvidenceDigest)
        .execute();

    return new FreshTenantCreatorEvidence(
        1,
        sourceEvidence,
        initiatingAccountId,
        accountAuthorizationOperationId,
        accountAuthorizationDigest,
        creatorEvidenceDigest);
  }

  private void requirePersistedReservation(
      FreshTenantCreationReservation reservation, String requestDigest) {
    Record record =
        dsl.selectFrom(RESERVATION_TABLE)
            .where(
                RESERVATION_TARGET_NAMESPACE
                    .eq(reservation.targetNamespace())
                    .and(RESERVATION_REQUEST_ID.eq(reservation.creationRequestId())))
            .fetchOne();
    if (record == null) {
      throw new CreationRequestConflictException(
          "Fresh tenant creation requires an exact persisted Game Design reservation");
    }
    try {
      FreshTenantCreationReservation persisted = toReservation(record);
      if (!persisted.equals(reservation) || !requestDigest.equals(persisted.requestDigest())) {
        throw new CreationRequestConflictException(
            "Fresh tenant creation reservation does not match the persisted request");
      }
    } catch (IllegalArgumentException
        | GameTenantCreationReservationRepository.InvalidReservationException exception) {
      throw new InvalidCreationEvidenceException(
          "Stored fresh tenant reservation evidence is invalid", exception);
    }
  }

  private FreshTenantCreationReservation toReservation(Record record) {
    Integer schemaVersion = record.get(RESERVATION_SCHEMA_VERSION);
    if (schemaVersion == null) {
      throw new InvalidCreationEvidenceException(
          "Stored fresh tenant reservation schema version is missing");
    }
    return new FreshTenantCreationReservation(
        schemaVersion,
        record.get(RESERVATION_TARGET_NAMESPACE),
        record.get(RESERVATION_REQUEST_ID),
        record.get(RESERVATION_REQUEST_DIGEST),
        record.get(RESERVATION_OPERATION_ID),
        record.get(RESERVATION_CANONICAL_TENANT_ID),
        record.get(RESERVATION_SOURCE_GAME_TENANT_KEY),
        record.get(RESERVATION_NAME),
        record.get(RESERVATION_DESCRIPTION));
  }

  private void requireReservationOutcomeMatches(
      FreshTenantCreationReservation reservation, FreshTenantCreationEvidence sourceEvidence) {
    if (reservation != null
        && (!reservation.operationId().equals(sourceEvidence.operationId())
            || !reservation.canonicalTenantId().equals(sourceEvidence.canonicalTenantId()))) {
      throw new CreationRequestConflictException(
          "Completed fresh tenant creation does not match its exact reservation");
    }
  }

  private Game insertReservedGame(Game candidate, UUID canonicalTenantId) {
    Record record =
        dsl.insertInto(GAME_TABLE)
            .set(GAME_TENANT_ID, candidate.getTenantId())
            .set(GAME_CANONICAL_TENANT_ID, canonicalTenantId)
            .set(GAME_NAME, candidate.getName())
            .set(GAME_DESCRIPTION, candidate.getDescription())
            .returning(
                GAME_ID, GAME_TENANT_ID, GAME_CANONICAL_TENANT_ID, GAME_NAME, GAME_DESCRIPTION)
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException(
          "Reserved Game tenant insert did not return its persisted row");
    }
    Game saved = new Game();
    saved.setId(record.get(GAME_ID));
    saved.setTenantId(record.get(GAME_TENANT_ID));
    saved.setCanonicalTenantId(record.get(GAME_CANONICAL_TENANT_ID));
    saved.setName(record.get(GAME_NAME));
    saved.setDescription(record.get(GAME_DESCRIPTION));
    return saved;
  }

  private FreshTenantCreationEvidence createCandidateInternal(
      String targetNamespace,
      UUID creationRequestId,
      String sourceGameTenantKey,
      String name,
      String description,
      boolean creatorQualificationRequired) {
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
            .set(CREATOR_QUALIFICATION_REQUIRED, creatorQualificationRequired)
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
      if (Boolean.TRUE.equals(existing.get(CREATOR_QUALIFICATION_REQUIRED))) {
        throw new CreationRequestConflictException(
            "Qualified fresh tenant creation cannot be retried as source-only creation");
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
  public Optional<FreshTenantCreationEvidence> read(
      UUID creationRequestId, String targetNamespace) {
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

  /**
   * Reads the source operation and creator qualification separately after commit and compares the
   * complete immutable request binding.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<FreshTenantCreatorEvidence> readCreatorQualification(
      UUID creationRequestId,
      String targetNamespace,
      String expectedRequestDigest,
      String expectedCreationEvidenceDigest,
      UUID expectedInitiatingAccountId,
      UUID expectedAccountAuthorizationOperationId,
      String expectedAccountAuthorizationDigest,
      String expectedCreatorEvidenceDigest) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh tenant creator read requires a committed-outcome owner read");
    }
    validateSelector(targetNamespace, creationRequestId);
    if (!GameTenantCreationDigest.isDigest(expectedRequestDigest)
        || !GameTenantCreationDigest.isDigest(expectedCreationEvidenceDigest)
        || !GameTenantCreationDigest.isDigest(expectedCreatorEvidenceDigest)) {
      throw new IllegalArgumentException("Canonical expected fresh tenant digests are required");
    }
    validateCreatorBinding(
        expectedInitiatingAccountId,
        expectedAccountAuthorizationOperationId,
        expectedAccountAuthorizationDigest);

    Optional<FreshTenantCreationEvidence> sourceResult = read(creationRequestId, targetNamespace);
    if (sourceResult.isEmpty()) {
      return Optional.empty();
    }
    FreshTenantCreationEvidence sourceEvidence = sourceResult.orElseThrow();
    if (!expectedRequestDigest.equals(sourceEvidence.requestDigest())
        || !expectedCreationEvidenceDigest.equals(sourceEvidence.evidenceDigest())) {
      throw invalidEvidence("Fresh tenant creator read does not match the exact source evidence");
    }

    Record operationRecord =
        dsl.selectFrom(OPERATION_TABLE)
            .where(OPERATION_ID.eq(sourceEvidence.operationId()))
            .fetchOne();
    if (operationRecord == null) {
      throw invalidEvidence("Fresh tenant creator source operation disappeared during readback");
    }

    Record creatorRecord =
        dsl.selectFrom(CREATOR_QUALIFICATION_TABLE)
            .where(CREATOR_QUALIFICATION_OPERATION_ID.eq(sourceEvidence.operationId()))
            .fetchOne();
    if (creatorRecord == null) {
      if (Boolean.TRUE.equals(operationRecord.get(CREATOR_QUALIFICATION_REQUIRED))) {
        throw invalidEvidence("Qualified fresh tenant operation has no creator evidence");
      }
      return Optional.empty();
    }
    if (!Boolean.TRUE.equals(operationRecord.get(CREATOR_QUALIFICATION_REQUIRED))) {
      throw invalidEvidence("Creator qualification is attached to a source-only operation");
    }
    FreshTenantCreatorEvidence qualification = toCreatorEvidence(creatorRecord, sourceEvidence);
    if (!expectedInitiatingAccountId.equals(qualification.initiatingAccountId())
        || !expectedAccountAuthorizationOperationId.equals(
            qualification.accountAuthorizationOperationId())
        || !expectedAccountAuthorizationDigest.equals(qualification.accountAuthorizationDigest())
        || !expectedCreatorEvidenceDigest.equals(qualification.evidenceDigest())) {
      throw invalidEvidence("Fresh tenant creator qualification does not match the exact request");
    }
    return Optional.of(qualification);
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Fresh tenant creation requires an active writable Game Design owner transaction");
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

  private void validateCreatorBinding(
      UUID initiatingAccountId,
      UUID accountAuthorizationOperationId,
      String accountAuthorizationDigest) {
    if (initiatingAccountId == null || initiatingAccountId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("A canonical nonnil initiating Account UUID is required");
    }
    if (accountAuthorizationOperationId == null
        || accountAuthorizationOperationId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(
          "A canonical nonnil Account authorization operation UUID is required");
    }
    if (!GameTenantCreationDigest.isDigest(accountAuthorizationDigest)) {
      throw new IllegalArgumentException("A canonical Account authorization digest is required");
    }
  }

  private FreshTenantCreatorEvidence readCreatorQualificationForOperation(
      FreshTenantCreationEvidence sourceEvidence) {
    Record creatorRecord =
        dsl.selectFrom(CREATOR_QUALIFICATION_TABLE)
            .where(CREATOR_QUALIFICATION_OPERATION_ID.eq(sourceEvidence.operationId()))
            .fetchOne();
    if (creatorRecord == null) {
      throw invalidEvidence("Qualified fresh tenant operation has no creator evidence");
    }
    return toCreatorEvidence(creatorRecord, sourceEvidence);
  }

  private FreshTenantCreatorEvidence toCreatorEvidence(
      Record record, FreshTenantCreationEvidence sourceEvidence) {
    Integer schemaVersion = record.get(CREATOR_QUALIFICATION_SCHEMA_VERSION);
    UUID initiatingAccountId = record.get(INITIATING_ACCOUNT_ID);
    UUID accountAuthorizationOperationId = record.get(ACCOUNT_AUTHORIZATION_OPERATION_ID);
    String accountAuthorizationDigest = record.get(ACCOUNT_AUTHORIZATION_DIGEST);
    String evidenceDigest = record.get(CREATOR_EVIDENCE_DIGEST);
    if (schemaVersion == null
        || schemaVersion != 1
        || initiatingAccountId == null
        || initiatingAccountId.equals(new UUID(0L, 0L))
        || accountAuthorizationOperationId == null
        || accountAuthorizationOperationId.equals(new UUID(0L, 0L))
        || !GameTenantCreationDigest.isDigest(accountAuthorizationDigest)
        || !GameTenantCreationDigest.isDigest(evidenceDigest)) {
      throw invalidEvidence("Stored fresh tenant creator qualification is incomplete or malformed");
    }
    try {
      return new FreshTenantCreatorEvidence(
          schemaVersion,
          sourceEvidence,
          initiatingAccountId,
          accountAuthorizationOperationId,
          accountAuthorizationDigest,
          evidenceDigest);
    } catch (IllegalArgumentException exception) {
      throw invalidEvidence(
          "Stored fresh tenant creator qualification digest does not match its tuple", exception);
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

  private FreshTenantCreationEvidence toReceipt(Record record) {
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

    return new FreshTenantCreationEvidence(
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
