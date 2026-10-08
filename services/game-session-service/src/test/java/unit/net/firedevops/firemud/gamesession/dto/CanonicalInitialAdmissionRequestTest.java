package unit.net.firedevops.firemud.gamesession.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.service.impl.DefaultDeniedCanonicalInitialAdmissionWorldVerifier;
import org.junit.jupiter.api.Test;

class CanonicalInitialAdmissionRequestTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID CANONICAL_VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void gameSessionRequestDigestIsBareAndWorldBindingDigestRemainsDistinct() {
    CanonicalInitialAdmissionRequest request = noPriorRequest();

    assertThat(request.requestDigest()).matches("[0-9a-f]{64}").doesNotStartWith("sha256:");
    assertThat(request.holdBindingDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(request.holdBindingDigest()).isNotEqualTo("sha256:" + request.requestDigest());
    assertThat(request.canonicalVersionId()).isEqualTo(CANONICAL_VERSION);
  }

  @Test
  void bothOriginsRequireTheirExactPriorPointerShape() {
    assertThatThrownBy(
            () ->
                new CanonicalInitialAdmissionRequest(
                    "prod",
                    TENANT,
                    "green-hollow",
                    REALM,
                    PLAYABLE_NAMESPACE,
                    "SHARED",
                    GAME_INSTANCE,
                    CANONICAL_VERSION,
                    7L,
                    12L,
                    OriginKind.NO_PRIOR_POINTER,
                    13L,
                    "gs-initial-admission-17",
                    noPriorRequest().requestDigest(),
                    HOLD_ID,
                    HOLD_FENCE,
                    "sha256:" + "c".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry");

    assertThatThrownBy(
            () ->
                new CanonicalInitialAdmissionRequest(
                    "prod",
                    TENANT,
                    "green-hollow",
                    REALM,
                    PLAYABLE_NAMESPACE,
                    "SHARED",
                    GAME_INSTANCE,
                    CANONICAL_VERSION,
                    7L,
                    12L,
                    OriginKind.EXPECT_CLOSED,
                    null,
                    "gs-initial-admission-17",
                    noPriorRequest().requestDigest(),
                    HOLD_ID,
                    HOLD_FENCE,
                    "sha256:" + "c".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires a positive");
  }

  @Test
  void worldProofMustMatchCanonicalVersionOriginCatalogAndHoldBinding() {
    CanonicalInitialAdmissionRequest request = noPriorRequest();
    CanonicalInitialAdmissionWorldProof proof =
        new CanonicalInitialAdmissionWorldProof(
            request.initialAdmissionRequestId(),
            request.requestDigest(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.realmId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.canonicalGameInstanceId(),
            request.canonicalVersionId(),
            "ACTIVE",
            request.activeLifecycleEpoch(),
            request.originKind(),
            request.expectedCatalogRevision(),
            request.expectedPriorPointerVersion(),
            request.holdId(),
            request.holdFence(),
            request.holdBindingDigest());

    proof.requireMatches(request);
    assertThatThrownBy(
            () ->
                new CanonicalInitialAdmissionWorldProof(
                        proof.initialAdmissionRequestId(),
                        proof.requestDigest(),
                        proof.targetNamespace(),
                        proof.canonicalTenantId(),
                        proof.worldSlug(),
                        proof.realmId(),
                        proof.playableStateNamespaceId(),
                        proof.playableStateScope(),
                        proof.canonicalGameInstanceId(),
                        uuid("66666666-6666-4666-8666-666666666666"),
                        proof.lifecycleState(),
                        proof.activeLifecycleEpoch(),
                        proof.originKind(),
                        proof.expectedCatalogRevision(),
                        proof.expectedPriorPointerVersion(),
                        proof.holdId(),
                        proof.holdFence(),
                        proof.holdBindingDigest())
                    .requireMatches(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void unauthenticatedDefaultWorldVerifierNeverTreatsCallerTupleAsOwnerProof() {
    assertThatThrownBy(
            () ->
                new DefaultDeniedCanonicalInitialAdmissionWorldVerifier().verify(noPriorRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("denied until authenticated World");
  }

  private static CanonicalInitialAdmissionRequest noPriorRequest() {
    String requestId = "gs-initial-admission-17";
    String digest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            "prod",
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            GAME_INSTANCE,
            CANONICAL_VERSION,
            7L,
            12L,
            OriginKind.NO_PRIOR_POINTER,
            null,
            requestId);
    return new CanonicalInitialAdmissionRequest(
        "prod",
        TENANT,
        "green-hollow",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        GAME_INSTANCE,
        CANONICAL_VERSION,
        7L,
        12L,
        OriginKind.NO_PRIOR_POINTER,
        null,
        requestId,
        digest,
        HOLD_ID,
        HOLD_FENCE,
        "sha256:" + "c".repeat(64));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
