package net.firedevops.firemud.common.testing;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.publication.PlayerCreationDescriptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/**
 * Controlled complete CHARACTER_CREATE values for transport proofs only. Foreign protection and
 * source-readback bytes are stipulated fixtures; this class grants no authenticated owner custody.
 */
public final class ControlledPlayerCreatedRosterTerminalFixtures {
  private ControlledPlayerCreatedRosterTerminalFixtures() {}

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "All retained generated protobuf messages are immutable.")
  public record Bundle(
      GetCanonicalGameplayRosterReferenceRequest originalRequest,
      GetCanonicalGameplayRosterReferenceResponse original,
      CanonicalSelectedPlayerCreatedCharacterResponse selected,
      CanonicalGameplayRosterRetirementAcknowledgementRequest acknowledgementRequest,
      CanonicalGameplayRosterRetirementAcknowledgementResponse acknowledgement,
      CanonicalGameplayRosterRetirementConfirmationRequest confirmation) {
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "All retained generated protobuf messages are immutable.")
    public Bundle {}
  }

  /** One real canonical action/terminal with a controlled 5 MiB opaque Account protection. */
  public static Bundle withFiveMebibyteAccountProtection(String namespace) {
    var action = action(namespace);
    var target = target(action);
    var source = source(action);
    var account = action.target().accountId();
    var operation = uuid(117);
    var character = uuid(2);
    var context =
        PlayerExecutionContext.newBuilder()
            .setRequestId(operation)
            .setAccountId(account)
            .setSessionId(uuid(118))
            .setTenantId(target.getTenantUuid())
            .setRealmId(target.getRealmUuid())
            .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
            .setPlayableStateScope("SHARED")
            .setGameInstanceId(target.getGameInstanceUuid())
            .build();
    var rosterRequest =
        CanonicalPlayerCreatedRosterRequest.newBuilder()
            .setRequestUuid(operation)
            .setCanonicalAccountUuid(account)
            .setExpectedTarget(target)
            .setExpectedSource(source)
            .setPlayerExecutionContext(context)
            .build();
    var actors =
        List.of(
            CanonicalGameplayRosterActor.newBuilder()
                .setCharacterUuid(character)
                .setDisplayName("Persisted created actor")
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .build());
    var digest =
        CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(account, target, source, actors);
    var roster =
        CanonicalPlayerCreatedRosterEvidence.requireResponse(
            rosterRequest,
            CanonicalPlayerCreatedRosterResponse.newBuilder()
                .setSourceIdentity(source)
                .setRoster(
                    CanonicalGameplayRosterResponse.newBuilder()
                        .setCanonicalAccountUuid(account)
                        .setTarget(target)
                        .setSnapshotUuid(uuid(4))
                        .setSnapshotDigest(digest)
                        .addAllActors(actors))
                .build());
    var selectedRequest =
        CanonicalSelectedPlayerCreatedCharacterRequest.newBuilder()
            .setRequestUuid(operation)
            .setCanonicalAccountUuid(account)
            .setSelectedCharacterUuid(character)
            .setExpectedTarget(target)
            .setExpectedSource(source)
            .setExpectedSnapshot(
                CanonicalGameplayRosterSnapshotReference.newBuilder()
                    .setSnapshotUuid(uuid(4))
                    .setSnapshotDigest(digest))
            .setPlayerExecutionContext(context.toBuilder().setCharacterId(character))
            .build();
    var selected =
        CanonicalPlayerCreatedRosterEvidence.requireSelectedResponse(
            selectedRequest, selectedCreatedResponse(action, target, source, selectedRequest));
    var originalRequest =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.fromString(operation),
            UUID.fromString(account),
            UUID.fromString(uuid(118)),
            namespace,
            target,
            source);
    var original =
        CanonicalGameplayRosterReferenceEvidence.requireResponse(
            originalRequest,
            CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                    originalRequest, 1L, UUID.fromString(uuid(130)), roster)
                .toBuilder()
                .setSelectionInput(
                    CanonicalGameplayRosterSelectionInput.newBuilder()
                        .setSchemaVersion(1)
                        .setSelector("1"))
                .setSelectedCharacterUuid(character)
                .setCanonicalSelectedPlayerCreatedCharacterReceipt(selected.toByteString())
                .setTerminalOutcome("SELECTED")
                .build());
    var retired =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            original.toBuilder()
                .setState(
                    CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
                .setRetirementUuid(uuid(131))
                .setSupersedingPointerDigest("e".repeat(64))
                .setSupersedingPointerVersion(target.getPointerVersion() + 1)
                .setSupersedingCatalogRevision(target.getCatalogRevision())
                .setSupersedingWorldEpoch(target.getActiveWorldEpoch())
                .build());
    var request =
        CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
            CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
                .setSchemaVersion(1)
                .setOriginalSelection(originalRequest)
                .setObservedRetirement(retired)
                .setObservationUuid(uuid(132))
                .setAdmissionUuid(uuid(133))
                .setSnapshotUuid(uuid(4))
                .setSnapshotDigest(digest)
                .setMinimumRetentionMillis(1000)
                .build());
    var acknowledgement =
        CanonicalGameplayRosterRetirementEvidence.response(
            request, UUID.fromString(uuid(134)), 1000);
    return new Bundle(
        originalRequest,
        original,
        selected,
        request,
        acknowledgement,
        CanonicalGameplayRosterRetirementEvidence.confirmation(request, acknowledgement));
  }

  private static CanonicalGameplayRosterTarget target(CharacterCreateOperatorAction action) {
    var target = action.target();
    var expected = action.expectedVersion();
    long catalogRevision = Long.parseLong(expected.catalogRevision());
    long pointerVersion = Long.parseLong(expected.pointerVersion());
    long worldEpoch = Long.parseLong(expected.activeLifecycleEpoch());
    return CanonicalGameplayRosterTarget.newBuilder()
        .setTenantUuid(action.scope().tenantId())
        .setRealmUuid(target.realmId())
        .setWorldSlug(target.worldSlug())
        .setRealmSlug(target.realmSlug())
        .setGameInstanceUuid(target.gameInstanceId())
        .setCatalogRevision(catalogRevision)
        .setPublishedPolicyDigest("e".repeat(64))
        .setPublishedReleaseBundleRef("release/controlled-player-created")
        .setAdmissionPointerSnapshotDigest("b".repeat(64))
        .setPublishedOwnerProofDigest("c".repeat(64))
        .setPlayableStateNamespaceUuid(action.scope().playableStateNamespaceId())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setEntryPolicy(
            CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
        .setCanonicalVersionUuid(target.versionId())
        .setPointerVersion(pointerVersion)
        .setActiveWorldEpoch(worldEpoch)
        .build();
  }

  private static CanonicalPlayerCreatedRosterSourceIdentity source(
      CharacterCreateOperatorAction action) {
    var authored = action.mutation().authoredSource();
    PlayerCreationDescriptor descriptor =
        PlayerCreationDescriptor.parse(authored.descriptor().canonicalJson());
    return CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
        .setSchemaVersion(1)
        .setDescriptorUuid(descriptor.descriptorId())
        .setDescriptorRevisionUuid(descriptor.descriptorRevisionId())
        .setDescriptorDigest(descriptor.digest())
        .setAuthoredBindingUuid(authored.bindingId())
        .setAuthoredBindingDigest(authored.bindingDigest())
        .build();
  }

  private static CanonicalSelectedPlayerCreatedCharacterResponse selectedCreatedResponse(
      CharacterCreateOperatorAction action,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source,
      CanonicalSelectedPlayerCreatedCharacterRequest request) {
    byte[] originalAction = CharacterCreateOperatorActionCodec.encode(action);
    String mutationDigest = CharacterCreateOperatorActionCodec.mutationDigest(originalAction);
    ReadOriginalCharacterCreateRequest originalRequest =
        ReadOriginalCharacterCreateRequest.newBuilder()
            .setTargetNamespace(action.scope().targetNamespace())
            .setOperationId(uuid(120))
            .setMutationDigest(mutationDigest)
            .build();
    CommitProtectedCharacterCreateRequest terminalRequest =
        CommitProtectedCharacterCreateRequest.newBuilder()
            .setOriginal(originalRequest)
            .setAccountProtection(protectionReference(121))
            .setGameSessionProtection(protectionReference(122))
            .setWorldProtection(protectionReference(123))
            .build();
    ReadOriginalCharacterCreateResponse accountOriginal =
        ReadOriginalCharacterCreateResponse.newBuilder()
            .setTargetNamespace(action.scope().targetNamespace())
            .setOperationId(uuid(120))
            .setCreateCharacterRequestId(uuid(124))
            .setConnectScopeId("controlled-connect-scope")
            .setAuthenticatedAccountId(action.target().accountId())
            .setPurpose("CHARACTER_CREATE")
            .setOriginalAction(ByteString.copyFrom(originalAction))
            // This opaque fixture is preserved as bytes and is not treated as remote authority.
            .setOriginalSourceReadback(ByteString.copyFromUtf8("controlled-source-readback"))
            .setMutationDigest(mutationDigest)
            .build();
    EntityCharacterCreateTerminal terminal =
        EntityCharacterCreateTerminal.newBuilder()
            .setOriginalRequest(terminalRequest)
            .setAccountOriginal(accountOriginal)
            .setOriginalAccountProtection(ByteString.copyFrom(new byte[5 * 1024 * 1024]))
            .setOriginalGameSessionProtection(ByteString.copyFromUtf8("game-session-protection"))
            .setOriginalWorldProtection(ByteString.copyFromUtf8("world-protection"))
            .setEntityIntakeRequestId(uuid(125))
            .setOutcome(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED)
            .setCharacterId(request.getSelectedCharacterUuid())
            .setTerminalId(uuid(126))
            .setTerminalFence(1)
            .setCompletedAt("2026-10-23T00:00:00Z")
            .setEntitySourceReceiptDigest("sha256:" + "d".repeat(64))
            .build();
    return CanonicalSelectedPlayerCreatedCharacterResponse.newBuilder()
        .setRequestUuid(request.getRequestUuid())
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setSelectedCharacterUuid(request.getSelectedCharacterUuid())
        .setTarget(target)
        .setSnapshot(request.getExpectedSnapshot())
        .setSourceIdentity(source)
        .setCreationOperationUuid(uuid(120))
        .setMutationDigest(mutationDigest)
        .setTerminal(terminal)
        .build();
  }

  private static CharacterCreateProtectionReference protectionReference(int value) {
    return CharacterCreateProtectionReference.newBuilder()
        .setProtectionId(uuid(value))
        .setProtectionFence(1)
        .build();
  }

  // Same complete base-public-player-created action values as the Common codec vector.
  private static CharacterCreateOperatorAction action(String namespace) {
    return new CharacterCreateOperatorAction(
        new CharacterCreateOperatorAction.Scope(
            namespace,
            "a1000000-0000-4000-8000-000000000001",
            "a3000000-0000-4000-8000-000000000001",
            CharacterCreateOperatorAction.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
        new CharacterCreateOperatorAction.Target(
            "a4000000-0000-4000-8000-000000000001",
            "a2000000-0000-4000-8000-000000000001",
            "ember-vale",
            "salt-harbor",
            "a5000000-0000-4000-8000-000000000001",
            "a6000000-0000-4000-8000-000000000001"),
        new CharacterCreateOperatorAction.ExpectedVersion(
            "17",
            "23",
            "31",
            Instant.parse("2026-10-11T12:34:56.123456789Z"),
            Instant.parse("2026-10-11T12:39:56.987654321Z")),
        new CharacterCreateOperatorAction.Mutation(
            "CHARACTER_CREATE",
            "PLAYER_CREATED",
            new CharacterCreateOperatorAction.Catalog(
                "a1000000-0000-4000-8000-000000000001",
                "tideward",
                "ember-vale",
                "Ember Reach",
                "a2000000-0000-4000-8000-000000000001",
                "salt-harbor",
                "Salt Harbor",
                true,
                true,
                "SHARED",
                "a3000000-0000-4000-8000-000000000001",
                "PLAYER_CREATED",
                "17"),
            new CharacterCreateOperatorAction.Pointer(
                "a1000000-0000-4000-8000-000000000001",
                "a2000000-0000-4000-8000-000000000001",
                "ember-vale",
                "salt-harbor",
                "OPEN",
                "a5000000-0000-4000-8000-000000000001",
                "23",
                "17",
                Instant.parse("2026-10-11T12:30:01.012345678Z")),
            new CharacterCreateOperatorAction.AuthoredSource(
                "game-design-authored-initial-actor-core-projection/v1",
                "a7000000-0000-4000-8000-000000000001",
                "sha256:e70f02bed77058455e908732d7b33617827b6e328468913bf107d990e26afb4a",
                "a1000000-0000-4000-8000-000000000001",
                "a6000000-0000-4000-8000-000000000001",
                "a8000000-0000-4000-8000-000000000001",
                "sha256:01e3d2f5ca1c0f6c6a17db5546729a7e4971e5b4972602b81f0556f2e5462263",
                "ember-vale",
                "published-ember-workflow-2026-10-11",
                "a9000000-0000-4000-8000-000000000001",
                "sha256:e4da1526c7e4896ea8a951fb8e0bc9af6306d0a2ce8199eb877be5927c731c3a",
                new CharacterCreateOperatorAction.Policy(
                    "aa000000-0000-4000-8000-000000000001",
                    "ab000000-0000-4000-8000-000000000001",
                    "policy-logical-ember-r4",
                    "{\"creationDescriptor\":{\"dependencies\":[],\"descriptorId\":\"ac000000-0000-4000-8000-000000000001\",\"descriptorRevisionId\":\"ad000000-0000-4000-8000-000000000001\",\"inputKind\":\"EMPTY_OBJECT\",\"schemaVersion\":1},\"entryPolicy\":\"PLAYER_CREATED\",\"initialActorCore\":{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"},\"publicProduction\":true,\"realmDisplayName\":\"Salt Harbor\",\"realmSlug\":\"salt-harbor\",\"schemaVersion\":3,\"stateScope\":\"SHARED\",\"visible\":true,\"worldDisplayName\":\"Ember Reach\",\"worldSlug\":\"ember-vale\"}",
                    "sha256:39d36dc77fa62069acf4cb6dec2a9ded18e409039a24da46d326f297d9e889a1"),
                new CharacterCreateOperatorAction.Core(
                    "{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}"),
                new CharacterCreateOperatorAction.Descriptor(
                    "{\"dependencies\":[],\"descriptorId\":\"ac000000-0000-4000-8000-000000000001\",\"descriptorRevisionId\":\"ad000000-0000-4000-8000-000000000001\",\"inputKind\":\"EMPTY_OBJECT\",\"schemaVersion\":1}",
                    "sha256:80fec9b6669a9786a676c6877fbdd9497f0643bf947504866d3f5a0dfaaee415"),
                new CharacterCreateOperatorAction.Rule(
                    "newcomer",
                    "{\"deniedAdmissionTags\":[],\"dispositionKey\":\"newcomer\",\"safeFeedbackKey\":\"welcome\"}",
                    "sha256:aa17d1d6463a644913ec98c2137fb44acffcd6cc7c9786a6648fd0971826c41b",
                    "ae000000-0000-4000-8000-000000000001",
                    "sha256:dbaa3b603d70a2b0518d679b6b41bf7d3acec7027b0c77df5857f8b30c0ed1f2",
                    "af000000-0000-4000-8000-000000000001",
                    "41"),
                new CharacterCreateOperatorAction.Rule(
                    "welcome",
                    "{\"arguments\":[],\"audience\":\"SOURCE_ACTOR\",\"feedbackKey\":\"welcome\",\"messageKey\":\"welcome_message\",\"messageTemplate\":\"Welcome, traveler.\",\"outcomeEvent\":\"actor_created\",\"replayPolicy\":\"EFFECT_IDEMPOTENT\",\"visibility\":\"PUBLIC\"}",
                    "sha256:8cf61d25d83637199e3d3386e43906f888fe477c3deb8e685a0a2494debfe871",
                    "ae000000-0000-4000-8000-000000000001",
                    "sha256:dbaa3b603d70a2b0518d679b6b41bf7d3acec7027b0c77df5857f8b30c0ed1f2",
                    "b0000000-0000-4000-8000-000000000001",
                    "42")),
            "{}"),
        CharacterCreateOperatorAction.AuditReason.ABSENT);
  }

  private static String uuid(int value) {
    return new UUID(0L, value).toString();
  }
}
