package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Game Session owner intake for one fresh public-production SHARED realm.
 *
 * <p>The caller must obtain the source and published policy set from authenticated Game Design
 * owner reads before calling this class. These typed values and their digests are validated and
 * durably retained here, but constructor bytes alone do not authenticate Game Design. This
 * repository creates no launch, runtime, activation, or admission state.
 */
public final class GameSessionCanonicalRealmCatalogRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final int INITIAL_CATALOG_REVISION = 1;
  private static final Set<String> CREATION_EVIDENCE_FIELDS =
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

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;

  public GameSessionCanonicalRealmCatalogRepository(
      DSLContext dsl, PlatformTransactionManager transactionManager, String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is missing or invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.writeTransaction = new TransactionTemplate(transactionManager);
    this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.readTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction.setReadOnly(true);
    this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Retains one exact source and sealed policy set, creates the GS-owned source-intake and
   * public-production SHARED catalog rows atomically, then independently reads the full result. The
   * two request IDs are caller-stable operation selectors; allocated GS operation and realm UUIDs
   * are never derived from Game Design identity.
   */
  public CanonicalRealmCatalog establishFreshPublicRealm(
      FreshTenantAssociation association,
      AuthoredWorldSourceEvidence source,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet,
      UUID intakeRequestId,
      UUID catalogRequestId) {
    requireNoActiveOwnerTransaction();
    PreparedInput input =
        prepareInput(association, source, publishedSet, intakeRequestId, catalogRequestId);

    CanonicalRealmCatalog committed =
        writeTransaction.execute(status -> establishInTransaction(input));
    if (committed == null) {
      throw new InvalidCanonicalRealmCatalogException(
          "Game Session canonical realm transaction returned no result");
    }

    CanonicalRealmCatalog independentReadback =
        readTransaction.execute(
            status ->
                readByCanonicalTenantIdInTransaction(input.source().canonicalTenantId())
                    .orElse(null));
    if (independentReadback == null || !matchesInput(independentReadback, input)) {
      throw new InvalidCanonicalRealmCatalogException(
          "Committed canonical realm catalog failed independent exact source and policy readback");
    }
    return independentReadback;
  }

  /** Reads only complete, mutually consistent stored evidence and never calls Game Design. */
  public Optional<CanonicalRealmCatalog> readByCanonicalTenantId(UUID canonicalTenantId) {
    requireNoActiveOwnerTransaction();
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    Optional<CanonicalRealmCatalog> result =
        readTransaction.execute(status -> readByCanonicalTenantIdInTransaction(canonicalTenantId));
    return Objects.requireNonNull(result, "Game Session canonical catalog read returned no result");
  }

  /** Reads complete stored catalog evidence inside another Game Session owner transaction. */
  Optional<CanonicalRealmCatalog> readByCanonicalTenantIdInOwnerTransaction(
      UUID canonicalTenantId) {
    requireOwnerTransaction();
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    return readByCanonicalTenantIdInTransaction(canonicalTenantId);
  }

  private CanonicalRealmCatalog establishInTransaction(PreparedInput input) {
    requireOwnerTransaction();
    lockIdentity(input);
    verifyStoredFreshAssociation(input.association(), input.source());

    Optional<CanonicalRealmCatalog> byTenant =
        readByCanonicalTenantIdInTransaction(input.source().canonicalTenantId());
    Optional<CanonicalRealmCatalog> byRequest =
        readByCatalogRequestIdInTransaction(input.catalogRequestId());
    if (byTenant.isPresent() || byRequest.isPresent()) {
      if (byTenant.isEmpty()
          || byRequest.isEmpty()
          || !byTenant.orElseThrow().realmId().equals(byRequest.orElseThrow().realmId())
          || !matchesInput(byTenant.orElseThrow(), input)) {
        throw new InvalidCanonicalRealmCatalogException(
            "Canonical realm retry differs from the complete original source or published policy evidence");
      }
      return byTenant.orElseThrow();
    }

    rejectPartialOrConflictingOwnerRows(input);
    rejectRetainedTenant(input.source().canonicalTenantId());

    String intakeRequestDigest = intakeRequestDigest(input);
    UUID intakeOperationId = allocateGsUuid(input.foreignIds());
    String intakeReceiptDigest = intakeReceiptDigest(intakeRequestDigest, intakeOperationId);
    String catalogRequestDigest =
        catalogRequestDigest(input, intakeOperationId, intakeReceiptDigest);
    UUID realmId = allocateGsUuid(union(input.foreignIds(), Set.of(intakeOperationId)));
    UUID playableNamespaceId =
        allocateGsUuid(union(input.foreignIds(), Set.of(intakeOperationId, realmId)));
    String catalogReceiptDigest =
        catalogReceiptDigest(
            catalogRequestDigest, realmId, playableNamespaceId, INITIAL_CATALOG_REVISION);

    insertSourceBinding(input.source());
    insertSourceIntake(input, intakeOperationId, intakeRequestDigest, intakeReceiptDigest);
    insertSharedNamespace(
        input, intakeOperationId, intakeRequestDigest, intakeReceiptDigest, playableNamespaceId);
    insertCatalog(
        input,
        intakeOperationId,
        intakeRequestDigest,
        intakeReceiptDigest,
        catalogRequestDigest,
        catalogReceiptDigest,
        realmId,
        playableNamespaceId);

    return readByCanonicalTenantIdInTransaction(input.source().canonicalTenantId())
        .orElseThrow(
            () ->
                new InvalidCanonicalRealmCatalogException(
                    "New canonical realm catalog did not read back inside the owner transaction"));
  }

  private Optional<CanonicalRealmCatalog> readByCanonicalTenantIdInTransaction(UUID tenantId) {
    requireOwnerTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_session_canonical_realm_catalog"
                + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
            workloadNamespace,
            tenantId);
    if (row == null) {
      rejectOrphanedCatalogPrerequisites(tenantId);
      return Optional.empty();
    }
    return Optional.of(readCompleteCatalog(row));
  }

  private Optional<CanonicalRealmCatalog> readByCatalogRequestIdInTransaction(UUID requestId) {
    requireOwnerTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_session_canonical_realm_catalog"
                + " WHERE target_namespace = ? AND creation_request_id = ?",
            workloadNamespace,
            requestId);
    if (row == null) {
      return Optional.empty();
    }
    UUID tenantId = required(row, "canonical_tenant_id", UUID.class);
    return readByCanonicalTenantIdInTransaction(tenantId);
  }

  private CanonicalRealmCatalog readCompleteCatalog(Record row) {
    String namespace = required(row, "target_namespace", String.class);
    UUID canonicalTenantId = required(row, "canonical_tenant_id", UUID.class);
    String tenantSlug = required(row, "tenant_slug", String.class);
    String worldSlug = required(row, "world_slug", String.class);
    String worldDisplayName = required(row, "source_world_display_name", String.class);
    UUID realmId = required(row, "realm_id", UUID.class);
    String realmSlug = required(row, "realm_slug", String.class);
    String realmDisplayName = required(row, "realm_display_name", String.class);
    UUID playableNamespaceId = required(row, "playable_state_namespace_id", UUID.class);
    String entryPolicyName = required(row, "character_creation_policy", String.class);
    long catalogRevision = required(row, "catalog_revision", Long.class);
    UUID catalogRequestId = required(row, "creation_request_id", UUID.class);
    String catalogRequestDigest = required(row, "request_digest", String.class);
    String catalogReceiptDigest = required(row, "receipt_digest", String.class);
    UUID intakeOperationId = required(row, "source_intake_operation_id", UUID.class);
    UUID intakeRequestId = required(row, "source_intake_request_id", UUID.class);
    String intakeRequestDigest = required(row, "source_intake_request_digest", String.class);
    String intakeReceiptDigest = required(row, "source_intake_receipt_digest", String.class);
    int sourceSchemaVersion = required(row, "source_schema_version", Integer.class);
    UUID sourceRegistrationRequestId = required(row, "source_registration_request_id", UUID.class);
    UUID sourceOperationId = required(row, "source_operation_id", UUID.class);
    String sourceRequestDigest = required(row, "source_request_digest", String.class);
    long sourceGameRowId = required(row, "source_game_row_id", Long.class);
    String sourceGameTenantKey = required(row, "source_game_tenant_key", String.class);
    String provenanceKind = required(row, "source_provenance_kind", String.class);
    String sourceEvidenceDigest = required(row, "source_evidence_digest", String.class);
    byte[] publishedSetBytes = required(row, "complete_policy_set_evidence", byte[].class);

    if (!workloadNamespace.equals(namespace)
        || !Boolean.TRUE.equals(required(row, "visible", Boolean.class))
        || !Boolean.TRUE.equals(required(row, "public_production", Boolean.class))
        || !"SHARED".equals(required(row, "state_scope", String.class))) {
      throw new InvalidCanonicalRealmCatalogException(
          "Stored canonical realm is outside the fresh public-production SHARED scope");
    }

    AuthoredWorldSourceEvidence source;
    RealmPolicyPublishedEvidence.PublishedSet publishedSet;
    try {
      source =
          new AuthoredWorldSourceEvidence(
              sourceSchemaVersion,
              namespace,
              sourceRegistrationRequestId,
              sourceOperationId,
              sourceRequestDigest,
              canonicalTenantId,
              tenantSlug,
              worldSlug,
              worldDisplayName,
              sourceGameRowId,
              sourceGameTenantKey,
              provenanceKind,
              sourceEvidenceDigest);
      publishedSet = RealmPolicyPublishedEvidenceCodec.decode(publishedSetBytes);
      if (!Arrays.equals(
          publishedSetBytes, RealmPolicyPublishedEvidenceCodec.encode(publishedSet))) {
        throw new IllegalArgumentException("Stored published policy evidence is not canonical");
      }
    } catch (IllegalArgumentException invalid) {
      throw new InvalidCanonicalRealmCatalogException(
          "Stored canonical realm source or complete policy evidence is invalid", invalid);
    }

    verifyStoredSourceBinding(source);
    verifyStoredSourceIntake(
        source, intakeOperationId, intakeRequestId, intakeRequestDigest, intakeReceiptDigest);
    FreshTenantAssociation association = verifyStoredFreshAssociation(source, canonicalTenantId);
    verifyStoredSharedNamespace(
        source,
        intakeOperationId,
        intakeRequestId,
        intakeRequestDigest,
        intakeReceiptDigest,
        playableNamespaceId);

    SelectedPolicy selected = selectPolicy(source, publishedSet);
    String expectedCatalogRequestDigest =
        catalogRequestDigest(
            workloadNamespace,
            catalogRequestId,
            canonicalTenantId,
            intakeOperationId,
            intakeReceiptDigest,
            selected.policyId(),
            selected.policyDigest(),
            publishedSet.policySetDigest());
    String expectedCatalogReceiptDigest =
        catalogReceiptDigest(
            expectedCatalogRequestDigest, realmId, playableNamespaceId, catalogRevision);
    if (!tenantSlug.equals(source.tenantSlug())
        || !worldSlug.equals(source.worldSlug())
        || !worldDisplayName.equals(source.worldDisplayName())
        || !realmSlug.equals(selected.policy().realmSlug())
        || !realmDisplayName.equals(selected.policy().realmDisplayName())
        || !entryPolicyName.equals(selected.policy().entryPolicy().name())
        || !catalogRequestDigest.equals(expectedCatalogRequestDigest)
        || !catalogReceiptDigest.equals(expectedCatalogReceiptDigest)) {
      throw new InvalidCanonicalRealmCatalogException(
          "Stored canonical realm receipt differs from exact source, selected policy, or actual revision");
    }

    verifyStoredCatalogSourceColumns(
        row, source, intakeOperationId, intakeRequestId, intakeRequestDigest, intakeReceiptDigest);

    return new CanonicalRealmCatalog(
        workloadNamespace,
        canonicalTenantId,
        association.gameSessionTenantId(),
        association.associationOperationId(),
        association.sourceEvidence(),
        source,
        intakeRequestId,
        intakeOperationId,
        intakeRequestDigest,
        intakeReceiptDigest,
        catalogRequestId,
        catalogRequestDigest,
        catalogReceiptDigest,
        realmId,
        playableNamespaceId,
        tenantSlug,
        worldSlug,
        realmSlug,
        realmDisplayName,
        worldDisplayName,
        entryPolicyName,
        catalogRevision,
        publishedSetBytes,
        publishedSet);
  }

  private void verifyStoredSourceBinding(AuthoredWorldSourceEvidence source) {
    Record binding =
        dsl.fetchOne(
            "SELECT target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id,"
                + " source_game_tenant_key, provenance_kind"
                + " FROM game_session_authored_world_tenant_source_binding"
                + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
            workloadNamespace,
            source.canonicalTenantId());
    if (binding == null
        || !workloadNamespace.equals(required(binding, "target_namespace", String.class))
        || !source.canonicalTenantId().equals(required(binding, "canonical_tenant_id", UUID.class))
        || !source.tenantSlug().equals(required(binding, "tenant_slug", String.class))
        || source.sourceGameRowId() != required(binding, "source_game_row_id", Long.class)
        || !source
            .sourceGameTenantKey()
            .equals(required(binding, "source_game_tenant_key", String.class))
        || !source.provenanceKind().equals(required(binding, "provenance_kind", String.class))) {
      throw new InvalidCanonicalRealmCatalogException(
          "Canonical realm has no exact immutable fresh tenant source binding");
    }
  }

  private void verifyStoredSourceIntake(
      AuthoredWorldSourceEvidence source,
      UUID operationId,
      UUID requestId,
      String requestDigest,
      String receiptDigest) {
    Record intake =
        dsl.fetchOne(
            "SELECT * FROM game_session_authored_world_source_intake"
                + " WHERE target_namespace = ? AND operation_id = ?",
            workloadNamespace,
            operationId);
    String expectedRequestDigest =
        intakeRequestDigest(
            workloadNamespace, requestId, source.operationId(), source.evidenceDigest());
    String expectedReceiptDigest = intakeReceiptDigest(expectedRequestDigest, operationId);
    if (intake == null
        || required(intake, "schema_version", Integer.class) != 1
        || required(intake, "source_schema_version", Integer.class) != source.schemaVersion()
        || !workloadNamespace.equals(required(intake, "target_namespace", String.class))
        || !requestId.equals(required(intake, "intake_request_id", UUID.class))
        || !requestDigest.equals(required(intake, "request_digest", String.class))
        || !expectedRequestDigest.equals(requestDigest)
        || !source
            .registrationRequestId()
            .equals(required(intake, "source_registration_request_id", UUID.class))
        || !source.operationId().equals(required(intake, "source_operation_id", UUID.class))
        || !source.requestDigest().equals(required(intake, "source_request_digest", String.class))
        || !source.canonicalTenantId().equals(required(intake, "canonical_tenant_id", UUID.class))
        || !source.tenantSlug().equals(required(intake, "tenant_slug", String.class))
        || !source.worldSlug().equals(required(intake, "world_slug", String.class))
        || !source.worldDisplayName().equals(required(intake, "world_display_name", String.class))
        || source.sourceGameRowId() != required(intake, "source_game_row_id", Long.class)
        || !source
            .sourceGameTenantKey()
            .equals(required(intake, "source_game_tenant_key", String.class))
        || !source.provenanceKind().equals(required(intake, "source_provenance_kind", String.class))
        || !source.evidenceDigest().equals(required(intake, "source_evidence_digest", String.class))
        || !receiptDigest.equals(required(intake, "receipt_digest", String.class))
        || !expectedReceiptDigest.equals(receiptDigest)) {
      throw new InvalidCanonicalRealmCatalogException(
          "Canonical realm source intake differs from complete original Game Design source evidence");
    }
  }

  private FreshTenantAssociation verifyStoredFreshAssociation(
      AuthoredWorldSourceEvidence source, UUID canonicalTenantId) {
    Record row =
        dsl.fetchOne(
            "SELECT fresh.target_namespace, fresh.association_request_id,"
                + " fresh.source_schema_version, fresh.source_target_namespace,"
                + " fresh.source_request_id, fresh.source_canonical_tenant_id,"
                + " fresh.canonical_tenant_id, fresh.legacy_game_session_tenant_id,"
                + " fresh.source_game_row_id, fresh.source_game_tenant_key, fresh.provenance_kind,"
                + " fresh.association_kind, fresh.association_operation_id,"
                + " fresh.complete_source_evidence::text AS complete_source_evidence,"
                + " claim.target_namespace AS claim_namespace,"
                + " claim.canonical_tenant_id AS claim_canonical_tenant_id,"
                + " claim.legacy_game_session_tenant_id AS claim_tenant_id,"
                + " claim.association_kind AS claim_kind,"
                + " claim.reservation_kind AS claim_reservation_kind,"
                + " claim.association_operation_id AS claim_operation_id,"
                + " claim.association_request_id AS claim_request_id,"
                + " reservation.reservation_kind AS reservation_kind"
                + " FROM game_session_fresh_tenant_association fresh"
                + " LEFT JOIN game_session_tenant_canonical_claim claim"
                + " ON claim.target_namespace = fresh.target_namespace"
                + " AND claim.canonical_tenant_id = fresh.canonical_tenant_id"
                + " LEFT JOIN game_session_tenant_scope_reservation reservation"
                + " ON reservation.game_session_tenant_id = fresh.legacy_game_session_tenant_id"
                + " WHERE fresh.target_namespace = ? AND fresh.canonical_tenant_id = ?",
            workloadNamespace,
            canonicalTenantId);
    if (row == null) {
      throw new InvalidCanonicalRealmCatalogException(
          "Fresh canonical realm requires an existing complete Game Session tenant association");
    }
    try {
      String requestIdText = required(row, "association_request_id", UUID.class).toString();
      UUID associationOperationId = required(row, "association_operation_id", UUID.class);
      UUID tenantId = required(row, "canonical_tenant_id", UUID.class);
      long numericTenantId = required(row, "legacy_game_session_tenant_id", Long.class);
      FreshTenantCreationEvidence creation =
          parseCreationEvidence(required(row, "complete_source_evidence", String.class));
      if (!workloadNamespace.equals(required(row, "target_namespace", String.class))
          || !workloadNamespace.equals(required(row, "source_target_namespace", String.class))
          || !requestIdText.equals(required(row, "source_request_id", UUID.class).toString())
          || !tenantId.equals(required(row, "source_canonical_tenant_id", UUID.class))
          || required(row, "source_schema_version", Integer.class) != creation.schemaVersion()
          || required(row, "source_game_row_id", Long.class) != creation.sourceGameRowId()
          || !required(row, "source_game_tenant_key", String.class)
              .equals(creation.sourceGameTenantKey())
          || !required(row, "provenance_kind", String.class).equals(creation.provenanceKind())
          || !"FRESH_SOURCE_BOUND".equals(required(row, "association_kind", String.class))
          || !creation.targetNamespace().equals(workloadNamespace)
          || !creation
              .creationRequestId()
              .equals(required(row, "association_request_id", UUID.class))
          || !creation.canonicalTenantId().equals(tenantId)
          || !creation.targetNamespace().equals(source.targetNamespace())
          || !creation.canonicalTenantId().equals(source.canonicalTenantId())
          || creation.sourceGameRowId() != source.sourceGameRowId()
          || !creation.sourceGameTenantKey().equals(source.sourceGameTenantKey())
          || !creation.provenanceKind().equals(source.provenanceKind())
          || !"FRESH_SOURCE_BOUND".equals(required(row, "claim_kind", String.class))
          || !"FRESH_SOURCE_BOUND".equals(required(row, "claim_reservation_kind", String.class))
          || !"FRESH_SOURCE_BOUND".equals(required(row, "reservation_kind", String.class))
          || !workloadNamespace.equals(required(row, "claim_namespace", String.class))
          || !tenantId.equals(required(row, "claim_canonical_tenant_id", UUID.class))
          || numericTenantId != required(row, "claim_tenant_id", Long.class)
          || !associationOperationId.equals(required(row, "claim_operation_id", UUID.class))
          || !required(row, "association_request_id", UUID.class)
              .equals(required(row, "claim_request_id", UUID.class))
          || associationOperationId.equals(creation.operationId())) {
        throw new InvalidCanonicalRealmCatalogException(
            "Stored fresh tenant association differs from complete source receipt or canonical claim");
      }
      return new FreshTenantAssociation(
          workloadNamespace, tenantId, numericTenantId, associationOperationId, creation);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidCanonicalRealmCatalogException(
          "Stored fresh tenant association contains invalid complete source evidence", invalid);
    }
  }

  private void verifyStoredFreshAssociation(
      FreshTenantAssociation expected, AuthoredWorldSourceEvidence source) {
    FreshTenantAssociation actual =
        verifyStoredFreshAssociation(source, expected.canonicalTenantId());
    if (!expected.equals(actual)) {
      throw new InvalidCanonicalRealmCatalogException(
          "Supplied fresh tenant association is not the exact committed Game Session owner row");
    }
  }

  private void verifyStoredSharedNamespace(
      AuthoredWorldSourceEvidence source,
      UUID intakeOperationId,
      UUID intakeRequestId,
      String intakeRequestDigest,
      String intakeReceiptDigest,
      UUID playableNamespaceId) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM gameplay_tenant_shared_playable_state_namespace"
                + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
            workloadNamespace,
            source.canonicalTenantId());
    if (row == null
        || row.get("tenant_id", Long.class) != null
        || !playableNamespaceId.equals(required(row, "playable_state_namespace_id", UUID.class))
        || !source.canonicalTenantId().equals(required(row, "canonical_tenant_id", UUID.class))
        || !workloadNamespace.equals(required(row, "target_namespace", String.class))
        || !intakeOperationId.equals(required(row, "source_intake_operation_id", UUID.class))
        || required(row, "source_intake_schema_version", Integer.class) != 1
        || !intakeRequestId.equals(required(row, "source_intake_request_id", UUID.class))
        || !intakeRequestDigest.equals(required(row, "source_intake_request_digest", String.class))
        || !intakeReceiptDigest.equals(required(row, "source_intake_receipt_digest", String.class))
        || required(row, "source_schema_version", Integer.class) != source.schemaVersion()
        || !source
            .registrationRequestId()
            .equals(required(row, "source_registration_request_id", UUID.class))
        || !source.operationId().equals(required(row, "source_operation_id", UUID.class))
        || !source.requestDigest().equals(required(row, "source_request_digest", String.class))
        || source.sourceGameRowId() != required(row, "source_game_row_id", Long.class)
        || !source
            .sourceGameTenantKey()
            .equals(required(row, "source_game_tenant_key", String.class))
        || !source.provenanceKind().equals(required(row, "source_provenance_kind", String.class))
        || !source.evidenceDigest().equals(required(row, "source_evidence_digest", String.class))) {
      throw new InvalidCanonicalRealmCatalogException(
          "Canonical realm SHARED namespace does not retain the exact complete source intake");
    }
  }

  private void verifyStoredCatalogSourceColumns(
      Record row,
      AuthoredWorldSourceEvidence source,
      UUID intakeOperationId,
      UUID intakeRequestId,
      String intakeRequestDigest,
      String intakeReceiptDigest) {
    if (!source.targetNamespace().equals(required(row, "target_namespace", String.class))
        || !source.canonicalTenantId().equals(required(row, "canonical_tenant_id", UUID.class))
        || !source.tenantSlug().equals(required(row, "tenant_slug", String.class))
        || !source.worldSlug().equals(required(row, "world_slug", String.class))
        || !source
            .worldDisplayName()
            .equals(required(row, "source_world_display_name", String.class))
        || !intakeOperationId.equals(required(row, "source_intake_operation_id", UUID.class))
        || required(row, "source_intake_schema_version", Integer.class) != 1
        || !intakeRequestId.equals(required(row, "source_intake_request_id", UUID.class))
        || !intakeRequestDigest.equals(required(row, "source_intake_request_digest", String.class))
        || !intakeReceiptDigest.equals(required(row, "source_intake_receipt_digest", String.class))
        || required(row, "source_schema_version", Integer.class) != source.schemaVersion()
        || !source
            .registrationRequestId()
            .equals(required(row, "source_registration_request_id", UUID.class))
        || !source.operationId().equals(required(row, "source_operation_id", UUID.class))
        || !source.requestDigest().equals(required(row, "source_request_digest", String.class))
        || source.sourceGameRowId() != required(row, "source_game_row_id", Long.class)
        || !source
            .sourceGameTenantKey()
            .equals(required(row, "source_game_tenant_key", String.class))
        || !source.provenanceKind().equals(required(row, "source_provenance_kind", String.class))
        || !source.evidenceDigest().equals(required(row, "source_evidence_digest", String.class))) {
      throw new InvalidCanonicalRealmCatalogException(
          "Canonical realm catalog columns differ from exact complete source intake evidence");
    }
  }

  private void rejectPartialOrConflictingOwnerRows(PreparedInput input) {
    AuthoredWorldSourceEvidence source = input.source();
    Record binding =
        dsl.fetchOne(
            "SELECT canonical_tenant_id FROM game_session_authored_world_tenant_source_binding"
                + " WHERE target_namespace = ? AND (canonical_tenant_id = ? OR tenant_slug = ?"
                + " OR source_game_row_id = ?)",
            workloadNamespace,
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.sourceGameRowId());
    Record intake =
        dsl.fetchOne(
            "SELECT operation_id FROM game_session_authored_world_source_intake"
                + " WHERE target_namespace = ? AND (intake_request_id = ?"
                + " OR source_registration_request_id = ? OR source_operation_id = ?"
                + " OR canonical_tenant_id = ?)",
            workloadNamespace,
            input.intakeRequestId(),
            source.registrationRequestId(),
            source.operationId(),
            source.canonicalTenantId());
    Record shared =
        dsl.fetchOne(
            "SELECT playable_state_namespace_id FROM gameplay_tenant_shared_playable_state_namespace"
                + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
            workloadNamespace,
            source.canonicalTenantId());
    Record request =
        dsl.fetchOne(
            "SELECT realm_id FROM game_session_canonical_realm_catalog"
                + " WHERE target_namespace = ? AND creation_request_id = ?",
            workloadNamespace,
            input.catalogRequestId());
    if (binding != null || intake != null || shared != null || request != null) {
      throw new InvalidCanonicalRealmCatalogException(
          "Partial, prior, or cross-tenant canonical owner rows cannot be adopted as a fresh catalog request");
    }
  }

  private void rejectOrphanedCatalogPrerequisites(UUID canonicalTenantId) {
    Record binding =
        dsl.fetchOne(
            "SELECT canonical_tenant_id FROM game_session_authored_world_tenant_source_binding"
                + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
            workloadNamespace,
            canonicalTenantId);
    Record intake =
        dsl.fetchOne(
            "SELECT operation_id FROM game_session_authored_world_source_intake"
                + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
            workloadNamespace,
            canonicalTenantId);
    Record shared =
        dsl.fetchOne(
            "SELECT playable_state_namespace_id FROM gameplay_tenant_shared_playable_state_namespace"
                + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
            workloadNamespace,
            canonicalTenantId);
    Record catalog =
        dsl.fetchOne(
            "SELECT realm_id FROM game_session_canonical_realm_catalog"
                + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
            workloadNamespace,
            canonicalTenantId);
    if (binding != null || intake != null || shared != null || catalog != null) {
      throw new InvalidCanonicalRealmCatalogException(
          "Incomplete historical or partial canonical realm owner rows fail closed");
    }
  }

  private void rejectRetainedTenant(UUID canonicalTenantId) {
    Record retained =
        dsl.fetchOne(
            "SELECT canonical_tenant_id FROM game_session_retained_tenant_association"
                + " WHERE target_namespace = ? AND canonical_tenant_id = ?",
            workloadNamespace,
            canonicalTenantId);
    if (retained != null) {
      throw new InvalidCanonicalRealmCatalogException(
          "Retained numeric tenant authority cannot be adopted by fresh canonical realm intake");
    }
  }

  private void insertSourceBinding(AuthoredWorldSourceEvidence source) {
    requireOne(
        dsl.execute(
            "INSERT INTO game_session_authored_world_tenant_source_binding"
                + " (target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id,"
                + " source_game_tenant_key, provenance_kind) VALUES (?, ?, ?, ?, ?, ?)",
            workloadNamespace,
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind()),
        "authored-world tenant source binding");
  }

  private void insertSourceIntake(
      PreparedInput input, UUID intakeOperationId, String requestDigest, String receiptDigest) {
    AuthoredWorldSourceEvidence source = input.source();
    requireOne(
        dsl.execute(
            "INSERT INTO game_session_authored_world_source_intake"
                + " (operation_id, schema_version, target_namespace, intake_request_id, request_digest,"
                + " source_schema_version, source_registration_request_id, source_operation_id,"
                + " source_request_digest, canonical_tenant_id, tenant_slug, world_slug,"
                + " world_display_name, source_game_row_id, source_game_tenant_key,"
                + " source_provenance_kind, source_evidence_digest, receipt_digest)"
                + " VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            intakeOperationId,
            workloadNamespace,
            input.intakeRequestId(),
            requestDigest,
            source.schemaVersion(),
            source.registrationRequestId(),
            source.operationId(),
            source.requestDigest(),
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.worldSlug(),
            source.worldDisplayName(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind(),
            source.evidenceDigest(),
            receiptDigest),
        "authored-world source intake");
  }

  private void insertSharedNamespace(
      PreparedInput input,
      UUID intakeOperationId,
      String intakeRequestDigest,
      String intakeReceiptDigest,
      UUID playableNamespaceId) {
    AuthoredWorldSourceEvidence source = input.source();
    requireOne(
        dsl.execute(
            "INSERT INTO gameplay_tenant_shared_playable_state_namespace"
                + " (tenant_id, playable_state_namespace_id, canonical_tenant_id, target_namespace,"
                + " source_intake_operation_id, source_intake_schema_version, source_intake_request_id,"
                + " source_intake_request_digest, source_intake_receipt_digest, source_schema_version,"
                + " source_registration_request_id, source_operation_id, source_request_digest,"
                + " source_game_row_id, source_game_tenant_key, source_provenance_kind, source_evidence_digest)"
                + " VALUES (NULL, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            playableNamespaceId,
            source.canonicalTenantId(),
            workloadNamespace,
            intakeOperationId,
            input.intakeRequestId(),
            intakeRequestDigest,
            intakeReceiptDigest,
            source.schemaVersion(),
            source.registrationRequestId(),
            source.operationId(),
            source.requestDigest(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind(),
            source.evidenceDigest()),
        "canonical tenant SHARED namespace");
  }

  private void insertCatalog(
      PreparedInput input,
      UUID intakeOperationId,
      String intakeRequestDigest,
      String intakeReceiptDigest,
      String catalogRequestDigest,
      String catalogReceiptDigest,
      UUID realmId,
      UUID playableNamespaceId) {
    AuthoredWorldSourceEvidence source = input.source();
    SelectedPolicy selected = input.selectedPolicy();
    requireOne(
        dsl.execute(
            "INSERT INTO game_session_canonical_realm_catalog"
                + " (target_namespace, canonical_tenant_id, tenant_slug, world_slug, realm_id,"
                + " realm_slug, realm_display_name, visible, public_production, state_scope,"
                + " playable_state_namespace_id, character_creation_policy, catalog_revision,"
                + " creation_request_id, request_digest, receipt_digest, source_intake_operation_id,"
                + " source_intake_schema_version, source_intake_request_id, source_intake_request_digest,"
                + " source_intake_receipt_digest, source_schema_version, source_registration_request_id,"
                + " source_operation_id, source_request_digest, source_world_display_name,"
                + " source_game_row_id, source_game_tenant_key, source_provenance_kind,"
                + " source_evidence_digest, complete_policy_set_evidence)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, 'SHARED', ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            workloadNamespace,
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.worldSlug(),
            realmId,
            selected.policy().realmSlug(),
            selected.policy().realmDisplayName(),
            playableNamespaceId,
            selected.policy().entryPolicy().name(),
            INITIAL_CATALOG_REVISION,
            input.catalogRequestId(),
            catalogRequestDigest,
            catalogReceiptDigest,
            intakeOperationId,
            input.intakeRequestId(),
            intakeRequestDigest,
            intakeReceiptDigest,
            source.schemaVersion(),
            source.registrationRequestId(),
            source.operationId(),
            source.requestDigest(),
            source.worldDisplayName(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind(),
            source.evidenceDigest(),
            input.publishedSetBytes()),
        "canonical public-production realm catalog");
  }

  private void lockIdentity(PreparedInput input) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 1))",
        workloadNamespace,
        input.source().canonicalTenantId().toString());
    List<String> requestLocks =
        List.of(
                "canonical-realm-intake:" + workloadNamespace + ":" + input.intakeRequestId(),
                "canonical-realm-request:" + workloadNamespace + ":" + input.catalogRequestId())
            .stream()
            .sorted()
            .toList();
    for (String key : requestLocks) {
      dsl.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", key);
    }
  }

  private PreparedInput prepareInput(
      FreshTenantAssociation association,
      AuthoredWorldSourceEvidence source,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet,
      UUID intakeRequestId,
      UUID catalogRequestId) {
    Objects.requireNonNull(association, "association");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(publishedSet, "publishedSet");
    requireNonNil(intakeRequestId, "intakeRequestId");
    requireNonNil(catalogRequestId, "catalogRequestId");
    FreshTenantCreationEvidence creation = association.sourceEvidence();
    if (!workloadNamespace.equals(association.targetNamespace())
        || !workloadNamespace.equals(source.targetNamespace())
        || !association.canonicalTenantId().equals(source.canonicalTenantId())
        || !workloadNamespace.equals(creation.targetNamespace())
        || !association.canonicalTenantId().equals(creation.canonicalTenantId())
        || creation.sourceGameRowId() != source.sourceGameRowId()
        || !creation.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !creation.provenanceKind().equals(source.provenanceKind())
        || !"NEW_GAME_ROW".equals(source.provenanceKind())) {
      throw new InvalidCanonicalRealmCatalogException(
          "Fresh tenant association and authored-world source differ in exact namespace, tenant, or NEW_GAME_ROW identity");
    }
    SelectedPolicy selected = selectPolicy(source, publishedSet);
    byte[] policyBytes;
    RealmPolicyPublishedEvidence.PublishedSet decoded;
    try {
      policyBytes = RealmPolicyPublishedEvidenceCodec.encode(publishedSet);
      decoded = RealmPolicyPublishedEvidenceCodec.decode(policyBytes);
      if (!Arrays.equals(policyBytes, RealmPolicyPublishedEvidenceCodec.encode(decoded))) {
        throw new IllegalArgumentException(
            "Published policy evidence changed across canonical encoding");
      }
    } catch (IllegalArgumentException invalid) {
      throw new InvalidCanonicalRealmCatalogException(
          "Complete canonical published policy evidence is required", invalid);
    }
    HashSet<UUID> foreignIds = new HashSet<>();
    foreignIds.add(creation.creationRequestId());
    foreignIds.add(creation.operationId());
    foreignIds.add(creation.canonicalTenantId());
    foreignIds.add(association.associationOperationId());
    foreignIds.add(source.registrationRequestId());
    foreignIds.add(source.operationId());
    foreignIds.add(publishedSet.target().canonicalTenantId());
    foreignIds.add(publishedSet.target().canonicalVersionId());
    foreignIds.add(publishedSet.sourceCommitId());
    publishedSet.policies().forEach(policy -> foreignIds.add(policy.policyId()));
    foreignIds.add(intakeRequestId);
    foreignIds.add(catalogRequestId);
    return new PreparedInput(
        association,
        source,
        decoded,
        selected,
        intakeRequestId,
        catalogRequestId,
        policyBytes,
        Set.copyOf(foreignIds));
  }

  private static SelectedPolicy selectPolicy(
      AuthoredWorldSourceEvidence source, RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    GameDesignPublicationOperationBinding publicationOperation =
        GameDesignPublicationOperationBinding.fromStored(publishedSet.operationBytes());
    if (!publishedSet.target().canonicalTenantId().equals(source.canonicalTenantId())
        || publishedSet.target().sourceGameRowId() != source.sourceGameRowId()
        || !publishedSet.target().sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !publishedSet.target().sourceProvenanceKind().equals(source.provenanceKind())
        || !publicationOperation
            .world()
            .request()
            .targetNamespace()
            .equals(source.targetNamespace())
        || !"NEW_GAME_ROW".equals(publishedSet.target().sourceProvenanceKind())) {
      throw new InvalidCanonicalRealmCatalogException(
          "Published policy target or operation namespace differs from exact fresh authored-world source identity");
    }
    List<RealmPolicyPublishedEvidence.Policy> publicPolicies =
        publishedSet.policies().stream()
            .filter(
                policy ->
                    policy.source().policy().visible()
                        && policy.source().policy().publicProduction())
            .toList();
    if (publishedSet.policyCount() != publishedSet.policies().size()
        || publicPolicies.size() != 1) {
      throw new InvalidCanonicalRealmCatalogException(
          "Exactly one complete visible public-production policy is required");
    }
    RealmPolicyPublishedEvidence.Policy selected = publicPolicies.getFirst();
    RealmEntryPolicy policy = selected.source().policy();
    if (policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || !source.worldSlug().equals(policy.worldSlug())
        || !source.worldDisplayName().equals(policy.worldDisplayName())) {
      throw new InvalidCanonicalRealmCatalogException(
          "Public-production policy must exactly match authored world and use SHARED state scope");
    }
    return new SelectedPolicy(selected.policyId(), selected.policyDigest(), policy);
  }

  private static boolean matchesInput(CanonicalRealmCatalog stored, PreparedInput input) {
    return stored.targetNamespace().equals(input.source().targetNamespace())
        && stored.canonicalTenantId().equals(input.source().canonicalTenantId())
        && stored.gameSessionTenantId() == input.association().gameSessionTenantId()
        && stored
            .tenantAssociationOperationId()
            .equals(input.association().associationOperationId())
        && stored.associationSourceEvidence().equals(input.association().sourceEvidence())
        && stored.sourceEvidence().equals(input.source())
        && stored.intakeRequestId().equals(input.intakeRequestId())
        && stored.catalogRequestId().equals(input.catalogRequestId())
        && stored.realmSlug().equals(input.selectedPolicy().policy().realmSlug())
        && stored.realmDisplayName().equals(input.selectedPolicy().policy().realmDisplayName())
        && stored.entryPolicy().equals(input.selectedPolicy().policy().entryPolicy().name())
        && Arrays.equals(stored.completePolicySetEvidence(), input.publishedSetBytes());
  }

  private static String intakeRequestDigest(PreparedInput input) {
    return intakeRequestDigest(
        input.source().targetNamespace(),
        input.intakeRequestId(),
        input.source().operationId(),
        input.source().evidenceDigest());
  }

  private static String intakeRequestDigest(
      String namespace, UUID intakeRequestId, UUID sourceOperationId, String sourceEvidenceDigest) {
    return digest(
        "gs-authored-world-source-intake-request/v1",
        namespace,
        intakeRequestId.toString(),
        sourceOperationId.toString(),
        sourceEvidenceDigest);
  }

  private static String intakeReceiptDigest(String requestDigest, UUID intakeOperationId) {
    return digest(
        "gs-authored-world-source-intake-receipt/v1", requestDigest, intakeOperationId.toString());
  }

  private String catalogRequestDigest(
      PreparedInput input, UUID intakeOperationId, String intakeReceiptDigest) {
    return catalogRequestDigest(
        workloadNamespace,
        input.catalogRequestId(),
        input.source().canonicalTenantId(),
        intakeOperationId,
        intakeReceiptDigest,
        input.selectedPolicy().policyId(),
        input.selectedPolicy().policyDigest(),
        input.publishedSet().policySetDigest());
  }

  private static String catalogRequestDigest(
      String namespace,
      UUID catalogRequestId,
      UUID canonicalTenantId,
      UUID intakeOperationId,
      String intakeReceiptDigest,
      UUID policyId,
      String policyDigest,
      String policySetDigest) {
    return digest(
        "gs-canonical-public-realm-request/v1",
        namespace,
        catalogRequestId.toString(),
        canonicalTenantId.toString(),
        intakeOperationId.toString(),
        intakeReceiptDigest,
        policyId.toString(),
        policyDigest,
        policySetDigest);
  }

  private static String catalogReceiptDigest(
      String requestDigest, UUID realmId, UUID playableNamespaceId, long catalogRevision) {
    return digest(
        "gs-canonical-public-realm-receipt/v1",
        requestDigest,
        realmId.toString(),
        playableNamespaceId.toString(),
        Long.toString(catalogRevision));
  }

  private static String digest(String... segments) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      sha256.update(frame(segments));
      return "sha256:" + HexFormat.of().formatHex(sha256.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** ADR0047 UTF-8 byte-length framing; every input segment has an unambiguous byte boundary. */
  private static byte[] frame(String... segments) {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    for (String segment : segments) {
      byte[] bytes = strictUtf8(segment);
      result.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
      result.write(':');
      result.writeBytes(bytes);
    }
    return result.toByteArray();
  }

  private static byte[] strictUtf8(String value) {
    Objects.requireNonNull(value, "digest segment");
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(java.nio.CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException invalidUtf8) {
      throw new InvalidCanonicalRealmCatalogException(
          "Canonical owner digest input must be valid UTF-8", invalidUtf8);
    }
  }

  private static UUID allocateGsUuid(Set<UUID> excluded) {
    UUID allocated;
    do {
      allocated = UUID.randomUUID();
    } while (NIL_UUID.equals(allocated) || excluded.contains(allocated));
    return allocated;
  }

  private static Set<UUID> union(Set<UUID> values, Set<UUID> more) {
    HashSet<UUID> union = new HashSet<>(values);
    union.addAll(more);
    return Set.copyOf(union);
  }

  private static FreshTenantCreationEvidence parseCreationEvidence(String serializedEvidence) {
    try {
      JsonNode json = JSON.readTree(serializedEvidence);
      if (json == null || !json.isObject() || json.size() != CREATION_EVIDENCE_FIELDS.size()) {
        throw new IllegalArgumentException(
            "Complete creation evidence must be one closed JSON object");
      }
      HashSet<String> fields = new HashSet<>();
      json.fieldNames().forEachRemaining(fields::add);
      if (!fields.equals(CREATION_EVIDENCE_FIELDS)) {
        throw new IllegalArgumentException(
            "Complete creation evidence has unknown or missing fields");
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
    } catch (IOException | IllegalArgumentException invalid) {
      throw new InvalidCanonicalRealmCatalogException(
          "Stored complete Game Design creation evidence is invalid", invalid);
    }
  }

  private static String requiredText(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Complete creation evidence field must be text: " + field);
    }
    return value.textValue();
  }

  private static int requiredInt(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(
          "Complete creation evidence field must be an integer: " + field);
    }
    return value.intValue();
  }

  private static long requiredLong(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(
          "Complete creation evidence field must be an integer: " + field);
    }
    return value.longValue();
  }

  private static UUID parseUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
      throw new IllegalArgumentException(
          "Complete creation evidence UUID must be canonical and nonnil");
    }
    return parsed;
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) {
      throw new InvalidCanonicalRealmCatalogException(
          "Stored canonical realm catalog is missing required evidence: " + field);
    }
    return value;
  }

  private static void requireOne(int affectedRows, String label) {
    if (affectedRows != 1) {
      throw new InvalidCanonicalRealmCatalogException(
          "Game Session " + label + " insert did not affect exactly one row");
    }
  }

  private static void requireNonNil(UUID id, String label) {
    if (id == null || NIL_UUID.equals(id)) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
  }

  private static void requireNoActiveOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authenticated Game Design reads must complete before opening a Game Session owner transaction");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Session canonical realm storage access requires an active owner transaction");
    }
  }

  private record PreparedInput(
      FreshTenantAssociation association,
      AuthoredWorldSourceEvidence source,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet,
      SelectedPolicy selectedPolicy,
      UUID intakeRequestId,
      UUID catalogRequestId,
      byte[] publishedSetBytes,
      Set<UUID> foreignIds) {
    private PreparedInput {
      publishedSetBytes = publishedSetBytes.clone();
      foreignIds = Set.copyOf(foreignIds);
    }

    @Override
    public byte[] publishedSetBytes() {
      return publishedSetBytes.clone();
    }
  }

  private record SelectedPolicy(UUID policyId, String policyDigest, RealmEntryPolicy policy) {}

  /** Complete GS-owned source intake and catalog receipt, including the exact stored policy set. */
  public record CanonicalRealmCatalog(
      String targetNamespace,
      UUID canonicalTenantId,
      long gameSessionTenantId,
      UUID tenantAssociationOperationId,
      FreshTenantCreationEvidence associationSourceEvidence,
      AuthoredWorldSourceEvidence sourceEvidence,
      UUID intakeRequestId,
      UUID intakeOperationId,
      String intakeRequestDigest,
      String intakeReceiptDigest,
      UUID catalogRequestId,
      String catalogRequestDigest,
      String catalogReceiptDigest,
      UUID realmId,
      UUID playableStateNamespaceId,
      String tenantSlug,
      String worldSlug,
      String realmSlug,
      String realmDisplayName,
      String sourceWorldDisplayName,
      String entryPolicy,
      long catalogRevision,
      byte[] completePolicySetEvidence,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    public CanonicalRealmCatalog {
      Objects.requireNonNull(targetNamespace);
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(tenantAssociationOperationId, "tenantAssociationOperationId");
      Objects.requireNonNull(associationSourceEvidence);
      Objects.requireNonNull(sourceEvidence);
      requireNonNil(intakeRequestId, "intakeRequestId");
      requireNonNil(intakeOperationId, "intakeOperationId");
      Objects.requireNonNull(intakeRequestDigest);
      Objects.requireNonNull(intakeReceiptDigest);
      requireNonNil(catalogRequestId, "catalogRequestId");
      Objects.requireNonNull(catalogRequestDigest);
      Objects.requireNonNull(catalogReceiptDigest);
      requireNonNil(realmId, "realmId");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(tenantSlug);
      Objects.requireNonNull(worldSlug);
      Objects.requireNonNull(realmSlug);
      Objects.requireNonNull(realmDisplayName);
      Objects.requireNonNull(sourceWorldDisplayName);
      Objects.requireNonNull(entryPolicy);
      if (gameSessionTenantId <= 0 || catalogRevision <= 0) {
        throw new IllegalArgumentException(
            "Positive GS tenant allocation and catalog revision required");
      }
      completePolicySetEvidence =
          Objects.requireNonNull(completePolicySetEvidence, "completePolicySetEvidence").clone();
      if (completePolicySetEvidence.length == 0) {
        throw new IllegalArgumentException("Complete published policy evidence required");
      }
      Objects.requireNonNull(publishedSet, "publishedSet");
    }

    @Override
    public byte[] completePolicySetEvidence() {
      return completePolicySetEvidence.clone();
    }
  }

  public static final class InvalidCanonicalRealmCatalogException extends IllegalStateException {
    public InvalidCanonicalRealmCatalogException(String message) {
      super(message);
    }

    public InvalidCanonicalRealmCatalogException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
