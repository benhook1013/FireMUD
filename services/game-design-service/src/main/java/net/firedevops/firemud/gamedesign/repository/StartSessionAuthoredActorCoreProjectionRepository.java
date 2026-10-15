package net.firedevops.firemud.gamedesign.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreSourceBinding;
import net.firedevops.firemud.gamedesign.repository.StartSessionLaunchDescriptorRepository.Binding;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Insert-only authored actor-core proofs owned by one retained StartSession descriptor binding. */
public final class StartSessionAuthoredActorCoreProjectionRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Table<?> TABLE =
      DSL.table(DSL.name("game_design_start_session_authored_actor_core_projection"));
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> CONTROL_PLANE_REQUEST_ID =
      DSL.field(DSL.name("control_plane_request_id"), String.class);
  private static final Field<Long> DESCRIPTOR_ROW_ID =
      DSL.field(DSL.name("launch_descriptor_row_id"), Long.class);
  private static final Field<Integer> POLICY_ORDER =
      DSL.field(DSL.name("policy_order"), Integer.class);
  private static final Field<UUID> BINDING_ID = DSL.field(DSL.name("binding_id"), UUID.class);
  private static final Field<byte[]> SOURCE_BINDING_BYTES =
      DSL.field(DSL.name("source_binding_bytes"), byte[].class);
  private static final Field<String> SOURCE_BINDING_DIGEST =
      DSL.field(DSL.name("source_binding_digest"), String.class);
  private static final Field<byte[]> PROJECTION_BYTES =
      DSL.field(DSL.name("projection_bytes"), byte[].class);
  private static final Field<String> PROJECTION_DIGEST =
      DSL.field(DSL.name("projection_digest"), String.class);
  private static final Field<Timestamp> CREATED_AT =
      DSL.field(DSL.name("created_at"), Timestamp.class);

  private final DSLContext dsl;

  public StartSessionAuthoredActorCoreProjectionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Allocates each Game Design binding identity only for a new owner row, inside its transaction.
   * The caller must first complete its exact retained-owner retry check.
   */
  public List<AuthoredInitialActorCoreProjection> insertForNewOwner(
      Binding owner, List<AuthoredInitialActorCoreSourceBinding> sourceBindings) {
    requireTransaction();
    requireOwner(owner);
    List<AuthoredInitialActorCoreSourceBinding> sources =
        List.copyOf(Objects.requireNonNull(sourceBindings, "sourceBindings"));
    if (!findByOwner(owner).isEmpty()) {
      throw new RetainedProjectionConflictException(
          "Authored actor-core projection rows already exist for a new descriptor owner");
    }

    for (int index = 0; index < sources.size(); index++) {
      AuthoredInitialActorCoreSourceBinding source =
          requireSourceMatchesOwner(owner, sources.get(index));
      UUID bindingId = UUID.randomUUID();
      AuthoredInitialActorCoreProjection projection =
          AuthoredInitialActorCoreProjection.fromValidatedBinding(bindingId, source);
      byte[] sourceBytes = source.canonicalBytes();
      String sourceDigest = source.digest();
      byte[] projectionBytes = projection.canonicalBytes();

      int inserted =
          dsl.insertInto(TABLE)
              .set(TARGET_NAMESPACE, owner.targetNamespace())
              .set(CANONICAL_TENANT_ID, owner.canonicalTenantId())
              .set(CONTROL_PLANE_REQUEST_ID, owner.controlPlaneRequestId())
              .set(DESCRIPTOR_ROW_ID, owner.descriptorRowId())
              .set(POLICY_ORDER, index)
              .set(BINDING_ID, bindingId)
              .set(SOURCE_BINDING_BYTES, sourceBytes)
              .set(SOURCE_BINDING_DIGEST, sourceDigest)
              .set(PROJECTION_BYTES, projectionBytes)
              .set(PROJECTION_DIGEST, projection.digest())
              .onConflictDoNothing()
              .execute();
      if (inserted != 1) {
        throw new RetainedProjectionConflictException(
            "Authored actor-core projection identity conflicted during new owner insertion");
      }
    }

    return validateReplay(findByOwner(owner), sources, owner);
  }

  /** Replays only a complete exact retained proof set; this method never repairs missing rows. */
  public List<AuthoredInitialActorCoreProjection> replayExact(
      Binding owner, List<AuthoredInitialActorCoreSourceBinding> sourceBindings) {
    requireOwner(owner);
    return validateReplay(
        findByOwner(owner),
        List.copyOf(Objects.requireNonNull(sourceBindings, "sourceBindings")),
        owner);
  }

  public List<RetainedProjection> findByOwner(Binding owner) {
    requireOwner(owner);
    return List.copyOf(
        dsl.selectFrom(TABLE)
            .where(
                TARGET_NAMESPACE
                    .eq(owner.targetNamespace())
                    .and(CANONICAL_TENANT_ID.eq(owner.canonicalTenantId()))
                    .and(CONTROL_PLANE_REQUEST_ID.eq(owner.controlPlaneRequestId()))
                    .and(DESCRIPTOR_ROW_ID.eq(owner.descriptorRowId())))
            .orderBy(POLICY_ORDER.asc())
            .fetch(this::toRetainedProjection));
  }

  static List<AuthoredInitialActorCoreProjection> validateReplay(
      List<RetainedProjection> retained,
      List<AuthoredInitialActorCoreSourceBinding> sourceBindings,
      Binding owner) {
    Objects.requireNonNull(retained, "retained");
    List<AuthoredInitialActorCoreSourceBinding> sources =
        List.copyOf(Objects.requireNonNull(sourceBindings, "sourceBindings"));
    requireOwner(owner);
    if (sources.isEmpty()) {
      if (!retained.isEmpty()) {
        throw new RetainedProjectionConflictException(
            "Retained authored actor-core proofs differ from the exact source policy set");
      }
      return List.of();
    }
    if (retained.isEmpty()) {
      throw new MissingRetainedProjectionException(
          "Retained descriptor has no authored actor-core proof for its applicable policies");
    }
    if (retained.size() != sources.size()) {
      throw new RetainedProjectionConflictException(
          "Retained authored actor-core proof count differs from the exact source policy set");
    }

    Set<UUID> bindingIds = new HashSet<>();
    List<AuthoredInitialActorCoreProjection> result = new ArrayList<>(sources.size());
    for (int index = 0; index < sources.size(); index++) {
      RetainedProjection row = retained.get(index);
      AuthoredInitialActorCoreSourceBinding source =
          requireSourceMatchesOwner(owner, sources.get(index));
      if (row.policyOrder() != index
          || !owner.targetNamespace().equals(row.targetNamespace())
          || !owner.canonicalTenantId().equals(row.canonicalTenantId())
          || !owner.controlPlaneRequestId().equals(row.controlPlaneRequestId())
          || !owner.descriptorRowId().equals(row.descriptorRowId())
          || !bindingIds.add(row.bindingId())) {
        throw new RetainedProjectionConflictException(
            "Retained authored actor-core proof owner identity or order changed");
      }

      AuthoredInitialActorCoreSourceBinding storedSource;
      AuthoredInitialActorCoreProjection projection;
      try {
        storedSource =
            AuthoredInitialActorCoreSourceBinding.fromStored(
                row.sourceBindingBytes(), row.sourceBindingDigest());
        projection =
            AuthoredInitialActorCoreProjection.fromStored(
                row.projectionBytes(), row.projectionDigest());
      } catch (IllegalArgumentException | IllegalStateException corrupt) {
        throw new RetainedProjectionConflictException(
            "Retained authored actor-core proof bytes or digests are corrupt", corrupt);
      }
      if (!Arrays.equals(storedSource.canonicalBytes(), source.canonicalBytes())
          || !storedSource.digest().equals(source.digest())
          || !projection.bindingId().equals(row.bindingId())
          || !projection.bindingDigest().equals(source.digest())) {
        throw new RetainedProjectionConflictException(
            "Retained authored actor-core private source proof differs from the exact source");
      }

      AuthoredInitialActorCoreProjection expected =
          AuthoredInitialActorCoreProjection.fromValidatedBinding(row.bindingId(), source);
      if (!Arrays.equals(expected.canonicalBytes(), row.projectionBytes())
          || !expected.digest().equals(row.projectionDigest())
          || !Arrays.equals(projection.canonicalBytes(), expected.canonicalBytes())) {
        throw new RetainedProjectionConflictException(
            "Retained authored actor-core projection differs from the exact private source");
      }
      result.add(projection);
    }
    return List.copyOf(result);
  }

  private RetainedProjection toRetainedProjection(Record row) {
    Timestamp createdAt = row.get(CREATED_AT, Timestamp.class);
    return new RetainedProjection(
        row.get(TARGET_NAMESPACE),
        row.get(CANONICAL_TENANT_ID),
        row.get(CONTROL_PLANE_REQUEST_ID),
        row.get(DESCRIPTOR_ROW_ID),
        row.get(POLICY_ORDER),
        row.get(BINDING_ID),
        row.get(SOURCE_BINDING_BYTES),
        row.get(SOURCE_BINDING_DIGEST),
        row.get(PROJECTION_BYTES),
        row.get(PROJECTION_DIGEST),
        createdAt == null ? null : createdAt.toLocalDateTime());
  }

  private static AuthoredInitialActorCoreSourceBinding requireSourceMatchesOwner(
      Binding owner, AuthoredInitialActorCoreSourceBinding source) {
    Objects.requireNonNull(source, "source binding");
    AuthoredInitialActorCoreSourceBinding decoded;
    try {
      decoded =
          AuthoredInitialActorCoreSourceBinding.fromStored(
              source.canonicalBytes(), source.digest());
    } catch (IllegalArgumentException | IllegalStateException corrupt) {
      throw new RetainedProjectionConflictException(
          "Authored actor-core source binding failed complete canonical reconstruction", corrupt);
    }
    var selected = decoded.selectedCommit();
    if (!Objects.equals(owner.canonicalTenantId(), selected.target().canonicalTenantId())
        || !Objects.equals(owner.canonicalVersionId(), selected.target().canonicalVersionId())
        || !Objects.equals(owner.selectedCommitId(), selected.commitId())
        || !Objects.equals(owner.sourceOperationId(), decoded.worldSourceOperationId())
        || !Objects.equals(owner.sourceEvidenceDigest(), decoded.worldSourceEvidenceDigest())
        || !Objects.equals(owner.worldSlug(), decoded.expectedWorldSlug())
        || !Objects.equals(owner.publishWorkflowId(), decoded.publicationWorkflowId())
        || owner.releaseBundleId() == null
        || owner.releaseBundleId() <= 0L
        || !Arrays.equals(decoded.canonicalBytes(), source.canonicalBytes())
        || !decoded.digest().equals(source.digest())) {
      throw new RetainedProjectionConflictException(
          "Authored actor-core source binding differs from the exact descriptor owner identity");
    }
    return decoded;
  }

  private static void requireOwner(Binding owner) {
    Objects.requireNonNull(owner, "owner binding");
    if (owner.targetNamespace() == null
        || owner.targetNamespace().isBlank()
        || owner.canonicalTenantId() == null
        || owner.controlPlaneRequestId() == null
        || owner.controlPlaneRequestId().isBlank()
        || owner.descriptorRowId() == null
        || owner.descriptorRowId() <= 0L
        || owner.releaseBundleId() == null
        || owner.releaseBundleId() <= 0L) {
      throw new IllegalArgumentException("Exact retained descriptor owner binding required");
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored actor-core identity allocation requires the descriptor owner transaction");
    }
  }

  /** Complete immutable source and projection proof keyed to the actual descriptor owner row. */
  public record RetainedProjection(
      String targetNamespace,
      UUID canonicalTenantId,
      String controlPlaneRequestId,
      Long descriptorRowId,
      Integer policyOrder,
      UUID bindingId,
      byte[] sourceBindingBytes,
      String sourceBindingDigest,
      byte[] projectionBytes,
      String projectionDigest,
      LocalDateTime createdAt) {
    public RetainedProjection {
      sourceBindingBytes = cloneBytes(sourceBindingBytes);
      projectionBytes = cloneBytes(projectionBytes);
    }

    @Override
    public byte[] sourceBindingBytes() {
      return cloneBytes(sourceBindingBytes);
    }

    @Override
    public byte[] projectionBytes() {
      return cloneBytes(projectionBytes);
    }

    private static byte[] cloneBytes(byte[] value) {
      return value == null ? null : value.clone();
    }
  }

  public static class RetainedProjectionConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public RetainedProjectionConflictException(String message) {
      super(message);
    }

    public RetainedProjectionConflictException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class MissingRetainedProjectionException
      extends RetainedProjectionConflictException {
    private static final long serialVersionUID = 1L;

    public MissingRetainedProjectionException(String message) {
      super(message);
    }
  }
}
