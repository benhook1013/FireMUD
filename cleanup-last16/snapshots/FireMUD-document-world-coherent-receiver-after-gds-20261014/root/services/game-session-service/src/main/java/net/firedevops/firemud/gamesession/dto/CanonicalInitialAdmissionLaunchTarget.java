package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.AuthorityKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch;

/** The exact canonical launch association and its owner-local runtime row locked together. */
public record CanonicalInitialAdmissionLaunchTarget(
    CanonicalGameInstanceLaunchAssociation association,
    UUID realmId,
    long gameInstanceId,
    long runtimeVersionId,
    long gameSessionTenantId,
    UUID canonicalVersionId,
    AuthorityKind authorityKind,
    String fixtureProvenanceDigest,
    Boolean requiresCharacterSelection,
    CurrentGameInstanceStatus fixtureStatus,
    long fixtureRowVersion,
    UUID fixtureCanonicalGameInstanceId) {
  /** Existing Human launch target. */
  public CanonicalInitialAdmissionLaunchTarget(
      CanonicalGameInstanceLaunchAssociation association,
      UUID realmId,
      long gameInstanceId,
      long runtimeVersionId) {
    this(
        association,
        realmId,
        gameInstanceId,
        runtimeVersionId,
        Objects.requireNonNull(association, "association").gameSessionTenantId(),
        association.launchBindingEvidence().releaseAttestation().canonicalVersionId(),
        AuthorityKind.HUMAN,
        null,
        null,
        null,
        0L,
        null);
  }

  public CanonicalInitialAdmissionLaunchTarget {
    Objects.requireNonNull(realmId, "realmId");
    Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
    Objects.requireNonNull(authorityKind, "authorityKind");
    if (gameInstanceId <= 0L || runtimeVersionId <= 0L || gameSessionTenantId <= 0L) {
      throw new IllegalArgumentException("Canonical launch target identifiers must be positive");
    }
    if (authorityKind == AuthorityKind.HUMAN) {
      Objects.requireNonNull(association, "association");
      if (fixtureProvenanceDigest != null
          || requiresCharacterSelection != null
          || fixtureStatus != null
          || fixtureRowVersion != 0L
          || fixtureCanonicalGameInstanceId != null
          || association.gameSessionTenantId() != gameSessionTenantId
          || !association.launchBindingEvidence().releaseAttestation().canonicalVersionId()
              .equals(canonicalVersionId)
          || association.launchBindingEvidence().descriptor().versionId() != runtimeVersionId) {
        throw new IllegalArgumentException(
            "Human launch target must retain its complete attested association");
      }
    } else {
      if (association != null
          || fixtureProvenanceDigest == null
          || !fixtureProvenanceDigest.matches("sha256:[0-9a-f]{64}")
          || !Boolean.FALSE.equals(requiresCharacterSelection)
          || fixtureStatus == null
          || fixtureRowVersion < 0L
          || fixtureCanonicalGameInstanceId == null
          || fixtureCanonicalGameInstanceId.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException(
            "Fixture launch target requires its exact policy-seal provenance");
      }
    }
  }

  public UUID canonicalGameInstanceId() {
    return authorityKind == AuthorityKind.HUMAN ? association.gameInstanceUuid()
        : fixtureCanonicalGameInstanceId;
  }

  /** Builds the fixture variant only from the verified immutable GDS launch receipt and owner read. */
  public static CanonicalInitialAdmissionLaunchTarget verifiedFixture(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      FixtureCanonicalInstanceLaunch launch) {
    Objects.requireNonNull(evidence, "evidence").requireVerifiedFixture();
    Objects.requireNonNull(launch, "launch");
    UUID canonicalVersionId;
    try {
      canonicalVersionId = UUID.fromString(evidence.publishedReleaseBundle().getCanonicalVersionId());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Verified fixture launch has no canonical Version UUID", malformed);
    }
    var catalog = launch.catalog().snapshot();
    var association = launch.catalog().association();
    if (!evidence.sourceEvidence().canonicalTenantId().equals(catalog.tenantId())
        || !evidence.sourceEvidence().equals(catalog.sourceIntakeReceipt().source())
        || !evidence.creationEvidence().equals(association.sourceEvidence())
        || evidence.fixtureCapability().requiresCharacterSelection()
        || launch.gameInstanceId() <= 0L
        || launch.gameInstanceUuid() == null
        || evidence.launchDescriptor().getVersionId() <= 0L) {
      throw new IllegalArgumentException(
          "Fixture launch readback differs from its verified source and creation evidence");
    }
    return new CanonicalInitialAdmissionLaunchTarget(
        null,
        catalog.realmId(),
        launch.gameInstanceId(),
        evidence.launchDescriptor().getVersionId(),
        association.gameSessionTenantId(),
        canonicalVersionId,
        AuthorityKind.RUN_OWNED_FIXTURE,
        evidence.fixturePolicySeal().evidenceDigest(),
        evidence.fixtureCapability().requiresCharacterSelection(),
        launch.status(),
        launch.rowVersion(),
        launch.gameInstanceUuid());
  }
}
