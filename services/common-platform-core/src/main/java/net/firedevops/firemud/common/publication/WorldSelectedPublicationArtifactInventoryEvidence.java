package net.firedevops.firemud.common.publication;

import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Request;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Typed World public inventory bound to one exact retained selected-publication freeze.
 *
 * <p>This value alone does not authenticate its producer; the authenticated mTLS caller and
 * verified World server identity path supply that boundary.
 */
public final class WorldSelectedPublicationArtifactInventoryEvidence {
  public static final String SCHEMA = "world-selected-publication-artifact-inventory/v1";
  public static final String SOURCE_MODEL = "WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1";
  public static final int SCHEMA_VERSION = 1;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final List<String> FAMILY_ORDER =
      List.of(
          "REGION", "ZONE", "ROOM", "ROOM_EXIT", "GENERATION_RULE", "WORLD_ENTITY_SPAWN_BINDING");
  private static final List<String> REGION_FIELDS =
      List.of(
          "id",
          "shardId",
          "name",
          "weather",
          "generationSeed",
          "generatorType",
          "generatorParams",
          "spacingMultiplier");
  private static final List<String> ZONE_FIELDS = List.of("id", "regionId", "name");
  private static final List<String> ROOM_FIELDS =
      List.of(
          "id",
          "zoneId",
          "name",
          "description",
          "nameLocalizedVariantsJson",
          "descriptionLocalizedVariantsJson");
  private static final List<String> ROOM_EXIT_FIELDS =
      List.of("id", "fromRoomId", "toRoomId", "direction", "cost");
  private static final List<String> GENERATION_RULE_FIELDS =
      List.of("id", "name", "scopeType", "scopeId", "value");
  private static final List<String> SPAWN_BINDING_FIELDS =
      List.of(
          "id",
          "roomId",
          "entityTemplateType",
          "entityReference.kind",
          "entityReference.tenantId",
          "entityReference.versionId",
          "entityReference.templateId",
          "spawnCount",
          "respawnDelaySeconds");
  private static final List<ArtifactDecision> ARTIFACT_DECISIONS =
      List.of(
          new ArtifactDecision(
              "NAVMESH", "NOT_REQUIRED", "ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"),
          new ArtifactDecision(
              "PATH_GRAPH",
              "NOT_REQUIRED",
              "AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH"));
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private final WorldSelectedDraftPublicationFreezeEvidence freezeEvidence;
  private final PublicEvidence publicEvidence;
  private final byte[] canonicalBytes;
  private final String digest;

  private WorldSelectedPublicationArtifactInventoryEvidence(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence, PublicEvidence publicEvidence) {
    this.freezeEvidence = Objects.requireNonNull(freezeEvidence, "freezeEvidence");
    this.publicEvidence = Objects.requireNonNull(publicEvidence, "publicEvidence");
    requireFreezeBinding(freezeEvidence, publicEvidence);
    this.canonicalBytes = publicEvidence.canonicalBytes();
    this.digest = sha256(canonicalBytes);
  }

  /** Creates evidence from World’s typed public projection and validates every public binding. */
  public static WorldSelectedPublicationArtifactInventoryEvidence fromPublicEvidence(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence, PublicEvidence publicEvidence) {
    return new WorldSelectedPublicationArtifactInventoryEvidence(freezeEvidence, publicEvidence);
  }

  /**
   * Strictly parses canonical public evidence, validates its digest, then binds it to the freeze.
   */
  public static WorldSelectedPublicationArtifactInventoryEvidence fromCanonicalBytes(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      byte[] canonicalBytes,
      String expectedDigest) {
    Objects.requireNonNull(canonicalBytes, "canonicalBytes");
    requireDigest(expectedDigest, "public inventory digest");
    if (!sha256(canonicalBytes).equals(expectedDigest)) {
      throw new IllegalArgumentException("World public inventory digest does not match its bytes");
    }
    final PublicEvidence evidence;
    try {
      evidence = JSON.readValue(canonicalBytes, PublicEvidence.class);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "World public inventory is not a closed typed value", invalid);
    }
    if (evidence == null || !Arrays.equals(canonicalBytes, evidence.canonicalBytes())) {
      throw new IllegalArgumentException("World public inventory is not canonical JSON");
    }
    return new WorldSelectedPublicationArtifactInventoryEvidence(freezeEvidence, evidence);
  }

  public WorldSelectedDraftPublicationFreezeEvidence freezeEvidence() {
    return freezeEvidence;
  }

  public PublicEvidence publicEvidence() {
    return publicEvidence;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  private static void requireFreezeBinding(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence, PublicEvidence evidence) {
    Request request = freezeEvidence.request();
    Acknowledgement acknowledgement = freezeEvidence.acknowledgement();
    AccountPublicationAuthorizationBinding account = request.accountBinding();
    var selection = account.input().selection();
    var intent = selection.intent();
    var selectedCommit = selection.selectedCommit();
    var owner = evidence.ownerScope();
    var application = evidence.selectedApplication();
    var accountOrder = evidence.accountOrder();
    var freeze = evidence.freeze();
    var checkpoint = evidence.checkpoint();
    var sourceModel = evidence.sourceModel();
    String expectedWorkflow =
        PublicationDigestRequestBinding.full(
                request.canonicalTenantId().toString(),
                Long.toString(selection.target().gameDesignVersionRowId()),
                request.publicationRequestId())
            .derivedWorkflowIdentity();

    if (!owner.targetNamespace().equals(request.targetNamespace())
        || !owner.canonicalTenantId().equals(request.canonicalTenantId())
        || !owner.canonicalVersionId().equals(request.canonicalVersionId())
        || !owner.intakeRequestId().equals(acknowledgement.intakeRequestId())
        || !application.applicationRequestId().equals(selectedCommit.requestId())
        || !application.appliedCommitId().equals(selectedCommit.commitId().toString())
        || !application.bindingDigest().equals(selectedCommit.digest())
        || !accountOrder.operationId().equals(account.operationId())
        || !accountOrder.fenceId().equals(account.fenceId())
        || !accountOrder.actorAccountId().equals(account.input().actorAccountId())
        || !accountOrder.publicationRequestId().equals(intent.publishRequestId())
        || !accountOrder.selectionDigest().equals(selection.digest())
        || !accountOrder.selectedCommitId().equals(selectedCommit.commitId().toString())
        || !accountOrder.bindingDigest().equals(sha256(account.canonicalBytes()))
        || !freeze.publicationFence().equals(acknowledgement.publicationFence())
        || !freeze.publicationRequestId().equals(request.publicationRequestId())
        || !freeze.requestDigest().equals(request.requestDigest())
        || !freeze.versionStateEpoch().equals(Long.toString(acknowledgement.versionStateEpoch()))
        || !freeze.publishWorkflowId().equals(expectedWorkflow)
        || !checkpoint.appliedCommitId().equals(acknowledgement.appliedCommitId())
        || !checkpoint.contentDigest().equals(acknowledgement.contentDigest())
        || checkpoint.digestSchemaVersion() != acknowledgement.digestSchemaVersion()
        || !appliedEpochsMatch(selectedCommit, sourceModel.appliedEpochs())) {
      throw new IllegalArgumentException(
          "World public inventory differs from exact selected Account order or freeze checkpoint");
    }
  }

  private static boolean appliedEpochsMatch(
      DraftCommitBinding selectedCommit, List<AppliedEpoch> actual) {
    List<AppliedEpoch> expected =
        selectedCommit.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new AppliedEpoch(
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch(),
                        new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
            .toList();
    return !expected.isEmpty() && expected.equals(actual);
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be canonical lowercase SHA-256");
    }
  }

  private static void requireUuid(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static UUID canonicalUuid(String value, String label) {
    if (value == null) throw new IllegalArgumentException(label + " is required");
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static long parsePositiveCanonicalLong(String value, String label) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a canonical positive decimal string");
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException(label + " is not a canonical positive decimal string");
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(label + " is outside the supported long range", invalid);
    }
  }

  private static boolean isText(String value) {
    return value != null && !value.isBlank() && value.equals(value.strip());
  }

  /** Typed canonical public inventory. This schema intentionally has no private World row keys. */
  public record PublicEvidence(
      String schema,
      int schemaVersion,
      String completeness,
      PublicOwnerScope ownerScope,
      SelectedApplication selectedApplication,
      PublicAccountOrder accountOrder,
      Freeze freeze,
      Checkpoint checkpoint,
      SourceModel sourceModel,
      List<ArtifactDecision> artifactDecisions) {
    public PublicEvidence {
      Objects.requireNonNull(schema, "schema");
      Objects.requireNonNull(completeness, "completeness");
      Objects.requireNonNull(ownerScope, "ownerScope");
      Objects.requireNonNull(selectedApplication, "selectedApplication");
      Objects.requireNonNull(accountOrder, "accountOrder");
      Objects.requireNonNull(freeze, "freeze");
      Objects.requireNonNull(checkpoint, "checkpoint");
      Objects.requireNonNull(sourceModel, "sourceModel");
      artifactDecisions =
          List.copyOf(Objects.requireNonNull(artifactDecisions, "artifactDecisions"));
      requireSupportedProfile(
          schema,
          schemaVersion,
          completeness,
          ownerScope,
          selectedApplication,
          accountOrder,
          freeze,
          checkpoint,
          sourceModel,
          artifactDecisions);
    }

    public byte[] canonicalBytes() {
      try {
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(this));
      } catch (IOException failure) {
        throw new IllegalStateException("World public inventory could not be encoded", failure);
      }
    }

    public String digest() {
      return sha256(canonicalBytes());
    }

    private static void requireSupportedProfile(
        String schema,
        int schemaVersion,
        String completeness,
        PublicOwnerScope owner,
        SelectedApplication application,
        PublicAccountOrder account,
        Freeze freeze,
        Checkpoint checkpoint,
        SourceModel model,
        List<ArtifactDecision> artifactDecisions) {
      if (!SCHEMA.equals(schema)
          || schemaVersion != SCHEMA_VERSION
          || !"COMPLETE".equals(completeness)
          || artifactDecisions.size() != ARTIFACT_DECISIONS.size()
          || !artifactDecisions.equals(ARTIFACT_DECISIONS)) {
        throw new IllegalArgumentException("Unsupported or incomplete World inventory profile");
      }
      requireUuid(owner.canonicalTenantId(), "canonicalTenantId");
      requireUuid(owner.canonicalVersionId(), "canonicalVersionId");
      requireUuid(owner.versionIdentityOperationId(), "versionIdentityOperationId");
      requireUuid(owner.intakeRequestId(), "intakeRequestId");
      requireUuid(owner.intakeOperationId(), "intakeOperationId");
      requireUuid(owner.sourceOperationId(), "sourceOperationId");
      if (!GrpcPeerIdentity.isValidNamespace(owner.targetNamespace())
          || !isText(owner.intakeRequestDigest())
          || !isText(owner.sourceEvidenceDigest())
          || !isText(owner.intakeReceiptDigest())) {
        throw new IllegalArgumentException("World public inventory owner scope is incomplete");
      }
      requireDigest(owner.intakeRequestDigest(), "intakeRequestDigest");
      requireDigest(owner.sourceEvidenceDigest(), "sourceEvidenceDigest");
      requireDigest(owner.intakeReceiptDigest(), "intakeReceiptDigest");

      requireUuid(application.applicationOperationId(), "applicationOperationId");
      requireUuid(application.applicationRequestId(), "applicationRequestId");
      canonicalUuid(application.appliedCommitId(), "application.appliedCommitId");
      requireDigest(application.bindingDigest(), "application.bindingDigest");
      requireDigest(application.appliedResultDigest(), "application.appliedResultDigest");

      requireUuid(account.operationId(), "account.operationId");
      requireUuid(account.fenceId(), "account.fenceId");
      requireUuid(account.actorAccountId(), "account.actorAccountId");
      if (!isText(account.publicationRequestId())) {
        throw new IllegalArgumentException("World public Account order is incomplete");
      }
      requireDigest(account.selectionDigest(), "account.selectionDigest");
      canonicalUuid(account.selectedCommitId(), "account.selectedCommitId");
      requireDigest(account.bindingDigest(), "account.bindingDigest");

      requireUuid(freeze.publicationFence(), "publicationFence");
      if (!isText(freeze.publicationRequestId()) || !isText(freeze.publishWorkflowId())) {
        throw new IllegalArgumentException("World public freeze binding is incomplete");
      }
      if (freeze.requestDigest() == null || !HEX_SHA256.matcher(freeze.requestDigest()).matches()) {
        throw new IllegalArgumentException("World freeze request digest must be lowercase SHA-256");
      }
      parsePositiveCanonicalLong(freeze.versionStateEpoch(), "versionStateEpoch");

      canonicalUuid(checkpoint.appliedCommitId(), "checkpoint.appliedCommitId");
      if (checkpoint.contentDigest() == null
          || !HEX_SHA256.matcher(checkpoint.contentDigest()).matches()
          || checkpoint.digestSchemaVersion() <= 0) {
        throw new IllegalArgumentException("World checkpoint is incomplete or malformed");
      }

      if (!SOURCE_MODEL.equals(model.modelId())
          || model.graphSchemaVersion() != 2
          || !isSha256(model.graphDigest())
          || !isSha256(model.topologyResultDigest())
          || model.generationRuleInputCount() != 0
          || !model.generationRuleFields().equals(GENERATION_RULE_FIELDS)
          || !model.regionFields().equals(REGION_FIELDS)
          || !model.zoneFields().equals(ZONE_FIELDS)
          || !model.roomFields().equals(ROOM_FIELDS)
          || !model.roomExitFields().equals(ROOM_EXIT_FIELDS)
          || !model.spawnBindingFields().equals(SPAWN_BINDING_FIELDS)
          || model.familyCounts().size() != FAMILY_ORDER.size()) {
        throw new IllegalArgumentException("World source model is unsupported or incomplete");
      }
      for (int index = 0; index < FAMILY_ORDER.size(); index++) {
        FamilyCount count = model.familyCounts().get(index);
        if (!FAMILY_ORDER.get(index).equals(count.family()) || count.rowCount() < 0) {
          throw new IllegalArgumentException("World source family enumeration is incomplete");
        }
      }
      int roomCount = familyCount(model.familyCounts(), "ROOM");
      int regionCount = familyCount(model.familyCounts(), "REGION");
      int generationRuleCount = familyCount(model.familyCounts(), "GENERATION_RULE");
      int spawnCount = familyCount(model.familyCounts(), "WORLD_ENTITY_SPAWN_BINDING");
      if (roomCount < 1
          || generationRuleCount != 0
          || model.regionGeneratorInputs().size() != regionCount
          || model.generationRuleInputCount() != generationRuleCount
          || model.spawnBindingCount() != spawnCount
          || model.spawnBindingInputs().size() != spawnCount
          || model.appliedEpochs().isEmpty()) {
        throw new IllegalArgumentException(
            "World source-model counts do not establish completeness");
      }
      for (RegionGeneratorInput region : model.regionGeneratorInputs()) {
        requireUuid(region.regionTemplateId(), "regionTemplateId");
        if (region.generatorType() == null || region.generatorParams() == null) {
          throw new IllegalArgumentException("World region generator inputs must be explicit");
        }
        if (!region.generatorType().isEmpty() || !region.generatorParams().isEmpty()) {
          throw new IllegalArgumentException(
              "Opaque World region generator inputs have no supported requiredness interpretation");
        }
      }
      requireCanonicalRegionOrder(model.regionGeneratorInputs());
      UUID tenantId = owner.canonicalTenantId();
      UUID versionId = owner.canonicalVersionId();
      for (SpawnBindingInput spawn : model.spawnBindingInputs()) {
        requireUuid(spawn.bindingTemplateId(), "spawn.bindingTemplateId");
        requireUuid(spawn.roomTemplateId(), "spawn.roomTemplateId");
        requireUuid(spawn.entityTemplateId(), "spawn.entityTemplateId");
        if (!"ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID".equals(spawn.entityReferenceKind())
            || !tenantId.equals(spawn.entityTenantId())
            || !versionId.equals(spawn.entityVersionId())
            || !("ITEM".equals(spawn.entityTemplateType())
                || "NPC".equals(spawn.entityTemplateType()))
            || spawn.spawnCount() <= 0
            || spawn.respawnDelaySeconds() < 0) {
          throw new IllegalArgumentException(
              "World spawn binding is outside the closed supported profile");
        }
      }
      requireCanonicalSpawnOrder(model.spawnBindingInputs());
    }

    private static void requireCanonicalRegionOrder(List<RegionGeneratorInput> inputs) {
      Set<UUID> seen = new HashSet<>();
      String previous = null;
      for (RegionGeneratorInput input : inputs) {
        String key = input.regionTemplateId().toString();
        if (!seen.add(input.regionTemplateId())
            || (previous != null && previous.compareTo(key) >= 0)) {
          throw new IllegalArgumentException(
              "World region generator inputs must have unique canonical UUID order");
        }
        previous = key;
      }
    }

    private static void requireCanonicalSpawnOrder(List<SpawnBindingInput> inputs) {
      Set<UUID> seen = new HashSet<>();
      String previous = null;
      for (SpawnBindingInput input : inputs) {
        String key = input.bindingTemplateId().toString();
        if (!seen.add(input.bindingTemplateId())
            || (previous != null && previous.compareTo(key) >= 0)) {
          throw new IllegalArgumentException(
              "World spawn binding inputs must have unique canonical UUID order");
        }
        previous = key;
      }
    }

    private static int familyCount(List<FamilyCount> counts, String family) {
      return counts.stream()
          .filter(count -> family.equals(count.family()))
          .mapToInt(FamilyCount::rowCount)
          .findFirst()
          .orElseThrow(
              () -> new IllegalArgumentException("World inventory omitted family " + family));
    }

    private static boolean isSha256(String value) {
      return value != null && SHA256.matcher(value).matches();
    }
  }

  public record FamilyCount(String family, int rowCount) {
    public FamilyCount {
      Objects.requireNonNull(family, "family");
      if (rowCount < 0) throw new IllegalArgumentException("rowCount must be nonnegative");
    }
  }

  public record RegionGeneratorInput(
      UUID regionTemplateId, String generatorType, String generatorParams) {}

  public record SpawnBindingInput(
      UUID bindingTemplateId,
      UUID roomTemplateId,
      String entityTemplateType,
      String entityReferenceKind,
      UUID entityTenantId,
      UUID entityVersionId,
      UUID entityTemplateId,
      int spawnCount,
      int respawnDelaySeconds) {}

  public record SelectedApplication(
      UUID applicationOperationId,
      UUID applicationRequestId,
      String appliedCommitId,
      String bindingDigest,
      String appliedResultDigest) {}

  public record PublicAccountOrder(
      UUID operationId,
      UUID fenceId,
      UUID actorAccountId,
      String publicationRequestId,
      String selectionDigest,
      String selectedCommitId,
      String bindingDigest) {}

  /** Public World scope; private local tenant/version keys and source-intake rows are omitted. */
  public record PublicOwnerScope(
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      UUID versionIdentityOperationId,
      UUID intakeRequestId,
      UUID intakeOperationId,
      String intakeRequestDigest,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String intakeReceiptDigest) {}

  public record Freeze(
      UUID publicationFence,
      String publicationRequestId,
      String requestDigest,
      String versionStateEpoch,
      String publishWorkflowId) {}

  public record Checkpoint(String appliedCommitId, String contentDigest, int digestSchemaVersion) {}

  public record ArtifactDecision(String artifactKind, String state, String rule) {
    public ArtifactDecision {
      Objects.requireNonNull(artifactKind, "artifactKind");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(rule, "rule");
    }
  }

  public record SourceModel(
      String modelId,
      int graphSchemaVersion,
      String graphDigest,
      String topologyResultDigest,
      List<FamilyCount> familyCounts,
      List<RegionGeneratorInput> regionGeneratorInputs,
      List<String> generationRuleFields,
      List<String> regionFields,
      List<String> zoneFields,
      List<String> roomFields,
      List<String> roomExitFields,
      List<String> spawnBindingFields,
      List<SpawnBindingInput> spawnBindingInputs,
      int generationRuleInputCount,
      int spawnBindingCount,
      List<AppliedEpoch> appliedEpochs) {
    public SourceModel {
      familyCounts = List.copyOf(Objects.requireNonNull(familyCounts, "familyCounts"));
      regionGeneratorInputs =
          List.copyOf(Objects.requireNonNull(regionGeneratorInputs, "regionGeneratorInputs"));
      generationRuleFields =
          List.copyOf(Objects.requireNonNull(generationRuleFields, "generationRuleFields"));
      regionFields = List.copyOf(Objects.requireNonNull(regionFields, "regionFields"));
      zoneFields = List.copyOf(Objects.requireNonNull(zoneFields, "zoneFields"));
      roomFields = List.copyOf(Objects.requireNonNull(roomFields, "roomFields"));
      roomExitFields = List.copyOf(Objects.requireNonNull(roomExitFields, "roomExitFields"));
      spawnBindingFields =
          List.copyOf(Objects.requireNonNull(spawnBindingFields, "spawnBindingFields"));
      spawnBindingInputs =
          List.copyOf(Objects.requireNonNull(spawnBindingInputs, "spawnBindingInputs"));
      appliedEpochs = List.copyOf(Objects.requireNonNull(appliedEpochs, "appliedEpochs"));
    }
  }
}
