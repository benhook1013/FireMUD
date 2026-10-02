package net.firedevops.firemud.gamesession.dto;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** Exact committed owner evidence for one initial canonical CLOSED route. */
public record CanonicalClosedAdmissionPointerSnapshot(
    String targetNamespace,
    UUID canonicalTenantId,
    UUID realmId,
    String worldSlug,
    String realmSlug,
    long pointerVersion,
    long catalogRevision,
    String admissionState,
    @Nullable UUID admissibleGameInstanceId,
    UUID requestId,
    String requestDigest,
    String receiptDigest,
    String actorPrincipal,
    String reason,
    long auditEventId,
    Instant updatedAt,
    CanonicalRealmCatalogSnapshot catalogSnapshot) {
  @SuppressFBWarnings(
      value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
      justification =
          "CLOSED routing requires the nullable admissible instance to be absent; this constructor rejects any supplied UUID.")
  public CanonicalClosedAdmissionPointerSnapshot {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(realmId, "realmId");
    Objects.requireNonNull(worldSlug, "worldSlug");
    Objects.requireNonNull(realmSlug, "realmSlug");
    Objects.requireNonNull(admissionState, "admissionState");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(requestDigest, "requestDigest");
    Objects.requireNonNull(receiptDigest, "receiptDigest");
    Objects.requireNonNull(actorPrincipal, "actorPrincipal");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
    if (!"CLOSED".equals(admissionState)
        || admissibleGameInstanceId != null
        || pointerVersion != 1L
        || catalogRevision != catalogSnapshot.catalogRevision()
        || !targetNamespace.equals(catalogSnapshot.targetNamespace())
        || !canonicalTenantId.equals(catalogSnapshot.tenantId())
        || !realmId.equals(catalogSnapshot.realmId())
        || !worldSlug.equals(catalogSnapshot.worldSlug())
        || !realmSlug.equals(catalogSnapshot.realmSlug())
        || auditEventId <= 0L) {
      throw new IllegalArgumentException(
          "Canonical closed pointer snapshot does not match its exact initial catalog evidence");
    }
  }
}
