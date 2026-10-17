package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Shared exact readback checks for a retained original selector and its runtime ROOM mapping. */
final class WorldCanonicalInstanceStartLocationReadback {
  private static final String ORIGINAL_SELECTOR_SQL =
      "SELECT s.receipt_bytes,s.account_binding_bytes,s.graph_digest,a.result_bytes,g.graph_bytes "
          + "FROM world_draft_start_location_receipt s JOIN world_draft_graph_application a "
          + "ON a.operation_id=s.operation_id AND a.request_id=s.request_id AND a.commit_id=s.commit_id "
          + "AND a.authorization_fence_id=s.authorization_fence_id "
          + "JOIN world_topology_draft_commit g ON g.request_id=s.request_id AND g.commit_id=s.commit_id "
          + "WHERE s.operation_id=? AND s.target_namespace=? AND s.canonical_tenant_id=? "
          + "AND s.canonical_version_id=? AND s.room_template_id=?";

  private static final String MAPPED_ROOM_SQL =
      "SELECT s.canonical_game_instance_id,s.world_instance_id,s.canonical_tenant_id, "
          + "s.canonical_version_id,s.room_template_id,s.runtime_room_instance_id,s.receipt_digest, "
          + "s.graph_digest,s.evidence_bytes,m.canonical_game_instance_id AS mapped_game_instance_id, "
          + "m.world_instance_id AS mapped_world_instance_id,m.family AS mapped_family, "
          + "m.runtime_room_instance_id AS mapped_room_instance_id,m.runtime_row_id AS mapped_room_row_id, "
          + "m.template_id AS mapped_template_id,r.room_instance_row_id AS actual_room_instance_id, "
          + "r.tenant_id AS room_tenant_key,r.game_instance_id AS room_game_instance_key, "
          + "ri.tenant_id AS region_tenant_key,ri.game_instance_id AS region_game_instance_key, "
          + "ri.world_instance_id AS room_world_instance_id "
          + "FROM world_canonical_preparation_start_location s "
          + "JOIN world_canonical_instance_topology_identity m ON m.world_instance_id=s.world_instance_id "
          + "AND m.canonical_game_instance_id=s.canonical_game_instance_id AND m.family='ROOM' "
          + "AND m.template_id=s.room_template_id "
          + "JOIN room_instance r ON r.id=m.runtime_row_id "
          + "JOIN region_instance ri ON ri.id=r.region_instance_id "
          + "WHERE s.canonical_game_instance_id=?";

  private WorldCanonicalInstanceStartLocationReadback() {}

  static void requireExactOriginalSelector(
      DSLContext dsl,
      WorldPublishedStartLocationEvidence selector,
      WorldDraftStartLocationEvidence receipt,
      byte[] graphBytes) {
    Record original =
        dsl.fetchOne(
            ORIGINAL_SELECTOR_SQL,
            receipt.operationId(),
            receipt.targetNamespace(),
            receipt.startLocation().tenantId(),
            receipt.startLocation().versionId(),
            receipt.startLocation().roomTemplateId());
    if (original == null
        || !Arrays.equals(
            selector.selectorReceiptBytes(), required(original, "receipt_bytes", byte[].class))
        || !Arrays.equals(
            selector.originalAccountBindingBytes(),
            required(original, "account_binding_bytes", byte[].class))
        || !Arrays.equals(
            selector.appliedResultBytes(), required(original, "result_bytes", byte[].class))
        || !Arrays.equals(graphBytes, required(original, "graph_bytes", byte[].class))
        || !receipt.graphDigest().equals(digest(graphBytes))
        || !receipt.graphDigest().equals(required(original, "graph_digest", String.class))) {
      throw invalid(
          "Canonical preparation selector differs from original World Account/APPLIED/graph receipt");
    }
  }

  static long requireMappedRoom(
      DSLContext dsl,
      WorldCanonicalInstanceAssociation association,
      RoomTemplateRef startLocation,
      byte[] selectorBytes) {
    Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(association, "association");
    Objects.requireNonNull(startLocation, "startLocation");
    Objects.requireNonNull(selectorBytes, "selectorBytes");
    var identity = association.identity();
    var prepareFields = association.worldPrepareFields();
    if (!identity.canonicalTenantId().equals(startLocation.tenantId())
        || !association.canonicalVersionId().equals(startLocation.versionId())) {
      throw invalid("Original World ROOM selector differs from its canonical association");
    }

    Record selected = dsl.fetchOne(MAPPED_ROOM_SQL, identity.canonicalGameInstanceId());
    if (selected == null
        || !identity
            .canonicalGameInstanceId()
            .equals(required(selected, "canonical_game_instance_id", java.util.UUID.class))
        || required(selected, "world_instance_id", Long.class) != association.worldInstanceId()
        || !identity
            .canonicalTenantId()
            .equals(required(selected, "canonical_tenant_id", java.util.UUID.class))
        || !association
            .canonicalVersionId()
            .equals(required(selected, "canonical_version_id", java.util.UUID.class))
        || !startLocation
            .roomTemplateId()
            .equals(required(selected, "room_template_id", java.util.UUID.class))
        || !Objects.equals(
            required(selected, "runtime_room_instance_id", Long.class),
            required(selected, "mapped_room_instance_id", Long.class))
        || !Objects.equals(
            required(selected, "runtime_room_instance_id", Long.class),
            required(selected, "mapped_room_row_id", Long.class))
        || !Objects.equals(
            required(selected, "runtime_room_instance_id", Long.class),
            required(selected, "actual_room_instance_id", Long.class))
        || !startLocation
            .roomTemplateId()
            .equals(required(selected, "mapped_template_id", java.util.UUID.class))
        || !identity
            .canonicalGameInstanceId()
            .equals(required(selected, "mapped_game_instance_id", java.util.UUID.class))
        || required(selected, "mapped_world_instance_id", Long.class)
            != association.worldInstanceId()
        || !"ROOM".equals(required(selected, "mapped_family", String.class))
        || !Arrays.equals(selectorBytes, required(selected, "evidence_bytes", byte[].class))
        || !receiptDigestFromEvidence(selectorBytes)
            .equals(required(selected, "receipt_digest", String.class))
        || !WorldDraftStartLocationEvidence.fromStored(
                WorldPublishedStartLocationEvidence.fromStored(selectorBytes)
                    .selectorReceiptBytes())
            .graphDigest()
            .equals(required(selected, "graph_digest", String.class))
        || required(selected, "room_tenant_key", Long.class) != prepareFields.privateTenantKey()
        || required(selected, "room_game_instance_key", Long.class)
            != prepareFields.privateGameInstanceKey()
        || required(selected, "region_tenant_key", Long.class) != prepareFields.privateTenantKey()
        || required(selected, "region_game_instance_key", Long.class)
            != prepareFields.privateGameInstanceKey()
        || required(selected, "room_world_instance_id", Long.class)
            != association.worldInstanceId()) {
      throw invalid("Canonical ROOM selector is not the exact mapped scoped World runtime room");
    }
    long runtimeRoomInstanceId = required(selected, "runtime_room_instance_id", Long.class);
    if (runtimeRoomInstanceId <= 0) {
      throw invalid("Runtime room instance ID must be positive");
    }
    return runtimeRoomInstanceId;
  }

  private static String receiptDigestFromEvidence(byte[] selectorBytes) {
    return WorldDraftStartLocationEvidence.fromStored(
            WorldPublishedStartLocationEvidence.fromStored(selectorBytes).selectorReceiptBytes())
        .receiptDigest();
  }

  private static String digest(byte[] bytes) {
    return "sha256:" + HexFormat.of().formatHex(sha256(bytes));
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) throw invalid("Canonical ROOM readback is missing " + field);
    return value;
  }

  private static InvalidStartLocationReadbackException invalid(String message) {
    return new InvalidStartLocationReadbackException(message);
  }

  static final class InvalidStartLocationReadbackException extends IllegalStateException {
    InvalidStartLocationReadbackException(String message) {
      super(message);
    }
  }
}
