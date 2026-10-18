package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.EntityTemplateReference;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.FamilyCount;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.GenerationRequirement;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.SpawnRequirement;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Row;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Template;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
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

  @ParameterizedTest
  @EnumSource(Status.Code.class)
  void gameDesignStatusCodesAreMappedAndSanitizedBeforeWorldRead(Status.Code foreignCode) {
    var wire = wireRequest(NAMESPACE);
    Metadata foreignTrailers = new Metadata();
    foreignTrailers.put(
        Metadata.Key.of("x-private-detail", Metadata.ASCII_STRING_MARSHALLER), "secret");
    StatusRuntimeException foreignFailure =
        Status.fromCode(foreignCode)
            .withDescription("foreign private description")
            .withCause(new IllegalStateException("private cause"))
            .asRuntimeException(foreignTrailers);
    when(gameDesign.getComplete(any(GetLaunchDescriptorRequest.class))).thenThrow(foreignFailure);
    var owner = owner();

    try (var ignored = attach(peer("entity-management-service", NAMESPACE))) {
      assertThatThrownBy(() -> owner.read(wire))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure -> {
                var rejected =
                    (WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure;
                Status.Code expected =
                    switch (foreignCode) {
                      case UNAVAILABLE,
                          DEADLINE_EXCEEDED,
                          CANCELLED,
                          RESOURCE_EXHAUSTED,
                          INTERNAL,
                          UNKNOWN ->
                          foreignCode;
                      default -> Status.Code.FAILED_PRECONDITION;
                    };
                assertThat(rejected.code()).isEqualTo(expected);
                assertThat(rejected.getMessage())
                    .isEqualTo("Authenticated Game Design release read could not complete")
                    .doesNotContain("foreign", "private", "secret");
                assertThat(rejected.getCause()).isNull();
              });
    }

    verify(gameDesign).getComplete(any(GetLaunchDescriptorRequest.class));
    verifyNoInteractions(published, transactions);
  }

  @ParameterizedTest
  @EnumSource(InvalidCarrierFailure.class)
  void invalidOrUnexpectedGameDesignRuntimeFailureIsSanitizedInternal(
      InvalidCarrierFailure failureKind) {
    RuntimeException foreignFailure =
        switch (failureKind) {
          case ILLEGAL_ARGUMENT -> new IllegalArgumentException("private carrier detail");
          case ILLEGAL_STATE -> new IllegalStateException("private carrier detail");
          case UNEXPECTED -> new UnsupportedOperationException("private runtime detail");
        };
    when(gameDesign.getComplete(any(GetLaunchDescriptorRequest.class))).thenThrow(foreignFailure);

    try (var ignored = attach(peer("entity-management-service", NAMESPACE))) {
      assertThatThrownBy(() -> owner().read(wireRequest(NAMESPACE)))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure -> {
                var rejected =
                    (WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure;
                assertThat(rejected.code()).isEqualTo(Status.Code.INTERNAL);
                assertThat(rejected.getMessage())
                    .isEqualTo(
                        failureKind == InvalidCarrierFailure.UNEXPECTED
                            ? "Authenticated Game Design release read failed"
                            : "Authenticated Game Design release evidence is invalid");
                assertThat(rejected.getCause()).isNull();
              });
    }

    verify(gameDesign).getComplete(any(GetLaunchDescriptorRequest.class));
    verifyNoInteractions(published, transactions);
  }

  @Test
  void readsRequirementsFromTheExactReleaseAndOwnedFrozenSource() throws Exception {
    var fixture = publishedReadFixture();
    when(gameDesign.getComplete(eq(fixture.request().getLaunchBindingRequest())))
        .thenReturn(fixture.launchBinding());
    when(published.readInOwnedSnapshot(eq(fixture.readCaptureRequest())))
        .thenReturn(Optional.of(fixture.source()));

    WorldPublishedSpawnRequirementsEvidence evidence;
    try (var ignored = attach(peer("entity-management-service", fixture.namespace()))) {
      evidence = owner(fixture.namespace()).read(fixture.request());
    }

    assertThat(evidence.request().targetNamespace()).isEqualTo(fixture.namespace());
    assertThat(evidence.launchBinding()).isEqualTo(fixture.launchBinding());
    assertThat(evidence.captureId()).isEqualTo(fixture.source().frozenTopology().captureId());
    assertThat(evidence.graphDigest())
        .isEqualTo(
            WorldDraftGraphAppliedResult.digest(fixture.source().frozenTopology().graphBytes()));
    assertThat(evidence.familyCounts())
        .extracting(FamilyCount::count)
        .containsExactly(1, 1, 2, 1, 1, 1);
    assertThat(evidence.spawnRequirements())
        .singleElement()
        .satisfies(
            spawn -> {
              assertThat(spawn.roomTemplate())
                  .isEqualTo(
                      fixture.plan().graph().freshGraphDeclaration().orElseThrow().startLocation());
              assertThat(spawn.entityTemplate().kind())
                  .isEqualTo(EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC);
              assertThat(spawn.spawnCount()).isEqualTo(3);
              assertThat(spawn.respawnDelaySeconds()).isEqualTo(90);
            });
    assertThat(evidence.generationRequirements())
        .singleElement()
        .satisfies(
            generation -> {
              assertThat(generation.name()).isEqualTo("density");
              assertThat(generation.value()).isEqualTo("sparse");
            });

    verify(gameDesign).getComplete(eq(fixture.request().getLaunchBindingRequest()));
    verify(published).readInOwnedSnapshot(eq(fixture.readCaptureRequest()));
  }

  @ParameterizedTest
  @EnumSource(ReleaseSourceMismatch.class)
  void releaseAndFrozenSourceMismatchesFailAfterTheOwnedRead(ReleaseSourceMismatch mismatch)
      throws Exception {
    var fixture = mismatchedPublishedReadFixture(mismatch);
    when(gameDesign.getComplete(eq(fixture.request().getLaunchBindingRequest())))
        .thenReturn(fixture.launchBinding());
    when(published.readInOwnedSnapshot(eq(fixture.readCaptureRequest())))
        .thenReturn(Optional.of(fixture.source()));

    try (var ignored = attach(peer("entity-management-service", fixture.namespace()))) {
      assertThatThrownBy(() -> owner(fixture.namespace()).read(fixture.request()))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure -> {
                var rejected =
                    (WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure;
                assertThat(rejected.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
                assertThat(rejected.getMessage())
                    .isEqualTo("Frozen World source differs from authenticated release evidence")
                    .doesNotContain("private", "SQL", "receipt");
                assertThat(rejected.getCause()).isNull();
              });
    }

    verify(gameDesign).getComplete(eq(fixture.request().getLaunchBindingRequest()));
    verify(published).readInOwnedSnapshot(eq(fixture.readCaptureRequest()));
  }

  private enum ReleaseSourceMismatch {
    CONTENT_DIGEST,
    APPLIED_COMMIT,
    WORLD_PARTICIPANT_SCOPE,
    WORLD_PARTICIPANT_DIGEST_SCHEMA,
    SELECTOR_RECEIPT_BINDING_DIGEST,
    SELECTED_ROOM_COUNT
  }

  private enum InvalidCarrierFailure {
    ILLEGAL_ARGUMENT,
    ILLEGAL_STATE,
    UNEXPECTED
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
  void unselectedLegacyReleaseCannotBeUsedAsPublishedSpawnSelector() {
    var wire = wireRequest(NAMESPACE);
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
        .thenReturn(AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION);
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

  @ParameterizedTest
  @EnumSource(SnapshotFailure.class)
  void snapshotFailuresMapOnlyRecoverableDataAccessFailuresToUnavailable(
      SnapshotFailure failureKind) {
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
    when(published.readInOwnedSnapshot(any())).thenThrow(snapshotFailure(failureKind));

    try (var ignored = attach(peer("entity-management-service", NAMESPACE))) {
      assertThatThrownBy(() -> owner().read(wire))
          .isInstanceOf(WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException.class)
          .satisfies(
              failure -> {
                var rejected =
                    (WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException) failure;
                var expectedCode =
                    switch (failureKind) {
                      case TRANSIENT, RECOVERABLE -> Status.Code.UNAVAILABLE;
                      case BAD_SQL, INTEGRITY, UNRELATED_RUNTIME -> Status.Code.INTERNAL;
                    };
                assertThat(rejected.code()).isEqualTo(expectedCode);
                assertThat(rejected.getMessage())
                    .isEqualTo(
                        expectedCode == Status.Code.UNAVAILABLE
                            ? "World published source is temporarily unavailable"
                            : "World published source read failed")
                    .doesNotContain("private", "SQL", "integrity");
                assertThat(rejected.getCause()).isNull();
              });
    }

    verify(gameDesign).getComplete(any(GetLaunchDescriptorRequest.class));
    verify(published).readInOwnedSnapshot(any());
  }

  private enum SnapshotFailure {
    TRANSIENT,
    RECOVERABLE,
    BAD_SQL,
    INTEGRITY,
    UNRELATED_RUNTIME
  }

  private static RuntimeException snapshotFailure(SnapshotFailure failureKind) {
    return switch (failureKind) {
      case TRANSIENT -> new TransientDataAccessResourceException("private transient detail");
      case RECOVERABLE -> new RecoverableDataAccessException("private recoverable detail");
      case BAD_SQL ->
          new BadSqlGrammarException("snapshot", "select private", new SQLException("private SQL"));
      case INTEGRITY -> new DataIntegrityViolationException("private integrity detail");
      case UNRELATED_RUNTIME -> new IllegalStateException("private runtime detail");
    };
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
  void projectsRetainedSpawnAndGenerationFieldsAndFamilyCountsInRevisionOrder() {
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

    List<WorldCanonicalAuthoredGraph.Row> frozenRows = new java.util.ArrayList<>();
    long privateKey = 1L;
    for (Node node : graph.nodes()) {
      var family =
          switch (node.mutation().getAggregateType()) {
            case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING ->
                Family.WORLD_ENTITY_SPAWN_BINDING;
            case WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE -> Family.GENERATION_RULE;
            default -> throw new IllegalArgumentException("Unexpected test family");
          };
      var retainedContent = node.mutation();
      if (node.revisionId().equals(spawnRevision)) {
        retainedContent =
            retainedContent.toBuilder()
                .setWorldEntitySpawnBinding(
                    retainedContent.getWorldEntitySpawnBinding().toBuilder().setSpawnCount(1))
                .build();
      }
      frozenRows.add(
          new WorldCanonicalAuthoredGraph.Row(
              new Template(family, node.templateId()),
              privateKey,
              privateKey,
              node,
              retainedContent));
      privateKey++;
    }
    var frozenGraph = new WorldCanonicalAuthoredGraph(TENANT, VERSION, 1L, 1L, frozenRows);
    var projection =
        WorldPublishedSpawnRequirementsReadOwner.projectRequirements(graph, frozenGraph);

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
                1,
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

  private static PublishedReadFixture publishedReadFixture() throws Exception {
    var plan =
        includeControlPlaneOwner(WorldDraftTopologyCommitPlanTest.selectedPlanWithAllFamilies());
    var capture = captureRequest(plan, 4, plan.binding().commitId().toString(), "e".repeat(64));
    var applied = appliedSelection(plan, capture);
    var frozen = frozenTopology(plan, capture, applied, uuid(800));
    var source =
        new WorldPublishedStartLocationSource(
            frozen,
            applied.appliedResult(),
            applied.appliedResult().startLocationReceipt().orElseThrow());
    var launchBinding = launchBinding(capture, applied.selectorEvidence(), 901, 3);
    return new PublishedReadFixture(
        plan,
        capture,
        source,
        launchBinding,
        wireRequest(launchBinding),
        applied.selectorEvidence());
  }

  private static PublishedReadFixture mismatchedPublishedReadFixture(ReleaseSourceMismatch mismatch)
      throws Exception {
    var baseline = publishedReadFixture();
    return switch (mismatch) {
      case CONTENT_DIGEST -> {
        var request = copyCaptureRequest(baseline.readCaptureRequest(), 4, null, "f".repeat(64));
        var selector = selectorEvidence(baseline.source().appliedResult(), request);
        var binding = launchBinding(request, selector, 901, 3);
        yield new PublishedReadFixture(
            baseline.plan(), request, baseline.source(), binding, wireRequest(binding), selector);
      }
      case APPLIED_COMMIT -> {
        UUID changedCommit = uuid(801);
        var plan = rebindPlan(baseline.plan(), changedCommit, false);
        var request =
            copyCaptureRequest(baseline.readCaptureRequest(), 4, changedCommit.toString(), null);
        var applied = appliedSelection(plan, request);
        var binding = launchBinding(request, applied.selectorEvidence(), 901, 3);
        yield new PublishedReadFixture(
            baseline.plan(),
            request,
            baseline.source(),
            binding,
            wireRequest(binding),
            applied.selectorEvidence());
      }
      case WORLD_PARTICIPANT_SCOPE -> {
        var binding =
            launchBinding(baseline.readCaptureRequest(), baseline.releaseSelector(), 902, 3);
        yield new PublishedReadFixture(
            baseline.plan(),
            baseline.readCaptureRequest(),
            baseline.source(),
            binding,
            wireRequest(binding),
            baseline.releaseSelector());
      }
      case WORLD_PARTICIPANT_DIGEST_SCHEMA -> {
        var request = copyCaptureRequest(baseline.readCaptureRequest(), 3, null, null);
        var selector = selectorEvidence(baseline.source().appliedResult(), request);
        var binding =
            launchBinding(
                request,
                selector,
                901,
                AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
        yield new PublishedReadFixture(
            baseline.plan(), request, baseline.source(), binding, wireRequest(binding), selector);
      }
      case SELECTOR_RECEIPT_BINDING_DIGEST -> {
        var plan = rebindPlan(baseline.plan(), null, true);
        var applied = appliedSelection(plan, baseline.readCaptureRequest());
        var binding =
            launchBinding(baseline.readCaptureRequest(), applied.selectorEvidence(), 901, 3);
        yield new PublishedReadFixture(
            baseline.plan(),
            baseline.readCaptureRequest(),
            baseline.source(),
            binding,
            wireRequest(binding),
            applied.selectorEvidence());
      }
      case SELECTED_ROOM_COUNT -> {
        var source = withDuplicateSelectedRoom(baseline.source());
        yield new PublishedReadFixture(
            baseline.plan(),
            baseline.readCaptureRequest(),
            source,
            baseline.launchBinding(),
            baseline.request(),
            baseline.releaseSelector());
      }
    };
  }

  private static WorldDraftTopologyCommitPlan includeControlPlaneOwner(
      WorldDraftTopologyCommitPlan source) {
    var binding = source.binding();
    List<RevisionPayload> revisions = new ArrayList<>(binding.revisions());
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            uuid(802),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "complete-release-owner-binding"));
    List<AffectedUnit> affectedUnits = new ArrayList<>(binding.affectedUnits());
    affectedUnits.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "RELEASE",
            "selected-release",
            "VERSION",
            Long.toString(binding.target().gameDesignVersionRowId()),
            "0"));
    var completeBinding =
        DraftCommitBinding.create(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            binding.baseCommitId(),
            revisions,
            affectedUnits);
    return WorldDraftTopologyCommitPlan.create(completeBinding, source.ownerBinding());
  }

  private static CaptureRequest captureRequest(
      WorldDraftTopologyCommitPlan plan,
      int digestSchemaVersion,
      String appliedCommitId,
      String contentDigest) {
    var owner = plan.ownerBinding();
    return new CaptureRequest(
        owner.targetNamespace(),
        owner.canonicalTenantId(),
        owner.canonicalVersionId(),
        owner.intakeRequestId(),
        uuid(803),
        "published-spawn-read-publication",
        "a".repeat(64),
        6,
        PublicationDigestRequestBinding.full(
                owner.canonicalTenantId().toString(),
                Long.toString(owner.gameDesignVersionId()),
                "published-spawn-read-publication")
            .derivedWorkflowIdentity(),
        appliedCommitId,
        contentDigest,
        digestSchemaVersion,
        plan.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList());
  }

  private static CaptureRequest copyCaptureRequest(
      CaptureRequest source,
      int digestSchemaVersion,
      String appliedCommitId,
      String contentDigest) {
    return new CaptureRequest(
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.canonicalVersionId(),
        source.intakeRequestId(),
        source.publicationFence(),
        source.publicationRequestId(),
        source.requestDigest(),
        source.versionStateEpoch(),
        source.publishWorkflowId(),
        appliedCommitId == null ? source.appliedCommitId() : appliedCommitId,
        contentDigest == null ? source.contentDigest() : contentDigest,
        digestSchemaVersion,
        source.suppliedOwnedAffectedTuples());
  }

  private static AppliedSelection appliedSelection(
      WorldDraftTopologyCommitPlan plan, CaptureRequest captureRequest) throws Exception {
    byte[] graphBytes = canonicalGraphBytes(plan);
    var graph = new WorldCanonicalAuthoredGraphReader().read(plan, graphBytes);
    var binding = plan.binding();
    var accountBinding =
        new DraftAuthorizationFenceBinding(
                uuid(804),
                binding.requestId(),
                binding.commitId(),
                uuid(805),
                uuid(806),
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.baseCommitId(),
                "0",
                binding.canonicalBytes(),
                binding.canonicalBytes(),
                binding.digest(),
                List.of(
                    new DraftAuthorizationFenceBinding.SourceEvidence(
                        DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                        uuid(807).toString(),
                        null,
                        "1",
                        null,
                        null,
                        new byte[] {1})))
            .withRequiredOwners();
    var operation =
        new WorldDraftTerminalOperation(
            accountBinding.operationId(),
            accountBinding.requestId(),
            accountBinding.commitId(),
            accountBinding.fenceId(),
            accountBinding.tenantId(),
            accountBinding.versionId(),
            binding,
            plan.ownerBinding(),
            accountBinding.canonicalBytes());
    var appliedResult =
        WorldDraftGraphAppliedResult.create(
            new WorldDraftGraphApplication(operation, plan), graphBytes);
    var selectorEvidence = selectorEvidence(appliedResult, captureRequest);
    return new AppliedSelection(graphBytes, graph, appliedResult, selectorEvidence);
  }

  private static WorldPublishedStartLocationEvidence selectorEvidence(
      WorldDraftGraphAppliedResult appliedResult, CaptureRequest captureRequest) {
    var receipt = appliedResult.startLocationReceipt().orElseThrow();
    return new WorldPublishedStartLocationEvidence(
        selectorRequest(captureRequest),
        receipt.canonicalBytes(),
        appliedResult.application().operation().accountBindingBytes(),
        appliedResult.canonicalBytes());
  }

  private static WorldPublishedStartLocationEvidence.Request selectorRequest(
      CaptureRequest captureRequest) {
    return new WorldPublishedStartLocationEvidence.Request(
        captureRequest.targetNamespace(),
        captureRequest.canonicalTenantId(),
        captureRequest.canonicalVersionId(),
        captureRequest.intakeRequestId(),
        captureRequest.publicationFence(),
        captureRequest.publicationRequestId(),
        captureRequest.requestDigest(),
        captureRequest.versionStateEpoch(),
        captureRequest.publishWorkflowId(),
        captureRequest.appliedCommitId(),
        captureRequest.contentDigest(),
        captureRequest.digestSchemaVersion(),
        captureRequest.suppliedOwnedAffectedTuples().stream()
            .map(
                tuple ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        tuple.owner(),
                        tuple.aggregateType(),
                        tuple.aggregateId(),
                        tuple.scopeType(),
                        tuple.scopeId(),
                        tuple.expectedEpoch()))
            .toList());
  }

  private static WorldCanonicalFrozenTopology frozenTopology(
      WorldDraftTopologyCommitPlan plan,
      CaptureRequest captureRequest,
      AppliedSelection applied,
      UUID captureId) {
    return new WorldCanonicalFrozenTopology(
        captureId,
        new WorldCanonicalFrozenTopology.Request(plan, captureRequest),
        mock(WorldAuthoredVersionIdentityReceipt.class),
        applied.graph(),
        applied.graphBytes(),
        applied.appliedResult().canonicalBytes(),
        applied.appliedResult().canonicalBytes());
  }

  private static CompleteLaunchBindingEvidence launchBinding(
      CaptureRequest captureRequest,
      WorldPublishedStartLocationEvidence selectorEvidence,
      int descriptorVersionId,
      int attestationSchemaVersion) {
    String namespace = captureRequest.targetNamespace();
    UUID sourceOperationId = uuid(808);
    String sourceEvidenceDigest = "sha256:" + "b".repeat(64);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            namespace,
            "control-plane-request",
            captureRequest.canonicalTenantId(),
            "world",
            sourceOperationId,
            sourceEvidenceDigest,
            77,
            false,
            null,
            false,
            null,
            true,
            (long) descriptorVersionId,
            false,
            null);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "launch-descriptor",
            descriptorVersionId,
            false,
            null,
            "{}",
            "generation-config-v1",
            captureRequest.versionStateEpoch(),
            8,
            "published-release-bundle",
            false,
            null);
    String commitId = selectorEvidence.request().appliedCommitId();
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                participant ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant,
                        Integer.toString(descriptorVersionId),
                        false,
                        null,
                        commitId,
                        "WORLD_MANAGEMENT".equals(participant)
                            ? selectorEvidence.request().contentDigest()
                            : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participant, attestationSchemaVersion),
                        "GAME_LOGIC".equals(participant),
                        "GAME_LOGIC".equals(participant) ? "sha256:" + "d".repeat(64) : null))
            .toList();
    var release =
        attestationSchemaVersion == AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION
            ? AuthoredWorldReleaseAttestationEvidence.create(
                namespace,
                descriptor.resultDigest(),
                captureRequest.canonicalTenantId(),
                captureRequest.canonicalVersionId(),
                descriptor.worldSlug(),
                descriptor.authoredWorldSourceOperationId(),
                descriptor.authoredWorldSourceEvidenceDigest(),
                descriptor.launchDescriptorId(),
                descriptor.publishedReleaseBundleRef(),
                descriptor.versionStateEpoch(),
                captureRequest.publishWorkflowId(),
                commitId,
                participants,
                "sha256:" + "e".repeat(64),
                1,
                List.of(),
                List.of(),
                List.of(),
                descriptor.generationConfigRevision(),
                selectorEvidence)
            : AuthoredWorldReleaseAttestationEvidence.createClosureSelector(
                namespace,
                descriptor.resultDigest(),
                captureRequest.canonicalTenantId(),
                captureRequest.canonicalVersionId(),
                descriptor.worldSlug(),
                descriptor.authoredWorldSourceOperationId(),
                descriptor.authoredWorldSourceEvidenceDigest(),
                descriptor.launchDescriptorId(),
                descriptor.publishedReleaseBundleRef(),
                descriptor.versionStateEpoch(),
                captureRequest.publishWorkflowId(),
                commitId,
                participants,
                "sha256:" + "e".repeat(64),
                1,
                List.of(),
                List.of(),
                List.of(),
                descriptor.generationConfigRevision(),
                selectorEvidence);
    return new CompleteLaunchBindingEvidence(descriptor, release);
  }

  private static ReadWorldPublishedSpawnRequirementsRequest wireRequest(
      CompleteLaunchBindingEvidence launchBinding) {
    var descriptor = launchBinding.descriptor();
    var bindingRequest =
        GetLaunchDescriptorRequest.newBuilder()
            .setRequestId(READ_ID.toString())
            .setCanonicalTenantId(descriptor.canonicalTenantId().toString())
            .setWorldSlug(descriptor.worldSlug())
            .setControlPlaneRequestId(descriptor.controlPlaneRequestId())
            .setExpectedRequestDigest(descriptor.requestDigest())
            .setExpectedResultDigest(descriptor.resultDigest())
            .build();
    return ReadWorldPublishedSpawnRequirementsRequest.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(descriptor.targetNamespace())
        .setReadRequestId(READ_ID.toString())
        .setLaunchBindingRequest(bindingRequest)
        .setExpectedReleaseAttestationDigest(launchBinding.releaseAttestation().evidenceDigest())
        .build();
  }

  private static byte[] canonicalGraphBytes(WorldDraftTopologyCommitPlan plan) throws Exception {
    Map<String, Long> privateKeys = new LinkedHashMap<>();
    long privateKey = 100;
    for (Node node : plan.graph().nodes()) {
      privateKeys.put(
          WorldDraftTopologyCommitRepository.family(node) + ":" + node.templateId(), privateKey++);
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    long mappingId = 1;
    for (Node node : plan.graph().nodes()) {
      String family = WorldDraftTopologyCommitRepository.family(node);
      Map<String, Object> mapping = new LinkedHashMap<>();
      mapping.put("id", mappingId++);
      mapping.put("target_namespace", plan.ownerBinding().targetNamespace());
      mapping.put("canonical_tenant_id", plan.graph().tenantId().toString());
      mapping.put("canonical_version_id", plan.graph().versionId().toString());
      mapping.put("family", family);
      mapping.put("template_id", node.templateId().toString());
      mapping.put("private_row_key", privateKeys.get(family + ":" + node.templateId()));
      mapping.put("tenant_id", 17);
      mapping.put("version_id", 29);
      mapping.put(
          "version_identity_operation_id",
          plan.ownerBinding().versionIdentityOperationId().toString());
      mapping.put("request_id", plan.binding().requestId().toString());
      mapping.put("commit_id", plan.binding().commitId().toString());
      mapping.put("revision_id", node.revisionId().toString());
      mapping.put("revision_order", node.revisionOrder());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("mapping", mapping);
      row.put(
          "content", WorldDraftTopologyCommitRepository.expectedContent(node, privateKeys, 17, 29));
      rows.add(row);
    }

    Map<String, Object> graph = new LinkedHashMap<>();
    graph.put("schemaVersion", "3");
    graph.put("canonicalTenantId", plan.graph().tenantId().toString());
    graph.put("canonicalVersionId", plan.graph().versionId().toString());
    graph.put(
        "inboundSourceClosure",
        plan.graph()
            .freshGraphDeclaration()
            .orElseThrow()
            .inboundSourceClosure()
            .orElseThrow()
            .content());
    graph.put("rows", rows);
    return new tools.jackson.databind.ObjectMapper().writeValueAsBytes(graph);
  }

  private static WorldDraftTopologyCommitPlan rebindPlan(
      WorldDraftTopologyCommitPlan source, UUID commitId, boolean changeGenerationInput) {
    var binding = source.binding();
    List<RevisionPayload> revisions = new ArrayList<>();
    for (RevisionPayload revision : binding.revisions()) {
      if (revision.owner() != Owner.WORLD_MANAGEMENT) {
        revisions.add(revision);
        continue;
      }
      var mutation = WorldDesignMutationRevision.newBuilder();
      try {
        JsonFormat.parser().merge(revision.payload(), mutation);
      } catch (InvalidProtocolBufferException invalid) {
        throw new IllegalStateException("Test World revision could not be decoded", invalid);
      }
      if (commitId != null) {
        mutation.setCommitId(commitId.toString());
      }
      if (changeGenerationInput
          && mutation.getAggregateType()
              == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE) {
        mutation.setGenerationRule(
            mutation.getGenerationRule().toBuilder().setValue("changed-generation-input"));
      }
      try {
        revisions.add(
            new RevisionPayload(
                revision.revisionOrder(),
                revision.revisionId(),
                revision.owner(),
                JsonFormat.printer().print(mutation.build())));
      } catch (InvalidProtocolBufferException invalid) {
        throw new IllegalStateException("Test World revision could not be encoded", invalid);
      }
    }
    var changedBinding =
        DraftCommitBinding.create(
            binding.target(),
            binding.requestId(),
            commitId == null ? binding.commitId() : commitId,
            binding.baseCommitId(),
            revisions,
            binding.affectedUnits());
    return WorldDraftTopologyCommitPlan.create(changedBinding, source.ownerBinding());
  }

  private static WorldPublishedStartLocationSource withDuplicateSelectedRoom(
      WorldPublishedStartLocationSource source) {
    var frozen = source.frozenTopology();
    UUID selectedRoom = source.selectorReceipt().startLocation().roomTemplateId();
    var rows = new ArrayList<>(frozen.graph().rows());
    Row selectedRow =
        frozen.graph().family(Family.ROOM).stream()
            .filter(row -> row.template().templateId().equals(selectedRoom))
            .findFirst()
            .orElseThrow();
    rows.add(selectedRow);
    var duplicateRoomGraph =
        new WorldCanonicalAuthoredGraph(
            frozen.graph().tenantId(),
            frozen.graph().versionId(),
            frozen.graph().localTenantKey(),
            frozen.graph().localVersionKey(),
            rows,
            frozen.graph().inboundSourceClosure());
    var duplicateRoomFrozen =
        new WorldCanonicalFrozenTopology(
            frozen.captureId(),
            frozen.request(),
            frozen.sourceIdentity(),
            duplicateRoomGraph,
            frozen.graphBytes(),
            frozen.storageResultBytes(),
            frozen.resultBytes());
    return new WorldPublishedStartLocationSource(
        duplicateRoomFrozen, source.appliedResult(), source.selectorReceipt());
  }

  private record AppliedSelection(
      byte[] graphBytes,
      WorldCanonicalAuthoredGraph graph,
      WorldDraftGraphAppliedResult appliedResult,
      WorldPublishedStartLocationEvidence selectorEvidence) {}

  private record PublishedReadFixture(
      WorldDraftTopologyCommitPlan plan,
      CaptureRequest readCaptureRequest,
      WorldPublishedStartLocationSource source,
      CompleteLaunchBindingEvidence launchBinding,
      ReadWorldPublishedSpawnRequirementsRequest request,
      WorldPublishedStartLocationEvidence releaseSelector) {
    String namespace() {
      return launchBinding.descriptor().targetNamespace();
    }
  }

  private WorldPublishedSpawnRequirementsReadOwner owner() {
    return new WorldPublishedSpawnRequirementsReadOwner(
        NAMESPACE, gameDesign, published, transactions);
  }

  private WorldPublishedSpawnRequirementsReadOwner owner(String namespace) {
    return new WorldPublishedSpawnRequirementsReadOwner(
        namespace, gameDesign, published, transactions);
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
