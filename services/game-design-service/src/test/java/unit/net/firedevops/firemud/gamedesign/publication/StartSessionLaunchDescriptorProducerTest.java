package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreSourceBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Audience;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Disposition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Feedback;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.ReplayPolicy;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Visibility;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class StartSessionLaunchDescriptorProducerTest {
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final UUID VERSION = uuid("03333333-3333-4333-8333-333333333333");
  private static final UUID COMMIT = uuid("04444444-4444-4444-8444-444444444444");
  private static final String NAMESPACE = "world-runtime";
  private static final long FENCE = 8L;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void requiresExactReplayAndSameNamespacePeerBeforeOwnerWork() {
    var associationReader = mock(StartSessionTemplateAssociationReadService.class);
    var transactions = mock(PlatformTransactionManager.class);
    var producer =
        new StartSessionLaunchDescriptorProducer(
            mock(DSLContext.class),
            transactions,
            NAMESPACE,
            associationReader,
            mock(PublishedReleaseBundleService.class),
            JSON);
    var initial = request(new InitialConfigured());

    assertThatThrownBy(() -> withPeer(() -> producer.resolve(initial), "game-session-service"))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(associationReader, transactions);

    assertThatThrownBy(() -> withPeer(() -> producer.resolve(null), "game-session-service"))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(associationReader, transactions);

    assertThatThrownBy(
            () ->
                withPeer(
                    () ->
                        producer.resolve(
                            request(
                                new ExactReplay(
                                    VERSION, COMMIT, "selected-workflow", digest('a')))),
                    "account-service"))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(associationReader, transactions);
  }

  @Test
  void delegatesExactReplayToAssociationReaderBeforeOpeningLocalWriteTransaction() {
    // This is an explicit upstream boundary double, not Account/mTLS or owner-storage proof.
    var associationReader = mock(StartSessionTemplateAssociationReadService.class);
    var transactions = mock(PlatformTransactionManager.class);
    var producer =
        new StartSessionLaunchDescriptorProducer(
            mock(DSLContext.class),
            transactions,
            NAMESPACE,
            associationReader,
            mock(PublishedReleaseBundleService.class),
            JSON);
    var request = request(new ExactReplay(VERSION, COMMIT, "selected-workflow", digest('a')));
    when(associationReader.read(request))
        .thenThrow(
            Status.UNAVAILABLE
                .withDescription("test seam: independent Account projection unavailable")
                .asRuntimeException());

    assertThatThrownBy(() -> withPeer(() -> producer.resolve(request), "game-session-service"))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
    verifyNoInteractions(transactions);
  }

  @Test
  void selectedSchema3ActorCoreIsResolvedFromExactOwnerSnapshots() {
    var fixture = selectedActorCoreSource();
    var resolved =
        StartSessionLaunchDescriptorProducer.resolveSelectedAuthoredInitialActorCoreSources(
            uuid("66666666-6666-4666-8666-666666666666"),
            digest('a'),
            "world",
            "selected-workflow",
            bytes("selected publication operation"),
            bytes("selected publication terminal evidence"),
            fixture.binding(),
            fixture.policySnapshot(),
            fixture.gameplayRules());

    assertThat(resolved).hasSize(1);
    assertThat(resolved.getFirst().core().displayName()).isEqualTo("Wayfarer");
    assertThat(resolved.getFirst().disposition().dispositionKey()).isEqualTo("newcomer");
    assertThat(resolved.getFirst().safeFeedback().feedbackKey()).isEqualTo("welcome");
    assertThat(resolved.getFirst().selectedCommit()).isEqualTo(fixture.binding());
    assertThat(resolved.getFirst().expectedWorldSlug()).isEqualTo("world");
    assertThatThrownBy(resolved::clear).isInstanceOf(UnsupportedOperationException.class);

    assertThat(
            StartSessionLaunchDescriptorProducer.resolveSelectedAuthoredInitialActorCoreSources(
                uuid("66666666-6666-4666-8666-666666666666"),
                digest('a'),
                "different-world",
                "selected-workflow",
                bytes("selected publication operation"),
                bytes("selected publication terminal evidence"),
                fixture.binding(),
                fixture.policySnapshot(),
                fixture.gameplayRules()))
        .isEmpty();

    DraftCommitBinding otherCommit =
        DraftCommitBinding.create(
            fixture.binding().target(),
            uuid("99999999-9999-4999-8999-999999999999"),
            uuid("88888888-8888-4888-8888-888888888888"),
            "base-commit-0",
            fixture.binding().revisions(),
            fixture.binding().affectedUnits());
    var changedPolicySnapshot =
        new net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot(
            otherCommit, "1", fixture.policySnapshot().policies());

    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorProducer.resolveSelectedAuthoredInitialActorCoreSources(
                    uuid("66666666-6666-4666-8666-666666666666"),
                    digest('a'),
                    "world",
                    "selected-workflow",
                    bytes("selected publication operation"),
                    bytes("selected publication terminal evidence"),
                    fixture.binding(),
                    changedPolicySnapshot,
                    fixture.gameplayRules()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selected authored commit");
  }

  @Test
  void resolvedBindingDefensivelyRetainsSelectedActorCoreAndRejectsChangedReleaseProvenance() {
    var fixture = selectedActorCoreSource();
    UUID sourceOperation = uuid("66666666-6666-4666-8666-666666666666");
    List<AuthoredInitialActorCoreSourceBinding> sourceBindings =
        StartSessionLaunchDescriptorProducer.resolveSelectedAuthoredInitialActorCoreSources(
            sourceOperation,
            digest('a'),
            "world",
            "selected-workflow",
            bytes("selected publication operation"),
            bytes("selected publication terminal evidence"),
            fixture.binding(),
            fixture.policySnapshot(),
            fixture.gameplayRules());
    var mutableBindings = new ArrayList<>(sourceBindings);
    var resolved =
        resolvedBinding(mutableBindings, sourceOperation, "world", COMMIT, "selected-workflow");

    mutableBindings.clear();
    assertThat(resolved.actorCoreBindings()).hasSize(1);
    assertThat(resolved.actorCoreBindings().getFirst().canonicalBytes())
        .isEqualTo(sourceBindings.getFirst().canonicalBytes());
    assertThatThrownBy(resolved.actorCoreBindings()::clear)
        .isInstanceOf(UnsupportedOperationException.class);

    assertThatThrownBy(
            () ->
                resolvedBinding(
                    sourceBindings,
                    uuid("77777777-7777-4777-8777-777777777777"),
                    "world",
                    COMMIT,
                    "selected-workflow"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact selected StartSession release");
    assertThatThrownBy(
            () ->
                resolvedBinding(
                    sourceBindings, sourceOperation, "other-world", COMMIT, "selected-workflow"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact selected StartSession release");
    assertThatThrownBy(
            () ->
                resolvedBinding(
                    sourceBindings,
                    sourceOperation,
                    "world",
                    uuid("88888888-8888-4888-8888-888888888888"),
                    "selected-workflow"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact selected StartSession release");
    assertThatThrownBy(
            () ->
                resolvedBinding(sourceBindings, sourceOperation, "world", COMMIT, "other-workflow"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact selected StartSession release");
  }

  private static StartSessionLaunchDescriptorProducer.ResolvedBinding resolvedBinding(
      List<AuthoredInitialActorCoreSourceBinding> actorCoreBindings,
      UUID associationSourceOperationId,
      String descriptorWorldSlug,
      UUID associationSelectedCommitId,
      String associationWorkflowId) {
    var request = request(new ExactReplay(VERSION, COMMIT, "selected-workflow", digest('a')));
    var association = mock(StartSessionTemplateAssociationReadEvidence.Association.class);
    when(association.canonicalTenantId()).thenReturn(TENANT);
    when(association.templateId()).thenReturn(91L);
    when(association.canonicalVersionId()).thenReturn(VERSION);
    when(association.selectedCommitId()).thenReturn(associationSelectedCommitId);
    when(association.publishWorkflowId()).thenReturn(associationWorkflowId);
    when(association.sourceOperationId()).thenReturn(associationSourceOperationId);
    when(association.sourceEvidenceDigest()).thenReturn(digest('a'));
    when(association.worldSlug()).thenReturn("world");

    var associationRead = mock(StartSessionTemplateAssociationReadEvidence.Result.class);
    when(associationRead.request()).thenReturn(request);
    when(associationRead.association()).thenReturn(association);

    var authoredWorld = mock(AuthoredWorldLaunchDescriptorEvidence.class);
    when(authoredWorld.worldSlug()).thenReturn(descriptorWorldSlug);
    when(authoredWorld.authoredWorldSourceOperationId())
        .thenReturn(uuid("66666666-6666-4666-8666-666666666666"));
    when(authoredWorld.authoredWorldSourceEvidenceDigest()).thenReturn(digest('a'));
    when(authoredWorld.canonicalTenantId()).thenReturn(TENANT);
    when(authoredWorld.versionId()).thenReturn(23L);
    when(authoredWorld.gameTemplateId()).thenReturn(91L);

    var descriptor = mock(ResolvedLaunchDescriptorDto.class);
    when(descriptor.controlPlaneRequestId()).thenReturn("launch-descriptor-test");
    when(descriptor.canonicalTenantId()).thenReturn(TENANT.toString());
    when(descriptor.gameTemplateId()).thenReturn(91L);
    when(descriptor.authoredWorldBinding()).thenReturn(authoredWorld);
    return new StartSessionLaunchDescriptorProducer.ResolvedBinding(
        request, associationRead, descriptor, actorCoreBindings);
  }

  private static SelectedActorCoreFixture selectedActorCoreSource() {
    var target =
        new TargetProof(
            TENANT, VERSION, 23L, "private-owner-key", 42L, "private-owner-key", "NEW_GAME_ROW");
    String policyJson =
        "{\"schemaVersion\":3,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
            + "\"realmSlug\":\"realm\",\"realmDisplayName\":\"Realm\",\"visible\":true,"
            + "\"publicProduction\":true,\"stateScope\":\"SHARED\",\"entryPolicy\":\"PLAYER_CREATED\","
            + "\"creationDescriptor\":{\"schemaVersion\":1,\"descriptorId\":\"11111111-1111-4111-8111-111111111111\","
            + "\"descriptorRevisionId\":\"22222222-2222-4222-8222-222222222222\","
            + "\"inputKind\":\"EMPTY_OBJECT\",\"dependencies\":[]},"
            + "\"initialActorCore\":{\"displayName\":\"Wayfarer\",\"dispositionKey\":\"newcomer\"}}";
    String policyPayload =
        RealmPolicySource.canonical(
            Map.of(
                "revisionKind",
                RealmPolicySource.REVISION_KIND,
                "logicalRevisionId",
                "selected-policy-r3",
                "policy",
                RealmPolicySource.tree(policyJson)));
    var disposition = new Disposition("newcomer", List.of(), "welcome");
    var feedback =
        new Feedback(
            "welcome",
            "actor.created",
            Audience.SOURCE_ACTOR,
            "actor.created",
            "Welcome, traveler.",
            List.of(),
            Visibility.PUBLIC,
            ReplayPolicy.EFFECT_IDEMPOTENT);
    List<RevisionPayload> revisions =
        List.of(
            new RevisionPayload(
                "0",
                uuid("55555555-5555-4555-8555-555555555555"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                policyPayload),
            new RevisionPayload(
                "1",
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(disposition)),
            new RevisionPayload(
                "2",
                uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(feedback)));
    List<AffectedUnit> affectedUnits =
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                RealmPolicySource.SCOPE,
                VERSION.toString(),
                RealmPolicySource.SCOPE,
                "effective",
                "0"),
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.SCOPE,
                VERSION.toString(),
                GameplayRuleSource.SCOPE,
                GameplayRuleSource.SCOPE_ID,
                "0"));
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            target,
            uuid("77777777-7777-4777-8777-777777777777"),
            COMMIT,
            "base-commit-0",
            revisions,
            affectedUnits);
    var policySource = RealmPolicySource.revision(binding, revisions.getFirst());
    var policySnapshot =
        new net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot(
            binding, "1", List.of(policySource));
    var rulesSnapshot =
        new GameplayRuleSnapshot(
            binding,
            "1",
            null,
            uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            GameplayRuleSource.replay(List.of(), binding));
    return new SelectedActorCoreFixture(binding, policySnapshot, rulesSnapshot);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private record SelectedActorCoreFixture(
      DraftCommitBinding binding,
      net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot policySnapshot,
      GameplayRuleSnapshot gameplayRules) {}

  private static void withPeer(Runnable action, String workload) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + workload, NAMESPACE, workload);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  private static StartSessionTemplateAssociationReadEvidence.Request request(
      StartSessionTemplateAssociationReadEvidence.Selection selection) {
    return new StartSessionTemplateAssociationReadEvidence.Request(
        1,
        NAMESPACE,
        uuid("01111111-1111-4111-8111-111111111111"),
        postTuple("launch-descriptor-test").canonicalBytes(),
        ATTEMPT,
        FENCE,
        selection);
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(String requestId) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact StartSession descriptor selection"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenant = TENANT.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenant, "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenant),
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
                "tenantAuthorityGeneration", Map.of(tenant, 3L),
                "membershipAuthorityGeneration", Map.of(tenant, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenant, 5L),
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
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
