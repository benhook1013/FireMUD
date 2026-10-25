package net.firedevops.firemud.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.AuthoredSource;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Catalog;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Core;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Descriptor;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.ExpectedVersion;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Mutation;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Pointer;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Policy;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Rule;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Scope;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Target;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.publication.PlayerCreationDescriptor;
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
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Pure controlled PLAYER_CREATED value tests; these do not establish authenticated creation proof.
 */
class CanonicalPlayerCreatedRosterEvidenceTest {
  private static final String VECTOR_RESOURCE = "/operator/character-create-v1-vectors.json";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ACCOUNT = uuid(1);
  private static final String CHARACTER_1 = uuid(2);
  private static final String CHARACTER_2 = uuid(3);
  private static final String SNAPSHOT = uuid(4);

  record SelectedFixture(
      CanonicalSelectedPlayerCreatedCharacterRequest request,
      CanonicalSelectedPlayerCreatedCharacterResponse response) {}

  private record Fixture(
      CharacterCreateOperatorAction action,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source,
      CanonicalSelectedPlayerCreatedCharacterRequest request,
      CanonicalSelectedPlayerCreatedCharacterResponse response) {}

  static SelectedFixture selectedFixture() {
    try {
      Fixture fixture = fixture(false);
      return new SelectedFixture(fixture.request(), fixture.response());
    } catch (IOException failure) {
      throw new IllegalStateException("Controlled CHARACTER_CREATE vector is unavailable", failure);
    }
  }

  @Test
  void acceptsEmptyRosterWithoutInventingAnActor() {
    CanonicalPlayerCreatedRosterRequest request = request(target(), source(), context(null));
    CanonicalPlayerCreatedRosterResponse response = response(request, List.of());

    assertEquals(response, CanonicalPlayerCreatedRosterEvidence.requireResponse(request, response));
  }

  @Test
  void digestBindsOneAndManyOrderedActors() {
    var one = List.of(actor(CHARACTER_1, "Mira"));
    var many = List.of(actor(CHARACTER_1, "Mira"), actor(CHARACTER_2, "Tāne"));

    String oneDigest =
        CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
            ACCOUNT, target(), source(), one);
    String manyDigest =
        CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
            ACCOUNT, target(), source(), many);
    String reversedDigest =
        CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
            ACCOUNT, target(), source(), List.of(many.get(1), many.get(0)));

    assertEquals(64, oneDigest.length());
    assertEquals(64, manyDigest.length());
    org.junit.jupiter.api.Assertions.assertNotEquals(oneDigest, manyDigest);
    org.junit.jupiter.api.Assertions.assertNotEquals(manyDigest, reversedDigest);
  }

  @Test
  void responseRejectsChangedSourceTargetAndSnapshotDigest() {
    CanonicalPlayerCreatedRosterRequest request = request(target(), source(), context(null));
    CanonicalPlayerCreatedRosterResponse valid =
        response(request, List.of(actor(CHARACTER_1, "Mira")));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireResponse(
                request, valid.toBuilder().setSourceIdentity(source(9)).build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireResponse(
                request,
                valid.toBuilder()
                    .setRoster(valid.getRoster().toBuilder().setTarget(target(9)))
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireResponse(
                request,
                valid.toBuilder()
                    .setRoster(valid.getRoster().toBuilder().setSnapshotDigest("0".repeat(64)))
                    .build()));
  }

  @Test
  void requestRejectsUnknownNilAndMalformedContextValues() {
    var request = request(target(), source(), context(null));
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireRequest(
                request.toBuilder().setRequestUuid(uuid(0)).build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireRequest(
                request.toBuilder().setUnknownFields(unknown).build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireRequest(
                request.toBuilder()
                    .setPlayerExecutionContext(
                        request.getPlayerExecutionContext().toBuilder().setPlayableStateScope("1"))
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireRequest(
                request.toBuilder()
                    .setExpectedTarget(
                        request.getExpectedTarget().toBuilder()
                            .setEntryPolicy(
                                CanonicalGameplayRosterEntryPolicy
                                    .CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY))
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireRequest(
                request.toBuilder()
                    .setPlayerExecutionContext(
                        request.getPlayerExecutionContext().toBuilder().setUnknownFields(unknown))
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(
                source().toBuilder().setDescriptorUuid(uuid(0)).build()));
  }

  @Test
  void rejectsDuplicateActorsAndMalformedSelectedContext() {
    var duplicateActors = List.of(actor(CHARACTER_1, "Mira"), actor(CHARACTER_1, "Mira"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
                ACCOUNT, target(), source(), duplicateActors));

    var selected = selectedRequest(context(CHARACTER_1));
    assertEquals(selected, CanonicalPlayerCreatedRosterEvidence.requireSelectedRequest(selected));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalPlayerCreatedRosterEvidence.requireSelectedRequest(
                selected.toBuilder()
                    .setPlayerExecutionContext(
                        selected.getPlayerExecutionContext().toBuilder()
                            .setCharacterId(CHARACTER_2))
                    .build()));
  }

  @Test
  void acceptsExactSelectedCreationReceiptAndReturnsTheOriginalImmutableResponse() {
    Fixture controlled = uncheckedFixture(false);
    assertNotEquals(
        rawDigest(controlled.action().mutation().authoredSource().policy().contentDigest()),
        controlled.target().getPublishedPolicyDigest());
    PlayerCreationDescriptor actualDescriptor =
        PlayerCreationDescriptor.parse(
            controlled.action().mutation().authoredSource().descriptor().canonicalJson());
    assertEquals(actualDescriptor.digest(), controlled.source().getDescriptorDigest());
    assertEquals(
        actualDescriptor.digest(), controlled.response().getSourceIdentity().getDescriptorDigest());
    SelectedFixture fixture = new SelectedFixture(controlled.request(), controlled.response());

    assertSame(
        fixture.response(),
        CanonicalPlayerCreatedRosterEvidence.requireSelectedResponse(
            fixture.request(), fixture.response()));
  }

  @Test
  void acceptsOriginalCreationAcrossRuntimeReplacement() throws IOException {
    Fixture fixture = fixture(true);

    assertEquals(
        fixture.response(),
        CanonicalPlayerCreatedRosterEvidence.requireSelectedResponse(
            fixture.request(), fixture.response()));
  }

  @Test
  void rejectsOuterSubstitutionsAndChangedCreationOperationDigestOrActor() {
    Fixture fixture = uncheckedFixture(false);
    var response = fixture.response();

    assertRejected(fixture.request(), response.toBuilder().setRequestUuid(uuid(101)).build());
    assertRejected(
        fixture.request(), response.toBuilder().setCanonicalAccountUuid(uuid(102)).build());
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTarget(response.getTarget().toBuilder().setPointerVersion(99))
            .build());
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setSnapshot(response.getSnapshot().toBuilder().setSnapshotDigest("f".repeat(64)))
            .build());
    assertRejected(
        fixture.request(), response.toBuilder().setCreationOperationUuid(uuid(103)).build());
    assertRejected(
        fixture.request(), response.toBuilder().setMutationDigest("f".repeat(64)).build());

    var changedActorRequest =
        selectedCreatedRequest(fixture.action(), fixture.target(), fixture.source(), uuid(104));
    assertRejected(
        changedActorRequest, response.toBuilder().setSelectedCharacterUuid(uuid(104)).build());
  }

  @Test
  void rejectsDescriptorBindingPolicyAndVersionSubstitution() throws IOException {
    Fixture fixture = fixture(false);
    var changedDescriptor = fixture.source().toBuilder().setDescriptorUuid(uuid(111)).build();
    assertRejectedWithSource(fixture, changedDescriptor);
    var changedBindingId = fixture.source().toBuilder().setAuthoredBindingUuid(uuid(112)).build();
    assertRejectedWithSource(fixture, changedBindingId);
    var changedBindingDigest =
        fixture.source().toBuilder().setAuthoredBindingDigest("sha256:" + "f".repeat(64)).build();
    assertRejectedWithSource(fixture, changedBindingDigest);

    CanonicalGameplayRosterTarget changedPolicy =
        fixture.target().toBuilder().setPublishedPolicyDigest("f".repeat(64)).build();
    assertRejected(
        fixture.request(), fixture.response().toBuilder().setTarget(changedPolicy).build());
    CanonicalGameplayRosterTarget changedVersion =
        fixture.target().toBuilder().setCanonicalVersionUuid(uuid(113)).build();
    assertRejectedWithTarget(fixture, changedVersion);
  }

  @Test
  void rejectsMalformedUnknownMissingAndAbortedTerminalValues() {
    Fixture fixture = uncheckedFixture(false);
    var response = fixture.response();
    var terminal = response.getTerminal();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertRejected(fixture.request(), response.toBuilder().setUnknownFields(unknown).build());
    assertRejected(
        fixture.request(),
        response.toBuilder().setTerminal(terminal.toBuilder().setUnknownFields(unknown)).build());
    assertRejected(fixture.request(), response.toBuilder().clearTerminal().build());
    assertRejected(
        fixture.request(),
        response.toBuilder().setTerminal(terminal.toBuilder().clearOriginalRequest()).build());
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(
                terminal.toBuilder()
                    .setOutcome(
                        EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED))
            .build());
    assertRejected(
        fixture.request(),
        response.toBuilder().setTerminal(terminal.toBuilder().setTerminalId("")).build());
    assertRejected(
        fixture.request(),
        response.toBuilder().setTerminal(terminal.toBuilder().setTerminalFence(0)).build());
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(terminal.toBuilder().setCompletedAt("not-an-instant"))
            .build());
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(terminal.toBuilder().setAbortRequestId(uuid(115)))
            .build());
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(terminal.toBuilder().setOriginalWorldProtection(ByteString.EMPTY))
            .build());

    var malformedAccountOriginal =
        terminal.getAccountOriginal().toBuilder()
            .setOriginalSourceReadback(ByteString.EMPTY)
            .build();
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(terminal.toBuilder().setAccountOriginal(malformedAccountOriginal))
            .build());
    var unknownReference =
        terminal.getOriginalRequest().getWorldProtection().toBuilder()
            .setUnknownFields(unknown)
            .build();
    var unknownOriginalRequest =
        terminal.getOriginalRequest().toBuilder().setWorldProtection(unknownReference).build();
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(terminal.toBuilder().setOriginalRequest(unknownOriginalRequest))
            .build());
  }

  @Test
  void enforcesOriginalSourceAndProtectionPurposeBoundsWithoutLargeByteArrays() {
    Fixture fixture = uncheckedFixture(false);
    var response = fixture.response();
    var terminal = response.getTerminal();
    var tooLargeSourceReadback =
        terminal.getAccountOriginal().toBuilder()
            .setOriginalSourceReadback(bytesWithSize(32 * 1024 * 1024 + 1))
            .build();
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(terminal.toBuilder().setAccountOriginal(tooLargeSourceReadback))
            .build());

    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(
                terminal.toBuilder()
                    .setOriginalAccountProtection(bytesWithSize(40 * 1024 * 1024 + 1)))
            .build());
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(
                terminal.toBuilder()
                    .setOriginalGameSessionProtection(bytesWithSize(48 * 1024 * 1024 + 1)))
            .build());
    assertRejected(
        fixture.request(),
        response.toBuilder()
            .setTerminal(
                terminal.toBuilder()
                    .setOriginalWorldProtection(bytesWithSize(48 * 1024 * 1024 + 1)))
            .build());
  }

  private static Fixture fixture(boolean runtimeReplacement) throws IOException {
    CharacterCreateOperatorAction action = controlledAction();
    CanonicalGameplayRosterTarget target = target(action, runtimeReplacement);
    CanonicalPlayerCreatedRosterSourceIdentity source = source(action);
    CanonicalSelectedPlayerCreatedCharacterRequest request =
        selectedCreatedRequest(action, target, source, CHARACTER_1);
    CanonicalSelectedPlayerCreatedCharacterResponse response =
        selectedCreatedResponse(action, target, source, request);
    return new Fixture(action, target, source, request, response);
  }

  private static Fixture uncheckedFixture(boolean runtimeReplacement) {
    try {
      return fixture(runtimeReplacement);
    } catch (IOException failure) {
      throw new IllegalStateException("Controlled CHARACTER_CREATE vector is unavailable", failure);
    }
  }

  private static CharacterCreateOperatorAction controlledAction() throws IOException {
    JsonNode root;
    try (InputStream input =
        CanonicalPlayerCreatedRosterEvidenceTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      if (input == null) throw new IOException("Missing CHARACTER_CREATE test vector");
      root = JSON.readTree(input);
    }
    for (JsonNode vector : root.path("vectors")) {
      if ("base-public-player-created".equals(text(vector, "name"))) return action(vector);
    }
    throw new IOException("Missing controlled PLAYER_CREATED action vector");
  }

  private static CharacterCreateOperatorAction action(JsonNode vector) {
    JsonNode scopeNode = vector.path("scope");
    JsonNode targetNode = vector.path("target");
    JsonNode expectedNode = vector.path("expectedVersion");
    JsonNode mutationNode = vector.path("mutation");
    JsonNode catalogNode = mutationNode.path("catalog");
    JsonNode pointerNode = mutationNode.path("pointer");
    JsonNode authoredNode = mutationNode.path("authoredSource");
    JsonNode policyNode = authoredNode.path("policy");
    JsonNode coreNode = authoredNode.path("core");
    JsonNode descriptorNode = authoredNode.path("descriptor");

    Scope scope =
        new Scope(
            text(scopeNode, "targetNamespace"),
            text(scopeNode, "tenantId"),
            text(scopeNode, "playableStateNamespaceId"),
            CharacterCreateOperatorAction.PlayableStateScope.valueOf(
                text(scopeNode, "playableStateScope")));
    Target target =
        new Target(
            text(targetNode, "accountId"),
            text(targetNode, "realmId"),
            text(targetNode, "worldSlug"),
            text(targetNode, "realmSlug"),
            text(targetNode, "gameInstanceId"),
            text(targetNode, "versionId"));
    ExpectedVersion expected =
        new ExpectedVersion(
            text(expectedNode, "catalogRevision"),
            text(expectedNode, "pointerVersion"),
            text(expectedNode, "activeLifecycleEpoch"),
            Instant.parse(text(expectedNode, "evaluatedAt")),
            Instant.parse(text(expectedNode, "connectScopeExpiresAt")));
    Catalog catalog =
        new Catalog(
            text(catalogNode, "tenantId"),
            text(catalogNode, "tenantSlug"),
            text(catalogNode, "worldSlug"),
            text(catalogNode, "worldDisplayName"),
            text(catalogNode, "realmId"),
            text(catalogNode, "realmSlug"),
            text(catalogNode, "realmDisplayName"),
            catalogNode.path("visible").booleanValue(),
            catalogNode.path("publicProduction").booleanValue(),
            text(catalogNode, "stateScope"),
            text(catalogNode, "playableStateNamespaceId"),
            text(catalogNode, "characterCreationPolicy"),
            text(catalogNode, "catalogRevision"));
    Pointer pointer =
        new Pointer(
            text(pointerNode, "tenantId"),
            text(pointerNode, "realmId"),
            text(pointerNode, "worldSlug"),
            text(pointerNode, "realmSlug"),
            text(pointerNode, "admissionState"),
            text(pointerNode, "admissibleGameInstanceId"),
            text(pointerNode, "pointerVersion"),
            text(pointerNode, "catalogRevision"),
            Instant.parse(text(pointerNode, "updatedAt")));
    Policy policy =
        new Policy(
            text(policyNode, "commitId"),
            text(policyNode, "revisionId"),
            text(policyNode, "logicalRevisionId"),
            text(policyNode, "canonicalPolicyJson"),
            text(policyNode, "contentDigest"));
    Core core = new Core(text(coreNode, "canonicalJson"));
    Descriptor descriptor =
        new Descriptor(text(descriptorNode, "canonicalJson"), text(descriptorNode, "digest"));
    AuthoredSource authored =
        new AuthoredSource(
            text(authoredNode, "schema"),
            text(authoredNode, "bindingId"),
            text(authoredNode, "bindingDigest"),
            text(authoredNode, "canonicalTenantId"),
            text(authoredNode, "canonicalVersionId"),
            text(authoredNode, "worldSourceOperationId"),
            text(authoredNode, "worldSourceEvidenceDigest"),
            text(authoredNode, "worldSlug"),
            text(authoredNode, "publicationWorkflowId"),
            text(authoredNode, "selectedCommitId"),
            text(authoredNode, "selectedCommitDigest"),
            policy,
            core,
            descriptor,
            rule(authoredNode.path("disposition")),
            rule(authoredNode.path("safeFeedback")));
    Mutation mutation =
        new Mutation(
            text(mutationNode, "purpose"),
            text(mutationNode, "entryPolicy"),
            catalog,
            pointer,
            authored,
            text(mutationNode, "canonicalCreationInput"));
    return new CharacterCreateOperatorAction(
        scope, target, expected, mutation, CharacterCreateOperatorAction.AuditReason.ABSENT);
  }

  private static Rule rule(JsonNode node) {
    return new Rule(
        text(node, "key"),
        text(node, "definitionJson"),
        text(node, "definitionDigest"),
        text(node, "sourceCommitId"),
        text(node, "sourceCommitDigest"),
        text(node, "revisionId"),
        text(node, "revisionOrder"));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Missing text field " + field);
    }
    return value.textValue();
  }

  private static CanonicalGameplayRosterTarget target(
      CharacterCreateOperatorAction action, boolean runtimeReplacement) {
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
        .setGameInstanceUuid(runtimeReplacement ? uuid(119) : target.gameInstanceId())
        .setCatalogRevision(runtimeReplacement ? catalogRevision + 11 : catalogRevision)
        .setPublishedPolicyDigest("e".repeat(64))
        .setPublishedReleaseBundleRef("release/controlled-player-created")
        .setAdmissionPointerSnapshotDigest("b".repeat(64))
        .setPublishedOwnerProofDigest("c".repeat(64))
        .setPlayableStateNamespaceUuid(action.scope().playableStateNamespaceId())
        .setPlayableStateScope(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED)
        .setEntryPolicy(
            CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
        .setCanonicalVersionUuid(target.versionId())
        .setPointerVersion(runtimeReplacement ? pointerVersion + 13 : pointerVersion)
        .setActiveWorldEpoch(runtimeReplacement ? worldEpoch + 17 : worldEpoch)
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

  private static CanonicalSelectedPlayerCreatedCharacterRequest selectedCreatedRequest(
      CharacterCreateOperatorAction action,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source,
      String character) {
    String requestUuid = uuid(117);
    return CanonicalSelectedPlayerCreatedCharacterRequest.newBuilder()
        .setRequestUuid(requestUuid)
        .setCanonicalAccountUuid(action.target().accountId())
        .setSelectedCharacterUuid(character)
        .setExpectedTarget(target)
        .setExpectedSnapshot(
            CanonicalGameplayRosterSnapshotReference.newBuilder()
                .setSnapshotUuid(SNAPSHOT)
                .setSnapshotDigest("a".repeat(64)))
        .setPlayerExecutionContext(
            context(target, action.target().accountId(), requestUuid, character))
        .setExpectedSource(source)
        .build();
  }

  private static PlayerExecutionContext context(
      CanonicalGameplayRosterTarget target, String account, String request, String character) {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(account)
        .setTenantId(target.getTenantUuid())
        .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
        .setGameInstanceId(target.getGameInstanceUuid())
        .setSessionId(uuid(118))
        .setRealmId(target.getRealmUuid())
        .setRequestId(request)
        .setPlayableStateScope("SHARED")
        .setCharacterId(character)
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
            .setOriginalAccountProtection(ByteString.copyFromUtf8("account-protection"))
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

  private static String rawDigest(String prefixedDigest) {
    return prefixedDigest.substring("sha256:".length());
  }

  private static void assertRejected(
      CanonicalSelectedPlayerCreatedCharacterRequest request,
      CanonicalSelectedPlayerCreatedCharacterResponse response) {
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalPlayerCreatedRosterEvidence.requireSelectedResponse(request, response));
  }

  private static void assertRejectedWithSource(
      Fixture fixture, CanonicalPlayerCreatedRosterSourceIdentity source) {
    var request = selectedCreatedRequest(fixture.action(), fixture.target(), source, CHARACTER_1);
    var response = fixture.response().toBuilder().setSourceIdentity(source).build();
    assertRejected(request, response);
  }

  private static void assertRejectedWithTarget(
      Fixture fixture, CanonicalGameplayRosterTarget target) {
    var request = selectedCreatedRequest(fixture.action(), target, fixture.source(), CHARACTER_1);
    var response = fixture.response().toBuilder().setTarget(target).build();
    assertRejected(request, response);
  }

  private static ByteString bytesWithSize(int size) {
    ByteString megabyte = ByteString.copyFrom(new byte[1024 * 1024]);
    ByteString result = ByteString.EMPTY;
    int remaining = size;
    while (remaining >= megabyte.size()) {
      result = result.concat(megabyte);
      remaining -= megabyte.size();
    }
    if (remaining > 0) result = result.concat(megabyte.substring(0, remaining));
    return result;
  }

  private static CanonicalPlayerCreatedRosterResponse response(
      CanonicalPlayerCreatedRosterRequest request, List<CanonicalGameplayRosterActor> actors) {
    String digest =
        CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
            request.getCanonicalAccountUuid(),
            request.getExpectedTarget(),
            request.getExpectedSource(),
            actors);
    return CanonicalPlayerCreatedRosterResponse.newBuilder()
        .setRoster(
            CanonicalGameplayRosterResponse.newBuilder()
                .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
                .setTarget(request.getExpectedTarget())
                .setSnapshotUuid(SNAPSHOT)
                .setSnapshotDigest(digest)
                .addAllActors(actors))
        .setSourceIdentity(request.getExpectedSource())
        .build();
  }

  private static CanonicalPlayerCreatedRosterRequest request(
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source,
      PlayerExecutionContext context) {
    return CanonicalPlayerCreatedRosterRequest.newBuilder()
        .setRequestUuid(uuid(5))
        .setCanonicalAccountUuid(ACCOUNT)
        .setExpectedTarget(target)
        .setPlayerExecutionContext(context)
        .setExpectedSource(source)
        .build();
  }

  private static CanonicalSelectedPlayerCreatedCharacterRequest selectedRequest(
      PlayerExecutionContext context) {
    return CanonicalSelectedPlayerCreatedCharacterRequest.newBuilder()
        .setRequestUuid(uuid(5))
        .setCanonicalAccountUuid(ACCOUNT)
        .setSelectedCharacterUuid(CHARACTER_1)
        .setExpectedTarget(target())
        .setExpectedSnapshot(
            CanonicalGameplayRosterSnapshotReference.newBuilder()
                .setSnapshotUuid(SNAPSHOT)
                .setSnapshotDigest("a".repeat(64)))
        .setPlayerExecutionContext(context)
        .setExpectedSource(source())
        .build();
  }

  private static PlayerExecutionContext context(String character) {
    var builder =
        PlayerExecutionContext.newBuilder()
            .setAccountId(ACCOUNT)
            .setTenantId(uuid(10))
            .setPlayableStateNamespaceId(uuid(11))
            .setGameInstanceId(uuid(12))
            .setSessionId(uuid(13))
            .setRealmId(uuid(14))
            .setRequestId(uuid(5))
            .setPlayableStateScope("SHARED");
    if (character != null) builder.setCharacterId(character);
    return builder.build();
  }

  private static CanonicalGameplayRosterTarget target() {
    return target(0);
  }

  private static CanonicalGameplayRosterTarget target(int variant) {
    return CanonicalGameplayRosterTarget.newBuilder()
        .setTenantUuid(uuid(10))
        .setRealmUuid(uuid(14))
        .setWorldSlug("world")
        .setRealmSlug("realm")
        .setGameInstanceUuid(uuid(12))
        .setCatalogRevision(7)
        .setPublishedPolicyDigest("a".repeat(64))
        .setPublishedReleaseBundleRef("release/one")
        .setAdmissionPointerSnapshotDigest("b".repeat(64))
        .setPublishedOwnerProofDigest("c".repeat(64))
        .setPlayableStateNamespaceUuid(uuid(11))
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setEntryPolicy(
            CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
        .setCanonicalVersionUuid(uuid(15))
        .setPointerVersion(variant == 0 ? 2 : 3)
        .setActiveWorldEpoch(4)
        .build();
  }

  private static CanonicalPlayerCreatedRosterSourceIdentity source() {
    return source(0);
  }

  private static CanonicalPlayerCreatedRosterSourceIdentity source(int variant) {
    return CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
        .setSchemaVersion(1)
        .setDescriptorUuid(uuid(20 + variant))
        .setDescriptorRevisionUuid(uuid(30))
        .setDescriptorDigest("sha256:" + "d".repeat(64))
        .setAuthoredBindingUuid(uuid(40))
        .setAuthoredBindingDigest("sha256:" + "e".repeat(64))
        .build();
  }

  private static CanonicalGameplayRosterActor actor(String character, String displayName) {
    return CanonicalGameplayRosterActor.newBuilder()
        .setCharacterUuid(character)
        .setDisplayName(displayName)
        .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
        .build();
  }

  private static String uuid(int value) {
    return new UUID(0L, value).toString();
  }
}
