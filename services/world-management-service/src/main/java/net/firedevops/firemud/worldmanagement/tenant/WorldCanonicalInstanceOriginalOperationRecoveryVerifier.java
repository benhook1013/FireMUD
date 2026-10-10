package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidenceReadClient;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.OriginalOperationRecoveryVerifier;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.PreparationDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticates exact historical Account and Game Session evidence for read-only World recovery.
 *
 * <p>This unregistered verifier performs only owner reads. It does not refresh original authority,
 * reacquire participation, authorize a new preparation, or infer a World terminal outcome.
 */
public final class WorldCanonicalInstanceOriginalOperationRecoveryVerifier
    implements OriginalOperationRecoveryVerifier {
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private final AccountStartSessionWorldParticipationHistoricalReadClient accountClient;
  private final HistoricalOriginalStartSessionOwnerEvidenceReadClient gameSessionClient;

  public WorldCanonicalInstanceOriginalOperationRecoveryVerifier(
      AccountStartSessionWorldParticipationHistoricalReadClient accountClient,
      HistoricalOriginalStartSessionOwnerEvidenceReadClient gameSessionClient) {
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient");
    this.gameSessionClient = Objects.requireNonNull(gameSessionClient, "gameSessionClient");
  }

  @Override
  public void verifyOriginalOperation(WorldCanonicalInstanceExecutionIdentity identity) {
    Objects.requireNonNull(identity, "original World execution identity is required");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Historical StartSession recovery must authenticate outside an ambient transaction");
    }

    final RetainedPreparation retained;
    try {
      retained = RetainedPreparation.parse(identity.preparationInputJson());
      requireIdentityMatches(identity, retained);
    } catch (PreparationDeniedException denied) {
      throw denied;
    } catch (IllegalArgumentException | ArithmeticException malformed) {
      throw denied("Retained World StartSession identity is malformed", malformed);
    }

    UUID accountReadId =
        freshReadId(
            identity.accountWorldParticipationId(),
            identity.gameSessionOwnerAttemptId(),
            identity.canonicalGameInstanceId());
    AccountStartSessionWorldParticipationHistoricalReadRequest accountRequest =
        new AccountStartSessionWorldParticipationHistoricalReadRequest(
            accountReadId,
            identity.targetNamespace(),
            identity.accountWorldParticipationId(),
            identity.accountWorldParticipationFence());
    AccountStartSessionWorldParticipationHistoricalReadEvidence accountEvidence =
        accountClient.read(accountRequest);
    if (accountEvidence == null) {
      throw denied("Account returned no historical participation evidence");
    }
    requireAccountEvidence(identity, accountRequest, accountEvidence);

    HistoricalOriginalStartSessionOwnerEvidence.Request gameSessionRequest =
        retained.gameSessionRequest(identity);
    HistoricalOriginalStartSessionOwnerEvidence.Result gameSessionEvidence =
        gameSessionClient.read(gameSessionRequest);
    if (gameSessionEvidence == null) {
      throw denied("Game Session returned no historical original-operation evidence");
    }
    requireGameSessionEvidence(identity, retained, gameSessionRequest, gameSessionEvidence);
  }

  private static void requireIdentityMatches(
      WorldCanonicalInstanceExecutionIdentity identity, RetainedPreparation retained) {
    JsonNode savedIdentity = retained.identity();
    if (!identity.canonicalGameInstanceId().equals(uuid(savedIdentity, "canonicalGameInstanceId"))
        || !identity.targetNamespace().equals(text(savedIdentity, "targetNamespace"))
        || !identity.canonicalTenantId().equals(uuid(savedIdentity, "canonicalTenantId"))
        || !identity.controlPlaneRequestId().equals(text(savedIdentity, "controlPlaneRequestId"))) {
      throw denied("Retained World preparation identity differs from its exact execution identity");
    }
    String input = identity.preparationInputJson();
    if (!identity.preparationInputDigest().equals(digest(input.getBytes(StandardCharsets.UTF_8)))) {
      throw denied("World preparation input digest differs from its exact retained bytes");
    }
    retained.requireCrossOwnerReadSelectors();
  }

  private static void requireAccountEvidence(
      WorldCanonicalInstanceExecutionIdentity identity,
      AccountStartSessionWorldParticipationHistoricalReadRequest expectedRequest,
      AccountStartSessionWorldParticipationHistoricalReadEvidence actual) {
    if (!expectedRequest.equals(actual.request())
        || !Arrays.equals(
            identity.originalPostAuthorizationTuple(), actual.originalPostAuthorizationTuple())
        || !identity.gameSessionOwnerAttemptId().equals(actual.gameSessionOwnerAttemptId())
        || identity.gameSessionOwnerFence() != actual.gameSessionOwnerFence()
        || !identity.canonicalGameInstanceId().equals(actual.canonicalGameInstanceId())
        || !identity.preparationInputJson().equals(actual.preparationInputJson())
        || !identity.preparationInputDigest().equals(actual.preparationInputDigest())) {
      throw denied(
          "Account historical participation differs from the complete original World execution tuple");
    }
  }

  private static void requireGameSessionEvidence(
      WorldCanonicalInstanceExecutionIdentity identity,
      RetainedPreparation retained,
      HistoricalOriginalStartSessionOwnerEvidence.Request expectedRequest,
      HistoricalOriginalStartSessionOwnerEvidence.Result actual) {
    if (!expectedRequest.equals(actual.request())
        || !Arrays.equals(
            identity.originalPostAuthorizationTuple(), actual.originalTuple().canonicalBytes())
        || !identity.gameSessionOwnerAttemptId().equals(actual.ownerAttemptId())
        || identity.gameSessionOwnerFence() != actual.ownerFence()) {
      throw denied(
          "Game Session historical evidence differs from the exact original tuple or owner attempt");
    }

    HistoricalOriginalStartSessionOwnerEvidence.LaunchAssociation association =
        actual.launchAssociation();
    JsonNode savedIdentity = retained.identity();
    if (!identity.targetNamespace().equals(association.targetNamespace())
        || !identity.canonicalTenantId().equals(association.canonicalTenantId())
        || !text(savedIdentity, "worldSlug").equals(association.worldSlug())
        || !identity.canonicalGameInstanceId().equals(association.gameInstanceUuid())
        || !identity.controlPlaneRequestId().equals(association.controlPlaneRequestId())
        || !text(retained.gameSessionReadEvidence(), "launchDescriptorId")
            .equals(association.launchDescriptorId())
        || descriptorTemplateId(association) != identity.gameTemplateId()
        || actual.firstSelection().association().templateId() != identity.gameTemplateId()
        || !uuid(savedIdentity, "playableStateNamespaceId")
            .equals(association.playableStateNamespaceId())
        || !RealmEntryPolicy.StateScope.SHARED.equals(association.playableStateScope())
        || !bool(savedIdentity, "publicProduction")
        || !association.publicProduction()) {
      throw denied("Game Session launch association differs from the retained World identity");
    }

    CompleteLaunchBindingEvidence launchBinding = association.launchBindingEvidence();
    AuthoredWorldLaunchDescriptorEvidence descriptor = launchBinding.descriptor();
    var release = launchBinding.releaseAttestation();
    requireFirstSelectionMatches(identity, retained, actual, release);
    if (!retained.sameJsonValue(
            retained.gameSessionReadEvidence().get("descriptorJson"), descriptor)
        || !retained.sameJsonValue(
            retained.gameSessionReadEvidence().get("releaseAttestationJson"), release)) {
      throw denied(
          "Game Session descriptor or release attestation differs from the exact retained preparation input");
    }
    retained.requireLaunchBindingAndSource(descriptor, release);
  }

  private static void requireFirstSelectionMatches(
      WorldCanonicalInstanceExecutionIdentity identity,
      RetainedPreparation retained,
      HistoricalOriginalStartSessionOwnerEvidence.Result actual,
      net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence release) {
    var firstSelection = actual.firstSelection().association();
    if (!identity.targetNamespace().equals(firstSelection.targetNamespace())
        || !identity.canonicalTenantId().equals(firstSelection.canonicalTenantId())
        || identity.gameTemplateId() != firstSelection.templateId()
        || !text(retained.identity(), "worldSlug").equals(firstSelection.worldSlug())
        || !uuid(retained.launchBinding, "canonicalVersionId")
            .equals(firstSelection.canonicalVersionId())
        || !release.commitId().equals(firstSelection.selectedCommitId().toString())
        || !release.publishWorkflowId().equals(firstSelection.publishWorkflowId())
        || !uuid(retained.sourceIntake, "sourceOperationId")
            .equals(firstSelection.sourceOperationId())
        || !text(retained.sourceIntake, "sourceEvidenceDigest")
            .equals(firstSelection.sourceEvidenceDigest())) {
      throw denied(
          "Game Session first-selection pin differs from the exact retained World release and source");
    }
  }

  private static long descriptorTemplateId(
      HistoricalOriginalStartSessionOwnerEvidence.LaunchAssociation association) {
    return association.launchBindingEvidence().descriptor().gameTemplateId();
  }

  private static UUID freshReadId(UUID... excluded) {
    while (true) {
      UUID candidate = UUID.randomUUID();
      if (NIL_UUID.equals(candidate)) continue;
      boolean matches = false;
      for (UUID value : excluded) matches |= candidate.equals(value);
      if (!matches) return candidate;
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw denied("Historical World preparation field is missing or malformed: " + field);
    }
    return value.asText();
  }

  private static UUID uuid(JsonNode object, String field) {
    String value = text(object, field);
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("UUID is not canonical and non-nil");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw denied("Historical World preparation UUID is malformed: " + field, malformed);
    }
  }

  private static boolean bool(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isBoolean()) {
      throw denied("Historical World preparation field is missing or malformed: " + field);
    }
    return value.booleanValue();
  }

  private static long number(JsonNode object, String field, boolean positive) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw denied("Historical World preparation field is missing or malformed: " + field);
    }
    long parsed = value.longValue();
    if (positive ? parsed <= 0L : parsed < 0L) {
      throw denied("Historical World preparation number is outside its allowed range: " + field);
    }
    return parsed;
  }

  private static void fields(JsonNode object, String label, String... names) {
    if (object == null || !object.isObject() || object.size() != names.length) {
      throw denied("Historical World preparation object is incomplete or open: " + label);
    }
    for (String name : names) {
      if (!object.has(name)) {
        throw denied("Historical World preparation object is missing " + label + "." + name);
      }
    }
  }

  private static void digestText(JsonNode object, String field) {
    if (!SHA256.matcher(text(object, field)).matches()) {
      throw denied("Historical World preparation digest is malformed: " + field);
    }
  }

  private static void rawDigestText(JsonNode object, String field) {
    if (!text(object, field).matches("[0-9a-f]{64}")) {
      throw denied("Historical World preparation digest is malformed: " + field);
    }
  }

  private static PreparationDeniedException denied(String message) {
    return new PreparationDeniedException(message);
  }

  private static PreparationDeniedException denied(String message, Throwable cause) {
    PreparationDeniedException denied = new PreparationDeniedException(message);
    denied.initCause(cause);
    return denied;
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record RetainedPreparation(
      int schemaVersion,
      JsonNode identity,
      JsonNode gameSessionReadRequest,
      JsonNode gameSessionReadEvidence,
      JsonNode launchBinding,
      JsonNode versionIdentity,
      JsonNode sourceIntake,
      JsonNode topology,
      String worldStartLocationEvidenceBase64) {
    static RetainedPreparation parse(String preparationInputJson) {
      if (preparationInputJson == null
          || preparationInputJson.isBlank()
          || preparationInputJson.getBytes(StandardCharsets.UTF_8).length
              > net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal
                  .MAX_CANONICAL_BYTES) {
        throw denied("Exact retained World preparation input is missing or oversized");
      }
      final JsonNode root;
      try {
        root = JSON.readTree(preparationInputJson);
      } catch (JacksonException malformed) {
        throw denied("Exact retained World preparation input is not strict JSON", malformed);
      }
      if (root == null || !root.isObject()) {
        throw denied("Exact retained World preparation input must be an object");
      }
      int schemaVersion = Math.toIntExact(number(root, "schemaVersion", true));
      if (schemaVersion != 1 && schemaVersion != 2) {
        throw denied("Unsupported retained World preparation input schema");
      }
      if (schemaVersion == 1) {
        fields(
            root,
            "root",
            "schemaVersion",
            "identity",
            "gameSessionReadRequest",
            "gameSessionReadEvidence",
            "launchBinding",
            "versionIdentity",
            "sourceIntake",
            "topology");
      } else {
        fields(
            root,
            "root",
            "schemaVersion",
            "identity",
            "gameSessionReadRequest",
            "gameSessionReadEvidence",
            "launchBinding",
            "versionIdentity",
            "sourceIntake",
            "topology",
            "worldStartLocationEvidenceBase64");
      }
      String selector = null;
      if (schemaVersion == 2) {
        if (root.size() != 9 || !root.has("worldStartLocationEvidenceBase64")) {
          throw denied("Schema-2 World preparation input requires its start-location evidence");
        }
        selector = text(root, "worldStartLocationEvidenceBase64");
        try {
          byte[] decoded = Base64.getDecoder().decode(selector);
          if (decoded.length == 0
              || !Base64.getEncoder().encodeToString(decoded).equals(selector)) {
            throw new IllegalArgumentException("selector base64 is not canonical");
          }
        } catch (IllegalArgumentException malformed) {
          throw denied("Retained World start-location evidence is malformed", malformed);
        }
      } else if (root.size() != 8) {
        throw denied("Schema-1 World preparation input has an unsupported root shape");
      }

      JsonNode identity = root.get("identity");
      fields(
          identity,
          "identity",
          "canonicalGameInstanceId",
          "targetNamespace",
          "canonicalTenantId",
          "worldSlug",
          "playableStateNamespaceId",
          "playableStateScope",
          "publicProduction",
          "controlPlaneRequestId");
      uuid(identity, "canonicalGameInstanceId");
      uuid(identity, "canonicalTenantId");
      uuid(identity, "playableStateNamespaceId");
      text(identity, "targetNamespace");
      text(identity, "worldSlug");
      text(identity, "playableStateScope");
      text(identity, "controlPlaneRequestId");
      bool(identity, "publicProduction");

      JsonNode request = root.get("gameSessionReadRequest");
      fields(
          request,
          "gameSessionReadRequest",
          "targetNamespace",
          "canonicalTenantId",
          "worldSlug",
          "canonicalGameInstanceId",
          "controlPlaneRequestId",
          "launchDescriptorId",
          "expectedDescriptorRequestDigest",
          "expectedDescriptorResultDigest",
          "expectedReleaseAttestationEvidenceDigest");
      JsonNode evidence = root.get("gameSessionReadEvidence");
      fields(
          evidence,
          "gameSessionReadEvidence",
          "targetNamespace",
          "canonicalTenantId",
          "worldSlug",
          "canonicalGameInstanceId",
          "controlPlaneRequestId",
          "launchDescriptorId",
          "descriptorRequestDigest",
          "descriptorResultDigest",
          "releaseAttestationEvidenceDigest",
          "playableStateNamespaceId",
          "playableStateScope",
          "publicProduction",
          "descriptorJson",
          "releaseAttestationJson");
      for (String field : new String[] {"canonicalTenantId", "canonicalGameInstanceId"}) {
        uuid(request, field);
        uuid(evidence, field);
      }
      uuid(evidence, "playableStateNamespaceId");
      for (String field :
          new String[] {
            "targetNamespace",
            "worldSlug",
            "controlPlaneRequestId",
            "launchDescriptorId",
            "expectedDescriptorRequestDigest",
            "expectedDescriptorResultDigest",
            "expectedReleaseAttestationEvidenceDigest"
          }) {
        text(request, field);
      }
      for (String field :
          new String[] {
            "targetNamespace",
            "worldSlug",
            "controlPlaneRequestId",
            "launchDescriptorId",
            "playableStateScope"
          }) {
        text(evidence, field);
      }
      for (String field :
          new String[] {
            "expectedDescriptorRequestDigest",
            "expectedDescriptorResultDigest",
            "expectedReleaseAttestationEvidenceDigest"
          }) {
        digestText(request, field);
      }
      for (String field :
          new String[] {
            "descriptorRequestDigest", "descriptorResultDigest", "releaseAttestationEvidenceDigest"
          }) {
        digestText(evidence, field);
      }
      bool(evidence, "publicProduction");
      parseEvidenceJson(text(evidence, "descriptorJson"), "descriptorJson");
      parseEvidenceJson(text(evidence, "releaseAttestationJson"), "releaseAttestationJson");

      JsonNode launch = root.get("launchBinding");
      fields(
          launch,
          "launchBinding",
          "schemaVersion",
          "operationId",
          "targetNamespace",
          "canonicalTenantId",
          "worldSlug",
          "controlPlaneRequestId",
          "descriptorRequestDigest",
          "descriptorResultDigest",
          "releaseAttestationDigest",
          "canonicalVersionId",
          "localTenantKey",
          "intakeOperationId",
          "intakeRequestId",
          "sourceOperationId",
          "sourceEvidenceDigest",
          "intakeRequestDigest",
          "intakeReceiptDigest");
      number(launch, "schemaVersion", true);
      for (String field :
          new String[] {
            "operationId",
            "canonicalTenantId",
            "canonicalVersionId",
            "intakeOperationId",
            "intakeRequestId",
            "sourceOperationId"
          }) {
        uuid(launch, field);
      }
      for (String field : new String[] {"targetNamespace", "worldSlug", "controlPlaneRequestId"}) {
        text(launch, field);
      }
      number(launch, "localTenantKey", true);
      for (String field :
          new String[] {
            "descriptorRequestDigest", "descriptorResultDigest", "releaseAttestationDigest",
            "sourceEvidenceDigest", "intakeRequestDigest", "intakeReceiptDigest"
          }) {
        digestText(launch, field);
      }

      JsonNode version = root.get("versionIdentity");
      fields(
          version,
          "versionIdentity",
          "schemaVersion",
          "operationId",
          "canonicalVersionId",
          "localVersionKey",
          "gameDesignVersionId",
          "versionState",
          "versionStateEpoch",
          "evidenceDigest");
      number(version, "schemaVersion", true);
      uuid(version, "operationId");
      uuid(version, "canonicalVersionId");
      number(version, "localVersionKey", true);
      number(version, "gameDesignVersionId", true);
      number(version, "versionStateEpoch", true);
      if (!Set.of(
              "VERSION_LIFECYCLE_STATE_DRAFT",
              "VERSION_LIFECYCLE_STATE_PUBLISHED",
              "VERSION_LIFECYCLE_STATE_ACTIVE")
          .contains(text(version, "versionState"))) {
        throw denied("Retained World version state is not supported");
      }
      digestText(version, "evidenceDigest");

      JsonNode source = root.get("sourceIntake");
      fields(
          source,
          "sourceIntake",
          "schemaVersion",
          "targetNamespace",
          "intakeRequestId",
          "operationId",
          "canonicalTenantId",
          "worldSlug",
          "sourceOperationId",
          "sourceEvidenceDigest",
          "requestDigest",
          "receiptDigest",
          "localTenantKey",
          "sourceEvidence");
      number(source, "schemaVersion", true);
      for (String field :
          new String[] {
            "intakeRequestId", "operationId", "canonicalTenantId", "sourceOperationId"
          }) {
        uuid(source, field);
      }
      text(source, "targetNamespace");
      text(source, "worldSlug");
      number(source, "localTenantKey", true);
      for (String field : new String[] {"sourceEvidenceDigest", "requestDigest", "receiptDigest"}) {
        digestText(source, field);
      }
      JsonNode sourceEvidence = source.get("sourceEvidence");
      fields(
          sourceEvidence,
          "sourceIntake.sourceEvidence",
          "schemaVersion",
          "registrationRequestId",
          "sourceOperationId",
          "requestDigest",
          "canonicalTenantId",
          "tenantSlug",
          "worldSlug",
          "worldDisplayName",
          "sourceGameRowId",
          "sourceGameTenantKey",
          "provenanceKind",
          "evidenceDigest");
      number(sourceEvidence, "schemaVersion", true);
      for (String field :
          new String[] {"registrationRequestId", "sourceOperationId", "canonicalTenantId"}) {
        uuid(sourceEvidence, field);
      }
      for (String field :
          new String[] {
            "requestDigest",
            "tenantSlug",
            "worldSlug",
            "worldDisplayName",
            "sourceGameTenantKey",
            "provenanceKind",
            "evidenceDigest"
          }) {
        text(sourceEvidence, field);
      }
      number(sourceEvidence, "sourceGameRowId", true);

      JsonNode topology = root.get("topology");
      fields(
          topology,
          "topology",
          "captureId",
          "targetNamespace",
          "canonicalTenantId",
          "canonicalVersionId",
          "versionIdentityOperationId",
          "requestId",
          "commitId",
          "freezeRequestId",
          "publicationFence",
          "publicationRequestDigest",
          "appliedCommitId",
          "planDigest",
          "regionCount",
          "zoneCount",
          "roomCount",
          "exitCount",
          "generationRuleCount",
          "spawnBindingCount");
      for (String field :
          new String[] {
            "captureId",
            "canonicalTenantId",
            "canonicalVersionId",
            "versionIdentityOperationId",
            "requestId",
            "commitId"
          }) {
        uuid(topology, field);
      }
      for (String field : new String[] {"targetNamespace", "freezeRequestId", "appliedCommitId"}) {
        text(topology, field);
      }
      uuid(topology, "publicationFence");
      rawDigestText(topology, "publicationRequestDigest");
      digestText(topology, "planDigest");
      number(topology, "regionCount", true);
      for (String field :
          new String[] {
            "zoneCount", "roomCount", "exitCount", "generationRuleCount", "spawnBindingCount"
          }) {
        number(topology, field, false);
      }
      if (number(topology, "generationRuleCount", false) != 0L
          || number(topology, "spawnBindingCount", false) != 0L) {
        throw denied("Retained World preparation input contains unsupported generation intent");
      }

      return new RetainedPreparation(
          schemaVersion, identity, request, evidence, launch, version, source, topology, selector);
    }

    HistoricalOriginalStartSessionOwnerEvidence.Request gameSessionRequest(
        WorldCanonicalInstanceExecutionIdentity identity) {
      String namespace = text(gameSessionReadEvidence, "targetNamespace");
      UUID tenant = uuid(gameSessionReadEvidence, "canonicalTenantId");
      UUID gameInstance = uuid(gameSessionReadEvidence, "canonicalGameInstanceId");
      UUID readId =
          freshReadId(
              identity.accountWorldParticipationId(),
              identity.gameSessionOwnerAttemptId(),
              gameInstance);
      var selector =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
              readId,
              namespace,
              tenant,
              text(gameSessionReadEvidence, "worldSlug"),
              gameInstance,
              text(gameSessionReadEvidence, "controlPlaneRequestId"),
              text(gameSessionReadEvidence, "launchDescriptorId"),
              text(gameSessionReadEvidence, "descriptorRequestDigest"),
              text(gameSessionReadEvidence, "descriptorResultDigest"),
              text(gameSessionReadEvidence, "releaseAttestationEvidenceDigest"));
      return new HistoricalOriginalStartSessionOwnerEvidence.Request(
          selector, identity.gameSessionOwnerAttemptId(), identity.gameSessionOwnerFence());
    }

    void requireCrossOwnerReadSelectors() {
      for (String field :
          new String[] {
            "targetNamespace", "worldSlug", "controlPlaneRequestId", "launchDescriptorId"
          }) {
        if (!text(gameSessionReadRequest, field).equals(text(gameSessionReadEvidence, field))) {
          throw denied("Retained Game Session request and evidence differ at " + field);
        }
      }
      if (!uuid(gameSessionReadRequest, "canonicalTenantId")
              .equals(uuid(gameSessionReadEvidence, "canonicalTenantId"))
          || !uuid(gameSessionReadRequest, "canonicalGameInstanceId")
              .equals(uuid(gameSessionReadEvidence, "canonicalGameInstanceId"))
          || !text(gameSessionReadRequest, "expectedDescriptorRequestDigest")
              .equals(text(gameSessionReadEvidence, "descriptorRequestDigest"))
          || !text(gameSessionReadRequest, "expectedDescriptorResultDigest")
              .equals(text(gameSessionReadEvidence, "descriptorResultDigest"))
          || !text(gameSessionReadRequest, "expectedReleaseAttestationEvidenceDigest")
              .equals(text(gameSessionReadEvidence, "releaseAttestationEvidenceDigest"))) {
        throw denied("Retained Game Session selector differs from its exact response projection");
      }
      for (String field :
          new String[] {
            "targetNamespace",
            "canonicalTenantId",
            "worldSlug",
            "canonicalGameInstanceId",
            "controlPlaneRequestId"
          }) {
        String identityValue =
            field.startsWith("canonical")
                ? uuid(this.identity, field).toString()
                : text(this.identity, field);
        String requestValue =
            field.startsWith("canonical")
                ? uuid(gameSessionReadRequest, field).toString()
                : text(gameSessionReadRequest, field);
        if (!identityValue.equals(requestValue)) {
          throw denied("Retained World identity differs from Game Session selector at " + field);
        }
      }
      if (!uuid(this.identity, "playableStateNamespaceId")
              .equals(uuid(gameSessionReadEvidence, "playableStateNamespaceId"))
          || !text(this.identity, "playableStateScope")
              .equals(text(gameSessionReadEvidence, "playableStateScope"))
          || bool(this.identity, "publicProduction")
              != bool(gameSessionReadEvidence, "publicProduction")) {
        throw denied("Retained World state namespace differs from Game Session evidence");
      }

      if (number(launchBinding, "schemaVersion", true) != 1L
          || number(versionIdentity, "schemaVersion", true) != 1L
          || number(sourceIntake, "schemaVersion", true) != 1L) {
        throw denied("Unsupported World preparation subdocument schema");
      }
      JsonNode sourceEvidence = requiredSourceEvidence();
      AuthoredWorldSourceEvidence source =
          new AuthoredWorldSourceEvidence(
              Math.toIntExact(number(sourceEvidence, "schemaVersion", true)),
              text(sourceIntake, "targetNamespace"),
              uuid(sourceEvidence, "registrationRequestId"),
              uuid(sourceEvidence, "sourceOperationId"),
              text(sourceEvidence, "requestDigest"),
              uuid(sourceEvidence, "canonicalTenantId"),
              text(sourceEvidence, "tenantSlug"),
              text(sourceEvidence, "worldSlug"),
              text(sourceEvidence, "worldDisplayName"),
              number(sourceEvidence, "sourceGameRowId", true),
              text(sourceEvidence, "sourceGameTenantKey"),
              text(sourceEvidence, "provenanceKind"),
              text(sourceEvidence, "evidenceDigest"));
      WorldAuthoredSourceIntakeReceipt receipt =
          new WorldAuthoredSourceIntakeReceipt(
              Math.toIntExact(number(sourceIntake, "schemaVersion", true)),
              text(sourceIntake, "targetNamespace"),
              uuid(sourceIntake, "intakeRequestId"),
              uuid(sourceIntake, "operationId"),
              uuid(sourceIntake, "canonicalTenantId"),
              text(sourceIntake, "worldSlug"),
              uuid(sourceIntake, "sourceOperationId"),
              text(sourceIntake, "sourceEvidenceDigest"),
              text(sourceIntake, "requestDigest"),
              text(sourceIntake, "receiptDigest"),
              number(sourceIntake, "localTenantKey", true),
              source);

      UUID versionId = uuid(versionIdentity, "canonicalVersionId");
      if (!versionId.equals(uuid(launchBinding, "canonicalVersionId"))
          || !versionId.equals(uuid(topology, "canonicalVersionId"))
          || !uuid(versionIdentity, "operationId")
              .equals(uuid(topology, "versionIdentityOperationId"))) {
        throw denied("Retained World version identity differs from its frozen topology");
      }
      if (!uuid(versionIdentity, "operationId").equals(uuid(topology, "versionIdentityOperationId"))
          || !text(this.identity, "targetNamespace").equals(text(topology, "targetNamespace"))
          || !uuid(this.identity, "canonicalTenantId").equals(uuid(topology, "canonicalTenantId"))
          || !text(identity, "canonicalGameInstanceId")
              .equals(text(gameSessionReadRequest, "canonicalGameInstanceId"))) {
        throw denied("Retained World topology identity is inconsistent");
      }
      if (!uuid(sourceIntake, "canonicalTenantId").equals(uuid(this.identity, "canonicalTenantId"))
          || !text(sourceIntake, "targetNamespace").equals(text(this.identity, "targetNamespace"))
          || !text(sourceIntake, "worldSlug").equals(text(identity, "worldSlug"))) {
        throw denied("Retained authored-source intake differs from the World target");
      }
      if (!uuid(launchBinding, "intakeOperationId").equals(receipt.operationId())
          || !uuid(launchBinding, "intakeRequestId").equals(receipt.intakeRequestId())
          || !uuid(launchBinding, "sourceOperationId").equals(receipt.sourceOperationId())
          || !text(launchBinding, "sourceEvidenceDigest").equals(receipt.sourceEvidenceDigest())
          || !text(launchBinding, "intakeRequestDigest").equals(receipt.requestDigest())
          || !text(launchBinding, "intakeReceiptDigest").equals(receipt.receiptDigest())
          || number(launchBinding, "localTenantKey", true) != receipt.localTenantKey()) {
        throw denied("Retained launch binding differs from its exact World source-intake receipt");
      }
      if (!uuid(sourceEvidence, "sourceOperationId").equals(receipt.sourceOperationId())
          || !text(sourceEvidence, "evidenceDigest").equals(receipt.sourceEvidenceDigest())
          || !uuid(sourceEvidence, "canonicalTenantId").equals(receipt.canonicalTenantId())
          || !text(sourceEvidence, "worldSlug").equals(receipt.worldSlug())) {
        throw denied("Retained authored source evidence differs from its intake receipt");
      }
    }

    void requireLaunchBindingAndSource(
        AuthoredWorldLaunchDescriptorEvidence descriptor,
        net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence release) {
      try {
        WorldAuthoredSourceIntakeReceipt receipt = sourceReceipt();
        CompleteLaunchBindingEvidence evidence =
            new CompleteLaunchBindingEvidence(descriptor, release);
        WorldCompleteLaunchBindingReceipt binding =
            new WorldCompleteLaunchBindingReceipt(
                Math.toIntExact(number(launchBinding, "schemaVersion", true)),
                uuid(launchBinding, "operationId"),
                text(launchBinding, "targetNamespace"),
                uuid(launchBinding, "canonicalTenantId"),
                text(launchBinding, "worldSlug"),
                text(launchBinding, "controlPlaneRequestId"),
                receipt,
                evidence);
        if (!descriptor.requestDigest().equals(text(launchBinding, "descriptorRequestDigest"))
            || !descriptor.resultDigest().equals(text(launchBinding, "descriptorResultDigest"))
            || !release.evidenceDigest().equals(text(launchBinding, "releaseAttestationDigest"))
            || !release.canonicalVersionId().equals(uuid(launchBinding, "canonicalVersionId"))
            || !release.canonicalVersionId().equals(uuid(versionIdentity, "canonicalVersionId"))
            || !release.canonicalVersionId().equals(uuid(topology, "canonicalVersionId"))
            || !release.commitId().equals(text(topology, "commitId"))
            || !release.commitId().equals(text(topology, "appliedCommitId"))
            || !binding.descriptor().equals(descriptor)
            || !identityValuesMatch(descriptor, release)) {
          throw denied("Retained complete launch binding differs from historical owner evidence");
        }
        var worldParticipants =
            release.participantDigests().stream()
                .filter(participant -> "WORLD_MANAGEMENT".equals(participant.participantKey()))
                .toList();
        if (worldParticipants.size() != 1
            || !worldParticipants
                .getFirst()
                .scopeValue()
                .equals(Long.toString(number(versionIdentity, "gameDesignVersionId", true)))) {
          throw denied("Historical release does not bind the retained World Version scope");
        }
        var worldSelector = release.worldStartLocationEvidence();
        if (schemaVersion == 2) {
          if (worldSelector == null
              || !Base64.getEncoder()
                  .encodeToString(worldSelector.canonicalBytes())
                  .equals(worldStartLocationEvidenceBase64)) {
            throw denied("Historical release selector differs from the retained World input");
          }
        } else if (worldSelector != null) {
          throw denied("Schema-1 retained World input cannot match a selected release selector");
        }
      } catch (PreparationDeniedException denied) {
        throw denied;
      } catch (IllegalArgumentException malformed) {
        throw denied(
            "Retained World launch, source, or version identity is inconsistent", malformed);
      }
    }

    private boolean identityValuesMatch(
        AuthoredWorldLaunchDescriptorEvidence descriptor,
        net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence release) {
      return text(launchBinding, "targetNamespace").equals(descriptor.targetNamespace())
          && uuid(launchBinding, "canonicalTenantId").equals(descriptor.canonicalTenantId())
          && text(launchBinding, "worldSlug").equals(descriptor.worldSlug())
          && text(launchBinding, "controlPlaneRequestId").equals(descriptor.controlPlaneRequestId())
          && descriptor
              .authoredWorldSourceOperationId()
              .equals(uuid(sourceIntake, "sourceOperationId"))
          && descriptor
              .authoredWorldSourceEvidenceDigest()
              .equals(text(sourceIntake, "sourceEvidenceDigest"))
          && release
              .authoredWorldSourceOperationId()
              .equals(uuid(sourceIntake, "sourceOperationId"))
          && release
              .authoredWorldSourceEvidenceDigest()
              .equals(text(sourceIntake, "sourceEvidenceDigest"))
          && descriptor.canonicalTenantId().equals(uuid(identity, "canonicalTenantId"))
          && descriptor.targetNamespace().equals(text(identity, "targetNamespace"))
          && descriptor.worldSlug().equals(text(identity, "worldSlug"));
    }

    private WorldAuthoredSourceIntakeReceipt sourceReceipt() {
      JsonNode sourceEvidence = requiredSourceEvidence();
      AuthoredWorldSourceEvidence source =
          new AuthoredWorldSourceEvidence(
              Math.toIntExact(number(sourceEvidence, "schemaVersion", true)),
              text(sourceIntake, "targetNamespace"),
              uuid(sourceEvidence, "registrationRequestId"),
              uuid(sourceEvidence, "sourceOperationId"),
              text(sourceEvidence, "requestDigest"),
              uuid(sourceEvidence, "canonicalTenantId"),
              text(sourceEvidence, "tenantSlug"),
              text(sourceEvidence, "worldSlug"),
              text(sourceEvidence, "worldDisplayName"),
              number(sourceEvidence, "sourceGameRowId", true),
              text(sourceEvidence, "sourceGameTenantKey"),
              text(sourceEvidence, "provenanceKind"),
              text(sourceEvidence, "evidenceDigest"));
      return new WorldAuthoredSourceIntakeReceipt(
          Math.toIntExact(number(sourceIntake, "schemaVersion", true)),
          text(sourceIntake, "targetNamespace"),
          uuid(sourceIntake, "intakeRequestId"),
          uuid(sourceIntake, "operationId"),
          uuid(sourceIntake, "canonicalTenantId"),
          text(sourceIntake, "worldSlug"),
          uuid(sourceIntake, "sourceOperationId"),
          text(sourceIntake, "sourceEvidenceDigest"),
          text(sourceIntake, "requestDigest"),
          text(sourceIntake, "receiptDigest"),
          number(sourceIntake, "localTenantKey", true),
          source);
    }

    private JsonNode requiredSourceEvidence() {
      if (sourceIntake == null || !sourceIntake.isObject()) {
        throw denied("Retained authored-source intake is missing");
      }
      JsonNode sourceEvidence = sourceIntake.get("sourceEvidence");
      if (sourceEvidence == null || !sourceEvidence.isObject()) {
        throw denied("Retained authored source evidence is missing");
      }
      return sourceEvidence;
    }

    boolean sameJsonValue(JsonNode expected, Object actual) {
      if (expected == null || !expected.isTextual()) {
        throw denied("Retained World evidence JSON must be stored as an exact JSON string");
      }
      try {
        JsonNode retained = parseEvidenceJson(expected.textValue(), "retained evidence");
        JsonNode observed = JSON.readTree(JSON.writeValueAsString(actual));
        return retained.equals(observed);
      } catch (JacksonException malformed) {
        throw denied(
            "Historical Game Session evidence could not be encoded for exact comparison",
            malformed);
      }
    }

    private static JsonNode parseEvidenceJson(String value, String label) {
      try {
        JsonNode node = JSON.readTree(value);
        if (node == null || !node.isObject()) {
          throw denied("Retained World " + label + " must be a JSON object");
        }
        return node;
      } catch (JacksonException malformed) {
        throw denied("Retained World " + label + " is not strict JSON", malformed);
      }
    }
  }
}
