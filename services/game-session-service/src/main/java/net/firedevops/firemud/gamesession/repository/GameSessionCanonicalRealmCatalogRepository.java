package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Game Session-owned immutable initial realm catalog creation and exact readback.
 *
 * <p>This is an unwired catalog source. It never creates or opens an admission pointer and does not
 * establish World lifecycle, membership, entitlement, or creator authorization.
 */
@Repository
public class GameSessionCanonicalRealmCatalogRepository {
  private static final int SCHEMA_VERSION = 1;
  private static final long INITIAL_CATALOG_REVISION = 1L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String REQUEST_DOMAIN = "game-session-canonical-realm-catalog-request/v1";
  private static final String RECEIPT_DOMAIN = "game-session-canonical-realm-catalog-receipt/v1";

  private static final Table<?> CATALOG =
      DSL.table(DSL.name("game_session_canonical_realm_catalog"));
  private static final Table<?> SHARED_NAMESPACES =
      DSL.table(DSL.name("gameplay_tenant_shared_playable_state_namespace"));

  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> TENANT_SLUG = DSL.field(DSL.name("tenant_slug"), String.class);
  private static final Field<String> WORLD_SLUG = DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<UUID> REALM_ID = DSL.field(DSL.name("realm_id"), UUID.class);
  private static final Field<String> REALM_SLUG = DSL.field(DSL.name("realm_slug"), String.class);
  private static final Field<String> REALM_DISPLAY_NAME =
      DSL.field(DSL.name("realm_display_name"), String.class);
  private static final Field<Boolean> VISIBLE = DSL.field(DSL.name("visible"), Boolean.class);
  private static final Field<Boolean> PUBLIC_PRODUCTION =
      DSL.field(DSL.name("public_production"), Boolean.class);
  private static final Field<String> STATE_SCOPE = DSL.field(DSL.name("state_scope"), String.class);
  private static final Field<UUID> PLAYABLE_STATE_NAMESPACE_ID =
      DSL.field(DSL.name("playable_state_namespace_id"), UUID.class);
  private static final Field<String> CHARACTER_CREATION_POLICY =
      DSL.field(DSL.name("character_creation_policy"), String.class);
  private static final Field<Long> CATALOG_REVISION =
      DSL.field(DSL.name("catalog_revision"), Long.class);
  private static final Field<UUID> CREATION_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<String> RECEIPT_DIGEST =
      DSL.field(DSL.name("receipt_digest"), String.class);
  private static final Field<UUID> SOURCE_INTAKE_OPERATION_ID =
      DSL.field(DSL.name("source_intake_operation_id"), UUID.class);
  private static final Field<Integer> SOURCE_INTAKE_SCHEMA_VERSION =
      DSL.field(DSL.name("source_intake_schema_version"), Integer.class);
  private static final Field<UUID> SOURCE_INTAKE_REQUEST_ID =
      DSL.field(DSL.name("source_intake_request_id"), UUID.class);
  private static final Field<String> SOURCE_INTAKE_REQUEST_DIGEST =
      DSL.field(DSL.name("source_intake_request_digest"), String.class);
  private static final Field<String> SOURCE_INTAKE_RECEIPT_DIGEST =
      DSL.field(DSL.name("source_intake_receipt_digest"), String.class);
  private static final Field<Integer> SOURCE_SCHEMA_VERSION =
      DSL.field(DSL.name("source_schema_version"), Integer.class);
  private static final Field<UUID> SOURCE_REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("source_registration_request_id"), UUID.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<String> SOURCE_REQUEST_DIGEST =
      DSL.field(DSL.name("source_request_digest"), String.class);
  private static final Field<String> SOURCE_WORLD_DISPLAY_NAME =
      DSL.field(DSL.name("source_world_display_name"), String.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> SOURCE_PROVENANCE_KIND =
      DSL.field(DSL.name("source_provenance_kind"), String.class);
  private static final Field<String> BINDING_PROVENANCE_KIND =
      DSL.field(DSL.name("provenance_kind"), String.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);

  private static final Field<Long> LEGACY_TENANT_ID = DSL.field(DSL.name("tenant_id"), Long.class);
  private static final Field<UUID> SHARED_NAMESPACE_ID =
      DSL.field(DSL.name("playable_state_namespace_id"), UUID.class);

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Fail-fast validation is local; no overridable callback or partially initialized object escapes.")
  public GameSessionCanonicalRealmCatalogRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
  }

  /**
   * Resolves exact committed authored-world evidence before the catalog write transaction begins.
   * The returned opaque prepared value can only be constructed by this repository and is verified
   * again against the locked immutable owner rows during creation.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public PreparedInitialPublicProduction prepareInitialPublicProduction(
      CreateCanonicalRealmCatalogRequest request) {
    Objects.requireNonNull(request, "request");
    requireCommittedRead();
    Record binding =
        dsl.fetchOne(
            "SELECT * FROM game_session_authored_world_tenant_source_binding "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
            request.targetNamespace(),
            request.canonicalTenantId());
    if (binding == null) {
      throw new InvalidCatalogEvidenceException(
          "Canonical tenant source binding is missing from committed Game Session owner storage");
    }
    Record intake =
        dsl.fetchOne(
            "SELECT * FROM game_session_authored_world_source_intake "
                + "WHERE target_namespace = ? AND operation_id = ?",
            request.targetNamespace(),
            request.sourceIntakeOperationId());
    if (intake == null) {
      throw new InvalidCatalogEvidenceException(
          "Exact committed authored-world source intake is missing");
    }
    IntakeReceipt committedSource = toIntakeReceipt(intake);
    requireTenantBindingMatches(binding, committedSource.source());
    requireRequestMatchesSource(request, committedSource);
    requireFreshSource(committedSource.source());
    requireNoRetainedTenantAssociation(request.targetNamespace(), request.canonicalTenantId());
    return new PreparedInitialPublicProduction(request, committedSource);
  }

  /**
   * Creates one public-production catalog snapshot under the caller's read-write owner transaction.
   * Exact retries read back the original immutable result; changed intent conflicts.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CanonicalRealmCatalogSnapshot createInitialPublicProduction(
      PreparedInitialPublicProduction prepared) {
    Objects.requireNonNull(prepared, "prepared");
    CreateCanonicalRealmCatalogRequest request = prepared.request();
    requireWritableOwnerTransaction();

    IntakeReceipt sourceReceipt = lockAndReadFreshSource(request);
    if (!prepared.committedSource().equals(sourceReceipt)) {
      throw new InvalidCatalogEvidenceException(
          "Committed authored-world evidence changed before canonical catalog creation");
    }
    // Retained association capture locks the V6 namespace family before committing an association.
    // Taking the same relation lock before this check serializes both SHARED and ISOLATED creation
    // against that owner transaction without deriving a link to any retained numeric tenant.
    dsl.execute("LOCK TABLE gameplay_tenant_shared_playable_state_namespace IN ROW EXCLUSIVE MODE");
    requireNoRetainedTenantAssociation(request.targetNamespace(), request.canonicalTenantId());

    String requestDigest = requestDigest(request, sourceReceipt);
    Record existing = findByRequest(request.targetNamespace(), request.requestId());
    if (existing != null) {
      return requireExactRetry(existing, request, sourceReceipt, requestDigest);
    }
    if (findPublicForTenant(request.targetNamespace(), request.canonicalTenantId()) != null) {
      throw new CatalogConflictException(
          "Canonical tenant already has a public-production realm catalog result");
    }
    if (findByRealmSlug(request.targetNamespace(), request.canonicalTenantId(), request.realmSlug())
        != null) {
      throw new CatalogConflictException("Realm selector is already owned by this tenant");
    }

    UUID realmId = UUID.randomUUID();
    requireNonNil(realmId, "allocated realmId");
    UUID namespaceId =
        "SHARED".equals(request.stateScope())
            ? getOrCreateSharedNamespace(request.targetNamespace(), sourceReceipt)
            : allocateIsolatedNamespace();
    UUID namespace = namespaceId;
    String receiptDigest =
        receiptDigest(
            request, sourceReceipt, realmId, namespace, INITIAL_CATALOG_REVISION, requestDigest);

    int inserted =
        dsl.insertInto(CATALOG)
            .set(TARGET_NAMESPACE, request.targetNamespace())
            .set(CANONICAL_TENANT_ID, request.canonicalTenantId())
            .set(TENANT_SLUG, sourceReceipt.source().tenantSlug())
            .set(WORLD_SLUG, sourceReceipt.source().worldSlug())
            .set(REALM_ID, realmId)
            .set(REALM_SLUG, request.realmSlug())
            .set(REALM_DISPLAY_NAME, request.realmDisplayName())
            .set(VISIBLE, request.visible())
            .set(PUBLIC_PRODUCTION, request.publicProduction())
            .set(STATE_SCOPE, request.stateScope())
            .set(PLAYABLE_STATE_NAMESPACE_ID, namespace)
            .set(CHARACTER_CREATION_POLICY, request.characterCreationPolicy())
            .set(CATALOG_REVISION, INITIAL_CATALOG_REVISION)
            .set(CREATION_REQUEST_ID, request.requestId())
            .set(REQUEST_DIGEST, requestDigest)
            .set(RECEIPT_DIGEST, receiptDigest)
            .set(SOURCE_INTAKE_OPERATION_ID, sourceReceipt.operationId())
            .set(SOURCE_INTAKE_SCHEMA_VERSION, SCHEMA_VERSION)
            .set(SOURCE_INTAKE_REQUEST_ID, sourceReceipt.intakeRequestId())
            .set(SOURCE_INTAKE_REQUEST_DIGEST, sourceReceipt.requestDigest())
            .set(SOURCE_INTAKE_RECEIPT_DIGEST, sourceReceipt.receiptDigest())
            .set(SOURCE_SCHEMA_VERSION, sourceReceipt.source().schemaVersion())
            .set(SOURCE_REGISTRATION_REQUEST_ID, sourceReceipt.source().registrationRequestId())
            .set(SOURCE_OPERATION_ID, sourceReceipt.source().operationId())
            .set(SOURCE_REQUEST_DIGEST, sourceReceipt.source().requestDigest())
            .set(SOURCE_WORLD_DISPLAY_NAME, sourceReceipt.source().worldDisplayName())
            .set(SOURCE_GAME_ROW_ID, sourceReceipt.source().sourceGameRowId())
            .set(SOURCE_GAME_TENANT_KEY, sourceReceipt.source().sourceGameTenantKey())
            .set(SOURCE_PROVENANCE_KIND, sourceReceipt.source().provenanceKind())
            .set(SOURCE_EVIDENCE_DIGEST, sourceReceipt.source().evidenceDigest())
            .onConflictDoNothing()
            .execute();

    Record persisted = findByRequest(request.targetNamespace(), request.requestId());
    if (persisted == null) {
      if (inserted == 0) {
        throw new CatalogConflictException(
            "Canonical realm selector or public-production claim conflicts with existing authority");
      }
      throw new InvalidCatalogEvidenceException(
          "Canonical realm catalog creation did not read back its persisted request");
    }
    return requireExactRetry(persisted, request, sourceReceipt, requestDigest);
  }

  /** Reads one exact immutable result by its stable creation request identity. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<CanonicalRealmCatalogSnapshot> readByRequest(
      String targetNamespace, UUID requestId) {
    requireReadSelector(targetNamespace, requestId);
    requireCommittedRead();
    Record record = findByRequest(targetNamespace, requestId);
    return record == null ? Optional.empty() : Optional.of(toSnapshot(record));
  }

  /**
   * Returns the unique visible public-production catalog snapshot, or no authority when absent.
   * Multiple or internally conflicting results fail closed rather than selecting by order.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<CanonicalRealmCatalogSnapshot> readUniqueVisiblePublicProduction(
      String targetNamespace, UUID canonicalTenantId) {
    requireReadSelector(targetNamespace, canonicalTenantId);
    requireCommittedRead();
    var rows =
        dsl.selectFrom(CATALOG)
            .where(
                TARGET_NAMESPACE
                    .eq(targetNamespace)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(PUBLIC_PRODUCTION.isTrue()))
            .orderBy(CREATION_REQUEST_ID.asc())
            .limit(2)
            .fetch();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() != 1) {
      throw new CatalogConflictException(
          "Public-production catalog authority is ambiguous for this canonical tenant");
    }
    CanonicalRealmCatalogSnapshot snapshot = toSnapshot(rows.getFirst());
    if (!snapshot.visible() || !snapshot.publicProduction()) {
      throw new InvalidCatalogEvidenceException(
          "Public-production catalog readback is not visible player-addressable authority");
    }
    return Optional.of(snapshot);
  }

  private IntakeReceipt lockAndReadFreshSource(CreateCanonicalRealmCatalogRequest request) {
    Record binding =
        dsl.fetchOne(
            "SELECT * FROM game_session_authored_world_tenant_source_binding "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? FOR UPDATE",
            request.targetNamespace(),
            request.canonicalTenantId());
    if (binding == null) {
      throw new InvalidCatalogEvidenceException(
          "Canonical tenant source binding is missing from Game Session owner storage");
    }

    // Lock order is stable: tenant source-binding row, then exact authored-world intake row,
    // followed by the shared-namespace family serialization lock in createInitialPublicProduction.
    Record intake =
        dsl.fetchOne(
            "SELECT * FROM game_session_authored_world_source_intake "
                + "WHERE target_namespace = ? AND operation_id = ? FOR UPDATE",
            request.targetNamespace(),
            request.sourceIntakeOperationId());
    if (intake == null) {
      throw new InvalidCatalogEvidenceException(
          "Exact committed authored-world source intake is missing");
    }
    IntakeReceipt receipt = toIntakeReceipt(intake);
    requireTenantBindingMatches(binding, receipt.source());
    requireRequestMatchesSource(request, receipt);
    requireFreshSource(receipt.source());
    return receipt;
  }

  private void requireRequestMatchesSource(
      CreateCanonicalRealmCatalogRequest request, IntakeReceipt receipt) {
    if (!request.targetNamespace().equals(receipt.source().targetNamespace())
        || !request.canonicalTenantId().equals(receipt.source().canonicalTenantId())
        || !request.sourceIntakeOperationId().equals(receipt.operationId())) {
      throw new InvalidCatalogEvidenceException(
          "Authored-world source intake does not match the exact requested tenant scope");
    }
  }

  private UUID getOrCreateSharedNamespace(String targetNamespace, IntakeReceipt sourceReceipt) {
    UUID tenantId = sourceReceipt.source().canonicalTenantId();
    Record current =
        dsl.fetchOne(
            "SELECT * FROM gameplay_tenant_shared_playable_state_namespace "
                + "WHERE canonical_tenant_id = ? FOR UPDATE",
            tenantId);
    if (current != null) {
      UUID currentId = required(current, SHARED_NAMESPACE_ID, "shared playable-state namespace");
      validateSharedNamespaceSource(current);
      if (!targetNamespace.equals(required(current, TARGET_NAMESPACE, "shared namespace target"))) {
        throw new CatalogConflictException(
            "Canonical tenant shared namespace belongs to a different Game Session namespace");
      }
      if (current.get(LEGACY_TENANT_ID) != null) {
        throw new InvalidCatalogEvidenceException(
            "Canonical shared namespace conflicts with an existing numeric namespace row");
      }
      return currentId;
    }

    UUID namespaceId = UUID.randomUUID();
    requireNonNil(namespaceId, "allocated shared namespace");
    AuthoredWorldSourceEvidence source = sourceReceipt.source();
    int inserted =
        dsl.insertInto(SHARED_NAMESPACES)
            .set(LEGACY_TENANT_ID, (Long) null)
            .set(SHARED_NAMESPACE_ID, namespaceId)
            .set(CANONICAL_TENANT_ID, tenantId)
            .set(TARGET_NAMESPACE, targetNamespace)
            .set(SOURCE_INTAKE_OPERATION_ID, sourceReceipt.operationId())
            .set(SOURCE_INTAKE_SCHEMA_VERSION, SCHEMA_VERSION)
            .set(SOURCE_INTAKE_REQUEST_ID, sourceReceipt.intakeRequestId())
            .set(SOURCE_INTAKE_REQUEST_DIGEST, sourceReceipt.requestDigest())
            .set(SOURCE_INTAKE_RECEIPT_DIGEST, sourceReceipt.receiptDigest())
            .set(SOURCE_SCHEMA_VERSION, source.schemaVersion())
            .set(SOURCE_REGISTRATION_REQUEST_ID, source.registrationRequestId())
            .set(SOURCE_OPERATION_ID, source.operationId())
            .set(SOURCE_REQUEST_DIGEST, source.requestDigest())
            .set(SOURCE_GAME_ROW_ID, source.sourceGameRowId())
            .set(SOURCE_GAME_TENANT_KEY, source.sourceGameTenantKey())
            .set(SOURCE_PROVENANCE_KIND, source.provenanceKind())
            .set(SOURCE_EVIDENCE_DIGEST, source.evidenceDigest())
            .onConflictDoNothing()
            .execute();
    if (inserted == 0) {
      Record raced =
          dsl.fetchOne(
              "SELECT * FROM gameplay_tenant_shared_playable_state_namespace "
                  + "WHERE canonical_tenant_id = ? FOR UPDATE",
              tenantId);
      if (raced != null) {
        validateSharedNamespaceSource(raced);
        if (raced.get(LEGACY_TENANT_ID) == null
            && targetNamespace.equals(
                required(raced, TARGET_NAMESPACE, "shared namespace target"))) {
          return required(raced, SHARED_NAMESPACE_ID, "shared playable-state namespace");
        }
      }
      throw new CatalogConflictException("Canonical shared namespace allocation conflicts");
    }
    Record persisted =
        dsl.fetchOne(
            "SELECT * FROM gameplay_tenant_shared_playable_state_namespace "
                + "WHERE canonical_tenant_id = ?",
            tenantId);
    if (persisted == null) {
      throw new InvalidCatalogEvidenceException(
          "Fresh shared namespace allocation did not read back from Game Session storage");
    }
    validateSharedNamespaceSource(persisted);
    return required(persisted, SHARED_NAMESPACE_ID, "shared playable-state namespace");
  }

  private UUID allocateIsolatedNamespace() {
    UUID namespaceId = UUID.randomUUID();
    requireNonNil(namespaceId, "allocated isolated namespace");
    if (dsl.fetchOne(
            "SELECT playable_state_namespace_id "
                + "FROM gameplay_tenant_shared_playable_state_namespace "
                + "WHERE playable_state_namespace_id = ?",
            namespaceId)
        != null) {
      throw new CatalogConflictException(
          "Allocated isolated namespace collides with shared identity");
    }
    return namespaceId;
  }

  private void validateSharedNamespaceSource(Record namespace) {
    UUID tenantId = required(namespace, CANONICAL_TENANT_ID, "canonical shared tenant id");
    String targetNamespace = required(namespace, TARGET_NAMESPACE, "shared namespace target");
    UUID intakeOperation =
        required(namespace, SOURCE_INTAKE_OPERATION_ID, "shared namespace source operation");
    Record intake =
        dsl.fetchOne(
            "SELECT * FROM game_session_authored_world_source_intake "
                + "WHERE target_namespace = ? AND operation_id = ?",
            targetNamespace,
            intakeOperation);
    if (intake == null) {
      throw new InvalidCatalogEvidenceException(
          "Fresh shared namespace source intake readback is missing");
    }
    IntakeReceipt receipt = toIntakeReceipt(intake);
    AuthoredWorldSourceEvidence source = receipt.source();
    if (!tenantId.equals(source.canonicalTenantId())
        || !targetNamespace.equals(source.targetNamespace())
        || !receipt
            .intakeRequestId()
            .equals(
                required(namespace, SOURCE_INTAKE_REQUEST_ID, "shared namespace intake request"))
        || !receipt
            .requestDigest()
            .equals(
                required(namespace, SOURCE_INTAKE_REQUEST_DIGEST, "shared namespace intake digest"))
        || !receipt
            .receiptDigest()
            .equals(
                required(
                    namespace, SOURCE_INTAKE_RECEIPT_DIGEST, "shared namespace receipt digest"))
        || !source
            .registrationRequestId()
            .equals(
                required(
                    namespace, SOURCE_REGISTRATION_REQUEST_ID, "shared namespace registration"))
        || !source
            .operationId()
            .equals(required(namespace, SOURCE_OPERATION_ID, "shared namespace source operation"))
        || !source
            .requestDigest()
            .equals(
                required(
                    namespace, SOURCE_REQUEST_DIGEST, "shared namespace source request digest"))
        || !Long.valueOf(source.sourceGameRowId())
            .equals(required(namespace, SOURCE_GAME_ROW_ID, "shared namespace source row"))
        || !source
            .sourceGameTenantKey()
            .equals(
                required(namespace, SOURCE_GAME_TENANT_KEY, "shared namespace source tenant key"))
        || !source
            .provenanceKind()
            .equals(required(namespace, SOURCE_PROVENANCE_KIND, "shared namespace provenance"))
        || !source
            .evidenceDigest()
            .equals(
                required(namespace, SOURCE_EVIDENCE_DIGEST, "shared namespace evidence digest"))) {
      throw new InvalidCatalogEvidenceException(
          "Fresh shared namespace differs from its immutable authored-world source receipt");
    }
    requireFreshSource(source);
    requireNoRetainedTenantAssociation(targetNamespace, tenantId);
  }

  private CanonicalRealmCatalogSnapshot requireExactRetry(
      Record existing,
      CreateCanonicalRealmCatalogRequest request,
      IntakeReceipt sourceReceipt,
      String expectedRequestDigest) {
    CanonicalRealmCatalogSnapshot snapshot = toSnapshot(existing);
    if (!request.requestId().equals(snapshot.creationRequestId())
        || !expectedRequestDigest.equals(snapshot.requestDigest())
        || !sourceReceipt.equals(snapshot.sourceIntakeReceipt())) {
      throw new CatalogConflictException(
          "Canonical realm creation request identity was reused with changed input or source evidence");
    }
    return snapshot;
  }

  private CanonicalRealmCatalogSnapshot toSnapshot(Record record) {
    try {
      IntakeReceipt sourceReceipt = toCatalogSourceReceipt(record);
      UUID requestId = required(record, CREATION_REQUEST_ID, "catalog request id");
      String targetNamespace = required(record, TARGET_NAMESPACE, "catalog target namespace");
      UUID canonicalTenantId = required(record, CANONICAL_TENANT_ID, "catalog tenant id");
      UUID sourceIntakeOperationId =
          required(record, SOURCE_INTAKE_OPERATION_ID, "catalog source intake operation");
      String realmSlug = required(record, REALM_SLUG, "catalog realm slug");
      String realmDisplayName = required(record, REALM_DISPLAY_NAME, "catalog realm display name");
      boolean visible = Boolean.TRUE.equals(record.get(VISIBLE));
      boolean publicProduction = Boolean.TRUE.equals(record.get(PUBLIC_PRODUCTION));
      String stateScope = required(record, STATE_SCOPE, "catalog state scope");
      String characterCreationPolicy =
          required(record, CHARACTER_CREATION_POLICY, "catalog character-creation policy");
      String expectedRequestDigest =
          requestDigest(
              requestId,
              targetNamespace,
              canonicalTenantId,
              sourceIntakeOperationId,
              realmSlug,
              realmDisplayName,
              visible,
              publicProduction,
              stateScope,
              characterCreationPolicy,
              sourceReceipt);
      String persistedRequestDigest = required(record, REQUEST_DIGEST, "catalog request digest");
      UUID realmId = required(record, REALM_ID, "catalog realm id");
      UUID namespaceId =
          required(record, PLAYABLE_STATE_NAMESPACE_ID, "catalog playable-state namespace");
      long revision = required(record, CATALOG_REVISION, "catalog revision");
      String expectedReceiptDigest =
          receiptDigest(
              targetNamespace,
              canonicalTenantId,
              requestId,
              sourceReceipt,
              realmId,
              namespaceId,
              revision,
              persistedRequestDigest);
      if (!expectedRequestDigest.equals(persistedRequestDigest)
          || !expectedReceiptDigest.equals(
              required(record, RECEIPT_DIGEST, "catalog receipt digest"))) {
        throw new InvalidCatalogEvidenceException(
            "Persisted canonical realm catalog request or receipt digest does not match its contents");
      }

      verifyPersistedSourceReceipt(sourceReceipt);
      verifyNamespaceSelection(
          targetNamespace, canonicalTenantId, stateScope, realmId, namespaceId);
      return new CanonicalRealmCatalogSnapshot(
          targetNamespace,
          canonicalTenantId,
          required(record, TENANT_SLUG, "catalog tenant slug"),
          required(record, WORLD_SLUG, "catalog world slug"),
          realmId,
          realmSlug,
          realmDisplayName,
          visible,
          publicProduction,
          stateScope,
          namespaceId,
          characterCreationPolicy,
          revision,
          requestId,
          persistedRequestDigest,
          required(record, RECEIPT_DIGEST, "catalog receipt digest"),
          sourceReceipt);
    } catch (InvalidCatalogEvidenceException | CatalogConflictException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new InvalidCatalogEvidenceException(
          "Persisted canonical realm catalog evidence is invalid", exception);
    }
  }

  private void verifyNamespaceSelection(
      String targetNamespace,
      UUID canonicalTenantId,
      String stateScope,
      UUID realmId,
      UUID namespaceId) {
    if ("SHARED".equals(stateScope)) {
      Record shared =
          dsl.fetchOne(
              "SELECT * FROM gameplay_tenant_shared_playable_state_namespace "
                  + "WHERE canonical_tenant_id = ?",
              canonicalTenantId);
      if (shared == null
          || shared.get(LEGACY_TENANT_ID) != null
          || !namespaceId.equals(required(shared, SHARED_NAMESPACE_ID, "shared namespace id"))) {
        throw new InvalidCatalogEvidenceException(
            "SHARED realm catalog does not resolve to its canonical tenant namespace row");
      }
      validateSharedNamespaceSource(shared);
    } else {
      if (dsl.fetchOne(
              "SELECT playable_state_namespace_id FROM gameplay_tenant_shared_playable_state_namespace "
                  + "WHERE playable_state_namespace_id = ?",
              namespaceId)
          != null) {
        throw new InvalidCatalogEvidenceException(
            "ISOLATED realm namespace conflicts with the canonical tenant SHARED namespace");
      }
      List<? extends Record> sameNamespace =
          dsl.select(REALM_ID)
              .from(CATALOG)
              .where(PLAYABLE_STATE_NAMESPACE_ID.eq(namespaceId).and(STATE_SCOPE.eq("ISOLATED")))
              .limit(2)
              .fetch();
      if (sameNamespace.size() > 1
          || (sameNamespace.size() == 1
              && !realmId.equals(sameNamespace.getFirst().get(REALM_ID)))) {
        throw new InvalidCatalogEvidenceException(
            "ISOLATED playable-state namespace is already owned by another realm");
      }
    }
    requireNoRetainedTenantAssociation(targetNamespace, canonicalTenantId);
  }

  private void verifyPersistedSourceReceipt(IntakeReceipt expected) {
    AuthoredWorldSourceEvidence source = expected.source();
    Record binding =
        dsl.fetchOne(
            "SELECT * FROM game_session_authored_world_tenant_source_binding "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
            source.targetNamespace(),
            source.canonicalTenantId());
    if (binding == null) {
      throw new InvalidCatalogEvidenceException("Persisted tenant source binding is missing");
    }
    requireTenantBindingMatches(binding, source);
    Record intake =
        dsl.fetchOne(
            "SELECT * FROM game_session_authored_world_source_intake "
                + "WHERE target_namespace = ? AND operation_id = ?",
            source.targetNamespace(),
            expected.operationId());
    if (intake == null || !expected.equals(toIntakeReceipt(intake))) {
      throw new InvalidCatalogEvidenceException(
          "Canonical realm catalog does not match exact immutable source-intake readback");
    }
    requireFreshSource(source);
  }

  private IntakeReceipt toCatalogSourceReceipt(Record record) {
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            required(record, SOURCE_SCHEMA_VERSION, "source schema version"),
            required(record, TARGET_NAMESPACE, "source target namespace"),
            required(record, SOURCE_REGISTRATION_REQUEST_ID, "source registration request"),
            required(record, SOURCE_OPERATION_ID, "source operation"),
            required(record, SOURCE_REQUEST_DIGEST, "source request digest"),
            required(record, CANONICAL_TENANT_ID, "source canonical tenant"),
            required(record, TENANT_SLUG, "source tenant slug"),
            required(record, WORLD_SLUG, "source world slug"),
            required(record, SOURCE_WORLD_DISPLAY_NAME, "source world display name"),
            required(record, SOURCE_GAME_ROW_ID, "source game row"),
            required(record, SOURCE_GAME_TENANT_KEY, "source game tenant key"),
            required(record, SOURCE_PROVENANCE_KIND, "source provenance"),
            required(record, SOURCE_EVIDENCE_DIGEST, "source evidence digest"));
    if (!Integer.valueOf(SCHEMA_VERSION)
        .equals(required(record, SOURCE_INTAKE_SCHEMA_VERSION, "source intake schema version"))) {
      throw new InvalidCatalogEvidenceException(
          "Unsupported persisted source-intake schema version");
    }
    return new IntakeReceipt(
        required(record, SOURCE_INTAKE_OPERATION_ID, "source intake operation"),
        required(record, SOURCE_INTAKE_REQUEST_ID, "source intake request"),
        required(record, SOURCE_INTAKE_REQUEST_DIGEST, "source intake request digest"),
        source,
        required(record, SOURCE_INTAKE_RECEIPT_DIGEST, "source intake receipt digest"));
  }

  private IntakeReceipt toIntakeReceipt(Record record) {
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            required(
                record,
                DSL.field(DSL.name("source_schema_version"), Integer.class),
                "source schema"),
            required(record, TARGET_NAMESPACE, "source target namespace"),
            required(record, SOURCE_REGISTRATION_REQUEST_ID, "source registration request"),
            required(record, SOURCE_OPERATION_ID, "source operation"),
            required(record, SOURCE_REQUEST_DIGEST, "source request digest"),
            required(record, CANONICAL_TENANT_ID, "source canonical tenant"),
            required(record, TENANT_SLUG, "source tenant slug"),
            required(record, WORLD_SLUG, "source world slug"),
            required(
                record,
                DSL.field(DSL.name("world_display_name"), String.class),
                "source world display name"),
            required(record, SOURCE_GAME_ROW_ID, "source game row"),
            required(record, SOURCE_GAME_TENANT_KEY, "source game tenant key"),
            required(
                record,
                DSL.field(DSL.name("source_provenance_kind"), String.class),
                "source provenance"),
            required(record, SOURCE_EVIDENCE_DIGEST, "source evidence digest"));
    Integer intakeSchema =
        required(record, DSL.field(DSL.name("schema_version"), Integer.class), "intake schema");
    if (!Integer.valueOf(SCHEMA_VERSION).equals(intakeSchema)) {
      throw new InvalidCatalogEvidenceException(
          "Unsupported persisted source-intake schema version");
    }
    return new IntakeReceipt(
        required(record, DSL.field(DSL.name("operation_id"), UUID.class), "intake operation"),
        required(record, DSL.field(DSL.name("intake_request_id"), UUID.class), "intake request"),
        required(
            record, DSL.field(DSL.name("request_digest"), String.class), "intake request digest"),
        source,
        required(
            record, DSL.field(DSL.name("receipt_digest"), String.class), "intake receipt digest"));
  }

  private void requireTenantBindingMatches(Record binding, AuthoredWorldSourceEvidence source) {
    if (!source
            .targetNamespace()
            .equals(required(binding, TARGET_NAMESPACE, "tenant binding namespace"))
        || !source
            .canonicalTenantId()
            .equals(required(binding, CANONICAL_TENANT_ID, "tenant binding canonical id"))
        || !source.tenantSlug().equals(required(binding, TENANT_SLUG, "tenant binding slug"))
        || !Long.valueOf(source.sourceGameRowId())
            .equals(required(binding, SOURCE_GAME_ROW_ID, "tenant binding source row"))
        || !source
            .sourceGameTenantKey()
            .equals(required(binding, SOURCE_GAME_TENANT_KEY, "tenant binding source key"))
        || !source
            .provenanceKind()
            .equals(required(binding, BINDING_PROVENANCE_KIND, "tenant binding provenance"))) {
      throw new InvalidCatalogEvidenceException(
          "Authored-world receipt conflicts with the immutable canonical tenant source binding");
    }
  }

  private void requireFreshSource(AuthoredWorldSourceEvidence source) {
    if (!"NEW_GAME_ROW".equals(source.provenanceKind())) {
      throw new InvalidCatalogEvidenceException(
          "Initial canonical catalog creation requires fresh Game Design tenant source provenance");
    }
  }

  private void requireNoRetainedTenantAssociation(String targetNamespace, UUID canonicalTenantId) {
    if (dsl.fetchOne(
            "SELECT operation_id FROM game_session_retained_tenant_association "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
            targetNamespace,
            canonicalTenantId)
        != null) {
      throw new CatalogConflictException(
          "Canonical tenant already has retained numeric Game Session identity evidence");
    }
  }

  private Record findByRequest(String targetNamespace, UUID requestId) {
    return dsl.selectFrom(CATALOG)
        .where(TARGET_NAMESPACE.eq(targetNamespace).and(CREATION_REQUEST_ID.eq(requestId)))
        .fetchOne();
  }

  private Record findPublicForTenant(String targetNamespace, UUID tenantId) {
    return dsl.selectFrom(CATALOG)
        .where(
            TARGET_NAMESPACE
                .eq(targetNamespace)
                .and(CANONICAL_TENANT_ID.eq(tenantId))
                .and(PUBLIC_PRODUCTION.isTrue()))
        .fetchOne();
  }

  private Record findByRealmSlug(String targetNamespace, UUID tenantId, String realmSlug) {
    return dsl.selectFrom(CATALOG)
        .where(
            TARGET_NAMESPACE
                .eq(targetNamespace)
                .and(CANONICAL_TENANT_ID.eq(tenantId))
                .and(REALM_SLUG.eq(realmSlug)))
        .fetchOne();
  }

  static String requestDigest(
      CreateCanonicalRealmCatalogRequest request, IntakeReceipt sourceReceipt) {
    return requestDigest(
        request.requestId(),
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.sourceIntakeOperationId(),
        request.realmSlug(),
        request.realmDisplayName(),
        request.visible(),
        request.publicProduction(),
        request.stateScope(),
        request.characterCreationPolicy(),
        sourceReceipt);
  }

  private static String requestDigest(
      UUID requestId,
      String targetNamespace,
      UUID canonicalTenantId,
      UUID sourceIntakeOperationId,
      String realmSlug,
      String realmDisplayName,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      String characterCreationPolicy,
      IntakeReceipt sourceReceipt) {
    AuthoredWorldSourceEvidence source = sourceReceipt.source();
    return digest(
        REQUEST_DOMAIN,
        Integer.toString(SCHEMA_VERSION),
        requestId.toString(),
        targetNamespace,
        canonicalTenantId.toString(),
        sourceIntakeOperationId.toString(),
        sourceReceipt.intakeRequestId().toString(),
        sourceReceipt.requestDigest(),
        sourceReceipt.receiptDigest(),
        Integer.toString(source.schemaVersion()),
        source.registrationRequestId().toString(),
        source.operationId().toString(),
        source.requestDigest(),
        source.canonicalTenantId().toString(),
        source.tenantSlug(),
        source.worldSlug(),
        source.worldDisplayName(),
        Long.toString(source.sourceGameRowId()),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.evidenceDigest(),
        realmSlug,
        realmDisplayName,
        Boolean.toString(visible),
        Boolean.toString(publicProduction),
        stateScope,
        characterCreationPolicy,
        "ABSENT",
        "ABSENT");
  }

  private static String receiptDigest(
      CreateCanonicalRealmCatalogRequest request,
      IntakeReceipt sourceReceipt,
      UUID realmId,
      UUID namespaceId,
      long revision,
      String requestDigest) {
    return receiptDigest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.requestId(),
        sourceReceipt,
        realmId,
        namespaceId,
        revision,
        requestDigest);
  }

  private static String receiptDigest(
      String targetNamespace,
      UUID canonicalTenantId,
      UUID requestId,
      IntakeReceipt sourceReceipt,
      UUID realmId,
      UUID namespaceId,
      long revision,
      String requestDigest) {
    return digest(
        RECEIPT_DOMAIN,
        Integer.toString(SCHEMA_VERSION),
        targetNamespace,
        canonicalTenantId.toString(),
        requestId.toString(),
        requestDigest,
        realmId.toString(),
        namespaceId.toString(),
        Long.toString(revision),
        sourceReceipt.operationId().toString(),
        sourceReceipt.receiptDigest());
  }

  private static String digest(String... values) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      for (String value : values) {
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

  private void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical realm catalog creation requires an active owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical realm catalog creation requires a read-write transaction");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                "Canonical realm catalog DSL connection must join the owner transaction");
          }
          if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Canonical realm catalog creation requires READ COMMITTED isolation");
          }
          return null;
        });
    Record isolationRow = dsl.fetchOne("SHOW transaction_isolation");
    if (isolationRow == null
        || !"read committed".equalsIgnoreCase(isolationRow.get(0, String.class).trim())) {
      throw new IllegalStateException(
          "Canonical realm catalog creation requires READ COMMITTED isolation");
    }
    Record readOnlyRow = dsl.fetchOne("SHOW transaction_read_only");
    if (readOnlyRow == null || !"off".equalsIgnoreCase(readOnlyRow.get(0, String.class).trim())) {
      throw new IllegalStateException(
          "Canonical realm catalog creation requires a read-write transaction");
    }
  }

  private void requireCommittedRead() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical realm catalog read requires a committed-outcome owner read");
    }
  }

  private static void requireReadSelector(String targetNamespace, UUID identity) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    requireNonNil(identity, "identity");
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static <T> T required(Record record, Field<T> field, String label) {
    T value = record.get(field);
    if (value == null) {
      throw new InvalidCatalogEvidenceException("Persisted " + label + " is missing");
    }
    return value;
  }

  /** Opaque source preflight result; callers cannot manufacture a committed owner receipt. */
  public static final class PreparedInitialPublicProduction {
    private final CreateCanonicalRealmCatalogRequest request;
    private final IntakeReceipt committedSource;

    private PreparedInitialPublicProduction(
        CreateCanonicalRealmCatalogRequest request, IntakeReceipt committedSource) {
      this.request = Objects.requireNonNull(request, "request");
      this.committedSource = Objects.requireNonNull(committedSource, "committedSource");
    }

    private CreateCanonicalRealmCatalogRequest request() {
      return request;
    }

    private IntakeReceipt committedSource() {
      return committedSource;
    }
  }

  public static final class CatalogConflictException extends IllegalStateException {
    public CatalogConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidCatalogEvidenceException extends IllegalStateException {
    public InvalidCatalogEvidenceException(String message) {
      super(message);
    }

    public InvalidCatalogEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
