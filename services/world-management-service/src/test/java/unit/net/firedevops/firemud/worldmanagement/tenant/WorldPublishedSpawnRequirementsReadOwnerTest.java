package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.EntityTemplateReference;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.FamilyCount;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.GenerationRequirement;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.SpawnRequirement;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Template;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan.GenerationRuleIntent;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan.SourceRow;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan.SpawnBindingIntent;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.FreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.Node;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.GenerationRuleDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldEntitySpawnBindingDesignMutation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldPublishedSpawnRequirementsReadOwnerTest {
  private static final String NAMESPACE = "test";
  private static final UUID READ_ID = uuid(1);
  private static final UUID TENANT = uuid(2);
  private static final UUID VERSION = uuid(3);
  private final AuthoredWorldLaunchDescriptorClient gameDesign =
      mock(AuthoredWorldLaunchDescriptorClient.class);
  private final WorldPublishedStartLocationRepository published =
      mock(WorldPublishedStartLocationRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  @AfterEach
  void clearThreadState() {
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void rejectsWrongPeerAndEndUserBeforeRequestDecodingOrOwnerAccess() {
    var malformed =
        ReadWorldPublishedSpawnRequirementsRequest.newBuilder().setSchemaVersion(-1).build();
    for (GrpcPeerIdentity identity :
        List.of(
            peer("game-design-service", NAMESPACE), peer("entity-management-service", "other"))) {
      try (var ignored = attach(identity)) {
        assertThatThrownBy(() -> owner().read(malformed))
            .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
            .satisfies(
                failure ->
                    assertThat(
                            ((WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException)
                                    failure)
                                .code())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
      }
    }
    SessionContext.setContext(TENANT.toString(), List.of(), java.util.Map.of());
    try (var ignored = attach(peer("entity-management-service", NAMESPACE))) {
      assertThatThrownBy(() -> owner().read(malformed))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure ->
                  assertThat(
                          ((WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure)
                              .code())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    }
    verifyNoInteractions(gameDesign, published, transactions);
  }

  @Test
  void ambientTransactionIsRejectedBeforeDecodingOrGameDesignRead() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try (var ignored = attach(peer("entity-management-service", NAMESPACE))) {
      assertThatThrownBy(
              () ->
                  owner()
                      .read(
                          ReadWorldPublishedSpawnRequirementsRequest.newBuilder()
                              .setSchemaVersion(-1)
                              .build()))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure ->
                  assertThat(
                          ((WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure)
                              .code())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
    }
    verifyNoInteractions(gameDesign, published, transactions);
  }

  @Test
  void requestNamespaceMustMatchConfiguredOwnerBeforeGameDesignRead() {
    try (var ignored = attach(peer("entity-management-service", NAMESPACE))) {
      assertThatThrownBy(() -> owner().read(wireRequest("other")))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure ->
                  assertThat(
                          ((WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure)
                              .code())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    }
    verifyNoInteractions(gameDesign, published, transactions);
  }

  @Test
  void unavailableGameDesignReadNeverOpensWorldSnapshot() {
    var wire = wireRequest(NAMESPACE);
    when(gameDesign.getComplete(any(GetLaunchDescriptorRequest.class)))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    var owner = owner();
    try (var ignored = attach(peer("entity-management-service", NAMESPACE))) {
      assertThatThrownBy(() -> owner.read(wire))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure ->
                  assertThat(
                          ((WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure)
                              .code())
                      .isEqualTo(Status.Code.UNAVAILABLE));
    }
    verify(gameDesign).getComplete(any(GetLaunchDescriptorRequest.class));
    verifyNoInteractions(published, transactions);
  }

  @Test
  void changedReleaseAttestationDigestFailsBeforeWorldRead() {
    var wire = wireRequest(NAMESPACE);
    var pair = mock(CompleteLaunchBindingEvidence.class);
    var descriptor = mock(AuthoredWorldLaunchDescriptorEvidence.class);
    var release = mock(AuthoredWorldReleaseAttestationEvidence.class);
    when(gameDesign.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(pair);
    when(pair.descriptor()).thenReturn(descriptor);
    when(pair.releaseAttestation()).thenReturn(release);
    when(descriptor.targetNamespace()).thenReturn(NAMESPACE);
    when(release.targetNamespace()).thenReturn(NAMESPACE);
    when(release.evidenceDigest()).thenReturn("sha256:" + "f".repeat(64));
    when(release.schemaVersion())
        .thenReturn(AuthoredWorldReleaseAttestationEvidence.CLOSURE_SELECTOR_SCHEMA_VERSION);
    when(release.worldStartLocationEvidence())
        .thenReturn(mock(WorldPublishedStartLocationEvidence.class));
    try (var ignored = attach(peer("entity-management-service", NAMESPACE))) {
      assertThatThrownBy(() -> owner().read(wire))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure ->
                  assertThat(
                          ((WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure)
                              .code())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
    }
    verifyNoInteractions(published, transactions);
  }

  @Test
  void malformedFrozenSourceFailsClosedAfterTheOwnedRead() {
    var wire = wireRequest(NAMESPACE);
    var selector = mock(WorldPublishedStartLocationEvidence.class);
    when(selector.request()).thenReturn(selectorRequest());
    var pair = mock(CompleteLaunchBindingEvidence.class);
    var descriptor = mock(AuthoredWorldLaunchDescriptorEvidence.class);
    var release = mock(AuthoredWorldReleaseAttestationEvidence.class);
    when(gameDesign.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(pair);
    when(pair.descriptor()).thenReturn(descriptor);
    when(pair.releaseAttestation()).thenReturn(release);
    when(descriptor.targetNamespace()).thenReturn(NAMESPACE);
    when(release.targetNamespace()).thenReturn(NAMESPACE);
    when(release.evidenceDigest()).thenReturn(wire.getExpectedReleaseAttestationDigest());
    when(release.schemaVersion())
        .thenReturn(AuthoredWorldReleaseAttestationEvidence.CLOSURE_SELECTOR_SCHEMA_VERSION);
    when(release.worldStartLocationEvidence()).thenReturn(selector);

    var source = mock(WorldPublishedStartLocationSource.class);
    var frozen = mock(WorldCanonicalFrozenTopology.class);
    when(source.frozenTopology()).thenReturn(frozen);
    when(frozen.status()).thenReturn("CHANGED");
    when(published.readInOwnedSnapshot(any())).thenReturn(Optional.of(source));
    try (var ignored = attach(peer("entity-management-service", NAMESPACE))) {
      assertThatThrownBy(() -> owner().read(wire))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure ->
                  assertThat(
                          ((WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure)
                              .code())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
    }
    verify(published).readInOwnedSnapshot(any());
  }

  @Test
  void projectsOriginalIntentFieldsAndExplicitFamilyCountsInRevisionOrder() {
    UUID spawnRevision = uuid(20);
    UUID generationRevision = uuid(21);
    UUID secondSpawnRevision = uuid(22);
    UUID spawnId = uuid(30);
    UUID ruleId = uuid(31);
    UUID secondSpawnId = uuid(32);
    UUID roomId = uuid(40);
    UUID entityId = uuid(41);
    UUID secondRoomId = uuid(42);
    UUID secondEntityId = uuid(43);
    var spawnMutation =
        base(
                spawnRevision,
                spawnId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                uuid(50))
            .setWorldEntitySpawnBinding(
                WorldEntitySpawnBindingDesignMutation.newBuilder()
                    .setRoomId(roomId.toString())
                    .setEntityTemplateType(
                        EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                    .setEntityTemplateId(entityId.toString())
                    .setSpawnCount(0)
                    .setRespawnDelaySeconds(77))
            .build();
    var generationMutation =
        base(
                generationRevision,
                ruleId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE,
                uuid(51))
            .setGenerationRule(
                GenerationRuleDesignMutation.newBuilder()
                    .setName("authored-name")
                    .setValue("unchanged-value"))
            .build();
    var secondSpawnMutation =
        base(
                secondSpawnRevision,
                secondSpawnId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE,
                uuid(52))
            .setWorldEntitySpawnBinding(
                WorldEntitySpawnBindingDesignMutation.newBuilder()
                    .setRoomId(secondRoomId.toString())
                    .setEntityTemplateType(
                        EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM)
                    .setEntityTemplateId(secondEntityId.toString())
                    .setSpawnCount(4)
                    .setRespawnDelaySeconds(9))
            .build();
    var entityRef =
        new WorldDraftTopologyInputGraph.EntityTemplateReference(
            EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC,
            TENANT,
            VERSION,
            entityId);
    var spawn =
        new SpawnBindingIntent(
            source(Family.WORLD_ENTITY_SPAWN_BINDING, spawnId, spawnMutation),
            new Template(Family.ROOM, roomId),
            new WorldDraftTopologyInputGraph.EntityTemplateReference(
                entityRef.kind(),
                entityRef.tenantId(),
                entityRef.versionId(),
                entityRef.templateId()),
            spawnMutation.getWorldEntitySpawnBinding());
    var generation =
        new GenerationRuleIntent(
            source(Family.GENERATION_RULE, ruleId, generationMutation),
            new Template(Family.ZONE, uuid(51)),
            generationMutation.getGenerationRule());
    var secondSpawn =
        new SpawnBindingIntent(
            source(Family.WORLD_ENTITY_SPAWN_BINDING, secondSpawnId, secondSpawnMutation),
            new Template(Family.ROOM, secondRoomId),
            new WorldDraftTopologyInputGraph.EntityTemplateReference(
                EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM,
                TENANT,
                VERSION,
                secondEntityId),
            secondSpawnMutation.getWorldEntitySpawnBinding());
    var topology = mock(WorldCanonicalInstanceTopologyPlan.class);
    when(topology.spawnBindings()).thenReturn(List.of(spawn, secondSpawn));
    when(topology.generationRules()).thenReturn(List.of(generation));
    when(topology.tenantId()).thenReturn(TENANT);
    when(topology.versionId()).thenReturn(VERSION);
    var declaration =
        new FreshGraphDeclaration(
            TENANT,
            VERSION,
            new RoomTemplateRef(TENANT, VERSION, roomId),
            List.of(
                new WorldDraftTopologyInputGraph.FamilyCount(
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION, 0),
                new WorldDraftTopologyInputGraph.FamilyCount(
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE, 0),
                new WorldDraftTopologyInputGraph.FamilyCount(
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM, 0),
                new WorldDraftTopologyInputGraph.FamilyCount(
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT, 0),
                new WorldDraftTopologyInputGraph.FamilyCount(
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE, 1),
                new WorldDraftTopologyInputGraph.FamilyCount(
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING,
                    2)));
    var graph =
        new WorldDraftTopologyInputGraph(
            TENANT,
            VERSION,
            List.of(
                new Node("0", spawnRevision, spawnId, uuid(50), spawnMutation, entityRef),
                new Node("1", generationRevision, ruleId, uuid(51), generationMutation, null),
                new Node(
                    "2",
                    secondSpawnRevision,
                    secondSpawnId,
                    uuid(52),
                    secondSpawnMutation,
                    new WorldDraftTopologyInputGraph.EntityTemplateReference(
                        EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM,
                        TENANT,
                        VERSION,
                        secondEntityId))),
            declaration);

    var projection = WorldPublishedSpawnRequirementsReadOwner.projectRequirements(graph, topology);

    assertThat(projection.familyCounts())
        .extracting(FamilyCount::count)
        .containsExactly(0, 0, 0, 0, 1, 2);
    assertThat(projection.spawnRequirements())
        .containsExactly(
            new SpawnRequirement(
                "0",
                spawnRevision,
                spawnId,
                new RoomTemplateRef(TENANT, VERSION, roomId),
                new EntityTemplateReference(
                    EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC,
                    TENANT,
                    VERSION,
                    entityId),
                0,
                77),
            new SpawnRequirement(
                "2",
                secondSpawnRevision,
                secondSpawnId,
                new RoomTemplateRef(TENANT, VERSION, secondRoomId),
                new EntityTemplateReference(
                    EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM,
                    TENANT,
                    VERSION,
                    secondEntityId),
                4,
                9));
    assertThat(projection.generationRequirements())
        .containsExactly(
            new GenerationRequirement(
                "1",
                generationRevision,
                ruleId,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
                uuid(51),
                "authored-name",
                "unchanged-value"));
  }

  private WorldPublishedSpawnRequirementsReadOwner owner() {
    return new WorldPublishedSpawnRequirementsReadOwner(
        NAMESPACE, gameDesign, published, transactions);
  }

  private static ReadWorldPublishedSpawnRequirementsRequest wireRequest(String namespace) {
    var binding =
        GetLaunchDescriptorRequest.newBuilder()
            .setRequestId(READ_ID.toString())
            .setCanonicalTenantId(TENANT.toString())
            .setWorldSlug("world")
            .setControlPlaneRequestId("control-plane-request")
            .setExpectedRequestDigest("sha256:" + "a".repeat(64))
            .setExpectedResultDigest("sha256:" + "b".repeat(64))
            .build();
    return ReadWorldPublishedSpawnRequirementsRequest.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(namespace)
        .setReadRequestId(READ_ID.toString())
        .setLaunchBindingRequest(binding)
        .setExpectedReleaseAttestationDigest("sha256:" + "c".repeat(64))
        .build();
  }

  private static WorldPublishedStartLocationEvidence.Request selectorRequest() {
    return new WorldPublishedStartLocationEvidence.Request(
        NAMESPACE,
        TENANT,
        VERSION,
        uuid(4),
        uuid(5),
        "publication",
        "d".repeat(64),
        6,
        "workflow",
        uuid(6).toString(),
        "e".repeat(64),
        4,
        List.of());
  }

  private static WorldDesignMutationRevision.Builder base(
      UUID revision,
      UUID aggregateId,
      WorldDesignAggregateType aggregate,
      WorldDesignScopeType scopeType,
      UUID scopeId) {
    return WorldDesignMutationRevision.newBuilder()
        .setLogicalRevisionId(revision.toString())
        .setCommitId(uuid(7).toString())
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setAggregateType(aggregate)
        .setAggregateId(aggregateId.toString())
        .setScopeType(scopeType)
        .setScopeId(scopeId.toString());
  }

  private static SourceRow source(Family family, UUID id, WorldDesignMutationRevision revision) {
    return new SourceRow(new Template(family, id), revision, revision, "original-payload");
  }

  private static UUID uuid(int value) {
    return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(value));
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static ContextScope attach(GrpcPeerIdentity peer) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    return new ContextScope(context.attach());
  }

  private record ContextScope(Context previous) implements AutoCloseable {
    @Override
    public void close() {
      Context.current().detach(previous);
    }
  }
}
