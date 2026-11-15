package net.firedevops.firemud.automationscripting.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.OffsetDateTime;
import java.util.Objects;
import net.firedevops.firemud.automationscripting.model.ScriptPatchAlreadySealedException;
import net.firedevops.firemud.automationscripting.model.ScriptPatchPublicationSeal;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional serialization point for Automation's mutable patch rows and sealed snapshots. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptPatchPublicationSealRepository {
  private static final int MAX_SCRIPT_PATCH_VERSION_LENGTH = 20;
  private static final int MAX_BASE_VERSION_ID_LENGTH = 128;

  public record PatchLock(
      Long tenantId,
      String scriptPatchVersion,
      boolean sealed,
      String baseVersionId,
      Integer manifestSchemaVersion,
      Integer contentDigestSchemaVersion,
      String contentDigest,
      String snapshotJson,
      OffsetDateTime sealedAt) {}

  private final DSLContext dsl;

  public ScriptPatchPublicationSealRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Creates/locks the one row shared by all patch writes and publication sealing. The caller must
   * already own a transaction, so the FOR UPDATE lock remains held through its whole mutation.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PatchLock lockForUpdate(Long tenantId, String scriptPatchVersion) {
    validateIdentity(tenantId, scriptPatchVersion);
    dsl.execute(
        "INSERT INTO script_patch_publication_seals (tenant_id, script_patch_version) "
            + "VALUES (?, ?) ON CONFLICT (tenant_id, script_patch_version) DO NOTHING",
        tenantId,
        scriptPatchVersion);
    Record row =
        dsl.fetchOne(
            "SELECT tenant_id, script_patch_version, is_sealed, base_version_id, "
                + "manifest_schema_version, content_digest_schema_version, content_digest, "
                + "snapshot_json, sealed_at FROM script_patch_publication_seals "
                + "WHERE tenant_id = ? AND script_patch_version = ? FOR UPDATE",
            tenantId,
            scriptPatchVersion);
    if (row == null) {
      throw new IllegalStateException("script_patch_publication_lock_row_missing");
    }
    Boolean sealed = row.get("is_sealed", Boolean.class);
    return new PatchLock(
        row.get("tenant_id", Long.class),
        row.get("script_patch_version", String.class),
        Boolean.TRUE.equals(sealed),
        row.get("base_version_id", String.class),
        row.get("manifest_schema_version", Integer.class),
        row.get("content_digest_schema_version", Integer.class),
        row.get("content_digest", String.class),
        row.get("snapshot_json", String.class),
        row.get("sealed_at", OffsetDateTime.class));
  }

  /**
   * Stores a complete snapshot while acquiring the same transaction lock used by UpdateScript.
   * Resealing is a no-op only when identity and every stored snapshot field match exactly.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ScriptPatchPublicationSeal seal(
      Long tenantId,
      String baseVersionId,
      String scriptPatchVersion,
      int manifestSchemaVersion,
      int contentDigestSchemaVersion,
      String contentDigest,
      String snapshotJson) {
    validateIdentity(tenantId, scriptPatchVersion);
    String validatedBaseVersionId = validateBaseVersionId(baseVersionId);
    if (manifestSchemaVersion < 1 || contentDigestSchemaVersion < 1) {
      throw new IllegalArgumentException("snapshot schema versions must be positive");
    }
    if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}") || snapshotJson == null) {
      throw new IllegalArgumentException("publication snapshot is incomplete");
    }

    PatchLock current = lockForUpdate(tenantId, scriptPatchVersion);
    if (current.sealed()) {
      if (isSameSeal(
          current,
          validatedBaseVersionId,
          manifestSchemaVersion,
          contentDigestSchemaVersion,
          contentDigest,
          snapshotJson)) {
        return toSeal(current);
      }
      throw new ScriptPatchAlreadySealedException(
          "script patch has already been sealed with different identity or content");
    }

    OffsetDateTime sealedAt = OffsetDateTime.now();
    int updated =
        dsl.execute(
            "UPDATE script_patch_publication_seals SET base_version_id = ?, is_sealed = TRUE, "
                + "manifest_schema_version = ?, content_digest_schema_version = ?, "
                + "content_digest = ?, snapshot_json = ?, sealed_at = ? "
                + "WHERE tenant_id = ? AND script_patch_version = ? AND is_sealed = FALSE",
            validatedBaseVersionId,
            manifestSchemaVersion,
            contentDigestSchemaVersion,
            contentDigest,
            snapshotJson,
            sealedAt,
            tenantId,
            scriptPatchVersion);
    if (updated != 1) {
      throw new IllegalStateException("script_patch_publication_seal_write_lost");
    }
    return toSeal(lockForUpdate(tenantId, scriptPatchVersion));
  }

  private static boolean isSameSeal(
      PatchLock current,
      String baseVersionId,
      int manifestSchemaVersion,
      int contentDigestSchemaVersion,
      String contentDigest,
      String snapshotJson) {
    return Objects.equals(current.baseVersionId(), baseVersionId)
        && Objects.equals(current.manifestSchemaVersion(), manifestSchemaVersion)
        && Objects.equals(current.contentDigestSchemaVersion(), contentDigestSchemaVersion)
        && Objects.equals(current.contentDigest(), contentDigest)
        && Objects.equals(current.snapshotJson(), snapshotJson);
  }

  private static ScriptPatchPublicationSeal toSeal(PatchLock lock) {
    return new ScriptPatchPublicationSeal(
        lock.tenantId(),
        lock.baseVersionId(),
        lock.scriptPatchVersion(),
        lock.manifestSchemaVersion(),
        lock.contentDigestSchemaVersion(),
        lock.contentDigest(),
        lock.snapshotJson(),
        lock.sealedAt());
  }

  private static void validateIdentity(Long tenantId, String scriptPatchVersion) {
    if (tenantId == null || tenantId <= 0) {
      throw new IllegalArgumentException("tenantId must be positive");
    }
    if (scriptPatchVersion == null
        || scriptPatchVersion.isBlank()
        || scriptPatchVersion.length() > MAX_SCRIPT_PATCH_VERSION_LENGTH) {
      throw new IllegalArgumentException("scriptPatchVersion is invalid");
    }
  }

  private static String validateBaseVersionId(String baseVersionId) {
    if (baseVersionId == null
        || baseVersionId.isBlank()
        || baseVersionId.length() > MAX_BASE_VERSION_ID_LENGTH) {
      throw new IllegalArgumentException("baseVersionId is invalid");
    }
    return baseVersionId;
  }
}
