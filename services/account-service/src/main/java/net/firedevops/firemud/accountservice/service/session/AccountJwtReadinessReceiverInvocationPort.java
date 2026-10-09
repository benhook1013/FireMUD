package net.firedevops.firemud.accountservice.service.session;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;

/**
 * Account-owned internal invocation boundary for one exact validator Pod and one exact probe.
 *
 * <p>The production implementation must route to the observed Pod endpoint, verify the TLS peer
 * against its protected Pod-specific pin while retaining the canonical service SAN, and return an
 * {@link AuthenticatedAcceptance} only after validating the receiver's production-verifier result
 * and local Pod identity. No RPC request or harness DTO is an implementation of this port.
 */
public abstract class AccountJwtReadinessReceiverInvocationPort {
  private static final Pattern UUID_V4 =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern UUID_CANONICAL =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
  private static final Pattern SERVICE_URI =
      Pattern.compile(
          "spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  /** Invokes one Pod; implementations must fail closed if any target binding is unavailable. */
  public abstract AuthenticatedAcceptance invoke(Invocation invocation);

  /** Availability guard called before one-shot signing or delivery is durably claimed. */
  public abstract void requireAvailable();

  /** No remote receiver or Pod-bound transport identity is currently composed. */
  public static AccountJwtReadinessReceiverInvocationPort defaultDenied() {
    return new AccountJwtReadinessReceiverInvocationPort() {
      @Override
      public AuthenticatedAcceptance invoke(Invocation invocation) {
        throw new ReceiverUnavailableException();
      }

      @Override
      public void requireAvailable() {
        throw new ReceiverUnavailableException();
      }
    };
  }

  /** Owner-package factory for a response already authenticated by the real Pod-bound client. */
  static AuthenticatedAcceptance authenticatedAcceptance(
      String podUid,
      String actualPodEndpoint,
      String image,
      String verifierConfigSha256,
      String actualServiceUri,
      String actualPeerSpkiSha256,
      UUID jti,
      String compactTokenSha256,
      String verifiedKid,
      ProbeExpectation result,
      long observedAtEpochSecond) {
    return new AuthenticatedAcceptance(
        podUid,
        actualPodEndpoint,
        image,
        verifierConfigSha256,
        actualServiceUri,
        actualPeerSpkiSha256,
        jti,
        compactTokenSha256,
        verifiedKid,
        result,
        observedAtEpochSecond);
  }

  /** Exact, non-secret Pod target derived by Account from one protected inventory snapshot. */
  public record PodTarget(
      String inventorySnapshotDigest,
      String environmentId,
      String clusterId,
      String clusterIncarnationUid,
      String namespace,
      String namespaceUid,
      String apiBindingRevision,
      String apiBindingDigest,
      String inventoryBindingRevision,
      String inventoryBindingDigest,
      String validatorId,
      String deploymentUid,
      String podUid,
      String podIp,
      String image,
      String verifierConfigSha256,
      String applicabilityMatrixDigest,
      ProbeExpectation expectation,
      Optional<URI> exactPodEndpoint,
      Optional<String> canonicalServiceUri,
      Optional<String> podLeafSpkiSha256) {
    public PodTarget {
      requireDigest(inventorySnapshotDigest, "inventory snapshot digest");
      requireIdentity(environmentId, "environment ID");
      requireCluster(clusterId);
      requireCanonicalUuid(clusterIncarnationUid, "cluster incarnation UID");
      requireIdentity(namespace, "Kubernetes namespace");
      requireCanonicalUuid(namespaceUid, "namespace UID");
      requireRevision(apiBindingRevision, "Kubernetes API binding revision");
      requireDigest(apiBindingDigest, "Kubernetes API binding digest");
      requireRevision(inventoryBindingRevision, "inventory binding revision");
      requireDigest(inventoryBindingDigest, "inventory binding digest");
      requireId(validatorId, "validator ID");
      requireCanonicalUuid(deploymentUid, "validator Deployment UID");
      requireCanonicalUuid(podUid, "validator Pod UID");
      if (!AccountJwtValidatorInventoryBinding.canonicalPodIp(podIp).equals(podIp)) {
        throw new IllegalArgumentException("Protected validator Pod IP is not canonical");
      }
      if (image == null || !image.matches("[^\\s@]+@sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Validator image must be pinned by digest");
      }
      requireDigest(verifierConfigSha256, "verifier configuration digest");
      requireDigest(applicabilityMatrixDigest, "applicability matrix digest");
      Objects.requireNonNull(expectation, "probe expectation is required");
      exactPodEndpoint = Objects.requireNonNull(exactPodEndpoint);
      canonicalServiceUri = Objects.requireNonNull(canonicalServiceUri);
      podLeafSpkiSha256 = Objects.requireNonNull(podLeafSpkiSha256);
      exactPodEndpoint.ifPresent(endpoint -> requirePodEndpoint(endpoint, podIp));
      canonicalServiceUri.ifPresent(AccountJwtReadinessReceiverInvocationPort::requireServiceUri);
      podLeafSpkiSha256.ifPresent(value -> requireDigest(value, "Pod TLS leaf SPKI digest"));
    }

    /** Missing addressing or a Pod-specific peer pin makes this target non-invocable. */
    public void requireRoutablePodIdentity() {
      if (exactPodEndpoint.isEmpty()
          || canonicalServiceUri.isEmpty()
          || podLeafSpkiSha256.isEmpty()) {
        throw new ReceiverUnavailableException();
      }
    }
  }

  /** One exact logical registry probe, including transient signed bytes but no signer authority. */
  public static final class Invocation {
    private final UUID rotationOperationId;
    private final String operationDigest;
    private final String planDigest;
    private final int planVersion;
    private final long planExpiresAtEpochSecond;
    private final String validatorId;
    private final String tokenProfile;
    private final String audience;
    private final ProbeKind probeKind;
    private final ProbeExpectation expectation;
    private final int registryVersion;
    private final long sourceEntryVersion;
    private final UUID jti;
    private final String compactJwt;
    private final String compactTokenSha256;
    private final String targetGeneration;
    private final String targetKid;
    private final Optional<String> expectedActiveGeneration;
    private final Optional<String> expectedActiveKid;
    private final long issuedAtEpochSecond;
    private final long expiresAtEpochSecond;
    private final PodTarget target;

    Invocation(
        UUID rotationOperationId,
        String operationDigest,
        String planDigest,
        int planVersion,
        long planExpiresAtEpochSecond,
        String validatorId,
        String tokenProfile,
        String audience,
        ProbeKind probeKind,
        ProbeExpectation expectation,
        int registryVersion,
        long sourceEntryVersion,
        UUID jti,
        String compactJwt,
        String compactTokenSha256,
        String targetGeneration,
        String targetKid,
        Optional<String> expectedActiveGeneration,
        Optional<String> expectedActiveKid,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond,
        PodTarget target) {
      this.rotationOperationId = Objects.requireNonNull(rotationOperationId);
      requireUuid(rotationOperationId.toString(), "rotation operation ID");
      requireDigest(operationDigest, "generation operation digest");
      requireDigest(planDigest, "readiness plan digest");
      if (planVersion != AccountJwtReadinessProbeRepository.INVENTORY_PLAN_VERSION
          || planExpiresAtEpochSecond <= 0L) {
        throw new IllegalArgumentException("Exact V2 readiness plan coordinates are required");
      }
      requireId(validatorId, "validator ID");
      if (tokenProfile == null || !tokenProfile.matches("[a-z][a-z0-9-]{0,127}")) {
        throw new IllegalArgumentException("Token profile is malformed");
      }
      if (audience == null || !audience.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
        throw new IllegalArgumentException("Token audience is malformed");
      }
      this.operationDigest = operationDigest;
      this.planDigest = planDigest;
      this.planVersion = planVersion;
      this.planExpiresAtEpochSecond = planExpiresAtEpochSecond;
      this.validatorId = validatorId;
      this.tokenProfile = tokenProfile;
      this.audience = audience;
      this.probeKind = Objects.requireNonNull(probeKind);
      this.expectation = Objects.requireNonNull(expectation);
      if (registryVersion != 1 || sourceEntryVersion <= 0L) {
        throw new IllegalArgumentException("Readiness entry version is malformed");
      }
      this.registryVersion = registryVersion;
      this.sourceEntryVersion = sourceEntryVersion;
      this.jti = Objects.requireNonNull(jti);
      requireUuid(jti.toString(), "probe JTI");
      if (compactJwt == null
          || compactJwt.isBlank()
          || compactJwt.length() > 16 * 1024
          || !compactJwt.matches("[A-Za-z0-9_=-]+\\.[A-Za-z0-9_=-]+\\.[A-Za-z0-9_=-]+")) {
        throw new IllegalArgumentException("Compact readiness token is malformed");
      }
      requireDigest(compactTokenSha256, "compact readiness token digest");
      if (!compactTokenSha256.equals(sha256(compactJwt))) {
        throw new IllegalArgumentException("Compact readiness token digest does not match bytes");
      }
      this.compactJwt = compactJwt;
      this.compactTokenSha256 = compactTokenSha256;
      if (targetGeneration == null || !targetGeneration.matches("[1-9][0-9]{0,18}")) {
        throw new IllegalArgumentException("Target generation is malformed");
      }
      if (targetKid == null || !targetKid.matches("[A-Za-z0-9_-]{1,64}")) {
        throw new IllegalArgumentException("Target key ID is malformed");
      }
      this.targetGeneration = targetGeneration;
      this.targetKid = targetKid;
      this.expectedActiveGeneration = Objects.requireNonNull(expectedActiveGeneration);
      this.expectedActiveKid = Objects.requireNonNull(expectedActiveKid);
      if (expectedActiveGeneration.isPresent() != expectedActiveKid.isPresent()) {
        throw new IllegalArgumentException("Expected active signer fence is partial");
      }
      this.expectedActiveGeneration.ifPresent(
          value -> {
            if (!value.matches("[1-9][0-9]{0,18}")) {
              throw new IllegalArgumentException("Expected active generation is malformed");
            }
          });
      this.expectedActiveKid.ifPresent(
          value -> {
            if (!value.matches("[A-Za-z0-9_-]{1,64}")) {
              throw new IllegalArgumentException("Expected active key ID is malformed");
            }
          });
      if (issuedAtEpochSecond <= 0L
          || expiresAtEpochSecond <= issuedAtEpochSecond
          || expiresAtEpochSecond > planExpiresAtEpochSecond) {
        throw new IllegalArgumentException("Readiness token lifetime is malformed");
      }
      this.issuedAtEpochSecond = issuedAtEpochSecond;
      this.expiresAtEpochSecond = expiresAtEpochSecond;
      this.target = Objects.requireNonNull(target);
      if (!validatorId.equals(target.validatorId())) {
        throw new IllegalArgumentException("Readiness probe and Pod validator differ");
      }
    }

    public UUID rotationOperationId() {
      return rotationOperationId;
    }

    public String operationDigest() {
      return operationDigest;
    }

    public String planDigest() {
      return planDigest;
    }

    public int planVersion() {
      return planVersion;
    }

    public long planExpiresAtEpochSecond() {
      return planExpiresAtEpochSecond;
    }

    public String validatorId() {
      return validatorId;
    }

    public String tokenProfile() {
      return tokenProfile;
    }

    public String audience() {
      return audience;
    }

    public ProbeKind probeKind() {
      return probeKind;
    }

    public ProbeExpectation expectation() {
      return expectation;
    }

    public int registryVersion() {
      return registryVersion;
    }

    public long sourceEntryVersion() {
      return sourceEntryVersion;
    }

    public UUID jti() {
      return jti;
    }

    public String compactJwt() {
      return compactJwt;
    }

    public String compactTokenSha256() {
      return compactTokenSha256;
    }

    public String targetGeneration() {
      return targetGeneration;
    }

    public String targetKid() {
      return targetKid;
    }

    public Optional<String> expectedActiveGeneration() {
      return expectedActiveGeneration;
    }

    public Optional<String> expectedActiveKid() {
      return expectedActiveKid;
    }

    public long issuedAtEpochSecond() {
      return issuedAtEpochSecond;
    }

    public long expiresAtEpochSecond() {
      return expiresAtEpochSecond;
    }

    public PodTarget target() {
      return target;
    }

    @Override
    public String toString() {
      return "AccountJwtReadinessReceiverInvocation[rotationOperationId="
          + rotationOperationId
          + ", validatorId="
          + validatorId
          + ", podUid="
          + target.podUid()
          + ", jti="
          + jti
          + ", tokenRedacted=true]";
    }
  }

  public enum ProbeExpectation {
    ACCEPT,
    INAPPLICABLE_REJECT
  }

  /**
   * Internal result established by the Account-owned receiver client after mTLS and response
   * identity checks. Its package-private constructor is not available to an RPC adapter/harness.
   */
  public static final class AuthenticatedAcceptance {
    private final String podUid;
    private final String actualPodEndpoint;
    private final String image;
    private final String verifierConfigSha256;
    private final String actualServiceUri;
    private final String actualPeerSpkiSha256;
    private final UUID jti;
    private final String compactTokenSha256;
    private final String verifiedKid;
    private final ProbeExpectation result;
    private final long observedAtEpochSecond;

    private AuthenticatedAcceptance(
        String podUid,
        String actualPodEndpoint,
        String image,
        String verifierConfigSha256,
        String actualServiceUri,
        String actualPeerSpkiSha256,
        UUID jti,
        String compactTokenSha256,
        String verifiedKid,
        ProbeExpectation result,
        long observedAtEpochSecond) {
      requireCanonicalUuid(podUid, "receiver-reported local Pod UID");
      requirePodEndpointShape(actualPodEndpoint);
      if (image == null || !image.matches("[^\\s@]+@sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Receiver image is not pinned by digest");
      }
      requireDigest(verifierConfigSha256, "receiver verifier configuration digest");
      requireServiceUri(actualServiceUri);
      requireDigest(actualPeerSpkiSha256, "authenticated peer SPKI digest");
      Objects.requireNonNull(jti);
      requireUuid(jti.toString(), "receiver probe JTI");
      requireDigest(compactTokenSha256, "receiver compact token digest");
      if (verifiedKid == null || !verifiedKid.matches("[A-Za-z0-9_-]{1,64}")) {
        throw new IllegalArgumentException("Receiver verified key ID is malformed");
      }
      this.result = Objects.requireNonNull(result);
      if (observedAtEpochSecond <= 0L) {
        throw new IllegalArgumentException("Receiver observation time is malformed");
      }
      this.podUid = podUid;
      this.actualPodEndpoint = actualPodEndpoint;
      this.image = image;
      this.verifierConfigSha256 = verifierConfigSha256;
      this.actualServiceUri = actualServiceUri;
      this.actualPeerSpkiSha256 = actualPeerSpkiSha256;
      this.jti = jti;
      this.compactTokenSha256 = compactTokenSha256;
      this.verifiedKid = verifiedKid;
      this.observedAtEpochSecond = observedAtEpochSecond;
    }

    public String podUid() {
      return podUid;
    }

    public String actualPodEndpoint() {
      return actualPodEndpoint;
    }

    public String image() {
      return image;
    }

    public String verifierConfigSha256() {
      return verifierConfigSha256;
    }

    public String actualServiceUri() {
      return actualServiceUri;
    }

    public String actualPeerSpkiSha256() {
      return actualPeerSpkiSha256;
    }

    public UUID jti() {
      return jti;
    }

    public String compactTokenSha256() {
      return compactTokenSha256;
    }

    public String verifiedKid() {
      return verifiedKid;
    }

    public ProbeExpectation result() {
      return result;
    }

    public long observedAtEpochSecond() {
      return observedAtEpochSecond;
    }

    @Override
    public String toString() {
      return "AccountJwtReadinessAuthenticatedAcceptance[podUid="
          + podUid
          + ", jti="
          + jti
          + ", tokenRedacted=true]";
    }
  }

  public static final class ReceiverUnavailableException extends IllegalStateException {
    public ReceiverUnavailableException() {
      super("Account JWT readiness receiver transport is unavailable");
    }
  }

  private static void requirePodEndpoint(URI endpoint, String podIp) {
    String endpointHost = podIp.indexOf(':') >= 0 ? "[" + podIp + "]" : podIp;
    if (!"grpcs".equals(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getPort() < 1
        || endpoint.getPort() > 65535
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null
        || (endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty())
        || !endpoint.toString().equals("grpcs://" + endpointHost + ":" + endpoint.getPort())) {
      throw new IllegalArgumentException("Exact Pod endpoint is malformed");
    }
  }

  private static void requirePodEndpointShape(String value) {
    try {
      URI endpoint = URI.create(value);
      if (!"grpcs".equals(endpoint.getScheme())
          || endpoint.getHost() == null
          || endpoint.getPort() < 1
          || endpoint.getPort() > 65535
          || endpoint.getRawUserInfo() != null
          || endpoint.getRawQuery() != null
          || endpoint.getRawFragment() != null
          || (endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty())) {
        throw new IllegalArgumentException("Observed Pod endpoint is malformed");
      }
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Observed Pod endpoint is malformed", failure);
    }
  }

  private static void requireServiceUri(String value) {
    if (value == null || !SERVICE_URI.matcher(value).matches()) {
      throw new IllegalArgumentException("Canonical validator service SAN is malformed");
    }
  }

  private static void requireDigest(String value, String name) {
    if (value == null || !DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " is malformed");
    }
  }

  private static void requireUuid(String value, String name) {
    if (value == null || !UUID_V4.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " is malformed");
    }
  }

  private static void requireCanonicalUuid(String value, String name) {
    if (value == null || !UUID_CANONICAL.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " is malformed");
    }
  }

  private static void requireId(String value, String name) {
    if (value == null || !ID.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " is malformed");
    }
  }

  private static void requireIdentity(String value, String name) {
    if (value == null || !value.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
      throw new IllegalArgumentException(name + " is malformed");
    }
  }

  private static void requireCluster(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("cluster ID is malformed");
    }
  }

  private static void requireRevision(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException(name + " is malformed");
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }
}
