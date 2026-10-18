package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Audience;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Visibility;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuthoredInitialActorCoreReadEvidenceTest {
  @Test
  void completeResultBindsTheExactAssociationDescriptorAndOrderedProjectionSet() throws Exception {
    var fixture = AuthoredInitialActorCoreReadTestFixture.create();
    var projection = fixture.projection(UUID.fromString("1e9c2424-5669-4b6a-8f8f-7b452ab64c62"));
    var result = fixture.result(List.of(projection));

    assertThat(result.request()).isEqualTo(fixture.request());
    assertThat(result.associationRead()).isEqualTo(fixture.associationRead());
    assertThat(result.descriptor()).isEqualTo(fixture.descriptor());
    assertThat(result.projections()).containsExactly(projection);
    assertThat(projection.worldSourceOperationId())
        .isEqualTo(fixture.associationRead().association().sourceOperationId());
    assertThat(projection.worldSourceEvidenceDigest())
        .isEqualTo(fixture.associationRead().association().sourceEvidenceDigest());
    assertThat(projection.worldSlug())
        .isEqualTo(fixture.associationRead().association().worldSlug());
    assertThat(projection.publicationWorkflowId())
        .isEqualTo(fixture.associationRead().association().publishWorkflowId());
    assertThat(projection.canonicalTenantId())
        .isEqualTo(fixture.associationRead().association().canonicalTenantId());
    assertThat(projection.canonicalVersionId())
        .isEqualTo(fixture.associationRead().association().canonicalVersionId());
    assertThat(projection.selectedCommitId())
        .isEqualTo(fixture.associationRead().association().selectedCommitId());
    assertThat(result.canonicalBytes()).isNotEmpty();
    assertThat(result.digest()).startsWith("sha256:");
  }

  @Test
  void refusesDuplicateProjectionBindingIds() throws Exception {
    var fixture = AuthoredInitialActorCoreReadTestFixture.create();
    UUID bindingId = UUID.fromString("2e9c2424-5669-4b6a-8f8f-7b452ab64c62");
    var first = fixture.projection(bindingId);
    var duplicate = fixture.projection(bindingId);

    assertThatThrownBy(() -> fixture.result(List.of(first, duplicate)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Projection differs from exact association or descriptor");
  }

  @Test
  void refusesAValidProjectionCorrelatedToAnotherWorldSourceOperation() throws Exception {
    var fixture = AuthoredInitialActorCoreReadTestFixture.create();
    var projection =
        fixture.projection(
            UUID.fromString("3e9c2424-5669-4b6a-8f8f-7b452ab64c62"),
            UUID.fromString("4e9c2424-5669-4b6a-8f8f-7b452ab64c62"));
    assertThat(projection.worldSourceOperationId())
        .isNotEqualTo(fixture.associationRead().association().sourceOperationId());

    assertThatThrownBy(() -> fixture.result(List.of(projection)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Projection differs from exact association or descriptor");
  }

  @Test
  void refusesInitialSelectionAsActorCoreReadAuthority() throws Exception {
    var fixture = AuthoredInitialActorCoreReadTestFixture.create();
    var initial =
        new Request(
            fixture.request().schemaVersion(),
            fixture.request().targetNamespace(),
            fixture.request().readRequestId(),
            fixture.request().canonicalPostAuthorizationTuple(),
            fixture.request().ownerAttemptId(),
            fixture.request().ownerFence(),
            new StartSessionTemplateAssociationReadEvidence.InitialConfigured());

    assertThatThrownBy(() -> new AuthoredInitialActorCoreReadEvidence.ReadRequest(initial))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exact pinned association replay");
  }
}

/** Shared, owner-shaped value fixture for the directly owning Common transport tests. */
final class AuthoredInitialActorCoreReadTestFixture {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID INTAKE_ID = uuid("04444444-4444-4444-8444-444444444444");
  private static final UUID WORLD_OPERATION_ID = uuid("05555555-5555-4555-8555-555555555555");
  private static final UUID SOURCE_OPERATION_ID = uuid("06666666-6666-4666-8666-666666666666");
  private static final UUID WORLD_READ_ID = uuid("07777777-7777-4777-8777-777777777777");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String NAMESPACE = "test";
  private static final String WORKFLOW = "selected-publication-workflow";
  private static final String WORLD_SLUG = "synthetic-world";
  private static final long TEMPLATE_ID = 19L;
  private static final UUID POLICY_REVISION = uuid("13333333-3333-4333-8333-333333333333");
  private static final UUID DISPOSITION_REVISION = uuid("14444444-4444-4444-8444-444444444444");
  private static final UUID FEEDBACK_REVISION = uuid("15555555-5555-4555-8555-555555555555");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuthoredInitialActorCoreReadTestFixture() {}

  static Fixture create() throws Exception {
    var worldEvidence = AuthoredWorldReleaseAttestationSelectorTest.selectorEvidence();
    UUID tenant = worldEvidence.request().canonicalTenantId();
    UUID version = worldEvidence.request().canonicalVersionId();
    UUID commit = uuid(worldEvidence.request().appliedCommitId());
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, INTAKE_ID, tenant, "tenant-key", WORLD_SLUG, "Synthetic World");
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            INTAKE_ID,
            SOURCE_OPERATION_ID,
            sourceRequestDigest,
            tenant,
            "tenant-key",
            WORLD_SLUG,
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            INTAKE_ID,
            SOURCE_OPERATION_ID,
            sourceRequestDigest,
            tenant,
            "tenant-key",
            WORLD_SLUG,
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    var intake =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1, NAMESPACE, INTAKE_ID, tenant, WORLD_SLUG, SOURCE_OPERATION_ID, sourceEvidenceDigest);
    var receipt =
        new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
            1,
            NAMESPACE,
            INTAKE_ID,
            WORLD_OPERATION_ID,
            tenant,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            sourceEvidenceDigest,
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(intake),
            digest('e'),
            source);
    var worldRead =
        new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
            1, NAMESPACE, WORLD_READ_ID, INTAKE_ID, tenant);
    var association =
        new StartSessionTemplateAssociationReadEvidence.Association(
            tenant,
            TEMPLATE_ID,
            version,
            commit,
            worldEvidence.request().publishWorkflowId(),
            digest('c'),
            digest('a'),
            NAMESPACE,
            INTAKE_ID,
            WORLD_OPERATION_ID,
            SOURCE_OPERATION_ID,
            WORLD_SLUG,
            sourceEvidenceDigest,
            worldRead,
            receipt);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "actor-core-original-start-session",
            tenant,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            sourceEvidenceDigest,
            TEMPLATE_ID,
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
            "descriptor-1",
            42L,
            false,
            null,
            "{}",
            "generation",
            9L,
            7L,
            "release-1",
            false,
            null);
    var releaseEvidence =
        AuthoredWorldReleaseAttestationSelectorTest.release(descriptor, worldEvidence);
    PublishedReleaseBundle.Builder release =
        PublishedReleaseBundle.newBuilder()
            .setId(7L)
            .setVersionId(42L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v2")
            .setPublishWorkflowId(worldEvidence.request().publishWorkflowId())
            .setManifestHash(releaseEvidence.manifestHash())
            .setManifestSchemaVersion(releaseEvidence.manifestSchemaVersion())
            .addAllRequiredManifestAssetKeys(releaseEvidence.requiredManifestAssetKeys())
            .setGenerationConfigRevision(releaseEvidence.generationConfigRevision())
            .setPublishedReleaseBundleRef(releaseEvidence.publishedReleaseBundleRef())
            .setCanonicalTenantId(tenant.toString())
            .setCanonicalVersionId(version.toString())
            .addAllCommandDefinitions(releaseEvidence.commandDefinitions());
    for (var participant : releaseEvidence.participantDigests()) {
      ParticipantDigest.Builder item =
          ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(participant.appliedCommitId())
              .setContentDigest(participant.contentDigest())
              .setDigestSchemaVersion(participant.digestSchemaVersion());
      if (participant.abilitySchemaDigestPresent()) {
        item.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      }
      release.addParticipantDigests(item);
    }
    var request =
        new Request(
            1,
            NAMESPACE,
            READ_ID,
            postTuple(tenant, NAMESPACE).canonicalBytes(),
            ATTEMPT,
            8L,
            new ExactReplay(
                version,
                commit,
                worldEvidence.request().publishWorkflowId(),
                association.associationDigest()));
    var associationRead =
        new StartSessionTemplateAssociationReadEvidence.Result(
            request, association, release.build(), worldEvidence, 23L);
    return new Fixture(request, associationRead, descriptor, source);
  }

  static StartSessionTemplateAssociationReadEvidence.Request request() throws Exception {
    return create().request();
  }

  static StartSessionTemplateAssociationReadEvidence.Request request(String namespace)
      throws Exception {
    var original = create().request();
    return new Request(
        original.schemaVersion(),
        namespace,
        original.readRequestId(),
        postTuple(
                original.decodedTuple().preAuthorizationTuple().action().scope().tenantId(),
                namespace)
            .canonicalBytes(),
        original.ownerAttemptId(),
        original.ownerFence(),
        original.selection());
  }

  private static AuthoredInitialActorCoreSourceBinding sourceBinding(
      Fixture fixture, UUID worldSourceOperationId) {
    var association = fixture.associationRead().association();
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
            JSON);
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
                "0", POLICY_REVISION, Owner.GAME_DESIGN_CONTROL_PLANE, policyPayload),
            new RevisionPayload(
                "1",
                DISPOSITION_REVISION,
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(disposition)),
            new RevisionPayload(
                "2",
                FEEDBACK_REVISION,
                Owner.GAME_DESIGN_CONTROL_PLANE,
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
            uuid("1eeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            association.selectedCommitId(),
            "base-0",
            revisions,
            List.of(
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    RealmPolicySource.SCOPE,
                    association.canonicalVersionId().toString(),
                    RealmPolicySource.SCOPE,
                    "effective",
                    "0"),
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    GameplayRuleSource.SCOPE,
                    association.canonicalVersionId().toString(),
                    GameplayRuleSource.SCOPE,
                    GameplayRuleSource.SCOPE_ID,
                    "0")));
    var selectedPolicy = RealmPolicySource.revision(commit, revisions.getFirst());
    var policySnapshot = new RealmPolicySnapshot(commit, "1", List.of(selectedPolicy));
    var dispositionEntry =
        new GameplayRuleSource.Entry(
            Family.DISPOSITIONS, disposition, commit, "1", DISPOSITION_REVISION);
    var feedbackEntry =
        new GameplayRuleSource.Entry(Family.FEEDBACK, feedback, commit, "2", FEEDBACK_REVISION);
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
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(
      UUID tenant, String namespace) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "actor-core-original-start-session",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenant, namespace),
                new StartSessionOperatorAction.Target(TEMPLATE_ID, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact original selected authored actor core"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        RESERVATION_OWNER,
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
                "applicableAccountId", ACTOR.toString(),
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
                "issuanceOperationId", ISSUANCE_ID.toString(),
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
                ACTOR.toString(),
                "controlUiTokenJti",
                TOKEN_JTI.toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  record Fixture(
      Request request,
      Result associationRead,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldSourceEvidence source) {
    AuthoredInitialActorCoreReadEvidence.ReadResult result() {
      return result(List.of(projection(uuid("0e9c2424-5669-4b6a-8f8f-7b452ab64c62"))));
    }

    AuthoredInitialActorCoreReadEvidence.ReadResult result(
        List<AuthoredInitialActorCoreProjection> projections) {
      return new AuthoredInitialActorCoreReadEvidence.ReadResult(
          request, associationRead, descriptor, projections);
    }

    AuthoredInitialActorCoreProjection projection(UUID bindingId) {
      return projection(bindingId, associationRead.association().sourceOperationId());
    }

    AuthoredInitialActorCoreProjection projection(UUID bindingId, UUID worldSourceOperationId) {
      return AuthoredInitialActorCoreProjection.fromValidatedBinding(
          bindingId, sourceBinding(this, worldSourceOperationId));
    }
  }
}
