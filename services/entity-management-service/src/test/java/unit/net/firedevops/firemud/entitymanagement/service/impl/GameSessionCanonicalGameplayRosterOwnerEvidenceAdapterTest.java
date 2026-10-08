package unit.net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GameSessionCanonicalGameplayRosterOwnerEvidenceAdapterTest {
  private static final String NAMESPACE = "gameplay";
  private static final UUID REQUEST_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REALM_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID NAMESPACE_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID INSTANCE_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID VERSION_ID = uuid("77777777-7777-4777-8777-777777777777");

  @Test
  void mapsOnlyCompleteSourceEvidenceAndDoesNotUseCallerDigestFieldsAsAuthority() {
    var fixture = fixture();
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    when(client.getCanonicalGameplayRosterOwnerRead(fixture.ownerReadRequest()))
        .thenReturn(fixture.evidence());
    var adapter = new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(client, NAMESPACE);

    CanonicalGameplayRosterOwnerEvidence resolved =
        adapter.resolveCurrentTarget(fixture.entityRequest());

    ArgumentCaptor<CanonicalGameplayRosterOwnerReadEvidence.Request> requestCaptor =
        ArgumentCaptor.forClass(CanonicalGameplayRosterOwnerReadEvidence.Request.class);
    verify(client).getCanonicalGameplayRosterOwnerRead(requestCaptor.capture());
    assertThat(requestCaptor.getValue()).isEqualTo(fixture.ownerReadRequest());
    assertThat(resolved.requestUuid()).isEqualTo(REQUEST_ID);
    assertThat(resolved.canonicalAccountUuid()).isEqualTo(ACCOUNT_ID);
    assertThat(resolved.target())
        .isEqualTo(
            new CanonicalGameplayRosterTarget(
                TENANT_ID,
                REALM_ID,
                "earth",
                "main",
                INSTANCE_ID,
                71L,
                73L,
                82L,
                VERSION_ID,
                "f".repeat(64),
                "release/canonical",
                "a".repeat(64),
                "b".repeat(64),
                NAMESPACE_ID,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
                CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY));
    assertThat(resolved.resolvedAt()).isNotNull();
    assertThat(resolved.target().publishedPolicyDigest())
        .isNotEqualTo(fixture.entityRequest().expectedTarget().publishedPolicyDigest());
    assertThat(resolved.target().publishedReleaseBundleRef())
        .isNotEqualTo(fixture.entityRequest().expectedTarget().publishedReleaseBundleRef());
    assertThat(resolved.target().admissionPointerSnapshotDigest())
        .isNotEqualTo(fixture.entityRequest().expectedTarget().admissionPointerSnapshotDigest());
    assertThat(resolved.target().publishedOwnerProofDigest())
        .isNotEqualTo(fixture.entityRequest().expectedTarget().publishedOwnerProofDigest());
  }

  @Test
  void rejectsMissingEvidenceAndChangedRequestEchoWithoutProducingTarget() {
    var fixture = fixture();
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    when(client.getCanonicalGameplayRosterOwnerRead(fixture.ownerReadRequest())).thenReturn(null);
    var adapter = new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(client, NAMESPACE);

    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));

    var changedEcho = mock(CanonicalGameplayRosterOwnerReadEvidence.class);
    when(changedEcho.request()).thenReturn(ownerRequest(74L, 82L, "main"));
    when(client.getCanonicalGameplayRosterOwnerRead(fixture.ownerReadRequest()))
        .thenReturn(changedEcho);
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));
  }

  @Test
  void rejectsNonterminalProofAndChangedTupleOrPointerAndEpochCounters() {
    var fixture = fixture();
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    when(client.getCanonicalGameplayRosterOwnerRead(fixture.ownerReadRequest()))
        .thenReturn(fixture.evidence());
    var adapter = new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(client, NAMESPACE);

    when(fixture.proof().outcome())
        .thenReturn(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));

    when(fixture.proof().outcome())
        .thenReturn(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    when(fixture.proof().committedPointerVersion()).thenReturn(74L);
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));

    when(fixture.proof().committedPointerVersion()).thenReturn(73L);
    when(fixture.holdRequest().activeLifecycleEpoch()).thenReturn(83L);
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));

    when(fixture.holdRequest().activeLifecycleEpoch()).thenReturn(82L);
    when(fixture.holdRequest().worldSlug()).thenReturn("other-world");
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));
  }

  @Test
  void rejectsChangedPolicyScopeEntryVisibilityAndReleaseAttestation() {
    var fixture = fixture();
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    when(client.getCanonicalGameplayRosterOwnerRead(fixture.ownerReadRequest()))
        .thenReturn(fixture.evidence());
    var adapter = new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(client, NAMESPACE);

    when(fixture.realmPolicy().publicProduction()).thenReturn(false);
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));

    when(fixture.realmPolicy().publicProduction()).thenReturn(true);
    when(fixture.realmPolicy().stateScope()).thenReturn(RealmEntryPolicy.StateScope.ISOLATED);
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));

    when(fixture.realmPolicy().stateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
    when(fixture.realmPolicy().entryPolicy()).thenReturn(null);
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));

    when(fixture.realmPolicy().entryPolicy())
        .thenReturn(RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY);
    when(fixture.policySet().publishedReleaseBundleDigest()).thenReturn("not-a-release-digest");
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));

    when(fixture.policySet().publishedReleaseBundleDigest()).thenReturn("sha256:" + "e".repeat(64));
    when(fixture.selectedPolicy().hasValidDigest(fixture.policySet())).thenReturn(false);
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));

    when(fixture.selectedPolicy().hasValidDigest(fixture.policySet())).thenReturn(true);
    when(fixture.selectedPolicy().policyDigest()).thenReturn("not-a-policy-digest");
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));
  }

  @Test
  void requiresTheSourcePointerSnapshotDigestInItsCanonicalRawHexForm() {
    var fixture = fixture();
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    when(client.getCanonicalGameplayRosterOwnerRead(fixture.ownerReadRequest()))
        .thenReturn(fixture.evidence());
    var adapter = new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(client, NAMESPACE);

    when(fixture.evidence().admissionPointerSnapshotDigest())
        .thenReturn("sha256:" + "a".repeat(64));
    assertUnavailable(() -> adapter.resolveCurrentTarget(fixture.entityRequest()));
  }

  @Test
  void unavailableTransportIsMappedToDefaultDeniedOwnerEvidence() {
    var fixture = fixture();
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    var failure = new IllegalStateException("transport detail");
    when(client.getCanonicalGameplayRosterOwnerRead(any())).thenThrow(failure);
    var adapter = new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(client, NAMESPACE);

    assertThatThrownBy(() -> adapter.resolveCurrentTarget(fixture.entityRequest()))
        .isInstanceOf(
            CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException.class)
        .hasCause(failure);
  }

  private static void assertUnavailable(ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(
            CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException.class);
  }

  private static Fixture fixture() {
    var ownerReadRequest = ownerRequest(73L, 82L, "main");
    var sourceEvidence = mock(CanonicalGameplayRosterOwnerReadEvidence.class);
    when(sourceEvidence.request()).thenReturn(ownerReadRequest);
    when(sourceEvidence.admissionPointerSnapshotDigest()).thenReturn("a".repeat(64));

    var proof = mock(GameSessionCanonicalInitialAdmissionOwnerProof.class);
    when(proof.outcome())
        .thenReturn(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    when(proof.committedPointerVersion()).thenReturn(73L);
    when(proof.auditEventId()).thenReturn(91L);
    when(proof.proofDigest()).thenReturn("sha256:" + "b".repeat(64));
    when(proof.positiveDurableAbort()).thenReturn(false);
    when(proof.terminalAt()).thenReturn(Instant.parse("2026-10-08T03:04:05Z"));
    var holdIdentity = mock(WorldCanonicalInitialAdmissionHold.HoldIdentity.class);
    var holdRequest = mock(WorldCanonicalInitialAdmissionHold.Request.class);
    when(proof.holdIdentity()).thenReturn(holdIdentity);
    when(holdIdentity.request()).thenReturn(holdRequest);
    when(holdRequest.targetNamespace()).thenReturn(NAMESPACE);
    when(holdRequest.canonicalTenantId()).thenReturn(TENANT_ID);
    when(holdRequest.worldSlug()).thenReturn("earth");
    when(holdRequest.realmId()).thenReturn(REALM_ID);
    when(holdRequest.playableStateNamespaceId()).thenReturn(NAMESPACE_ID);
    when(holdRequest.playableStateScope()).thenReturn("SHARED");
    when(holdRequest.canonicalGameInstanceId()).thenReturn(INSTANCE_ID);
    when(holdRequest.canonicalVersionId()).thenReturn(VERSION_ID);
    when(holdRequest.expectedCatalogRevision()).thenReturn(71L);
    when(holdRequest.activeLifecycleEpoch()).thenReturn(82L);
    when(sourceEvidence.gameSessionOwnerProof()).thenReturn(proof);

    var policySet = mock(PublishedRealmEntryPolicySetEvidence.class);
    var routePolicy = realmPolicy();
    var selectedPolicy = selectedPolicy(routePolicy);
    var setTarget = mock(TargetProof.class);
    when(policySet.target()).thenReturn(setTarget);
    when(setTarget.canonicalTenantId()).thenReturn(TENANT_ID);
    when(setTarget.canonicalVersionId()).thenReturn(VERSION_ID);
    when(policySet.policyCount()).thenReturn(1);
    when(policySet.policies()).thenReturn(List.of(selectedPolicy));
    when(policySet.versionNumber()).thenReturn(5);
    when(policySet.publicationVersionStateEpoch()).thenReturn(12L);
    when(policySet.publishedReleaseBundleRef()).thenReturn("release/canonical");
    when(policySet.publishedReleaseBundleDigest()).thenReturn("sha256:" + "c".repeat(64));
    when(policySet.policySetDigest()).thenReturn("sha256:" + "d".repeat(64));
    when(policySet.requireValidDigest()).thenReturn(policySet);
    when(sourceEvidence.publishedPolicySetEvidence()).thenReturn(policySet);
    when(sourceEvidence.selectedPolicyEvidence()).thenReturn(selectedPolicy);
    return new Fixture(
        ownerReadRequest,
        sourceEvidence,
        proof,
        holdRequest,
        policySet,
        selectedPolicy,
        routePolicy);
  }

  private static PublishedRealmEntryPolicyEvidence selectedPolicy(RealmEntryPolicy policy) {
    var evidence = mock(PublishedRealmEntryPolicyEvidence.class);
    when(evidence.policy()).thenReturn(policy);
    when(evidence.policyDigest()).thenReturn("sha256:" + "f".repeat(64));
    when(evidence.hasValidDigest(any())).thenReturn(true);
    return evidence;
  }

  private static RealmEntryPolicy realmPolicy() {
    var policy = mock(RealmEntryPolicy.class);
    when(policy.visible()).thenReturn(true);
    when(policy.publicProduction()).thenReturn(true);
    when(policy.stateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
    when(policy.entryPolicy()).thenReturn(RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY);
    when(policy.worldSlug()).thenReturn("earth");
    when(policy.realmSlug()).thenReturn("main");
    return policy;
  }

  private static CanonicalGameplayRosterOwnerReadEvidence.Request ownerRequest(
      long pointerVersion, long activeWorldEpoch, String realmSlug) {
    return new CanonicalGameplayRosterOwnerReadEvidence.Request(
        REQUEST_ID,
        ACCOUNT_ID,
        NAMESPACE,
        TENANT_ID,
        "earth",
        REALM_ID,
        realmSlug,
        NAMESPACE_ID,
        "SHARED",
        INSTANCE_ID,
        VERSION_ID,
        71L,
        pointerVersion,
        activeWorldEpoch);
  }

  private static CanonicalGameplayRosterReadRequest entityRequest() {
    return new CanonicalGameplayRosterReadRequest(
        REQUEST_ID,
        ACCOUNT_ID,
        new CanonicalGameplayRosterTarget(
            TENANT_ID,
            REALM_ID,
            "earth",
            "main",
            INSTANCE_ID,
            71L,
            73L,
            82L,
            VERSION_ID,
            "9".repeat(64),
            "release/caller-echo-only",
            "8".repeat(64),
            "7".repeat(64),
            NAMESPACE_ID,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      CanonicalGameplayRosterOwnerReadEvidence.Request ownerReadRequest,
      CanonicalGameplayRosterOwnerReadEvidence evidence,
      GameSessionCanonicalInitialAdmissionOwnerProof proof,
      WorldCanonicalInitialAdmissionHold.Request holdRequest,
      PublishedRealmEntryPolicySetEvidence policySet,
      PublishedRealmEntryPolicyEvidence selectedPolicy,
      RealmEntryPolicy realmPolicy) {
    CanonicalGameplayRosterReadRequest entityRequest() {
      return GameSessionCanonicalGameplayRosterOwnerEvidenceAdapterTest.entityRequest();
    }
  }
}
