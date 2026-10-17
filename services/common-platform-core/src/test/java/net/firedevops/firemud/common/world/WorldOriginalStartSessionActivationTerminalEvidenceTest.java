package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Integrity and exact-copy tests; no authenticated producer or committed database is implied. */
class WorldOriginalStartSessionActivationTerminalEvidenceTest {
  private static final UUID ACCOUNT_PROTECTION = uuid("78787878-7878-4878-8878-787878787878");
  private static final UUID ACTIVATION_ID = uuid("89898989-8989-4898-8898-898989898989");
  private static final UUID CURRENT_ATTEMPT_READ_ID = uuid("f6f6f6f6-f6f6-46f6-86f6-f6f6f6f6f6f6");
  private static final long ACCOUNT_FENCE = 19L;
  private static final long WORLD_FENCE = 23L;
  private static final Instant LEASE = Instant.parse("2026-10-09T10:20:30.456789Z");
  private static final Instant ACQUIRED = Instant.parse("2026-10-09T10:11:12.123Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsFullAccountProtectionAndBothExactWorldTerminalOutcomes() throws Exception {
    Fixture fixture = fixture();
    var request = accountRequest(fixture);
    byte[] capture = captureReference(originalTuple(fixture));
    var protection =
        AccountStartSessionActivationProtectionEvidence.create(
            request,
            ACCOUNT_PROTECTION,
            ACCOUNT_FENCE,
            ACQUIRED,
            capture,
            sha256(capture),
            List.of(source(fixture.launchRequest().canonicalTenantId())));

    var active = nextActive(request.worldActivationRequest().preparing());
    var committedResult =
        new WorldCanonicalInstanceActivation.Result(
            request.worldActivationRequest(),
            WorldCanonicalInstanceActivation.Outcome.COMMITTED,
            null,
            active);
    var committed =
        WorldOriginalStartSessionActivationTerminalEvidence.create(
            protection, committedResult, WORLD_FENCE);
    var decodedCommitted =
        WorldOriginalStartSessionActivationTerminalEvidence.decode(committed.canonicalBytes());

    assertThat(decodedCommitted.canonicalBytes()).containsExactly(committed.canonicalBytes());
    assertThat(decodedCommitted.accountProtectionEvidence().canonicalBytes())
        .containsExactly(protection.canonicalBytes());
    assertThat(decodedCommitted.activationResult().canonicalBytes())
        .containsExactly(committedResult.canonicalBytes());
    assertThat(decodedCommitted.activationResult().request().preparingEvidenceBytes())
        .containsExactly(request.worldActivationRequest().preparingEvidenceBytes());
    assertThat(decodedCommitted.worldActivationFence()).isEqualTo(WORLD_FENCE);
    assertThat(decodedCommitted.outcome())
        .isEqualTo(WorldCanonicalInstanceActivation.Outcome.COMMITTED);

    var abortedResult =
        new WorldCanonicalInstanceActivation.Result(
            request.worldActivationRequest(),
            WorldCanonicalInstanceActivation.Outcome.ABORTED,
            "POSITIVE_FENCED_ABORT",
            request.worldActivationRequest().preparing());
    var aborted =
        WorldOriginalStartSessionActivationTerminalEvidence.create(
            protection, abortedResult, WORLD_FENCE + 1L);
    var decodedAborted =
        WorldOriginalStartSessionActivationTerminalEvidence.decode(aborted.canonicalBytes());
    assertThat(decodedAborted.canonicalBytes()).containsExactly(aborted.canonicalBytes());
    assertThat(decodedAborted.outcome())
        .isEqualTo(WorldCanonicalInstanceActivation.Outcome.ABORTED);
    assertThat(decodedAborted.activationResult().terminalCode()).isEqualTo("POSITIVE_FENCED_ABORT");
  }

  @Test
  void rejectsMissingFenceChangedResultAndAlteredReceiptIdentity() throws Exception {
    Fixture fixture = fixture();
    var request = accountRequest(fixture);
    byte[] capture = captureReference(originalTuple(fixture));
    var protection =
        AccountStartSessionActivationProtectionEvidence.create(
            request,
            ACCOUNT_PROTECTION,
            ACCOUNT_FENCE,
            ACQUIRED,
            capture,
            sha256(capture),
            List.of(source(fixture.launchRequest().canonicalTenantId())));
    var committed =
        new WorldCanonicalInstanceActivation.Result(
            request.worldActivationRequest(),
            WorldCanonicalInstanceActivation.Outcome.COMMITTED,
            null,
            nextActive(request.worldActivationRequest().preparing()));

    assertThatThrownBy(
            () ->
                WorldOriginalStartSessionActivationTerminalEvidence.create(
                    protection, committed, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    var changedRequest =
        new WorldCanonicalInstanceActivation.Request(
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            request.worldActivationRequest().preparing());
    var substitutedResult =
        new WorldCanonicalInstanceActivation.Result(
            changedRequest,
            WorldCanonicalInstanceActivation.Outcome.COMMITTED,
            null,
            nextActive(changedRequest.preparing()));
    assertThatThrownBy(
            () ->
                WorldOriginalStartSessionActivationTerminalEvidence.create(
                    protection, substitutedResult, WORLD_FENCE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from the original Account-protected");

    byte[] exact =
        WorldOriginalStartSessionActivationTerminalEvidence.create(
                protection, committed, WORLD_FENCE)
            .canonicalBytes();
    var root = JSON.readValue(exact, Map.class);
    root.put("accountProtectionId", "99999999-9999-4999-8999-999999999999");
    byte[] forgedIdentity = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
    assertThatThrownBy(
            () -> WorldOriginalStartSessionActivationTerminalEvidence.decode(forgedIdentity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity differs");
  }

  private static WorldCanonicalInstanceLifecycleEvidence nextActive(
      WorldCanonicalInstanceLifecycleEvidence preparing) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        preparing.request(),
        preparing.launchBinding(),
        preparing.startLocation(),
        preparing.runtimeRoomInstanceId(),
        "ACTIVE",
        preparing.lifecycleEpoch() + 1L,
        preparing.rowVersion() + 1L,
        preparing.captureId(),
        preparing.graphSha256(),
        preparing.preparationInputDigest(),
        preparing.operationalRegionAssignments());
  }

  private static AccountStartSessionActivationProtectionRequest accountRequest(Fixture fixture) {
    var tuple = originalTuple(fixture);
    var original = fixture.preparing().request();
    var launchSelector = fixture.launchRequest();
    var gsRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            launchSelector.readRequestId(),
            launchSelector.targetNamespace(),
            launchSelector.canonicalTenantId(),
            launchSelector.worldSlug(),
            launchSelector.gameInstanceUuid(),
            launchSelector.controlPlaneRequestId(),
            launchSelector.launchDescriptorId(),
            launchSelector.expectedDescriptorRequestDigest(),
            launchSelector.expectedDescriptorResultDigest(),
            launchSelector.expectedReleaseAttestationEvidenceDigest(),
            new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
                tuple.canonicalBytes(),
                uuid("45454545-4545-4454-8454-454545454545"),
                uuid("56565656-5656-4565-8565-565656565656"),
                11L));
    // Explicit fixture-owned realm/catalog values, not authenticated GS owner output.
    var gsResult =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            gsRequest,
            fixture.playableNamespaceId(),
            RealmEntryPolicy.StateScope.SHARED,
            true,
            CurrentGameInstanceStatus.STARTING,
            0L,
            uuid("56565656-5656-4565-8565-565656565656"),
            11L,
            uuid("e8e8e8e8-e8e8-48e8-88e8-e8e8e8e8e8e8"),
            17L,
            fixture.binding());
    var currentAttemptRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            CURRENT_ATTEMPT_READ_ID,
            gsRequest.targetNamespace(),
            tuple.canonicalBytes(),
            uuid("56565656-5656-4565-8565-565656565656"),
            uuid("45454545-4545-4454-8454-454545454545"),
            11L);
    var currentAttempt =
        new OriginalStartSessionCurrentAttemptEvidence.Result(
            currentAttemptRequest,
            LEASE,
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));
    var worldRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            original.schemaVersion(),
            uuid("a1a1a1a1-a1a1-41a1-81a1-a1a1a1a1a1a1"),
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.worldSlug(),
            original.canonicalGameInstanceId(),
            original.playableStateNamespaceId(),
            original.playableStateScope(),
            original.publicProduction(),
            original.controlPlaneRequestId(),
            original.canonicalVersionId(),
            original.expectedDescriptorRequestDigest(),
            original.expectedDescriptorResultDigest(),
            original.expectedReleaseAttestationDigest());
    var preparing =
        new WorldCanonicalInstanceLifecycleEvidence(
            worldRequest,
            fixture.preparing().launchBinding(),
            fixture.preparing().startLocation(),
            fixture.preparing().runtimeRoomInstanceId(),
            "PREPARING",
            fixture.preparing().lifecycleEpoch(),
            0L,
            fixture.preparing().captureId(),
            fixture.preparing().graphSha256(),
            fixture.preparing().preparationInputDigest(),
            fixture.preparing().operationalRegionAssignments());
    var worldActivation = new WorldCanonicalInstanceActivation.Request(ACTIVATION_ID, preparing);
    return AccountStartSessionActivationProtectionRequest.create(
        uuid("67676767-6767-4676-8676-676767676767"),
        13L,
        gsResult,
        currentAttempt,
        worldActivation);
  }

  private static Fixture fixture() throws Exception {
    var selected = WorldPublishedStartLocationGrpcCodecTest.evidence();
    var selector = selected.request();
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            selector.targetNamespace(),
            "terminal-evidence-control-request",
            selector.canonicalTenantId(),
            "terminal-evidence-world",
            uuid("abababab-abab-4bab-8bab-abababababab"),
            "sha256:" + "1".repeat(64),
            91L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "terminal-evidence-launch",
            42L,
            false,
            null,
            "{}",
            "generation-revision",
            9L,
            7L,
            "release-bundle",
            false,
            null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.appliedCommitId(),
                        selector.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "2".repeat(64) : null))
            .toList();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            selector.canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selector.publishWorkflowId(),
            selector.appliedCommitId(),
            participants,
            "sha256:" + "3".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision(),
            selected);
    var binding = new CompleteLaunchBindingEvidence(descriptor, release);
    var selectorReceipt =
        WorldDraftStartLocationEvidence.fromStored(selected.selectorReceiptBytes());
    var worldRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            uuid("90909090-9090-4090-8090-909090909090"),
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            uuid("12121212-1212-4212-8212-121212121212"),
            uuid("23232323-2323-4232-8232-232323232323"),
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            release.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var preparing =
        new WorldCanonicalInstanceLifecycleEvidence(
            worldRequest,
            binding,
            selectorReceipt.startLocation(),
            1042L,
            "PREPARING",
            3L,
            0L,
            uuid("bcbcbcbc-bcbc-4bcb-8bcb-bcbcbcbcbcbc"),
            selectorReceipt.graphDigest().substring("sha256:".length()),
            "sha256:" + "4".repeat(64),
            Map.of(
                uuid("11111111-1111-4111-8111-111111111111"),
                uuid("22222222-2222-4222-8222-222222222222")));
    var gsRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            uuid("a2a2a2a2-a2a2-42a2-82a2-a2a2a2a2a2a2"),
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            worldRequest.canonicalGameInstanceId(),
            descriptor.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    return new Fixture(preparing, binding, gsRequest, uuid("23232323-2323-4232-8232-232323232323"));
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(Fixture fixture) {
    var request = fixture.launchRequest();
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            request.controlPlaneRequestId(),
            uuid("a4f5f4eb-8243-4d42-903a-33495456a622"),
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(
                    request.canonicalTenantId(), request.targetNamespace()),
                new StartSessionOperatorAction.Target(
                    fixture.binding().descriptor().gameTemplateId(),
                    uuid("dededede-dede-4ede-8ede-dededededede")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "World activation terminal evidence fixture"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + request.targetNamespace() + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "5".repeat(64),
        uuid("efefefef-efef-4fef-8fef-efefefefefef"),
        19L,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenant = tuple.action().scope().tenantId().toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenant, 3L),
            "membershipAuthorityGeneration", Map.of(tenant, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuance =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", "a4f5f4eb-8243-4d42-903a-33495456a622",
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    Map<String, Object> scope =
        Map.of(
            "scope",
            Map.of("tenantId", tenant, "targetNamespace", tuple.action().scope().targetNamespace()),
            "actionFamily",
            tuple.actionFamily(),
            "applicableAccountId",
            "a4f5f4eb-8243-4d42-903a-33495456a622",
            "applicableTenantId",
            tenant);
    return canonical(
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope", scope,
            "accountProjectionEvidence", projection,
            "issuanceOperationIdentity", operation,
            "issuanceKind", "human_operator",
            "authorityTuple", authority,
            "membershipVersion", Map.of(tenant, 5L),
            "issuanceFence", "23",
            "issuanceEvidence", issuance));
  }

  private static byte[] captureReference(StartSessionPostAuthorizationExecutionTuple tuple) {
    return canonical(
        Map.of(
            "schema", "account-start-session-authority-capture/v1",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "capturedAt", "2026-10-09T10:11:12.123Z",
            "bundleReference",
                Map.of(
                    "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion", tuple.bundleReference().sourceVersion(),
                    "sourceFence", tuple.bundleReference().sourceFence(),
                    "linearization", tuple.bundleReference().linearization()),
            "snapshotSha256", "6".repeat(64)));
  }

  private static SourceEvidence source(UUID tenant) {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        tenant.toString(),
        "2",
        "17",
        null,
        null,
        "activation-source-snapshot".getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      WorldCanonicalInstanceLifecycleEvidence preparing,
      CompleteLaunchBindingEvidence binding,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request launchRequest,
      UUID playableNamespaceId) {}
}
