package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.ArtifactDecision;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Freeze;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicAccountOrder;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicOwnerScope;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SelectedApplication;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SourceModel;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SpawnBindingInput;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable, owner-derived complete inventory for the closed logical room/exit source model.
 *
 * <p>This is not an artifact payload or an Asset Storage manifest. It is derived from the exact
 * retained APPLIED graph/3 and the World-owned freeze checkpoint. Its inbound source declaration is
 * original authored input and currently supports only seven explicitly declared empty families;
 * legacy graph/2 bytes remain a separate exact-read path and never imply an empty declaration. The
 * supported room/exit profile uses authored edges directly and has no separate NAVMESH or
 * PATH_GRAPH bundle requirement.
 */
public final class WorldSelectedPublicationArtifactInventory {
  static final String SCHEMA = "world-selected-publication-artifact-inventory/v1";
  static final String INBOUND_CLOSURE_SCHEMA =
      WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA;
  private static final int SCHEMA_VERSION =
      WorldSelectedPublicationArtifactInventoryEvidence.SCHEMA_VERSION;
  private static final int INBOUND_CLOSURE_SCHEMA_VERSION =
      WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION;
  private static final String SOURCE_MODEL = "WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1";
  private static final String INBOUND_CLOSURE_SOURCE_MODEL =
      WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL;
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern HEX_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();
  private static final List<WorldCanonicalAuthoredGraph.Family> SUPPORTED_FAMILY_ORDER =
      List.of(
          WorldCanonicalAuthoredGraph.Family.REGION,
          WorldCanonicalAuthoredGraph.Family.ZONE,
          WorldCanonicalAuthoredGraph.Family.ROOM,
          WorldCanonicalAuthoredGraph.Family.ROOM_EXIT,
          WorldCanonicalAuthoredGraph.Family.GENERATION_RULE,
          WorldCanonicalAuthoredGraph.Family.WORLD_ENTITY_SPAWN_BINDING);
  private static final List<String> INBOUND_SOURCE_FAMILY_ORDER =
      List.of(
          "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT",
          "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT",
          "WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION",
          "WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING",
          "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK",
          "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE",
          "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING");
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

  enum UnrepresentableReason {
    UNKNOWN_GRAPH_FAMILY,
    ROOM_TOPOLOGY_REQUIRED,
    REGION_GENERATOR_INPUT,
    GENERATION_RULE_INPUT,
    SPAWN_BINDING_INPUT
  }

  static final class UnrepresentableRequirednessException extends ConflictException {
    private final UnrepresentableReason reason;

    UnrepresentableRequirednessException(UnrepresentableReason reason, String message) {
      super(message);
      this.reason = Objects.requireNonNull(reason, "reason");
    }

    UnrepresentableReason reason() {
      return reason;
    }
  }

  record OwnerScope(
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String localTenantKey,
      String localVersionKey,
      UUID versionIdentityOperationId,
      String gameDesignVersionId,
      UUID intakeRequestId,
      UUID intakeOperationId,
      String intakeRequestDigest,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String intakeReceiptDigest) {}

  record SourceIntake(
      String worldSlug,
      String sourceGameRowId,
      String sourceGameTenantKey,
      String sourceProvenanceKind) {}

  public record AccountOrder(
      UUID operationId,
      UUID fenceId,
      UUID actorAccountId,
      String publicationRequestId,
      String selectionDigest,
      String selectedCommitId,
      String bindingDigest,
      byte[] canonicalBindingBytes) {
    public AccountOrder {
      canonicalBindingBytes =
          Objects.requireNonNull(canonicalBindingBytes, "canonicalBindingBytes").clone();
    }

    @Override
    public byte[] canonicalBindingBytes() {
      return canonicalBindingBytes.clone();
    }
  }

  record Envelope(
      String schema,
      int schemaVersion,
      String completeness,
      OwnerScope ownerScope,
      SourceIntake sourceIntake,
      SelectedApplication selectedApplication,
      AccountOrder accountOrder,
      Freeze freeze,
      Checkpoint checkpoint,
      SourceModel sourceModel,
      List<ArtifactDecision> artifactDecisions) {
    Envelope {
      Objects.requireNonNull(schema, "schema");
      Objects.requireNonNull(completeness, "completeness");
      Objects.requireNonNull(ownerScope, "ownerScope");
      Objects.requireNonNull(sourceIntake, "sourceIntake");
      Objects.requireNonNull(selectedApplication, "selectedApplication");
      Objects.requireNonNull(accountOrder, "accountOrder");
      Objects.requireNonNull(freeze, "freeze");
      Objects.requireNonNull(checkpoint, "checkpoint");
      Objects.requireNonNull(sourceModel, "sourceModel");
      artifactDecisions =
          List.copyOf(Objects.requireNonNull(artifactDecisions, "artifactDecisions"));
    }
  }

  private final Envelope envelope;
  private final byte[] canonicalBytes;
  private final String contentDigest;

  WorldSelectedPublicationArtifactInventory(Envelope envelope, byte[] canonicalBytes) {
    this.envelope = Objects.requireNonNull(envelope, "envelope");
    this.canonicalBytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
    this.contentDigest = WorldDraftGraphAppliedResult.digest(this.canonicalBytes);
  }

  static WorldSelectedPublicationArtifactInventory capture(
      FrozenAttempt attempt,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldSelectedDraftPublicationCheckpointRepository.CapturedCheckpoint captured) {
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(accountBinding, "accountBinding");
    Objects.requireNonNull(captured, "captured");
    var request = attempt.request();
    var checkpoint = attempt.checkpoint();
    if (!captured.checkpoint().equals(checkpoint)
        || !request.ownerBinding().equals(captured.owner().binding())
        || !request
            .ownerBinding()
            .equals(captured.application().application().operation().ownerBinding())
        || !request.canonicalTenantId().equals(captured.graph().tenantId())
        || !request.canonicalVersionId().equals(captured.graph().versionId())
        || captured.graph().localTenantKey() != captured.owner().localTenantKey()
        || captured.graph().localVersionKey() != captured.owner().localVersionKey()
        || !checkpoint
            .appliedCommitId()
            .equals(captured.application().application().operation().commitId().toString())
        || !accountBinding
            .input()
            .selection()
            .selectedCommit()
            .equals(captured.application().application().operation().binding())
        || !Arrays.equals(
            accountBinding.input().selection().selectedCommit().canonicalBytes(),
            captured.application().application().operation().binding().canonicalBytes())) {
      throw new ConflictException(
          "World artifact inventory source differs from exact APPLIED selection and freeze checkpoint");
    }

    WorldCanonicalAuthoredGraph graph = captured.graph();
    requireSupportedFamilySchema();
    var inboundClosure =
        graph
            .inboundSourceClosure()
            .orElseThrow(
                () ->
                    new ConflictException(
                        "World selected inventory requires the exact original inbound source closure declaration"));
    List<FamilyCount> counts = new ArrayList<>();
    for (WorldCanonicalAuthoredGraph.Family family : SUPPORTED_FAMILY_ORDER) {
      counts.add(new FamilyCount(family.name(), graph.family(family).size()));
    }
    int roomCount = familyCount(counts, WorldCanonicalAuthoredGraph.Family.ROOM.name());
    if (roomCount == 0 || graph.family(WorldCanonicalAuthoredGraph.Family.ROOM).isEmpty()) {
      throw unrepresentable(
          UnrepresentableReason.ROOM_TOPOLOGY_REQUIRED,
          "World complete artifact inventory requires the selected fresh graph's typed start room");
    }

    List<RegionGeneratorInput> generatorInputs = new ArrayList<>();
    for (var row : graph.family(WorldCanonicalAuthoredGraph.Family.REGION)) {
      if (!row.authored().mutation().hasRegion()) {
        throw unrepresentable(
            UnrepresentableReason.UNKNOWN_GRAPH_FAMILY,
            "World selected graph region lacks its closed generator-input representation");
      }
      var region = row.authored().mutation().getRegion();
      String generatorType = region.getGeneratorType();
      String generatorParams = region.getGeneratorParams();
      generatorInputs.add(
          new RegionGeneratorInput(row.template().templateId(), generatorType, generatorParams));
    }

    int generationRuleCount =
        graph.family(WorldCanonicalAuthoredGraph.Family.GENERATION_RULE).size();

    List<SpawnBindingInput> spawnInputs = new ArrayList<>();
    for (var row : graph.family(WorldCanonicalAuthoredGraph.Family.WORLD_ENTITY_SPAWN_BINDING)) {
      if (!row.content().hasWorldEntitySpawnBinding() || row.entityReference() == null) {
        throw unrepresentable(
            UnrepresentableReason.UNKNOWN_GRAPH_FAMILY,
            "World selected graph spawn binding lacks its closed typed Entity reference");
      }
      var spawn = row.content().getWorldEntitySpawnBinding();
      var reference = row.entityReference();
      spawnInputs.add(
          new SpawnBindingInput(
              row.template().templateId(),
              UUID.fromString(spawn.getRoomId()),
              spawn.getEntityTemplateType().name(),
              reference.kind().name(),
              reference.tenantId(),
              reference.versionId(),
              reference.templateId(),
              spawn.getSpawnCount(),
              spawn.getRespawnDelaySeconds()));
    }
    requireSupportedRequiredness(
        graph.tenantId(), graph.versionId(), generatorInputs, generationRuleCount, spawnInputs);

    var operation = captured.application().application().operation();
    var selected = accountBinding.input().selection();
    var source = captured.owner().receipt().source();
    var owner = request.ownerBinding();
    var sourceModel =
        new SourceModel(
            INBOUND_CLOSURE_SOURCE_MODEL,
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION,
            WorldDraftGraphAppliedResult.digest(captured.application().graphBytes()),
            WorldDraftGraphAppliedResult.digest(captured.topology().resultBytes()),
            counts,
            generatorInputs,
            GENERATION_RULE_FIELDS,
            REGION_FIELDS,
            ZONE_FIELDS,
            ROOM_FIELDS,
            ROOM_EXIT_FIELDS,
            SPAWN_BINDING_FIELDS,
            spawnInputs,
            generationRuleCount,
            familyCount(
                counts, WorldCanonicalAuthoredGraph.Family.WORLD_ENTITY_SPAWN_BINDING.name()),
            captured.application().appliedEpochs(),
            new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceClosureDeclaration(
                inboundClosure.schemaVersion(),
                inboundClosure.familyCounts().stream()
                    .map(
                        family ->
                            new WorldSelectedPublicationArtifactInventoryEvidence
                                .InboundSourceFamilyCount(family.family().name(), family.count()))
                    .toList()));
    var bindingBytes = accountBinding.canonicalBytes();
    var envelope =
        new Envelope(
            INBOUND_CLOSURE_SCHEMA,
            INBOUND_CLOSURE_SCHEMA_VERSION,
            "COMPLETE",
            new OwnerScope(
                owner.targetNamespace(),
                owner.canonicalTenantId(),
                owner.canonicalVersionId(),
                Long.toString(captured.owner().localTenantKey()),
                Long.toString(captured.owner().localVersionKey()),
                owner.versionIdentityOperationId(),
                Long.toString(owner.gameDesignVersionId()),
                owner.intakeRequestId(),
                owner.intakeOperationId(),
                owner.intakeRequestDigest(),
                owner.sourceOperationId(),
                owner.sourceEvidenceDigest(),
                owner.intakeReceiptDigest()),
            new SourceIntake(
                captured.owner().receipt().worldSlug(),
                Long.toString(source.sourceGameRowId()),
                source.sourceGameTenantKey(),
                source.provenanceKind()),
            new SelectedApplication(
                operation.operationId(),
                operation.requestId(),
                operation.commitId().toString(),
                operation.binding().digest(),
                captured.application().digest()),
            new AccountOrder(
                accountBinding.operationId(),
                accountBinding.fenceId(),
                accountBinding.input().actorAccountId(),
                selected.intent().publishRequestId(),
                selected.digest(),
                selected.selectedCommit().commitId().toString(),
                WorldDraftGraphAppliedResult.digest(bindingBytes),
                bindingBytes),
            new Freeze(
                attempt.publicationFence(),
                request.publicationRequestId(),
                request.requestDigest(),
                Long.toString(request.versionStateEpoch()),
                request.publishWorkflowId()),
            new Checkpoint(
                checkpoint.appliedCommitId(),
                checkpoint.contentDigest(),
                checkpoint.digestSchemaVersion()),
            sourceModel,
            ARTIFACT_DECISIONS);
    requireEnvelopeBinding(envelope, attempt, accountBinding);
    return new WorldSelectedPublicationArtifactInventory(envelope, encode(envelope));
  }

  static WorldSelectedPublicationArtifactInventory fromStored(
      byte[] canonicalBytes,
      String storedDigest,
      FrozenAttempt attempt,
      AccountPublicationAuthorizationBinding accountBinding,
      long expectedLocalTenantKey,
      long expectedLocalVersionKey) {
    Objects.requireNonNull(canonicalBytes, "canonicalBytes");
    Objects.requireNonNull(storedDigest, "storedDigest");
    Envelope envelope = decodeCanonicalEnvelope(canonicalBytes);
    if (!WorldDraftGraphAppliedResult.digest(canonicalBytes).equals(storedDigest)
        || !Long.toString(expectedLocalTenantKey).equals(envelope.ownerScope().localTenantKey())
        || !Long.toString(expectedLocalVersionKey)
            .equals(envelope.ownerScope().localVersionKey())) {
      throw new ConflictException("Stored World artifact inventory is corrupt or noncanonical");
    }
    requireEnvelopeBinding(envelope, attempt, accountBinding);
    requireCompleteSupportedModel(envelope);
    return new WorldSelectedPublicationArtifactInventory(envelope, canonicalBytes);
  }

  static Envelope decodeCanonicalEnvelope(byte[] canonicalBytes) {
    Objects.requireNonNull(canonicalBytes, "canonicalBytes");
    final Envelope envelope;
    try {
      envelope = JSON.readValue(canonicalBytes, Envelope.class);
    } catch (RuntimeException invalid) {
      throw new ConflictException("Stored World artifact inventory is not a closed typed value");
    }
    if (envelope == null || !Arrays.equals(canonicalBytes, encode(envelope))) {
      throw new ConflictException("Stored World artifact inventory is null or noncanonical");
    }
    requireCanonicalLongFields(envelope);
    return envelope;
  }

  Envelope envelope() {
    return envelope;
  }

  byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  String contentDigest() {
    return contentDigest;
  }

  /** Returns the public canonical inventory projection, never private numeric owner/source keys. */
  public PublicEvidence publicEvidence() {
    var owner = envelope.ownerScope();
    return new PublicEvidence(
        envelope.schema(),
        envelope.schemaVersion(),
        envelope.completeness(),
        new PublicOwnerScope(
            owner.targetNamespace(),
            owner.canonicalTenantId(),
            owner.canonicalVersionId(),
            owner.versionIdentityOperationId(),
            owner.intakeRequestId(),
            owner.intakeOperationId(),
            owner.intakeRequestDigest(),
            owner.sourceOperationId(),
            owner.sourceEvidenceDigest(),
            owner.intakeReceiptDigest()),
        envelope.selectedApplication(),
        publicAccountOrder(envelope.accountOrder()),
        envelope.freeze(),
        envelope.checkpoint(),
        publicSourceModel(envelope.sourceModel()),
        envelope.artifactDecisions());
  }

  /**
   * Stable public projection order; retained private-envelope bytes preserve original graph order.
   */
  private static SourceModel publicSourceModel(SourceModel model) {
    List<RegionGeneratorInput> regions =
        model.regionGeneratorInputs().stream()
            .sorted(Comparator.comparing(input -> input.regionTemplateId().toString()))
            .toList();
    List<SpawnBindingInput> spawnBindings =
        model.spawnBindingInputs().stream()
            .sorted(Comparator.comparing(input -> input.bindingTemplateId().toString()))
            .toList();
    return new SourceModel(
        model.modelId(),
        model.graphSchemaVersion(),
        model.graphDigest(),
        model.topologyResultDigest(),
        model.familyCounts(),
        regions,
        model.generationRuleFields(),
        model.regionFields(),
        model.zoneFields(),
        model.roomFields(),
        model.roomExitFields(),
        model.spawnBindingFields(),
        spawnBindings,
        model.generationRuleInputCount(),
        model.spawnBindingCount(),
        model.appliedEpochs(),
        model.inboundSourceClosure());
  }

  /** Canonical bytes for the safe public projection; private owner keys are never serialized. */
  public byte[] publicCanonicalBytes() {
    return publicEvidence().canonicalBytes();
  }

  /**
   * Digest of {@link #publicCanonicalBytes()}, distinct from the private durable-envelope digest.
   */
  public String publicDigest() {
    return publicEvidence().digest();
  }

  private static PublicAccountOrder publicAccountOrder(AccountOrder account) {
    return new PublicAccountOrder(
        account.operationId(),
        account.fenceId(),
        account.actorAccountId(),
        account.publicationRequestId(),
        account.selectionDigest(),
        account.selectedCommitId(),
        account.bindingDigest());
  }

  private static void requireEnvelopeBinding(
      Envelope envelope,
      FrozenAttempt attempt,
      AccountPublicationAuthorizationBinding accountBinding) {
    requireCanonicalLongFields(envelope);
    var request = attempt.request();
    var owner = request.ownerBinding();
    var identity = envelope.ownerScope();
    var selected = accountBinding.input().selection();
    var selectedCommit = selected.selectedCommit();
    var checkpoint = attempt.checkpoint();
    var freeze = envelope.freeze();
    var account = envelope.accountOrder();
    var application = envelope.selectedApplication();
    var source = envelope.sourceIntake();
    var sourceModel = envelope.sourceModel();
    boolean legacyEnvelope =
        SCHEMA.equals(envelope.schema()) && envelope.schemaVersion() == SCHEMA_VERSION;
    boolean inboundClosureEnvelope =
        INBOUND_CLOSURE_SCHEMA.equals(envelope.schema())
            && envelope.schemaVersion() == INBOUND_CLOSURE_SCHEMA_VERSION;
    if ((!legacyEnvelope && !inboundClosureEnvelope)
        || !"COMPLETE".equals(envelope.completeness())
        || !nonNil(identity.canonicalTenantId())
        || !nonNil(identity.canonicalVersionId())
        || !nonNil(identity.versionIdentityOperationId())
        || !nonNil(identity.intakeRequestId())
        || !nonNil(identity.intakeOperationId())
        || !nonNil(identity.sourceOperationId())
        || !isSha256(identity.intakeRequestDigest())
        || !isSha256(identity.sourceEvidenceDigest())
        || !isSha256(identity.intakeReceiptDigest())
        || !hasText(source.worldSlug())
        || !hasText(source.sourceGameTenantKey())
        || !hasText(source.sourceProvenanceKind())
        || !owner.targetNamespace().equals(identity.targetNamespace())
        || !owner.canonicalTenantId().equals(identity.canonicalTenantId())
        || !owner.canonicalVersionId().equals(identity.canonicalVersionId())
        || !owner.versionIdentityOperationId().equals(identity.versionIdentityOperationId())
        || !Long.toString(owner.gameDesignVersionId()).equals(identity.gameDesignVersionId())
        || !owner.intakeRequestId().equals(identity.intakeRequestId())
        || !owner.intakeOperationId().equals(identity.intakeOperationId())
        || !owner.intakeRequestDigest().equals(identity.intakeRequestDigest())
        || !owner.sourceOperationId().equals(identity.sourceOperationId())
        || !owner.sourceEvidenceDigest().equals(identity.sourceEvidenceDigest())
        || !owner.intakeReceiptDigest().equals(identity.intakeReceiptDigest())
        || !attempt.publicationFence().equals(freeze.publicationFence())
        || !request.publicationRequestId().equals(freeze.publicationRequestId())
        || !request.requestDigest().equals(freeze.requestDigest())
        || !Long.toString(request.versionStateEpoch()).equals(freeze.versionStateEpoch())
        || !request.publishWorkflowId().equals(freeze.publishWorkflowId())
        || !checkpoint.appliedCommitId().equals(envelope.checkpoint().appliedCommitId())
        || !checkpoint.contentDigest().equals(envelope.checkpoint().contentDigest())
        || checkpoint.digestSchemaVersion() != envelope.checkpoint().digestSchemaVersion()
        || !accountBinding.operationId().equals(account.operationId())
        || !accountBinding.fenceId().equals(account.fenceId())
        || !accountBinding.input().actorAccountId().equals(account.actorAccountId())
        || !selected.intent().publishRequestId().equals(account.publicationRequestId())
        || !selected.digest().equals(account.selectionDigest())
        || !selected.selectedCommit().commitId().toString().equals(account.selectedCommitId())
        || !isSha256(account.selectionDigest())
        || !isSha256(account.bindingDigest())
        || !WorldDraftGraphAppliedResult.digest(accountBinding.canonicalBytes())
            .equals(account.bindingDigest())
        || !Arrays.equals(accountBinding.canonicalBytes(), account.canonicalBindingBytes())
        || !checkpoint.appliedCommitId().equals(application.appliedCommitId())
        || !selectedCommit.commitId().toString().equals(application.appliedCommitId())
        || !selectedCommit.requestId().equals(application.applicationRequestId())
        || !selectedCommit.digest().equals(application.bindingDigest())
        || application.applicationOperationId() == null
        || application.applicationOperationId().equals(new UUID(0L, 0L))
        || application.applicationRequestId() == null
        || application.applicationRequestId().equals(new UUID(0L, 0L))
        || !application.applicationRequestId().equals(selectedCommit.requestId())
        || !isSha256(application.bindingDigest())
        || !isSha256(application.appliedResultDigest())
        || !nonNil(account.operationId())
        || !nonNil(account.fenceId())
        || !nonNil(account.actorAccountId())
        || !nonNil(freeze.publicationFence())
        || !isHexDigest(freeze.requestDigest())
        || !isSha256(sourceModel.graphDigest())
        || !isSha256(sourceModel.topologyResultDigest())
        || !appliedEpochsMatch(selectedCommit, sourceModel.appliedEpochs())
        || !accountBinding.operationId().equals(account.operationId())) {
      throw new ConflictException(
          "World artifact inventory differs from exact Account order, selected graph, or freeze");
    }
  }

  private static void requireCompleteSupportedModel(Envelope envelope) {
    if (envelope.sourceModel() == null) {
      throw new ConflictException(
          "Stored World artifact inventory does not declare the complete supported source model");
    }
    SourceModel sourceModel = envelope.sourceModel();
    boolean legacyEnvelope =
        SCHEMA.equals(envelope.schema()) && envelope.schemaVersion() == SCHEMA_VERSION;
    boolean inboundClosureEnvelope =
        INBOUND_CLOSURE_SCHEMA.equals(envelope.schema())
            && envelope.schemaVersion() == INBOUND_CLOSURE_SCHEMA_VERSION;
    boolean legacyModel =
        legacyEnvelope
            && SOURCE_MODEL.equals(sourceModel.modelId())
            && sourceModel.graphSchemaVersion() == 2
            && sourceModel.inboundSourceClosure() == null;
    boolean inboundClosureModel =
        inboundClosureEnvelope
            && INBOUND_CLOSURE_SOURCE_MODEL.equals(sourceModel.modelId())
            && sourceModel.graphSchemaVersion()
                == WorldSelectedPublicationArtifactInventoryEvidence
                    .INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION
            && sourceModel.inboundSourceClosure() != null;
    if (envelope.sourceModel() == null
        || (!legacyModel && !inboundClosureModel)
        || envelope.sourceModel().generationRuleInputCount() != 0
        || envelope.sourceModel().familyCounts().size() != SUPPORTED_FAMILY_ORDER.size()
        || !envelope.sourceModel().regionFields().equals(REGION_FIELDS)
        || !envelope.sourceModel().zoneFields().equals(ZONE_FIELDS)
        || !envelope.sourceModel().roomFields().equals(ROOM_FIELDS)
        || !envelope.sourceModel().roomExitFields().equals(ROOM_EXIT_FIELDS)
        || !envelope.sourceModel().generationRuleFields().equals(GENERATION_RULE_FIELDS)
        || !envelope.sourceModel().spawnBindingFields().equals(SPAWN_BINDING_FIELDS)
        || envelope.artifactDecisions().size() != ARTIFACT_DECISIONS.size()
        || !envelope.artifactDecisions().equals(ARTIFACT_DECISIONS)
        || !isSha256(envelope.sourceModel().graphDigest())
        || !isSha256(envelope.sourceModel().topologyResultDigest())
        || !isHexDigest(envelope.checkpoint().contentDigest())
        || envelope.checkpoint().digestSchemaVersion() != (inboundClosureModel ? 4 : 3)) {
      throw new ConflictException(
          "Stored World artifact inventory does not declare the complete supported source model");
    }
    if (inboundClosureModel) {
      requireEmptyInboundClosure(sourceModel.inboundSourceClosure());
    }
    for (int index = 0; index < SUPPORTED_FAMILY_ORDER.size(); index++) {
      var familyCount = envelope.sourceModel().familyCounts().get(index);
      if (!SUPPORTED_FAMILY_ORDER.get(index).name().equals(familyCount.family())
          || familyCount.rowCount() < 0) {
        throw new ConflictException(
            "Stored World artifact inventory family enumeration is incomplete");
      }
    }
    if (familyCount(
                envelope.sourceModel().familyCounts(),
                WorldCanonicalAuthoredGraph.Family.ROOM.name())
            == 0
        || familyCount(
                envelope.sourceModel().familyCounts(),
                WorldCanonicalAuthoredGraph.Family.GENERATION_RULE.name())
            != 0
        || familyCount(
                envelope.sourceModel().familyCounts(),
                WorldCanonicalAuthoredGraph.Family.WORLD_ENTITY_SPAWN_BINDING.name())
            != envelope.sourceModel().spawnBindingCount()
        || envelope.sourceModel().spawnBindingInputs().size()
            != envelope.sourceModel().spawnBindingCount()
        || envelope.sourceModel().regionGeneratorInputs().size()
            != familyCount(
                envelope.sourceModel().familyCounts(),
                WorldCanonicalAuthoredGraph.Family.REGION.name())
        || envelope.sourceModel().appliedEpochs().isEmpty()) {
      throw new ConflictException(
          "Stored World artifact inventory claims an unsupported requiredness input");
    }
    requireSupportedRequiredness(
        envelope.ownerScope().canonicalTenantId(),
        envelope.ownerScope().canonicalVersionId(),
        envelope.sourceModel().regionGeneratorInputs(),
        envelope.sourceModel().generationRuleInputCount(),
        envelope.sourceModel().spawnBindingInputs());
  }

  private static void requireSupportedFamilySchema() {
    if (!Arrays.equals(
        WorldCanonicalAuthoredGraph.Family.values(),
        SUPPORTED_FAMILY_ORDER.toArray(WorldCanonicalAuthoredGraph.Family[]::new))) {
      throw unrepresentable(
          UnrepresentableReason.UNKNOWN_GRAPH_FAMILY,
          "World authored graph family catalog changed without an artifact-requiredness rule");
    }
  }

  private static void requireEmptyInboundClosure(
      WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceClosureDeclaration
          declaration) {
    if (declaration == null
        || declaration.schemaVersion() != 1
        || declaration.familyCounts().size() != INBOUND_SOURCE_FAMILY_ORDER.size()) {
      throw new ConflictException(
          "Stored World inventory omits the complete version-1 inbound source declaration");
    }
    for (int index = 0; index < INBOUND_SOURCE_FAMILY_ORDER.size(); index++) {
      var count = declaration.familyCounts().get(index);
      if (!INBOUND_SOURCE_FAMILY_ORDER.get(index).equals(count.family()) || count.count() != 0) {
        throw new ConflictException(
            "Stored World inventory has unknown or unsupported nonempty inbound source input");
      }
    }
  }

  static void requireSupportedRequiredness(
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      List<RegionGeneratorInput> regionInputs,
      int generationRuleInputCount,
      List<SpawnBindingInput> spawnInputs) {
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
    if (generationRuleInputCount != 0) {
      throw unrepresentable(
          UnrepresentableReason.GENERATION_RULE_INPUT,
          "World selected graph has GENERATION_RULE name/scope/value inputs without a closed requiredness registry");
    }
    for (var region : regionInputs) {
      if (region.regionTemplateId() == null
          || region.generatorType() == null
          || region.generatorParams() == null) {
        throw unrepresentable(
            UnrepresentableReason.UNKNOWN_GRAPH_FAMILY,
            "World complete inventory omitted an explicit REGION generatorType/generatorParams input");
      }
      if (!region.generatorType().isEmpty() || !region.generatorParams().isEmpty()) {
        throw unrepresentable(
            UnrepresentableReason.REGION_GENERATOR_INPUT,
            "World selected graph has non-empty opaque REGION generatorType/generatorParams; derived-artifact requiredness is unrepresentable");
      }
    }
    for (var spawn : spawnInputs) {
      if (spawn == null
          || !"ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID".equals(spawn.entityReferenceKind())
          || !canonicalTenantId.equals(spawn.entityTenantId())
          || !canonicalVersionId.equals(spawn.entityVersionId())
          || spawn.bindingTemplateId() == null
          || spawn.roomTemplateId() == null
          || spawn.entityTemplateId() == null
          || !("ITEM".equals(spawn.entityTemplateType())
              || "NPC".equals(spawn.entityTemplateType()))
          || spawn.spawnCount() <= 0
          || spawn.respawnDelaySeconds() < 0) {
        throw unrepresentable(
            UnrepresentableReason.SPAWN_BINDING_INPUT,
            "World selected graph spawn binding has an unsupported typed Entity reference or count representation");
      }
    }
  }

  private static int familyCount(List<FamilyCount> counts, String family) {
    return counts.stream()
        .filter(count -> family.equals(count.family()))
        .mapToInt(FamilyCount::rowCount)
        .findFirst()
        .orElseThrow(
            () ->
                unrepresentable(
                    UnrepresentableReason.UNKNOWN_GRAPH_FAMILY,
                    "World selected graph omitted a required closed source family"));
  }

  private static boolean isSha256(String value) {
    return value != null && SHA256.matcher(value).matches();
  }

  private static boolean isHexDigest(String value) {
    return value != null && HEX_DIGEST.matcher(value).matches();
  }

  private static void requireCanonicalLongFields(Envelope envelope) {
    var owner = envelope.ownerScope();
    var source = envelope.sourceIntake();
    var freeze = envelope.freeze();
    parsePositiveCanonicalLong(owner.localTenantKey(), "localTenantKey");
    parsePositiveCanonicalLong(owner.localVersionKey(), "localVersionKey");
    parsePositiveCanonicalLong(owner.gameDesignVersionId(), "gameDesignVersionId");
    parsePositiveCanonicalLong(source.sourceGameRowId(), "sourceGameRowId");
    parsePositiveCanonicalLong(freeze.versionStateEpoch(), "versionStateEpoch");
  }

  private static long parsePositiveCanonicalLong(String value, String field) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw new ConflictException(
          "Stored World artifact inventory has a noncanonical positive decimal " + field);
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
        throw new ConflictException(
            "Stored World artifact inventory has a noncanonical positive decimal " + field);
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new ConflictException(
          "Stored World artifact inventory has an out-of-range positive decimal " + field);
    }
  }

  private static boolean nonNil(UUID value) {
    return value != null && !value.equals(new UUID(0L, 0L));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean appliedEpochsMatch(
      net.firedevops.firemud.common.authoring.DraftCommitBinding selectedCommit,
      List<AppliedEpoch> actual) {
    var expected =
        selectedCommit.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
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

  static byte[] encode(Envelope envelope) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(envelope));
    } catch (IOException failure) {
      throw new ConflictException("World complete artifact inventory could not be encoded");
    }
  }

  private static UnrepresentableRequirednessException unrepresentable(
      UnrepresentableReason reason, String message) {
    return new UnrepresentableRequirednessException(reason, message);
  }
}
