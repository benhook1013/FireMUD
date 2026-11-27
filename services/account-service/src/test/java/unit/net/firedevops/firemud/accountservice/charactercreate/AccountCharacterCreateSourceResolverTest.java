package net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreSourceBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Audience;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Visibility;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicCatalog;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicPointer;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Actual closed source/strict action codec proof with controlled principal, scope verification and
 * authenticated-transport capability mocks. The complete owner-shaped fixture is copied from the
 * native source-client test; this does not establish genuine publication, bootstrap or scope
 * custody.
 */
class AccountCharacterCreateSourceResolverTest {
  private static final UUID FIXTURE_ID = UUID.fromString("01111111-1111-4111-8111-111111111111");
  private static final String NAMESPACE = "test";
  private static final String OPAQUE_SCOPE = "Issued.Scope.Mixed_CASE-opaque";
  private final UUID account = UUID.randomUUID();
  private final AccountCharacterCreatePrincipal principal =
      mock(AccountCharacterCreatePrincipal.class);
  private final AccountCharacterCreateScopeService scopes =
      mock(AccountCharacterCreateScopeService.class);
  private final AccountCharacterCreatePublicSourceClient sources =
      mock(AccountCharacterCreatePublicSourceClient.class);
  private Fixture fixture;
  private CanonicalJoinScopeV2 scope;
  private AccountCharacterCreateSourceResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    fixture = fixture();
    scope = scope(fixture.request());
    resolver = new AccountCharacterCreateSourceResolver(NAMESPACE, scopes, sources);
    when(principal.authenticateCurrentAccount()).thenReturn(account);
    when(scopes.verify(principal, OPAQUE_SCOPE)).thenReturn(scope);
    when(sources.read(any()))
        .thenAnswer(
            invocation ->
                authenticated(
                    fixture.response().toBuilder()
                        .setRequest(
                            (ReadCharacterCreatePublicSourceRequest) invocation.getArgument(0))
                        .build()));
  }

  @Test
  void buildsStrictActionFromEntireActualSourceAndRetainsEveryOwnerByte() throws Exception {
    var resolved = resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE);
    var response =
        ReadCharacterCreatePublicSourceResponse.parseFrom(resolved.originalSourceReadback());
    var action = CharacterCreateOperatorActionCodec.decode(resolved.actionBytes());
    assertThat(CharacterCreateOperatorActionCodec.encode(action))
        .containsExactly(resolved.actionBytes());
    assertThat(resolved.originalSourceReadback()).containsExactly(response.toByteArray());
    assertThat(response.getAuthoredSource().toByteArray())
        .containsExactly(fixture.response().getAuthoredSource().toByteArray());
    assertThat(response.getWorldLifecycleEvidence())
        .isEqualTo(fixture.response().getWorldLifecycleEvidence());
    assertThat(response.getCatalog()).isEqualTo(fixture.response().getCatalog());
    assertThat(response.getPointer()).isEqualTo(fixture.response().getPointer());
    assertThat(action.target().accountId()).isEqualTo(account.toString());
    assertThat(action.expectedVersion().evaluatedAt())
        .isEqualTo(Instant.parse(scope.evaluatedAt()));
    assertThat(action.expectedVersion().connectScopeExpiresAt())
        .isEqualTo(Instant.parse(scope.connectScopeExpiresAt()));
    assertThat(action.expectedVersion().activeLifecycleEpoch()).isEqualTo("7");
    assertThat(action.mutation().canonicalCreationInput()).isEqualTo("{}");
    var projection = fixture.authored().projections().getFirst();
    var authored = action.mutation().authoredSource();
    assertThat(authored.bindingId()).isEqualTo(projection.bindingId().toString());
    assertThat(authored.bindingDigest()).isEqualTo(projection.bindingDigest());
    assertThat(authored.policy().canonicalPolicyJson())
        .isEqualTo(projection.policy().canonicalJson());
    assertThat(authored.core().canonicalJson()).isEqualTo(projection.core().canonicalJson());
    assertThat(authored.descriptor().canonicalJson())
        .isEqualTo(projection.descriptor().canonicalJson());
    assertThat(authored.disposition().definitionJson())
        .isEqualTo(projection.disposition().definitionJson());
    assertThat(authored.safeFeedback().definitionJson())
        .isEqualTo(projection.safeFeedback().definitionJson());
    assertThat(authored.disposition().revisionOrder())
        .isEqualTo(projection.disposition().revisionOrder());
    assertThat(authored.safeFeedback().revisionOrder())
        .isEqualTo(projection.safeFeedback().revisionOrder());
    var requested = ArgumentCaptor.forClass(ReadCharacterCreatePublicSourceRequest.class);
    verify(sources).read(requested.capture());
    var request = requested.getValue();
    assertThat(request.getReadRequestId()).isNotEqualTo(fixture.request().getReadRequestId());
    assertThat(request.toBuilder().setReadRequestId(fixture.request().getReadRequestId()).build())
        .isEqualTo(fixture.request());
    var order = org.mockito.Mockito.inOrder(principal, scopes, sources);
    order.verify(principal).authenticateCurrentAccount();
    order.verify(scopes).verify(principal, OPAQUE_SCOPE);
    order.verify(sources).read(request);
  }

  @Test
  void namespacePrincipalScopeAccountAndExpiryFailuresCannotReadSource() {
    assertThatThrownBy(() -> resolver.resolvePublicProduction("other", principal, OPAQUE_SCOPE))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> resolver.resolvePublicProduction(NAMESPACE, null, OPAQUE_SCOPE))
        .isInstanceOf(RuntimeException.class);
    when(principal.authenticateCurrentAccount()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(() -> resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE))
        .isInstanceOf(RuntimeException.class);
    when(principal.authenticateCurrentAccount()).thenReturn(account);
    var otherScope =
        new CanonicalJoinScopeV2(
            "other-opaque",
            account,
            scope.tenantId(),
            scope.realmId(),
            scope.tenantSlug(),
            scope.worldSlug(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt());
    when(scopes.verify(principal, OPAQUE_SCOPE)).thenReturn(otherScope);
    assertThatThrownBy(() -> resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE))
        .isInstanceOf(RuntimeException.class);
    var isolated =
        new CanonicalJoinScopeV2(
            OPAQUE_SCOPE,
            account,
            scope.tenantId(),
            scope.realmId(),
            scope.tenantSlug(),
            scope.worldSlug(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            "ISOLATED",
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt());
    when(scopes.verify(principal, OPAQUE_SCOPE)).thenReturn(isolated);
    assertThatThrownBy(() -> resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE))
        .isInstanceOf(RuntimeException.class);
    when(scopes.verify(principal, OPAQUE_SCOPE))
        .thenThrow(Status.PERMISSION_DENIED.withDescription("scope expired").asRuntimeException());
    assertThatThrownBy(() -> resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE))
        .hasMessageContaining("scope expired");
    verifyNoInteractions(sources);
  }

  @Test
  void everyIssuedSelectorAndOriginalTimeMustMatchTheActualSourceEcho() {
    for (var change :
        List.<UnaryOperator<ReadCharacterCreatePublicSourceRequest.Builder>>of(
            request -> request.setTargetNamespace("other"),
            request -> request.setReadRequestId(UUID.randomUUID().toString()),
            request -> request.setCanonicalTenantId(UUID.randomUUID().toString()),
            request -> request.setRealmId(UUID.randomUUID().toString()),
            request -> request.setWorldSlug("other"),
            request -> request.setRealmSlug("other"),
            request -> request.setPlayableStateNamespaceId(UUID.randomUUID().toString()),
            request -> request.setPlayableStateScope("ISOLATED"),
            request -> request.setCanonicalGameInstanceId(UUID.randomUUID().toString()),
            request -> request.setCatalogRevision(4),
            request -> request.setPointerVersion(6),
            request -> request.setEvaluatedAt("2026-10-24T00:00:01Z"),
            request -> request.setConnectScopeExpiresAt("2026-10-24T00:06:00Z"))) {
      doAnswer(
              invocation -> {
                ReadCharacterCreatePublicSourceRequest request = invocation.getArgument(0);
                return authenticated(
                    fixture.response().toBuilder()
                        .setRequest(change.apply(request.toBuilder()))
                        .build());
              })
          .when(sources)
          .read(any());
      assertThatThrownBy(() -> resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void completeOwnerSourcesMustRefusePolicyTargetEpochAndPublicationSubstitution() {
    var good = fixture.response();
    for (var changed :
        List.of(
            good.toBuilder()
                .setCatalog(good.getCatalog().toBuilder().setTenantSlug("other"))
                .build(),
            good.toBuilder()
                .setCatalog(good.getCatalog().toBuilder().setPublicProduction(false))
                .build(),
            good.toBuilder().setCatalog(good.getCatalog().toBuilder().setVisible(false)).build(),
            good.toBuilder()
                .setCatalog(good.getCatalog().toBuilder().setStateScope("ISOLATED"))
                .build(),
            good.toBuilder()
                .setCatalog(good.getCatalog().toBuilder().setCharacterCreationPolicy("PRESEEDED"))
                .build(),
            good.toBuilder()
                .setCatalog(good.getCatalog().toBuilder().setCatalogRevision(4))
                .build(),
            good.toBuilder().setPointer(good.getPointer().toBuilder().setPointerVersion(6)).build(),
            good.toBuilder()
                .setPointer(good.getPointer().toBuilder().setAdmissionState("CLOSED"))
                .build(),
            good.toBuilder().setCanonicalVersionId(UUID.randomUUID().toString()).build(),
            good.toBuilder().setActiveLifecycleEpoch(8).build(),
            good.toBuilder().setOriginalOwnerFence(9).build(),
            good.toBuilder().setOriginalOwnerAttemptId(UUID.randomUUID().toString()).build(),
            good.toBuilder().setOriginalControlPlaneRequestId("substituted").build(),
            good.toBuilder().clearAuthoredSource().build(),
            good.toBuilder()
                .setAuthoredSource(good.getAuthoredSource().toBuilder().clearProjections())
                .build(),
            good.toBuilder()
                .setAuthoredSource(
                    good.getAuthoredSource().toBuilder()
                        .addProjections(good.getAuthoredSource().getProjections(0)))
                .build(),
            good.toBuilder().setWorldLifecycleEvidence(ByteString.copyFromUtf8("{}")).build(),
            good.toBuilder().setUnknownFields(unknown()).build())) {
      doAnswer(
              invocation ->
                  authenticated(
                      changed.toBuilder()
                          .setRequest(
                              (ReadCharacterCreatePublicSourceRequest) invocation.getArgument(0))
                          .build()))
          .when(sources)
          .read(any());
      assertThatThrownBy(() -> resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void historicalRereadUsesExactOriginalRequestAndFullBytesWithoutScopeOrClockRefresh()
      throws Exception {
    var resolved = resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE);
    var intent = intent(resolved);
    var original =
        ReadCharacterCreatePublicSourceResponse.parseFrom(intent.originalSourceReadback());
    clearInvocations(principal, scopes, sources);

    resolver.requireExactOriginalSource(intent);

    verify(sources).read(original.getRequest());
    verifyNoInteractions(principal, scopes);
    // Metadata omitted by the action itself is still exact historical source authority.
    doReturn(
            authenticated(
                original.toBuilder()
                    .setCatalog(original.getCatalog().toBuilder().setReceiptDigest(digest('f')))
                    .build()))
        .when(sources)
        .read(original.getRequest());
    assertThatThrownBy(() -> resolver.requireExactOriginalSource(intent))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void malformedNoncanonicalAndReboundRetainedSourceFailsBeforeReread() throws Exception {
    var resolved = resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE);
    var original =
        ReadCharacterCreatePublicSourceResponse.parseFrom(resolved.originalSourceReadback());
    clearInvocations(sources);
    // Duplicate schema tag is parseable, but its closed roundtrip differs.
    byte[] duplicate =
        Arrays.copyOf(
            resolved.originalSourceReadback(), resolved.originalSourceReadback().length + 2);
    duplicate[duplicate.length - 2] = 8;
    duplicate[duplicate.length - 1] = 1;
    for (byte[] invalid :
        List.of(
            new byte[] {1},
            duplicate,
            original.toBuilder().setUnknownFields(unknown()).build().toByteArray(),
            original.toBuilder().setActiveLifecycleEpoch(8).build().toByteArray())) {
      var changed =
          new AccountCharacterCreateIntent(
              UUID.randomUUID(),
              UUID.randomUUID(),
              OPAQUE_SCOPE,
              account,
              resolved.actionBytes(),
              CharacterCreateOperatorActionCodec.mutationDigest(resolved.actionBytes()),
              invalid);
      assertThatThrownBy(() -> resolver.requireExactOriginalSource(changed))
          .isInstanceOf(RuntimeException.class);
    }
    verifyNoInteractions(sources);
  }

  @Test
  void unavailableOwnerAndSourceOnlyDelegationRemainFailClosed() {
    doThrow(Status.UNAVAILABLE.asRuntimeException()).when(sources).read(any());
    assertThatThrownBy(() -> resolver.resolvePublicProduction(NAMESPACE, principal, OPAQUE_SCOPE))
        .hasMessageContaining("UNAVAILABLE");
    clearInvocations(sources);
    assertThatThrownBy(() -> resolver.delegateProtected(null, null))
        .hasMessageContaining("UNAVAILABLE");
    verifyNoInteractions(sources);
  }

  private AccountCharacterCreateIntent intent(
      AccountCharacterCreateOwnerClients.ResolvedSource source) {
    return new AccountCharacterCreateIntent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        OPAQUE_SCOPE,
        account,
        source.actionBytes(),
        CharacterCreateOperatorActionCodec.mutationDigest(source.actionBytes()),
        source.originalSourceReadback());
  }

  private CanonicalJoinScopeV2 scope(ReadCharacterCreatePublicSourceRequest request) {
    return new CanonicalJoinScopeV2(
        OPAQUE_SCOPE,
        account,
        UUID.fromString(request.getCanonicalTenantId()),
        UUID.fromString(request.getRealmId()),
        fixture.response().getCatalog().getTenantSlug(),
        request.getWorldSlug(),
        request.getRealmSlug(),
        UUID.fromString(request.getPlayableStateNamespaceId()),
        request.getPlayableStateScope(),
        UUID.fromString(request.getCanonicalGameInstanceId()),
        request.getCatalogRevision(),
        request.getPointerVersion(),
        request.getEvaluatedAt(),
        request.getConnectScopeExpiresAt());
  }

  private static AccountCharacterCreatePublicSourceClient.AuthenticatedSource authenticated(
      ReadCharacterCreatePublicSourceResponse response) {
    var capability = mock(AccountCharacterCreatePublicSourceClient.AuthenticatedSource.class);
    when(capability.response()).thenReturn(response);
    return capability;
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private record Fixture(
      ReadCharacterCreatePublicSourceRequest request,
      ReadCharacterCreatePublicSourceResponse response,
      AuthoredInitialActorCoreReadEvidence.ReadResult authored,
      WorldCanonicalInstanceLifecycleEvidence world) {}

  // Common's authored-core fixture lives in its private unit-test source and is not exported.
  // Build only the owner-shaped values needed here, using the exported World fixture and real
  // codecs.
  private static Fixture fixture() throws Exception {
    return fixture("canonical-instance-launch-descriptor");
  }

  private static Fixture fixture(String descriptorId) throws Exception {
    var originalWorld = AuthoringFixtures.lifecycleEvidence("ACTIVE", 7L);
    var originalDescriptor = originalWorld.launchBinding().descriptor();
    var selector = originalWorld.launchBinding().releaseAttestation().worldStartLocationEvidence();
    var tenant = originalWorld.request().canonicalTenantId();
    var version = originalWorld.request().canonicalVersionId();
    var intakeId = UUID.randomUUID();
    var operationId = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, intakeId, tenant, "tenant-key", "synthetic-world", "Synthetic World");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            intakeId,
            originalDescriptor.authoredWorldSourceOperationId(),
            requestDigest,
            tenant,
            "tenant-key",
            "synthetic-world",
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            intakeId,
            originalDescriptor.authoredWorldSourceOperationId(),
            requestDigest,
            tenant,
            "tenant-key",
            "synthetic-world",
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW",
            evidenceDigest);
    var intake =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1,
            NAMESPACE,
            intakeId,
            tenant,
            "synthetic-world",
            source.operationId(),
            evidenceDigest);
    var receipt =
        new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
            1,
            NAMESPACE,
            intakeId,
            operationId,
            tenant,
            "synthetic-world",
            source.operationId(),
            evidenceDigest,
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(intake),
            digest('e'),
            source);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "world-lifecycle-control-request",
            tenant,
            "synthetic-world",
            source.operationId(),
            evidenceDigest,
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            descriptorId,
            42L,
            false,
            null,
            "{}",
            originalDescriptor.generationConfigRevision(),
            9L,
            7L,
            originalDescriptor.publishedReleaseBundleRef(),
            false,
            null);
    var oldRelease = originalWorld.launchBinding().releaseAttestation();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            tenant,
            version,
            descriptor.worldSlug(),
            source.operationId(),
            evidenceDigest,
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            oldRelease.publishWorkflowId(),
            oldRelease.commitId(),
            oldRelease.participantDigests(),
            oldRelease.manifestHash(),
            oldRelease.manifestSchemaVersion(),
            oldRelease.requiredManifestAssetKeys(),
            oldRelease.artifactDigests(),
            oldRelease.commandDefinitions(),
            oldRelease.generationConfigRevision(),
            selector);
    var lifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            1,
            FIXTURE_ID,
            NAMESPACE,
            tenant,
            descriptor.worldSlug(),
            originalWorld.request().canonicalGameInstanceId(),
            originalWorld.request().playableStateNamespaceId(),
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            version,
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var world =
        new WorldCanonicalInstanceLifecycleEvidence(
            lifecycleRequest,
            new CompleteLaunchBindingEvidence(descriptor, release),
            originalWorld.startLocation(),
            originalWorld.runtimeRoomInstanceId(),
            "ACTIVE",
            7L,
            originalWorld.rowVersion(),
            originalWorld.captureId(),
            originalWorld.graphSha256(),
            originalWorld.preparationInputDigest(),
            originalWorld.operationalRegionAssignments());
    var association =
        new StartSessionTemplateAssociationReadEvidence.Association(
            tenant,
            19L,
            version,
            UUID.fromString(selector.request().appliedCommitId()),
            selector.request().publishWorkflowId(),
            digest('c'),
            digest('a'),
            NAMESPACE,
            intakeId,
            operationId,
            source.operationId(),
            descriptor.worldSlug(),
            evidenceDigest,
            new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                1, NAMESPACE, FIXTURE_ID, intakeId, tenant),
            receipt);
    var readRequest =
        new StartSessionTemplateAssociationReadEvidence.Request(
            1,
            NAMESPACE,
            FIXTURE_ID,
            postTuple(tenant, NAMESPACE).canonicalBytes(),
            FIXTURE_ID,
            8L,
            new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                version,
                association.selectedCommitId(),
                association.publishWorkflowId(),
                association.associationDigest()));
    var bundle =
        PublishedReleaseBundle.newBuilder()
            .setId(7L)
            .setVersionId(42L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v2")
            .setPublishWorkflowId(release.publishWorkflowId())
            .setManifestHash(release.manifestHash())
            .setManifestSchemaVersion(release.manifestSchemaVersion())
            .addAllRequiredManifestAssetKeys(release.requiredManifestAssetKeys())
            .setGenerationConfigRevision(release.generationConfigRevision())
            .setPublishedReleaseBundleRef(release.publishedReleaseBundleRef())
            .setCanonicalTenantId(tenant.toString())
            .setCanonicalVersionId(version.toString())
            .addAllCommandDefinitions(release.commandDefinitions());
    for (var participant : release.participantDigests()) {
      var item =
          ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(participant.appliedCommitId())
              .setContentDigest(participant.contentDigest())
              .setDigestSchemaVersion(participant.digestSchemaVersion());
      if (participant.abilitySchemaDigestPresent())
        item.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      bundle.addParticipantDigests(item);
    }
    var authored =
        new AuthoredInitialActorCoreReadEvidence.ReadResult(
            readRequest,
            new StartSessionTemplateAssociationReadEvidence.Result(
                readRequest, association, bundle.build(), selector, 23L),
            descriptor,
            List.of(
                AuthoredInitialActorCoreProjection.fromValidatedBinding(
                    FIXTURE_ID, sourceBinding(association, association.sourceOperationId()))));
    var request =
        ReadCharacterCreatePublicSourceRequest.newBuilder()
            .setSchemaVersion(1)
            .setReadRequestId(FIXTURE_ID.toString())
            .setTargetNamespace(NAMESPACE)
            .setCanonicalTenantId(tenant.toString())
            .setRealmId(FIXTURE_ID.toString())
            .setWorldSlug(descriptor.worldSlug())
            .setRealmSlug("realm")
            .setPlayableStateNamespaceId(lifecycleRequest.playableStateNamespaceId().toString())
            .setPlayableStateScope("SHARED")
            .setCanonicalGameInstanceId(lifecycleRequest.canonicalGameInstanceId().toString())
            .setCatalogRevision(3L)
            .setPointerVersion(5L)
            .setEvaluatedAt("2026-10-24T00:00:00Z")
            .setConnectScopeExpiresAt("2026-10-24T00:05:00Z")
            .build();
    var response =
        ReadCharacterCreatePublicSourceResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(request)
            .setCatalog(
                CharacterCreatePublicCatalog.newBuilder()
                    .setTenantId(tenant.toString())
                    .setTenantSlug("tenant")
                    .setWorldSlug(request.getWorldSlug())
                    .setWorldDisplayName("Synthetic World")
                    .setRealmId(request.getRealmId())
                    .setRealmSlug("realm")
                    .setRealmDisplayName("Realm")
                    .setVisible(true)
                    .setPublicProduction(true)
                    .setStateScope("SHARED")
                    .setPlayableStateNamespaceId(request.getPlayableStateNamespaceId())
                    .setCharacterCreationPolicy("PLAYER_CREATED")
                    .setCatalogRevision(3L)
                    .setCreationRequestId(FIXTURE_ID.toString())
                    .setReceiptDigest(digest('a')))
            .setPointer(
                CharacterCreatePublicPointer.newBuilder()
                    .setTenantId(tenant.toString())
                    .setRealmId(request.getRealmId())
                    .setWorldSlug(request.getWorldSlug())
                    .setRealmSlug("realm")
                    .setAdmissionState("OPEN")
                    .setAdmissibleGameInstanceId(request.getCanonicalGameInstanceId())
                    .setPointerVersion(5L)
                    .setCatalogRevision(3L)
                    .setUpdatedAt("2026-10-24T00:00:00.123456789Z")
                    .setInitialAdmissionRequestId("initial-admission")
                    .setInitialAdmissionOwnerProofDigest(digest('b'))
                    .setAdmissionPointerSnapshotDigest("c".repeat(64)))
            .setCanonicalVersionId(version.toString())
            .setActiveLifecycleEpoch(7L)
            .setOriginalControlPlaneRequestId(descriptor.controlPlaneRequestId())
            .setOriginalOwnerAttemptId(FIXTURE_ID.toString())
            .setOriginalOwnerFence(8L)
            .setAuthoredSource(AuthoredInitialActorCoreReadGrpcCodec.toResponse(authored))
            .setWorldLifecycleEvidence(ByteString.copyFrom(world.canonicalBytes()))
            .build();
    return new Fixture(request, response, authored, world);
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(
      UUID tenant, String namespace) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "world-lifecycle-control-request",
            FIXTURE_ID,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenant, namespace),
                new StartSessionOperatorAction.Target(19L, FIXTURE_ID),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact original selected authored actor core"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        FIXTURE_ID,
        19L,
        authorityBundle(pre, tenant, namespace),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple, UUID tenant, String namespace) {
    String tenantText = tenant.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantText, "targetNamespace", namespace),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", FIXTURE_ID.toString(),
                "applicableTenantId", tenantText),
            "accountProjectionEvidence",
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", digest('a'),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId", FIXTURE_ID.toString(),
                "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind",
                        "controlPlaneRequestId",
                        "requestId",
                        tuple.controlPlaneRequestId()),
                "mutationDigest", tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenantText, 3L),
                "membershipAuthorityGeneration", Map.of(tenantText, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenantText, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                FIXTURE_ID.toString(),
                "controlUiTokenJti",
                FIXTURE_ID.toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static AuthoredInitialActorCoreSourceBinding sourceBinding(
      StartSessionTemplateAssociationReadEvidence.Association association,
      UUID worldSourceOperationId) {
    var policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":3,\"worldSlug\":\""
                + association.worldSlug()
                + "\",\"worldDisplayName\":\"Synthetic World\","
                + "\"realmSlug\":\"realm\",\"realmDisplayName\":\"Realm\",\"visible\":true,"
                + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                + "\"entryPolicy\":\"PLAYER_CREATED\",\"creationDescriptor\":{"
                + "\"schemaVersion\":1,\"descriptorId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\","
                + "\"descriptorRevisionId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\","
                + "\"inputKind\":\"EMPTY_OBJECT\",\"dependencies\":[]},"
                + "\"initialActorCore\":{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}}",
            tools.jackson.databind.json.JsonMapper.builder().build());
    var policyPayload =
        RealmPolicySource.canonical(
            Map.of(
                "revisionKind",
                RealmPolicySource.REVISION_KIND,
                "logicalRevisionId",
                "policy-logical-r1",
                "policy",
                RealmPolicySource.tree(policy.canonicalJson())));
    var disposition = new Disposition("newcomer", List.of(), "welcome");
    var feedback =
        new Feedback(
            "welcome",
            "actor_created",
            Audience.SOURCE_ACTOR,
            "welcome_message",
            "Welcome, traveler.",
            List.of(),
            Visibility.PUBLIC,
            GameplayRuleManifest.ReplayPolicy.EFFECT_IDEMPOTENT);
    List<RevisionPayload> revisions =
        List.of(
            new RevisionPayload(
                "0",
                UUID.fromString("13333333-3333-4333-8333-333333333333"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                policyPayload),
            new RevisionPayload(
                "1",
                UUID.fromString("14444444-4444-4444-8444-444444444444"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(disposition)),
            new RevisionPayload(
                "2",
                UUID.fromString("15555555-5555-4555-8555-555555555555"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(feedback)));
    TargetProof target =
        new TargetProof(
            association.canonicalTenantId(),
            association.canonicalVersionId(),
            23L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    DraftCommitBinding commit =
        DraftCommitBinding.create(
            target,
            UUID.fromString("1eeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            association.selectedCommitId(),
            "base-0",
            revisions,
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    RealmPolicySource.SCOPE,
                    association.canonicalVersionId().toString(),
                    RealmPolicySource.SCOPE,
                    "effective",
                    "0"),
                new AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSource.SCOPE,
                    association.canonicalVersionId().toString(),
                    GameplayRuleSource.SCOPE,
                    GameplayRuleSource.SCOPE_ID,
                    "0")));
    var selectedPolicy = RealmPolicySource.revision(commit, revisions.getFirst());
    var policySnapshot = new RealmPolicySnapshot(commit, "1", List.of(selectedPolicy));
    var dispositionEntry =
        new GameplayRuleSource.Entry(
            Family.DISPOSITIONS,
            disposition,
            commit,
            "1",
            UUID.fromString("14444444-4444-4444-8444-444444444444"));
    var feedbackEntry =
        new GameplayRuleSource.Entry(
            Family.FEEDBACK,
            feedback,
            commit,
            "2",
            UUID.fromString("15555555-5555-4555-8555-555555555555"));
    var gameplay =
        new GameplayRuleSelectedSource(
            gameplaySnapshot(commit, List.of(dispositionEntry, feedbackEntry)));
    return AuthoredInitialActorCoreSourceBinding.resolveAll(
            worldSourceOperationId,
            association.sourceEvidenceDigest(),
            association.worldSlug(),
            association.publishWorkflowId(),
            bytes("selected publication operation bytes"),
            bytes("selected release terminal evidence"),
            commit,
            policySnapshot,
            gameplay)
        .getFirst();
  }

  private static String gameplaySnapshot(
      DraftCommitBinding binding, List<GameplayRuleSource.Entry> entries) {
    Map<String, Object> families = new java.util.LinkedHashMap<>();
    for (Family family : Family.values()) {
      families.put(
          family.name(),
          entries.stream()
              .filter(value -> value.family() == family)
              .map(GameplayRuleSource.Entry::definition)
              .toList());
    }
    var manifest =
        GameplayRuleManifest.fromStored(
            GameplayRuleManifest.canonical(
                Map.of("schema", GameplayRuleManifest.SCHEMA, "families", families)));
    Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
    snapshot.put("schema", "game-design-gameplay-rule-source-snapshot/v1");
    snapshot.put("bindingJson", binding.canonicalJson());
    snapshot.put("bindingDigest", binding.digest());
    snapshot.put("sourceEpoch", "1");
    snapshot.put("inheritedCommitId", "");
    snapshot.put("genesisReceiptId", "77777777-7777-4777-8777-777777777777");
    snapshot.put("manifestJson", manifest.canonicalJson());
    snapshot.put(
        "entries",
        entries.stream()
            .map(
                entry ->
                    Map.of(
                        "family", entry.family().name(),
                        "definitionJson", GameplayRuleManifest.canonical(entry.definition()),
                        "sourceBindingJson", entry.sourceBinding().canonicalJson(),
                        "sourceBindingDigest", entry.sourceBinding().digest(),
                        "revisionOrder", entry.revisionOrder(),
                        "revisionId", entry.revisionId().toString()))
            .toList());
    return CommandSource.canonical(snapshot);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
