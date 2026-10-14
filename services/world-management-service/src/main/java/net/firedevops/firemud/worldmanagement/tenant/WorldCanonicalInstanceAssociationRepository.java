package net.firedevops.firemud.worldmanagement.tenant;

import java.sql.Connection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationResponse;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * World-local storage and exact readback for a claimed canonical instance association.
 *
 * <p>This component has no public transport and does not authenticate Game Session. A positive
 * owner readback only returns retained correlation; JOIN, ACTIVE, and admission remain unavailable
 * until an authenticated same-namespace producer and complete prepare/materialization proof are
 * integrated by the lifecycle owner.
 */
public final class WorldCanonicalInstanceAssociationRepository {
  private static final Table<?> ASSOCIATION =
      DSL.table(DSL.name("world_canonical_instance_association"));
  private static final Table<?> WORLD_INSTANCE = DSL.table(DSL.name("world_instance"));
  private static final Table<?> COMPLETE_BINDING =
      DSL.table(DSL.name("world_complete_launch_binding"));
  private static final Table<?> SOURCE_INTAKE = DSL.table(DSL.name("world_authored_source_intake"));
  private static final Table<?> VERSION_IDENTITY =
      DSL.table(DSL.name("world_authored_version_identity"));
  private static final JsonMapper CLOSED_JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private static final Field<Short> SCHEMA_VERSION = field("schema_version", Short.class);
  private static final Field<UUID> CANONICAL_GAME_INSTANCE_ID =
      field("canonical_game_instance_id", UUID.class);
  private static final Field<String> CANONICAL_TARGET_NAMESPACE =
      field("canonical_target_namespace", String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID = field("canonical_tenant_id", UUID.class);
  private static final Field<String> CANONICAL_WORLD_SLUG =
      field("canonical_world_slug", String.class);
  private static final Field<UUID> PLAYABLE_STATE_NAMESPACE_ID =
      field("playable_state_namespace_id", UUID.class);
  private static final Field<String> PLAYABLE_STATE_SCOPE =
      field("playable_state_scope", String.class);
  private static final Field<Boolean> PUBLIC_PRODUCTION = field("public_production", Boolean.class);
  private static final Field<UUID> CANONICAL_REALM_ID = field("canonical_realm_id", UUID.class);
  private static final Field<Long> CATALOG_REVISION = field("catalog_revision", Long.class);
  private static final Field<UUID> PLAYTEST_LIFECYCLE_ID =
      field("playtest_lifecycle_id", UUID.class);
  private static final Field<Long> PLAYTEST_STATE_GENERATION =
      field("playtest_state_generation", Long.class);
  private static final Field<String> CONTROL_PLANE_REQUEST_ID =
      field("control_plane_request_id", String.class);
  private static final Field<Long> WORLD_INSTANCE_ID = field("world_instance_id", Long.class);
  private static final Field<UUID> LAUNCH_BINDING_OPERATION_ID =
      field("canonical_launch_binding_operation_id", UUID.class);
  private static final Field<UUID> VERSION_IDENTITY_OPERATION_ID =
      field("version_identity_operation_id", UUID.class);
  private static final Field<UUID> CANONICAL_VERSION_ID = field("canonical_version_id", UUID.class);
  private static final Field<Long> LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<Long> PRIVATE_GAME_INSTANCE_KEY =
      field("private_game_instance_key", Long.class);
  private static final Field<Long> GAME_TEMPLATE_ID = field("game_template_id", Long.class);
  private static final Field<String> LAUNCH_DESCRIPTOR_ID =
      field("launch_descriptor_id", String.class);
  private static final Field<Long> LOCAL_VERSION_KEY = field("local_version_key", Long.class);
  private static final Field<String> SCRIPT_PATCH_VERSION =
      field("script_patch_version", String.class);
  private static final Field<String> RUNTIME_FLAGS_JSON = field("runtime_flags_json", String.class);
  private static final Field<String> GENERATION_CONFIG_REVISION =
      field("generation_config_revision", String.class);
  private static final Field<Long> RELEASE_BUNDLE_ID = field("release_bundle_id", Long.class);
  private static final Field<String> PUBLISHED_RELEASE_BUNDLE_REF =
      field("published_release_bundle_ref", String.class);
  private static final Field<Long> VERSION_STATE_EPOCH = field("version_state_epoch", Long.class);
  private static final Field<String> REMAP_SET_ID = field("remap_set_id", String.class);
  private static final Field<UUID> INTAKE_OPERATION_ID = field("intake_operation_id", UUID.class);
  private static final Field<UUID> INTAKE_REQUEST_ID = field("intake_request_id", UUID.class);
  private static final Field<UUID> SOURCE_OPERATION_ID = field("source_operation_id", UUID.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> INTAKE_REQUEST_DIGEST =
      field("intake_request_digest", String.class);
  private static final Field<String> INTAKE_RECEIPT_DIGEST =
      field("intake_receipt_digest", String.class);
  private static final Field<String> DESCRIPTOR_REQUEST_DIGEST =
      field("descriptor_request_digest", String.class);
  private static final Field<String> DESCRIPTOR_RESULT_DIGEST =
      field("descriptor_result_digest", String.class);
  private static final Field<String> RELEASE_ATTESTATION_DIGEST =
      field("release_attestation_digest", String.class);
  private static final Field<String> CANONICAL_AUTHORITY_KIND =
      field("canonical_authority_kind", String.class);
  private static final Field<String> FIXTURE_PROVENANCE_DIGEST =
      field("fixture_provenance_digest", String.class);
  private static final Field<Long> GAME_SESSION_TENANT_ID =
      field("game_session_tenant_id", Long.class);
  private static final Field<Long> GAME_SESSION_GAME_INSTANCE_ID =
      field("game_session_game_instance_id", Long.class);
  private static final Field<Long> GAME_DESIGN_VERSION_ID =
      field("game_design_version_id", Long.class);
  private static final Field<String> PREPARATION_INPUT_DIGEST =
      field("preparation_input_digest", String.class);
  private static final Field<Long> PREPARING_LIFECYCLE_EPOCH =
      field("preparing_lifecycle_epoch", Long.class);
  private static final Field<byte[]> GAME_SESSION_ASSOCIATION_RESPONSE_BYTES =
      field("game_session_association_response_bytes", byte[].class);

  private static final Field<Long> INSTANCE_ID = field("id", Long.class);
  private static final Field<Long> INSTANCE_TENANT_ID = field("tenant_id", Long.class);
  private static final Field<Long> INSTANCE_GAME_INSTANCE_ID =
      field("game_instance_id", Long.class);
  private static final Field<Long> INSTANCE_GAME_TEMPLATE_ID =
      field("game_template_id", Long.class);
  private static final Field<String> INSTANCE_CONTROL_PLANE_REQUEST_ID =
      field("control_plane_request_id", String.class);
  private static final Field<Long> INSTANCE_LIFECYCLE_EPOCH = field("lifecycle_epoch", Long.class);
  private static final Field<String> INSTANCE_STATUS = field("status", String.class);
  private static final Field<String> INSTANCE_LAUNCH_DESCRIPTOR_ID =
      field("launch_descriptor_id", String.class);
  private static final Field<Long> INSTANCE_VERSION_ID = field("version_id", Long.class);
  private static final Field<String> INSTANCE_SCRIPT_PATCH_VERSION =
      field("script_patch_version", String.class);
  private static final Field<String> INSTANCE_RUNTIME_FLAGS_JSON =
      field("runtime_flags_json", String.class);
  private static final Field<String> INSTANCE_GENERATION_CONFIG_REVISION =
      field("generation_config_revision", String.class);
  private static final Field<Long> INSTANCE_RELEASE_BUNDLE_ID =
      field("release_bundle_id", Long.class);
  private static final Field<String> INSTANCE_PUBLISHED_RELEASE_BUNDLE_REF =
      field("published_release_bundle_ref", String.class);
  private static final Field<Long> INSTANCE_VERSION_STATE_EPOCH =
      field("version_state_epoch", Long.class);
  private static final Field<String> INSTANCE_REMAP_SET_ID = field("remap_set_id", String.class);
  private static final Field<UUID> INSTANCE_CANONICAL_GAME_INSTANCE_ID =
      field("canonical_game_instance_id", UUID.class);
  private static final Field<String> INSTANCE_CANONICAL_TARGET_NAMESPACE =
      field("canonical_target_namespace", String.class);
  private static final Field<UUID> INSTANCE_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<String> INSTANCE_CANONICAL_WORLD_SLUG =
      field("canonical_world_slug", String.class);
  private static final Field<UUID> INSTANCE_PLAYABLE_STATE_NAMESPACE_ID =
      field("playable_state_namespace_id", UUID.class);
  private static final Field<String> INSTANCE_PLAYABLE_STATE_SCOPE =
      field("playable_state_scope", String.class);
  private static final Field<Boolean> INSTANCE_PUBLIC_PRODUCTION =
      field("public_production", Boolean.class);
  private static final Field<UUID> INSTANCE_CANONICAL_REALM_ID =
      field("canonical_realm_id", UUID.class);
  private static final Field<Long> INSTANCE_CATALOG_REVISION =
      field("catalog_revision", Long.class);
  private static final Field<UUID> INSTANCE_PLAYTEST_LIFECYCLE_ID =
      field("playtest_lifecycle_id", UUID.class);
  private static final Field<Long> INSTANCE_PLAYTEST_STATE_GENERATION =
      field("playtest_state_generation", Long.class);
  private static final Field<UUID> INSTANCE_LAUNCH_BINDING_OPERATION_ID =
      field("canonical_launch_binding_operation_id", UUID.class);
  private static final Field<String> INSTANCE_CANONICAL_AUTHORITY_KIND =
      field("canonical_authority_kind", String.class);
  private static final Field<String> INSTANCE_FIXTURE_PROVENANCE_DIGEST =
      field("fixture_provenance_digest", String.class);
  private static final Field<Long> INSTANCE_GAME_SESSION_TENANT_ID =
      field("game_session_tenant_id", Long.class);
  private static final Field<Long> INSTANCE_GAME_SESSION_GAME_INSTANCE_ID =
      field("game_session_game_instance_id", Long.class);
  private static final Field<Long> INSTANCE_GAME_DESIGN_VERSION_ID =
      field("game_design_version_id", Long.class);
  private static final Field<String> INSTANCE_FIXTURE_BINDING_SHA256 =
      field("run_owned_fixture_binding_sha256", String.class);
  private static final Field<Long> INSTANCE_FIXTURE_PREPARING_EPOCH =
      field("run_owned_fixture_preparing_lifecycle_epoch", Long.class);

  private static final Field<UUID> BINDING_OPERATION_ID =
      DSL.field(DSL.name("binding_operation_id"), UUID.class);
  private static final Field<UUID> BINDING_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<UUID> BINDING_CANONICAL_VERSION_ID =
      field("canonical_version_id", UUID.class);
  private static final Field<String> BINDING_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<String> BINDING_WORLD_SLUG = field("world_slug", String.class);
  private static final Field<String> BINDING_CONTROL_PLANE_REQUEST_ID =
      field("control_plane_request_id", String.class);
  private static final Field<UUID> BINDING_INTAKE_OPERATION_ID =
      field("intake_operation_id", UUID.class);
  private static final Field<UUID> BINDING_INTAKE_REQUEST_ID =
      field("intake_request_id", UUID.class);
  private static final Field<Long> BINDING_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<UUID> BINDING_SOURCE_OPERATION_ID =
      field("source_operation_id", UUID.class);
  private static final Field<String> BINDING_SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> BINDING_INTAKE_RECEIPT_DIGEST =
      field("intake_receipt_digest", String.class);
  private static final Field<String> BINDING_DESCRIPTOR_REQUEST_DIGEST =
      field("descriptor_request_digest", String.class);
  private static final Field<String> BINDING_DESCRIPTOR_RESULT_DIGEST =
      field("descriptor_result_digest", String.class);
  private static final Field<String> BINDING_RELEASE_ATTESTATION_DIGEST =
      field("release_attestation_digest", String.class);
  private static final Field<String> BINDING_DESCRIPTOR_JSON =
      field("descriptor_json", String.class);
  private static final Field<String> BINDING_RELEASE_ATTESTATION_JSON =
      field("release_attestation_json", String.class);

  private static final Field<UUID> SOURCE_OPERATION_ID_KEY =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<Short> SOURCE_SCHEMA_VERSION = field("schema_version", Short.class);
  private static final Field<String> SOURCE_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<UUID> SOURCE_INTAKE_REQUEST_ID =
      field("intake_request_id", UUID.class);
  private static final Field<String> SOURCE_REQUEST_DIGEST = field("request_digest", String.class);
  private static final Field<Long> SOURCE_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<Short> SOURCE_EVIDENCE_SCHEMA_VERSION =
      field("source_schema_version", Short.class);
  private static final Field<UUID> SOURCE_REGISTRATION_REQUEST_ID =
      field("source_registration_request_id", UUID.class);
  private static final Field<UUID> SOURCE_SOURCE_OPERATION_ID =
      field("source_operation_id", UUID.class);
  private static final Field<String> SOURCE_SOURCE_REQUEST_DIGEST =
      field("source_request_digest", String.class);
  private static final Field<UUID> SOURCE_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<String> SOURCE_TENANT_SLUG = field("tenant_slug", String.class);
  private static final Field<String> SOURCE_WORLD_SLUG = field("world_slug", String.class);
  private static final Field<String> SOURCE_WORLD_DISPLAY_NAME =
      field("world_display_name", String.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID = field("source_game_row_id", Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      field("source_game_tenant_key", String.class);
  private static final Field<String> SOURCE_PROVENANCE_KIND =
      field("source_provenance_kind", String.class);
  private static final Field<String> SOURCE_SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> SOURCE_RECEIPT_DIGEST = field("receipt_digest", String.class);

  private static final Field<UUID> VERSION_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<String> VERSION_TARGET_NAMESPACE =
      field("target_namespace", String.class);
  private static final Field<UUID> VERSION_CANONICAL_TENANT_ID =
      field("canonical_tenant_id", UUID.class);
  private static final Field<String> VERSION_WORLD_SLUG = field("world_slug", String.class);
  private static final Field<UUID> VERSION_CANONICAL_VERSION_ID =
      field("canonical_version_id", UUID.class);
  private static final Field<Long> VERSION_GAME_DESIGN_VERSION_ID =
      field("game_design_version_id", Long.class);
  private static final Field<Long> VERSION_LOCAL_VERSION_KEY =
      field("local_version_key", Long.class);
  private static final Field<UUID> VERSION_INTAKE_OPERATION_ID =
      field("intake_operation_id", UUID.class);
  private static final Field<UUID> VERSION_INTAKE_REQUEST_ID =
      field("intake_request_id", UUID.class);
  private static final Field<Long> VERSION_LOCAL_TENANT_KEY = field("local_tenant_key", Long.class);
  private static final Field<String> VERSION_INTAKE_REQUEST_DIGEST =
      field("intake_request_digest", String.class);
  private static final Field<UUID> VERSION_SOURCE_OPERATION_ID =
      field("source_operation_id", UUID.class);
  private static final Field<String> VERSION_SOURCE_EVIDENCE_DIGEST =
      field("source_evidence_digest", String.class);
  private static final Field<String> VERSION_INTAKE_RECEIPT_DIGEST =
      field("intake_receipt_digest", String.class);
  private static final Field<Short> VERSION_STATE_SCHEMA_VERSION =
      field("version_state_schema_version", Short.class);
  private static final Field<UUID> VERSION_STATE_READ_REQUEST_ID =
      field("version_state_read_request_id", UUID.class);
  private static final Field<String> VERSION_STATE_STATE =
      field("version_state_state", String.class);
  private static final Field<Long> VERSION_STATE_EVIDENCE_EPOCH =
      field("version_state_epoch", Long.class);
  private static final Field<String> VERSION_STATE_EVIDENCE_DIGEST =
      field("version_state_evidence_digest", String.class);
  private static final Field<String> VERSION_STATE_EVIDENCE_JSON =
      field("version_state_evidence_json", String.class);

  private final DSLContext dsl;
  private final WorldCompleteLaunchBindingRepository launchBindingRepository;
  private final WorldAuthoredSourceIntakeRepository sourceIntakeRepository;
  private final WorldAuthoredVersionIdentityRepository versionIdentityRepository;

  public WorldCanonicalInstanceAssociationRepository(
      DSLContext dsl,
      WorldCompleteLaunchBindingRepository launchBindingRepository,
      WorldAuthoredSourceIntakeRepository sourceIntakeRepository,
      WorldAuthoredVersionIdentityRepository versionIdentityRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.launchBindingRepository =
        Objects.requireNonNull(launchBindingRepository, "launchBindingRepository");
    this.sourceIntakeRepository =
        Objects.requireNonNull(sourceIntakeRepository, "sourceIntakeRepository");
    this.versionIdentityRepository =
        Objects.requireNonNull(versionIdentityRepository, "versionIdentityRepository");
  }

  /**
   * Retains an exact non-authorizing claim in the caller's short writable READ COMMITTED World
   * transaction, after the same transaction created or selected its exact canonical World row.
   *
   * <p>This method does not commit the caller's transaction. The caller must commit, then use
   * {@link #readOwnerAssociation(UUID)} as a separate committed read before reporting storage
   * success. An exact retry compares the immutable V26 descriptor/release bytes and V23/V27 source
   * and Version provenance already supplied in {@code claim}; it never re-resolves Game Design.
   */
  public void retainClaimInOwnerTransaction(WorldCanonicalInstanceAssociation.Claim claim) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(claim, "claim");

    Record worldRow = lockWorldInstance(claim.worldInstanceId());
    WorldCanonicalInstanceAssociation.WorldPrepareFields prepareFields =
        toWorldPrepareFields(worldRow);
    requireInsertBornIdentity(worldRow, claim);
    prepareFields.requireMatches(
        claim.identity(), claim.completeLaunchBinding(), claim.versionIdentity());
    requireExactImmutableOwnerEvidence(claim);

    Record existing = findConflict(claim);
    if (existing != null) {
      requireSameStoredClaim(existing, claim, prepareFields);
      return;
    }

    int inserted =
        dsl.insertInto(ASSOCIATION)
            .set(SCHEMA_VERSION, (short) 2)
            .set(CANONICAL_GAME_INSTANCE_ID, claim.canonicalGameInstanceId())
            .set(CANONICAL_TARGET_NAMESPACE, claim.targetNamespace())
            .set(CANONICAL_TENANT_ID, claim.canonicalTenantId())
            .set(CANONICAL_WORLD_SLUG, claim.worldSlug())
            .set(PLAYABLE_STATE_NAMESPACE_ID, claim.playableStateNamespaceId())
            .set(PLAYABLE_STATE_SCOPE, claim.playableStateScope())
            .set(PUBLIC_PRODUCTION, claim.identity().publicProduction())
            .set(CANONICAL_REALM_ID, claim.identity().canonicalRealmId())
            .set(CATALOG_REVISION, claim.identity().catalogRevision())
            .set(PLAYTEST_LIFECYCLE_ID, (UUID) null)
            .set(PLAYTEST_STATE_GENERATION, (Long) null)
            .set(CONTROL_PLANE_REQUEST_ID, claim.controlPlaneRequestId())
            .set(WORLD_INSTANCE_ID, prepareFields.worldInstanceId())
            .set(LAUNCH_BINDING_OPERATION_ID, claim.launchBindingOperationId())
            .set(VERSION_IDENTITY_OPERATION_ID, claim.versionIdentityOperationId())
            .set(CANONICAL_VERSION_ID, claim.canonicalVersionId())
            .set(LOCAL_TENANT_KEY, prepareFields.privateTenantKey())
            .set(PRIVATE_GAME_INSTANCE_KEY, prepareFields.privateGameInstanceKey())
            .set(GAME_TEMPLATE_ID, prepareFields.gameTemplateId())
            .set(LAUNCH_DESCRIPTOR_ID, prepareFields.launchDescriptorId())
            .set(LOCAL_VERSION_KEY, prepareFields.localVersionKey())
            .set(SCRIPT_PATCH_VERSION, prepareFields.scriptPatchVersion())
            .set(RUNTIME_FLAGS_JSON, prepareFields.runtimeFlagsJson())
            .set(GENERATION_CONFIG_REVISION, prepareFields.generationConfigRevision())
            .set(RELEASE_BUNDLE_ID, prepareFields.releaseBundleId())
            .set(PUBLISHED_RELEASE_BUNDLE_REF, prepareFields.publishedReleaseBundleRef())
            .set(VERSION_STATE_EPOCH, prepareFields.versionStateEpoch())
            .set(REMAP_SET_ID, prepareFields.remapSetId())
            .set(
                INTAKE_OPERATION_ID,
                claim.completeLaunchBinding().sourceIntakeReceipt().operationId())
            .set(
                INTAKE_REQUEST_ID,
                claim.completeLaunchBinding().sourceIntakeReceipt().intakeRequestId())
            .set(
                SOURCE_OPERATION_ID,
                claim.completeLaunchBinding().sourceIntakeReceipt().sourceOperationId())
            .set(
                SOURCE_EVIDENCE_DIGEST,
                claim.completeLaunchBinding().sourceIntakeReceipt().sourceEvidenceDigest())
            .set(
                INTAKE_REQUEST_DIGEST,
                claim.completeLaunchBinding().sourceIntakeReceipt().requestDigest())
            .set(
                INTAKE_RECEIPT_DIGEST,
                claim.completeLaunchBinding().sourceIntakeReceipt().receiptDigest())
            .set(
                DESCRIPTOR_REQUEST_DIGEST,
                claim.completeLaunchBinding().descriptor().requestDigest())
            .set(
                DESCRIPTOR_RESULT_DIGEST, claim.completeLaunchBinding().descriptor().resultDigest())
            .set(
                RELEASE_ATTESTATION_DIGEST,
                claim.completeLaunchBinding().evidence().releaseAttestation().evidenceDigest())
            .onConflictDoNothing()
            .execute();

    Record stored = findConflict(claim);
    if (stored == null) {
      throw new InvalidAssociationEvidenceException(
          "World canonical instance association is missing after its owner claim");
    }
    requireSameStoredClaim(stored, claim, prepareFields);
    if (inserted == 0) {
      // The complete exact compare above is the only idempotent conflict outcome.
      return;
    }
  }

  /**
   * Serializes the existing fixture operation and canonical instance before any publication-owner
   * or source/Version row lock. Reacquiring these transaction-scoped locks during allocation is
   * safe.
   */
  public void lockRunOwnedFixtureInOwnerTransaction(
      RunOwnedWorldLaunchEvidence fixtureEvidence,
      PreparedWorldInstanceRequest gameSessionRequest) {
    Objects.requireNonNull(fixtureEvidence, "fixtureEvidence").requireFixedGameSessionPeer();
    requireWritableReadCommittedOwnerTransaction();
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Run-owned World fixture locking requires a physical writable READ COMMITTED owner transaction");
          }
        });
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"),
            "World transaction state query returned no row");
    if (!"read committed".equals(state.get("isolation", String.class))
        || !"off".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "Run-owned World fixture locking requires a physical writable READ COMMITTED owner transaction");
    }
    Objects.requireNonNull(fixtureEvidence, "fixtureEvidence");
    Objects.requireNonNull(gameSessionRequest, "gameSessionRequest");
    fixtureEvidence.requireMatches(gameSessionRequest);
    var gs = fixtureEvidence.gameSessionAssociation();
    if (gs == null) {
      throw new InvalidAssociationEvidenceException(
          "Run-owned fixture locking requires the exact verified Game Session association");
    }
    String lockIdentity =
        gs.request().targetNamespace()
            + ":"
            + gs.canonicalTenantId()
            + ":"
            + fixtureEvidence.capability().operationId();
    dsl.fetchOne("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", lockIdentity);
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "canonical-game-instance:" + gs.gameInstanceUuid());
  }

  /**
   * Allocates and retains one fresh World-local run-owned fixture row and its canonical association
   * in the caller's writable READ COMMITTED owner transaction. This is the fixture arm of the
   * existing association owner; it does not adopt a prior numeric row or reuse Game Session or Game
   * Design private keys as World keys.
   */
  public WorldCanonicalInstanceAssociation allocateRunOwnedFixtureInOwnerTransaction(
      RunOwnedWorldLaunchEvidence fixtureEvidence,
      PreparedWorldInstanceRequest gameSessionRequest,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      WorldAuthoredVersionIdentityReceipt versionIdentity) {
    Objects.requireNonNull(fixtureEvidence, "fixtureEvidence").requireFixedGameSessionPeer();
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(fixtureEvidence, "fixtureEvidence");
    Objects.requireNonNull(gameSessionRequest, "gameSessionRequest");
    Objects.requireNonNull(sourceReceipt, "sourceReceipt");
    Objects.requireNonNull(versionIdentity, "versionIdentity");
    lockRunOwnedFixtureInOwnerTransaction(fixtureEvidence, gameSessionRequest);

    var gs = fixtureEvidence.gameSessionAssociation();
    if (gs == null
        || !sourceReceipt.equals(versionIdentity.sourceIntakeReceipt())
        || !sourceReceipt.source().equals(fixtureEvidence.sourceReceipt().sourceEvidence())
        || !sourceReceipt.targetNamespace().equals(gs.request().targetNamespace())
        || !fixtureEvidence
            .fixtureProvenanceDigest()
            .equals(gs.fixturePolicySeal().evidenceDigest())
        || !fixtureEvidence
            .capability()
            .operationId()
            .equals(gameSessionRequest.controlPlaneRequestId())
        || !sourceReceipt.canonicalTenantId().equals(versionIdentity.canonicalTenantId())
        || !sourceReceipt.worldSlug().equals(versionIdentity.worldSlug())
        || !fixtureEvidence
            .launchDescriptor()
            .canonicalTenantId()
            .equals(sourceReceipt.canonicalTenantId())
        || !fixtureEvidence.launchDescriptor().worldSlug().equals(sourceReceipt.worldSlug())
        || fixtureEvidence.launchDescriptor().versionId() != versionIdentity.gameDesignVersionId()
        || !versionIdentity.canonicalVersionId().equals(gs.canonicalVersionId())
        || !"NEW_GAME_ROW".equals(sourceReceipt.source().provenanceKind())) {
      throw new InvalidAssociationEvidenceException(
          "Run-owned fixture allocation differs from the exact Game Session, World source, or Version owner readbacks");
    }

    lockFixtureSourceAndVersionOwners(sourceReceipt, versionIdentity);

    WorldAuthoredSourceIntakeReceipt storedSource =
        sourceIntakeRepository
            .readInOwnerActivationTransaction(
                sourceReceipt.targetNamespace(), sourceReceipt.intakeRequestId())
            .orElseThrow(
                () ->
                    new InvalidAssociationEvidenceException(
                        "Run-owned fixture lost its retained World source intake"));
    WorldAuthoredVersionIdentityReceipt storedVersion =
        versionIdentityRepository
            .readByCanonicalVersionInOwnerActivationTransaction(
                versionIdentity.targetNamespace(),
                versionIdentity.canonicalTenantId(),
                versionIdentity.worldSlug(),
                versionIdentity.canonicalVersionId())
            .orElseThrow(
                () ->
                    new InvalidAssociationEvidenceException(
                        "Run-owned fixture lost its retained World Version identity"));
    if (!storedSource.equals(sourceReceipt) || !storedVersion.equals(versionIdentity)) {
      throw new InvalidAssociationEvidenceException(
          "Run-owned fixture World source or Version receipt changed before allocation");
    }

    String fixtureOperationId = fixtureEvidence.capability().operationId();

    Record existing =
        findFixtureConflict(
            gs.gameInstanceUuid(),
            gs.request().targetNamespace(),
            gs.canonicalTenantId(),
            fixtureOperationId,
            sourceReceipt.localTenantKey());
    if (existing != null) {
      if (required(existing, SCHEMA_VERSION) != 3) {
        throw new RegistrationConflictException(
            "Run-owned fixture cannot adopt a historical or Human canonical association");
      }
      WorldCanonicalInstanceAssociation stored = toAssociation(existing, false, true);
      requireSameRunOwnedFixture(
          stored, fixtureEvidence, gameSessionRequest, sourceReceipt, versionIdentity);
      return stored;
    }

    Record preexistingWorldRow =
        dsl.selectFrom(WORLD_INSTANCE)
            .where(
                INSTANCE_CANONICAL_GAME_INSTANCE_ID
                    .eq(gs.gameInstanceUuid())
                    .or(
                        INSTANCE_TENANT_ID
                            .eq(sourceReceipt.localTenantKey())
                            .and(INSTANCE_CONTROL_PLANE_REQUEST_ID.eq(fixtureOperationId))))
            .fetchOne();
    if (preexistingWorldRow != null) {
      throw new RegistrationConflictException(
          "Run-owned fixture cannot adopt an existing World instance row without its exact fixture association");
    }

    long instanceId = nextPositiveSequenceValue("world_instance_id_seq");
    long privateGameInstanceKey =
        nextPositiveSequenceValue("world_canonical_private_game_instance_key_seq");
    var capability = fixtureEvidence.capability();
    var descriptor = fixtureEvidence.launchDescriptor();
    var source = sourceReceipt.source();
    String fixtureBindingSha256 = fixtureEvidence.bindingDigest(gameSessionRequest, 1L);
    String preparationInputDigest = "sha256:" + fixtureBindingSha256;
    byte[] associationResponseBytes =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureResponse(gs).toByteArray();
    String runtimeFlagsJson = descriptor.runtimeFlagsJson();

    WorldCanonicalInstanceAssociation.CanonicalIdentity identity =
        new WorldCanonicalInstanceAssociation.CanonicalIdentity(
            gs.gameInstanceUuid(),
            gs.request().targetNamespace(),
            gs.canonicalTenantId(),
            source.worldSlug(),
            gs.playableStateNamespaceId(),
            capability.stateScope(),
            capability.publicProductionRealm(),
            gs.canonicalRealmId(),
            gs.catalogRevision(),
            fixtureOperationId);
    WorldCanonicalInstanceAssociation.WorldPrivateKeys worldPrivateKeys =
        new WorldCanonicalInstanceAssociation.WorldPrivateKeys(
            sourceReceipt.localTenantKey(),
            privateGameInstanceKey,
            versionIdentity.localVersionKey());

    Map<String, Object> worldRow =
        runOwnedFixtureWorldInstanceRow(
            instanceId,
            worldPrivateKeys,
            fixtureEvidence,
            identity,
            fixtureBindingSha256,
            runtimeFlagsJson);
    Map<String, Object> associationRow =
        runOwnedFixtureAssociationRow(
            identity,
            instanceId,
            worldPrivateKeys,
            fixtureEvidence,
            sourceReceipt,
            versionIdentity,
            preparationInputDigest,
            associationResponseBytes,
            runtimeFlagsJson);

    dsl.fetchOne(
        "SELECT world_allocate_run_owned_fixture_instance(?::jsonb, ?::jsonb)",
        writeJson(worldRow),
        writeJson(associationRow));
    Record storedWorld = lockWorldInstance(instanceId);
    requireInsertBornFixtureIdentity(
        storedWorld,
        instanceId,
        identity,
        worldPrivateKeys,
        gameSessionRequest,
        fixtureEvidence,
        fixtureBindingSha256);
    Record storedAssociation = findByCanonicalGameInstance(identity.canonicalGameInstanceId());
    if (storedAssociation == null || required(storedAssociation, SCHEMA_VERSION) != 3) {
      throw new InvalidAssociationEvidenceException(
          "Run-owned fixture canonical association is missing after atomic allocation");
    }
    requireSameRunOwnedFixture(
        toAssociation(storedAssociation, false, true),
        fixtureEvidence,
        gameSessionRequest,
        sourceReceipt,
        versionIdentity);

    WorldCanonicalInstanceAssociation.RunOwnedFixture runOwnedFixture =
        new WorldCanonicalInstanceAssociation.RunOwnedFixture(
            worldPrivateKeys,
            gs.gameSessionTenantId(),
            gs.gameInstanceId(),
            descriptor.gameTemplateId(),
            UUID.fromString(fixtureOperationId),
            fixtureEvidence.fixtureProvenanceDigest(),
            preparationInputDigest,
            1L,
            associationResponseBytes,
            gs,
            sourceReceipt);
    return new WorldCanonicalInstanceAssociation(
        identity, instanceId, null, versionIdentity, null, runOwnedFixture);
  }

  private Map<String, Object> runOwnedFixtureWorldInstanceRow(
      long worldInstanceId,
      WorldCanonicalInstanceAssociation.WorldPrivateKeys worldPrivateKeys,
      RunOwnedWorldLaunchEvidence evidence,
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      String fixtureBindingSha256,
      String runtimeFlagsJson) {
    var descriptor = evidence.launchDescriptor();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put(INSTANCE_ID.getName(), worldInstanceId);
    row.put(INSTANCE_TENANT_ID.getName(), worldPrivateKeys.tenantId());
    row.put(INSTANCE_GAME_INSTANCE_ID.getName(), worldPrivateKeys.gameInstanceId());
    row.put(INSTANCE_GAME_TEMPLATE_ID.getName(), descriptor.gameTemplateId());
    row.put(INSTANCE_CONTROL_PLANE_REQUEST_ID.getName(), identity.controlPlaneRequestId());
    row.put(INSTANCE_LAUNCH_DESCRIPTOR_ID.getName(), descriptor.launchDescriptorId());
    row.put(INSTANCE_VERSION_ID.getName(), worldPrivateKeys.versionId());
    row.put(
        INSTANCE_SCRIPT_PATCH_VERSION.getName(),
        descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null);
    row.put(INSTANCE_RUNTIME_FLAGS_JSON.getName(), runtimeFlagsJson);
    row.put(INSTANCE_GENERATION_CONFIG_REVISION.getName(), descriptor.generationConfigRevision());
    row.put(INSTANCE_RELEASE_BUNDLE_ID.getName(), descriptor.releaseBundleId());
    row.put(
        INSTANCE_PUBLISHED_RELEASE_BUNDLE_REF.getName(), descriptor.publishedReleaseBundleRef());
    row.put(INSTANCE_VERSION_STATE_EPOCH.getName(), descriptor.versionStateEpoch());
    row.put(
        INSTANCE_REMAP_SET_ID.getName(),
        descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null);
    row.put(INSTANCE_CANONICAL_GAME_INSTANCE_ID.getName(), identity.canonicalGameInstanceId());
    row.put(INSTANCE_CANONICAL_TARGET_NAMESPACE.getName(), identity.targetNamespace());
    row.put(INSTANCE_CANONICAL_TENANT_ID.getName(), identity.canonicalTenantId());
    row.put(INSTANCE_CANONICAL_WORLD_SLUG.getName(), identity.worldSlug());
    row.put(INSTANCE_PLAYABLE_STATE_NAMESPACE_ID.getName(), identity.playableStateNamespaceId());
    row.put(INSTANCE_PLAYABLE_STATE_SCOPE.getName(), identity.playableStateScope());
    row.put(INSTANCE_PUBLIC_PRODUCTION.getName(), identity.publicProduction());
    row.put(INSTANCE_CANONICAL_REALM_ID.getName(), identity.canonicalRealmId());
    row.put(INSTANCE_CATALOG_REVISION.getName(), identity.catalogRevision());
    row.put(INSTANCE_PLAYTEST_LIFECYCLE_ID.getName(), (UUID) null);
    row.put(INSTANCE_PLAYTEST_STATE_GENERATION.getName(), (Long) null);
    row.put(INSTANCE_LAUNCH_BINDING_OPERATION_ID.getName(), (UUID) null);
    row.put(INSTANCE_CANONICAL_AUTHORITY_KIND.getName(), "RUN_OWNED_FIXTURE");
    row.put(INSTANCE_FIXTURE_PROVENANCE_DIGEST.getName(), evidence.fixtureProvenanceDigest());
    row.put(
        INSTANCE_GAME_SESSION_TENANT_ID.getName(),
        evidence.gameSessionAssociation().gameSessionTenantId());
    row.put(
        INSTANCE_GAME_SESSION_GAME_INSTANCE_ID.getName(),
        evidence.gameSessionAssociation().gameInstanceId());
    row.put(INSTANCE_GAME_DESIGN_VERSION_ID.getName(), descriptor.versionId());
    row.put(INSTANCE_FIXTURE_BINDING_SHA256.getName(), fixtureBindingSha256);
    row.put(INSTANCE_FIXTURE_PREPARING_EPOCH.getName(), 1L);
    row.put(INSTANCE_LIFECYCLE_EPOCH.getName(), 1L);
    row.put(INSTANCE_STATUS.getName(), "PREPARING");
    return row;
  }

  private Map<String, Object> runOwnedFixtureAssociationRow(
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      long worldInstanceId,
      WorldCanonicalInstanceAssociation.WorldPrivateKeys worldPrivateKeys,
      RunOwnedWorldLaunchEvidence evidence,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      WorldAuthoredVersionIdentityReceipt versionIdentity,
      String preparationInputDigest,
      byte[] gameSessionResponseBytes,
      String runtimeFlagsJson) {
    var descriptor = evidence.launchDescriptor();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put(SCHEMA_VERSION.getName(), (short) 3);
    row.put(CANONICAL_GAME_INSTANCE_ID.getName(), identity.canonicalGameInstanceId());
    row.put(CANONICAL_TARGET_NAMESPACE.getName(), identity.targetNamespace());
    row.put(CANONICAL_TENANT_ID.getName(), identity.canonicalTenantId());
    row.put(CANONICAL_WORLD_SLUG.getName(), identity.worldSlug());
    row.put(PLAYABLE_STATE_NAMESPACE_ID.getName(), identity.playableStateNamespaceId());
    row.put(PLAYABLE_STATE_SCOPE.getName(), identity.playableStateScope());
    row.put(PUBLIC_PRODUCTION.getName(), identity.publicProduction());
    row.put(CANONICAL_REALM_ID.getName(), identity.canonicalRealmId());
    row.put(CATALOG_REVISION.getName(), identity.catalogRevision());
    row.put(PLAYTEST_LIFECYCLE_ID.getName(), (UUID) null);
    row.put(PLAYTEST_STATE_GENERATION.getName(), (Long) null);
    row.put(CONTROL_PLANE_REQUEST_ID.getName(), identity.controlPlaneRequestId());
    row.put(WORLD_INSTANCE_ID.getName(), worldInstanceId);
    row.put(LAUNCH_BINDING_OPERATION_ID.getName(), (UUID) null);
    row.put(VERSION_IDENTITY_OPERATION_ID.getName(), versionIdentity.operationId());
    row.put(CANONICAL_VERSION_ID.getName(), versionIdentity.canonicalVersionId());
    row.put(LOCAL_TENANT_KEY.getName(), worldPrivateKeys.tenantId());
    row.put(PRIVATE_GAME_INSTANCE_KEY.getName(), worldPrivateKeys.gameInstanceId());
    row.put(GAME_TEMPLATE_ID.getName(), descriptor.gameTemplateId());
    row.put(LAUNCH_DESCRIPTOR_ID.getName(), descriptor.launchDescriptorId());
    row.put(LOCAL_VERSION_KEY.getName(), worldPrivateKeys.versionId());
    row.put(
        SCRIPT_PATCH_VERSION.getName(),
        descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null);
    row.put(RUNTIME_FLAGS_JSON.getName(), runtimeFlagsJson);
    row.put(GENERATION_CONFIG_REVISION.getName(), descriptor.generationConfigRevision());
    row.put(RELEASE_BUNDLE_ID.getName(), descriptor.releaseBundleId());
    row.put(PUBLISHED_RELEASE_BUNDLE_REF.getName(), descriptor.publishedReleaseBundleRef());
    row.put(VERSION_STATE_EPOCH.getName(), descriptor.versionStateEpoch());
    row.put(
        REMAP_SET_ID.getName(), descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null);
    row.put(INTAKE_OPERATION_ID.getName(), sourceReceipt.operationId());
    row.put(INTAKE_REQUEST_ID.getName(), sourceReceipt.intakeRequestId());
    row.put(SOURCE_OPERATION_ID.getName(), sourceReceipt.sourceOperationId());
    row.put(SOURCE_EVIDENCE_DIGEST.getName(), sourceReceipt.sourceEvidenceDigest());
    row.put(INTAKE_REQUEST_DIGEST.getName(), sourceReceipt.requestDigest());
    row.put(INTAKE_RECEIPT_DIGEST.getName(), sourceReceipt.receiptDigest());
    row.put(DESCRIPTOR_REQUEST_DIGEST.getName(), descriptor.requestDigest());
    row.put(DESCRIPTOR_RESULT_DIGEST.getName(), descriptor.resultDigest());
    row.put(RELEASE_ATTESTATION_DIGEST.getName(), (String) null);
    row.put(CANONICAL_AUTHORITY_KIND.getName(), "RUN_OWNED_FIXTURE");
    row.put(FIXTURE_PROVENANCE_DIGEST.getName(), evidence.fixtureProvenanceDigest());
    row.put(
        GAME_SESSION_TENANT_ID.getName(), evidence.gameSessionAssociation().gameSessionTenantId());
    row.put(
        GAME_SESSION_GAME_INSTANCE_ID.getName(),
        evidence.gameSessionAssociation().gameInstanceId());
    row.put(GAME_DESIGN_VERSION_ID.getName(), descriptor.versionId());
    row.put(PREPARATION_INPUT_DIGEST.getName(), preparationInputDigest);
    row.put(PREPARING_LIFECYCLE_EPOCH.getName(), 1L);
    row.put(
        GAME_SESSION_ASSOCIATION_RESPONSE_BYTES.getName(),
        "\\x" + HexFormat.of().formatHex(gameSessionResponseBytes));
    return row;
  }

  private Record findFixtureConflict(
      UUID canonicalGameInstanceId,
      String namespace,
      UUID canonicalTenantId,
      String fixtureOperationId,
      long worldTenantId) {
    var rows =
        dsl.selectFrom(ASSOCIATION)
            .where(
                CANONICAL_GAME_INSTANCE_ID
                    .eq(canonicalGameInstanceId)
                    .or(
                        CANONICAL_TARGET_NAMESPACE
                            .eq(namespace)
                            .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                            .and(CONTROL_PLANE_REQUEST_ID.eq(fixtureOperationId)))
                    .or(
                        FIXTURE_PROVENANCE_DIGEST
                            .isNotNull()
                            .and(CONTROL_PLANE_REQUEST_ID.eq(fixtureOperationId))))
            .fetch();
    if (rows.size() > 1) {
      throw new RegistrationConflictException(
          "Run-owned fixture identity or operation has multiple World associations");
    }
    if (!rows.isEmpty()) return rows.getFirst();

    Record worldRow =
        dsl.selectFrom(WORLD_INSTANCE)
            .where(
                INSTANCE_CANONICAL_GAME_INSTANCE_ID
                    .eq(canonicalGameInstanceId)
                    .or(
                        INSTANCE_TENANT_ID
                            .eq(worldTenantId)
                            .and(INSTANCE_CONTROL_PLANE_REQUEST_ID.eq(fixtureOperationId))))
            .fetchOne();
    if (worldRow != null) {
      throw new RegistrationConflictException(
          "Run-owned fixture operation or shared canonical UUID is already bound to a World row");
    }
    return null;
  }

  private static void requireSameRunOwnedFixture(
      WorldCanonicalInstanceAssociation stored,
      RunOwnedWorldLaunchEvidence evidence,
      PreparedWorldInstanceRequest request,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      WorldAuthoredVersionIdentityReceipt versionIdentity) {
    var expectedGs = evidence.gameSessionAssociation();
    var actual = stored.runOwnedFixture();
    if (actual == null) {
      throw new RegistrationConflictException(
          "Run-owned fixture retry resolved to a Human association");
    }
    String expectedPreparationDigest =
        "sha256:" + evidence.bindingDigest(request, actual.preparingLifecycleEpoch());
    if (!stored.isRunOwnedFixture()
        || !stored.versionIdentity().equals(versionIdentity)
        || !stored.identity().canonicalGameInstanceId().equals(expectedGs.gameInstanceUuid())
        || !stored.identity().targetNamespace().equals(expectedGs.request().targetNamespace())
        || !stored.identity().canonicalTenantId().equals(expectedGs.canonicalTenantId())
        || !stored.identity().worldSlug().equals(sourceReceipt.worldSlug())
        || !stored.identity().controlPlaneRequestId().equals(evidence.capability().operationId())
        || !actual.sourceIntakeReceipt().equals(sourceReceipt)
        || !actual.fixtureOperationId().toString().equals(evidence.capability().operationId())
        || !actual.fixtureProvenanceDigest().equals(evidence.fixtureProvenanceDigest())
        || !actual.preparationInputDigest().equals(expectedPreparationDigest)
        || actual.gameSessionTenantId() != expectedGs.gameSessionTenantId()
        || actual.gameSessionGameInstanceId() != expectedGs.gameInstanceId()
        || !sameFixtureLaunchAllocation(actual.gameSessionAssociation(), expectedGs)
        || actual.worldPrivateKeys().tenantId() != sourceReceipt.localTenantKey()
        || actual.worldPrivateKeys().versionId() != versionIdentity.localVersionKey()
        || !Objects.equals(actual.gameTemplateId(), evidence.launchDescriptor().gameTemplateId())) {
      throw new RegistrationConflictException(
          "Run-owned fixture retry differs from the original World allocation, policy seal, or owner receipts");
    }
  }

  private static boolean sameFixtureLaunchAllocation(
      CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult stored,
      CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult current) {
    return stored.request().targetNamespace().equals(current.request().targetNamespace())
        && stored.request().fixtureOperationId().equals(current.request().fixtureOperationId())
        && stored
            .request()
            .expectedFixturePolicyEvidenceDigest()
            .equals(current.request().expectedFixturePolicyEvidenceDigest())
        && stored.gameSessionTenantId() == current.gameSessionTenantId()
        && stored.gameInstanceId() == current.gameInstanceId()
        && stored.canonicalTenantId().equals(current.canonicalTenantId())
        && stored.gameInstanceUuid().equals(current.gameInstanceUuid())
        && stored.canonicalVersionId().equals(current.canonicalVersionId())
        && stored.canonicalRealmId().equals(current.canonicalRealmId())
        && stored.playableStateNamespaceId().equals(current.playableStateNamespaceId())
        && stored.catalogRevision() == current.catalogRevision()
        && java.util.Arrays.equals(
            stored.fixturePolicyRequestBytes(), current.fixturePolicyRequestBytes())
        && stored.fixturePolicySeal().equals(current.fixturePolicySeal());
  }

  /**
   * Independently reads and reconstructs one immutable owner association after the write
   * transaction commits. A present result is non-authorizing and carries no lifecycle status.
   */
  public Optional<WorldCanonicalInstanceAssociation> readOwnerAssociation(
      UUID canonicalGameInstanceId) {
    requireNoActiveTransaction("World canonical instance association read");
    return readOwnerAssociation(canonicalGameInstanceId, false);
  }

  /**
   * Resolves only the provider-authenticated fixture UUID to its retained World-private keys. The
   * external Game Session allocation tuple is checked against the original immutable binding; it is
   * never used as a World key.
   */
  public WorldCanonicalInstanceAssociation readRunOwnedFixtureAssociation(
      RunOwnedWorldLaunchEvidence evidence,
      PreparedWorldInstanceRequest originalGameSessionRequest) {
    Objects.requireNonNull(evidence, "evidence").requireFixedGameSessionPeer();
    requireNoActiveTransaction("Run-owned World fixture association read");
    Objects.requireNonNull(evidence, "evidence");
    Objects.requireNonNull(originalGameSessionRequest, "originalGameSessionRequest");
    evidence.requireMatches(originalGameSessionRequest);
    var gameSession = evidence.gameSessionAssociation();
    if (gameSession == null) {
      throw new InvalidAssociationEvidenceException(
          "Authenticated Game Session fixture allocation is unavailable");
    }
    WorldCanonicalInstanceAssociation stored =
        readOwnerAssociation(gameSession.gameInstanceUuid())
            .orElseThrow(
                () ->
                    new InvalidAssociationEvidenceException(
                        "Run-owned World fixture association is not retained"));
    requireFixtureAssociationMatches(stored, evidence, originalGameSessionRequest);
    return stored;
  }

  /** Reconstructs the association without leaving the lifecycle owner's verified snapshot. */
  Optional<WorldCanonicalInstanceAssociation> readOwnerAssociationInOwnerTransaction(
      UUID canonicalGameInstanceId) {
    requireReadOnlyRepeatableReadOwnerTransaction();
    return readOwnerAssociation(canonicalGameInstanceId, true);
  }

  /** Reuses the exact immutable source reconstruction inside a writable activation transaction. */
  Optional<WorldCanonicalInstanceAssociation> readOwnerAssociationInActivationTransaction(
      UUID canonicalGameInstanceId) {
    requireWritableActivationTransaction();
    return readOwnerAssociation(canonicalGameInstanceId, true, true);
  }

  /**
   * Locks and verifies the immutable fixture association inside a writable lifecycle transaction.
   */
  public WorldCanonicalInstanceAssociation readRunOwnedFixtureAssociationInActivationTransaction(
      RunOwnedWorldLaunchEvidence evidence,
      PreparedWorldInstanceRequest originalGameSessionRequest) {
    Objects.requireNonNull(evidence, "evidence").requireFixedGameSessionPeer();
    requireWritableActivationTransaction();
    Objects.requireNonNull(evidence, "evidence");
    Objects.requireNonNull(originalGameSessionRequest, "originalGameSessionRequest");
    evidence.requireMatches(originalGameSessionRequest);
    var gameSession = evidence.gameSessionAssociation();
    if (gameSession == null) {
      throw new InvalidAssociationEvidenceException(
          "Authenticated Game Session fixture allocation is unavailable");
    }
    WorldCanonicalInstanceAssociation stored =
        readOwnerAssociation(gameSession.gameInstanceUuid(), true, true)
            .orElseThrow(
                () ->
                    new InvalidAssociationEvidenceException(
                        "Run-owned World fixture association is not retained"));
    requireFixtureAssociationMatches(stored, evidence, originalGameSessionRequest);
    return stored;
  }

  /** Fixture activation remains unavailable after PREPARING-only materialization. */
  public void activateRunOwnedFixtureInOwnerTransaction(
      WorldCanonicalInstanceAssociation association, long expectedEpoch, long expectedRowVersion) {
    throw new IllegalStateException(
        "RUN_OWNED_WORLD_ACTIVATION_UNAVAILABLE: fixture activation requires the later canonical lifecycle and hold composition");
  }

  private static void requireFixtureAssociationMatches(
      WorldCanonicalInstanceAssociation stored,
      RunOwnedWorldLaunchEvidence evidence,
      PreparedWorldInstanceRequest originalGameSessionRequest) {
    if (!stored.isRunOwnedFixture()) {
      throw new RegistrationConflictException(
          "Run-owned fixture cannot adopt a Human canonical World association");
    }
    var fixture = stored.runOwnedFixture();
    requireSameRunOwnedFixture(
        stored,
        evidence,
        originalGameSessionRequest,
        fixture.sourceIntakeReceipt(),
        stored.versionIdentity());
  }

  private void lockFixtureSourceAndVersionOwners(
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      WorldAuthoredVersionIdentityReceipt versionIdentity) {
    Record tenant =
        dsl.fetchOne(
            "SELECT local_tenant_key FROM world_authored_source_tenant_association "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? FOR SHARE",
            sourceReceipt.targetNamespace(),
            sourceReceipt.canonicalTenantId());
    Record source =
        dsl.fetchOne(
            "SELECT receipt_digest FROM world_authored_source_intake "
                + "WHERE target_namespace = ? AND intake_request_id = ? "
                + "AND operation_id = ? FOR SHARE",
            sourceReceipt.targetNamespace(),
            sourceReceipt.intakeRequestId(),
            sourceReceipt.operationId());
    Record version =
        dsl.fetchOne(
            "SELECT operation_id FROM world_authored_version_identity "
                + "WHERE operation_id = ? AND target_namespace = ? "
                + "AND canonical_tenant_id = ? AND world_slug = ? "
                + "AND canonical_version_id = ? FOR SHARE",
            versionIdentity.operationId(),
            versionIdentity.targetNamespace(),
            versionIdentity.canonicalTenantId(),
            versionIdentity.worldSlug(),
            versionIdentity.canonicalVersionId());
    if (tenant == null
        || !Objects.equals(
            tenant.get("local_tenant_key", Long.class), sourceReceipt.localTenantKey())
        || source == null
        || !Objects.equals(
            source.get("receipt_digest", String.class), sourceReceipt.receiptDigest())
        || version == null) {
      throw new InvalidAssociationEvidenceException(
          "Run-owned fixture source or Version owner row changed before World allocation");
    }
  }

  private Optional<WorldCanonicalInstanceAssociation> readOwnerAssociation(
      UUID canonicalGameInstanceId, boolean inOwnerSnapshot) {
    return readOwnerAssociation(canonicalGameInstanceId, inOwnerSnapshot, false);
  }

  private Optional<WorldCanonicalInstanceAssociation> readOwnerAssociation(
      UUID canonicalGameInstanceId, boolean inOwnerSnapshot, boolean inActivationTransaction) {
    requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    Record row =
        inActivationTransaction
            ? dsl.selectFrom(ASSOCIATION)
                .where(CANONICAL_GAME_INSTANCE_ID.eq(canonicalGameInstanceId))
                .forUpdate()
                .fetchOne()
            : findByCanonicalGameInstance(canonicalGameInstanceId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(toAssociation(row, inOwnerSnapshot, inActivationTransaction));
  }

  private WorldCanonicalInstanceAssociation toAssociation(
      Record row, boolean inOwnerSnapshot, boolean inActivationTransaction) {
    try {
      short schemaVersion = required(row, SCHEMA_VERSION);
      if (schemaVersion == 3) {
        return toRunOwnedFixtureAssociation(row, inOwnerSnapshot, inActivationTransaction);
      }
      if (schemaVersion != 2) {
        throw new IllegalArgumentException(
            "Historical World canonical instance association lacks launch-captured catalog identity");
      }
      String namespace = required(row, CANONICAL_TARGET_NAMESPACE);
      UUID tenantId = required(row, CANONICAL_TENANT_ID);
      String worldSlug = required(row, CANONICAL_WORLD_SLUG);
      String controlRequestId = required(row, CONTROL_PLANE_REQUEST_ID);
      UUID bindingOperationId = required(row, LAUNCH_BINDING_OPERATION_ID);
      UUID intakeRequestId = required(row, INTAKE_REQUEST_ID);
      WorldCompleteLaunchBindingRepository.StoredBinding storedBinding =
          (inActivationTransaction
                  ? launchBindingRepository.readInOwnerActivationTransaction(
                      namespace, tenantId, controlRequestId)
                  : inOwnerSnapshot
                      ? launchBindingRepository.readInOwnerReadOnlyRepeatableRead(
                          namespace, tenantId, controlRequestId)
                      : launchBindingRepository.read(namespace, tenantId, controlRequestId))
              .orElseThrow(
                  () ->
                      new InvalidAssociationEvidenceException(
                          "World canonical association lost its immutable launch binding"));
      if (!storedBinding.operationId().equals(bindingOperationId)) {
        throw new InvalidAssociationEvidenceException(
            "World canonical association substituted its complete launch binding");
      }
      WorldAuthoredSourceIntakeReceipt sourceReceipt =
          (inActivationTransaction
                  ? sourceIntakeRepository.readInOwnerActivationTransaction(
                      namespace, intakeRequestId)
                  : inOwnerSnapshot
                      ? sourceIntakeRepository.readInOwnerReadOnlyRepeatableRead(
                          namespace, intakeRequestId)
                      : sourceIntakeRepository.read(namespace, intakeRequestId))
              .orElseThrow(
                  () ->
                      new InvalidAssociationEvidenceException(
                          "World canonical association lost its original source intake"));
      WorldCompleteLaunchBindingReceipt completeBinding =
          launchBindingRepository.toReceipt(storedBinding, sourceReceipt);
      UUID canonicalVersionId = required(row, CANONICAL_VERSION_ID);
      WorldAuthoredVersionIdentityReceipt versionIdentity =
          (inActivationTransaction
                  ? versionIdentityRepository.readByCanonicalVersionInOwnerActivationTransaction(
                      namespace, tenantId, worldSlug, canonicalVersionId)
                  : inOwnerSnapshot
                      ? versionIdentityRepository
                          .readByCanonicalVersionInOwnerReadOnlyRepeatableRead(
                              namespace, tenantId, worldSlug, canonicalVersionId)
                      : versionIdentityRepository.readByCanonicalVersion(
                          namespace, tenantId, worldSlug, canonicalVersionId))
              .orElseThrow(
                  () ->
                      new InvalidAssociationEvidenceException(
                          "World canonical association lost its UUID-to-private-Version receipt"));
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity =
          new WorldCanonicalInstanceAssociation.CanonicalIdentity(
              required(row, CANONICAL_GAME_INSTANCE_ID),
              namespace,
              tenantId,
              worldSlug,
              required(row, PLAYABLE_STATE_NAMESPACE_ID),
              required(row, PLAYABLE_STATE_SCOPE),
              required(row, PUBLIC_PRODUCTION),
              required(row, CANONICAL_REALM_ID),
              required(row, CATALOG_REVISION),
              controlRequestId);
      if (row.get(PLAYTEST_LIFECYCLE_ID) != null || row.get(PLAYTEST_STATE_GENERATION) != null) {
        throw new InvalidAssociationEvidenceException(
            "Persisted playtest association lacks the current producer's conditional lifecycle/generation proof");
      }
      long worldInstanceId = required(row, WORLD_INSTANCE_ID);
      Record worldRow = readWorldInstance(worldInstanceId);
      WorldCanonicalInstanceAssociation.WorldPrepareFields prepareFields =
          toWorldPrepareFields(worldRow);
      requireInsertBornIdentity(worldRow, identity, completeBinding.operationId());
      prepareFields.requireMatches(identity, completeBinding, versionIdentity);
      requireStoredAssociationColumns(
          row, identity, worldInstanceId, completeBinding, versionIdentity, prepareFields);
      return new WorldCanonicalInstanceAssociation(
          identity, worldInstanceId, completeBinding, versionIdentity, prepareFields);
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidAssociationEvidenceException) {
        throw exception;
      }
      throw new InvalidAssociationEvidenceException(
          "Persisted World canonical instance association is invalid", exception);
    }
  }

  private WorldCanonicalInstanceAssociation toRunOwnedFixtureAssociation(
      Record row, boolean inOwnerSnapshot, boolean inActivationTransaction) {
    if (!"RUN_OWNED_FIXTURE".equals(required(row, CANONICAL_AUTHORITY_KIND))
        || row.get(LAUNCH_BINDING_OPERATION_ID) != null
        || row.get(RELEASE_ATTESTATION_DIGEST) != null) {
      throw new InvalidAssociationEvidenceException(
          "Fixture association contains a Human launch or release authority field");
    }
    String namespace = required(row, CANONICAL_TARGET_NAMESPACE);
    UUID canonicalTenantId = required(row, CANONICAL_TENANT_ID);
    String worldSlug = required(row, CANONICAL_WORLD_SLUG);
    String fixtureOperationId = required(row, CONTROL_PLANE_REQUEST_ID);
    UUID fixtureOperation = UUID.fromString(fixtureOperationId);
    String fixtureDigest = required(row, FIXTURE_PROVENANCE_DIGEST);
    byte[] responseBytes = required(row, GAME_SESSION_ASSOCIATION_RESPONSE_BYTES);
    GetRunOwnedFixtureLaunchAssociationResponse response;
    try {
      response = GetRunOwnedFixtureLaunchAssociationResponse.parseFrom(responseBytes);
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new InvalidAssociationEvidenceException(
          "Persisted Game Session fixture association response is invalid", invalid);
    }
    var request =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
            UUID.fromString(response.getReadRequestId()),
            namespace,
            fixtureOperation,
            fixtureDigest);
    var gs =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureResponse(request, response);
    long worldInstanceId = required(row, WORLD_INSTANCE_ID);
    WorldCanonicalInstanceAssociation.CanonicalIdentity identity =
        new WorldCanonicalInstanceAssociation.CanonicalIdentity(
            required(row, CANONICAL_GAME_INSTANCE_ID),
            namespace,
            canonicalTenantId,
            worldSlug,
            required(row, PLAYABLE_STATE_NAMESPACE_ID),
            required(row, PLAYABLE_STATE_SCOPE),
            required(row, PUBLIC_PRODUCTION),
            required(row, CANONICAL_REALM_ID),
            required(row, CATALOG_REVISION),
            fixtureOperationId);
    UUID canonicalVersionId = required(row, CANONICAL_VERSION_ID);
    WorldAuthoredSourceIntakeReceipt sourceReceipt =
        (inActivationTransaction
                ? sourceIntakeRepository.readInOwnerActivationTransaction(
                    namespace, required(row, INTAKE_REQUEST_ID))
                : inOwnerSnapshot
                    ? sourceIntakeRepository.readInOwnerReadOnlyRepeatableRead(
                        namespace, required(row, INTAKE_REQUEST_ID))
                    : sourceIntakeRepository.read(namespace, required(row, INTAKE_REQUEST_ID)))
            .orElseThrow(
                () ->
                    new InvalidAssociationEvidenceException(
                        "Run-owned fixture association lost its exact World source intake"));
    WorldAuthoredVersionIdentityReceipt versionIdentity =
        (inActivationTransaction
                ? versionIdentityRepository.readByCanonicalVersionInOwnerActivationTransaction(
                    namespace, canonicalTenantId, worldSlug, canonicalVersionId)
                : inOwnerSnapshot
                    ? versionIdentityRepository.readByCanonicalVersionInOwnerReadOnlyRepeatableRead(
                        namespace, canonicalTenantId, worldSlug, canonicalVersionId)
                    : versionIdentityRepository.readByCanonicalVersion(
                        namespace, canonicalTenantId, worldSlug, canonicalVersionId))
            .orElseThrow(
                () ->
                    new InvalidAssociationEvidenceException(
                        "Run-owned fixture association lost its exact World Version identity"));
    WorldCanonicalInstanceAssociation.WorldPrivateKeys worldPrivateKeys =
        new WorldCanonicalInstanceAssociation.WorldPrivateKeys(
            required(row, LOCAL_TENANT_KEY),
            required(row, PRIVATE_GAME_INSTANCE_KEY),
            required(row, LOCAL_VERSION_KEY));
    WorldCanonicalInstanceAssociation.RunOwnedFixture fixture =
        new WorldCanonicalInstanceAssociation.RunOwnedFixture(
            worldPrivateKeys,
            required(row, GAME_SESSION_TENANT_ID),
            required(row, GAME_SESSION_GAME_INSTANCE_ID),
            required(row, GAME_TEMPLATE_ID),
            fixtureOperation,
            fixtureDigest,
            required(row, PREPARATION_INPUT_DIGEST),
            required(row, PREPARING_LIFECYCLE_EPOCH),
            responseBytes,
            gs,
            sourceReceipt);

    Record worldRow = readWorldInstance(worldInstanceId);
    requireStoredFixtureAssociationColumns(
        row, identity, worldInstanceId, fixture, versionIdentity);
    requireStoredFixtureWorldRow(worldRow, row, identity, fixture, versionIdentity);
    return new WorldCanonicalInstanceAssociation(
        identity, worldInstanceId, null, versionIdentity, null, fixture);
  }

  private static void requireStoredFixtureAssociationColumns(
      Record row,
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      long worldInstanceId,
      WorldCanonicalInstanceAssociation.RunOwnedFixture fixture,
      WorldAuthoredVersionIdentityReceipt versionIdentity) {
    var source = fixture.sourceIntakeReceipt();
    var versionState = versionIdentity.versionStateEvidence();
    AuthoredWorldLaunchDescriptorEvidence descriptor = runOwnedFixtureDescriptor(fixture);
    if (required(row, SCHEMA_VERSION) != 3
        || !"RUN_OWNED_FIXTURE".equals(required(row, CANONICAL_AUTHORITY_KIND))
        || !Objects.equals(
            required(row, CANONICAL_GAME_INSTANCE_ID), identity.canonicalGameInstanceId())
        || !Objects.equals(required(row, CANONICAL_TARGET_NAMESPACE), identity.targetNamespace())
        || !Objects.equals(required(row, CANONICAL_TENANT_ID), identity.canonicalTenantId())
        || !Objects.equals(required(row, CANONICAL_WORLD_SLUG), identity.worldSlug())
        || !Objects.equals(
            required(row, PLAYABLE_STATE_NAMESPACE_ID), identity.playableStateNamespaceId())
        || !Objects.equals(required(row, PLAYABLE_STATE_SCOPE), identity.playableStateScope())
        || !Objects.equals(required(row, PUBLIC_PRODUCTION), identity.publicProduction())
        || !Objects.equals(required(row, CANONICAL_REALM_ID), identity.canonicalRealmId())
        || !Objects.equals(required(row, CATALOG_REVISION), identity.catalogRevision())
        || row.get(PLAYTEST_LIFECYCLE_ID) != null
        || row.get(PLAYTEST_STATE_GENERATION) != null
        || !Objects.equals(
            required(row, CONTROL_PLANE_REQUEST_ID), identity.controlPlaneRequestId())
        || !Objects.equals(required(row, WORLD_INSTANCE_ID), worldInstanceId)
        || row.get(LAUNCH_BINDING_OPERATION_ID) != null
        || !Objects.equals(
            required(row, VERSION_IDENTITY_OPERATION_ID), versionIdentity.operationId())
        || !Objects.equals(
            required(row, CANONICAL_VERSION_ID), versionIdentity.canonicalVersionId())
        || !Objects.equals(required(row, LOCAL_TENANT_KEY), fixture.worldPrivateKeys().tenantId())
        || !Objects.equals(
            required(row, PRIVATE_GAME_INSTANCE_KEY), fixture.worldPrivateKeys().gameInstanceId())
        || !Objects.equals(required(row, GAME_TEMPLATE_ID), fixture.gameTemplateId())
        || !Objects.equals(required(row, LOCAL_VERSION_KEY), fixture.worldPrivateKeys().versionId())
        || !Objects.equals(required(row, GAME_SESSION_TENANT_ID), fixture.gameSessionTenantId())
        || !Objects.equals(
            required(row, GAME_SESSION_GAME_INSTANCE_ID), fixture.gameSessionGameInstanceId())
        || !Objects.equals(
            required(row, GAME_DESIGN_VERSION_ID), versionIdentity.gameDesignVersionId())
        || !Objects.equals(required(row, LAUNCH_DESCRIPTOR_ID), descriptor.launchDescriptorId())
        || !Objects.equals(required(row, GAME_TEMPLATE_ID), descriptor.gameTemplateId())
        || !Objects.equals(
            row.get(SCRIPT_PATCH_VERSION),
            descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null)
        || !Objects.equals(required(row, RUNTIME_FLAGS_JSON), descriptor.runtimeFlagsJson())
        || !Objects.equals(
            required(row, GENERATION_CONFIG_REVISION), descriptor.generationConfigRevision())
        || !Objects.equals(required(row, RELEASE_BUNDLE_ID), descriptor.releaseBundleId())
        || !Objects.equals(
            required(row, PUBLISHED_RELEASE_BUNDLE_REF), descriptor.publishedReleaseBundleRef())
        || !Objects.equals(
            row.get(REMAP_SET_ID), descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null)
        || !Objects.equals(required(row, DESCRIPTOR_REQUEST_DIGEST), descriptor.requestDigest())
        || !Objects.equals(required(row, DESCRIPTOR_RESULT_DIGEST), descriptor.resultDigest())
        || !Objects.equals(
            required(row, FIXTURE_PROVENANCE_DIGEST), fixture.fixtureProvenanceDigest())
        || !Objects.equals(
            required(row, PREPARATION_INPUT_DIGEST), fixture.preparationInputDigest())
        || !Objects.equals(
            required(row, PREPARING_LIFECYCLE_EPOCH), fixture.preparingLifecycleEpoch())
        || row.get(RELEASE_ATTESTATION_DIGEST) != null
        || !Objects.equals(required(row, INTAKE_OPERATION_ID), source.operationId())
        || !Objects.equals(required(row, INTAKE_REQUEST_ID), source.intakeRequestId())
        || !Objects.equals(required(row, SOURCE_OPERATION_ID), source.sourceOperationId())
        || !Objects.equals(required(row, SOURCE_EVIDENCE_DIGEST), source.sourceEvidenceDigest())
        || !Objects.equals(required(row, INTAKE_REQUEST_DIGEST), source.requestDigest())
        || !Objects.equals(required(row, INTAKE_RECEIPT_DIGEST), source.receiptDigest())
        || !Objects.equals(required(row, VERSION_STATE_EPOCH), versionState.versionStateEpoch())) {
      throw new InvalidAssociationEvidenceException(
          "Persisted run-owned fixture association differs from its immutable World and external owner evidence");
    }
  }

  private static void requireStoredFixtureWorldRow(
      Record row,
      Record associationRow,
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      WorldCanonicalInstanceAssociation.RunOwnedFixture fixture,
      WorldAuthoredVersionIdentityReceipt versionIdentity) {
    String preparationInputDigest = fixture.preparationInputDigest();
    if (row == null
        || !"RUN_OWNED_FIXTURE".equals(row.get(INSTANCE_CANONICAL_AUTHORITY_KIND, String.class))
        || !Objects.equals(
            row.get(INSTANCE_CANONICAL_GAME_INSTANCE_ID, UUID.class),
            identity.canonicalGameInstanceId())
        || !Objects.equals(
            row.get(INSTANCE_CANONICAL_TARGET_NAMESPACE, String.class), identity.targetNamespace())
        || !Objects.equals(
            row.get(INSTANCE_CANONICAL_TENANT_ID, UUID.class), identity.canonicalTenantId())
        || !Objects.equals(
            row.get(INSTANCE_CANONICAL_WORLD_SLUG, String.class), identity.worldSlug())
        || !Objects.equals(
            row.get(INSTANCE_PLAYABLE_STATE_NAMESPACE_ID, UUID.class),
            identity.playableStateNamespaceId())
        || !Objects.equals(
            row.get(INSTANCE_PLAYABLE_STATE_SCOPE, String.class), identity.playableStateScope())
        || !Objects.equals(
            row.get(INSTANCE_PUBLIC_PRODUCTION, Boolean.class), identity.publicProduction())
        || !Objects.equals(
            row.get(INSTANCE_CANONICAL_REALM_ID, UUID.class), identity.canonicalRealmId())
        || !Objects.equals(
            row.get(INSTANCE_CATALOG_REVISION, Long.class), identity.catalogRevision())
        || row.get(INSTANCE_LAUNCH_BINDING_OPERATION_ID, UUID.class) != null
        || !Objects.equals(
            row.get(INSTANCE_FIXTURE_PROVENANCE_DIGEST, String.class),
            fixture.fixtureProvenanceDigest())
        || !Objects.equals(
            row.get(INSTANCE_GAME_SESSION_TENANT_ID, Long.class), fixture.gameSessionTenantId())
        || !Objects.equals(
            row.get(INSTANCE_GAME_SESSION_GAME_INSTANCE_ID, Long.class),
            fixture.gameSessionGameInstanceId())
        || !Objects.equals(
            row.get(INSTANCE_GAME_DESIGN_VERSION_ID, Long.class),
            versionIdentity.gameDesignVersionId())
        || !Objects.equals(
            row.get(INSTANCE_TENANT_ID, Long.class), fixture.worldPrivateKeys().tenantId())
        || !Objects.equals(
            row.get(INSTANCE_GAME_INSTANCE_ID, Long.class),
            fixture.worldPrivateKeys().gameInstanceId())
        || !Objects.equals(
            row.get(INSTANCE_VERSION_ID, Long.class), fixture.worldPrivateKeys().versionId())
        || !Objects.equals(
            row.get(INSTANCE_FIXTURE_BINDING_SHA256, String.class),
            preparationInputDigest.substring("sha256:".length()))
        || !Objects.equals(
            row.get(INSTANCE_FIXTURE_PREPARING_EPOCH, Long.class),
            fixture.preparingLifecycleEpoch())
        || fixture.preparingLifecycleEpoch() != 1L
        || !Objects.equals(
            row.get(INSTANCE_LIFECYCLE_EPOCH, Long.class), fixture.preparingLifecycleEpoch())
        || !"PREPARING".equals(row.get(INSTANCE_STATUS, String.class))
        || !Objects.equals(row.get("row_version", Long.class), 0L)
        || !Objects.equals(
            row.get(INSTANCE_CONTROL_PLANE_REQUEST_ID, String.class),
            identity.controlPlaneRequestId())
        || !Objects.equals(
            row.get(INSTANCE_GAME_TEMPLATE_ID, Long.class),
            required(associationRow, GAME_TEMPLATE_ID))
        || !Objects.equals(
            row.get(INSTANCE_LAUNCH_DESCRIPTOR_ID, String.class),
            required(associationRow, LAUNCH_DESCRIPTOR_ID))
        || !Objects.equals(
            row.get(INSTANCE_SCRIPT_PATCH_VERSION, String.class),
            associationRow.get(SCRIPT_PATCH_VERSION, String.class))
        || !Objects.equals(
            row.get(INSTANCE_RUNTIME_FLAGS_JSON, String.class),
            required(associationRow, RUNTIME_FLAGS_JSON))
        || !Objects.equals(
            row.get(INSTANCE_GENERATION_CONFIG_REVISION, String.class),
            required(associationRow, GENERATION_CONFIG_REVISION))
        || !Objects.equals(
            row.get(INSTANCE_RELEASE_BUNDLE_ID, Long.class),
            required(associationRow, RELEASE_BUNDLE_ID))
        || !Objects.equals(
            row.get(INSTANCE_PUBLISHED_RELEASE_BUNDLE_REF, String.class),
            required(associationRow, PUBLISHED_RELEASE_BUNDLE_REF))
        || !Objects.equals(
            row.get(INSTANCE_REMAP_SET_ID, String.class),
            associationRow.get(REMAP_SET_ID, String.class))
        || !Objects.equals(
            row.get(INSTANCE_VERSION_STATE_EPOCH, Long.class),
            versionIdentity.versionStateEvidence().versionStateEpoch())) {
      throw new InvalidAssociationEvidenceException(
          "Persisted run-owned fixture World row differs from its retained owner association");
    }
  }

  private void requireInsertBornFixtureIdentity(
      Record row,
      long worldInstanceId,
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      WorldCanonicalInstanceAssociation.WorldPrivateKeys worldPrivateKeys,
      PreparedWorldInstanceRequest request,
      RunOwnedWorldLaunchEvidence evidence,
      String fixtureBindingSha256) {
    evidence.requireMatches(request);
    var gameSession = evidence.gameSessionAssociation();
    if (gameSession == null) {
      throw new InvalidAssociationEvidenceException(
          "Run-owned World fixture row requires the exact authenticated Game Session association");
    }
    var capability = evidence.capability();
    var source = evidence.sourceReceipt().sourceEvidence();
    var descriptor = evidence.launchDescriptor();
    if (row == null
        || !evidence.bindingDigest(request, 1L).equals(fixtureBindingSha256)
        || !identity.canonicalGameInstanceId().equals(gameSession.gameInstanceUuid())
        || !identity.targetNamespace().equals(gameSession.request().targetNamespace())
        || !identity.canonicalTenantId().equals(gameSession.canonicalTenantId())
        || !identity.worldSlug().equals(source.worldSlug())
        || !identity.playableStateNamespaceId().equals(gameSession.playableStateNamespaceId())
        || !identity.playableStateScope().equals(capability.stateScope())
        || identity.publicProduction() != capability.publicProductionRealm()
        || !identity.canonicalRealmId().equals(gameSession.canonicalRealmId())
        || identity.catalogRevision() != gameSession.catalogRevision()
        || !identity.controlPlaneRequestId().equals(capability.operationId())
        || !descriptor.targetNamespace().equals(source.targetNamespace())
        || !descriptor.canonicalTenantId().equals(source.canonicalTenantId())
        || !descriptor.worldSlug().equals(source.worldSlug())
        || !descriptor.authoredWorldSourceOperationId().equals(source.operationId())
        || !descriptor.authoredWorldSourceEvidenceDigest().equals(source.evidenceDigest())
        || !evidence
            .fixtureProvenanceDigest()
            .equals(gameSession.fixturePolicySeal().evidenceDigest())
        || !Objects.equals(row.get(INSTANCE_ID), worldInstanceId)
        || !Objects.equals(row.get(INSTANCE_TENANT_ID), worldPrivateKeys.tenantId())
        || !Objects.equals(row.get(INSTANCE_GAME_INSTANCE_ID), worldPrivateKeys.gameInstanceId())
        || !Objects.equals(row.get(INSTANCE_VERSION_ID), worldPrivateKeys.versionId())
        || !Objects.equals(row.get(INSTANCE_GAME_TEMPLATE_ID), capability.gameTemplateId())
        || !Objects.equals(
            row.get(INSTANCE_CONTROL_PLANE_REQUEST_ID), identity.controlPlaneRequestId())
        || !Objects.equals(row.get(INSTANCE_LAUNCH_DESCRIPTOR_ID), descriptor.launchDescriptorId())
        || !Objects.equals(
            row.get(INSTANCE_SCRIPT_PATCH_VERSION),
            descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null)
        || !Objects.equals(row.get(INSTANCE_RUNTIME_FLAGS_JSON), descriptor.runtimeFlagsJson())
        || !Objects.equals(
            row.get(INSTANCE_GENERATION_CONFIG_REVISION), descriptor.generationConfigRevision())
        || !Objects.equals(row.get(INSTANCE_RELEASE_BUNDLE_ID), descriptor.releaseBundleId())
        || !Objects.equals(
            row.get(INSTANCE_PUBLISHED_RELEASE_BUNDLE_REF), descriptor.publishedReleaseBundleRef())
        || !Objects.equals(row.get(INSTANCE_VERSION_STATE_EPOCH), descriptor.versionStateEpoch())
        || !Objects.equals(
            row.get(INSTANCE_REMAP_SET_ID),
            descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null)
        || !Objects.equals(
            row.get(INSTANCE_CANONICAL_GAME_INSTANCE_ID), identity.canonicalGameInstanceId())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_TARGET_NAMESPACE), identity.targetNamespace())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_TENANT_ID), identity.canonicalTenantId())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_WORLD_SLUG), identity.worldSlug())
        || !Objects.equals(
            row.get(INSTANCE_PLAYABLE_STATE_NAMESPACE_ID), identity.playableStateNamespaceId())
        || !Objects.equals(row.get(INSTANCE_PLAYABLE_STATE_SCOPE), identity.playableStateScope())
        || !Objects.equals(row.get(INSTANCE_PUBLIC_PRODUCTION), identity.publicProduction())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_REALM_ID), identity.canonicalRealmId())
        || !Objects.equals(row.get(INSTANCE_CATALOG_REVISION), identity.catalogRevision())
        || row.get(INSTANCE_PLAYTEST_LIFECYCLE_ID) != null
        || row.get(INSTANCE_PLAYTEST_STATE_GENERATION) != null
        || row.get(INSTANCE_LAUNCH_BINDING_OPERATION_ID) != null
        || !"RUN_OWNED_FIXTURE".equals(row.get(INSTANCE_CANONICAL_AUTHORITY_KIND))
        || !Objects.equals(
            row.get(INSTANCE_FIXTURE_PROVENANCE_DIGEST), evidence.fixtureProvenanceDigest())
        || !Objects.equals(
            row.get(INSTANCE_GAME_SESSION_TENANT_ID), gameSession.gameSessionTenantId())
        || !Objects.equals(
            row.get(INSTANCE_GAME_SESSION_GAME_INSTANCE_ID), gameSession.gameInstanceId())
        || !Objects.equals(row.get(INSTANCE_GAME_DESIGN_VERSION_ID), descriptor.versionId())
        || !Objects.equals(row.get(INSTANCE_FIXTURE_BINDING_SHA256), fixtureBindingSha256)
        || !Objects.equals(row.get(INSTANCE_FIXTURE_PREPARING_EPOCH), 1L)
        || !Objects.equals(row.get(INSTANCE_LIFECYCLE_EPOCH), 1L)
        || !"PREPARING".equals(row.get(INSTANCE_STATUS))) {
      throw new InvalidAssociationEvidenceException(
          "World run-owned fixture row differs from the exact authenticated Game Session, source, descriptor, or original preparing identity");
    }
  }

  static AuthoredWorldLaunchDescriptorEvidence runOwnedFixtureDescriptor(
      WorldCanonicalInstanceAssociation.RunOwnedFixture fixture) {
    var policy = fixture.gameSessionAssociation().fixturePolicyRequest();
    var source = policy.sourceEvidence();
    var capability = policy.capability();
    return AuthoredWorldLaunchDescriptorGrpcCodec.fromLaunchDescriptorMessage(
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            source.targetNamespace(),
            capability.operationId(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            capability.gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null),
        policy.launchReadEvidence().launchDescriptor());
  }

  private long nextPositiveSequenceValue(String sequenceName) {
    if (!"world_instance_id_seq".equals(sequenceName)
        && !"world_canonical_private_game_instance_key_seq".equals(sequenceName)) {
      throw new IllegalArgumentException("Unsupported World fixture allocation sequence");
    }
    Record sequenceRow = dsl.fetchOne("SELECT nextval('" + sequenceName + "') AS value");
    Long value = sequenceRow == null ? null : sequenceRow.get("value", Long.class);
    if (value == null || value <= 0) {
      throw new InvalidAssociationEvidenceException(
          "World run-owned fixture allocation sequence returned an invalid private key");
    }
    return value;
  }

  private void requireExactImmutableOwnerEvidence(WorldCanonicalInstanceAssociation.Claim claim) {
    var sourceReceipt = claim.completeLaunchBinding().sourceIntakeReceipt();
    var evidence = claim.completeLaunchBinding().evidence();
    var descriptor = evidence.descriptor();
    var release = evidence.releaseAttestation();

    Record binding =
        dsl.selectFrom(COMPLETE_BINDING)
            .where(BINDING_OPERATION_ID.eq(claim.launchBindingOperationId()))
            .fetchOne();
    if (binding == null
        || !Objects.equals(
            binding.get(BINDING_TARGET_NAMESPACE, String.class), claim.targetNamespace())
        || !Objects.equals(
            binding.get(BINDING_CANONICAL_TENANT_ID, UUID.class), claim.canonicalTenantId())
        || !Objects.equals(
            binding.get(BINDING_CANONICAL_VERSION_ID, UUID.class), claim.canonicalVersionId())
        || !Objects.equals(binding.get(BINDING_WORLD_SLUG, String.class), claim.worldSlug())
        || !Objects.equals(
            binding.get(BINDING_CONTROL_PLANE_REQUEST_ID, String.class),
            claim.controlPlaneRequestId())
        || !Objects.equals(
            binding.get(BINDING_INTAKE_OPERATION_ID, UUID.class), sourceReceipt.operationId())
        || !Objects.equals(
            binding.get(BINDING_INTAKE_REQUEST_ID, UUID.class), sourceReceipt.intakeRequestId())
        || !Objects.equals(
            binding.get(BINDING_LOCAL_TENANT_KEY, Long.class), sourceReceipt.localTenantKey())
        || !Objects.equals(
            binding.get(BINDING_SOURCE_OPERATION_ID, UUID.class), sourceReceipt.sourceOperationId())
        || !Objects.equals(
            binding.get(BINDING_SOURCE_EVIDENCE_DIGEST, String.class),
            sourceReceipt.sourceEvidenceDigest())
        || !Objects.equals(
            binding.get(BINDING_INTAKE_RECEIPT_DIGEST, String.class), sourceReceipt.receiptDigest())
        || !Objects.equals(
            binding.get(BINDING_DESCRIPTOR_REQUEST_DIGEST, String.class),
            descriptor.requestDigest())
        || !Objects.equals(
            binding.get(BINDING_DESCRIPTOR_RESULT_DIGEST, String.class), descriptor.resultDigest())
        || !Objects.equals(
            binding.get(BINDING_RELEASE_ATTESTATION_DIGEST, String.class), release.evidenceDigest())
        || !Objects.equals(
            binding.get(BINDING_DESCRIPTOR_JSON, String.class), writeJson(descriptor))
        || !Objects.equals(
            binding.get(BINDING_RELEASE_ATTESTATION_JSON, String.class), writeJson(release))) {
      throw new RegistrationConflictException(
          "Canonical instance claim differs from exact immutable V26 descriptor/release bytes");
    }

    Record source =
        dsl.selectFrom(SOURCE_INTAKE)
            .where(SOURCE_OPERATION_ID_KEY.eq(sourceReceipt.operationId()))
            .fetchOne();
    requireExactSourceColumns(source, sourceReceipt);

    WorldAuthoredVersionIdentityReceipt identity = claim.versionIdentity();
    AuthoredWorldVersionStateEvidence versionEvidence = identity.versionStateEvidence();
    Record version =
        dsl.selectFrom(VERSION_IDENTITY)
            .where(VERSION_OPERATION_ID.eq(identity.operationId()))
            .fetchOne();
    if (version == null
        || !Objects.equals(
            version.get(VERSION_TARGET_NAMESPACE, String.class), identity.targetNamespace())
        || !Objects.equals(
            version.get(VERSION_CANONICAL_TENANT_ID, UUID.class), identity.canonicalTenantId())
        || !Objects.equals(version.get(VERSION_WORLD_SLUG, String.class), identity.worldSlug())
        || !Objects.equals(
            version.get(VERSION_CANONICAL_VERSION_ID, UUID.class), identity.canonicalVersionId())
        || !Objects.equals(
            version.get(VERSION_GAME_DESIGN_VERSION_ID, Long.class), identity.gameDesignVersionId())
        || !Objects.equals(
            version.get(VERSION_LOCAL_VERSION_KEY, Long.class), identity.localVersionKey())
        || !Objects.equals(
            version.get(VERSION_INTAKE_OPERATION_ID, UUID.class), sourceReceipt.operationId())
        || !Objects.equals(
            version.get(VERSION_INTAKE_REQUEST_ID, UUID.class), sourceReceipt.intakeRequestId())
        || !Objects.equals(
            version.get(VERSION_LOCAL_TENANT_KEY, Long.class), sourceReceipt.localTenantKey())
        || !Objects.equals(
            version.get(VERSION_INTAKE_REQUEST_DIGEST, String.class), sourceReceipt.requestDigest())
        || !Objects.equals(
            version.get(VERSION_SOURCE_OPERATION_ID, UUID.class), sourceReceipt.sourceOperationId())
        || !Objects.equals(
            version.get(VERSION_SOURCE_EVIDENCE_DIGEST, String.class),
            sourceReceipt.sourceEvidenceDigest())
        || !Objects.equals(
            version.get(VERSION_INTAKE_RECEIPT_DIGEST, String.class), sourceReceipt.receiptDigest())
        || !Objects.equals(
            version.get(VERSION_STATE_SCHEMA_VERSION, Short.class),
            (short) versionEvidence.request().schemaVersion())
        || !Objects.equals(
            version.get(VERSION_STATE_READ_REQUEST_ID, UUID.class),
            versionEvidence.request().readRequestId())
        || !Objects.equals(
            version.get(VERSION_STATE_STATE, String.class),
            versionEvidence.versionState().name().replace("VERSION_LIFECYCLE_STATE_", ""))
        || !Objects.equals(
            version.get(VERSION_STATE_EVIDENCE_EPOCH, Long.class),
            versionEvidence.versionStateEpoch())
        || !Objects.equals(
            version.get(VERSION_STATE_EVIDENCE_DIGEST, String.class),
            versionEvidence.evidenceDigest())
        || !Objects.equals(
            version.get(VERSION_STATE_EVIDENCE_JSON, String.class), writeJson(versionEvidence))) {
      throw new RegistrationConflictException(
          "Canonical instance claim differs from exact immutable V27 Version/source bytes");
    }
  }

  private static void requireExactSourceColumns(
      Record row, WorldAuthoredSourceIntakeReceipt receipt) {
    if (row == null) {
      throw new RegistrationConflictException(
          "Canonical instance claim lost its original source intake");
    }
    AuthoredWorldSourceEvidence source = receipt.source();
    if (!Objects.equals(
            row.get(SOURCE_SCHEMA_VERSION, Short.class), (short) receipt.schemaVersion())
        || !Objects.equals(
            row.get(SOURCE_TARGET_NAMESPACE, String.class), receipt.targetNamespace())
        || !Objects.equals(row.get(SOURCE_INTAKE_REQUEST_ID, UUID.class), receipt.intakeRequestId())
        || !Objects.equals(row.get(SOURCE_REQUEST_DIGEST, String.class), receipt.requestDigest())
        || !Objects.equals(row.get(SOURCE_LOCAL_TENANT_KEY, Long.class), receipt.localTenantKey())
        || !Objects.equals(
            row.get(SOURCE_EVIDENCE_SCHEMA_VERSION, Short.class), (short) source.schemaVersion())
        || !Objects.equals(
            row.get(SOURCE_REGISTRATION_REQUEST_ID, UUID.class), source.registrationRequestId())
        || !Objects.equals(row.get(SOURCE_SOURCE_OPERATION_ID, UUID.class), source.operationId())
        || !Objects.equals(
            row.get(SOURCE_SOURCE_REQUEST_DIGEST, String.class), source.requestDigest())
        || !Objects.equals(
            row.get(SOURCE_CANONICAL_TENANT_ID, UUID.class), source.canonicalTenantId())
        || !Objects.equals(row.get(SOURCE_TENANT_SLUG, String.class), source.tenantSlug())
        || !Objects.equals(row.get(SOURCE_WORLD_SLUG, String.class), source.worldSlug())
        || !Objects.equals(
            row.get(SOURCE_WORLD_DISPLAY_NAME, String.class), source.worldDisplayName())
        || !Objects.equals(row.get(SOURCE_GAME_ROW_ID, Long.class), source.sourceGameRowId())
        || !Objects.equals(
            row.get(SOURCE_GAME_TENANT_KEY, String.class), source.sourceGameTenantKey())
        || !Objects.equals(row.get(SOURCE_PROVENANCE_KIND, String.class), source.provenanceKind())
        || !Objects.equals(
            row.get(SOURCE_SOURCE_EVIDENCE_DIGEST, String.class), source.evidenceDigest())
        || !Objects.equals(row.get(SOURCE_RECEIPT_DIGEST, String.class), receipt.receiptDigest())) {
      throw new RegistrationConflictException(
          "Canonical instance claim differs from exact immutable World source intake fields");
    }
  }

  private void requireSameStoredClaim(
      Record stored,
      WorldCanonicalInstanceAssociation.Claim claim,
      WorldCanonicalInstanceAssociation.WorldPrepareFields prepareFields) {
    requireStoredAssociationColumns(
        stored,
        claim.identity(),
        claim.worldInstanceId(),
        claim.completeLaunchBinding(),
        claim.versionIdentity(),
        prepareFields);
    WorldCanonicalInstanceAssociation.WorldPrepareFields actual =
        toWorldPrepareFields(readWorldInstance(claim.worldInstanceId()));
    if (!actual.equals(prepareFields)) {
      throw new InvalidAssociationEvidenceException(
          "World canonical association row changed during exact owner retry");
    }
  }

  private static void requireStoredAssociationColumns(
      Record row,
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      long worldInstanceId,
      WorldCompleteLaunchBindingReceipt completeLaunchBinding,
      WorldAuthoredVersionIdentityReceipt versionIdentity,
      WorldCanonicalInstanceAssociation.WorldPrepareFields prepareFields) {
    var source = completeLaunchBinding.sourceIntakeReceipt();
    var descriptor = completeLaunchBinding.descriptor();
    var release = completeLaunchBinding.evidence().releaseAttestation();
    if (required(row, SCHEMA_VERSION) != 2
        || !Objects.equals(
            required(row, CANONICAL_GAME_INSTANCE_ID), identity.canonicalGameInstanceId())
        || !Objects.equals(required(row, CANONICAL_TARGET_NAMESPACE), identity.targetNamespace())
        || !Objects.equals(required(row, CANONICAL_TENANT_ID), identity.canonicalTenantId())
        || !Objects.equals(required(row, CANONICAL_WORLD_SLUG), identity.worldSlug())
        || !Objects.equals(
            required(row, PLAYABLE_STATE_NAMESPACE_ID), identity.playableStateNamespaceId())
        || !Objects.equals(required(row, PLAYABLE_STATE_SCOPE), identity.playableStateScope())
        || !Objects.equals(required(row, PUBLIC_PRODUCTION), identity.publicProduction())
        || !Objects.equals(required(row, CANONICAL_REALM_ID), identity.canonicalRealmId())
        || !Objects.equals(required(row, CATALOG_REVISION), identity.catalogRevision())
        || row.get(PLAYTEST_LIFECYCLE_ID) != null
        || row.get(PLAYTEST_STATE_GENERATION) != null
        || !Objects.equals(
            required(row, CONTROL_PLANE_REQUEST_ID), identity.controlPlaneRequestId())
        || !Objects.equals(required(row, WORLD_INSTANCE_ID), worldInstanceId)
        || !Objects.equals(
            required(row, LAUNCH_BINDING_OPERATION_ID), completeLaunchBinding.operationId())
        || !Objects.equals(
            required(row, VERSION_IDENTITY_OPERATION_ID), versionIdentity.operationId())
        || !Objects.equals(required(row, CANONICAL_VERSION_ID), release.canonicalVersionId())
        || !Objects.equals(required(row, LOCAL_TENANT_KEY), prepareFields.privateTenantKey())
        || !Objects.equals(
            required(row, PRIVATE_GAME_INSTANCE_KEY), prepareFields.privateGameInstanceKey())
        || !Objects.equals(required(row, GAME_TEMPLATE_ID), prepareFields.gameTemplateId())
        || !Objects.equals(required(row, LAUNCH_DESCRIPTOR_ID), prepareFields.launchDescriptorId())
        || !Objects.equals(required(row, LOCAL_VERSION_KEY), prepareFields.localVersionKey())
        || !Objects.equals(row.get(SCRIPT_PATCH_VERSION), prepareFields.scriptPatchVersion())
        || !Objects.equals(required(row, RUNTIME_FLAGS_JSON), prepareFields.runtimeFlagsJson())
        || !Objects.equals(
            required(row, GENERATION_CONFIG_REVISION), prepareFields.generationConfigRevision())
        || !Objects.equals(required(row, RELEASE_BUNDLE_ID), prepareFields.releaseBundleId())
        || !Objects.equals(
            required(row, PUBLISHED_RELEASE_BUNDLE_REF), prepareFields.publishedReleaseBundleRef())
        || !Objects.equals(required(row, VERSION_STATE_EPOCH), prepareFields.versionStateEpoch())
        || !Objects.equals(row.get(REMAP_SET_ID), prepareFields.remapSetId())
        || !Objects.equals(required(row, INTAKE_OPERATION_ID), source.operationId())
        || !Objects.equals(required(row, INTAKE_REQUEST_ID), source.intakeRequestId())
        || !Objects.equals(required(row, SOURCE_OPERATION_ID), source.sourceOperationId())
        || !Objects.equals(required(row, SOURCE_EVIDENCE_DIGEST), source.sourceEvidenceDigest())
        || !Objects.equals(required(row, INTAKE_REQUEST_DIGEST), source.requestDigest())
        || !Objects.equals(required(row, INTAKE_RECEIPT_DIGEST), source.receiptDigest())
        || !Objects.equals(required(row, DESCRIPTOR_REQUEST_DIGEST), descriptor.requestDigest())
        || !Objects.equals(required(row, DESCRIPTOR_RESULT_DIGEST), descriptor.resultDigest())
        || !Objects.equals(required(row, RELEASE_ATTESTATION_DIGEST), release.evidenceDigest())) {
      throw new RegistrationConflictException(
          "Canonical Game Instance, World row, request, source, Version, descriptor, or release binding conflicts");
    }
  }

  private void requireInsertBornIdentity(
      Record row, WorldCanonicalInstanceAssociation.Claim claim) {
    requireInsertBornIdentity(row, claim.identity(), claim.launchBindingOperationId());
  }

  private void requireInsertBornIdentity(
      Record row,
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      UUID launchBindingOperationId) {
    if (row == null
        || !Objects.equals(
            row.get(INSTANCE_CANONICAL_GAME_INSTANCE_ID), identity.canonicalGameInstanceId())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_TARGET_NAMESPACE), identity.targetNamespace())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_TENANT_ID), identity.canonicalTenantId())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_WORLD_SLUG), identity.worldSlug())
        || !Objects.equals(
            row.get(INSTANCE_PLAYABLE_STATE_NAMESPACE_ID), identity.playableStateNamespaceId())
        || !Objects.equals(row.get(INSTANCE_PLAYABLE_STATE_SCOPE), identity.playableStateScope())
        || !Objects.equals(row.get(INSTANCE_PUBLIC_PRODUCTION), identity.publicProduction())
        || !Objects.equals(row.get(INSTANCE_CANONICAL_REALM_ID), identity.canonicalRealmId())
        || !Objects.equals(row.get(INSTANCE_CATALOG_REVISION), identity.catalogRevision())
        || row.get(INSTANCE_PLAYTEST_LIFECYCLE_ID) != null
        || row.get(INSTANCE_PLAYTEST_STATE_GENERATION) != null
        || !Objects.equals(
            row.get(INSTANCE_LAUNCH_BINDING_OPERATION_ID), launchBindingOperationId)) {
      throw new InvalidAssociationEvidenceException(
          "World row has no matching insert-born canonical prepare identity; retained numeric rows cannot be associated");
    }
  }

  private Record lockWorldInstance(long worldInstanceId) {
    Record row =
        dsl.selectFrom(WORLD_INSTANCE)
            .where(INSTANCE_ID.eq(worldInstanceId))
            .forUpdate()
            .fetchOne();
    if (row == null) {
      throw new InvalidAssociationEvidenceException(
          "Canonical instance association requires an already-created World instance row");
    }
    return row;
  }

  private Record readWorldInstance(long worldInstanceId) {
    Record row = dsl.selectFrom(WORLD_INSTANCE).where(INSTANCE_ID.eq(worldInstanceId)).fetchOne();
    if (row == null) {
      throw new InvalidAssociationEvidenceException(
          "Canonical instance association lost its exact World instance row");
    }
    return row;
  }

  private WorldCanonicalInstanceAssociation.WorldPrepareFields toWorldPrepareFields(Record row) {
    if (row == null) {
      throw new InvalidAssociationEvidenceException("World instance row is missing");
    }
    try {
      return new WorldCanonicalInstanceAssociation.WorldPrepareFields(
          required(row, INSTANCE_ID),
          required(row, INSTANCE_TENANT_ID),
          required(row, INSTANCE_GAME_INSTANCE_ID),
          required(row, INSTANCE_GAME_TEMPLATE_ID),
          required(row, INSTANCE_CONTROL_PLANE_REQUEST_ID),
          required(row, INSTANCE_LAUNCH_DESCRIPTOR_ID),
          required(row, INSTANCE_VERSION_ID),
          row.get(INSTANCE_SCRIPT_PATCH_VERSION),
          required(row, INSTANCE_RUNTIME_FLAGS_JSON),
          required(row, INSTANCE_GENERATION_CONFIG_REVISION),
          required(row, INSTANCE_RELEASE_BUNDLE_ID),
          required(row, INSTANCE_PUBLISHED_RELEASE_BUNDLE_REF),
          required(row, INSTANCE_VERSION_STATE_EPOCH),
          row.get(INSTANCE_REMAP_SET_ID));
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidAssociationEvidenceException) {
        throw exception;
      }
      throw new InvalidAssociationEvidenceException(
          "World instance prepare fields are incomplete or invalid", exception);
    }
  }

  private Record findConflict(WorldCanonicalInstanceAssociation.Claim claim) {
    Record row = findByCanonicalGameInstance(claim.canonicalGameInstanceId());
    if (row == null) {
      row =
          dsl.selectFrom(ASSOCIATION)
              .where(
                  CANONICAL_TARGET_NAMESPACE
                      .eq(claim.targetNamespace())
                      .and(CANONICAL_TENANT_ID.eq(claim.canonicalTenantId()))
                      .and(CONTROL_PLANE_REQUEST_ID.eq(claim.controlPlaneRequestId())))
              .fetchOne();
    }
    if (row == null) {
      row =
          dsl.selectFrom(ASSOCIATION)
              .where(WORLD_INSTANCE_ID.eq(claim.worldInstanceId()))
              .fetchOne();
    }
    if (row == null) {
      row =
          dsl.selectFrom(ASSOCIATION)
              .where(LAUNCH_BINDING_OPERATION_ID.eq(claim.launchBindingOperationId()))
              .fetchOne();
    }
    return row;
  }

  private Record findByCanonicalGameInstance(UUID canonicalGameInstanceId) {
    return dsl.selectFrom(ASSOCIATION)
        .where(CANONICAL_GAME_INSTANCE_ID.eq(canonicalGameInstanceId))
        .fetchOne();
  }

  private static String writeJson(Object value) {
    try {
      return CLOSED_JSON.writeValueAsString(value);
    } catch (Exception exception) {
      throw new InvalidAssociationEvidenceException(
          "Immutable World owner evidence could not be encoded for exact comparison", exception);
    }
  }

  private static <T> T required(Record row, Field<T> field) {
    return Objects.requireNonNull(row.get(field), "Persisted " + field.getName() + " is null");
  }

  private static <T> Field<T> field(String name, Class<T> type) {
    return DSL.field(DSL.name(name), type);
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(label + " requires an independent committed owner read");
    }
  }

  private void requireReadOnlyRepeatableReadOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World canonical association snapshot read requires a read-only REPEATABLE READ owner transaction");
    }
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"),
            "World transaction state query returned no row");
    if (!"repeatable read".equals(state.get("isolation", String.class))
        || !"on".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "World canonical association snapshot read requires a read-only REPEATABLE READ owner transaction");
    }
  }

  private void requireWritableActivationTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World canonical association activation read requires a writable READ COMMITTED owner transaction");
    }
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"),
            "World transaction state query returned no row");
    if (!"read committed".equals(state.get("isolation", String.class))
        || !"off".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "World canonical association activation read requires a writable READ COMMITTED owner transaction");
    }
  }

  private static void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World canonical instance association requires an active World owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World canonical instance association requires a writable owner transaction");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation == null || declaredIsolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException(
          "World canonical instance association requires READ COMMITTED isolation");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  public static final class RegistrationConflictException extends IllegalStateException {
    public RegistrationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidAssociationEvidenceException extends IllegalStateException {
    public InvalidAssociationEvidenceException(String message) {
      super(message);
    }

    public InvalidAssociationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
