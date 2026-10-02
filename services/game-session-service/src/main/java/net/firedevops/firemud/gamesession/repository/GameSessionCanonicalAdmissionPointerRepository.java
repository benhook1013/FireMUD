package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalClosedAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalClosedAdmissionPointerRequest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-local persistence for one initial canonical CLOSED admission pointer per realm. */
@Repository
public class GameSessionCanonicalAdmissionPointerRepository {
  private static final int SCHEMA_VERSION = 1;
  private static final int CANONICAL_REPRESENTATION_VERSION = 2;
  private static final long INITIAL_POINTER_VERSION = 1L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String CLOSED = "CLOSED";
  private static final String ABSENT = "ABSENT";
  private static final String REQUEST_DOMAIN = "game-session-canonical-closed-pointer-request/v1";
  private static final String RECEIPT_DOMAIN = "game-session-canonical-closed-pointer-receipt/v1";

  private final DSLContext dsl;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;
  private final GameplayAdmissionPointerEventRepository eventRepository;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected repositories and DSLContext are internal Spring collaborators.")
  public GameSessionCanonicalAdmissionPointerRepository(
      DSLContext dsl,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameplayAdmissionPointerEventRepository eventRepository) {
    this.dsl = dsl;
    this.catalogRepository = catalogRepository;
    this.eventRepository = eventRepository;
  }

  /**
   * Prepares from a committed catalog read without retaining an ambient owner transaction. The
   * returned value is privately constructed and must be revalidated under the catalog row lock.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public PreparedInitialClosed prepareInitialClosed(
      CreateCanonicalClosedAdmissionPointerRequest request) {
    Objects.requireNonNull(request, "request");
    requireCommittedRead();
    CanonicalRealmCatalogSnapshot catalog =
        catalogRepository
            .readByRequest(request.targetNamespace(), request.catalogCreationRequestId())
            .orElseThrow(
                () ->
                    new InvalidCanonicalClosedPointerEvidenceException(
                        "Exact committed initial catalog request is missing"));
    requireExactInitialCatalog(request, catalog);
    return new PreparedInitialClosed(request, catalog, requestDigest(request, catalog));
  }

  /**
   * Commits the CLOSED route, one append-only event, and its immutable request outcome atomically.
   * The caller must perform a separate committed {@link #readByRequest} before returning evidence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void createInitialClosed(PreparedInitialClosed prepared) {
    Objects.requireNonNull(prepared, "prepared");
    requireWritableOwnerTransaction();
    CreateCanonicalClosedAdmissionPointerRequest request = prepared.request();
    CanonicalRealmCatalogSnapshot catalog =
        catalogRepository.lockExactInitialPublicProduction(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.catalogCreationRequestId(),
            request.catalogRevision());
    requireExactInitialCatalog(request, catalog);
    if (!prepared.catalogSnapshot().equals(catalog)) {
      throw new InvalidCanonicalClosedPointerEvidenceException(
          "Locked catalog snapshot changed after canonical CLOSED pointer preparation");
    }

    String requestDigest = requestDigest(request, catalog);
    if (!prepared.requestDigest().equals(requestDigest)) {
      throw new InvalidCanonicalClosedPointerEvidenceException(
          "Prepared canonical CLOSED pointer digest does not match its exact inputs");
    }

    Record priorRequest = findRequest(request.targetNamespace(), request.requestId());
    if (priorRequest != null) {
      requireSameRequest(priorRequest, request, catalog, requestDigest);
      return;
    }
    if (pointerExistsForRealm(request.realmId())) {
      throw new CanonicalClosedPointerConflictException(
          "Canonical realm already has an admission-pointer representation");
    }

    Instant updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Long pointerId;
    long auditEventId;
    String receiptDigest;
    try {
      Record insertedPointer =
          dsl.fetchOne(
              "INSERT INTO gameplay_admission_pointer ("
                  + "world_slug, realm_slug, world_display_name, realm_display_name, tenant_id, "
                  + "game_instance_id, pointer_version, visible, requires_character_selection, "
                  + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
                  + "created_at, updated_at, public_production_realm, catalog_revision, realm_id, "
                  + "playable_state_namespace_id, representation_version, target_namespace, "
                  + "canonical_tenant_id, admission_state) "
                  + "VALUES (?, ?, NULL, NULL, NULL, NULL, 1, NULL, NULL, NULL, NULL, NULL, NULL, "
                  + "?, ?, NULL, ?, ?, NULL, 2, ?, ?, 'CLOSED') RETURNING id",
              catalog.worldSlug(),
              catalog.realmSlug(),
              toLocalDateTime(updatedAt),
              toLocalDateTime(updatedAt),
              catalog.catalogRevision(),
              catalog.realmId(),
              catalog.targetNamespace(),
              catalog.tenantId());
      if (insertedPointer == null) {
        throw new InvalidCanonicalClosedPointerEvidenceException(
            "Canonical CLOSED pointer insert returned no owner row");
      }
      pointerId = insertedPointer.get("id", Long.class);
      if (pointerId == null || pointerId <= 0L) {
        throw new InvalidCanonicalClosedPointerEvidenceException(
            "Canonical CLOSED pointer row id is invalid");
      }

      auditEventId =
          eventRepository.appendCanonicalClosed(
              catalog.targetNamespace(),
              catalog.tenantId(),
              catalog.realmId(),
              catalog.worldSlug(),
              catalog.realmSlug(),
              catalog.catalogRevision(),
              request.actorPrincipal(),
              request.reason(),
              request.requestId(),
              updatedAt);
      receiptDigest = receiptDigest(request, catalog, requestDigest, auditEventId, updatedAt);
      dsl.execute(
          "INSERT INTO game_session_canonical_closed_admission_pointer_request ("
              + "target_namespace, request_id, request_digest, canonical_tenant_id, realm_id, "
              + "catalog_creation_request_id, catalog_revision, catalog_request_digest, "
              + "catalog_receipt_digest, actor_principal, reason, admission_state, pointer_version, "
              + "audit_event_id, receipt_digest, updated_at) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLOSED', 1, ?, ?, ?)",
          request.targetNamespace(),
          request.requestId(),
          requestDigest,
          request.canonicalTenantId(),
          request.realmId(),
          request.catalogCreationRequestId(),
          request.catalogRevision(),
          catalog.requestDigest(),
          catalog.receiptDigest(),
          request.actorPrincipal(),
          request.reason(),
          auditEventId,
          receiptDigest,
          OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC));
    } catch (org.jooq.exception.IntegrityConstraintViolationException exception) {
      throw new CanonicalClosedPointerConflictException(
          "Canonical CLOSED pointer creation conflicts with committed realm or request authority",
          exception);
    }
  }

  /** Reads and verifies the immutable request outcome from committed owner rows. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<CanonicalClosedAdmissionPointerSnapshot> readByRequest(
      String targetNamespace, UUID requestId) {
    requireReadSelector(targetNamespace, requestId);
    requireCommittedRead();
    Record outcome = findRequest(targetNamespace, requestId);
    if (outcome == null) {
      return Optional.empty();
    }
    try {
      CreateCanonicalClosedAdmissionPointerRequest request = requestFromOutcome(outcome);
      if (!targetNamespace.equals(request.targetNamespace())
          || !requestId.equals(request.requestId())) {
        throw new InvalidCanonicalClosedPointerEvidenceException(
            "Canonical CLOSED pointer outcome does not match its read selector");
      }
      CanonicalRealmCatalogSnapshot catalog =
          catalogRepository
              .readByRequest(targetNamespace, request.catalogCreationRequestId())
              .orElseThrow(
                  () ->
                      new InvalidCanonicalClosedPointerEvidenceException(
                          "Canonical CLOSED pointer outcome lost its catalog reference"));
      requireExactInitialCatalog(request, catalog);
      String requestDigest = requestDigest(request, catalog);
      if (!requestDigest.equals(required(outcome, "request_digest", String.class))) {
        throw new InvalidCanonicalClosedPointerEvidenceException(
            "Canonical CLOSED pointer request digest does not match committed request inputs");
      }
      if (!catalog.requestDigest().equals(required(outcome, "catalog_request_digest", String.class))
          || !catalog
              .receiptDigest()
              .equals(required(outcome, "catalog_receipt_digest", String.class))) {
        throw new InvalidCanonicalClosedPointerEvidenceException(
            "Canonical CLOSED pointer outcome does not match its exact catalog digests");
      }

      long eventId = required(outcome, "audit_event_id", Long.class);
      Instant updatedAt = outcomeTimestamp(outcome);
      String receiptDigest = receiptDigest(request, catalog, requestDigest, eventId, updatedAt);
      if (!receiptDigest.equals(required(outcome, "receipt_digest", String.class))) {
        throw new InvalidCanonicalClosedPointerEvidenceException(
            "Canonical CLOSED pointer receipt digest does not match its committed result");
      }
      requireOutcomeMatches(
          outcome, request, catalog, requestDigest, receiptDigest, eventId, updatedAt);

      Record pointer = findCanonicalPointer(request);
      if (pointer == null) {
        throw new InvalidCanonicalClosedPointerEvidenceException(
            "Canonical CLOSED pointer outcome has no committed routing row");
      }
      requirePointerMatches(pointer, request, catalog, updatedAt);

      Record event = findCanonicalEvent(eventId);
      if (event == null) {
        throw new InvalidCanonicalClosedPointerEvidenceException(
            "Canonical CLOSED pointer outcome has no committed audit event");
      }
      requireEventMatches(event, request, catalog, eventId, updatedAt);

      return Optional.of(
          new CanonicalClosedAdmissionPointerSnapshot(
              catalog.targetNamespace(),
              catalog.tenantId(),
              catalog.realmId(),
              catalog.worldSlug(),
              catalog.realmSlug(),
              INITIAL_POINTER_VERSION,
              catalog.catalogRevision(),
              CLOSED,
              null,
              request.requestId(),
              requestDigest,
              receiptDigest,
              request.actorPrincipal(),
              request.reason(),
              eventId,
              updatedAt,
              catalog));
    } catch (InvalidCanonicalClosedPointerEvidenceException
        | GameSessionCanonicalRealmCatalogRepository.InvalidCatalogEvidenceException
        | GameSessionCanonicalRealmCatalogRepository.CatalogConflictException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new InvalidCanonicalClosedPointerEvidenceException(
          "Persisted canonical CLOSED pointer evidence is invalid", exception);
    }
  }

  private Record findRequest(String targetNamespace, UUID requestId) {
    return dsl.fetchOne(
        "SELECT * FROM game_session_canonical_closed_admission_pointer_request "
            + "WHERE target_namespace = ? AND request_id = ?",
        targetNamespace,
        requestId);
  }

  private boolean pointerExistsForRealm(UUID realmId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(org.jooq.impl.DSL.table("gameplay_admission_pointer"))
            .where(org.jooq.impl.DSL.field("realm_id", UUID.class).eq(realmId)));
  }

  private Record findCanonicalPointer(CreateCanonicalClosedAdmissionPointerRequest request) {
    return dsl.fetchOne(
        "SELECT * FROM gameplay_admission_pointer "
            + "WHERE representation_version = 2 AND target_namespace = ? "
            + "AND canonical_tenant_id = ? AND realm_id = ?",
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.realmId());
  }

  private Record findCanonicalEvent(long eventId) {
    return dsl.fetchOne(
        "SELECT * FROM gameplay_admission_pointer_event "
            + "WHERE representation_version = 2 AND id = ?",
        eventId);
  }

  private static CreateCanonicalClosedAdmissionPointerRequest requestFromOutcome(Record outcome) {
    return new CreateCanonicalClosedAdmissionPointerRequest(
        required(outcome, "request_id", UUID.class),
        required(outcome, "target_namespace", String.class),
        required(outcome, "canonical_tenant_id", UUID.class),
        required(outcome, "realm_id", UUID.class),
        required(outcome, "catalog_creation_request_id", UUID.class),
        required(outcome, "catalog_revision", Long.class),
        required(outcome, "actor_principal", String.class),
        required(outcome, "reason", String.class));
  }

  private static void requireSameRequest(
      Record outcome,
      CreateCanonicalClosedAdmissionPointerRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      String requestDigest) {
    if (!request.requestId().equals(required(outcome, "request_id", UUID.class))
        || !requestDigest.equals(required(outcome, "request_digest", String.class))
        || !request.targetNamespace().equals(required(outcome, "target_namespace", String.class))
        || !request.canonicalTenantId().equals(required(outcome, "canonical_tenant_id", UUID.class))
        || !request.realmId().equals(required(outcome, "realm_id", UUID.class))
        || !request
            .catalogCreationRequestId()
            .equals(required(outcome, "catalog_creation_request_id", UUID.class))
        || request.catalogRevision() != required(outcome, "catalog_revision", Long.class)
        || !catalog
            .requestDigest()
            .equals(required(outcome, "catalog_request_digest", String.class))
        || !catalog
            .receiptDigest()
            .equals(required(outcome, "catalog_receipt_digest", String.class))
        || !request.actorPrincipal().equals(required(outcome, "actor_principal", String.class))
        || !request.reason().equals(required(outcome, "reason", String.class))) {
      throw new CanonicalClosedPointerConflictException(
          "Canonical CLOSED pointer request identity was reused with changed input");
    }
  }

  private static void requireExactInitialCatalog(
      CreateCanonicalClosedAdmissionPointerRequest request, CanonicalRealmCatalogSnapshot catalog) {
    if (!catalog.visible()
        || !catalog.publicProduction()
        || catalog.catalogRevision() != request.catalogRevision()
        || catalog.catalogRevision() != INITIAL_POINTER_VERSION
        || !request.targetNamespace().equals(catalog.targetNamespace())
        || !request.canonicalTenantId().equals(catalog.tenantId())
        || !request.realmId().equals(catalog.realmId())
        || !request.catalogCreationRequestId().equals(catalog.creationRequestId())) {
      throw new InvalidCanonicalClosedPointerEvidenceException(
          "Canonical CLOSED pointer request does not identify the exact initial public catalog");
    }
  }

  private static void requireOutcomeMatches(
      Record outcome,
      CreateCanonicalClosedAdmissionPointerRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      String requestDigest,
      String receiptDigest,
      long eventId,
      Instant updatedAt) {
    if (!CLOSED.equals(required(outcome, "admission_state", String.class))
        || required(outcome, "pointer_version", Long.class) != INITIAL_POINTER_VERSION
        || required(outcome, "audit_event_id", Long.class) != eventId
        || !request.requestId().equals(required(outcome, "request_id", UUID.class))
        || !request.targetNamespace().equals(required(outcome, "target_namespace", String.class))
        || !request.canonicalTenantId().equals(required(outcome, "canonical_tenant_id", UUID.class))
        || !request.realmId().equals(required(outcome, "realm_id", UUID.class))
        || !request
            .catalogCreationRequestId()
            .equals(required(outcome, "catalog_creation_request_id", UUID.class))
        || request.catalogRevision() != required(outcome, "catalog_revision", Long.class)
        || !requestDigest.equals(required(outcome, "request_digest", String.class))
        || !receiptDigest.equals(required(outcome, "receipt_digest", String.class))
        || !catalog
            .requestDigest()
            .equals(required(outcome, "catalog_request_digest", String.class))
        || !catalog
            .receiptDigest()
            .equals(required(outcome, "catalog_receipt_digest", String.class))
        || !request.actorPrincipal().equals(required(outcome, "actor_principal", String.class))
        || !request.reason().equals(required(outcome, "reason", String.class))
        || !updatedAt.equals(outcomeTimestamp(outcome))) {
      throw new InvalidCanonicalClosedPointerEvidenceException(
          "Canonical CLOSED pointer outcome conflicts with its request or catalog authority");
    }
  }

  private static void requirePointerMatches(
      Record pointer,
      CreateCanonicalClosedAdmissionPointerRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      Instant updatedAt) {
    if (required(pointer, "representation_version", Integer.class)
            != CANONICAL_REPRESENTATION_VERSION
        || !request.targetNamespace().equals(required(pointer, "target_namespace", String.class))
        || !request.canonicalTenantId().equals(required(pointer, "canonical_tenant_id", UUID.class))
        || !request.realmId().equals(required(pointer, "realm_id", UUID.class))
        || !catalog.worldSlug().equals(required(pointer, "world_slug", String.class))
        || !catalog.realmSlug().equals(required(pointer, "realm_slug", String.class))
        || required(pointer, "pointer_version", Long.class) != INITIAL_POINTER_VERSION
        || required(pointer, "catalog_revision", Long.class) != request.catalogRevision()
        || !CLOSED.equals(required(pointer, "admission_state", String.class))
        || pointer.get("tenant_id", Long.class) != null
        || pointer.get("game_instance_id", Long.class) != null
        || pointer.get("playable_state_namespace_id", UUID.class) != null
        || hasLegacyPointerCatalogCopy(pointer)
        || !updatedAt.equals(toInstant(pointer.get("created_at", java.time.LocalDateTime.class)))
        || !updatedAt.equals(toInstant(pointer.get("updated_at", java.time.LocalDateTime.class)))) {
      throw new InvalidCanonicalClosedPointerEvidenceException(
          "Canonical CLOSED pointer row is aliased, policy-copied or inconsistent with its catalog");
    }
  }

  private static boolean hasLegacyPointerCatalogCopy(Record pointer) {
    return pointer.get("world_display_name", String.class) != null
        || pointer.get("realm_display_name", String.class) != null
        || pointer.get("visible", Boolean.class) != null
        || pointer.get("public_production_realm", Boolean.class) != null
        || pointer.get("requires_character_selection", Boolean.class) != null
        || pointer.get("state_scope", String.class) != null
        || pointer.get("character_creation_policy", String.class) != null
        || pointer.get("last_updated_by", String.class) != null
        || pointer.get("last_update_reason", String.class) != null;
  }

  private static void requireEventMatches(
      Record event,
      CreateCanonicalClosedAdmissionPointerRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      long eventId,
      Instant updatedAt) {
    if (required(event, "id", Long.class) != eventId
        || required(event, "representation_version", Integer.class)
            != CANONICAL_REPRESENTATION_VERSION
        || !request.targetNamespace().equals(required(event, "target_namespace", String.class))
        || !request.canonicalTenantId().equals(required(event, "canonical_tenant_id", UUID.class))
        || !request.realmId().equals(required(event, "realm_id", UUID.class))
        || !catalog.worldSlug().equals(required(event, "world_slug", String.class))
        || !catalog.realmSlug().equals(required(event, "realm_slug", String.class))
        || required(event, "pointer_version", Long.class) != INITIAL_POINTER_VERSION
        || required(event, "catalog_revision", Long.class) != request.catalogRevision()
        || !CLOSED.equals(required(event, "admission_state", String.class))
        || !request
            .requestId()
            .toString()
            .equals(required(event, "control_plane_request_id", String.class))
        || !request.actorPrincipal().equals(required(event, "actor_principal", String.class))
        || !request.reason().equals(required(event, "reason", String.class))
        || event.get("tenant_id", Long.class) != null
        || event.get("game_instance_id", Long.class) != null
        || event.get("prepared_version_upgrade_id", String.class) != null
        || hasLegacyEventCatalogCopy(event)
        || !updatedAt.equals(toInstant(event.get("occurred_at", java.time.LocalDateTime.class)))) {
      throw new InvalidCanonicalClosedPointerEvidenceException(
          "Canonical CLOSED pointer audit event conflicts with its pointer or catalog authority");
    }
  }

  private static boolean hasLegacyEventCatalogCopy(Record event) {
    return event.get("world_display_name", String.class) != null
        || event.get("realm_display_name", String.class) != null
        || event.get("visible", Boolean.class) != null
        || event.get("public_production_realm", Boolean.class) != null
        || event.get("requires_character_selection", Boolean.class) != null
        || event.get("state_scope", String.class) != null
        || event.get("character_creation_policy", String.class) != null;
  }

  private static Instant outcomeTimestamp(Record outcome) {
    OffsetDateTime updatedAt = required(outcome, "updated_at", OffsetDateTime.class);
    return updatedAt.toInstant();
  }

  static String requestDigest(
      CreateCanonicalClosedAdmissionPointerRequest request, CanonicalRealmCatalogSnapshot catalog) {
    return digest(requestDigestFields(request, catalog));
  }

  private static List<String> requestDigestFields(
      CreateCanonicalClosedAdmissionPointerRequest request, CanonicalRealmCatalogSnapshot catalog) {
    return List.of(
        REQUEST_DOMAIN,
        Integer.toString(SCHEMA_VERSION),
        request.requestId().toString(),
        request.targetNamespace(),
        request.canonicalTenantId().toString(),
        request.realmId().toString(),
        request.catalogCreationRequestId().toString(),
        Long.toString(request.catalogRevision()),
        catalog.requestDigest(),
        catalog.receiptDigest(),
        request.actorPrincipal(),
        request.reason(),
        CLOSED,
        ABSENT);
  }

  static String receiptDigest(
      CreateCanonicalClosedAdmissionPointerRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      String requestDigest,
      long auditEventId,
      Instant updatedAt) {
    return digest(receiptDigestFields(request, catalog, requestDigest, auditEventId, updatedAt));
  }

  private static List<String> receiptDigestFields(
      CreateCanonicalClosedAdmissionPointerRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      String requestDigest,
      long auditEventId,
      Instant updatedAt) {
    requirePositive(auditEventId, "auditEventId");
    return List.of(
        RECEIPT_DOMAIN,
        Integer.toString(SCHEMA_VERSION),
        request.requestId().toString(),
        requestDigest,
        request.targetNamespace(),
        request.canonicalTenantId().toString(),
        request.realmId().toString(),
        catalog.worldSlug(),
        catalog.realmSlug(),
        Long.toString(INITIAL_POINTER_VERSION),
        Long.toString(request.catalogRevision()),
        Long.toString(auditEventId),
        Objects.requireNonNull(updatedAt, "updatedAt").toString(),
        CLOSED,
        ABSENT);
  }

  private static String digest(List<String> fields) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      for (String value : fields) {
        byte[] bytes = strictUtf8(value);
        hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        hash.update((byte) ':');
        hash.update(bytes);
      }
      return "sha256:" + HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static byte[] strictUtf8(String value) {
    Objects.requireNonNull(value, "digest value");
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Digest inputs must be well-formed UTF-8 text", exception);
    }
  }

  private static void requireReadSelector(String targetNamespace, UUID requestId) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    requireNonNil(requestId, "requestId");
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) {
      throw new InvalidCanonicalClosedPointerEvidenceException(name + " must be positive");
    }
  }

  private static <T> T required(Record record, String fieldName, Class<T> type) {
    T value = record.get(fieldName, type);
    if (value == null) {
      throw new InvalidCanonicalClosedPointerEvidenceException(
          "Persisted canonical CLOSED pointer " + fieldName + " is missing");
    }
    return value;
  }

  private void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical CLOSED pointer creation requires an active owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical CLOSED pointer creation requires a read-write transaction");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                "Canonical CLOSED pointer DSL connection must join the owner transaction");
          }
          if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Canonical CLOSED pointer creation requires READ COMMITTED isolation");
          }
          return null;
        });
    Record isolationRow = dsl.fetchOne("SHOW transaction_isolation");
    if (isolationRow == null
        || !"read committed".equalsIgnoreCase(isolationRow.get(0, String.class).trim())) {
      throw new IllegalStateException(
          "Canonical CLOSED pointer creation requires READ COMMITTED isolation");
    }
    Record readOnlyRow = dsl.fetchOne("SHOW transaction_read_only");
    if (readOnlyRow == null || !"off".equalsIgnoreCase(readOnlyRow.get(0, String.class).trim())) {
      throw new IllegalStateException(
          "Canonical CLOSED pointer creation requires a read-write transaction");
    }
  }

  private void requireCommittedRead() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical CLOSED pointer operation requires a committed-outcome owner read");
    }
  }

  /**
   * Opaque preflight result; callers cannot construct a preparation or change its catalog proof.
   */
  public static final class PreparedInitialClosed {
    private final CreateCanonicalClosedAdmissionPointerRequest request;
    private final CanonicalRealmCatalogSnapshot catalogSnapshot;
    private final String requestDigest;

    private PreparedInitialClosed(
        CreateCanonicalClosedAdmissionPointerRequest request,
        CanonicalRealmCatalogSnapshot catalogSnapshot,
        String requestDigest) {
      this.request = Objects.requireNonNull(request, "request");
      this.catalogSnapshot = Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
      this.requestDigest = Objects.requireNonNull(requestDigest, "requestDigest");
    }

    private CreateCanonicalClosedAdmissionPointerRequest request() {
      return request;
    }

    private CanonicalRealmCatalogSnapshot catalogSnapshot() {
      return catalogSnapshot;
    }

    private String requestDigest() {
      return requestDigest;
    }
  }

  public static class CanonicalClosedPointerConflictException extends IllegalStateException {
    public CanonicalClosedPointerConflictException(String message) {
      super(message);
    }

    public CanonicalClosedPointerConflictException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class InvalidCanonicalClosedPointerEvidenceException extends IllegalStateException {
    public InvalidCanonicalClosedPointerEvidenceException(String message) {
      super(message);
    }

    public InvalidCanonicalClosedPointerEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
