package net.firedevops.firemud.gamesession.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadClient;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PlayerCreationDescriptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreResponse;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.Candidate;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetRequest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled composition proof. Owner repositories, cryptographic clients and source codecs are
 * doubles here; this does not qualify genuine issuance, publication, lifecycle or deployed custody.
 */
class GameSessionCharacterCreatePublicSourceServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID REALM = UUID.randomUUID();
  private static final UUID INSTANCE = UUID.randomUUID();
  private static final UUID PLAYABLE = UUID.randomUUID();
  private static final UUID VERSION = UUID.randomUUID();
  private static final UUID DESCRIPTOR = UUID.randomUUID();
  private static final UUID DESCRIPTOR_REVISION = UUID.randomUUID();
  private static final UUID AUTHORED_BINDING = UUID.randomUUID();
  private static final String DESCRIPTOR_JSON =
      "{\"schemaVersion\":1,\"descriptorId\":\""
          + DESCRIPTOR
          + "\",\"descriptorRevisionId\":\""
          + DESCRIPTOR_REVISION
          + "\",\"inputKind\":\"EMPTY_OBJECT\",\"dependencies\":[]}";

  @Test
  void resolvedCreatedSourceBindsActualSelectedTargetDescriptorAndAuthoredIdentity() {
    try (var fixture = new Fixture()) {
      var resolved = fixture.service.resolvePlayerCreatedRosterSource(rosterRequest());
      assertThat(resolved.route()).isSameAs(fixture.route);
      var target = resolved.target();
      assertThat(target.getTenantUuid()).isEqualTo(TENANT.toString());
      assertThat(target.getRealmUuid()).isEqualTo(REALM.toString());
      assertThat(target.getWorldSlug()).isEqualTo("world");
      assertThat(target.getRealmSlug()).isEqualTo("realm");
      assertThat(target.getGameInstanceUuid()).isEqualTo(INSTANCE.toString());
      assertThat(target.getCanonicalVersionUuid()).isEqualTo(VERSION.toString());
      assertThat(target.getPlayableStateNamespaceUuid()).isEqualTo(PLAYABLE.toString());
      assertThat(target.getPlayableStateScope())
          .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
      assertThat(target.getEntryPolicy())
          .isEqualTo(
              CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED);
      assertThat(target.getCatalogRevision()).isEqualTo(1L);
      assertThat(target.getPointerVersion()).isEqualTo(3L);
      assertThat(target.getActiveWorldEpoch()).isEqualTo(7L);
      assertThat(target.getPublishedPolicyDigest()).isEqualTo("c".repeat(64));
      assertThat(target.getPublishedReleaseBundleRef()).isEqualTo("original-release");
      assertThat(target.getAdmissionPointerSnapshotDigest()).isEqualTo("b".repeat(64));
      assertThat(target.getPublishedOwnerProofDigest()).isEqualTo("a".repeat(64));
      var identity = resolved.sourceIdentity();
      assertThat(CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(identity))
          .isSameAs(identity);
      assertThat(identity.getSchemaVersion()).isEqualTo(1);
      assertThat(identity.getDescriptorUuid()).isEqualTo(DESCRIPTOR.toString());
      assertThat(identity.getDescriptorRevisionUuid()).isEqualTo(DESCRIPTOR_REVISION.toString());
      assertThat(identity.getDescriptorDigest())
          .isEqualTo(PlayerCreationDescriptor.parse(DESCRIPTOR_JSON).digest());
      assertThat(identity.getAuthoredBindingUuid()).isEqualTo(AUTHORED_BINDING.toString());
      assertThat(identity.getAuthoredBindingDigest()).isEqualTo("sha256:" + "d".repeat(64));
      assertThat(resolved.response().getRequest()).isEqualTo(rosterRequest());
      assertThat(resolved.response().getAuthoredSource().toByteArray())
          .containsExactly(
              AuthoredInitialActorCoreReadGrpcCodec.toResponse(fixture.authored).toByteArray());
      assertThat(resolved.response().getPublishedPolicySetEvidence().toByteArray())
          .containsExactly(fixture.policySet.canonicalBytes());
      assertThat(fixture.service.readPlayerCreatedRosterSource(rosterRequest()))
          .isEqualTo(resolved.response());
      verify(fixture.gameDesign, org.mockito.Mockito.times(2)).read(any());
      verify(fixture.world, org.mockito.Mockito.times(2)).read(any());
      assertThat(
              GameSessionCharacterCreatePublicSourceService.ResolvedPlayerCreatedRosterSource.class
                  .getDeclaredConstructors())
          .allSatisfy(
              constructor ->
                  assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()))
                      .isTrue());
    }
  }

  @Test
  void resolvedCreatedSourceRejectsMalformedDescriptorBindingAndChangedAuthoredReadback() {
    List<java.util.function.Consumer<Fixture>> malformedSourceIdentities =
        List.of(
            fixture -> when(fixture.projection.descriptor().canonicalJson()).thenReturn("{}"),
            fixture -> when(fixture.projection.bindingDigest()).thenReturn("d".repeat(64)));
    for (var invalidate : malformedSourceIdentities) {
      try (var fixture = new Fixture()) {
        invalidate.accept(fixture);
        assertThatThrownBy(() -> fixture.service.resolvePlayerCreatedRosterSource(rosterRequest()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure -> {
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION);
                  assertThat(Status.fromThrowable(failure).getDescription())
                      .isEqualTo(
                          "Exact original public CHARACTER_CREATE source is unavailable or changed");
                });
      }
    }

    List<java.util.function.Consumer<Fixture>> invalidations =
        List.of(
            fixture -> when(fixture.projection.bindingId()).thenReturn(new UUID(0L, 0L)),
            fixture -> when(fixture.policyEvidence.policyDigest()).thenReturn("c".repeat(64)),
            fixture -> when(fixture.authored.canonicalBytes()).thenReturn(new byte[] {9}),
            fixture -> when(fixture.projection.canonicalTenantId()).thenReturn(UUID.randomUUID()),
            fixture -> when(fixture.projection.canonicalVersionId()).thenReturn(UUID.randomUUID()));
    for (var invalidate : invalidations) {
      try (var fixture = new Fixture()) {
        invalidate.accept(fixture);
        assertThatThrownBy(() -> fixture.service.resolvePlayerCreatedRosterSource(rosterRequest()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure -> {
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION);
                  assertThat(Status.fromThrowable(failure).getDescription())
                      .isEqualTo(
                          "Exact original public CHARACTER_CREATE source is unavailable or changed");
                });
      }
    }
  }

  @Test
  void actualSourceCompositionClassifiesOutboundStatusesAsDependencyUnavailable() {
    for (String boundary : List.of("authored", "world", "policy")) {
      for (var status :
          List.of(
              Status.PERMISSION_DENIED,
              Status.UNAUTHENTICATED,
              Status.INVALID_ARGUMENT,
              Status.DEADLINE_EXCEEDED,
              Status.UNAVAILABLE,
              Status.FAILED_PRECONDITION)) {
        for (boolean rosterRead : List.of(false, true)) {
          if (boundary.equals("policy") && !rosterRead) continue;
          try (var fixture = new Fixture()) {
            var failure =
                status.withDescription("private downstream failure details").asRuntimeException();
            if (boundary.equals("authored")) doThrow(failure).when(fixture.gameDesign).read(any());
            else if (boundary.equals("world")) doThrow(failure).when(fixture.world).read(any());
            else
              doThrow(failure)
                  .when(fixture.publishedPolicies)
                  .listPublishedRealmEntryPolicies(TENANT, VERSION);
            var observed = readThroughAdapter(fixture.service, rosterRead);
            assertThat(observed.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
            assertThat(observed.status.getDescription())
                .isEqualTo("Creation source dependency unavailable");
            assertThat(observed.value).isNull();
            assertThat(observed.completed).isFalse();
          }
        }
      }
    }
  }

  @Test
  void actualUninitializedPolicyClientIsUnavailableAndKeepsItsCauseInsideTheService()
      throws Exception {
    try (var fixture = new Fixture()) {
      var tls = new CommonGrpcClientProperties();
      tls.setCertChain("controlled-gs.crt");
      tls.setPrivateKey("controlled-gs.key");
      tls.setCaCert("controlled-ca.crt");
      var channels = mock(GrpcChannelFactory.class);
      try (var policy =
          new GameDesignPublishedRealmPolicyClient(
              new ServiceEndpointsProperties(), tls, channels, NAMESPACE)) {
        var actual =
            new GameSessionCharacterCreatePublicSourceService(
                NAMESPACE,
                fixture.routes,
                fixture.catalogs,
                fixture.admissions,
                fixture.launches,
                fixture.retention,
                fixture.gameDesign,
                fixture.world,
                policy);
        assertThatThrownBy(() -> actual.readPlayerCreatedRosterSource(rosterRequest()))
            .isInstanceOf(
                GameSessionCharacterCreatePublicSourceService.SourceDependencyUnavailableException
                    .class)
            .hasCauseInstanceOf(
                GameDesignPublishedRealmPolicyClient.UninitializedClientException.class);
        var observed = readThroughAdapter(actual, true);
        assertThat(observed.status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(observed.status.getDescription())
            .isEqualTo("Creation source dependency unavailable");
        assertThat(observed.value).isNull();
        assertThat(observed.completed).isFalse();
        verifyNoInteractions(channels);
      }
    }
    try (var fixture = new Fixture()) {
      var transport =
          Status.PERMISSION_DENIED.withDescription("private peer reason").asRuntimeException();
      doThrow(transport).when(fixture.gameDesign).read(any());
      assertThatThrownBy(() -> fixture.service.read(request()))
          .isInstanceOf(
              GameSessionCharacterCreatePublicSourceService.SourceDependencyUnavailableException
                  .class)
          .hasCause(transport)
          .hasMessage("Creation source dependency unavailable");
    }
  }

  @Test
  void localCallerRequestMissingSourceAndUndifferentiatedEvidenceErrorsKeepTheirClassifications() {
    try (var fixture = new Fixture()) {
      var adapter =
          new GameSessionCharacterCreatePublicSourceGrpcService(fixture.service, NAMESPACE);
      var invalid =
          new SourceObserver<
              net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse>();
      runAsSourcePeer(
          "entity-management-service",
          () ->
              adapter.readPlayerCreatedRosterSource(
                  rosterRequest().toBuilder().setSchemaVersion(2).build(), invalid));
      assertThat(invalid.status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      var denied =
          new SourceObserver<
              net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse>();
      runAsSourcePeer(
          "account-service", () -> adapter.readPlayerCreatedRosterSource(rosterRequest(), denied));
      assertThat(denied.status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
      verifyNoInteractions(fixture.gameDesign, fixture.world, fixture.publishedPolicies);
    }
    try (var fixture = new Fixture()) {
      when(fixture.retention.readByOriginalOperation(anyString(), anyString()))
          .thenReturn(Optional.empty());
      assertThat(readThroughAdapter(fixture.service, true).status.getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
      verifyNoInteractions(fixture.gameDesign, fixture.world, fixture.publishedPolicies);
    }
    // Common authored-core/World clients use plain IllegalStateException both for missing init
    // and invalid evidence. Without a typed discriminator their existing classification stays.
    for (boolean world : List.of(false, true)) {
      try (var fixture = new Fixture()) {
        var invalid = new IllegalStateException("undifferentiated client state or evidence error");
        if (world) doThrow(invalid).when(fixture.world).read(any());
        else doThrow(invalid).when(fixture.gameDesign).read(any());
        assertThat(readThroughAdapter(fixture.service, true).status.getCode())
            .isEqualTo(Status.Code.FAILED_PRECONDITION);
      }
    }
    try (var fixture = new Fixture()) {
      doThrow(
              new GameDesignPublishedRealmPolicyClient.InvalidResponseException(
                  "invalid policy evidence", null))
          .when(fixture.publishedPolicies)
          .listPublishedRealmEntryPolicies(TENANT, VERSION);
      assertThat(readThroughAdapter(fixture.service, true).status.getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }
  }

  private static SourceObserver<?> readThroughAdapter(
      GameSessionCharacterCreatePublicSourceService source, boolean rosterRead) {
    var adapter = new GameSessionCharacterCreatePublicSourceGrpcService(source, NAMESPACE);
    if (rosterRead) {
      var observer =
          new SourceObserver<
              net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse>();
      runAsSourcePeer(
          "entity-management-service",
          () -> adapter.readPlayerCreatedRosterSource(rosterRequest(), observer));
      return observer;
    }
    var observer =
        new SourceObserver<
            net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceResponse>();
    runAsSourcePeer(
        "account-service", () -> adapter.readCharacterCreatePublicSource(request(), observer));
    return observer;
  }

  private static void runAsSourcePeer(String service, Runnable operation) {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service, NAMESPACE, service))
        .run(operation);
  }

  private static final class SourceObserver<T> implements StreamObserver<T> {
    private T value;
    private Status status = Status.OK;
    private boolean completed;

    @Override
    public void onNext(T response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      status = Status.fromThrowable(failure);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  @Test
  void accountReadsDoNotCallTheRosterPublishedPolicyOwner() {
    try (var fixture = new Fixture()) {
      fixture.service.read(request());
      fixture.service.resolve(resolveRequest());
      verifyNoInteractions(fixture.publishedPolicies);
    }
  }

  @Test
  void entityRosterRejectsMissingOrChangedSealedPolicyAndOriginalSourceIdentity() {
    List<java.util.function.Consumer<Fixture>> invalidations =
        List.of(
            fixture ->
                when(fixture.publishedPolicies.listPublishedRealmEntryPolicies(TENANT, VERSION))
                    .thenReturn(null),
            fixture ->
                when(fixture.policySet.requireValidDigest())
                    .thenThrow(new IllegalArgumentException("invalid set")),
            fixture ->
                when(fixture.publicationOperation.world().request().targetNamespace())
                    .thenReturn("other"),
            fixture ->
                when(fixture.policySet.target().canonicalTenantId()).thenReturn(UUID.randomUUID()),
            fixture ->
                when(fixture.policySet.target().canonicalVersionId()).thenReturn(UUID.randomUUID()),
            fixture -> when(fixture.policySet.sourceCommitId()).thenReturn(UUID.randomUUID()),
            fixture -> when(fixture.policySet.publishWorkflowId()).thenReturn("other-publication"),
            fixture ->
                when(fixture.policySet.publishedReleaseBundleRef()).thenReturn("other-release"),
            fixture ->
                when(fixture.policySet.publishedReleaseBundleDigest())
                    .thenReturn("sha256:" + "4".repeat(64)),
            fixture ->
                when(fixture.policySet.manifestHash()).thenReturn("sha256:" + "4".repeat(64)),
            fixture -> when(fixture.policySet.versionNumber()).thenReturn(2),
            fixture ->
                when(fixture.publicationTerminal.publishedReleaseBundleDigest())
                    .thenReturn("sha256:" + "4".repeat(64)),
            fixture ->
                when(fixture.publicationTerminal.releaseContent().canonicalBytes())
                    .thenReturn(new byte[] {9}),
            fixture -> when(fixture.policySet.policies()).thenReturn(List.of()),
            fixture ->
                when(fixture.policySet.policies())
                    .thenReturn(List.of(fixture.policyEvidence, fixture.policyEvidence)),
            fixture ->
                when(fixture.policyEvidence.requireValidDigest(fixture.policySet))
                    .thenThrow(new IllegalArgumentException("invalid policy")),
            fixture -> when(fixture.policy.schemaVersion()).thenReturn(2),
            fixture ->
                when(fixture.policy.canonicalJson()).thenReturn("different-policy-and-descriptor"),
            fixture -> when(fixture.policyEvidence.sourceCommitId()).thenReturn(UUID.randomUUID()),
            fixture ->
                when(fixture.policyEvidence.sourceRevisionId()).thenReturn(UUID.randomUUID()),
            fixture ->
                when(fixture.policyEvidence.logicalRevisionId())
                    .thenReturn("different-logical-revision"),
            fixture ->
                when(fixture
                        .publicationOperation
                        .account()
                        .input()
                        .selection()
                        .selectedCommit()
                        .digest())
                    .thenReturn("sha256:" + "5".repeat(64)));
    for (var invalidate : invalidations) {
      try (var fixture = new Fixture()) {
        invalidate.accept(fixture);
        assertThatThrownBy(() -> fixture.service.readPlayerCreatedRosterSource(rosterRequest()))
            .isInstanceOfAny(StatusRuntimeException.class, IllegalArgumentException.class);
      }
    }
  }

  @Test
  void entityRosterRejectsIndependentlyChangedWorldSelectorsAndImmutableReleaseFields() {
    List<java.util.function.Consumer<Fixture>> invalidations =
        List.of(
            fixture -> when(fixture.associationWorld.canonicalBytes()).thenReturn(new byte[] {10}),
            fixture -> when(fixture.releaseWorld.canonicalBytes()).thenReturn(new byte[] {11}),
            fixture ->
                when(fixture.publicationOperation.world().canonicalBytes())
                    .thenReturn(new byte[] {12}),
            fixture -> when(fixture.worldRelease.manifestSchemaVersion()).thenReturn(2),
            fixture -> when(fixture.worldRelease.artifactDigests()).thenReturn(List.of()),
            fixture -> when(fixture.worldRelease.requiredManifestAssetKeys()).thenReturn(List.of()),
            fixture ->
                when(fixture.worldRelease.commandDefinitions()).thenReturn(List.of("changed")),
            fixture -> when(fixture.worldRelease.generationConfigRevision()).thenReturn("changed"),
            fixture -> when(fixture.worldRelease.participantDigests()).thenReturn(List.of()));
    for (var invalidate : invalidations) {
      try (var fixture = new Fixture()) {
        invalidate.accept(fixture);
        assertThatThrownBy(() -> fixture.service.readPlayerCreatedRosterSource(rosterRequest()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
      }
    }
  }

  @Test
  void finalTargetRereadOccursAfterTheAdditionalPublishedPolicyOwnerCall() {
    try (var fixture = new Fixture()) {
      var changed = mock(CanonicalPlayableTarget.class);
      when(changed.targetNamespace()).thenReturn("other");
      when(fixture.publishedPolicies.listPublishedRealmEntryPolicies(TENANT, VERSION))
          .thenAnswer(
              invocation -> {
                when(fixture.routes.readCurrentTarget(TENANT)).thenReturn(changed);
                return fixture.policySet;
              });
      assertThatThrownBy(() -> fixture.service.resolvePlayerCreatedRosterSource(rosterRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      verify(fixture.gameDesign).read(any());
      verify(fixture.world).read(any());
      verify(fixture.publishedPolicies).listPublishedRealmEntryPolicies(TENANT, VERSION);
    }
  }

  @Test
  void entityRosterSourcePreservesFullOriginalOwnerProofWithoutAccountScopeOrClock() {
    try (var fixture = new Fixture()) {
      var account = fixture.service.read(request());
      var result = fixture.service.readPlayerCreatedRosterSource(rosterRequest());
      assertThat(result.getRequest()).isEqualTo(rosterRequest());
      assertThat(result.getCatalog()).isEqualTo(account.getCatalog());
      assertThat(result.getPointer()).isEqualTo(account.getPointer());
      assertThat(result.getCanonicalVersionId()).isEqualTo(VERSION.toString());
      assertThat(result.getActiveLifecycleEpoch()).isEqualTo(7);
      assertThat(result.getOriginalControlPlaneRequestId())
          .isEqualTo(account.getOriginalControlPlaneRequestId());
      assertThat(result.getOriginalOwnerAttemptId()).isEqualTo(account.getOriginalOwnerAttemptId());
      assertThat(result.getOriginalOwnerFence()).isEqualTo(account.getOriginalOwnerFence());
      assertThat(result.getAuthoredSource().toByteArray())
          .containsExactly(account.getAuthoredSource().toByteArray());
      assertThat(result.getWorldLifecycleEvidence()).isEqualTo(account.getWorldLifecycleEvidence());
      assertThat(result.getPublishedPolicySetEvidence().toByteArray())
          .containsExactly(fixture.policySet.canonicalBytes());
      verify(fixture.publishedPolicies).listPublishedRealmEntryPolicies(TENANT, VERSION);
      assertThat(result.getPointer().getUpdatedAt()).isEqualTo("2026-10-24T00:00:00.123456789Z");
      verify(fixture.gameDesign, org.mockito.Mockito.times(2)).read(any());
      verify(fixture.world, org.mockito.Mockito.times(2)).read(any());
    }
  }

  @Test
  void entityRosterRequiresExactVersionEpochAndTargetBeforeAnyOtherOwner() {
    try (var fixture = new Fixture()) {
      for (var changed :
          List.of(
              rosterRequest().toBuilder()
                  .setExpectedCanonicalVersionId(UUID.randomUUID().toString())
                  .build(),
              rosterRequest().toBuilder().setActiveWorldEpoch(8).build(),
              rosterRequest().toBuilder()
                  .setCanonicalTenantId(UUID.randomUUID().toString())
                  .build(),
              rosterRequest().toBuilder().setRealmId(UUID.randomUUID().toString()).build(),
              rosterRequest().toBuilder().setWorldSlug("other").build(),
              rosterRequest().toBuilder().setRealmSlug("other").build(),
              rosterRequest().toBuilder()
                  .setCanonicalGameInstanceId(UUID.randomUUID().toString())
                  .build(),
              rosterRequest().toBuilder()
                  .setPlayableStateNamespaceId(UUID.randomUUID().toString())
                  .build(),
              rosterRequest().toBuilder().setCatalogRevision(2).build(),
              rosterRequest().toBuilder().setPointerVersion(4).build())) {
        assertThatThrownBy(() -> fixture.service.readPlayerCreatedRosterSource(changed))
            .isInstanceOf(StatusRuntimeException.class);
      }
      verifyNoInteractions(
          fixture.catalogs,
          fixture.admissions,
          fixture.launches,
          fixture.retention,
          fixture.gameDesign,
          fixture.world);
    }
  }

  @Test
  void entityRosterRechecksExpectedVersionAndEpochAfterIndependentOwnerCalls() {
    for (boolean changeVersion : List.of(true, false)) {
      try (var fixture = new Fixture()) {
        var changed = mock(CanonicalPlayableTarget.class);
        when(changed.targetNamespace()).thenReturn(NAMESPACE);
        when(changed.canonicalTenantId()).thenReturn(TENANT);
        when(changed.realmId()).thenReturn(REALM);
        when(changed.worldSlug()).thenReturn("world");
        when(changed.realmSlug()).thenReturn("realm");
        when(changed.playableStateNamespaceId()).thenReturn(PLAYABLE);
        when(changed.playableStateScope()).thenReturn("SHARED");
        when(changed.canonicalGameInstanceId()).thenReturn(INSTANCE);
        when(changed.catalogRevision()).thenReturn(1L);
        when(changed.pointerVersion()).thenReturn(3L);
        when(changed.canonicalVersionId()).thenReturn(changeVersion ? UUID.randomUUID() : VERSION);
        when(changed.activeWorldEpoch()).thenReturn(changeVersion ? 7L : 8L);
        when(changed.characterCreationPolicy()).thenReturn("PLAYER_CREATED");
        when(fixture.routes.readCurrentTarget(TENANT)).thenReturn(fixture.route, changed);
        assertThatThrownBy(() -> fixture.service.readPlayerCreatedRosterSource(rosterRequest()))
            .isInstanceOf(StatusRuntimeException.class);
        verify(fixture.gameDesign).read(any());
        verify(fixture.world).read(any());
      }
    }
  }

  @Test
  void entityRosterRefusesOriginalSourceSubstitutionAndInactiveWorld() {
    try (var fixture = new Fixture()) {
      when(fixture.authored.canonicalBytes()).thenReturn(new byte[] {9});
      assertThatThrownBy(() -> fixture.service.readPlayerCreatedRosterSource(rosterRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      verifyNoInteractions(fixture.world);
    }
    try (var fixture = new Fixture()) {
      when(fixture.lifecycle.lifecycleStatus()).thenReturn("TERMINATING");
      assertThatThrownBy(() -> fixture.service.readPlayerCreatedRosterSource(rosterRequest()))
          .isInstanceOf(StatusRuntimeException.class);
    }
    try (var fixture = new Fixture()) {
      when(fixture.candidate.canonicalSourceResultBytes())
          .thenReturn("retained".getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[] {9});
      assertThatThrownBy(() -> fixture.service.readPlayerCreatedRosterSource(rosterRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      verify(fixture.world).read(any());
    }
  }

  @Test
  void entityRosterRefusesAmbientSqlBeforeReadingAnyOwner() {
    try (var fixture = new Fixture()) {
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> fixture.service.readPlayerCreatedRosterSource(rosterRequest()))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      verifyNoInteractions(
          fixture.routes,
          fixture.catalogs,
          fixture.admissions,
          fixture.launches,
          fixture.retention,
          fixture.gameDesign,
          fixture.world);
    }
  }

  static ReadPlayerCreatedRosterSourceRequest rosterRequest() {
    return ReadPlayerCreatedRosterSourceRequest.newBuilder()
        .setSchemaVersion(1)
        .setReadRequestId("60000000-0000-4000-8000-000000000003")
        .setTargetNamespace(NAMESPACE)
        .setCanonicalTenantId(TENANT.toString())
        .setRealmId(REALM.toString())
        .setWorldSlug("world")
        .setRealmSlug("realm")
        .setPlayableStateNamespaceId(PLAYABLE.toString())
        .setPlayableStateScope("SHARED")
        .setCanonicalGameInstanceId(INSTANCE.toString())
        .setCatalogRevision(1)
        .setPointerVersion(3)
        .setExpectedCanonicalVersionId(VERSION.toString())
        .setActiveWorldEpoch(7)
        .build();
  }

  @Test
  void resolvesFriendlySelectorsToFullActualCanonicalTargetBeforeScopeIssuance() {
    try (var fixture = new Fixture()) {
      var response = fixture.service.resolve(resolveRequest());
      assertThat(response.getRequest()).isEqualTo(resolveRequest());
      assertThat(response.getCatalog().getTenantId()).isEqualTo(TENANT.toString());
      assertThat(response.getCatalog().getTenantSlug()).isEqualTo("tenant");
      assertThat(response.getCatalog().getRealmId()).isEqualTo(REALM.toString());
      assertThat(response.getCatalog().getWorldSlug()).isEqualTo("world");
      assertThat(response.getCatalog().getRealmSlug()).isEqualTo("realm");
      assertThat(response.getCatalog().getWorldDisplayName()).isEqualTo("Published world");
      assertThat(response.getCatalog().getRealmDisplayName()).isEqualTo("Published realm");
      assertThat(response.getCatalog().getPlayableStateNamespaceId())
          .isEqualTo(PLAYABLE.toString());
      assertThat(response.getCatalog().getStateScope()).isEqualTo("SHARED");
      assertThat(response.getCatalog().getVisible()).isTrue();
      assertThat(response.getCatalog().getPublicProduction()).isTrue();
      assertThat(response.getCatalog().getCharacterCreationPolicy()).isEqualTo("PLAYER_CREATED");
      assertThat(response.getCatalog().getCatalogRevision()).isEqualTo(1);
      assertThat(response.getPointer().getPointerVersion()).isEqualTo(3);
      assertThat(response.getPointer().getAdmissionState()).isEqualTo("OPEN");
      assertThat(response.getPointer().getUpdatedAt()).isEqualTo("2026-10-24T00:00:00.123456789Z");
      assertThat(response.getCanonicalGameInstanceId()).isEqualTo(INSTANCE.toString());
      assertThat(response.getCanonicalVersionId()).isEqualTo(VERSION.toString());
      assertThat(response.getActiveLifecycleEpoch()).isEqualTo(7);
      assertThat(fixture.service.resolve(resolveRequest())).isEqualTo(response);
      verifyNoInteractions(fixture.launches, fixture.retention, fixture.gameDesign, fixture.world);
    }
  }

  @Test
  void friendlyResolutionRejectsUnknownAmbiguousAndUnsupportedTargets() {
    try (var fixture = new Fixture()) {
      var unknown = resolveRequest().toBuilder().setWorldSlug("unknown").build();
      assertThatThrownBy(() -> fixture.service.resolve(unknown))
          .isInstanceOf(StatusRuntimeException.class);
      when(fixture.routes.readVisiblePublicProductionTargets()).thenReturn(List.of());
      assertThatThrownBy(() -> fixture.service.resolve(resolveRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      when(fixture.routes.readVisiblePublicProductionTargets())
          .thenReturn(List.of(fixture.route, fixture.route));
      assertThatThrownBy(() -> fixture.service.resolve(resolveRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      when(fixture.routes.readVisiblePublicProductionTargets()).thenReturn(List.of(fixture.route));
      when(fixture.route.characterCreationPolicy()).thenReturn("PRESEEDED_ONLY");
      assertThatThrownBy(() -> fixture.service.resolve(resolveRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      when(fixture.route.characterCreationPolicy()).thenReturn("PLAYER_CREATED");
      when(fixture.route.playableStateScope()).thenReturn("ISOLATED");
      assertThatThrownBy(() -> fixture.service.resolve(resolveRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      verifyNoInteractions(
          fixture.catalogs,
          fixture.admissions,
          fixture.launches,
          fixture.retention,
          fixture.gameDesign,
          fixture.world);
    }
  }

  @Test
  void friendlyResolutionRejectsPublicPolicyDenialAndCurrentRouteReplacement() {
    try (var fixture = new Fixture()) {
      when(fixture.catalog.publicProduction()).thenReturn(false);
      assertThatThrownBy(() -> fixture.service.resolve(resolveRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      when(fixture.catalog.publicProduction()).thenReturn(true);
      when(fixture.catalog.visible()).thenReturn(false);
      assertThatThrownBy(() -> fixture.service.resolve(resolveRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      verifyNoInteractions(fixture.gameDesign, fixture.world);
    }
    try (var fixture = new Fixture()) {
      var changed = mock(CanonicalPlayableTarget.class);
      when(fixture.routes.readCurrentTarget(TENANT)).thenReturn(fixture.route, changed);
      assertThatThrownBy(() -> fixture.service.resolve(resolveRequest()))
          .isInstanceOf(StatusRuntimeException.class);
      verifyNoInteractions(fixture.gameDesign, fixture.world);
    }
  }

  static ResolveCharacterCreatePublicTargetRequest resolveRequest() {
    return ResolveCharacterCreatePublicTargetRequest.newBuilder()
        .setSchemaVersion(1)
        .setReadRequestId("60000000-0000-4000-8000-000000000002")
        .setTargetNamespace(NAMESPACE)
        .setWorldSlug("world")
        .setRealmSlug("realm")
        .build();
  }

  @Test
  void failedFixtureConstructionClosesOnlyItsPartiallyOpenedStaticCodecs() {
    try (var existingPolicyMock = mockStatic(RealmEntryPolicy.class)) {
      assertThatThrownBy(Fixture::new)
          .isInstanceOf(org.mockito.exceptions.base.MockitoException.class);
      assertThat(existingPolicyMock.isClosed()).isFalse();
    }
    try (var fixture = new Fixture()) {
      assertThat(fixture.service.read(request()).getRequest()).isEqualTo(request());
    }
  }

  @Test
  void returnsExactOriginalSourceAndFullPointerTimestampAfterIndependentOwnerReads() {
    try (var fixture = new Fixture()) {
      var result = fixture.service.read(request());
      assertThat(result.getRequest()).isEqualTo(request());
      assertThat(result.getCatalog().getCharacterCreationPolicy()).isEqualTo("PLAYER_CREATED");
      assertThat(result.getCatalog().getWorldDisplayName()).isEqualTo("Published world");
      assertThat(result.getCatalog().getRealmDisplayName()).isEqualTo("Published realm");
      assertThat(result.getPointer().getUpdatedAt()).isEqualTo("2026-10-24T00:00:00.123456789Z");
      assertThat(result.getPointer().getAdmissionState()).isEqualTo("OPEN");
      assertThat(result.getCanonicalVersionId()).isEqualTo(VERSION.toString());
      assertThat(result.getActiveLifecycleEpoch()).isEqualTo(7);
      assertThat(result.getAuthoredSource().getCanonicalResultBytes())
          .isEqualTo(ByteString.copyFromUtf8("retained"));
      assertThat(result.getWorldLifecycleEvidence())
          .isEqualTo(ByteString.copyFromUtf8("world-original"));
      verify(fixture.gameDesign).read(any());
      verify(fixture.world).read(any());
      assertThat(fixture.service.read(request())).isEqualTo(result);
    }
  }

  @Test
  void rejectsChangedSelectorsAndUnsupportedScopesBeforeAnyOtherOwner() {
    try (var fixture = new Fixture()) {
      for (var altered :
          List.of(
              request().toBuilder().setRealmId(UUID.randomUUID().toString()).build(),
              request().toBuilder()
                  .setCanonicalGameInstanceId(UUID.randomUUID().toString())
                  .build(),
              request().toBuilder()
                  .setPlayableStateNamespaceId(UUID.randomUUID().toString())
                  .build(),
              request().toBuilder().setCatalogRevision(2).build(),
              request().toBuilder().setPointerVersion(4).build(),
              request().toBuilder().setRealmSlug("other").build(),
              request().toBuilder().setPlayableStateScope("ISOLATED").build())) {
        assertThatThrownBy(() -> fixture.service.read(altered))
            .isInstanceOf(StatusRuntimeException.class);
      }
      verifyNoInteractions(
          fixture.catalogs,
          fixture.admissions,
          fixture.launches,
          fixture.retention,
          fixture.gameDesign,
          fixture.world);
    }
  }

  @Test
  void rejectsMissingRetainedOriginalWithoutCapturingOrCallingRemoteOwners() {
    try (var fixture = new Fixture()) {
      when(fixture.retention.readByOriginalOperation(NAMESPACE, "original-launch"))
          .thenReturn(Optional.empty());
      assertThatThrownBy(() -> fixture.service.read(request()))
          .isInstanceOf(StatusRuntimeException.class);
      verifyNoInteractions(fixture.gameDesign, fixture.world);
    }
  }

  @Test
  void rejectsChangedPublicationProvenanceBeforeAuthoredOwnerCall() {
    try (var fixture = new Fixture()) {
      when(fixture.candidate.selectedCommitId()).thenReturn(UUID.randomUUID());
      assertThatThrownBy(() -> fixture.service.read(request()))
          .isInstanceOf(StatusRuntimeException.class);
      verifyNoInteractions(fixture.gameDesign, fixture.world);
    }
  }

  @Test
  void rejectsOriginalSourceReplacementWithoutCallingWorld() {
    try (var fixture = new Fixture()) {
      when(fixture.authored.canonicalBytes()).thenReturn(new byte[] {9});
      assertThatThrownBy(() -> fixture.service.read(request()))
          .isInstanceOf(StatusRuntimeException.class);
      verifyNoInteractions(fixture.world);
    }
  }

  @Test
  void rejectsWorldNonActiveAndChangedCurrentTargetAfterRemoteReads() {
    try (var fixture = new Fixture()) {
      when(fixture.lifecycle.lifecycleStatus()).thenReturn("TERMINATING");
      assertThatThrownBy(() -> fixture.service.read(request()))
          .isInstanceOf(StatusRuntimeException.class);
    }
    try (var fixture = new Fixture()) {
      var changed = mock(CanonicalPlayableTarget.class);
      when(changed.targetNamespace()).thenReturn("other");
      when(fixture.routes.readCurrentTarget(TENANT)).thenReturn(fixture.route, changed);
      assertThatThrownBy(() -> fixture.service.read(request()))
          .isInstanceOf(StatusRuntimeException.class);
    }
  }

  @Test
  void accountSourceRejectsOverlengthSlugsBeforeReadingAnyOwner() {
    String overlength = "a".repeat(RealmEntryPolicy.MAX_SLUG_LENGTH + 1);
    for (var invalid :
        List.of(
            request().toBuilder().setWorldSlug(overlength).build(),
            request().toBuilder().setRealmSlug(overlength).build())) {
      try (var fixture = new Fixture()) {
        assertThatThrownBy(() -> fixture.service.read(invalid))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
        verifyNoInteractions(
            fixture.routes,
            fixture.catalogs,
            fixture.admissions,
            fixture.launches,
            fixture.retention,
            fixture.gameDesign,
            fixture.world,
            fixture.publishedPolicies);
      }
    }
  }

  @Test
  void accountSourceRequestValidationAcceptsMaximumCanonicalSlugs() {
    String maximum = "a".repeat(RealmEntryPolicy.MAX_SLUG_LENGTH);
    var bounded = request().toBuilder().setWorldSlug(maximum).setRealmSlug(maximum).build();
    assertThatCode(
            () -> GameSessionCharacterCreatePublicSourceService.requireRequest(bounded, NAMESPACE))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAmbientTransactionBeforeReadingAnyOwner() {
    try (var fixture = new Fixture()) {
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> fixture.service.read(request()))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      verifyNoInteractions(
          fixture.routes,
          fixture.catalogs,
          fixture.admissions,
          fixture.launches,
          fixture.retention,
          fixture.gameDesign,
          fixture.world);
    }
  }

  static ReadCharacterCreatePublicSourceRequest request() {
    return ReadCharacterCreatePublicSourceRequest.newBuilder()
        .setSchemaVersion(1)
        .setReadRequestId("60000000-0000-4000-8000-000000000001")
        .setTargetNamespace(NAMESPACE)
        .setCanonicalTenantId(TENANT.toString())
        .setRealmId(REALM.toString())
        .setWorldSlug("world")
        .setRealmSlug("realm")
        .setPlayableStateNamespaceId(PLAYABLE.toString())
        .setPlayableStateScope("SHARED")
        .setCanonicalGameInstanceId(INSTANCE.toString())
        .setCatalogRevision(1)
        .setPointerVersion(3)
        .setEvaluatedAt("2026-10-24T00:00:00Z")
        .setConnectScopeExpiresAt("2026-10-24T00:05:00Z")
        .build();
  }

  private static final class Fixture implements AutoCloseable {
    final CanonicalPlayerRouteReadService routes = mock(CanonicalPlayerRouteReadService.class);
    final GameSessionCanonicalRealmCatalogRepository catalogs =
        mock(GameSessionCanonicalRealmCatalogRepository.class);
    final CanonicalInitialAdmissionRepository admissions =
        mock(CanonicalInitialAdmissionRepository.class);
    final CanonicalGameInstanceLaunchAssociationRepository launches =
        mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    final GameSessionAuthoredActorCoreRetentionRepository retention =
        mock(GameSessionAuthoredActorCoreRetentionRepository.class);
    final AuthoredInitialActorCoreReadClient gameDesign =
        mock(AuthoredInitialActorCoreReadClient.class);
    final WorldCanonicalInstanceLifecycleClient world =
        mock(WorldCanonicalInstanceLifecycleClient.class);
    final GameDesignPublishedRealmPolicyClient publishedPolicies =
        mock(GameDesignPublishedRealmPolicyClient.class);
    final PublishedRealmEntryPolicySetEvidence policySet =
        mock(PublishedRealmEntryPolicySetEvidence.class, RETURNS_DEEP_STUBS);
    final PublishedRealmEntryPolicyEvidence policyEvidence =
        mock(PublishedRealmEntryPolicyEvidence.class);
    final AuthoredInitialActorCoreProjection projection =
        mock(AuthoredInitialActorCoreProjection.class, RETURNS_DEEP_STUBS);
    final RealmEntryPolicy policy = mock(RealmEntryPolicy.class);
    final GameDesignPublicationOperationBinding publicationOperation =
        mock(GameDesignPublicationOperationBinding.class, RETURNS_DEEP_STUBS);
    final GameDesignPublicationTerminalEvidence publicationTerminal =
        mock(GameDesignPublicationTerminalEvidence.class, RETURNS_DEEP_STUBS);
    final CanonicalPlayableTarget route = mock(CanonicalPlayableTarget.class);
    final CanonicalRealmCatalogSnapshot catalog =
        mock(CanonicalRealmCatalogSnapshot.class, RETURNS_DEEP_STUBS);
    final Candidate candidate = mock(Candidate.class);
    final AuthoredInitialActorCoreReadEvidence.ReadResult authored =
        mock(AuthoredInitialActorCoreReadEvidence.ReadResult.class);
    final WorldCanonicalInstanceLifecycleEvidence lifecycle =
        mock(WorldCanonicalInstanceLifecycleEvidence.class);
    final AuthoredWorldReleaseAttestationEvidence worldRelease =
        mock(AuthoredWorldReleaseAttestationEvidence.class);
    final WorldPublishedStartLocationEvidence associationWorld =
        mock(WorldPublishedStartLocationEvidence.class);
    final WorldPublishedStartLocationEvidence releaseWorld =
        mock(WorldPublishedStartLocationEvidence.class);
    private org.mockito.MockedStatic<StartSessionTemplateAssociationReadGrpcCodec> associationCodec;
    private org.mockito.MockedStatic<AuthoredInitialActorCoreReadGrpcCodec> sourceCodec;
    private org.mockito.MockedStatic<RealmEntryPolicy> policyCodec;
    private org.mockito.MockedStatic<GameDesignPublicationOperationBinding>
        publicationOperationCodec;
    private org.mockito.MockedStatic<GameDesignPublicationTerminalEvidence>
        publicationTerminalCodec;
    private org.mockito.MockedConstruction<GameDesignPublicationTerminalEvidence.ReleaseContent>
        releaseContentCodec;
    final GameSessionCharacterCreatePublicSourceService service;

    Fixture() {
      try {
        associationCodec = mockStatic(StartSessionTemplateAssociationReadGrpcCodec.class);
        sourceCodec = mockStatic(AuthoredInitialActorCoreReadGrpcCodec.class);
        policyCodec = mockStatic(RealmEntryPolicy.class);
        publicationOperationCodec = mockStatic(GameDesignPublicationOperationBinding.class);
        publicationTerminalCodec = mockStatic(GameDesignPublicationTerminalEvidence.class);
        releaseContentCodec =
            org.mockito.Mockito.mockConstruction(
                GameDesignPublicationTerminalEvidence.ReleaseContent.class,
                (value, context) -> {
                  when(value.contentDigest()).thenReturn("sha256:" + "1".repeat(64));
                  when(value.canonicalBytes()).thenReturn(new byte[] {8});
                  when(value.manifestSchemaVersion())
                      .thenReturn((Integer) context.arguments().get(7));
                  when(value.artifactDigests())
                      .thenReturn(
                          ((List<?>) context.arguments().get(8))
                              .stream()
                                  .map(AuthoredWorldReleaseAttestationEvidence.Artifact.class::cast)
                                  .toList());
                  when(value.requiredManifestAssetKeys())
                      .thenReturn(
                          ((List<?>) context.arguments().get(9))
                              .stream().map(String.class::cast).toList());
                  when(value.participantDigests())
                      .thenReturn(
                          ((List<?>) context.arguments().get(10))
                              .stream()
                                  .map(
                                      GameDesignPublicationTerminalEvidence.Participant.class::cast)
                                  .toList());
                  when(value.commandDefinitions())
                      .thenReturn(
                          ((List<?>) context.arguments().get(11))
                              .stream().map(String.class::cast).toList());
                  when(value.generationConfigRevision())
                      .thenReturn((String) context.arguments().get(12));
                  when(value.worldStartLocationEvidence())
                      .thenReturn(
                          (WorldPublishedStartLocationEvidence) context.arguments().get(13));
                });
        policyCodec.when(() -> RealmEntryPolicy.isCanonicalSlug(anyString())).thenCallRealMethod();
        UUID sourceOperation = UUID.randomUUID();
        UUID commit = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        byte[] tuple = new byte[] {1};
        byte[] account = new byte[] {2};
        byte[] sourceBytes = "retained".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var launch = mock(CanonicalGameInstanceLaunchAssociation.class, RETURNS_DEEP_STUBS);
        when(launch.launchBindingEvidence().releaseAttestation()).thenReturn(worldRelease);
        var proof = mock(CanonicalInitialAdmissionOwnerProof.class);
        when(proof.provesCommit()).thenReturn(true);
        when(proof.proofDigest()).thenReturn("sha256:" + "a".repeat(64));
        when(proof.initialAdmissionRequestId()).thenReturn("open-original");
        when(proof.activeLifecycleEpoch()).thenReturn(7L);
        var pointer =
            new CanonicalInitialAdmissionRepository.CurrentOpenSource(
                new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(proof, "b".repeat(64)),
                TENANT,
                REALM,
                "world",
                "realm",
                "OPEN",
                INSTANCE,
                3,
                1,
                Instant.parse("2026-10-24T00:00:00.123456789Z"));
        when(routes.readCurrentTarget(TENANT)).thenReturn(route);
        when(routes.readVisiblePublicProductionTargets()).thenReturn(List.of(route));
        when(route.targetNamespace()).thenReturn(NAMESPACE);
        when(route.canonicalTenantId()).thenReturn(TENANT);
        when(route.tenantSlug()).thenReturn("tenant");
        when(route.realmId()).thenReturn(REALM);
        when(route.worldSlug()).thenReturn("world");
        when(route.realmSlug()).thenReturn("realm");
        when(route.worldDisplayName()).thenReturn("Published world");
        when(route.realmDisplayName()).thenReturn("Published realm");
        when(route.playableStateNamespaceId()).thenReturn(PLAYABLE);
        when(route.playableStateScope()).thenReturn("SHARED");
        when(route.canonicalGameInstanceId()).thenReturn(INSTANCE);
        when(route.canonicalVersionId()).thenReturn(VERSION);
        when(route.catalogRevision()).thenReturn(1L);
        when(route.pointerVersion()).thenReturn(3L);
        when(route.characterCreationPolicy()).thenReturn("PLAYER_CREATED");
        when(route.activeWorldEpoch()).thenReturn(7L);
        when(route.ownerProofDigest()).thenReturn("sha256:" + "a".repeat(64));
        when(route.admissionPointerSnapshotDigest()).thenReturn("b".repeat(64));
        when(catalogs.readUniqueVisiblePublicProduction(NAMESPACE, TENANT))
            .thenReturn(Optional.of(catalog));
        when(catalog.targetNamespace()).thenReturn(NAMESPACE);
        when(catalog.tenantId()).thenReturn(TENANT);
        when(catalog.tenantSlug()).thenReturn("tenant");
        when(catalog.realmId()).thenReturn(REALM);
        when(catalog.worldSlug()).thenReturn("world");
        when(catalog.realmSlug()).thenReturn("realm");
        when(catalog.realmDisplayName()).thenReturn("Published realm");
        when(catalog.sourceIntakeReceipt().source().worldDisplayName())
            .thenReturn("Published world");
        when(catalog.sourceIntakeReceipt().source().operationId()).thenReturn(sourceOperation);
        when(catalog.sourceIntakeReceipt().source().evidenceDigest()).thenReturn("source-digest");
        when(catalog.playableStateNamespaceId()).thenReturn(PLAYABLE);
        when(catalog.stateScope()).thenReturn("SHARED");
        when(catalog.visible()).thenReturn(true);
        when(catalog.publicProduction()).thenReturn(true);
        when(catalog.characterCreationPolicy()).thenReturn("PLAYER_CREATED");
        when(catalog.catalogRevision()).thenReturn(1L);
        when(catalog.creationRequestId()).thenReturn(UUID.randomUUID());
        when(catalog.receiptDigest()).thenReturn("catalog-receipt");
        when(admissions.readCurrentOpenSourceForRealm(NAMESPACE, TENANT, REALM))
            .thenReturn(pointer);
        when(launch.targetNamespace()).thenReturn(NAMESPACE);
        when(launch.canonicalTenantId()).thenReturn(TENANT);
        when(launch.gameInstanceUuid()).thenReturn(INSTANCE);
        when(launch.worldSlug()).thenReturn("world");
        when(launch.controlPlaneRequestId()).thenReturn("original-launch");
        when(launch.launchDescriptorId()).thenReturn("original-descriptor");
        when(launch.playableStateNamespaceId()).thenReturn(PLAYABLE);
        when(launch.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
        when(launch.publicProduction()).thenReturn(true);
        when(launch.currentGameInstanceStatus())
            .thenReturn(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING);
        when(launch.launchBindingEvidence().descriptor().requestDigest())
            .thenReturn("sha256:" + "c".repeat(64));
        when(launch.launchBindingEvidence().descriptor().resultDigest())
            .thenReturn("sha256:" + "d".repeat(64));
        when(launch.launchBindingEvidence().releaseAttestation().evidenceDigest())
            .thenReturn("sha256:" + "e".repeat(64));
        when(launch.launchBindingEvidence().releaseAttestation().canonicalVersionId())
            .thenReturn(VERSION);
        var launchBinding = launch.launchBindingEvidence();
        var launchDescriptor = launchBinding.descriptor();
        String descriptorRequestDigest = launchDescriptor.requestDigest();
        String descriptorResultDigest = launchDescriptor.resultDigest();
        String releaseDigest = launchBinding.releaseAttestation().evidenceDigest();
        var launchTarget = mock(CanonicalInitialAdmissionLaunchTarget.class);
        when(launchTarget.association()).thenReturn(launch);
        when(launches.readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE))
            .thenReturn(Optional.of(launchTarget));
        when(retention.readByOriginalOperation(NAMESPACE, "original-launch"))
            .thenReturn(
                Optional.of(
                    new GameSessionAuthoredActorCoreRetentionRepository.StoredSource(
                        candidate, Instant.EPOCH)));
        when(candidate.targetNamespace()).thenReturn(NAMESPACE);
        when(candidate.canonicalTenantId()).thenReturn(TENANT);
        when(candidate.gameInstanceUuid()).thenReturn(INSTANCE);
        when(candidate.controlPlaneRequestId()).thenReturn("original-launch");
        when(candidate.worldSlug()).thenReturn("world");
        when(candidate.ownerAttemptId()).thenReturn(owner);
        when(candidate.ownerFence()).thenReturn(8L);
        when(candidate.originalPostAuthorizationTuple()).thenReturn(tuple);
        when(candidate.accountRedemptionProjection()).thenReturn(account);
        when(candidate.canonicalVersionId()).thenReturn(VERSION);
        when(candidate.selectedCommitId()).thenReturn(commit);
        when(candidate.publishWorkflowId()).thenReturn("publication");
        when(candidate.associationDigest()).thenReturn("association");
        when(candidate.worldSourceOperationId()).thenReturn(sourceOperation);
        when(candidate.worldSourceEvidenceDigest()).thenReturn("source-digest");
        when(candidate.launchDescriptorId()).thenReturn("original-descriptor");
        when(candidate.descriptorRequestDigest()).thenReturn(descriptorRequestDigest);
        when(candidate.descriptorResultDigest()).thenReturn(descriptorResultDigest);
        when(candidate.releaseAttestationEvidenceDigest()).thenReturn(releaseDigest);
        when(candidate.sourceReadRequestBytes()).thenReturn(new byte[0]);
        when(candidate.canonicalSourceResultBytes()).thenReturn(sourceBytes);
        when(candidate.sourceResultDigest()).thenReturn("source-result-digest");
        var associationRequest = mock(StartSessionTemplateAssociationReadEvidence.Request.class);
        when(associationRequest.selection())
            .thenReturn(
                new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                    VERSION, commit, "publication", "sha256:" + "f".repeat(64)));
        associationCodec
            .when(() -> StartSessionTemplateAssociationReadGrpcCodec.fromRequest(any()))
            .thenReturn(associationRequest);
        associationCodec
            .when(() -> StartSessionTemplateAssociationReadGrpcCodec.toRequest(any()))
            .thenReturn(ReadStartSessionTemplateAssociationRequest.getDefaultInstance());
        var historical =
            mock(HistoricalOriginalStartSessionOwnerEvidence.Result.class, RETURNS_DEEP_STUBS);
        when(launches.readHistoricalOriginalStartSessionOwnerEvidence(any()))
            .thenReturn(Optional.of(historical));
        when(historical.ownerAttemptId()).thenReturn(owner);
        when(historical.ownerFence()).thenReturn(8L);
        when(historical.originalTuple().canonicalBytes()).thenReturn(tuple);
        when(historical.accountRedemptionProjection()).thenReturn(account);
        when(historical.descriptorPin().associationRead().request()).thenReturn(associationRequest);
        var selected = historical.descriptorPin().associationRead().association();
        when(selected.canonicalVersionId()).thenReturn(VERSION);
        when(selected.canonicalTenantId()).thenReturn(TENANT);
        when(selected.selectedCommitId()).thenReturn(commit);
        when(selected.publishWorkflowId()).thenReturn("publication");
        when(selected.associationDigest()).thenReturn("association");
        when(selected.sourceOperationId()).thenReturn(sourceOperation);
        when(selected.sourceEvidenceDigest()).thenReturn("source-digest");
        var original = historical.launchAssociation();
        when(original.targetNamespace()).thenReturn(NAMESPACE);
        when(original.canonicalTenantId()).thenReturn(TENANT);
        when(original.gameInstanceUuid()).thenReturn(INSTANCE);
        when(original.worldSlug()).thenReturn("world");
        when(original.controlPlaneRequestId()).thenReturn("original-launch");
        when(original.launchDescriptorId()).thenReturn("original-descriptor");
        when(original.playableStateNamespaceId()).thenReturn(PLAYABLE);
        when(original.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
        when(original.publicProduction()).thenReturn(true);
        when(original.launchBindingEvidence()).thenReturn(launchBinding);
        when(authored.request()).thenReturn(associationRequest);
        when(authored.canonicalBytes()).thenReturn(sourceBytes);
        when(authored.digest()).thenReturn("source-result-digest");
        when(authored.descriptor()).thenReturn(launchDescriptor);
        when(projection.policy().canonicalJson()).thenReturn("controlled-policy");
        when(projection.bindingId()).thenReturn(AUTHORED_BINDING);
        when(projection.bindingDigest()).thenReturn("sha256:" + "d".repeat(64));
        when(projection.descriptor().canonicalJson()).thenReturn(DESCRIPTOR_JSON);
        when(projection.descriptor().digest())
            .thenReturn(PlayerCreationDescriptor.parse(DESCRIPTOR_JSON).digest());
        when(projection.canonicalTenantId()).thenReturn(TENANT);
        when(projection.canonicalVersionId()).thenReturn(VERSION);
        when(projection.worldSourceOperationId()).thenReturn(sourceOperation);
        when(projection.worldSourceEvidenceDigest()).thenReturn("source-digest");
        when(projection.selectedCommitId()).thenReturn(commit);
        when(projection.publicationWorkflowId()).thenReturn("publication");
        when(authored.projections()).thenReturn(List.of(projection));
        policyCodec.when(() -> RealmEntryPolicy.parseCanonical(any(), any())).thenReturn(policy);
        when(policy.entryPolicy()).thenReturn(RealmEntryPolicy.EntryPolicy.PLAYER_CREATED);
        when(policy.visible()).thenReturn(true);
        when(policy.publicProduction()).thenReturn(true);
        when(policy.stateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
        when(policy.worldSlug()).thenReturn("world");
        when(policy.realmSlug()).thenReturn("realm");
        when(policy.worldDisplayName()).thenReturn("Published world");
        when(policy.realmDisplayName()).thenReturn("Published realm");
        when(policy.schemaVersion())
            .thenReturn(RealmEntryPolicy.AUTHORED_INITIAL_ACTOR_CORE_SCHEMA_VERSION);
        when(policy.canonicalJson()).thenReturn("controlled-policy");
        var policyRevision = UUID.randomUUID();
        when(projection.policy().commitId()).thenReturn(commit);
        when(projection.policy().revisionId()).thenReturn(policyRevision);
        when(projection.policy().logicalRevisionId()).thenReturn("policy-logical");
        when(projection.selectedCommitDigest()).thenReturn("sha256:" + "2".repeat(64));
        var originalAssociationRead = historical.descriptorPin().associationRead();
        when(authored.associationRead()).thenReturn(originalAssociationRead);
        when(originalAssociationRead.worldPublishedStartLocationEvidence())
            .thenReturn(associationWorld);
        when(associationWorld.canonicalBytes()).thenReturn(new byte[] {9});
        when(releaseWorld.canonicalBytes()).thenReturn(new byte[] {9});
        when(worldRelease.worldStartLocationEvidence()).thenReturn(releaseWorld);
        var artifact =
            new AuthoredWorldReleaseAttestationEvidence.Artifact(
                "world",
                "WORLD_MANIFEST",
                "artifacts/sha256/" + "6".repeat(64),
                "sha256:" + "6".repeat(64),
                "application/json",
                1);
        var participant =
            new AuthoredWorldReleaseAttestationEvidence.Participant(
                "world",
                "PUBLIC",
                false,
                null,
                commit.toString(),
                "7".repeat(64),
                1,
                true,
                "sha256:" + "8".repeat(64));
        when(authored.associationRead().releaseBundle())
            .thenReturn(
                PublishedReleaseBundle.newBuilder()
                    .setId(1L)
                    .setVersionId(1L)
                    .setCanonicalTenantId(TENANT.toString())
                    .setCanonicalVersionId(VERSION.toString())
                    .setPublishWorkflowId("publication")
                    .setPublishedReleaseBundleRef("original-release")
                    .setVersionNumber(1)
                    .setAttestationSchemaVersion("v2")
                    .setManifestHash("sha256:" + "3".repeat(64))
                    .setManifestSchemaVersion(1)
                    .addRequiredManifestAssetKeys("world")
                    .addArtifactDigests(
                        PublishedArtifactDigest.newBuilder()
                            .setUsageKey(artifact.usageKey())
                            .setArtifactKind(artifact.artifactKind())
                            .setImmutableObjectKey(artifact.immutableObjectKey())
                            .setContentDigest(artifact.contentDigest())
                            .setContentType(artifact.contentType())
                            .setArtifactSchemaVersion(artifact.artifactSchemaVersion()))
                    .addParticipantDigests(
                        ParticipantDigest.newBuilder()
                            .setParticipantKey(participant.participantKey())
                            .setScopeValue(participant.scopeValue())
                            .setAppliedCommitId(participant.appliedCommitId())
                            .setContentDigest(participant.contentDigest())
                            .setDigestSchemaVersion(participant.digestSchemaVersion())
                            .setAbilitySchemaDigest(participant.abilitySchemaDigest()))
                    .addCommandDefinitions("look")
                    .setGenerationConfigRevision("generation-1")
                    .build());
        when(worldRelease.manifestSchemaVersion()).thenReturn(1);
        when(worldRelease.artifactDigests()).thenReturn(List.of(artifact));
        when(worldRelease.requiredManifestAssetKeys()).thenReturn(List.of("world"));
        when(worldRelease.commandDefinitions()).thenReturn(List.of("look"));
        when(worldRelease.generationConfigRevision()).thenReturn("generation-1");
        when(worldRelease.participantDigests()).thenReturn(List.of(participant));
        when(launchBinding.releaseAttestation().publishedReleaseBundleRef())
            .thenReturn("original-release");
        when(launchBinding.releaseAttestation().publishWorkflowId()).thenReturn("publication");
        when(launchBinding.releaseAttestation().commitId()).thenReturn(commit.toString());
        when(launchBinding.releaseAttestation().manifestHash())
            .thenReturn("sha256:" + "3".repeat(64));
        when(policySet.requireValidDigest()).thenReturn(policySet);
        when(policySet.target().canonicalTenantId()).thenReturn(TENANT);
        when(policySet.target().canonicalVersionId()).thenReturn(VERSION);
        when(policySet.sourceCommitId()).thenReturn(commit);
        when(policySet.publishWorkflowId()).thenReturn("publication");
        when(policySet.publishedReleaseBundleRef()).thenReturn("original-release");
        when(policySet.publishedReleaseBundleDigest()).thenReturn("sha256:" + "1".repeat(64));
        when(policySet.manifestHash()).thenReturn("sha256:" + "3".repeat(64));
        when(policySet.versionNumber()).thenReturn(1);
        when(policySet.canonicalBytes())
            .thenReturn("controlled-sealed-set".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(policySet.operationBytes()).thenReturn(new byte[] {6});
        when(policySet.terminalEvidenceBytes()).thenReturn(new byte[] {7});
        when(policySet.policies()).thenReturn(List.of(policyEvidence));
        when(policyEvidence.policy()).thenReturn(policy);
        when(policyEvidence.policyDigest()).thenReturn("sha256:" + "c".repeat(64));
        when(policyEvidence.requireValidDigest(policySet)).thenReturn(policyEvidence);
        when(policyEvidence.sourceCommitId()).thenReturn(commit);
        when(policyEvidence.sourceRevisionId()).thenReturn(policyRevision);
        when(policyEvidence.logicalRevisionId()).thenReturn("policy-logical");
        publicationOperationCodec
            .when(() -> GameDesignPublicationOperationBinding.fromStored(any()))
            .thenReturn(publicationOperation);
        publicationTerminalCodec
            .when(() -> GameDesignPublicationTerminalEvidence.fromStored(any()))
            .thenReturn(publicationTerminal);
        when(publicationOperation.world().request().targetNamespace()).thenReturn(NAMESPACE);
        when(publicationOperation.world().canonicalBytes()).thenReturn(new byte[] {9});
        when(publicationOperation.account().input().selection().selectedCommit().digest())
            .thenReturn("sha256:" + "2".repeat(64));
        when(publicationTerminal.publishedReleaseBundleDigest())
            .thenReturn("sha256:" + "1".repeat(64));
        when(publicationTerminal.releaseContent().canonicalBytes()).thenReturn(new byte[] {8});
        when(publishedPolicies.listPublishedRealmEntryPolicies(TENANT, VERSION))
            .thenAnswer(
                invocation -> {
                  assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                  assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                      .isFalse();
                  return policySet;
                });
        when(gameDesign.read(any()))
            .thenAnswer(
                invocation -> {
                  assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                  return authored;
                });
        sourceCodec
            .when(() -> AuthoredInitialActorCoreReadGrpcCodec.toResponse(authored))
            .thenReturn(
                ReadAuthoredInitialActorCoreResponse.newBuilder()
                    .setSchemaVersion(1)
                    .setCanonicalResultBytes(ByteString.copyFrom(sourceBytes))
                    .build());
        when(world.read(any()))
            .thenAnswer(
                invocation -> {
                  assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                  when(lifecycle.request()).thenReturn(invocation.getArgument(0));
                  return lifecycle;
                });
        when(lifecycle.lifecycleStatus()).thenReturn("ACTIVE");
        when(lifecycle.lifecycleEpoch()).thenReturn(7L);
        when(lifecycle.launchBinding()).thenReturn(launchBinding);
        when(lifecycle.canonicalBytes())
            .thenReturn("world-original".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        service =
            new GameSessionCharacterCreatePublicSourceService(
                NAMESPACE,
                routes,
                catalogs,
                admissions,
                launches,
                retention,
                gameDesign,
                world,
                publishedPolicies);
      } catch (RuntimeException | Error failure) {
        close();
        throw failure;
      }
    }

    @Override
    public void close() {
      if (releaseContentCodec != null && !releaseContentCodec.isClosed())
        releaseContentCodec.close();
      if (publicationTerminalCodec != null && !publicationTerminalCodec.isClosed())
        publicationTerminalCodec.close();
      if (publicationOperationCodec != null && !publicationOperationCodec.isClosed())
        publicationOperationCodec.close();
      if (policyCodec != null && !policyCodec.isClosed()) policyCodec.close();
      if (sourceCodec != null && !sourceCodec.isClosed()) sourceCodec.close();
      if (associationCodec != null && !associationCodec.isClosed()) associationCodec.close();
    }
  }
}
