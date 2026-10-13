package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.v1.AccountPodTargetBinding;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeRequest;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeResponse;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.OwnerProbeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerSelector.LocalIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.PodTarget;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ProbeExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;

/** Strict closed-schema mapper for Account's separate production Pod receiver RPC. */
public final class AccountJwtReadinessPodReceiverProtoMapper {
  private static final int SCHEMA_VERSION = 1;
  private static final int MAX_COMPACT_TOKEN_BYTES = 16 * 1024;
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern GENERATION = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  public ParsedRequest parse(ReceiveAccountJwtReadinessProbeRequest request) {
    if (request == null
        || request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasCoordinates()
        || request.getCompactJwt().isEmpty()
        || request.getCompactJwt().length() > MAX_COMPACT_TOKEN_BYTES
        || !isAscii(request.getCompactJwt())) {
      throw new InvalidReceiverRequestException();
    }
    ReadinessProbeCoordinates coordinates = request.getCoordinates();
    if (!coordinates.getUnknownFields().asMap().isEmpty()) {
      throw new InvalidReceiverRequestException();
    }
    try {
      ProbeKind probeKind =
          switch (coordinates.getProbeKind()) {
            case CANARY -> ProbeKind.CANARY;
            case REPRESENTATIVE -> ProbeKind.REPRESENTATIVE;
            case PROBE_KIND_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalArgumentException();
          };
      if (!AccountJwtReadinessProbeCrypto.VALIDATOR_ID.equals(coordinates.getValidatorId())
          || coordinates.getExpectedOutcome() != ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT) {
        throw new IllegalArgumentException();
      }
      if (probeKind == ProbeKind.CANARY) {
        if (!AccountJwtReadinessProbeCrypto.CANARY_PROFILE.equals(coordinates.getTokenProfile())
            || !AccountJwtReadinessProbeCrypto.CANARY_AUDIENCE.equals(coordinates.getAudience())) {
          throw new IllegalArgumentException();
        }
      } else {
        AccountJwtReadinessProbeCrypto.ProfileDescriptor profile =
            AccountJwtReadinessProbeCrypto.representativeProfile(coordinates.getTokenProfile());
        if (profile == null || !profile.audience().equals(coordinates.getAudience())) {
          throw new IllegalArgumentException();
        }
      }
      Optional<ActiveSigner> expectedActive =
          switch (coordinates.getExpectedActiveState()) {
            case ABSENT -> {
              if (!coordinates.getExpectedActiveGeneration().isEmpty()
                  || !coordinates.getExpectedActiveKid().isEmpty()) {
                throw new IllegalArgumentException();
              }
              yield Optional.empty();
            }
            case PRESENT -> {
              if (coordinates.getExpectedActiveGeneration().isEmpty()
                  || coordinates.getExpectedActiveKid().isEmpty()) {
                throw new IllegalArgumentException();
              }
              yield Optional.of(
                  new ActiveSigner(
                      coordinates.getExpectedActiveGeneration(),
                      coordinates.getExpectedActiveKid()));
            }
            case EXPECTED_ACTIVE_STATE_UNSPECIFIED, UNKNOWN, UNRECOGNIZED ->
                throw new IllegalArgumentException();
          };
      UUID operationId = canonicalUuid(coordinates.getRotationOperationId());
      UUID jti = canonicalUuid(coordinates.getJti());
      requireCoordinateBounds(coordinates);
      byte[] compactBytes = request.getCompactJwt().getBytes(StandardCharsets.US_ASCII);
      try {
        return new ParsedRequest(
            coordinates,
            operationId,
            jti,
            probeKind,
            expectedActive,
            request.getCompactJwt(),
            sha256(compactBytes));
      } finally {
        java.util.Arrays.fill(compactBytes, (byte) 0);
      }
    } catch (RuntimeException invalid) {
      throw new InvalidReceiverRequestException();
    }
  }

  public AccountJwtReadinessProbeOwnerSelector toOwnerSelector(
      ParsedRequest request, LocalIdentity localIdentity) {
    ReadinessProbeCoordinates coordinates = request.coordinates();
    return new AccountJwtReadinessProbeOwnerSelector(
        request.operationId(),
        coordinates.getOperationDigest(),
        coordinates.getPlanDigest(),
        coordinates.getPlanVersion(),
        coordinates.getRegistryVersion(),
        coordinates.getValidatorId(),
        coordinates.getTokenProfile(),
        coordinates.getAudience(),
        request.probeKind(),
        ProbeExpectation.ACCEPT,
        request.jti(),
        coordinates.getEntryVersion(),
        coordinates.getTargetGeneration(),
        coordinates.getTargetKid(),
        request.expectedActive(),
        coordinates.getIssuedAtEpochSeconds(),
        coordinates.getExpiresAtEpochSeconds(),
        coordinates.getPlanExpiresAtEpochSeconds(),
        request.compactTokenSha256(),
        localIdentity);
  }

  public ReceiveAccountJwtReadinessProbeResponse toResponse(
      ParsedRequest request,
      String verifiedKeyId,
      AccountJwtReadinessPodLocalIdentityProvider.LocalObservation local,
      OwnerProbeEvidence owner,
      long observedAtEpochSeconds) {
    return ReceiveAccountJwtReadinessProbeResponse.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setCoordinates(request.coordinates())
        .setCompactTokenSha256(request.compactTokenSha256())
        .setVerifiedKeyId(verifiedKeyId)
        .setReceiverIdentity(local.wireIdentity())
        .setAccountPodTarget(toTarget(owner))
        .setObservedAtEpochSeconds(observedAtEpochSeconds)
        .setOutcome(ReceiveAccountJwtReadinessProbeResponse.ObservationOutcome.VERIFIED)
        .build();
  }

  private static AccountPodTargetBinding toTarget(OwnerProbeEvidence owner) {
    PodTarget target = owner.expectedPod().target();
    return AccountPodTargetBinding.newBuilder()
        .setInventorySnapshotDigest(target.inventorySnapshotDigest())
        .setEnvironmentId(target.environmentId())
        .setClusterId(target.clusterId())
        .setClusterIncarnationUid(target.clusterIncarnationUid())
        .setNamespace(target.namespace())
        .setNamespaceUid(target.namespaceUid())
        .setApiBindingRevision(target.apiBindingRevision())
        .setApiBindingDigest(target.apiBindingDigest())
        .setInventoryBindingRevision(target.inventoryBindingRevision())
        .setInventoryBindingDigest(target.inventoryBindingDigest())
        .setValidatorId(target.validatorId())
        .setDeploymentUid(target.deploymentUid())
        .setPodUid(target.podUid())
        .setPodIp(target.podIp())
        .setImage(target.image())
        .setVerifierConfigSha256(target.verifierConfigSha256())
        .setApplicabilityMatrixDigest(target.applicabilityMatrixDigest())
        .setExpectedOutcome(
            target.expectation() == ProbeExpectation.ACCEPT
                ? ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT
                : ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT)
        .setDirectPodEndpoint(target.exactPodEndpoint().orElseThrow().toString())
        .setCanonicalServiceUri(target.canonicalServiceUri().orElseThrow())
        .setServerLeafSpkiSha256(target.podLeafSpkiSha256().orElseThrow())
        .build();
  }

  private static UUID canonicalUuid(String value) {
    UUID uuid = UUID.fromString(value);
    if (!uuid.toString().equals(value) || uuid.version() != 4 || uuid.variant() != 2) {
      throw new IllegalArgumentException();
    }
    return uuid;
  }

  private static void requireCoordinateBounds(ReadinessProbeCoordinates coordinates) {
    if (!DIGEST.matcher(coordinates.getOperationDigest()).matches()
        || !DIGEST.matcher(coordinates.getPlanDigest()).matches()
        || coordinates.getPlanVersion() != AccountJwtReadinessProbeRepository.INVENTORY_PLAN_VERSION
        || coordinates.getRegistryVersion() != 1
        || coordinates.getEntryVersion() <= 0L
        || !GENERATION.matcher(coordinates.getTargetGeneration()).matches()
        || !KID.matcher(coordinates.getTargetKid()).matches()
        || coordinates.getIssuedAtEpochSeconds() <= 0L
        || coordinates.getExpiresAtEpochSeconds() <= coordinates.getIssuedAtEpochSeconds()
        || coordinates.getExpiresAtEpochSeconds() - coordinates.getIssuedAtEpochSeconds()
            > AccountJwtReadinessProbeRepository.PROBE_LIFETIME_SECONDS
        || coordinates.getPlanExpiresAtEpochSeconds() <= 0L
        || coordinates.getExpiresAtEpochSeconds() > coordinates.getPlanExpiresAtEpochSeconds()) {
      throw new IllegalArgumentException();
    }
  }

  private static boolean isAscii(String value) {
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) > 0x7f) {
        return false;
      }
    }
    return true;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception unavailable) {
      throw new InvalidReceiverRequestException();
    }
  }

  public record ParsedRequest(
      ReadinessProbeCoordinates coordinates,
      UUID operationId,
      UUID jti,
      ProbeKind probeKind,
      Optional<ActiveSigner> expectedActive,
      String compactJwt,
      String compactTokenSha256) {
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "ReadinessProbeCoordinates is an immutable generated protobuf value.")
    public ParsedRequest {}

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "ReadinessProbeCoordinates is an immutable generated protobuf value.")
    public ReadinessProbeCoordinates coordinates() {
      return coordinates;
    }
  }

  public static final class InvalidReceiverRequestException extends IllegalArgumentException {
    public InvalidReceiverRequestException() {
      super("Account Pod readiness receiver request is invalid");
    }
  }
}
