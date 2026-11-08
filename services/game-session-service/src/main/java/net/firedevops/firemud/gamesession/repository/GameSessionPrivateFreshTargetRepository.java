package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.dto.CanonicalPrivateFreshTargetSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Preparation;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.ReadState;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Snapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Allocates an internal private FRESH realm catalog identity from one exact retained preparation.
 *
 * <p>The row remains non-public and PREPARING. This repository creates no World owner data,
 * runtime, CLOSED pointer, admission, or player visibility.
 */
public class GameSessionPrivateFreshTargetRepository {
  private static final String CATALOG = "game_session_canonical_realm_catalog";
  private static final String REQUEST_SCHEMA = "game-session-private-fresh-catalog-request/v1";
  private static final String RECEIPT_SCHEMA = "game-session-private-fresh-catalog-receipt/v1";
  private static final long INITIAL_CATALOG_REVISION = 1L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final GameSessionInitialPlaytestLifecycleRepository preparations;
  private final GameSessionCanonicalRealmCatalogRepository catalogs;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Fail-fast validation is local; no overridable callback or partially initialized object escapes.")
  public GameSessionPrivateFreshTargetRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.preparations = new GameSessionInitialPlaytestLifecycleRepository(dsl);
    this.catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
  }

  /**
   * Allocates a target realm ID for the exact prepared FRESH lifecycle in the caller's SERIALIZABLE
   * owner transaction. Namespace, lifecycle, generation, selectors, and policy come only from the
   * locked retained preparation.
   *
   * <p>The caller must handle PostgreSQL SQLSTATE 40001 by rolling back and retrying the complete
   * owner transaction with this same immutable request. An advisory-lock wait does not refresh a
   * SERIALIZABLE snapshot; retrying only this statement or allocating another request is invalid.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Allocation allocate(Request request) {
    Objects.requireNonNull(request, "request");
    requireWritableSerializableOwnerTransaction();
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "private-fresh-preparation/" + request.preparationRequestId());
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "private-fresh-request/" + request.requestId());

    Record priorRequest = findByCreationRequest(request.requestId());
    if (priorRequest != null
        && (!request
                .preparationRequestId()
                .equals(priorRequest.get("private_preparation_request_id", UUID.class))
            || !request
                .playtestLifecycleId()
                .equals(priorRequest.get("private_playtest_lifecycle_id", UUID.class)))) {
      throw new IdempotencyConflictException(
          "Private FRESH target request identity was reused for another preparation or lifecycle");
    }

    Snapshot snapshot =
        preparations
            .readExactForPrivateCatalogTarget(
                request.preparationRequestId(), request.playtestLifecycleId())
            .orElseThrow(
                () ->
                    new PreparationUnavailableException(
                        "Exact retained FRESH preparation and lifecycle are unavailable"));
    Preparation preparation = snapshot.preparation();
    CanonicalRealmCatalogSnapshot sourceCatalog = readExactSourceCatalog(preparation);
    IntakeReceipt intake = sourceCatalog.sourceIntakeReceipt();
    byte[] completePolicyEvidence = readCompleteHumanPolicyEvidence(preparation);
    byte[] requestPreimage = requestPreimage(request, preparation.requestDigest());
    String requestDigest = digest(requestPreimage);

    if (priorRequest != null) {
      return new Allocation(
          requireExactRetry(priorRequest, request, preparation, sourceCatalog, requestPreimage),
          true);
    }
    if (snapshot.state() != ReadState.INCOMPLETE) {
      throw new PreparationUnavailableException(
          "Exact retained FRESH preparation has expired and cannot allocate a catalog identity");
    }
    Record priorPreparation = findByPreparation(request.preparationRequestId());
    if (priorPreparation != null) {
      throw new IdempotencyConflictException(
          "Exact FRESH preparation already has a different private catalog request");
    }

    RealmEntryPolicy policy = preparation.source().targetPolicy().source().policy();
    UUID realmId;
    do {
      realmId = UUID.randomUUID();
    } while (NIL_UUID.equals(realmId)
        || realmId.equals(preparation.request().sourceCatalogRealmId())
        || realmId.equals(preparation.source().targetPolicy().policyId())
        || realmId.equals(preparation.playtestLifecycleId())
        || realmId.equals(preparation.playableStateNamespaceId()));
    String receiptDigest =
        receiptDigest(
            request.requestId(), preparation, sourceCatalog, realmId, requestDigest, policy);
    Object[] values =
        insertValues(
            request,
            preparation,
            sourceCatalog,
            intake,
            completePolicyEvidence,
            realmId,
            requestPreimage,
            requestDigest,
            receiptDigest,
            policy);
    String columns =
        "target_namespace, canonical_tenant_id, tenant_slug, world_slug, realm_id, realm_slug, "
            + "realm_display_name, visible, public_production, state_scope, playable_state_namespace_id, "
            + "character_creation_policy, catalog_revision, creation_request_id, request_digest, "
            + "receipt_digest, source_intake_operation_id, source_intake_schema_version, "
            + "source_intake_request_id, source_intake_request_digest, source_intake_receipt_digest, "
            + "source_schema_version, source_registration_request_id, source_operation_id, "
            + "source_request_digest, source_world_display_name, source_game_row_id, "
            + "source_game_tenant_key, source_provenance_kind, source_evidence_digest, "
            + "complete_policy_set_evidence, private_target_request_preimage, "
            + "private_preparation_request_id, private_playtest_lifecycle_id, "
            + "private_preparation_request_digest, private_state_generation, "
            + "private_source_catalog_realm_id, private_source_catalog_request_id, "
            + "private_source_catalog_revision, private_source_catalog_receipt_digest, "
            + "private_target_policy_id, private_target_policy_digest, "
            + "private_target_policy_source_commit_id, private_target_policy_source_revision_id, "
            + "private_target_policy_logical_revision_id, private_canonical_version_id, "
            + "private_published_release_bundle_ref, private_published_release_bundle_digest, "
            + "private_source_policy_set_digest, private_source_policy_carrier_digest";
    String placeholders = String.join(", ", java.util.Collections.nCopies(values.length, "?"));
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO "
                + CATALOG
                + " ("
                + columns
                + ") VALUES ("
                + placeholders
                + ") ON CONFLICT DO NOTHING RETURNING *",
            values);
    if (inserted != null) {
      return new Allocation(
          requireExactRetry(inserted, request, preparation, sourceCatalog, requestPreimage), false);
    }
    Record racedRequest = findByCreationRequest(request.requestId());
    if (racedRequest != null) {
      return new Allocation(
          requireExactRetry(racedRequest, request, preparation, sourceCatalog, requestPreimage),
          true);
    }
    Record racedPreparation = findByPreparation(request.preparationRequestId());
    if (racedPreparation != null) {
      throw new IdempotencyConflictException(
          "Exact FRESH preparation already has a different private catalog request");
    }
    throw new CatalogConflictException(
        "Private FRESH target conflicts with a retained realm selector or namespace identity");
  }

  /** Reads a private preparation-bound projection after its catalog row has committed. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<CanonicalPrivateFreshTargetSnapshot> readByPreparation(
      UUID preparationRequestId, UUID playtestLifecycleId) {
    requireCommittedRead();
    requireNonNil(preparationRequestId, "preparationRequestId");
    requireNonNil(playtestLifecycleId, "playtestLifecycleId");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + CATALOG
                + " WHERE private_preparation_request_id = ? AND private_playtest_lifecycle_id = ?",
            preparationRequestId,
            playtestLifecycleId);
    return row == null ? Optional.empty() : Optional.of(toSnapshot(row));
  }

  /**
   * Coherent no-write World source read inside the receiving owner's short transaction. Shared
   * locks and retained source/receipt qualification supply as-of facts, not later protection.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CanonicalPrivateFreshTargetSnapshot> readForWorldPreparation(
      UUID preparationRequestId, UUID playtestLifecycleId) {
    return new GameSessionPrivateFreshCatalogRevisionRepository(dsl)
        .readForWorldPreparation(preparationRequestId, playtestLifecycleId);
  }

  Optional<CanonicalPrivateFreshTargetSnapshot> readInitialForWorldPreparation(
      UUID preparationRequestId, UUID playtestLifecycleId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Private World source requires a writable owner read transaction");
    }
    requireNonNil(preparationRequestId, "preparationRequestId");
    requireNonNil(playtestLifecycleId, "playtestLifecycleId");
    var prepared =
        preparations
            .readExactForPrivateCatalogTarget(preparationRequestId, playtestLifecycleId)
            .orElseThrow(
                () -> new PreparationUnavailableException("Exact private preparation is absent"));
    if (prepared.state() != ReadState.INCOMPLETE)
      throw new PreparationUnavailableException("Exact private preparation has expired");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + CATALOG
                + " WHERE private_preparation_request_id = ? AND private_playtest_lifecycle_id = ? FOR SHARE",
            preparationRequestId,
            playtestLifecycleId);
    if (row == null) return Optional.empty();
    var preparation = prepared.preparation();
    var source = preparation.source();
    var sourceCatalog = readExactSourceCatalog(preparation);
    byte[] policy = readCompleteHumanPolicyEvidence(preparation);
    var request =
        new Request(
            required(row, "creation_request_id", UUID.class),
            preparationRequestId,
            playtestLifecycleId);
    var result =
        requireExactRetry(
            row,
            request,
            preparation,
            sourceCatalog,
            requestPreimage(request, preparation.requestDigest()));
    if (!Arrays.equals(policy, required(row, "complete_policy_set_evidence", byte[].class))
        || !source.intakeOperationId().equals(row.get("source_intake_operation_id", UUID.class))
        || !source
            .intakeReceiptDigest()
            .equals(row.get("source_intake_receipt_digest", String.class))
        || !source.sourceOperationId().equals(row.get("source_operation_id", UUID.class))
        || !source.sourceEvidenceDigest().equals(row.get("source_evidence_digest", String.class))
        || !result
            .receiptDigest()
            .equals(
                receiptDigest(
                    request.requestId(),
                    preparation,
                    sourceCatalog,
                    result.realmId(),
                    result.requestDigest(),
                    source.targetPolicy().source().policy()))) {
      throw new InvalidPrivateTargetException(
          "Private World target differs from complete retained source receipt");
    }
    return Optional.of(result);
  }

  private Record findByCreationRequest(UUID requestId) {
    List<Record> rows =
        dsl.fetch(
            "SELECT * FROM "
                + CATALOG
                + " WHERE creation_request_id = ? AND private_preparation_request_id IS NOT NULL",
            requestId);
    if (rows.size() > 1) {
      throw new CatalogConflictException("Private FRESH catalog request identity is ambiguous");
    }
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Record findByPreparation(UUID preparationRequestId) {
    List<Record> rows =
        dsl.fetch(
            "SELECT * FROM " + CATALOG + " WHERE private_preparation_request_id = ?",
            preparationRequestId);
    if (rows.size() > 1) {
      throw new CatalogConflictException("FRESH preparation has multiple private catalog targets");
    }
    return rows.isEmpty() ? null : rows.getFirst();
  }

  CanonicalRealmCatalogSnapshot readExactSourceCatalog(Preparation preparation) {
    var request = preparation.request();
    CanonicalRealmCatalogSnapshot source =
        catalogs.readRetainedInitialPublicProduction(
            request.targetNamespace(),
            request.tenantId(),
            request.sourceCatalogRealmId(),
            request.sourceCatalogRequestId(),
            request.sourceCatalogRevision());
    var sourceReference = preparation.source();
    if (!source.visible()
        || !source.publicProduction()
        || !source.tenantId().equals(request.tenantId())
        || !source.worldSlug().equals(request.worldSlug())
        || !source.realmId().equals(sourceReference.catalogRealmId())
        || !source.creationRequestId().equals(sourceReference.catalogRequestId())
        || source.catalogRevision() != sourceReference.catalogRevision()
        || !source.receiptDigest().equals(sourceReference.catalogReceiptDigest())
        || !source.sourceIntakeReceipt().operationId().equals(sourceReference.intakeOperationId())
        || !source
            .sourceIntakeReceipt()
            .receiptDigest()
            .equals(sourceReference.intakeReceiptDigest())) {
      throw new PreparationUnavailableException(
          "Private target source catalog no longer matches its exact FRESH preparation");
    }
    return source;
  }

  byte[] readCompleteHumanPolicyEvidence(Preparation preparation) {
    var request = preparation.request();
    Record row =
        dsl.fetchOne(
            "SELECT complete_policy_set_evidence, fixture_policy_request_bytes, "
                + "fixture_policy_seal_operation_id, fixture_policy_seal_request_digest, "
                + "fixture_policy_seal_evidence_digest FROM "
                + CATALOG
                + " WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? FOR SHARE",
            request.targetNamespace(),
            request.tenantId(),
            request.sourceCatalogRealmId());
    byte[] evidence = row == null ? null : row.get("complete_policy_set_evidence", byte[].class);
    if (evidence == null
        || evidence.length == 0
        || row.get("fixture_policy_request_bytes", byte[].class) != null
        || row.get("fixture_policy_seal_operation_id", UUID.class) != null
        || row.get("fixture_policy_seal_request_digest", String.class) != null
        || row.get("fixture_policy_seal_evidence_digest", String.class) != null
        || !digest(evidence).equals(preparation.source().policyCarrierDigest())) {
      throw new PreparationUnavailableException(
          "Private FRESH target requires its exact retained Human published policy carrier");
    }
    return evidence;
  }

  private Object[] insertValues(
      Request request,
      Preparation preparation,
      CanonicalRealmCatalogSnapshot sourceCatalog,
      IntakeReceipt intake,
      byte[] completePolicyEvidence,
      UUID realmId,
      byte[] requestPreimage,
      String requestDigest,
      String receiptDigest,
      RealmEntryPolicy policy) {
    var source = intake.source();
    var preparationRequest = preparation.request();
    var targetPolicy = preparation.source().targetPolicy();
    return new Object[] {
      preparationRequest.targetNamespace(),
      preparationRequest.tenantId(),
      source.tenantSlug(),
      preparationRequest.worldSlug(),
      realmId,
      preparationRequest.realmSlug(),
      policy.realmDisplayName(),
      false,
      false,
      RealmEntryPolicy.StateScope.ISOLATED.name(),
      preparation.playableStateNamespaceId(),
      policy.entryPolicy().name(),
      INITIAL_CATALOG_REVISION,
      request.requestId(),
      requestDigest,
      receiptDigest,
      intake.operationId(),
      1,
      intake.intakeRequestId(),
      intake.requestDigest(),
      intake.receiptDigest(),
      source.schemaVersion(),
      source.registrationRequestId(),
      source.operationId(),
      source.requestDigest(),
      source.worldDisplayName(),
      source.sourceGameRowId(),
      source.sourceGameTenantKey(),
      source.provenanceKind(),
      source.evidenceDigest(),
      completePolicyEvidence,
      requestPreimage,
      request.preparationRequestId(),
      request.playtestLifecycleId(),
      preparation.requestDigest(),
      preparation.playtestStateGeneration(),
      sourceCatalog.realmId(),
      sourceCatalog.creationRequestId(),
      sourceCatalog.catalogRevision(),
      sourceCatalog.receiptDigest(),
      targetPolicy.policyId(),
      targetPolicy.policyDigest(),
      targetPolicy.source().commitId(),
      targetPolicy.source().revisionId(),
      targetPolicy.source().logicalRevisionId(),
      preparation.source().canonicalVersionId(),
      preparation.source().publishedReleaseBundleRef(),
      preparation.source().publishedReleaseBundleDigest(),
      preparation.source().policySetDigest(),
      preparation.source().policyCarrierDigest()
    };
  }

  private CanonicalPrivateFreshTargetSnapshot requireExactRetry(
      Record row,
      Request request,
      Preparation preparation,
      CanonicalRealmCatalogSnapshot sourceCatalog,
      byte[] expectedRequestPreimage) {
    CanonicalPrivateFreshTargetSnapshot snapshot = toSnapshot(row);
    var source = preparation.source();
    var targetPolicy = source.targetPolicy();
    RealmEntryPolicy policy = targetPolicy.source().policy();
    if (!request.requestId().equals(snapshot.creationRequestId())
        || !request.preparationRequestId().equals(snapshot.preparationRequestId())
        || !request.playtestLifecycleId().equals(snapshot.playtestLifecycleId())
        || !Arrays.equals(
            expectedRequestPreimage, row.get("private_target_request_preimage", byte[].class))
        || !preparation
            .requestDigest()
            .equals(row.get("private_preparation_request_digest", String.class))
        || !preparation.request().targetNamespace().equals(snapshot.targetNamespace())
        || !preparation.request().tenantId().equals(snapshot.tenantId())
        || !preparation.request().worldSlug().equals(snapshot.worldSlug())
        || !preparation.request().realmSlug().equals(snapshot.realmSlug())
        || !preparation.playableStateNamespaceId().equals(snapshot.playableStateNamespaceId())
        || preparation.playtestStateGeneration() != snapshot.playtestStateGeneration()
        || !policy.realmDisplayName().equals(snapshot.realmDisplayName())
        || !policy.entryPolicy().name().equals(snapshot.characterCreationPolicy())
        || !targetPolicy.policyId().equals(snapshot.targetPolicyId())
        || !targetPolicy.policyDigest().equals(snapshot.targetPolicyDigest())
        || !source.canonicalVersionId().equals(snapshot.canonicalVersionId())
        || !source.publishedReleaseBundleRef().equals(snapshot.publishedReleaseBundleRef())
        || !source.publishedReleaseBundleDigest().equals(snapshot.publishedReleaseBundleDigest())
        || !sourceCatalog.realmId().equals(row.get("private_source_catalog_realm_id", UUID.class))
        || !sourceCatalog
            .creationRequestId()
            .equals(row.get("private_source_catalog_request_id", UUID.class))
        || sourceCatalog.catalogRevision() != row.get("private_source_catalog_revision", Long.class)
        || !sourceCatalog
            .receiptDigest()
            .equals(row.get("private_source_catalog_receipt_digest", String.class))) {
      throw new IdempotencyConflictException(
          "Private FRESH catalog request conflicts with its exact retained preparation or outcome");
    }
    return snapshot;
  }

  private CanonicalPrivateFreshTargetSnapshot toSnapshot(Record row) {
    try {
      UUID requestId = required(row, "creation_request_id", UUID.class);
      UUID preparationRequestId = required(row, "private_preparation_request_id", UUID.class);
      UUID lifecycleId = required(row, "private_playtest_lifecycle_id", UUID.class);
      String preparationDigest = required(row, "private_preparation_request_digest", String.class);
      byte[] requestBytes = required(row, "private_target_request_preimage", byte[].class);
      String requestDigest = required(row, "request_digest", String.class);
      if (!Arrays.equals(
              requestPreimage(requestId, preparationRequestId, lifecycleId, preparationDigest),
              requestBytes)
          || !digest(requestBytes).equals(requestDigest)) {
        throw new InvalidPrivateTargetException(
            "Private FRESH catalog request provenance does not match its immutable row");
      }
      String receiptDigest = required(row, "receipt_digest", String.class);
      if (!receiptDigest(row).equals(receiptDigest)) {
        throw new InvalidPrivateTargetException(
            "Private FRESH catalog outcome receipt does not match its immutable row");
      }
      return new CanonicalPrivateFreshTargetSnapshot(
          required(row, "target_namespace", String.class),
          required(row, "canonical_tenant_id", UUID.class),
          required(row, "world_slug", String.class),
          required(row, "realm_slug", String.class),
          required(row, "realm_id", UUID.class),
          required(row, "realm_display_name", String.class),
          Boolean.TRUE.equals(row.get("visible", Boolean.class)),
          Boolean.TRUE.equals(row.get("public_production", Boolean.class)),
          required(row, "state_scope", String.class),
          required(row, "playable_state_namespace_id", UUID.class),
          required(row, "character_creation_policy", String.class),
          required(row, "catalog_revision", Long.class),
          requestId,
          requestDigest,
          receiptDigest,
          preparationRequestId,
          lifecycleId,
          required(row, "private_state_generation", Long.class),
          "PREPARING",
          required(row, "private_source_catalog_realm_id", UUID.class),
          required(row, "private_target_policy_id", UUID.class),
          required(row, "private_target_policy_digest", String.class),
          required(row, "private_canonical_version_id", UUID.class),
          required(row, "private_published_release_bundle_ref", String.class),
          required(row, "private_published_release_bundle_digest", String.class));
    } catch (InvalidPrivateTargetException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new InvalidPrivateTargetException(
          "Persisted private FRESH catalog target evidence is invalid", exception);
    }
  }

  private String receiptDigest(Record row) {
    return digest(
        receiptPreimage(
            required(row, "creation_request_id", UUID.class),
            required(row, "request_digest", String.class),
            required(row, "target_namespace", String.class),
            required(row, "canonical_tenant_id", UUID.class),
            required(row, "tenant_slug", String.class),
            required(row, "world_slug", String.class),
            required(row, "realm_id", UUID.class),
            required(row, "realm_slug", String.class),
            required(row, "realm_display_name", String.class),
            Boolean.TRUE.equals(row.get("visible", Boolean.class)),
            Boolean.TRUE.equals(row.get("public_production", Boolean.class)),
            required(row, "state_scope", String.class),
            required(row, "playable_state_namespace_id", UUID.class),
            required(row, "character_creation_policy", String.class),
            required(row, "catalog_revision", Long.class),
            required(row, "private_preparation_request_id", UUID.class),
            required(row, "private_playtest_lifecycle_id", UUID.class),
            required(row, "private_preparation_request_digest", String.class),
            required(row, "private_state_generation", Long.class),
            required(row, "private_source_catalog_realm_id", UUID.class),
            required(row, "private_source_catalog_request_id", UUID.class),
            required(row, "private_source_catalog_revision", Long.class),
            required(row, "private_source_catalog_receipt_digest", String.class),
            required(row, "private_target_policy_id", UUID.class),
            required(row, "private_target_policy_digest", String.class),
            required(row, "private_target_policy_source_commit_id", UUID.class),
            required(row, "private_target_policy_source_revision_id", UUID.class),
            required(row, "private_target_policy_logical_revision_id", String.class),
            required(row, "private_canonical_version_id", UUID.class),
            required(row, "private_published_release_bundle_ref", String.class),
            required(row, "private_published_release_bundle_digest", String.class),
            required(row, "private_source_policy_set_digest", String.class),
            required(row, "private_source_policy_carrier_digest", String.class),
            required(row, "source_intake_operation_id", UUID.class),
            required(row, "source_intake_receipt_digest", String.class)));
  }

  private static String receiptDigest(
      UUID requestId,
      Preparation preparation,
      CanonicalRealmCatalogSnapshot sourceCatalog,
      UUID realmId,
      String requestDigest,
      RealmEntryPolicy policy) {
    IntakeReceipt intake = sourceCatalog.sourceIntakeReceipt();
    var source = intake.source();
    var sourceReference = preparation.source();
    var targetPolicy = sourceReference.targetPolicy();
    return digest(
        receiptPreimage(
            requestId,
            requestDigest,
            preparation.request().targetNamespace(),
            preparation.request().tenantId(),
            source.tenantSlug(),
            preparation.request().worldSlug(),
            realmId,
            preparation.request().realmSlug(),
            policy.realmDisplayName(),
            false,
            false,
            RealmEntryPolicy.StateScope.ISOLATED.name(),
            preparation.playableStateNamespaceId(),
            policy.entryPolicy().name(),
            INITIAL_CATALOG_REVISION,
            preparation.request().requestId(),
            preparation.playtestLifecycleId(),
            preparation.requestDigest(),
            preparation.playtestStateGeneration(),
            sourceCatalog.realmId(),
            sourceCatalog.creationRequestId(),
            sourceCatalog.catalogRevision(),
            sourceCatalog.receiptDigest(),
            targetPolicy.policyId(),
            targetPolicy.policyDigest(),
            targetPolicy.source().commitId(),
            targetPolicy.source().revisionId(),
            targetPolicy.source().logicalRevisionId(),
            sourceReference.canonicalVersionId(),
            sourceReference.publishedReleaseBundleRef(),
            sourceReference.publishedReleaseBundleDigest(),
            sourceReference.policySetDigest(),
            sourceReference.policyCarrierDigest(),
            intake.operationId(),
            intake.receiptDigest()));
  }

  private static byte[] receiptPreimage(
      UUID requestId,
      String requestDigest,
      String targetNamespace,
      UUID tenantId,
      String tenantSlug,
      String worldSlug,
      UUID realmId,
      String realmSlug,
      String realmDisplayName,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      UUID playableStateNamespaceId,
      String characterCreationPolicy,
      long catalogRevision,
      UUID preparationRequestId,
      UUID lifecycleId,
      String preparationRequestDigest,
      long generation,
      UUID sourceCatalogRealmId,
      UUID sourceCatalogRequestId,
      long sourceCatalogRevision,
      String sourceCatalogReceiptDigest,
      UUID targetPolicyId,
      String targetPolicyDigest,
      UUID policySourceCommitId,
      UUID policySourceRevisionId,
      String policyLogicalRevisionId,
      UUID canonicalVersionId,
      String releaseBundleRef,
      String releaseBundleDigest,
      String policySetDigest,
      String policyCarrierDigest,
      UUID sourceIntakeOperationId,
      String sourceIntakeReceiptDigest) {
    return framed(
        RECEIPT_SCHEMA,
        requestId.toString(),
        requestDigest,
        targetNamespace,
        tenantId.toString(),
        tenantSlug,
        worldSlug,
        realmId.toString(),
        realmSlug,
        realmDisplayName,
        Boolean.toString(visible),
        Boolean.toString(publicProduction),
        stateScope,
        playableStateNamespaceId.toString(),
        characterCreationPolicy,
        Long.toString(catalogRevision),
        preparationRequestId.toString(),
        lifecycleId.toString(),
        preparationRequestDigest,
        Long.toString(generation),
        sourceCatalogRealmId.toString(),
        sourceCatalogRequestId.toString(),
        Long.toString(sourceCatalogRevision),
        sourceCatalogReceiptDigest,
        targetPolicyId.toString(),
        targetPolicyDigest,
        policySourceCommitId.toString(),
        policySourceRevisionId.toString(),
        policyLogicalRevisionId,
        canonicalVersionId.toString(),
        releaseBundleRef,
        releaseBundleDigest,
        policySetDigest,
        policyCarrierDigest,
        sourceIntakeOperationId.toString(),
        sourceIntakeReceiptDigest);
  }

  private static byte[] requestPreimage(Request request, String preparationDigest) {
    return requestPreimage(
        request.requestId(),
        request.preparationRequestId(),
        request.playtestLifecycleId(),
        preparationDigest);
  }

  private static byte[] requestPreimage(
      UUID requestId, UUID preparationRequestId, UUID lifecycleId, String preparationDigest) {
    return framed(
        REQUEST_SCHEMA,
        requestId.toString(),
        preparationRequestId.toString(),
        lifecycleId.toString(),
        preparationDigest);
  }

  private static byte[] framed(String... values) {
    var out = new ByteArrayOutputStream();
    for (String value : values) DraftAuthorizationFenceBinding.frame(out, value);
    return out.toByteArray();
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  void requireWritableSerializableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Private FRESH target allocation requires a writable owner transaction");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_SERIALIZABLE) {
            throw new IllegalStateException(
                "Private FRESH target allocation requires its owner SERIALIZABLE transaction");
          }
          return null;
        });
    Record isolationRow = dsl.fetchOne("SHOW transaction_isolation");
    if (isolationRow == null
        || !"serializable".equalsIgnoreCase(isolationRow.get(0, String.class).trim())) {
      throw new IllegalStateException(
          "Private FRESH target allocation requires SERIALIZABLE isolation");
    }
    Record readOnlyRow = dsl.fetchOne("SHOW transaction_read_only");
    if (readOnlyRow == null || !"off".equalsIgnoreCase(readOnlyRow.get(0, String.class).trim())) {
      throw new IllegalStateException(
          "Private FRESH target allocation requires a writable owner transaction");
    }
  }

  private static void requireCommittedRead() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Private FRESH target projection requires committed owner readback");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null)
      throw new InvalidPrivateTargetException("Missing private target field " + field);
    return value;
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) throw new IllegalArgumentException(name + " must be non-nil");
  }

  public record Request(UUID requestId, UUID preparationRequestId, UUID playtestLifecycleId) {
    public Request {
      requireNonNil(requestId, "requestId");
      requireNonNil(preparationRequestId, "preparationRequestId");
      requireNonNil(playtestLifecycleId, "playtestLifecycleId");
    }
  }

  public record Allocation(CanonicalPrivateFreshTargetSnapshot snapshot, boolean replayed) {}

  public static final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String message) {
      super(message);
    }
  }

  public static final class CatalogConflictException extends IllegalStateException {
    public CatalogConflictException(String message) {
      super(message);
    }
  }

  public static final class PreparationUnavailableException extends IllegalStateException {
    public PreparationUnavailableException(String message) {
      super(message);
    }
  }

  public static final class InvalidPrivateTargetException extends IllegalStateException {
    public InvalidPrivateTargetException(String message) {
      super(message);
    }

    public InvalidPrivateTargetException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
