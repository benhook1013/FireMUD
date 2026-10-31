package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.v1.AccountPodTargetBinding;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeResponse;

/**
 * Closed-wire parser and exact Account owner evidence matcher for the GS Pod readiness receiver.
 */
public final class GameSessionJwtReadinessReceiverProtoMapper {
  public static final int SCHEMA_VERSION = 1;
  public static final int CURRENT_PLAN_VERSION = 2;
  public static final int CURRENT_REGISTRY_VERSION = 1;
  public static final int MAX_COMPACT_JWT_BYTES = 16 * 1024;
  public static final int MAX_PROBE_LIFETIME_SECONDS = 300;
  public static final String CANARY_PROFILE = "account-jwt-readiness-canary";
  public static final String CANARY_AUDIENCE = "firemud-account-jwt-readiness";
  public static final String GAME_SESSION_VALIDATOR = "game-session-service";

  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern GENERATION = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern PROFILE = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final java.util.Map<String, String> REPRESENTATIVE_AUDIENCES =
      java.util.Map.of(
          "control-ui", "control-ui",
          "player-bootstrap", "player-bootstrap",
          "game-session-account-delegation", "account-service");

  public ParsedRequest parse(ReceiveReadinessProbeRequest request) {
    if (request == null
        || request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasCoordinates()
        || request.getCompactJwt().isEmpty()
        || request.getCompactJwt().length() > MAX_COMPACT_JWT_BYTES
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
      ReadinessProbeCoordinates.ExpectedOutcome expectedOutcome = coordinates.getExpectedOutcome();
      if (!GAME_SESSION_VALIDATOR.equals(coordinates.getValidatorId())) {
        throw new IllegalArgumentException();
      }
      if (probeKind == ProbeKind.CANARY) {
        if (!CANARY_PROFILE.equals(coordinates.getTokenProfile())
            || !CANARY_AUDIENCE.equals(coordinates.getAudience())
            || expectedOutcome != ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT) {
          throw new IllegalArgumentException();
        }
      } else {
        String audience = REPRESENTATIVE_AUDIENCES.get(coordinates.getTokenProfile());
        ReadinessProbeCoordinates.ExpectedOutcome outcome =
            "game-session-account-delegation".equals(coordinates.getTokenProfile())
                ? ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT
                : ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT;
        if (audience == null
            || !audience.equals(coordinates.getAudience())
            || expectedOutcome != outcome) {
          throw new IllegalArgumentException();
        }
      }
      UUID operationId = canonicalUuid(coordinates.getRotationOperationId());
      UUID jti = canonicalUuid(coordinates.getJti());
      Optional<ActiveFence> expectedActive = parseActiveFence(coordinates);
      requireCoordinates(coordinates);
      byte[] compactBytes = request.getCompactJwt().getBytes(StandardCharsets.US_ASCII);
      try {
        return new ParsedRequest(
            coordinates,
            operationId,
            jti,
            probeKind,
            expectedActive,
            expectedOutcome,
            request.getCompactJwt(),
            sha256(compactBytes));
      } finally {
        java.util.Arrays.fill(compactBytes, (byte) 0);
      }
    } catch (RuntimeException invalid) {
      if (invalid instanceof InvalidReceiverRequestException receiverException) {
        throw receiverException;
      }
      throw new InvalidReceiverRequestException();
    }
  }

  public GetCurrentReadinessProbeOwnerRequest ownerReadRequest(
      ParsedRequest request, ReadinessReceiverLocalIdentity localIdentity) {
    if (localIdentity == null
        || !localIdentity.getUnknownFields().asMap().isEmpty()
        || !localIdentity.hasAccountJwksSourceIdentity()
        || !localIdentity.getAccountJwksSourceIdentity().getUnknownFields().asMap().isEmpty()) {
      throw new IdentityUnavailableException();
    }
    return GetCurrentReadinessProbeOwnerRequest.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setExpectedCoordinates(request.coordinates())
        .setCompactTokenSha256(request.compactTokenSha256())
        .setProtectedLocalIdentity(localIdentity)
        .build();
  }

  public OwnerEvidence requireCurrentOwner(
      ParsedRequest request,
      ReadinessReceiverLocalIdentity localIdentity,
      GetCurrentReadinessProbeOwnerResponse response) {
    if (response == null
        || response.getSchemaVersion() != SCHEMA_VERSION
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.getOwnerRecordFound()
        || !response.hasCurrentCoordinates()
        || !response.hasCurrentPodTarget()
        || response.getCurrentState() != GetCurrentReadinessProbeOwnerResponse.ProbeState.ISSUED
        || !response.getCurrentCoordinates().getUnknownFields().asMap().isEmpty()
        || !response.hasCurrentPodTarget()
        || !response.getCurrentPodTarget().getUnknownFields().asMap().isEmpty()
        || !request.coordinates().equals(response.getCurrentCoordinates())
        || !request.compactTokenSha256().equals(response.getCompactTokenSha256())
        || !DIGEST.matcher(response.getCompactTokenSha256()).matches()) {
      throw new OwnerEvidenceUnavailableException();
    }
    AccountPodTargetBinding target = response.getCurrentPodTarget();
    if (!GAME_SESSION_VALIDATOR.equals(target.getValidatorId())
        || !localIdentity.getValidatorId().equals(target.getValidatorId())
        || !localIdentity.getDeploymentUid().equals(target.getDeploymentUid())
        || !localIdentity.getPodUid().equals(target.getPodUid())
        || !localIdentity.getPodIp().equals(target.getPodIp())
        || !localIdentity.getDirectPodEndpoint().equals(target.getDirectPodEndpoint())
        || !localIdentity.getCanonicalServiceUri().equals(target.getCanonicalServiceUri())
        || !localIdentity.getImage().equals(target.getImage())
        || !localIdentity.getVerifierConfigSha256().equals(target.getVerifierConfigSha256())
        || !localIdentity
            .getApplicabilityMatrixDigest()
            .equals(target.getApplicabilityMatrixDigest())
        || !localIdentity.getServerLeafSpkiSha256().equals(target.getServerLeafSpkiSha256())
        || target.getExpectedOutcome() != request.expectedOutcome()
        || !sourceIdentityMatchesTarget(localIdentity, target)
        || !DIGEST.matcher(target.getInventorySnapshotDigest()).matches()
        || !DIGEST.matcher(target.getApiBindingDigest()).matches()
        || !DIGEST.matcher(target.getInventoryBindingDigest()).matches()) {
      throw new OwnerEvidenceUnavailableException();
    }
    return new OwnerEvidence(response, target);
  }

  public ReceiveReadinessProbeResponse response(
      ParsedRequest request,
      String verifiedKeyId,
      ReadinessReceiverLocalIdentity localIdentity,
      OwnerEvidence owner,
      long observedAtEpochSeconds,
      ReceiveReadinessProbeResponse.ObservationOutcome outcome) {
    if (!KID.matcher(verifiedKeyId).matches()
        || !verifiedKeyId.equals(request.coordinates().getTargetKid())
        || observedAtEpochSeconds < request.coordinates().getIssuedAtEpochSeconds()
        || observedAtEpochSeconds >= request.coordinates().getExpiresAtEpochSeconds()
        || observedAtEpochSeconds >= request.coordinates().getPlanExpiresAtEpochSeconds()) {
      throw new OwnerEvidenceUnavailableException();
    }
    return ReceiveReadinessProbeResponse.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setCoordinates(request.coordinates())
        .setCompactTokenSha256(request.compactTokenSha256())
        .setVerifiedKeyId(verifiedKeyId)
        .setReceiverIdentity(localIdentity)
        .setAccountPodTarget(owner.target())
        .setObservedAtEpochSeconds(observedAtEpochSeconds)
        .setOutcome(outcome)
        .build();
  }

  private static Optional<ActiveFence> parseActiveFence(ReadinessProbeCoordinates coordinates) {
    return switch (coordinates.getExpectedActiveState()) {
      case ABSENT -> {
        if (!coordinates.getExpectedActiveGeneration().isEmpty()
            || !coordinates.getExpectedActiveKid().isEmpty()) {
          throw new IllegalArgumentException();
        }
        yield Optional.empty();
      }
      case PRESENT -> {
        if (!GENERATION.matcher(coordinates.getExpectedActiveGeneration()).matches()
            || !KID.matcher(coordinates.getExpectedActiveKid()).matches()) {
          throw new IllegalArgumentException();
        }
        yield Optional.of(
            new ActiveFence(
                coordinates.getExpectedActiveGeneration(), coordinates.getExpectedActiveKid()));
      }
      case EXPECTED_ACTIVE_STATE_UNSPECIFIED, UNKNOWN, UNRECOGNIZED ->
          throw new IllegalArgumentException();
    };
  }

  private static void requireCoordinates(ReadinessProbeCoordinates value) {
    if (!DIGEST.matcher(value.getOperationDigest()).matches()
        || !DIGEST.matcher(value.getPlanDigest()).matches()
        || value.getPlanVersion() != CURRENT_PLAN_VERSION
        || value.getRegistryVersion() != CURRENT_REGISTRY_VERSION
        || value.getEntryVersion() <= 0L
        || !GENERATION.matcher(value.getTargetGeneration()).matches()
        || !KID.matcher(value.getTargetKid()).matches()
        || !PROFILE.matcher(value.getTokenProfile()).matches()
        || !PROFILE.matcher(value.getAudience()).matches()
        || value.getIssuedAtEpochSeconds() <= 0L
        || value.getExpiresAtEpochSeconds() <= value.getIssuedAtEpochSeconds()
        || value.getExpiresAtEpochSeconds() - value.getIssuedAtEpochSeconds()
            > MAX_PROBE_LIFETIME_SECONDS
        || value.getPlanExpiresAtEpochSeconds() < value.getExpiresAtEpochSeconds()) {
      throw new IllegalArgumentException();
    }
  }

  private static UUID canonicalUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value) || parsed.version() != 4 || parsed.variant() != 2) {
      throw new IllegalArgumentException();
    }
    return parsed;
  }

  private static boolean sourceIdentityMatchesTarget(
      ReadinessReceiverLocalIdentity local, AccountPodTargetBinding target) {
    var source = local.getAccountJwksSourceIdentity();
    return source.getEnvironmentId().equals(target.getEnvironmentId())
        && source.getClusterId().equals(target.getClusterId())
        && source.getClusterIncarnationUid().equals(target.getClusterIncarnationUid())
        && source.getNamespace().equals(target.getNamespace())
        && source.getNamespaceUid().equals(target.getNamespaceUid());
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

  public enum ProbeKind {
    CANARY,
    REPRESENTATIVE
  }

  public record ActiveFence(String generation, String keyId) {}

  public record ParsedRequest(
      ReadinessProbeCoordinates coordinates,
      UUID operationId,
      UUID jti,
      ProbeKind probeKind,
      Optional<ActiveFence> expectedActive,
      ReadinessProbeCoordinates.ExpectedOutcome expectedOutcome,
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

  public record OwnerEvidence(
      GetCurrentReadinessProbeOwnerResponse response, AccountPodTargetBinding target) {
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "GetCurrentReadinessProbeOwnerResponse and AccountPodTargetBinding are immutable generated protobuf values.")
    public OwnerEvidence {}

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "GetCurrentReadinessProbeOwnerResponse is an immutable generated protobuf value.")
    public GetCurrentReadinessProbeOwnerResponse response() {
      return response;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "AccountPodTargetBinding is an immutable generated protobuf value.")
    public AccountPodTargetBinding target() {
      return target;
    }
  }

  public static final class InvalidReceiverRequestException extends IllegalArgumentException {
    public InvalidReceiverRequestException() {
      super("Game Session readiness receiver request is invalid");
    }
  }

  public static final class IdentityUnavailableException extends RuntimeException {
    public IdentityUnavailableException() {
      super("Protected Game Session receiver identity is unavailable");
    }
  }

  public static final class OwnerEvidenceUnavailableException extends RuntimeException {
    public OwnerEvidenceUnavailableException() {
      super("Current Account readiness owner evidence is unavailable");
    }
  }
}
