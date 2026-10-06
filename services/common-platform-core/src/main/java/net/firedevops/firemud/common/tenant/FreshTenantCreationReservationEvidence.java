package net.firedevops.firemud.common.tenant;

import java.util.Objects;
import java.util.UUID;

/** Complete immutable, non-authoritative identity evidence for one Game Design reservation. */
public record FreshTenantCreationReservationEvidence(
    int schemaVersion,
    String targetNamespace,
    UUID creationRequestId,
    String requestDigest,
    UUID operationId,
    UUID canonicalTenantId,
    String sourceGameTenantKey,
    String name,
    String description,
    String evidenceDigest) {
  public FreshTenantCreationReservationEvidence {
    Objects.requireNonNull(evidenceDigest, "evidenceDigest");
    FreshTenantCreationReservationDigest.validate(
        schemaVersion,
        targetNamespace,
        creationRequestId,
        requestDigest,
        operationId,
        canonicalTenantId,
        sourceGameTenantKey,
        name,
        description);
    String expectedEvidenceDigest =
        FreshTenantCreationReservationDigest.evidenceDigest(
            schemaVersion,
            targetNamespace,
            creationRequestId,
            requestDigest,
            operationId,
            canonicalTenantId,
            sourceGameTenantKey,
            name,
            description);
    if (!expectedEvidenceDigest.equals(evidenceDigest)) {
      throw new IllegalArgumentException("Reservation evidence digest does not match its tuple");
    }
  }

  public static FreshTenantCreationReservationEvidence fromReservation(
      int schemaVersion,
      String targetNamespace,
      UUID creationRequestId,
      String requestDigest,
      UUID operationId,
      UUID canonicalTenantId,
      String sourceGameTenantKey,
      String name,
      String description) {
    return new FreshTenantCreationReservationEvidence(
        schemaVersion,
        targetNamespace,
        creationRequestId,
        requestDigest,
        operationId,
        canonicalTenantId,
        sourceGameTenantKey,
        name,
        description,
        FreshTenantCreationReservationDigest.evidenceDigest(
            schemaVersion,
            targetNamespace,
            creationRequestId,
            requestDigest,
            operationId,
            canonicalTenantId,
            sourceGameTenantKey,
            name,
            description));
  }
}
