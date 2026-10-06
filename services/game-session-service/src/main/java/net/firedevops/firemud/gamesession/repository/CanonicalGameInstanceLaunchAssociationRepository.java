package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Explicit Game Session owner persistence for the exact launch-to-instance binding. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "The explicit owner boundary validates trusted collaborators and performs no I/O while"
            + " constructing; it is deliberately not a Spring bean.")
public final class CanonicalGameInstanceLaunchAssociationRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ASSOCIATION_TABLE = "game_session_canonical_instance_launch";

  private final DSLContext dsl;
  private final String workloadNamespace;

  public CanonicalGameInstanceLaunchAssociationRepository(
      DSLContext dsl, String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Captures an existing STARTING owner row and its exact launch binding in the same writable READ
   * COMMITTED transaction. This method does not allocate or create a runtime row.
   */
  public CanonicalGameInstanceLaunchAssociation capture(
      FreshGameSessionTenantAssociation tenantAssociation,
      long gameInstanceId,
      CompleteLaunchBindingEvidence launchBindingEvidence) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(tenantAssociation, "tenantAssociation");
    requirePositive(gameInstanceId, "gameInstanceId");
    ValidatedBinding binding = validateBinding(launchBindingEvidence);
    if (!workloadNamespace.equals(binding.descriptor().targetNamespace())) {
      throw new IllegalArgumentException("Launch binding targets another Game Session namespace");
    }

    RuntimeTenantIdentityEvidence source = tenantAssociation.sourceEvidence();
    requireFreshSourceIdentity(tenantAssociation, source);
    if (!binding.descriptor().canonicalTenantId().equals(source.canonicalTenantId())) {
      throw new IllegalArgumentException("Launch binding does not match its fresh tenant owner");
    }

    lockRequest(binding.descriptor().controlPlaneRequestId());
    FreshTenantOwnerMapping mapping = requireFreshTenantMapping(tenantAssociation);
    GameInstanceRow runtime =
        requireLockedRuntime(tenantAssociation.legacyGameSessionTenantId(), gameInstanceId);
    RealmOwnerProjection realm = requirePublicSharedRealm(binding.descriptor());
    CanonicalGameInstanceLaunchAssociation existing =
        findByRequest(binding.descriptor().controlPlaneRequestId(), true)
            .map(this::toAssociation)
            .orElse(null);
    if (existing != null) {
      requireExactRetry(
          existing, tenantAssociation, gameInstanceId, runtime, binding.evidence(), mapping, realm);
      return existing;
    }

    requireStartingRuntime(runtime, binding.evidence());
    String evidenceJson = serializeEvidence(binding.evidence());
    RuntimeTenantIdentityEvidence sourceEvidence = tenantAssociation.sourceEvidence();
    dsl.execute(
        "INSERT INTO "
            + ASSOCIATION_TABLE
            + " (target_namespace, control_plane_request_id, game_session_tenant_id, "
            + "canonical_tenant_id, tenant_association_operation_id, tenant_association_request_id, "
            + "tenant_source_schema_version, tenant_source_target_namespace, "
            + "tenant_source_request_id, tenant_source_canonical_tenant_id, "
            + "tenant_source_game_row_id, tenant_source_game_tenant_key, "
            + "tenant_source_provenance_kind, tenant_association_kind, game_instance_id, "
            + "game_instance_uuid, canonical_realm_id, world_slug, playable_state_namespace_id, playable_state_scope, "
            + "public_production, captured_starting_row_version, game_template_id, "
            + "launch_descriptor_id, version_id, release_bundle_id, generation_config_revision, "
            + "version_state_epoch, complete_launch_binding_evidence) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FRESH_SOURCE_BOUND', ?, ?, ?, ?, ?, "
            + "'SHARED', TRUE, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
        workloadNamespace,
        binding.descriptor().controlPlaneRequestId(),
        tenantAssociation.legacyGameSessionTenantId(),
        sourceEvidence.canonicalTenantId(),
        mapping.operationId(),
        sourceEvidence.requestId(),
        sourceEvidence.schemaVersion(),
        sourceEvidence.targetNamespace(),
        sourceEvidence.requestId(),
        sourceEvidence.canonicalTenantId(),
        sourceEvidence.sourceGameRowId(),
        sourceEvidence.sourceGameTenantKey(),
        sourceEvidence.provenanceKind(),
        gameInstanceId,
        runtime.gameInstanceUuid(),
        realm.realmId(),
        binding.descriptor().worldSlug(),
        realm.playableStateNamespaceId(),
        runtime.rowVersion(),
        binding.descriptor().gameTemplateId(),
        binding.descriptor().launchDescriptorId(),
        binding.descriptor().versionId(),
        binding.descriptor().releaseBundleId(),
        binding.descriptor().generationConfigRevision(),
        binding.descriptor().versionStateEpoch(),
        evidenceJson);

    CanonicalGameInstanceLaunchAssociation inserted =
        findByRequest(binding.descriptor().controlPlaneRequestId(), true)
            .map(this::toAssociation)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Canonical launch association insert readback is missing"));
    requireExactRetry(
        inserted, tenantAssociation, gameInstanceId, runtime, binding.evidence(), mapping, realm);
    return inserted;
  }

  /** Serializes a STARTING-row producer on the exact namespace/request key used by capture. */
  public void lockRequestForStartingGameInstance(String controlPlaneRequestId) {
    requireWritableReadCommittedOwnerTransaction();
    requireRequestId(controlPlaneRequestId);
    lockRequest(controlPlaneRequestId);
  }

  /**
   * Reads one committed immutable owner association plus the transient current GameInstance row.
   */
  public Optional<CanonicalGameInstanceLaunchAssociation> read(String controlPlaneRequestId) {
    requireOutsideTransaction();
    requireRequestId(controlPlaneRequestId);
    return findByRequest(controlPlaneRequestId, false).map(this::toAssociation);
  }

  private void lockRequest(String controlPlaneRequestId) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 0))",
        workloadNamespace,
        controlPlaneRequestId);
  }

  private GameInstanceRow requireLockedRuntime(long tenantId, long gameInstanceId) {
    Record row =
        dsl.fetchOne(
            "SELECT id, tenant_id, game_instance_uuid, status, row_version, game_template_id, "
                + "launch_descriptor_id, version_id, release_bundle_id, generation_config_revision, "
                + "version_state_epoch, run_owned_start_request_id, "
                + "run_owned_start_published_release_bundle_ref FROM game_instances "
                + "WHERE tenant_id = ? AND id = ? FOR UPDATE",
            tenantId,
            gameInstanceId);
    if (row == null) {
      throw new IllegalStateException("Game Session owner instance row is missing in tenant scope");
    }
    return toRuntime(row, "status", "row_version");
  }

  private RealmOwnerProjection requirePublicSharedRealm(
      net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence descriptor) {
    Record row =
        dsl.fetchOne(
            "SELECT realm_id, playable_state_namespace_id, state_scope, public_production, visible "
                + "FROM game_session_canonical_realm_catalog WHERE target_namespace = ? "
                + "AND canonical_tenant_id = ? AND world_slug = ? AND public_production = TRUE "
                + "AND visible = TRUE",
            workloadNamespace,
            descriptor.canonicalTenantId(),
            descriptor.worldSlug());
    if (row == null
        || !Boolean.TRUE.equals(row.get("public_production", Boolean.class))
        || !Boolean.TRUE.equals(row.get("visible", Boolean.class))
        || !RealmEntryPolicy.StateScope.SHARED
            .name()
            .equals(row.get("state_scope", String.class))) {
      throw new IllegalStateException(
          "Canonical launch association requires the owner's visible public SHARED realm");
    }
    UUID namespaceId = row.get("playable_state_namespace_id", UUID.class);
    UUID realmId = row.get("realm_id", UUID.class);
    requireNonNil(namespaceId, "playableStateNamespaceId");
    requireNonNil(realmId, "canonicalRealmId");
    return new RealmOwnerProjection(realmId, namespaceId);
  }

  private FreshTenantOwnerMapping requireFreshTenantMapping(
      FreshGameSessionTenantAssociation expected) {
    RuntimeTenantIdentityEvidence source = expected.sourceEvidence();
    Record row =
        dsl.fetchOne(
            "SELECT association.association_operation_id, association.association_request_id, "
                + "association.source_schema_version, association.source_target_namespace, "
                + "association.source_request_id, association.source_canonical_tenant_id, "
                + "association.canonical_tenant_id, association.legacy_game_session_tenant_id, "
                + "association.source_game_row_id, association.source_game_tenant_key, "
                + "association.provenance_kind, association.association_kind, "
                + "claim.association_operation_id AS claim_operation_id, "
                + "claim.association_request_id AS claim_request_id, "
                + "claim.association_kind AS claim_kind, claim.reservation_kind AS claim_reservation_kind, "
                + "reservation.reservation_kind "
                + "FROM game_session_fresh_tenant_association association "
                + "JOIN game_session_tenant_canonical_claim claim ON "
                + "claim.target_namespace = association.target_namespace "
                + "AND claim.canonical_tenant_id = association.canonical_tenant_id "
                + "AND claim.legacy_game_session_tenant_id = association.legacy_game_session_tenant_id "
                + "AND claim.association_operation_id = association.association_operation_id "
                + "AND claim.association_kind = association.association_kind "
                + "JOIN game_session_tenant_scope_reservation reservation ON "
                + "reservation.game_session_tenant_id = association.legacy_game_session_tenant_id "
                + "WHERE association.target_namespace = ? AND association.association_operation_id = ?",
            workloadNamespace,
            expected.associationOperationId());
    if (row == null) {
      throw new IllegalStateException(
          "Persisted fresh Game Session tenant association is unavailable");
    }
    UUID operationId = row.get("association_operation_id", UUID.class);
    long localTenantId = row.get("legacy_game_session_tenant_id", Long.class);
    if (!expected.associationOperationId().equals(operationId)
        || localTenantId != expected.legacyGameSessionTenantId()
        || !source.requestId().equals(row.get("association_request_id", UUID.class))
        || !source.requestId().equals(row.get("source_request_id", UUID.class))
        || source.schemaVersion() != row.get("source_schema_version", Integer.class)
        || !source.targetNamespace().equals(row.get("source_target_namespace", String.class))
        || !source.canonicalTenantId().equals(row.get("source_canonical_tenant_id", UUID.class))
        || !source.canonicalTenantId().equals(row.get("canonical_tenant_id", UUID.class))
        || source.sourceGameRowId() != row.get("source_game_row_id", Long.class)
        || !source.sourceGameTenantKey().equals(row.get("source_game_tenant_key", String.class))
        || !source.provenanceKind().equals(row.get("provenance_kind", String.class))
        || !operationId.equals(row.get("claim_operation_id", UUID.class))
        || !source.requestId().equals(row.get("claim_request_id", UUID.class))
        || !"FRESH_SOURCE_BOUND".equals(row.get("association_kind", String.class))
        || !"FRESH_SOURCE_BOUND".equals(row.get("claim_kind", String.class))
        || !"FRESH_SOURCE_BOUND".equals(row.get("reservation_kind", String.class))
        || !"FRESH_SOURCE_BOUND".equals(row.get("claim_reservation_kind", String.class))) {
      throw new IllegalStateException(
          "Persisted fresh tenant source identity does not match its owner");
    }
    return new FreshTenantOwnerMapping(operationId);
  }

  private Optional<Record> findByRequest(String controlPlaneRequestId, boolean forUpdate) {
    String sql =
        "SELECT association.*, current.status AS current_status, "
            + "current.row_version AS current_row_version, current.game_instance_uuid AS "
            + "current_game_instance_uuid, current.game_template_id AS current_game_template_id, "
            + "current.launch_descriptor_id AS current_launch_descriptor_id, "
            + "current.version_id AS current_version_id, current.release_bundle_id AS "
            + "current_release_bundle_id, current.generation_config_revision AS "
            + "current_generation_config_revision, current.version_state_epoch AS "
            + "current_version_state_epoch, current.run_owned_start_request_id AS "
            + "current_run_owned_start_request_id, current.run_owned_start_published_release_bundle_ref "
            + "AS current_run_owned_start_published_release_bundle_ref "
            + "FROM "
            + ASSOCIATION_TABLE
            + " association JOIN game_instances current "
            + "ON current.id = association.game_instance_id "
            + "AND current.tenant_id = association.game_session_tenant_id "
            + "JOIN game_session_canonical_realm_catalog realm "
            + "ON realm.target_namespace = association.target_namespace "
            + "AND realm.realm_id = association.canonical_realm_id "
            + "AND realm.canonical_tenant_id = association.canonical_tenant_id "
            + "AND realm.world_slug = association.world_slug "
            + "AND realm.playable_state_namespace_id = association.playable_state_namespace_id "
            + "AND realm.visible = TRUE AND realm.public_production = TRUE "
            + "AND realm.state_scope = 'SHARED' "
            + "WHERE association.target_namespace = ? AND association.control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE OF association, current" : "");
    return Optional.ofNullable(dsl.fetchOne(sql, workloadNamespace, controlPlaneRequestId));
  }

  private CanonicalGameInstanceLaunchAssociation toAssociation(Record row) {
    try {
      CompleteLaunchBindingEvidence evidence =
          JSON.readValue(
              row.get("complete_launch_binding_evidence", JSONB.class).data(),
              CompleteLaunchBindingEvidence.class);
      ValidatedBinding binding = validateBinding(evidence);
      UUID canonicalTenantId = row.get("canonical_tenant_id", UUID.class);
      UUID associationOperationId = row.get("tenant_association_operation_id", UUID.class);
      UUID gameInstanceUuid = row.get("game_instance_uuid", UUID.class);
      UUID namespaceId = row.get("playable_state_namespace_id", UUID.class);
      Boolean publicProduction = row.get("public_production", Boolean.class);
      if (!workloadNamespace.equals(row.get("target_namespace", String.class))
          || !"FRESH_SOURCE_BOUND".equals(row.get("tenant_association_kind", String.class))
          || !binding.descriptor().targetNamespace().equals(workloadNamespace)
          || !binding
              .descriptor()
              .controlPlaneRequestId()
              .equals(row.get("control_plane_request_id", String.class))
          || !canonicalTenantId.equals(binding.descriptor().canonicalTenantId())
          || !row.get("world_slug", String.class).equals(binding.descriptor().worldSlug())
          || !row.get("launch_descriptor_id", String.class)
              .equals(binding.descriptor().launchDescriptorId())
          || row.get("game_template_id", Long.class) != binding.descriptor().gameTemplateId()
          || row.get("version_id", Long.class) != binding.descriptor().versionId()
          || row.get("release_bundle_id", Long.class) != binding.descriptor().releaseBundleId()
          || !row.get("generation_config_revision", String.class)
              .equals(binding.descriptor().generationConfigRevision())
          || row.get("version_state_epoch", Long.class) != binding.descriptor().versionStateEpoch()
          || !Boolean.TRUE.equals(publicProduction)) {
        throw new IllegalStateException(
            "Persisted launch association contradicts its typed binding");
      }
      RuntimeTenantIdentityEvidence source = sourceFromRow(row);
      FreshGameSessionTenantAssociation mapping =
          new FreshGameSessionTenantAssociation(
              associationOperationId, row.get("game_session_tenant_id", Long.class), source);
      requireFreshTenantMapping(mapping);
      GameInstanceRow current =
          new GameInstanceRow(
              row.get("game_instance_id", Long.class),
              row.get("game_session_tenant_id", Long.class),
              row.get("current_game_instance_uuid", UUID.class),
              row.get("current_status", String.class),
              row.get("current_row_version", Long.class),
              row.get("current_game_template_id", Long.class),
              row.get("current_launch_descriptor_id", String.class),
              row.get("current_version_id", Long.class),
              row.get("current_release_bundle_id", Long.class),
              row.get("current_generation_config_revision", String.class),
              row.get("current_version_state_epoch", Long.class),
              row.get("current_run_owned_start_request_id", String.class),
              row.get("current_run_owned_start_published_release_bundle_ref", String.class));
      requireRuntimeBinding(current, evidence);
      if (!gameInstanceUuid.equals(current.gameInstanceUuid())
          || current.rowVersion() < row.get("captured_starting_row_version", Long.class)
          || !RealmEntryPolicy.StateScope.SHARED
              .name()
              .equals(row.get("playable_state_scope", String.class))) {
        throw new IllegalStateException(
            "Current Game Session row contradicts its launch association");
      }
      return new CanonicalGameInstanceLaunchAssociation(
          workloadNamespace,
          row.get("game_session_tenant_id", Long.class),
          associationOperationId,
          canonicalTenantId,
          gameInstanceUuid,
          row.get("world_slug", String.class),
          namespaceId,
          RealmEntryPolicy.StateScope.SHARED,
          true,
          binding.descriptor().controlPlaneRequestId(),
          binding.descriptor().launchDescriptorId(),
          row.get("captured_starting_row_version", Long.class),
          evidence,
          CurrentGameInstanceStatus.valueOf(current.status()),
          current.rowVersion());
    } catch (RuntimeException exception) {
      if (exception instanceof IllegalStateException illegalState) {
        throw illegalState;
      }
      throw new IllegalStateException(
          "Persisted canonical launch association is malformed", exception);
    }
  }

  private static RuntimeTenantIdentityEvidence sourceFromRow(Record row) {
    return new RuntimeTenantIdentityEvidence(
        row.get("tenant_source_schema_version", Integer.class),
        row.get("tenant_source_target_namespace", String.class),
        row.get("tenant_source_request_id", UUID.class),
        row.get("tenant_source_canonical_tenant_id", UUID.class),
        row.get("tenant_source_game_row_id", Long.class),
        row.get("tenant_source_game_tenant_key", String.class),
        row.get("tenant_source_provenance_kind", String.class));
  }

  private void requireExactRetry(
      CanonicalGameInstanceLaunchAssociation stored,
      FreshGameSessionTenantAssociation expectedTenant,
      long expectedGameInstanceId,
      GameInstanceRow current,
      CompleteLaunchBindingEvidence expectedEvidence,
      FreshTenantOwnerMapping mapping,
      RealmOwnerProjection realm) {
    if (stored.gameSessionTenantId() != expectedTenant.legacyGameSessionTenantId()
        || !stored.tenantAssociationOperationId().equals(mapping.operationId())
        || !stored.canonicalTenantId().equals(expectedTenant.sourceEvidence().canonicalTenantId())
        || !stored.playableStateNamespaceId().equals(realm.playableStateNamespaceId())
        || !stored.launchBindingEvidence().equals(expectedEvidence)
        || !stored.gameInstanceUuid().equals(current.gameInstanceUuid())
        || stored.currentRowVersion() > current.rowVersion()
        || stored.capturedStartingRowVersion() > current.rowVersion()) {
      throw new CanonicalGameInstanceLaunchAssociationConflictException(
          "Launch association request was reused with a changed tenant, instance, or source binding");
    }
    Record row =
        dsl.fetchOne(
            "SELECT game_instance_id FROM "
                + ASSOCIATION_TABLE
                + " WHERE target_namespace = ? AND control_plane_request_id = ?",
            workloadNamespace,
            stored.controlPlaneRequestId());
    if (row == null || row.get("game_instance_id", Long.class) != expectedGameInstanceId) {
      throw new CanonicalGameInstanceLaunchAssociationConflictException(
          "Launch association request was reused for another Game Session instance");
    }
    requireRuntimeBinding(current, expectedEvidence);
  }

  private static void requireStartingRuntime(
      GameInstanceRow runtime, CompleteLaunchBindingEvidence evidence) {
    requireRuntimeBinding(runtime, evidence);
    if (!"STARTING".equals(runtime.status())
        || !evidence.descriptor().controlPlaneRequestId().equals(runtime.runOwnedStartRequestId())
        || !evidence
            .descriptor()
            .publishedReleaseBundleRef()
            .equals(runtime.runOwnedStartPublishedReleaseBundleRef())) {
      throw new IllegalStateException(
          "Launch association capture requires the exact persisted run-owned STARTING instance");
    }
  }

  private static void requireRuntimeBinding(
      GameInstanceRow runtime, CompleteLaunchBindingEvidence evidence) {
    var descriptor = evidence.descriptor();
    if (runtime.id() <= 0
        || runtime.tenantId() <= 0
        || runtime.gameInstanceUuid() == null
        || NIL_UUID.equals(runtime.gameInstanceUuid())
        || runtime.rowVersion() < 0
        || runtime.gameTemplateId() == null
        || runtime.gameTemplateId() != descriptor.gameTemplateId()
        || !Objects.equals(runtime.launchDescriptorId(), descriptor.launchDescriptorId())
        || runtime.versionId() == null
        || runtime.versionId() != descriptor.versionId()
        || runtime.releaseBundleId() == null
        || runtime.releaseBundleId() != descriptor.releaseBundleId()
        || runtime.versionStateEpoch() == null
        || runtime.versionStateEpoch() != descriptor.versionStateEpoch()
        || !Objects.equals(
            runtime.generationConfigRevision(), descriptor.generationConfigRevision())
        || !Objects.equals(runtime.runOwnedStartRequestId(), descriptor.controlPlaneRequestId())
        || !Objects.equals(
            runtime.runOwnedStartPublishedReleaseBundleRef(),
            descriptor.publishedReleaseBundleRef())) {
      throw new IllegalStateException(
          "Game Session instance does not match the complete launch binding");
    }
    try {
      CurrentGameInstanceStatus.valueOf(runtime.status());
    } catch (RuntimeException invalidStatus) {
      throw new IllegalStateException("Game Session instance status is unavailable", invalidStatus);
    }
  }

  private static GameInstanceRow toRuntime(
      Record row, String statusColumn, String rowVersionColumn) {
    return toRuntime(
        row,
        statusColumn,
        rowVersionColumn,
        "game_instance_uuid",
        "game_template_id",
        "launch_descriptor_id",
        "version_id",
        "release_bundle_id",
        "generation_config_revision",
        "version_state_epoch",
        "run_owned_start_request_id",
        "run_owned_start_published_release_bundle_ref");
  }

  private static GameInstanceRow toRuntime(
      Record row,
      String statusColumn,
      String rowVersionColumn,
      String uuidColumn,
      String templateColumn,
      String descriptorColumn,
      String versionColumn,
      String bundleColumn,
      String configColumn,
      String epochColumn,
      String requestColumn,
      String publishedRefColumn) {
    return new GameInstanceRow(
        row.get("id", Long.class),
        row.get("tenant_id", Long.class),
        row.get(uuidColumn, UUID.class),
        row.get(statusColumn, String.class),
        row.get(rowVersionColumn, Long.class),
        row.get(templateColumn, Long.class),
        row.get(descriptorColumn, String.class),
        row.get(versionColumn, Long.class),
        row.get(bundleColumn, Long.class),
        row.get(configColumn, String.class),
        row.get(epochColumn, Long.class),
        row.get(requestColumn, String.class),
        row.get(publishedRefColumn, String.class));
  }

  private static ValidatedBinding validateBinding(CompleteLaunchBindingEvidence evidence) {
    Objects.requireNonNull(evidence, "launchBindingEvidence");
    var descriptor = evidence.descriptor();
    descriptor.requireValid();
    evidence.releaseAttestation().requireValid(descriptor);
    requireRequestId(descriptor.controlPlaneRequestId());
    return new ValidatedBinding(evidence);
  }

  private void requireFreshSourceIdentity(
      FreshGameSessionTenantAssociation association, RuntimeTenantIdentityEvidence evidence) {
    if (evidence.schemaVersion() != 1
        || !workloadNamespace.equals(evidence.targetNamespace())
        || evidence.requestId() == null
        || NIL_UUID.equals(evidence.requestId())
        || evidence.canonicalTenantId() == null
        || NIL_UUID.equals(evidence.canonicalTenantId())
        || association.legacyGameSessionTenantId() <= 0
        || !"NEW_GAME_ROW".equals(evidence.provenanceKind())) {
      throw new IllegalArgumentException(
          "Only exact fresh NEW_GAME_ROW owner identity is supported");
    }
  }

  private static String serializeEvidence(CompleteLaunchBindingEvidence evidence) {
    try {
      return JSON.writeValueAsString(evidence);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Failed to persist complete launch binding evidence", exception);
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical launch association capture requires a writable owner transaction");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equals(settings.get("transaction_isolation", String.class))
        || !"off".equals(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical launch association capture requires writable READ COMMITTED isolation");
    }
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical launch association read requires a committed owner read");
    }
  }

  private static void requireRequestId(String value) {
    Objects.requireNonNull(value, "controlPlaneRequestId");
    if (value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("controlPlaneRequestId must contain 1 to 128 characters");
    }
  }

  private static void requirePositive(long value, String label) {
    if (value <= 0) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private record ValidatedBinding(CompleteLaunchBindingEvidence evidence) {
    private net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence
        descriptor() {
      return evidence.descriptor();
    }
  }

  private record FreshTenantOwnerMapping(UUID operationId) {}

  private record RealmOwnerProjection(UUID realmId, UUID playableStateNamespaceId) {}

  private record GameInstanceRow(
      long id,
      long tenantId,
      UUID gameInstanceUuid,
      String status,
      long rowVersion,
      Long gameTemplateId,
      String launchDescriptorId,
      Long versionId,
      Long releaseBundleId,
      String generationConfigRevision,
      Long versionStateEpoch,
      String runOwnedStartRequestId,
      String runOwnedStartPublishedReleaseBundleRef) {}

  public static final class CanonicalGameInstanceLaunchAssociationConflictException
      extends RuntimeException {
    public CanonicalGameInstanceLaunchAssociationConflictException(String message) {
      super(message);
    }
  }
}
