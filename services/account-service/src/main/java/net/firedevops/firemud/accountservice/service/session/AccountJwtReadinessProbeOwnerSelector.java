package net.firedevops.firemud.accountservice.service.session;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ProbeExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;

/** Closed, bounded selector for an authenticated read of one current V2 readiness entry. */
public record AccountJwtReadinessProbeOwnerSelector(
    UUID rotationOperationId,
    String operationDigest,
    String planDigest,
    int planVersion,
    int registryVersion,
    String validatorId,
    String tokenProfile,
    String audience,
    ProbeKind probeKind,
    ProbeExpectation expectedOutcome,
    UUID jti,
    long entryVersion,
    String targetGeneration,
    String targetKid,
    Optional<ActiveSigner> expectedActive,
    long issuedAtEpochSecond,
    long expiresAtEpochSecond,
    long planExpiresAtEpochSecond,
    String compactTokenSha256,
    LocalIdentity localIdentity) {
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
  private static final Pattern PROFILE = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern GENERATION = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

  public AccountJwtReadinessProbeOwnerSelector {
    Objects.requireNonNull(rotationOperationId, "operation ID is required");
    if (rotationOperationId.version() != 4 || rotationOperationId.variant() != 2) {
      throw new IllegalArgumentException("Canonical readiness operation UUIDv4 is required");
    }
    requireDigest(operationDigest, "operation digest");
    requireDigest(planDigest, "plan digest");
    if (planVersion != AccountJwtReadinessProbeRepository.INVENTORY_PLAN_VERSION
        || registryVersion != 1
        || entryVersion <= 0L) {
      throw new IllegalArgumentException("Current V2 readiness coordinates are required");
    }
    requireId(validatorId, "validator ID");
    requireProfile(tokenProfile);
    requireProfile(audience);
    Objects.requireNonNull(probeKind, "probe kind is required");
    Objects.requireNonNull(expectedOutcome, "expected outcome is required");
    Objects.requireNonNull(jti, "probe JTI is required");
    if (jti.version() != 4 || jti.variant() != 2) {
      throw new IllegalArgumentException("Canonical readiness JTI is required");
    }
    requireGeneration(targetGeneration);
    requireKid(targetKid);
    expectedActive = Objects.requireNonNull(expectedActive, "active fence state is required");
    if (issuedAtEpochSecond <= 0L
        || expiresAtEpochSecond <= issuedAtEpochSecond
        || planExpiresAtEpochSecond <= 0L
        || expiresAtEpochSecond > planExpiresAtEpochSecond) {
      throw new IllegalArgumentException("Readiness probe time bounds are malformed");
    }
    requireDigest(compactTokenSha256, "compact token digest");
    Objects.requireNonNull(localIdentity, "protected local identity is required");
  }

  public record LocalIdentity(
      String validatorId,
      String deploymentUid,
      String podUid,
      String podIp,
      String directPodEndpoint,
      String canonicalServiceUri,
      String image,
      String verifierConfigSha256,
      String applicabilityMatrixDigest,
      String sourceInventoryRevision,
      String sourceInventoryDigest,
      String serverLeafSpkiSha256) {
    public LocalIdentity {
      requireId(validatorId, "validator ID");
      requireCanonicalUuid(deploymentUid, "deployment UID");
      requireCanonicalUuid(podUid, "Pod UID");
      if (!net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding
          .canonicalPodIp(podIp)
          .equals(podIp)) {
        throw new IllegalArgumentException("Pod IP is not canonical");
      }
      requireText(directPodEndpoint, 256, "direct Pod endpoint");
      requireText(canonicalServiceUri, 256, "canonical service URI");
      if (image == null || !image.matches("[^\\s@]+@sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Validator image must be digest-pinned");
      }
      requireDigest(verifierConfigSha256, "verifier configuration digest");
      requireDigest(applicabilityMatrixDigest, "applicability matrix digest");
      if (sourceInventoryRevision == null || !REVISION.matcher(sourceInventoryRevision).matches()) {
        throw new IllegalArgumentException("Receiver source inventory revision is malformed");
      }
      requireDigest(sourceInventoryDigest, "receiver source inventory digest");
      requireDigest(serverLeafSpkiSha256, "receiver TLS SPKI digest");
      URI endpoint = URI.create(directPodEndpoint);
      if (!endpoint.toString().equals(directPodEndpoint)
          || !canonicalServiceUri.matches(
              "spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
        throw new IllegalArgumentException("Receiver endpoint or service identity is malformed");
      }
    }
  }

  private static void requireCanonicalUuid(String value, String label) {
    if (value == null
        || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        || !UUID.fromString(value).toString().equals(value)) {
      throw new IllegalArgumentException(label + " is not canonical");
    }
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " is malformed");
    }
  }

  private static void requireId(String value, String label) {
    if (value == null || !ID.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " is malformed");
    }
  }

  private static void requireProfile(String value) {
    if (value == null || !PROFILE.matcher(value).matches()) {
      throw new IllegalArgumentException("Readiness profile or audience is malformed");
    }
  }

  private static void requireKid(String value) {
    if (value == null || !KID.matcher(value).matches()) {
      throw new IllegalArgumentException("Readiness target key ID is malformed");
    }
  }

  private static void requireGeneration(String value) {
    if (value == null || !GENERATION.matcher(value).matches()) {
      throw new IllegalArgumentException("Readiness target generation is malformed");
    }
  }

  private static void requireText(String value, int maximum, String label) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label + " is malformed");
    }
  }
}
