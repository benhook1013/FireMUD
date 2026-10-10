package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.CanonicalRealmCatalog;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Game Session owner storage for one complete canonical launch.
 *
 * <p>Inputs are integrity-checked values obtained by the original StartSession composition; this
 * repository does not authenticate their producers, repeat Account authorization, or admit a World
 * instance. It atomically creates only the durable Game Session STARTING row and its V26 launch
 * association, then proves the exact committed owner state in a separate read transaction.
 */
public final class GameSessionCanonicalInstanceLaunchRepository {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;

  public GameSessionCanonicalInstanceLaunchRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      String workloadNamespace,
      GameSessionCanonicalRealmCatalogRepository catalogRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is missing or invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
    this.writeTransaction = new TransactionTemplate(transactionManager);
    this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.readTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction.setReadOnly(true);
    this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Creates one fresh canonical runtime allocation or returns its exact existing request retry.
   *
   * <p>The opaque Game Instance UUID is allocated inside the owner transaction only after the
   * namespace/request lock and exact-retry lookup. The original tuple and complete descriptor /
   * release pair are retained without projecting or reconstructing either value.
   */
  public CanonicalInstanceLaunch createStartingLaunch(
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      CompleteLaunchBindingEvidence completeLaunchBindingEvidence,
      CanonicalRealmCatalog expectedCatalog) {
    requireNoActiveOwnerTransaction();
    PreparedLaunch input =
        prepareInput(originalTuple, completeLaunchBindingEvidence, expectedCatalog);

    CanonicalInstanceLaunch committed =
        writeTransaction.execute(status -> createInTransaction(input));
    if (committed == null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Game Session canonical launch transaction returned no result");
    }

    CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector =
        independentReadSelector(input, committed.gameInstanceUuid());
    CanonicalInstanceLaunch independentReadback =
        readByLaunchIdentity(selector)
            .orElseThrow(
                () ->
                    new InvalidCanonicalInstanceLaunchException(
                        "Committed canonical launch association is unavailable on independent readback"));
    if (!matchesInput(independentReadback, input)) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Committed canonical launch failed independent exact tuple, binding, runtime, or catalog readback");
    }
    return independentReadback;
  }

  /** Reads complete immutable launch evidence and the current Game Instance row in one snapshot. */
  public Optional<CanonicalInstanceLaunch> readByLaunchIdentity(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request) {
    requireNoActiveOwnerTransaction();
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Launch-association namespace differs from the configured Game Session owner");
    }
    Optional<CanonicalInstanceLaunch> result =
        readTransaction.execute(status -> readByLaunchIdentityInTransaction(request));
    return Objects.requireNonNull(
        result, "Game Session launch read transaction returned no result");
  }

  private CanonicalInstanceLaunch createInTransaction(PreparedLaunch input) {
    requireOwnerTransaction();
    lockIdentity(input);

    CanonicalRealmCatalog actualCatalog =
        catalogRepository
            .readByCanonicalTenantIdInOwnerTransaction(input.expectedCatalog().canonicalTenantId())
            .orElseThrow(
                () ->
                    new InvalidCanonicalInstanceLaunchException(
                        "Complete source-backed canonical catalog is unavailable in the launch transaction"));
    requireSameCatalog(input.expectedCatalog(), actualCatalog);
    validateTupleBindingCatalogAndDescriptor(input.originalTuple(), input.binding(), actualCatalog);

    Record existing =
        readLaunchRowByOperationInTransaction(input.originalTuple().controlPlaneRequestId(), true);
    if (existing != null) {
      CanonicalInstanceLaunch retained = readLaunchRow(existing, false);
      if (!matchesInput(retained, input)) {
        throw new InvalidCanonicalInstanceLaunchException(
            "Original StartSession request is already bound to different complete launch evidence");
      }
      return retained;
    }

    rejectOrphanedRunOwnedInstance(input);
    UUID gameInstanceUuid = newNonNilUuid();
    long startingRowVersion = 0L;
    long gameInstanceId = insertStartingGameInstance(input, gameInstanceUuid, startingRowVersion);
    insertLaunchAssociation(
        input, actualCatalog, gameInstanceId, gameInstanceUuid, startingRowVersion);

    Record inserted =
        readLaunchRowByOperationInTransaction(input.originalTuple().controlPlaneRequestId(), false);
    if (inserted == null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "New Game Session STARTING instance and launch association did not read back in their owner transaction");
    }
    return readLaunchRow(inserted, true);
  }

  private Optional<CanonicalInstanceLaunch> readByLaunchIdentityInTransaction(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request) {
    requireOwnerTransaction();
    Record row = readLaunchRowByOperationInTransaction(request.controlPlaneRequestId(), false);
    if (row == null) {
      return Optional.empty();
    }
    CanonicalInstanceLaunch launch = readLaunchRow(row, false);
    requireRequestMatchesLaunch(request, launch);
    return Optional.of(launch);
  }

  private Record readLaunchRowByOperationInTransaction(
      String controlPlaneRequestId, boolean forUpdate) {
    requireOwnerTransaction();
    String suffix = forUpdate ? " FOR UPDATE OF launch" : "";
    return dsl.fetchOne(
        "SELECT launch.*, runtime.status AS gs_current_status,"
            + " runtime.row_version AS gs_current_row_version,"
            + " runtime.game_instance_uuid AS gs_runtime_instance_uuid,"
            + " runtime.tenant_id AS gs_runtime_tenant_id,"
            + " runtime.runtime_version AS gs_runtime_version,"
            + " runtime.owner_account_uuid AS gs_runtime_owner_account_uuid,"
            + " runtime.game_template_id AS gs_runtime_game_template_id,"
            + " runtime.launch_descriptor_id AS gs_runtime_launch_descriptor_id,"
            + " runtime.version_id AS gs_runtime_version_id,"
            + " runtime.release_bundle_id AS gs_runtime_release_bundle_id,"
            + " runtime.generation_config_revision AS gs_runtime_generation_config_revision,"
            + " runtime.version_state_epoch AS gs_runtime_version_state_epoch,"
            + " runtime.run_owned_start_request_id AS gs_runtime_request_id,"
            + " runtime.run_owned_start_request_digest AS gs_runtime_request_digest,"
            + " runtime.run_owned_start_published_release_bundle_ref AS gs_runtime_release_ref,"
            + " launch.complete_launch_binding_evidence::text AS gs_complete_launch_binding_json"
            + " FROM game_session_canonical_instance_launch launch"
            + " LEFT JOIN game_instances runtime"
            + " ON runtime.tenant_id = launch.game_session_tenant_id"
            + " AND runtime.id = launch.game_instance_id"
            + " AND runtime.game_instance_uuid = launch.game_instance_uuid"
            + " WHERE launch.target_namespace = ? AND launch.control_plane_request_id = ?"
            + suffix,
        workloadNamespace,
        controlPlaneRequestId);
  }

  private CanonicalInstanceLaunch readLaunchRow(Record row, boolean requireStartingAtCapture) {
    String namespace = required(row, "target_namespace", String.class);
    String controlPlaneRequestId = required(row, "control_plane_request_id", String.class);
    long gameSessionTenantId = required(row, "game_session_tenant_id", Long.class);
    UUID canonicalTenantId = required(row, "canonical_tenant_id", UUID.class);
    UUID tenantAssociationOperationId =
        required(row, "tenant_association_operation_id", UUID.class);
    UUID tenantAssociationRequestId = required(row, "tenant_association_request_id", UUID.class);
    int sourceSchemaVersion = required(row, "tenant_source_schema_version", Integer.class);
    String sourceTargetNamespace = required(row, "tenant_source_target_namespace", String.class);
    UUID sourceRequestId = required(row, "tenant_source_request_id", UUID.class);
    UUID sourceCanonicalTenantId = required(row, "tenant_source_canonical_tenant_id", UUID.class);
    long sourceGameRowId = required(row, "tenant_source_game_row_id", Long.class);
    String sourceGameTenantKey = required(row, "tenant_source_game_tenant_key", String.class);
    String sourceProvenanceKind = required(row, "tenant_source_provenance_kind", String.class);
    String associationKind = required(row, "tenant_association_kind", String.class);
    long gameInstanceId = required(row, "game_instance_id", Long.class);
    UUID gameInstanceUuid = required(row, "game_instance_uuid", UUID.class);
    UUID canonicalRealmId = required(row, "canonical_realm_id", UUID.class);
    String worldSlug = required(row, "world_slug", String.class);
    UUID playableStateNamespaceId = required(row, "playable_state_namespace_id", UUID.class);
    String playableStateScopeName = required(row, "playable_state_scope", String.class);
    boolean publicProduction = required(row, "public_production", Boolean.class);
    long capturedStartingRowVersion = required(row, "captured_starting_row_version", Long.class);
    long gameTemplateId = required(row, "game_template_id", Long.class);
    String launchDescriptorId = required(row, "launch_descriptor_id", String.class);
    long versionId = required(row, "version_id", Long.class);
    long releaseBundleId = required(row, "release_bundle_id", Long.class);
    String generationConfigRevision = required(row, "generation_config_revision", String.class);
    long versionStateEpoch = required(row, "version_state_epoch", Long.class);
    String bindingJson = required(row, "gs_complete_launch_binding_json", String.class);
    byte[] originalTupleBytes = required(row, "original_start_session_tuple", byte[].class);
    UUID catalogCreationRequestId = required(row, "catalog_creation_request_id", UUID.class);
    long catalogRevision = required(row, "catalog_revision", Long.class);
    UUID catalogSourceIntakeOperationId =
        required(row, "catalog_source_intake_operation_id", UUID.class);
    UUID catalogSourceIntakeRequestId =
        required(row, "catalog_source_intake_request_id", UUID.class);

    if (!workloadNamespace.equals(namespace)
        || !"FRESH_SOURCE_BOUND".equals(associationKind)
        || sourceSchemaVersion != 1
        || !namespace.equals(sourceTargetNamespace)
        || !sourceRequestId.equals(tenantAssociationRequestId)
        || !canonicalTenantId.equals(sourceCanonicalTenantId)
        || !"NEW_GAME_ROW".equals(sourceProvenanceKind)
        || gameSessionTenantId <= 0L
        || gameInstanceId <= 0L
        || capturedStartingRowVersion < 0L
        || catalogRevision <= 0L) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored canonical launch source identity, scope, or captured catalog key is incomplete");
    }

    Long runtimeTenantId = nullable(row, "gs_runtime_tenant_id", Long.class);
    Long currentRowVersion = nullable(row, "gs_current_row_version", Long.class);
    String status = nullable(row, "gs_current_status", String.class);
    UUID runtimeUuid = nullable(row, "gs_runtime_instance_uuid", UUID.class);
    if (runtimeTenantId == null
        || currentRowVersion == null
        || status == null
        || runtimeUuid == null
        || runtimeTenantId != gameSessionTenantId
        || !gameInstanceUuid.equals(runtimeUuid)
        || currentRowVersion < capturedStartingRowVersion) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored canonical launch association has no exact current Game Instance row");
    }
    CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus typedStatus =
        parseStatus(status);
    if (requireStartingAtCapture
        && (typedStatus
                != CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                    .STARTING
            || currentRowVersion != capturedStartingRowVersion)) {
      throw new InvalidCanonicalInstanceLaunchException(
          "New canonical launch must capture the exact STARTING Game Instance row version");
    }

    CompleteLaunchBindingEvidence binding = decodeBinding(bindingJson);
    StartSessionPostAuthorizationExecutionTuple originalTuple = decodeTuple(originalTupleBytes);
    CanonicalRealmCatalog catalog =
        catalogRepository
            .readByCanonicalTenantIdInOwnerTransaction(canonicalTenantId)
            .orElseThrow(
                () ->
                    new InvalidCanonicalInstanceLaunchException(
                        "Stored canonical launch source-backed catalog is unavailable"));
    requireStoredCatalogIdentity(
        row,
        catalog,
        canonicalRealmId,
        worldSlug,
        playableStateNamespaceId,
        catalogCreationRequestId,
        catalogRevision,
        catalogSourceIntakeOperationId,
        catalogSourceIntakeRequestId);
    AuthoredWorldSourceEvidence launchSource = catalog.sourceEvidence();
    validateTupleBindingCatalogAndDescriptor(originalTuple, binding, catalog);
    requireAssociationBinding(
        namespace,
        controlPlaneRequestId,
        canonicalTenantId,
        gameSessionTenantId,
        tenantAssociationOperationId,
        tenantAssociationRequestId,
        sourceSchemaVersion,
        sourceTargetNamespace,
        sourceRequestId,
        sourceCanonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        sourceProvenanceKind,
        gameInstanceId,
        gameInstanceUuid,
        canonicalRealmId,
        worldSlug,
        playableStateNamespaceId,
        playableStateScopeName,
        publicProduction,
        capturedStartingRowVersion,
        gameTemplateId,
        launchDescriptorId,
        versionId,
        releaseBundleId,
        generationConfigRevision,
        versionStateEpoch,
        originalTuple,
        binding,
        row,
        launchSource,
        catalog);

    return new CanonicalInstanceLaunch(
        namespace,
        controlPlaneRequestId,
        gameSessionTenantId,
        canonicalTenantId,
        gameInstanceId,
        gameInstanceUuid,
        canonicalRealmId,
        playableStateNamespaceId,
        RealmEntryPolicy.StateScope.valueOf(playableStateScopeName),
        publicProduction,
        typedStatus,
        currentRowVersion,
        capturedStartingRowVersion,
        catalogCreationRequestId,
        catalogRevision,
        catalogSourceIntakeOperationId,
        catalogSourceIntakeRequestId,
        binding,
        originalTupleBytes,
        catalog);
  }

  private void requireAssociationBinding(
      String namespace,
      String controlPlaneRequestId,
      UUID canonicalTenantId,
      long gameSessionTenantId,
      UUID tenantAssociationOperationId,
      UUID tenantAssociationRequestId,
      int sourceSchemaVersion,
      String sourceTargetNamespace,
      UUID sourceRequestId,
      UUID sourceCanonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String sourceProvenanceKind,
      long gameInstanceId,
      UUID gameInstanceUuid,
      UUID canonicalRealmId,
      String worldSlug,
      UUID playableStateNamespaceId,
      String playableStateScopeName,
      boolean publicProduction,
      long capturedStartingRowVersion,
      long gameTemplateId,
      String launchDescriptorId,
      long versionId,
      long releaseBundleId,
      String generationConfigRevision,
      long versionStateEpoch,
      StartSessionPostAuthorizationExecutionTuple tuple,
      CompleteLaunchBindingEvidence binding,
      Record row,
      AuthoredWorldSourceEvidence source,
      CanonicalRealmCatalog catalog) {
    var descriptor = binding.descriptor();
    FreshTenantCreationEvidence fresh = catalog.associationSourceEvidence();
    if (!namespace.equals(catalog.targetNamespace())
        || !canonicalTenantId.equals(catalog.canonicalTenantId())
        || gameSessionTenantId != catalog.gameSessionTenantId()
        || !tenantAssociationOperationId.equals(catalog.tenantAssociationOperationId())
        || !tenantAssociationRequestId.equals(fresh.creationRequestId())
        || sourceSchemaVersion != fresh.schemaVersion()
        || !sourceTargetNamespace.equals(fresh.targetNamespace())
        || !sourceRequestId.equals(fresh.creationRequestId())
        || !sourceCanonicalTenantId.equals(fresh.canonicalTenantId())
        || sourceGameRowId != fresh.sourceGameRowId()
        || !sourceGameTenantKey.equals(fresh.sourceGameTenantKey())
        || !sourceProvenanceKind.equals(fresh.provenanceKind())
        || !source.equals(catalog.sourceEvidence())
        || !worldSlug.equals(catalog.worldSlug())
        || !canonicalRealmId.equals(catalog.realmId())
        || !playableStateNamespaceId.equals(catalog.playableStateNamespaceId())
        || !"SHARED".equals(playableStateScopeName)
        || !publicProduction
        || !controlPlaneRequestId.equals(tuple.controlPlaneRequestId())
        || !controlPlaneRequestId.equals(descriptor.controlPlaneRequestId())
        || !canonicalTenantId.equals(tuple.preAuthorizationTuple().action().scope().tenantId())
        || !namespace.equals(tuple.preAuthorizationTuple().action().scope().targetNamespace())
        || tuple.preAuthorizationTuple().action().target().gameTemplateId() != gameTemplateId
        || !tuple
            .preAuthorizationTuple()
            .action()
            .target()
            .ownerAccountId()
            .equals(nullable(row, "gs_runtime_owner_account_uuid", UUID.class))
        || descriptor.gameTemplateId() != gameTemplateId
        || !descriptor.launchDescriptorId().equals(launchDescriptorId)
        || descriptor.versionId() != versionId
        || descriptor.releaseBundleId() != releaseBundleId
        || !descriptor.generationConfigRevision().equals(generationConfigRevision)
        || descriptor.versionStateEpoch() != versionStateEpoch
        || descriptor.authoredWorldSourceOperationId().equals(source.operationId()) == false
        || !descriptor.authoredWorldSourceEvidenceDigest().equals(source.evidenceDigest())
        || !descriptor
            .publishedReleaseBundleRef()
            .equals(required(row, "gs_runtime_release_ref", String.class))
        || !Long.toString(descriptor.versionId())
            .equals(required(row, "gs_runtime_version", String.class))
        || required(row, "gs_runtime_game_template_id", Long.class) != gameTemplateId
        || !descriptor
            .launchDescriptorId()
            .equals(required(row, "gs_runtime_launch_descriptor_id", String.class))
        || required(row, "gs_runtime_version_id", Long.class) != versionId
        || required(row, "gs_runtime_release_bundle_id", Long.class) != releaseBundleId
        || !descriptor
            .generationConfigRevision()
            .equals(required(row, "gs_runtime_generation_config_revision", String.class))
        || required(row, "gs_runtime_version_state_epoch", Long.class) != versionStateEpoch
        || gameInstanceId <= 0L
        || NIL_UUID.equals(tenantAssociationOperationId)
        || NIL_UUID.equals(gameInstanceUuid)
        || capturedStartingRowVersion < 0L
        || required(row, "gs_runtime_tenant_id", Long.class) != gameSessionTenantId
        || !gameInstanceUuid.equals(required(row, "gs_runtime_instance_uuid", UUID.class))
        || !controlPlaneRequestId.equals(required(row, "gs_runtime_request_id", String.class))
        || !tuple
            .mutationDigest()
            .equals(required(row, "gs_runtime_request_digest", String.class))) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored canonical launch association differs from the exact original tuple, complete binding, or source-backed catalog");
    }
  }

  private void requireStoredCatalogIdentity(
      Record row,
      CanonicalRealmCatalog catalog,
      UUID canonicalRealmId,
      String worldSlug,
      UUID playableStateNamespaceId,
      UUID catalogCreationRequestId,
      long catalogRevision,
      UUID catalogSourceIntakeOperationId,
      UUID catalogSourceIntakeRequestId) {
    if (!catalog.targetNamespace().equals(required(row, "target_namespace", String.class))
        || !catalog.canonicalTenantId().equals(required(row, "canonical_tenant_id", UUID.class))
        || !catalog.realmId().equals(canonicalRealmId)
        || !catalog.worldSlug().equals(worldSlug)
        || !catalog.playableStateNamespaceId().equals(playableStateNamespaceId)
        || !catalog.catalogRequestId().equals(catalogCreationRequestId)
        || catalog.catalogRevision() != catalogRevision
        || !catalog.intakeOperationId().equals(catalogSourceIntakeOperationId)
        || !catalog.intakeRequestId().equals(catalogSourceIntakeRequestId)
        || !catalog.worldSlug().equals(required(row, "world_slug", String.class))
        || !"SHARED".equals(required(row, "playable_state_scope", String.class))
        || !Boolean.TRUE.equals(required(row, "public_production", Boolean.class))) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored launch catalog identity is not the exact actual complete catalog key and revision");
    }
  }

  private void lockIdentity(PreparedLaunch input) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 1))",
        workloadNamespace,
        input.expectedCatalog().canonicalTenantId().toString());
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "canonical-instance-launch:"
            + workloadNamespace
            + ":"
            + input.originalTuple().controlPlaneRequestId());
  }

  private void rejectOrphanedRunOwnedInstance(PreparedLaunch input) {
    String requestId = input.originalTuple().controlPlaneRequestId();
    Record orphan =
        dsl.fetchOne(
            "SELECT id FROM game_instances WHERE tenant_id = ?"
                + " AND run_owned_start_request_id = ? FOR UPDATE",
            input.expectedCatalog().gameSessionTenantId(),
            requestId);
    if (orphan != null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Original StartSession request has a retained Game Instance without its exact canonical launch association");
    }
  }

  private long insertStartingGameInstance(
      PreparedLaunch input, UUID gameInstanceUuid, long startingRowVersion) {
    var descriptor = input.binding().descriptor();
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO game_instances"
                + " (tenant_id, runtime_version, owner_account_id,"
                + " owner_account_uuid, status, row_version, game_instance_uuid, game_template_id,"
                + " launch_descriptor_id, version_id, release_bundle_id, generation_config_revision,"
                + " version_state_epoch, run_owned_start_request_id,"
                + " run_owned_start_request_digest, run_owned_start_published_release_bundle_ref)"
                + " VALUES (?, ?, NULL, ?, 'STARTING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " RETURNING id",
            input.expectedCatalog().gameSessionTenantId(),
            Long.toString(descriptor.versionId()),
            input.originalTuple().preAuthorizationTuple().action().target().ownerAccountId(),
            startingRowVersion,
            gameInstanceUuid,
            descriptor.gameTemplateId(),
            descriptor.launchDescriptorId(),
            descriptor.versionId(),
            descriptor.releaseBundleId(),
            descriptor.generationConfigRevision(),
            descriptor.versionStateEpoch(),
            input.originalTuple().controlPlaneRequestId(),
            input.originalTuple().mutationDigest(),
            descriptor.publishedReleaseBundleRef());
    return required(inserted, "id", Long.class);
  }

  private void insertLaunchAssociation(
      PreparedLaunch input,
      CanonicalRealmCatalog catalog,
      long gameInstanceId,
      UUID gameInstanceUuid,
      long startingRowVersion) {
    FreshTenantCreationEvidence fresh = catalog.associationSourceEvidence();
    var descriptor = input.binding().descriptor();
    String bindingJson = encodeBinding(input.binding());
    requireOne(
        dsl.execute(
            "INSERT INTO game_session_canonical_instance_launch"
                + " (target_namespace, control_plane_request_id, game_session_tenant_id,"
                + " canonical_tenant_id, tenant_association_operation_id, tenant_association_request_id,"
                + " tenant_source_schema_version, tenant_source_target_namespace, tenant_source_request_id,"
                + " tenant_source_canonical_tenant_id, tenant_source_game_row_id,"
                + " tenant_source_game_tenant_key, tenant_source_provenance_kind, tenant_association_kind,"
                + " game_instance_id, game_instance_uuid, canonical_realm_id, world_slug,"
                + " playable_state_namespace_id, playable_state_scope, public_production,"
                + " captured_starting_row_version, game_template_id, launch_descriptor_id, version_id,"
                + " release_bundle_id, generation_config_revision, version_state_epoch,"
                + " complete_launch_binding_evidence, original_start_session_tuple,"
                + " catalog_creation_request_id, catalog_revision, catalog_source_intake_operation_id,"
                + " catalog_source_intake_request_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FRESH_SOURCE_BOUND',"
                + " ?, ?, ?, ?, ?, 'SHARED', TRUE, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)",
            workloadNamespace,
            input.originalTuple().controlPlaneRequestId(),
            catalog.gameSessionTenantId(),
            catalog.canonicalTenantId(),
            catalog.tenantAssociationOperationId(),
            fresh.creationRequestId(),
            fresh.schemaVersion(),
            fresh.targetNamespace(),
            fresh.creationRequestId(),
            fresh.canonicalTenantId(),
            fresh.sourceGameRowId(),
            fresh.sourceGameTenantKey(),
            fresh.provenanceKind(),
            gameInstanceId,
            gameInstanceUuid,
            catalog.realmId(),
            catalog.worldSlug(),
            catalog.playableStateNamespaceId(),
            startingRowVersion,
            descriptor.gameTemplateId(),
            descriptor.launchDescriptorId(),
            descriptor.versionId(),
            descriptor.releaseBundleId(),
            descriptor.generationConfigRevision(),
            descriptor.versionStateEpoch(),
            bindingJson,
            input.originalTuple().canonicalBytes(),
            catalog.catalogRequestId(),
            catalog.catalogRevision(),
            catalog.intakeOperationId(),
            catalog.intakeRequestId()),
        "canonical instance launch association");
  }

  private PreparedLaunch prepareInput(
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      CompleteLaunchBindingEvidence completeLaunchBindingEvidence,
      CanonicalRealmCatalog expectedCatalog) {
    Objects.requireNonNull(originalTuple, "originalTuple");
    Objects.requireNonNull(completeLaunchBindingEvidence, "completeLaunchBindingEvidence");
    Objects.requireNonNull(expectedCatalog, "expectedCatalog");
    final StartSessionPostAuthorizationExecutionTuple decoded;
    try {
      decoded = StartSessionPostAuthorizationExecutionTuple.decode(originalTuple.canonicalBytes());
      if (!Arrays.equals(decoded.canonicalBytes(), originalTuple.canonicalBytes())) {
        throw new IllegalArgumentException(
            "Original StartSession tuple is not exact canonical evidence");
      }
      completeLaunchBindingEvidence.descriptor().requireValid();
      completeLaunchBindingEvidence
          .releaseAttestation()
          .requireValid(completeLaunchBindingEvidence.descriptor());
      validateTupleBindingCatalogAndDescriptor(
          decoded, completeLaunchBindingEvidence, expectedCatalog);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Original tuple, complete descriptor/release pair, or source-backed catalog is invalid",
          invalid);
    }
    if (!workloadNamespace.equals(expectedCatalog.targetNamespace())) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Canonical launch catalog belongs to another Game Session namespace");
    }
    return new PreparedLaunch(decoded, completeLaunchBindingEvidence, expectedCatalog);
  }

  private void validateTupleBindingCatalogAndDescriptor(
      StartSessionPostAuthorizationExecutionTuple tuple,
      CompleteLaunchBindingEvidence binding,
      CanonicalRealmCatalog catalog) {
    var descriptor = binding.descriptor();
    var release = binding.releaseAttestation();
    var action = tuple.preAuthorizationTuple().action();
    AuthoredWorldSourceEvidence source = catalog.sourceEvidence();
    if (!"human_operator".equals(tuple.issuanceKind())
        || !"StartSession".equals(tuple.preAuthorizationTuple().actionFamily())
        || !"game-session-service".equals(tuple.preAuthorizationTuple().targetOwner())
        || !workloadNamespace.equals(
            tuple.preAuthorizationTuple().action().scope().targetNamespace())
        || !catalog
            .targetNamespace()
            .equals(tuple.preAuthorizationTuple().action().scope().targetNamespace())
        || !catalog.canonicalTenantId().equals(action.scope().tenantId())
        || !tuple.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
        || !catalog.targetNamespace().equals(descriptor.targetNamespace())
        || !catalog.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !catalog.worldSlug().equals(descriptor.worldSlug())
        || !source.operationId().equals(descriptor.authoredWorldSourceOperationId())
        || !source.evidenceDigest().equals(descriptor.authoredWorldSourceEvidenceDigest())
        || action.target().gameTemplateId() != descriptor.gameTemplateId()
        || !catalog.canonicalTenantId().equals(release.canonicalTenantId())
        || !catalog.targetNamespace().equals(release.targetNamespace())
        || !catalog.worldSlug().equals(release.worldSlug())
        || !source.operationId().equals(release.authoredWorldSourceOperationId())
        || !source.evidenceDigest().equals(release.authoredWorldSourceEvidenceDigest())
        || !descriptor.publishedReleaseBundleRef().equals(release.publishedReleaseBundleRef())
        || !catalog.canonicalTenantId().equals(catalog.sourceEvidence().canonicalTenantId())
        || !catalog.targetNamespace().equals(catalog.sourceEvidence().targetNamespace())
        || catalog.catalogRevision() <= 0L
        || catalog.gameSessionTenantId() <= 0L) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Original StartSession tuple, selected release pair, and complete canonical catalog identity disagree");
    }
  }

  private boolean matchesInput(CanonicalInstanceLaunch launch, PreparedLaunch input) {
    return launch.targetNamespace().equals(workloadNamespace)
        && launch.controlPlaneRequestId().equals(input.originalTuple().controlPlaneRequestId())
        && launch.canonicalTenantId().equals(input.expectedCatalog().canonicalTenantId())
        && launch.gameSessionTenantId() == input.expectedCatalog().gameSessionTenantId()
        && Arrays.equals(launch.originalStartSessionTuple(), input.originalTuple().canonicalBytes())
        && launch.launchBindingEvidence().equals(input.binding())
        && sameCatalog(launch.canonicalRealmCatalog(), input.expectedCatalog());
  }

  private static boolean sameCatalog(CanonicalRealmCatalog left, CanonicalRealmCatalog right) {
    return left.targetNamespace().equals(right.targetNamespace())
        && left.canonicalTenantId().equals(right.canonicalTenantId())
        && left.gameSessionTenantId() == right.gameSessionTenantId()
        && left.tenantAssociationOperationId().equals(right.tenantAssociationOperationId())
        && left.associationSourceEvidence().equals(right.associationSourceEvidence())
        && left.sourceEvidence().equals(right.sourceEvidence())
        && left.intakeRequestId().equals(right.intakeRequestId())
        && left.intakeOperationId().equals(right.intakeOperationId())
        && left.intakeRequestDigest().equals(right.intakeRequestDigest())
        && left.intakeReceiptDigest().equals(right.intakeReceiptDigest())
        && left.catalogRequestId().equals(right.catalogRequestId())
        && left.catalogRequestDigest().equals(right.catalogRequestDigest())
        && left.catalogReceiptDigest().equals(right.catalogReceiptDigest())
        && left.realmId().equals(right.realmId())
        && left.playableStateNamespaceId().equals(right.playableStateNamespaceId())
        && left.tenantSlug().equals(right.tenantSlug())
        && left.worldSlug().equals(right.worldSlug())
        && left.realmSlug().equals(right.realmSlug())
        && left.realmDisplayName().equals(right.realmDisplayName())
        && left.sourceWorldDisplayName().equals(right.sourceWorldDisplayName())
        && left.entryPolicy().equals(right.entryPolicy())
        && left.catalogRevision() == right.catalogRevision()
        && MessageDigest.isEqual(
            left.completePolicySetEvidence(), right.completePolicySetEvidence())
        && MessageDigest.isEqual(
            RealmPolicyPublishedEvidenceCodec.encode(left.publishedSet()),
            RealmPolicyPublishedEvidenceCodec.encode(right.publishedSet()));
  }

  private static void requireSameCatalog(
      CanonicalRealmCatalog expected, CanonicalRealmCatalog actual) {
    if (!sameCatalog(expected, actual)) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Exact pre-read canonical catalog evidence changed before launch storage");
    }
  }

  private static void requireRequestMatchesLaunch(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request,
      CanonicalInstanceLaunch launch) {
    var descriptor = launch.launchBindingEvidence().descriptor();
    var release = launch.launchBindingEvidence().releaseAttestation();
    if (!request.targetNamespace().equals(launch.targetNamespace())
        || !request.canonicalTenantId().equals(launch.canonicalTenantId())
        || !request.worldSlug().equals(launch.canonicalRealmCatalog().worldSlug())
        || !request.gameInstanceUuid().equals(launch.gameInstanceUuid())
        || !request.controlPlaneRequestId().equals(launch.controlPlaneRequestId())
        || !request.launchDescriptorId().equals(descriptor.launchDescriptorId())
        || !request.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !request.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || !request.expectedReleaseAttestationEvidenceDigest().equals(release.evidenceDigest())) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Exact canonical launch read selector differs from the retained owner evidence");
    }
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request independentReadSelector(
      PreparedLaunch input, UUID gameInstanceUuid) {
    UUID requestId;
    do {
      requestId = newNonNilUuid();
    } while (requestId.equals(input.binding().descriptor().authoredWorldSourceOperationId())
        || requestId.toString().equals(input.originalTuple().controlPlaneRequestId()));
    var descriptor = input.binding().descriptor();
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        requestId,
        input.expectedCatalog().targetNamespace(),
        input.expectedCatalog().canonicalTenantId(),
        descriptor.worldSlug(),
        gameInstanceUuid,
        descriptor.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        input.binding().releaseAttestation().evidenceDigest());
  }

  private static CompleteLaunchBindingEvidence decodeBinding(String json) {
    try {
      CompleteLaunchBindingEvidence evidence =
          JSON.readValue(json, CompleteLaunchBindingEvidence.class);
      evidence.descriptor().requireValid();
      evidence.releaseAttestation().requireValid(evidence.descriptor());
      return evidence;
    } catch (JsonProcessingException | IllegalArgumentException malformed) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored complete descriptor and release-attestation pair is malformed", malformed);
    }
  }

  private static String encodeBinding(CompleteLaunchBindingEvidence evidence) {
    try {
      evidence.descriptor().requireValid();
      evidence.releaseAttestation().requireValid(evidence.descriptor());
      return JSON.writeValueAsString(evidence);
    } catch (JsonProcessingException | IllegalArgumentException invalid) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Complete descriptor and release-attestation pair cannot be retained", invalid);
    }
  }

  private static StartSessionPostAuthorizationExecutionTuple decodeTuple(byte[] bytes) {
    try {
      StartSessionPostAuthorizationExecutionTuple tuple =
          StartSessionPostAuthorizationExecutionTuple.decode(bytes);
      if (!Arrays.equals(bytes, tuple.canonicalBytes())) {
        throw new IllegalArgumentException("Stored original StartSession tuple is not exact");
      }
      return tuple;
    } catch (IllegalArgumentException malformed) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored original StartSession post-authorization tuple is missing or malformed",
          malformed);
    }
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
      parseStatus(String value) {
    try {
      return CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.valueOf(
          value);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored Game Instance has an unsupported current lifecycle status", unknown);
    }
  }

  private static UUID newNonNilUuid() {
    UUID value = UUID.randomUUID();
    if (NIL_UUID.equals(value)) {
      throw new IllegalStateException("Game Session UUID allocator returned nil");
    }
    return value;
  }

  private static void requireNoActiveOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical launch owner operation requires its own independent transaction");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Game Session canonical launch storage access requires an active owner transaction");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    if (row == null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Game Session canonical launch storage returned no row");
    }
    T value = row.get(field, type);
    if (value == null) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Stored canonical launch is missing required evidence: " + field);
    }
    return value;
  }

  private static <T> T nullable(Record row, String field, Class<T> type) {
    return row == null ? null : row.get(field, type);
  }

  private static void requireOne(int affectedRows, String label) {
    if (affectedRows != 1) {
      throw new InvalidCanonicalInstanceLaunchException(
          "Game Session " + label + " insert did not affect exactly one row");
    }
  }

  private record PreparedLaunch(
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      CompleteLaunchBindingEvidence binding,
      CanonicalRealmCatalog expectedCatalog) {}

  /** Complete immutable launch association, its live GS row, and the exact catalog snapshot. */
  public record CanonicalInstanceLaunch(
      String targetNamespace,
      String controlPlaneRequestId,
      long gameSessionTenantId,
      UUID canonicalTenantId,
      long gameInstanceId,
      UUID gameInstanceUuid,
      UUID canonicalRealmId,
      UUID playableStateNamespaceId,
      RealmEntryPolicy.StateScope playableStateScope,
      boolean publicProduction,
      CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
          currentGameInstanceStatus,
      long currentRowVersion,
      long capturedStartingRowVersion,
      UUID catalogCreationRequestId,
      long catalogRevision,
      UUID catalogSourceIntakeOperationId,
      UUID catalogSourceIntakeRequestId,
      CompleteLaunchBindingEvidence launchBindingEvidence,
      byte[] originalStartSessionTuple,
      CanonicalRealmCatalog canonicalRealmCatalog) {
    public CanonicalInstanceLaunch {
      Objects.requireNonNull(targetNamespace, "targetNamespace");
      Objects.requireNonNull(controlPlaneRequestId, "controlPlaneRequestId");
      requirePositive(gameSessionTenantId, "gameSessionTenantId");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requirePositive(gameInstanceId, "gameInstanceId");
      requireNonNil(gameInstanceUuid, "gameInstanceUuid");
      requireNonNil(canonicalRealmId, "canonicalRealmId");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(playableStateScope, "playableStateScope");
      if (playableStateScope != RealmEntryPolicy.StateScope.SHARED || !publicProduction) {
        throw new IllegalArgumentException(
            "Canonical launch requires public-production SHARED scope");
      }
      Objects.requireNonNull(currentGameInstanceStatus, "currentGameInstanceStatus");
      if (currentRowVersion < 0L || capturedStartingRowVersion < 0L) {
        throw new IllegalArgumentException("Canonical launch row versions must not be negative");
      }
      requireNonNil(catalogCreationRequestId, "catalogCreationRequestId");
      requirePositive(catalogRevision, "catalogRevision");
      requireNonNil(catalogSourceIntakeOperationId, "catalogSourceIntakeOperationId");
      requireNonNil(catalogSourceIntakeRequestId, "catalogSourceIntakeRequestId");
      Objects.requireNonNull(launchBindingEvidence, "launchBindingEvidence");
      launchBindingEvidence.descriptor().requireValid();
      launchBindingEvidence.releaseAttestation().requireValid(launchBindingEvidence.descriptor());
      originalStartSessionTuple =
          Objects.requireNonNull(originalStartSessionTuple, "originalStartSessionTuple").clone();
      if (originalStartSessionTuple.length == 0
          || originalStartSessionTuple.length
              > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
        throw new IllegalArgumentException("Complete original StartSession tuple bytes required");
      }
      Objects.requireNonNull(canonicalRealmCatalog, "canonicalRealmCatalog");
      if (!canonicalTenantId.equals(canonicalRealmCatalog.canonicalTenantId())
          || !canonicalRealmId.equals(canonicalRealmCatalog.realmId())
          || catalogRevision != canonicalRealmCatalog.catalogRevision()
          || !catalogCreationRequestId.equals(canonicalRealmCatalog.catalogRequestId())
          || !catalogSourceIntakeOperationId.equals(canonicalRealmCatalog.intakeOperationId())
          || !catalogSourceIntakeRequestId.equals(canonicalRealmCatalog.intakeRequestId())) {
        throw new IllegalArgumentException(
            "Launch result differs from the actual complete catalog key");
      }
    }

    @Override
    public byte[] originalStartSessionTuple() {
      return originalStartSessionTuple.clone();
    }

    private static void requirePositive(long value, String field) {
      if (value <= 0L) {
        throw new IllegalArgumentException(field + " must be positive");
      }
    }

    private static void requireNonNil(UUID value, String field) {
      if (value == null || NIL_UUID.equals(value)) {
        throw new IllegalArgumentException(field + " must be a nonnil UUID");
      }
    }
  }

  public static final class InvalidCanonicalInstanceLaunchException extends IllegalStateException {
    public InvalidCanonicalInstanceLaunchException(String message) {
      super(message);
    }

    public InvalidCanonicalInstanceLaunchException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
