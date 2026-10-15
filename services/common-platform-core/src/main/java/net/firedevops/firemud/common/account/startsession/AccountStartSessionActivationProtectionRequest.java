package net.firedevops.firemud.common.account.startsession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exact integrity-only inputs for Account source protection through original StartSession World
 * activation.
 *
 * <p>This carrier preserves the complete original tuple, Account projection, finite original Game
 * Session lease, the canonical direct launch-association response, and the exact World PREPARING
 * activation request. The separate CurrentAttempt response bytes remain available for Account's
 * existing V160 child storage without changing V1003's closed witness. This carrier authenticates
 * neither owner, establishes no current source state, and grants no activation or gameplay admission.
 */
public final class AccountStartSessionActivationProtectionRequest {
  public static final String SCHEMA = "account-start-session-activation-protection-request/v1";

  /**
   * New Account-carrier acceptance bound for one complete World PREPARING child. The existing World
   * lifecycle evidence has no whole-envelope byte cap; Account rejects larger children rather than
   * truncating or reinterpreting them. This is not a World transport limit.
   */
  public static final int MAX_WORLD_PREPARING_EVIDENCE_BYTES = 64 * 1024 * 1024;

  /**
   * Aggregate closed-envelope bound: Base64 expansion of the existing 41 MiB launch-result and 256
   * KiB tuple caps, this carrier's 64 MiB PREPARING child cap, and the existing Account projection
   * cap is about 140.6 MiB; the remaining space covers the fixed JSON envelope. This bounds this
   * new Account value only and does not change any owner transport limit.
   */
  public static final int MAX_CANONICAL_BYTES = 144 * 1024 * 1024;

  public static final int MAX_PROJECTION_BYTES =
      AccountStartSessionAdmissionProtectionRequest.MAX_PROJECTION_BYTES;
  public static final int MAX_GAME_SESSION_LAUNCH_EVIDENCE_BYTES = 41 * 1024 * 1024;

  private static final String OPERATION_IDENTITY_SCHEMA =
      "account-start-session-activation-protection-operation-identity/v1";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern MICROS_UTC =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");
  private static final DateTimeFormatter MICROS_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "originalPostAuthorizationTupleBytesBase64",
          "accountRedemptionProjectionBytesBase64",
          "gameSessionOwnerMutationId",
          "gameSessionOwnerAttemptId",
          "gameSessionOwnerFence",
          "originalLeaseExpiresAt",
          "accountWorldParticipationId",
          "accountWorldParticipationFence",
          "gameSessionLaunchReadEvidenceBytesBase64",
          "worldActivationRequestId",
          "preparingEvidenceBytesBase64");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final byte[] originalPostAuthorizationTupleBytes;
  private final byte[] accountRedemptionProjectionBytes;
  private final UUID gameSessionOwnerMutationId;
  private final UUID gameSessionOwnerAttemptId;
  private final long gameSessionOwnerFence;
  private final Instant originalLeaseExpiresAt;
  private final UUID accountWorldParticipationId;
  private final long accountWorldParticipationFence;
  private final CanonicalGameInstanceLaunchAssociationReadEvidence.Result launchAssociation;
  private final OriginalStartSessionCurrentAttemptEvidence.Result originalCurrentAttempt;
  private final byte[] originalAttemptResponseBytes;
  private final WorldCanonicalInstanceActivation.Request worldActivationRequest;
  private final StartSessionPostAuthorizationExecutionTuple originalTuple;
  private final byte[] canonicalBytes;
  private final byte[] operationIdentityBytes;

  private AccountStartSessionActivationProtectionRequest(
      byte[] originalPostAuthorizationTupleBytes,
      byte[] accountRedemptionProjectionBytes,
      UUID gameSessionOwnerMutationId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      Instant originalLeaseExpiresAt,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Result launchAssociation,
      OriginalStartSessionCurrentAttemptEvidence.Result originalCurrentAttempt,
      byte[] originalAttemptResponseBytes,
      WorldCanonicalInstanceActivation.Request worldActivationRequest,
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      byte[] canonicalBytes,
      byte[] operationIdentityBytes) {
    this.originalPostAuthorizationTupleBytes = originalPostAuthorizationTupleBytes.clone();
    this.accountRedemptionProjectionBytes = accountRedemptionProjectionBytes.clone();
    this.gameSessionOwnerMutationId = gameSessionOwnerMutationId;
    this.gameSessionOwnerAttemptId = gameSessionOwnerAttemptId;
    this.gameSessionOwnerFence = gameSessionOwnerFence;
    this.originalLeaseExpiresAt = originalLeaseExpiresAt;
    this.accountWorldParticipationId = accountWorldParticipationId;
    this.accountWorldParticipationFence = accountWorldParticipationFence;
    this.launchAssociation = Objects.requireNonNull(launchAssociation);
    this.originalCurrentAttempt = originalCurrentAttempt;
    this.originalAttemptResponseBytes =
        originalAttemptResponseBytes == null ? null : originalAttemptResponseBytes.clone();
    this.worldActivationRequest = worldActivationRequest;
    this.originalTuple = originalTuple;
    this.canonicalBytes = canonicalBytes.clone();
    this.operationIdentityBytes = operationIdentityBytes.clone();
  }

  /**
   * Creates one full request and validates the exact original-to-STARTING-to-PREPARING bindings.
   */
  public static AccountStartSessionActivationProtectionRequest create(
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Result launchAssociation,
      OriginalStartSessionCurrentAttemptEvidence.Result originalCurrentAttempt,
      WorldCanonicalInstanceActivation.Request worldActivationRequest) {
    Objects.requireNonNull(originalCurrentAttempt, "originalCurrentAttempt");
    var currentAttemptRequest = originalCurrentAttempt.request();
    return createRetained(
        currentAttemptRequest.canonicalPostAuthorizationTuple(),
        originalCurrentAttempt.accountRedemptionProjection(),
        currentAttemptRequest.expectedOwnerMutationId(),
        currentAttemptRequest.expectedOwnerAttemptId(),
        currentAttemptRequest.expectedOwnerFence(),
        originalCurrentAttempt.originalLeaseExpiresAt(),
        accountWorldParticipationId,
        accountWorldParticipationFence,
        launchAssociation,
        originalCurrentAttempt,
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(originalCurrentAttempt)
            .toByteArray(),
        worldActivationRequest);
  }

  private static AccountStartSessionActivationProtectionRequest createRetained(
      byte[] exactCanonicalOriginalPostAuthorizationTupleBytes,
      byte[] exactCanonicalAccountRedemptionProjectionBytes,
      UUID gameSessionOwnerMutationId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      Instant originalLeaseExpiresAt,
      UUID accountWorldParticipationId,
      long accountWorldParticipationFence,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Result launchAssociation,
      OriginalStartSessionCurrentAttemptEvidence.Result originalCurrentAttempt,
      byte[] originalAttemptResponseBytes,
      WorldCanonicalInstanceActivation.Request worldActivationRequest) {
    byte[] tupleBytes =
        bounded(
            exactCanonicalOriginalPostAuthorizationTupleBytes,
            StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES,
            "original post-authorization tuple");
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
    if (!Arrays.equals(tupleBytes, tuple.canonicalBytes())) {
      throw invalid("Original post-authorization tuple bytes are not exact canonical bytes");
    }

    byte[] projectionBytes =
        bounded(
            exactCanonicalAccountRedemptionProjectionBytes,
            MAX_PROJECTION_BYTES,
            "Account redemption projection");
    byte[] expectedProjection = StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple);
    if (!MessageDigest.isEqual(expectedProjection, projectionBytes)) {
      throw invalid("Complete Account redemption projection differs from the original tuple");
    }

    requireNonNil(gameSessionOwnerMutationId, "gameSessionOwnerMutationId");
    requireNonNil(gameSessionOwnerAttemptId, "gameSessionOwnerAttemptId");
    requirePositive(gameSessionOwnerFence, "gameSessionOwnerFence");
    Objects.requireNonNull(originalLeaseExpiresAt, "originalLeaseExpiresAt is required");
    String expiryText = canonicalExpiry(originalLeaseExpiresAt);
    requireNonNil(accountWorldParticipationId, "accountWorldParticipationId");
    requirePositive(accountWorldParticipationFence, "accountWorldParticipationFence");
    Objects.requireNonNull(launchAssociation, "launchAssociation");
    Objects.requireNonNull(worldActivationRequest, "worldActivationRequest");

    byte[] launchBytes =
        bounded(
            CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(launchAssociation)
                .toByteArray(),
            MAX_GAME_SESSION_LAUNCH_EVIDENCE_BYTES,
            "Game Session launch read evidence");
    CanonicalGameInstanceLaunchAssociationReadEvidence.Result decodedLaunch =
        decodeLaunchAssociation(launchBytes);
    if (originalCurrentAttempt != null) {
      if (!Arrays.equals(
              originalAttemptResponseBytes,
              OriginalStartSessionCurrentAttemptEvidenceGrpcCodec
                  .toResponse(originalCurrentAttempt)
                  .toByteArray())
          || !Arrays.equals(
              tupleBytes,
              originalCurrentAttempt.request().canonicalPostAuthorizationTuple())
          || !MessageDigest.isEqual(
              projectionBytes, originalCurrentAttempt.accountRedemptionProjection())
          || !gameSessionOwnerMutationId.equals(
              originalCurrentAttempt.request().expectedOwnerMutationId())
          || !gameSessionOwnerAttemptId.equals(
              originalCurrentAttempt.request().expectedOwnerAttemptId())
          || gameSessionOwnerFence
              != originalCurrentAttempt.request().expectedOwnerFence()
          || !originalLeaseExpiresAt.equals(originalCurrentAttempt.originalLeaseExpiresAt())) {
        throw invalid("Current-attempt observation differs from the exact original request");
      }
      StartSessionAccountRedemptionProjection.requireCurrentBinding(
          projectionBytes, originalCurrentAttempt.request().originalTuple());
    }
    byte[] preparingBytes =
        bounded(
            worldActivationRequest.preparingEvidenceBytes(),
            MAX_WORLD_PREPARING_EVIDENCE_BYTES,
            "World PREPARING evidence");
    WorldCanonicalInstanceLifecycleEvidence preparing =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(preparingBytes);
    if (!Arrays.equals(preparingBytes, preparing.canonicalBytes())) {
      throw invalid("World PREPARING evidence bytes are not exact canonical bytes");
    }
    WorldCanonicalInstanceActivation.Request decodedActivation =
        new WorldCanonicalInstanceActivation.Request(
            worldActivationRequest.activationRequestId(), preparing);
    if (!Arrays.equals(
        decodedActivation.preparingEvidenceBytes(),
        worldActivationRequest.preparingEvidenceBytes())) {
      throw invalid("World activation request differs from its complete PREPARING evidence");
    }

    requireExactOriginalStartBinding(
        tupleBytes,
        tuple,
        decodedLaunch,
        gameSessionOwnerMutationId,
        gameSessionOwnerAttemptId,
        gameSessionOwnerFence,
        originalCurrentAttempt,
        decodedActivation);
    byte[] canonical =
        encode(
            tupleBytes,
            projectionBytes,
            gameSessionOwnerMutationId,
            gameSessionOwnerAttemptId,
            gameSessionOwnerFence,
            expiryText,
            accountWorldParticipationId,
            accountWorldParticipationFence,
            launchBytes,
            decodedActivation.activationRequestId(),
            preparingBytes);
    byte[] operationIdentity =
        encodeOperationIdentity(
            tupleBytes,
            projectionBytes,
            gameSessionOwnerMutationId,
            gameSessionOwnerAttemptId,
            gameSessionOwnerFence,
            expiryText,
            accountWorldParticipationId,
            accountWorldParticipationFence,
            launchOperationIdentityBytes(launchBytes),
            decodedActivation.activationRequestId(),
            decodedActivation.canonicalRequestBytes());
    return new AccountStartSessionActivationProtectionRequest(
        tupleBytes,
        projectionBytes,
        gameSessionOwnerMutationId,
        gameSessionOwnerAttemptId,
        gameSessionOwnerFence,
        originalLeaseExpiresAt,
        accountWorldParticipationId,
        accountWorldParticipationFence,
        decodedLaunch,
        originalCurrentAttempt,
        originalAttemptResponseBytes,
        decodedActivation,
        tuple,
        canonical,
        operationIdentity);
  }

  /** Strictly decodes one complete canonical bounded request; decoding grants no authority. */
  public static AccountStartSessionActivationProtectionRequest decode(byte[] exactCanonicalBytes) {
    byte[] stored =
        bounded(exactCanonicalBytes, MAX_CANONICAL_BYTES, "activation protection request");
    try {
      String json = strictUtf8(stored);
      requireCanonicalJson(stored, json, "activation protection request");
      Map<String, Object> root = JSON.readValue(json, new TypeReference<>() {});
      requireExactFields(root, ROOT_FIELDS, "activation protection request");
      if (!SCHEMA.equals(string(root.get("schema"), "schema"))) {
        throw invalid("Unsupported Account activation protection request schema");
      }
      byte[] preparingBytes =
          bounded(
              decodeBase64(
                  root.get("preparingEvidenceBytesBase64"), "preparingEvidenceBytesBase64"),
              MAX_WORLD_PREPARING_EVIDENCE_BYTES,
              "World PREPARING evidence");
      WorldCanonicalInstanceLifecycleEvidence preparing =
          WorldCanonicalInstanceLifecycleEvidence.fromStored(preparingBytes);
      AccountStartSessionActivationProtectionRequest result =
          createRetained(
              decodeBase64(
                  root.get("originalPostAuthorizationTupleBytesBase64"),
                  "originalPostAuthorizationTupleBytesBase64"),
              decodeBase64(
                  root.get("accountRedemptionProjectionBytesBase64"),
                  "accountRedemptionProjectionBytesBase64"),
              canonicalUuid(root.get("gameSessionOwnerMutationId"), "gameSessionOwnerMutationId"),
              canonicalUuid(root.get("gameSessionOwnerAttemptId"), "gameSessionOwnerAttemptId"),
              positiveLong(root.get("gameSessionOwnerFence"), "gameSessionOwnerFence"),
              parseExpiry(string(root.get("originalLeaseExpiresAt"), "originalLeaseExpiresAt")),
              canonicalUuid(root.get("accountWorldParticipationId"), "accountWorldParticipationId"),
              positiveLong(
                  root.get("accountWorldParticipationFence"), "accountWorldParticipationFence"),
              decodeLaunchAssociation(
                  bounded(
                      decodeBase64(
                          root.get("gameSessionLaunchReadEvidenceBytesBase64"),
                          "gameSessionLaunchReadEvidenceBytesBase64"),
                      MAX_GAME_SESSION_LAUNCH_EVIDENCE_BYTES,
                      "Game Session launch read evidence")),
              null,
              null,
              new WorldCanonicalInstanceActivation.Request(
                  canonicalUuid(root.get("worldActivationRequestId"), "worldActivationRequestId"),
                  preparing));
      if (!MessageDigest.isEqual(stored, result.canonicalBytes)) {
        throw invalid("Account activation protection request members are inconsistent");
      }
      return result;
    } catch (IllegalArgumentException expected) {
      throw expected;
    } catch (IOException | RuntimeException malformed) {
      throw invalid("Account activation protection request is malformed");
    }
  }

  public byte[] originalPostAuthorizationTupleBytes() {
    return originalPostAuthorizationTupleBytes.clone();
  }

  public byte[] accountRedemptionProjectionBytes() {
    return accountRedemptionProjectionBytes.clone();
  }

  public UUID gameSessionOwnerMutationId() {
    return gameSessionOwnerMutationId;
  }

  public UUID gameSessionOwnerAttemptId() {
    return gameSessionOwnerAttemptId;
  }

  public long gameSessionOwnerFence() {
    return gameSessionOwnerFence;
  }

  public Instant originalLeaseExpiresAt() {
    return originalLeaseExpiresAt;
  }

  public UUID accountWorldParticipationId() {
    return accountWorldParticipationId;
  }

  public long accountWorldParticipationFence() {
    return accountWorldParticipationFence;
  }

  public CanonicalGameInstanceLaunchAssociationReadEvidence.Result launchAssociation() {
    return launchAssociation;
  }

  /** Exact canonical protobuf bytes retained in V1003's existing launch-evidence child. */
  public byte[] gameSessionLaunchReadEvidenceBytes() {
    return CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(launchAssociation)
        .toByteArray();
  }

  /**
   * The acquisition-only live current-attempt observation. Decoded retained requests do not
   * recreate its separate read correlation identity; Account retains those response bytes in the
   * existing original-attempt row.
   */
  public OriginalStartSessionCurrentAttemptEvidence.Result originalCurrentAttempt() {
    if (originalCurrentAttempt == null) {
      throw new IllegalStateException(
          "Retained activation requests do not reconstruct a current-attempt read identity");
    }
    return originalCurrentAttempt;
  }

  /** Exact canonical current-attempt response bytes for the existing separate V160 storage field. */
  public byte[] originalAttemptResponseBytes() {
    if (originalAttemptResponseBytes == null) {
      throw new IllegalStateException(
          "Retained activation requests do not reconstruct a current-attempt read response");
    }
    return originalAttemptResponseBytes.clone();
  }

  public WorldCanonicalInstanceActivation.Request worldActivationRequest() {
    return worldActivationRequest;
  }

  public StartSessionPostAuthorizationExecutionTuple originalTuple() {
    return originalTuple;
  }

  /** Full exact storage bytes retaining the launch and World PREPARING read correlations. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /** Stable retry identity omits owner read correlations while preserving operation selectors. */
  public byte[] operationIdentityBytes() {
    return operationIdentityBytes.clone();
  }

  public String requestDigest() {
    return sha256(operationIdentityBytes);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof AccountStartSessionActivationProtectionRequest that
            && Arrays.equals(canonicalBytes, that.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  private static void requireExactOriginalStartBinding(
      byte[] tupleBytes,
      StartSessionPostAuthorizationExecutionTuple tuple,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Result launch,
      UUID ownerMutationId,
      UUID ownerAttemptId,
      long ownerFence,
      OriginalStartSessionCurrentAttemptEvidence.Result originalCurrentAttempt,
      WorldCanonicalInstanceActivation.Request activationRequest) {
    CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector = launch.request();
    WorldCanonicalInstanceLifecycleEvidence preparing = activationRequest.preparing();
    var worldRequest = preparing.request();
    var action = tuple.preAuthorizationTuple().action();
    var scope = action.scope();
    var descriptor = launch.launchBindingEvidence().descriptor();
    var release = launch.launchBindingEvidence().releaseAttestation();
    if (!StartSessionOperatorAction.ACTION_FAMILY.equals(
            tuple.preAuthorizationTuple().actionFamily())
        || !"game-session-service".equals(tuple.preAuthorizationTuple().targetOwner())
        || !scope.targetNamespace().equals(selector.targetNamespace())
        || !scope.tenantId().equals(selector.canonicalTenantId())
        || !tuple.controlPlaneRequestId().equals(selector.controlPlaneRequestId())
        || tuple.preAuthorizationTuple().action().target().gameTemplateId()
            != descriptor.gameTemplateId()
        || !ownerAttemptId.equals(launch.ownerAttemptId())
        || ownerFence != launch.ownerFence()) {
      throw invalid("Original StartSession tuple differs from the exact launch scope");
    }
    var operation = selector.originalOperation();
    if (operation != null && (!Arrays.equals(tupleBytes, operation.originalPostAuthorizationTuple())
        || !ownerAttemptId.equals(operation.ownerAttemptId()) || ownerFence != operation.ownerFence()
        || !ownerMutationId.equals(operation.ownerMutationId()))) {
      throw invalid("Launch read original operation differs from the complete protection tuple");
    }
    if (originalCurrentAttempt != null
        && (!Arrays.equals(
                tupleBytes,
                originalCurrentAttempt.request().canonicalPostAuthorizationTuple())
            || !ownerAttemptId.equals(
                originalCurrentAttempt.request().expectedOwnerAttemptId())
            || ownerFence != originalCurrentAttempt.request().expectedOwnerFence()
            || !scope.targetNamespace().equals(
                originalCurrentAttempt.request().targetNamespace()))) {
      throw invalid("Current-attempt observation differs from the exact original tuple owner");
    }
    if (launch.currentGameInstanceStatus()
        != CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING) {
      throw invalid("Original activation protection requires the exact Game Session STARTING row");
    }
    if (launch.currentRowVersion() < 0L) {
      throw invalid("Game Session STARTING rowVersion must be nonnegative");
    }
    if (!selector.targetNamespace().equals(worldRequest.targetNamespace())
        || !selector.canonicalTenantId().equals(worldRequest.canonicalTenantId())
        || !selector.worldSlug().equals(worldRequest.worldSlug())
        || !selector.gameInstanceUuid().equals(worldRequest.canonicalGameInstanceId())
        || !selector.controlPlaneRequestId().equals(worldRequest.controlPlaneRequestId())
        || !selector
            .expectedDescriptorRequestDigest()
            .equals(worldRequest.expectedDescriptorRequestDigest())
        || !selector
            .expectedDescriptorResultDigest()
            .equals(worldRequest.expectedDescriptorResultDigest())
        || !selector
            .expectedReleaseAttestationEvidenceDigest()
            .equals(worldRequest.expectedReleaseAttestationDigest())
        || !selector.launchDescriptorId().equals(descriptor.launchDescriptorId())
        || !worldRequest.canonicalVersionId().equals(release.canonicalVersionId())
        || !worldRequest.playableStateNamespaceId().equals(launch.playableStateNamespaceId())
        || !worldRequest.playableStateScope().equals(launch.playableStateScope().name())
        || worldRequest.publicProduction() != launch.publicProduction()
        || !preparing.launchBinding().equals(launch.launchBindingEvidence())
        || !activationRequest.canonicalGameInstanceId().equals(selector.gameInstanceUuid())) {
      throw invalid("Game Session STARTING and World PREPARING evidence do not bind exactly");
    }
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Result decodeLaunchAssociation(
      byte[] exactResponseBytes) {
    byte[] boundedBytes =
        bounded(
            exactResponseBytes,
            MAX_GAME_SESSION_LAUNCH_EVIDENCE_BYTES,
            "Game Session launch read evidence");
    try {
      GetCanonicalGameInstanceLaunchAssociationResponse response =
          GetCanonicalGameInstanceLaunchAssociationResponse.parseFrom(boundedBytes);
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              canonicalUuid(response.getReadRequestId(), "readRequestId"),
              response.getTargetNamespace(),
              canonicalUuid(response.getCanonicalTenantId(), "canonicalTenantId"),
              response.getWorldSlug(),
              canonicalUuid(response.getGameInstanceUuid(), "gameInstanceUuid"),
              response.getControlPlaneRequestId(),
              response.getLaunchDescriptorId(),
              response.getDescriptorRequestDigest(),
              response.getDescriptorResultDigest(),
              response.getReleaseAttestationEvidenceDigest(),
              response.getOriginalPostAuthorizationTuple().isEmpty() && response.getExpectedOwnerMutationId().isEmpty() && response.getExpectedOwnerAttemptId().isEmpty() && response.getExpectedOwnerFence() == 0L
                  ? null : new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(response.getOriginalPostAuthorizationTuple().toByteArray(), canonicalUuid(response.getExpectedOwnerMutationId(), "expectedOwnerMutationId"), canonicalUuid(response.getExpectedOwnerAttemptId(), "expectedOwnerAttemptId"), response.getExpectedOwnerFence()));
      CanonicalGameInstanceLaunchAssociationReadEvidence.Result result =
          CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(request, response);
      if (!Arrays.equals(
          boundedBytes,
          CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(result).toByteArray())) {
        throw invalid("Game Session launch evidence is not the exact canonical response bytes");
      }
      return result;
    } catch (IOException malformed) {
      throw invalid("Game Session launch evidence is malformed");
    }
  }

  private static byte[] launchOperationIdentityBytes(byte[] exactResponseBytes) {
    try {
      return GetCanonicalGameInstanceLaunchAssociationResponse.parseFrom(exactResponseBytes)
          .toBuilder()
          .clearReadRequestId()
          .build()
          .toByteArray();
    } catch (IOException malformed) {
      throw invalid("Game Session launch evidence is malformed");
    }
  }

  private static byte[] encode(
      byte[] tupleBytes,
      byte[] projectionBytes,
      UUID ownerMutationId,
      UUID ownerAttemptId,
      long ownerFence,
      String leaseExpiry,
      UUID participationId,
      long participationFence,
      byte[] launchEvidenceBytes,
      UUID activationRequestId,
      byte[] preparingEvidenceBytes) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put(
        "originalPostAuthorizationTupleBytesBase64",
        Base64.getEncoder().encodeToString(tupleBytes));
    value.put(
        "accountRedemptionProjectionBytesBase64",
        Base64.getEncoder().encodeToString(projectionBytes));
    value.put("gameSessionOwnerMutationId", ownerMutationId.toString());
    value.put("gameSessionOwnerAttemptId", ownerAttemptId.toString());
    value.put("gameSessionOwnerFence", Long.toString(ownerFence));
    value.put("originalLeaseExpiresAt", leaseExpiry);
    value.put("accountWorldParticipationId", participationId.toString());
    value.put("accountWorldParticipationFence", Long.toString(participationFence));
    value.put(
        "gameSessionLaunchReadEvidenceBytesBase64",
        Base64.getEncoder().encodeToString(launchEvidenceBytes));
    value.put("worldActivationRequestId", activationRequestId.toString());
    value.put(
        "preparingEvidenceBytesBase64", Base64.getEncoder().encodeToString(preparingEvidenceBytes));
    return canonicalJson(value, MAX_CANONICAL_BYTES, "Account activation protection request");
  }

  private static byte[] encodeOperationIdentity(
      byte[] tupleBytes,
      byte[] projectionBytes,
      UUID ownerMutationId,
      UUID ownerAttemptId,
      long ownerFence,
      String leaseExpiry,
      UUID participationId,
      long participationFence,
      byte[] launchOperationIdentityBytes,
      UUID activationRequestId,
      byte[] activationOperationIdentityBytes) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", OPERATION_IDENTITY_SCHEMA);
    value.put(
        "originalPostAuthorizationTupleBytesBase64",
        Base64.getEncoder().encodeToString(tupleBytes));
    value.put(
        "accountRedemptionProjectionBytesBase64",
        Base64.getEncoder().encodeToString(projectionBytes));
    value.put("gameSessionOwnerMutationId", ownerMutationId.toString());
    value.put("gameSessionOwnerAttemptId", ownerAttemptId.toString());
    value.put("gameSessionOwnerFence", Long.toString(ownerFence));
    value.put("originalLeaseExpiresAt", leaseExpiry);
    value.put("accountWorldParticipationId", participationId.toString());
    value.put("accountWorldParticipationFence", Long.toString(participationFence));
    value.put(
        "gameSessionLaunchReadOperationIdentityBytesBase64",
        Base64.getEncoder().encodeToString(launchOperationIdentityBytes));
    value.put("worldActivationRequestId", activationRequestId.toString());
    value.put(
        "worldActivationOperationIdentityBytesBase64",
        Base64.getEncoder().encodeToString(activationOperationIdentityBytes));
    return canonicalJson(value, MAX_CANONICAL_BYTES, "Account activation operation identity");
  }

  private static byte[] canonicalJson(Map<String, Object> value, int maximum, String label) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (canonical.length > maximum) throw invalid(label + " exceeds its byte limit");
      return canonical;
    } catch (IOException impossible) {
      throw new IllegalStateException(label + " cannot be encoded", impossible);
    }
  }

  private static byte[] decodeBase64(Object value, String field) {
    String text = string(value, field);
    try {
      byte[] decoded = Base64.getDecoder().decode(text);
      if (!Base64.getEncoder().encodeToString(decoded).equals(text)) {
        throw invalid(field + " must use canonical Base64");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is malformed Base64");
    }
  }

  private static byte[] bounded(byte[] value, int maximum, String name) {
    if (value == null || value.length == 0 || value.length > maximum) {
      throw invalid(name + " is absent or exceeds its byte limit");
    }
    return value.clone();
  }

  private static String canonicalExpiry(Instant value) {
    if (value.getNano() % 1_000 != 0) {
      throw invalid("originalLeaseExpiresAt must use exact microsecond precision");
    }
    String text = MICROS_FORMAT.format(value);
    if (!MICROS_UTC.matcher(text).matches()) {
      throw invalid("originalLeaseExpiresAt must use canonical UTC microsecond text");
    }
    return text;
  }

  private static Instant parseExpiry(String value) {
    if (!MICROS_UTC.matcher(value).matches()) {
      throw invalid("originalLeaseExpiresAt must use canonical UTC microsecond text");
    }
    try {
      Instant parsed = Instant.parse(value);
      if (!MICROS_FORMAT.format(parsed).equals(value)) {
        throw invalid("originalLeaseExpiresAt must use canonical UTC microsecond text");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalArgumentException expected) throw expected;
      throw invalid("originalLeaseExpiresAt is malformed");
    }
  }

  private static long positiveLong(Object value, String field) {
    String text = string(value, field);
    if (!POSITIVE_LONG.matcher(text).matches()) {
      throw invalid(field + " must be a positive canonical signed 64-bit decimal string");
    }
    try {
      long parsed = Long.parseLong(text);
      if (parsed <= 0L || !Long.toString(parsed).equals(text)) {
        throw invalid(field + " must be a positive canonical signed 64-bit decimal string");
      }
      return parsed;
    } catch (NumberFormatException malformed) {
      throw invalid(field + " is outside the supported signed 64-bit range");
    }
  }

  private static UUID canonicalUuid(Object value, String field) {
    String text = string(value, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || NIL_UUID.equals(parsed)) {
        throw invalid(field + " must be a canonical lowercase non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be a canonical lowercase non-nil UUID");
    }
  }

  private static UUID requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (NIL_UUID.equals(value)) throw invalid(field + " must be a non-nil UUID");
    return value;
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0L) throw invalid(field + " must be positive");
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static String string(Object value, String field) {
    if (!(value instanceof String text)) throw invalid(field + " must be a string");
    return text;
  }

  private static void requireExactFields(
      Map<String, Object> value, Set<String> expected, String label) {
    if (value == null || !value.keySet().equals(expected)) {
      throw invalid(label + " has missing or unsupported fields");
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid("Account activation protection request is not valid UTF-8");
    }
  }

  private static void requireCanonicalJson(byte[] originalBytes, String json, String label)
      throws IOException {
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(json);
    if (!MessageDigest.isEqual(canonical, originalBytes)) {
      throw invalid(label + " is not canonical RFC 8785 JSON");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
