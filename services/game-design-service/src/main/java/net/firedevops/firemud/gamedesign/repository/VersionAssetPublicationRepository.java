package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Owner-local persistence boundary for Draft asset mappings and durable export snapshots. */
@Repository
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Spring rejects a missing DSLContext before publication; this repository has no finalizer")
public class VersionAssetPublicationRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Comparator<String> UTF8_BYTE_ORDER =
      VersionAssetPublicationRepository::compareUtf8;

  private final DSLContext dsl;

  public VersionAssetPublicationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Associates an ordinary source asset with an exact tenant-owned Draft Version. Numeric ids in
   * this method are private Game Design owner keys and are not public identity aliases.
   */
  public void associateDraftAsset(String tenantId, long versionId, long assetId, String usageType) {
    requireTenantId(tenantId);
    if (versionId <= 0 || assetId <= 0) {
      throw new IllegalArgumentException("Version and asset owner keys must be positive");
    }

    VersionRow version =
        lockVersionByTenantAndId(tenantId, versionId)
            .orElseThrow(() -> new IllegalStateException("Exact tenant Version row was not found"));
    qualifyVersion(version);
    requireDraft(version);
    requireNoSnapshot(tenantId, versionId);

    AssetRow asset =
        findAssetForUpdate(tenantId, assetId)
            .orElseThrow(
                () -> new IllegalStateException("Exact tenant game asset row was not found"));
    validateUsageKey(asset.fileName());

    Record existing =
        dsl.fetchOne(
            "SELECT usage_key, usage_type FROM version_asset "
                + "WHERE tenant_id = ? AND version_id = ? AND asset_id = ?",
            tenantId,
            versionId,
            assetId);
    if (existing != null) {
      if (Objects.equals(existing.get("usage_key", String.class), asset.fileName())
          && Objects.equals(existing.get("usage_type", String.class), usageType)) {
        return;
      }
      throw new IllegalStateException("Conflicting Version asset association already exists");
    }

    Record usageCollision =
        dsl.fetchOne(
            "SELECT asset_id FROM version_asset "
                + "WHERE tenant_id = ? AND version_id = ? AND usage_key = ?",
            tenantId,
            versionId,
            asset.fileName());
    if (usageCollision != null) {
      throw new IllegalStateException("Version asset usage key is already mapped to another asset");
    }

    dsl.execute(
        "INSERT INTO version_asset "
            + "(tenant_id, version_id, asset_id, usage_key, usage_type) VALUES (?, ?, ?, ?, ?)",
        tenantId,
        versionId,
        assetId,
        asset.fileName(),
        usageType);
  }

  /**
   * Freezes the current exact Draft mapping set or reads an earlier frozen snapshot. The caller
   * must hold a transaction because the Version lock serializes mapping writes with first freeze.
   */
  public ExportSnapshot freezeOrReadSnapshot(String tenantId, int versionNumber) {
    requireTenantId(tenantId);
    if (versionNumber <= 0) {
      throw new IllegalArgumentException("Version number must be positive");
    }
    VersionRow version =
        lockVersionByTenantAndVersionNumber(tenantId, versionNumber)
            .orElseThrow(() -> new IllegalStateException("Exact tenant Version was not found"));
    qualifyVersion(version);

    if (hasSnapshot(tenantId, version.id())) {
      return readSnapshot(version);
    }
    requireDraft(version);

    List<SourceAsset> selected = lockMappedSourceAssets(tenantId, version.id());
    selected.sort(Comparator.comparing(asset -> asset.usageKey(), UTF8_BYTE_ORDER));
    Set<String> usageKeys = new HashSet<>();
    Set<Long> assetIds = new HashSet<>();
    List<PreparedAsset> prepared = new ArrayList<>(selected.size());
    for (SourceAsset source : selected) {
      validateUsageKey(source.usageKey());
      if (source.assetId() == null) {
        throw new IllegalStateException("Version asset source row identity is missing");
      }
      if (!Objects.equals(source.usageKey(), source.fileName())) {
        throw new IllegalStateException(
            "Version asset usage key no longer matches its source file name");
      }
      if (!usageKeys.add(source.usageKey()) || !assetIds.add(source.assetId())) {
        throw new IllegalStateException(
            "Version asset mapping contains a duplicate source or usage key");
      }
      byte[] bytes = requireBytes(source.bytes());
      prepared.add(
          new PreparedAsset(
              source.usageKey(),
              source.assetId(),
              requireContentType(source.contentType()),
              bytes,
              digest(bytes)));
    }

    for (PreparedAsset asset : prepared) {
      dsl.execute(
          "INSERT INTO version_asset_export_item "
              + "(tenant_id, version_id, usage_key, asset_id, content_type, content_hash, byte_size) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?)",
          tenantId,
          version.id(),
          asset.usageKey(),
          asset.assetId(),
          asset.contentType(),
          asset.contentDigest(),
          (long) asset.bytes().length);
    }
    dsl.execute(
        "INSERT INTO version_asset_export_snapshot "
            + "(tenant_id, version_id, canonical_tenant_id, canonical_version_id, version_number, "
            + "captured_version_state_epoch, item_count) VALUES (?, ?, ?, ?, ?, ?, ?)",
        tenantId,
        version.id(),
        version.canonicalTenantId(),
        version.canonicalVersionId(),
        version.versionNumber(),
        version.stateEpoch(),
        prepared.size());

    return readSnapshot(version);
  }

  /**
   * Reads only an already committed owner-local snapshot; absence is never interpreted as empty.
   */
  public ExportSnapshot readFrozenSnapshot(String tenantId, int versionNumber) {
    requireTenantId(tenantId);
    if (versionNumber <= 0) {
      throw new IllegalArgumentException("Version number must be positive");
    }
    VersionRow version =
        readVersionByTenantAndVersionNumber(tenantId, versionNumber)
            .orElseThrow(() -> new IllegalStateException("Exact tenant Version was not found"));
    qualifyVersion(version);
    return readSnapshot(version);
  }

  private ExportSnapshot readSnapshot(VersionRow version) {
    Record header =
        dsl.fetchOne(
            "SELECT canonical_tenant_id, canonical_version_id, version_number, "
                + "captured_version_state_epoch, item_count "
                + "FROM version_asset_export_snapshot WHERE tenant_id = ? AND version_id = ?",
            version.tenantId(),
            version.id());
    if (header == null) {
      throw new IllegalStateException("No durable frozen Version asset snapshot exists");
    }

    UUID canonicalTenantId = header.get("canonical_tenant_id", UUID.class);
    UUID canonicalVersionId = header.get("canonical_version_id", UUID.class);
    Integer storedVersionNumber = header.get("version_number", Integer.class);
    Long capturedEpoch = header.get("captured_version_state_epoch", Long.class);
    Integer itemCount = header.get("item_count", Integer.class);
    if (!Objects.equals(canonicalTenantId, version.canonicalTenantId())
        || !Objects.equals(canonicalVersionId, version.canonicalVersionId())
        || !Objects.equals(storedVersionNumber, version.versionNumber())
        || capturedEpoch == null
        || capturedEpoch <= 0
        || version.stateEpoch() < capturedEpoch
        || itemCount == null
        || itemCount < 0) {
      throw new IllegalStateException("Frozen Version asset snapshot identity is inconsistent");
    }

    long mappingCount =
        Optional.ofNullable(
                dsl.fetchOne(
                    "SELECT count(*) AS item_count FROM version_asset "
                        + "WHERE tenant_id = ? AND version_id = ?",
                    version.tenantId(),
                    version.id()))
            .map(row -> row.get("item_count", Long.class))
            .orElse(0L);
    if (mappingCount != itemCount.longValue()) {
      throw new IllegalStateException(
          "Frozen snapshot no longer matches its complete Version mapping set");
    }

    List<Record> rows =
        dsl.fetch(
            "SELECT i.usage_key AS snapshot_usage_key, i.asset_id AS snapshot_asset_id, "
                + "i.content_type AS snapshot_content_type, i.content_hash AS snapshot_content_hash, "
                + "i.byte_size AS snapshot_byte_size, va.usage_key AS mapping_usage_key, "
                + "va.asset_id AS mapping_asset_id, ga.id AS source_asset_id, "
                + "ga.tenant_id AS source_tenant_id, ga.file_name AS source_file_name, "
                + "ga.content_type AS source_content_type, ga.data AS source_data "
                + "FROM version_asset_export_item i "
                + "LEFT JOIN version_asset va ON va.tenant_id = i.tenant_id "
                + "AND va.version_id = i.version_id AND va.asset_id = i.asset_id "
                + "AND va.usage_key = i.usage_key "
                + "LEFT JOIN game_assets ga ON ga.tenant_id = i.tenant_id AND ga.id = i.asset_id "
                + "WHERE i.tenant_id = ? AND i.version_id = ?",
            version.tenantId(),
            version.id());
    if (rows.size() != itemCount) {
      throw new IllegalStateException("Frozen Version asset snapshot is incomplete");
    }

    List<AssetSelection> items = new ArrayList<>(rows.size());
    Set<String> usageKeys = new HashSet<>();
    Set<Long> assetIds = new HashSet<>();
    for (Record row : rows) {
      String usageKey = row.get("snapshot_usage_key", String.class);
      Long snapshotAssetId = row.get("snapshot_asset_id", Long.class);
      String snapshotType = row.get("snapshot_content_type", String.class);
      String snapshotDigest = row.get("snapshot_content_hash", String.class);
      Long snapshotSize = row.get("snapshot_byte_size", Long.class);
      Long mappingAssetId = row.get("mapping_asset_id", Long.class);
      String mappingUsageKey = row.get("mapping_usage_key", String.class);
      Long sourceAssetId = row.get("source_asset_id", Long.class);
      String sourceTenantId = row.get("source_tenant_id", String.class);
      String sourceFileName = row.get("source_file_name", String.class);
      String sourceType = row.get("source_content_type", String.class);
      byte[] sourceData = row.get("source_data", byte[].class);

      validateUsageKey(usageKey);
      requireContentType(sourceType);
      if (snapshotAssetId == null
          || snapshotType == null
          || snapshotDigest == null
          || snapshotSize == null
          || !snapshotAssetId.equals(mappingAssetId)
          || !Objects.equals(usageKey, mappingUsageKey)
          || !snapshotAssetId.equals(sourceAssetId)
          || !Objects.equals(version.tenantId(), sourceTenantId)
          || !Objects.equals(usageKey, sourceFileName)
          || !Objects.equals(snapshotType, sourceType)
          || sourceData == null
          || snapshotSize != sourceData.length
          || !snapshotDigest.equals(digest(sourceData))) {
        throw new IllegalStateException(
            "Frozen Version asset source bytes or mapping failed verification");
      }
      if (!usageKeys.add(usageKey) || !assetIds.add(snapshotAssetId)) {
        throw new IllegalStateException(
            "Frozen Version asset snapshot contains duplicate keys or sources");
      }
      items.add(
          new AssetSelection(usageKey, snapshotAssetId, sourceData, sourceType, snapshotDigest));
    }
    items.sort(Comparator.comparing(AssetSelection::usageKey, UTF8_BYTE_ORDER));

    return new ExportSnapshot(
        version.tenantId(),
        version.id(),
        storedVersionNumber,
        capturedEpoch,
        canonicalTenantId,
        canonicalVersionId,
        items);
  }

  private List<SourceAsset> lockMappedSourceAssets(String tenantId, long versionId) {
    return dsl.fetch(
            "SELECT va.usage_key, va.asset_id, ga.file_name, ga.content_type, ga.data "
                + "FROM version_asset va "
                + "JOIN game_assets ga ON ga.tenant_id = va.tenant_id AND ga.id = va.asset_id "
                + "AND ga.file_name = va.usage_key "
                + "WHERE va.tenant_id = ? AND va.version_id = ? "
                + "ORDER BY va.asset_id FOR UPDATE OF ga",
            tenantId,
            versionId)
        .map(
            row ->
                new SourceAsset(
                    row.get("usage_key", String.class),
                    row.get("asset_id", Long.class),
                    row.get("file_name", String.class),
                    row.get("content_type", String.class),
                    row.get("data", byte[].class)));
  }

  private Optional<AssetRow> findAssetForUpdate(String tenantId, long assetId) {
    Record row =
        dsl.fetchOne(
            "SELECT file_name FROM game_assets WHERE tenant_id = ? AND id = ? FOR UPDATE",
            tenantId,
            assetId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(new AssetRow(row.get("file_name", String.class)));
  }

  private Optional<VersionRow> lockVersionByTenantAndId(String tenantId, long versionId) {
    return readVersion(
        "SELECT id, tenant_id, version_number, version_state, version_state_epoch, "
            + "canonical_tenant_id, canonical_version_id, identity_source_game_row_id, "
            + "identity_source_game_tenant_key, identity_source_provenance_kind "
            + "FROM version WHERE tenant_id = ? AND id = ? FOR UPDATE",
        tenantId,
        versionId);
  }

  private Optional<VersionRow> lockVersionByTenantAndVersionNumber(
      String tenantId, int versionNumber) {
    return readVersion(
        "SELECT id, tenant_id, version_number, version_state, version_state_epoch, "
            + "canonical_tenant_id, canonical_version_id, identity_source_game_row_id, "
            + "identity_source_game_tenant_key, identity_source_provenance_kind "
            + "FROM version WHERE tenant_id = ? AND version_number = ? FOR UPDATE",
        tenantId,
        versionNumber);
  }

  private Optional<VersionRow> readVersionByTenantAndVersionNumber(
      String tenantId, int versionNumber) {
    return readVersion(
        "SELECT id, tenant_id, version_number, version_state, version_state_epoch, "
            + "canonical_tenant_id, canonical_version_id, identity_source_game_row_id, "
            + "identity_source_game_tenant_key, identity_source_provenance_kind "
            + "FROM version WHERE tenant_id = ? AND version_number = ?",
        tenantId,
        versionNumber);
  }

  private Optional<VersionRow> readVersion(String sql, Object first, Object second) {
    Record row = dsl.fetchOne(sql, first, second);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new VersionRow(
            row.get("id", Long.class),
            row.get("tenant_id", String.class),
            row.get("version_number", Integer.class),
            row.get("version_state", String.class),
            row.get("version_state_epoch", Long.class),
            row.get("canonical_tenant_id", UUID.class),
            row.get("canonical_version_id", UUID.class),
            row.get("identity_source_game_row_id", Long.class),
            row.get("identity_source_game_tenant_key", String.class),
            row.get("identity_source_provenance_kind", String.class)));
  }

  private void qualifyVersion(VersionRow version) {
    if (version.id() == null
        || version.id() <= 0
        || version.tenantId() == null
        || version.versionNumber() == null
        || version.versionNumber() <= 0
        || version.stateEpoch() == null
        || version.stateEpoch() <= 0
        || version.canonicalTenantId() == null
        || version.canonicalVersionId() == null
        || NIL_UUID.equals(version.canonicalTenantId())
        || NIL_UUID.equals(version.canonicalVersionId())
        || version.identitySourceGameRowId() == null
        || version.identitySourceGameRowId() <= 0
        || !Objects.equals(version.tenantId(), version.identitySourceGameTenantKey())
        || !("NEW_GAME_ROW".equals(version.identitySourceProvenanceKind())
            || "RETAINED_GAME_V29".equals(version.identitySourceProvenanceKind()))) {
      throw new IllegalStateException("Version lacks a complete canonical owner identity");
    }
    Record game =
        dsl.fetchOne(
            "SELECT id, tenant_id, canonical_tenant_id, tenant_identity_provenance_kind, "
                + "tenant_identity_source_game_id, tenant_identity_source_legacy_tenant_id "
                + "FROM game WHERE id = ? AND tenant_id = ?",
            version.identitySourceGameRowId(),
            version.identitySourceGameTenantKey());
    if (game == null
        || !Objects.equals(game.get("canonical_tenant_id", UUID.class), version.canonicalTenantId())
        || !Objects.equals(
            game.get("tenant_identity_provenance_kind", String.class),
            version.identitySourceProvenanceKind())
        || !Objects.equals(
            game.get("tenant_identity_source_game_id", Long.class), game.get("id", Long.class))
        || !Objects.equals(
            game.get("tenant_identity_source_legacy_tenant_id", String.class),
            version.tenantId())) {
      throw new IllegalStateException(
          "Version canonical identity does not match its exact source Game row");
    }
    if (version.state() == null) {
      throw new IllegalStateException("Version has an unexpected lifecycle state");
    }
    try {
      net.firedevops.firemud.gamedesign.model.VersionLifecycleState.valueOf(version.state());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Version has an unexpected lifecycle state", exception);
    }
  }

  private void requireDraft(VersionRow version) {
    if (!"DRAFT".equals(version.state())) {
      throw new IllegalStateException(
          "Version asset mappings and first freeze require a Draft Version");
    }
  }

  private void requireNoSnapshot(String tenantId, long versionId) {
    if (hasSnapshot(tenantId, versionId)) {
      throw new IllegalStateException(
          "Version asset mappings are frozen by the durable export snapshot");
    }
  }

  private boolean hasSnapshot(String tenantId, long versionId) {
    return dsl.fetchOne(
            "SELECT 1 FROM version_asset_export_snapshot WHERE tenant_id = ? AND version_id = ?",
            tenantId,
            versionId)
        != null;
  }

  private static void requireTenantId(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("Owner-local tenant key is required");
    }
  }

  private static void validateUsageKey(String usageKey) {
    if (usageKey == null || usageKey.isBlank()) {
      throw new IllegalStateException("Asset source file name cannot be a blank usage key");
    }
    if ("manifest.json".equals(usageKey)) {
      throw new IllegalStateException(
          "Asset source file name collides with the reserved manifest.json key");
    }
  }

  private static String requireContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      throw new IllegalStateException("Asset source content type is missing");
    }
    return contentType;
  }

  private static byte[] requireBytes(byte[] bytes) {
    if (bytes == null) {
      throw new IllegalStateException("Asset source bytes are missing");
    }
    return bytes.clone();
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static int compareUtf8(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int commonLength = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < commonLength; index++) {
      int compared =
          Integer.compare(
              Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
      if (compared != 0) {
        return compared;
      }
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  private record VersionRow(
      Long id,
      String tenantId,
      Integer versionNumber,
      String state,
      Long stateEpoch,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      Long identitySourceGameRowId,
      String identitySourceGameTenantKey,
      String identitySourceProvenanceKind) {}

  private record AssetRow(String fileName) {}

  private record SourceAsset(
      String usageKey, Long assetId, String fileName, String contentType, byte[] bytes) {}

  private record PreparedAsset(
      String usageKey, long assetId, String contentType, byte[] bytes, String contentDigest) {}

  /**
   * A defensive byte selection persisted in the immutable per-Version export projection. The
   * numeric asset id is a private Game Design owner key, not an external identifier.
   */
  public record AssetSelection(
      String usageKey, long assetId, byte[] bytes, String contentType, String contentDigest) {
    public AssetSelection {
      Objects.requireNonNull(usageKey, "usageKey");
      Objects.requireNonNull(bytes, "bytes");
      Objects.requireNonNull(contentType, "contentType");
      Objects.requireNonNull(contentDigest, "contentDigest");
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  /**
   * Complete owner-local immutable readback for a frozen Version asset selection. Its tenant,
   * Version, and asset numeric ids remain private owner keys; canonical UUIDs are read from
   * persisted provenance and do not alias those keys.
   */
  public record ExportSnapshot(
      String tenantId,
      long versionId,
      int versionNumber,
      long capturedVersionStateEpoch,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      List<AssetSelection> items) {
    public ExportSnapshot {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
      Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
      items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
  }
}
