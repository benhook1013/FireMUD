package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable non-authoritative reservation of a Game Design-issued fresh tenant identity. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected jOOQ collaborators are internal Spring components.")
public class GameTenantCreationReservationRepository {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Table<?> RESERVATION_TABLE =
      DSL.table(DSL.name("game_tenant_creation_reservations"));
  private static final Field<Integer> SCHEMA_VERSION_FIELD =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CREATION_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final Field<String> DESCRIPTION = DSL.field(DSL.name("description"), String.class);

  private final DSLContext dsl;

  public GameTenantCreationReservationRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Reserves one canonical UUID and stable creation operation identity in an owner transaction.
   *
   * <p>This record is preparation only. It creates no game, creator qualification, administrator,
   * admission state, or content.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public FreshTenantCreationReservation reserve(
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
    FreshTenantCreationReservation requested =
        new FreshTenantCreationReservation(
            SCHEMA_VERSION,
            targetNamespace,
            creationRequestId,
            requestDigest,
            newNonNilUuid(),
            newNonNilUuid(),
            sourceGameTenantKey,
            name,
            description);

    int inserted =
        dsl.insertInto(RESERVATION_TABLE)
            .set(SCHEMA_VERSION_FIELD, requested.schemaVersion())
            .set(TARGET_NAMESPACE, requested.targetNamespace())
            .set(CREATION_REQUEST_ID, requested.creationRequestId())
            .set(REQUEST_DIGEST, requested.requestDigest())
            .set(OPERATION_ID, requested.operationId())
            .set(CANONICAL_TENANT_ID, requested.canonicalTenantId())
            .set(SOURCE_GAME_TENANT_KEY, requested.sourceGameTenantKey())
            .set(NAME, requested.name())
            .set(DESCRIPTION, requested.description())
            .onConflictDoNothing()
            .execute();

    if (inserted == 0) {
      Record existingRecord = select(targetNamespace, creationRequestId);
      if (existingRecord == null) {
        throw new ReservationConflictException(
            "Fresh tenant reservation request or source identity is already claimed");
      }
      FreshTenantCreationReservation existing = toReservation(existingRecord);
      requireSameInput(existing, requested);
      return existing;
    }

    FreshTenantCreationReservation persisted =
        toReservation(selectRequired(targetNamespace, creationRequestId));
    if (!requested.equals(persisted)) {
      throw new InvalidReservationException(
          "Fresh tenant reservation did not exactly read back after insertion");
    }
    return persisted;
  }

  /** Independently reads a committed reservation and compares its complete original request. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<FreshTenantCreationReservation> read(
      String targetNamespace, UUID creationRequestId, String expectedRequestDigest) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh tenant reservation read requires an independent committed-outcome read");
    }
    validateSelector(targetNamespace, creationRequestId);
    if (!GameTenantCreationDigest.isDigest(expectedRequestDigest)) {
      throw new IllegalArgumentException("A canonical expected request digest is required");
    }
    Record record = select(targetNamespace, creationRequestId);
    if (record == null) {
      return Optional.empty();
    }
    FreshTenantCreationReservation reservation = toReservation(record);
    if (!expectedRequestDigest.equals(reservation.requestDigest())) {
      throw new ReservationConflictException(
          "Fresh tenant reservation read does not match the exact request digest");
    }
    return Optional.of(reservation);
  }

  /** Independently reads and exact-compares every persisted reservation field. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<FreshTenantCreationReservation> readExact(
      FreshTenantCreationReservation expected) {
    Objects.requireNonNull(expected, "expected");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh tenant reservation read requires an independent committed-outcome read");
    }
    Optional<FreshTenantCreationReservation> result =
        read(expected.targetNamespace(), expected.creationRequestId(), expected.requestDigest());
    if (result.isPresent() && !expected.equals(result.orElseThrow())) {
      throw new ReservationConflictException(
          "Fresh tenant reservation read does not match the exact persisted identity");
    }
    return result;
  }

  private Record select(String targetNamespace, UUID creationRequestId) {
    return dsl.selectFrom(RESERVATION_TABLE)
        .where(TARGET_NAMESPACE.eq(targetNamespace).and(CREATION_REQUEST_ID.eq(creationRequestId)))
        .fetchOne();
  }

  private Record selectRequired(String targetNamespace, UUID creationRequestId) {
    Record record = select(targetNamespace, creationRequestId);
    if (record == null) {
      throw new InvalidReservationException("Persisted fresh tenant reservation is missing");
    }
    return record;
  }

  private FreshTenantCreationReservation toReservation(Record record) {
    Integer schemaVersion = record.get(SCHEMA_VERSION_FIELD);
    String targetNamespace = record.get(TARGET_NAMESPACE);
    UUID creationRequestId = record.get(CREATION_REQUEST_ID);
    String requestDigest = record.get(REQUEST_DIGEST);
    UUID operationId = record.get(OPERATION_ID);
    UUID canonicalTenantId = record.get(CANONICAL_TENANT_ID);
    String sourceGameTenantKey = record.get(SOURCE_GAME_TENANT_KEY);
    String name = record.get(NAME);
    String description = record.get(DESCRIPTION);
    if (schemaVersion == null || schemaVersion != SCHEMA_VERSION) {
      throw new InvalidReservationException(
          "Stored fresh tenant reservation schema is unsupported");
    }
    try {
      return new FreshTenantCreationReservation(
          schemaVersion,
          targetNamespace,
          creationRequestId,
          requestDigest,
          operationId,
          canonicalTenantId,
          sourceGameTenantKey,
          name,
          description);
    } catch (IllegalArgumentException exception) {
      throw new InvalidReservationException(
          "Stored fresh tenant reservation is incomplete or inconsistent", exception);
    }
  }

  private void requireSameInput(
      FreshTenantCreationReservation existing, FreshTenantCreationReservation requested) {
    if (!existing.targetNamespace().equals(requested.targetNamespace())
        || !existing.creationRequestId().equals(requested.creationRequestId())
        || !existing.requestDigest().equals(requested.requestDigest())
        || !existing.sourceGameTenantKey().equals(requested.sourceGameTenantKey())
        || !existing.name().equals(requested.name())
        || !Objects.equals(existing.description(), requested.description())) {
      throw new ReservationConflictException(
          "Fresh tenant reservation identity was reused with changed input");
    }
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Fresh tenant reservation requires an active writable Game Design owner transaction");
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
    if (sourceGameTenantKey.isBlank()
        || sourceGameTenantKey.length() > 72
        || sourceGameTenantKey.codePointCount(0, sourceGameTenantKey.length()) > 36) {
      throw new IllegalArgumentException("sourceGameTenantKey must fit the game tenant key bound");
    }
    if (name.length() > 200 || name.codePointCount(0, name.length()) > 100) {
      throw new IllegalArgumentException("name must fit the game name bound");
    }
    if (description != null
        && (description.length() > 510
            || description.codePointCount(0, description.length()) > 255
            || GameTenantCreationDigest.utf8ByteLength(description) > 1020)) {
      throw new IllegalArgumentException("description must fit the game description bound");
    }
  }

  private void validateSelector(String targetNamespace, UUID creationRequestId) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(creationRequestId, "creationRequestId");
    if (!DNS_LABEL.matcher(targetNamespace).matches()) {
      throw new IllegalArgumentException("targetNamespace must be one lowercase DNS label");
    }
    if (creationRequestId.equals(NIL_UUID)) {
      throw new IllegalArgumentException("creationRequestId must not be nil");
    }
  }

  private UUID newNonNilUuid() {
    UUID id;
    do {
      id = UUID.randomUUID();
    } while (id.equals(NIL_UUID));
    return id;
  }

  /** Indicates changed reuse of a reservation request or a source-key collision. */
  public static final class ReservationConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public ReservationConflictException(String message) {
      super(message);
    }
  }

  /** Indicates stored reservation evidence that cannot be trusted. */
  public static final class InvalidReservationException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public InvalidReservationException(String message) {
      super(message);
    }

    public InvalidReservationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
