package unit.net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Purpose;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadEvidence;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadGrpcCodec;
import net.firedevops.firemud.entitymanagement.client.GameSessionPreseededActorAssignmentOwnerReadClient;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload.ActorKind;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionPreseededActorAssignmentOwnerEvidenceAdapter;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GetPreseededActorAssignmentOwnerReadResponse;
import org.junit.jupiter.api.Test;

class GameSessionPreseededActorAssignmentOwnerEvidenceAdapterTest {
  private static final String NAMESPACE = "gameplay";
  private static final UUID ASSIGNMENT = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ACCOUNT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID OTHER_TENANT = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID REALM = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID PLAYABLE_NAMESPACE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID INSTANCE = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID VERSION = uuid("66666666-6666-4666-8666-666666666666");

  @Test
  void mapsCompleteCurrentSourceAndAccountEvidenceToExactEligibleTarget() throws Exception {
    SourceFixture fixture = sourceFixture();
    var sourceRequest = fixture.evidence().request();
    String sourceNamespace = sourceRequest.targetNamespace();
    var request = request(sourceRequest);
    var staging =
        staging(
            sourceNamespace,
            request.assignmentUuid(),
            request.canonicalAccountUuid(),
            request.expectedTarget().canonicalTenantUuid());
    var client = mock(GameSessionPreseededActorAssignmentOwnerReadClient.class);
    when(client.getPreseededActorAssignmentOwnerRead(sourceRequest)).thenReturn(fixture.evidence());

    var result =
        new GameSessionPreseededActorAssignmentOwnerEvidenceAdapter(client, sourceNamespace)
            .resolveCurrentEligibleTarget(
                request,
                identity(sourceNamespace, request.assignmentUuid(), request.canonicalAccountUuid()),
                staging);

    var selectedPolicy = fixture.evidence().sourceEvidence().selectedPolicyEvidence();
    var ownerProof = fixture.evidence().sourceEvidence().gameSessionOwnerProof();
    assertThat(result.assignmentUuid()).isEqualTo(request.assignmentUuid());
    assertThat(result.canonicalAccountUuid()).isEqualTo(request.canonicalAccountUuid());
    assertThat(request.expectedTarget().exactlyMatches(result)).isTrue();
    assertThat(result.eligibility())
        .isEqualTo(PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE);
    assertThat(result.eligibilityEvaluatedAt()).isEqualTo(staging.observedAt());
    assertThat(result.membershipAuthorityGeneration())
        .isEqualTo(staging.membershipAuthorityGeneration());
    assertThat(result.eligibilityEvidenceDigest()).isEqualTo(staging.eligibilityDecisionDigest());
    assertThat(result.canonicalTenantUuid()).isEqualTo(sourceRequest.canonicalTenantUuid());
    assertThat(result.realmUuid()).isEqualTo(sourceRequest.realmUuid());
    assertThat(result.worldSlug()).isEqualTo(sourceRequest.worldSlug());
    assertThat(result.realmSlug()).isEqualTo(sourceRequest.realmSlug());
    assertThat(result.gameInstanceId())
        .isEqualTo(sourceRequest.canonicalGameInstanceUuid().toString());
    assertThat(result.catalogRevision()).isEqualTo(sourceRequest.expectedCatalogRevision());
    assertThat(result.canonicalVersionUuid()).isEqualTo(sourceRequest.canonicalVersionUuid());
    assertThat(result.frozenPolicyDigest()).isEqualTo(rawDigest(selectedPolicy.policyDigest()));
    assertThat(result.playableStateNamespaceId())
        .isEqualTo(sourceRequest.playableStateNamespaceUuid());
    assertThat(result.playableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(result.entryPolicy())
        .isEqualTo(PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY);
    assertThat(result.accountPurpose())
        .isEqualTo(PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING);
    assertThat(result.accountCurrentness())
        .isEqualTo(
            PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION);
    assertThat(result.accountAuthoritySnapshotDigest())
        .isEqualTo(staging.authoritySnapshotDigest());
    assertThat(result.publishedOwnerProofDigest()).isEqualTo(rawDigest(ownerProof.proofDigest()));
    assertThat(result.publishedReleaseBundleRef())
        .isEqualTo(
            fixture
                .evidence()
                .sourceEvidence()
                .publishedPolicySetEvidence()
                .publishedReleaseBundleRef());
  }

  @Test
  void rejectsValidSourceResponseBoundToDifferentAssignmentBeforeReturningEligibleTarget()
      throws Exception {
    SourceFixture fixture = sourceFixture();
    var sourceRequest = fixture.evidence().request();
    String sourceNamespace = sourceRequest.targetNamespace();
    UUID differentAssignment = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    var request = request(differentAssignment, sourceRequest);
    var staging =
        staging(
            sourceNamespace,
            request.assignmentUuid(),
            request.canonicalAccountUuid(),
            request.expectedTarget().canonicalTenantUuid());
    var client = mock(GameSessionPreseededActorAssignmentOwnerReadClient.class);
    when(client.getPreseededActorAssignmentOwnerRead(
            org.mockito.ArgumentMatchers.any(
                PreseededActorAssignmentOwnerReadEvidence.Request.class)))
        .thenReturn(fixture.evidence());

    assertThatThrownBy(
            () ->
                new GameSessionPreseededActorAssignmentOwnerEvidenceAdapter(client, sourceNamespace)
                    .resolveCurrentEligibleTarget(
                        request,
                        identity(
                            sourceNamespace,
                            request.assignmentUuid(),
                            request.canonicalAccountUuid()),
                        staging))
        .isInstanceOf(
            PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException.class);
  }

  @Test
  void accountSnapshotMustBindExactAssignmentAccountTenantAndCurrentStagingPurpose() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    var adapter = adapter(channelFactory);

    assertThatThrownBy(
            () ->
                adapter.resolveCurrentEligibleTarget(
                    request(INSTANCE.toString()), identity(), staging(OTHER_TENANT)))
        .isInstanceOf(
            PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException.class);

    verifyNoInteractions(channelFactory);
  }

  @Test
  void numericLegacyGameInstanceIdsAreNotConvertedIntoCanonicalUuidSelectors() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    var adapter = adapter(channelFactory);

    assertThatThrownBy(
            () -> adapter.resolveCurrentEligibleTarget(request("73"), identity(), staging(TENANT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gameInstanceId must be a canonical non-nil UUID");

    verifyNoInteractions(channelFactory);
  }

  private static GameSessionPreseededActorAssignmentOwnerEvidenceAdapter adapter(
      GrpcChannelFactory channelFactory) {
    var client =
        new GameSessionPreseededActorAssignmentOwnerReadClient(
            new ServiceEndpointsProperties(), tlsProperties(), channelFactory, NAMESPACE);
    return new GameSessionPreseededActorAssignmentOwnerEvidenceAdapter(client, NAMESPACE);
  }

  private static PreseededActorAssignmentRequest request(String gameInstanceId) {
    return new PreseededActorAssignmentRequest(
        ASSIGNMENT,
        ACCOUNT,
        new PreseededActorCorePayload(ActorKind.PLAYER, "Ari"),
        new PreseededActorAssignmentExpectedTarget(
            TENANT,
            REALM,
            "earth",
            "main",
            gameInstanceId,
            71L,
            VERSION,
            "a".repeat(64),
            PLAYABLE_NAMESPACE,
            "release:exact",
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
  }

  private static PreseededActorAssignmentRequest request(
      PreseededActorAssignmentOwnerReadEvidence.Request sourceRequest) {
    return request(sourceRequest.assignmentUuid(), sourceRequest);
  }

  private static PreseededActorAssignmentRequest request(
      UUID assignmentUuid, PreseededActorAssignmentOwnerReadEvidence.Request sourceRequest) {
    return new PreseededActorAssignmentRequest(
        assignmentUuid,
        sourceRequest.canonicalAccountUuid(),
        new PreseededActorCorePayload(ActorKind.PLAYER, "Ari"),
        new PreseededActorAssignmentExpectedTarget(
            sourceRequest.canonicalTenantUuid(),
            sourceRequest.realmUuid(),
            sourceRequest.worldSlug(),
            sourceRequest.realmSlug(),
            sourceRequest.canonicalGameInstanceUuid().toString(),
            sourceRequest.expectedCatalogRevision(),
            sourceRequest.canonicalVersionUuid(),
            sourceRequest.expectedPolicyDigest(),
            sourceRequest.playableStateNamespaceUuid(),
            sourceRequest.expectedPublishedReleaseBundleRef(),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
  }

  private static RuntimeAccountIdentityEvidence identity() {
    return identity(NAMESPACE, ASSIGNMENT, ACCOUNT);
  }

  private static RuntimeAccountIdentityEvidence identity(
      String namespace, UUID assignmentUuid, UUID accountUuid) {
    return new RuntimeAccountIdentityEvidence(
        1, namespace, assignmentUuid, accountUuid, 73L, "ACCOUNT_REPOSITORY_INSERT");
  }

  private static AccountActorStagingEligibilityEvidence staging(UUID tenant) {
    return staging(NAMESPACE, ASSIGNMENT, ACCOUNT, tenant);
  }

  private static AccountActorStagingEligibilityEvidence staging(
      String namespace, UUID assignmentUuid, UUID accountUuid, UUID tenant) {
    return AccountActorStagingEligibilityEvidence.seal(
        namespace,
        assignmentUuid,
        accountUuid,
        tenant,
        Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
        Instant.parse("2026-10-08T03:04:05Z"),
        "ACCOUNT_REPOSITORY_INSERT",
        "ACTIVE",
        "ACTIVE",
        true,
        "EXPLICIT_JOIN",
        17L,
        19L,
        "FRESH_GAME_DESIGN",
        uuid("77777777-7777-4777-8777-777777777777"),
        "sha256:" + "b".repeat(64),
        23L,
        uuid("88888888-8888-4888-8888-888888888888"),
        "sha256:" + "c".repeat(64),
        false);
  }

  private static SourceFixture sourceFixture() throws IOException {
    // Synthetic evidence from Common PreseededActorAssignmentOwnerReadEvidenceTest.fixture(),
    // serialized and decoded through the public codec; it does not establish live owner authority.
    try (var input =
        GameSessionPreseededActorAssignmentOwnerEvidenceAdapterTest.class.getResourceAsStream(
            "/gameplay/preseeded-assignment-owner-read.bin.gz")) {
      if (input == null) {
        throw new IllegalStateException("Frozen assignment owner evidence fixture is missing");
      }
      try (var gzip = new GZIPInputStream(input)) {
        var response = GetPreseededActorAssignmentOwnerReadResponse.parseFrom(gzip.readAllBytes());
        var request = PreseededActorAssignmentOwnerReadGrpcCodec.fromRequest(response.getRequest());
        return new SourceFixture(
            PreseededActorAssignmentOwnerReadGrpcCodec.fromResponse(request, response));
      }
    }
  }

  private static String rawDigest(String digest) {
    return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
  }

  private record SourceFixture(PreseededActorAssignmentOwnerReadEvidence evidence) {}

  private static CommonGrpcClientProperties tlsProperties() {
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain("entity-client.crt");
    properties.setPrivateKey("entity-client.key");
    properties.setCaCert("game-session-ca.crt");
    return properties;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
