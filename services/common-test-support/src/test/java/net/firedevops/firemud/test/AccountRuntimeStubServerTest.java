package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Instant;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.common.account.AccountProfileJson;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;

class AccountRuntimeStubServerTest {
  @Test
  void authenticationCanonicalizesEmailAndRuntimeAuthoritySnapshotsAreFreshAndComplete()
      throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        server.mapAccountId("demo@example.com", 7L);

        assertThat(
                stub.authenticate(
                        AuthenticateRequest.newBuilder()
                            .setEmail("  DEMO@EXAMPLE.COM ")
                            .setPassword("password")
                            .build())
                    .getAccountId())
            .isEqualTo("7");

        var request =
            GetTenantMembershipForRuntimeRequest.newBuilder()
                .setPlayerContext(
                    net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                        .setAccountId("7")
                        .setTenantId("1")
                        .setRequestId("request-1"))
                .build();
        Instant activeBefore = Instant.now();
        GetTenantMembershipForRuntimeResponse active = stub.getTenantMembershipForRuntime(request);
        Instant activeAfter = Instant.now();
        assertThat(active.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(active.getMembershipExists()).isTrue();
        assertThat(active.getGameplayAdmissionAllowed()).isTrue();
        assertThat(active.getMembershipVersionMap()).containsEntry(active.getTenantId(), "1");
        assertThat(active.getRequestId()).isEqualTo("request-1");
        assertThat(active.getMembershipAuthorityGeneration()).isEqualTo("1");
        assertMembershipEventMatches(active, "ACTIVE", true);
        assertThat(Instant.parse(active.getEvaluatedAt())).isBetween(activeBefore, activeAfter);

        var secondRequest =
            request.toBuilder()
                .setPlayerContext(
                    request.getPlayerContext().toBuilder().setRequestId("request-2").build())
                .build();
        var secondActive = stub.getTenantMembershipForRuntime(secondRequest);
        assertThat(secondActive.getRequestId()).isEqualTo("request-2");
        assertMembershipEventMatches(secondActive, "ACTIVE", true);
        assertThat(secondActive.getOutboxSourceEvidence(0).getCanonicalEventJson())
            .isEqualTo(active.getOutboxSourceEvidence(0).getCanonicalEventJson());
        assertThat(secondActive.getOutboxSourceEvidence(0).getEventDigest())
            .isEqualTo(active.getOutboxSourceEvidence(0).getEventDigest());

        server.setGameplayAdmissionAllowed(false);
        Instant deniedBefore = Instant.now();
        var deniedMembership =
            stub.getTenantMembershipForRuntime(
                request.toBuilder()
                    .setPlayerContext(
                        request.getPlayerContext().toBuilder().setRequestId("request-denied"))
                    .build());
        Instant deniedAfter = Instant.now();
        assertThat(deniedMembership.getMembershipExists()).isTrue();
        assertThat(deniedMembership.getGameplayAdmissionAllowed()).isFalse();
        assertThat(deniedMembership.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(deniedMembership.getRequestId()).isEqualTo("request-denied");
        assertThat(deniedMembership.getMembershipVersionMap())
            .containsEntry(deniedMembership.getTenantId(), "1");
        assertThat(deniedMembership.getMembershipAuthorityGeneration()).isEqualTo("1");
        assertThat(deniedMembership.getRolesList()).containsExactly("player");
        assertMembershipEventMatches(deniedMembership, "ACTIVE", false);
        assertThat(Instant.parse(deniedMembership.getEvaluatedAt()))
            .isBetween(deniedBefore, deniedAfter);

        server.setMembershipInactive();
        Instant inactiveBefore = Instant.now();
        var inactive = stub.getTenantMembershipForRuntime(request);
        Instant inactiveAfter = Instant.now();
        assertThat(inactive.getMembershipExists()).isTrue();
        assertThat(inactive.getGameplayAdmissionAllowed()).isFalse();
        assertThat(inactive.getMembershipLifecycleState()).isEqualTo("INACTIVE");
        assertThat(inactive.hasMembershipBaseline()).isTrue();
        assertThat(inactive.hasAuthorityTuple()).isTrue();
        assertThat(inactive.getOutboxCheckpointsCount()).isEqualTo(4);
        assertThat(inactive.getOutboxSourceEvidenceCount()).isEqualTo(1);
        assertMembershipEventMatches(inactive, "INACTIVE", false);
        assertThat(Instant.parse(inactive.getEvaluatedAt()))
            .isBetween(inactiveBefore, inactiveAfter);

        server.setMembershipExists(false);
        Instant missingBefore = Instant.now();
        var membership = stub.getTenantMembershipForRuntime(request);
        Instant missingAfter = Instant.now();
        assertThat(membership.getMembershipExists()).isFalse();
        assertThat(membership.getGameplayAdmissionAllowed()).isFalse();
        assertThat(membership.getMembershipLifecycleState()).isEqualTo("MISSING");
        assertThat(membership.getMembershipVersionMap())
            .containsEntry(membership.getTenantId(), "1");
        assertThat(membership.getMembershipAuthorityGeneration()).isEqualTo("1");
        assertThat(membership.getMembershipBaseline().getMembershipVersionMap())
            .isEqualTo(membership.getMembershipVersionMap());
        assertThat(membership.getOutboxCheckpointsList())
            .filteredOn(checkpoint -> checkpoint.getOutboxStreamKey().contains(":membership/"))
            .extracting(checkpoint -> checkpoint.getOutboxSequence())
            .containsExactly("0");
        assertThat(membership.getOutboxSourceEvidenceCount()).isZero();
        assertThat(Instant.parse(membership.getEvaluatedAt()))
            .isBetween(missingBefore, missingAfter);

        var grant =
            stub.getRealmAccessGrantForRuntime(
                GetRealmAccessGrantForRuntimeRequest.newBuilder()
                    .setAccountId("7")
                    .setTenantId("1")
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setRequestId("request-grant")
                    .build());
        assertThat(grant.getGranted()).isTrue();
        assertThat(grant.getGrantVersion()).isEqualTo(1L);
        assertFresh(grant.getEvaluatedAt());

        var entitlement =
            stub.getTenantEntitlementsForRuntime(
                GetTenantEntitlementsForRuntimeRequest.newBuilder()
                    .setTenantId("1")
                    .setRequestId("request-entitlement")
                    .build());
        assertThat(entitlement.getGameplayAvailable()).isTrue();
        assertThat(entitlement.getAllowPublicJoin()).isTrue();
        assertThat(entitlement.getEntitlementVersion()).isEqualTo(1L);
        assertThat(entitlement.getTenantBillingSequence()).isEqualTo(1L);
        assertFresh(entitlement.getEvaluatedAt());
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private static void assertMembershipEventMatches(
      GetTenantMembershipForRuntimeResponse response, String lifecycle, boolean admitted) {
    var source = response.getOutboxSourceEvidence(0);
    var event = MembershipAuthorityEventV1Codec.verify(source.getCanonicalEventJson());
    assertThat(event.canonicalJson()).isEqualTo(source.getCanonicalEventJson());
    assertThat(event.eventDigest()).isEqualTo(source.getEventDigest());
    assertThat(event.eventId()).isEqualTo(source.getEventId());
    assertThat(event.accountId()).isEqualTo(response.getAccountId());
    assertThat(event.tenantId()).isEqualTo(response.getTenantId());
    assertThat(event.outboxStreamKey()).isEqualTo(source.getOutboxStreamKey());
    assertThat(event.outboxSequence()).isEqualTo(source.getOutboxSequence());
    assertThat(event.membershipLifecycleState()).isEqualTo(lifecycle);
    assertThat(event.membershipVersion()).isEqualTo(response.getMembershipVersionMap());
    assertThat(event.membershipAuthorityGeneration())
        .isEqualTo(response.getMembershipAuthorityGeneration());
    assertThat(event.authorityTuple().issuerAuthGeneration())
        .isEqualTo(response.getAuthorityTuple().getIssuerAuthGeneration());
    assertThat(event.authorityTuple().accountAuthorityGeneration())
        .isEqualTo(response.getAuthorityTuple().getAccountAuthorityGeneration());
    assertThat(event.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(response.getAuthorityTuple().getTenantAuthorityGenerationMap());
    assertThat(event.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(response.getAuthorityTuple().getMembershipAuthorityGenerationMap());
    assertThat(event.authorityTuple().privateRealmGrantVersions()).isEmpty();
    assertThat(event.issuanceFence()).isEqualTo(response.getIssuanceFence());
    assertThat(event.roles()).containsExactlyElementsOf(response.getRolesList());
    assertThat(event.gameplayAdmissionAllowed()).isEqualTo(admitted);
  }

  private static void assertFresh(String evaluatedAt) {
    Instant evaluated = Instant.parse(evaluatedAt);
    Instant now = Instant.now();
    assertThat(!evaluated.isBefore(now.minusSeconds(15))).isTrue();
    assertThat(!evaluated.isAfter(now)).isTrue();
  }

  @Test
  void profileReadAndWriteSupportVisibilityPolicyRoundTrips() throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);

        AccountProfileJson initialProfile =
            AccountProfileJson.parse(
                stub.getProfile(
                        GetProfileRequest.newBuilder().setTenantId("1").setAccountId("7").build())
                    .getProfileJson(),
                "FRIENDS_ONLY");
        assertThat(initialProfile.presenceVisibilityPolicy()).isEqualTo("FRIENDS_ONLY");

        assertThat(
                stub.updateProfile(
                        UpdateProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId("7")
                            .setProfileJson(
                                """
                                {"displayName":"Demo-7","bio":null,"presenceVisibilityPolicy":"PRIVATE"}
                                """)
                            .build())
                    .getSuccess())
            .isTrue();

        AccountProfileJson updatedProfile =
            AccountProfileJson.parse(
                stub.getProfile(
                        GetProfileRequest.newBuilder().setTenantId("1").setAccountId("7").build())
                    .getProfileJson(),
                "FRIENDS_ONLY");
        assertThat(updatedProfile.presenceVisibilityPolicy()).isEqualTo("PRIVATE");
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void resetRuntimeStateRestoresDefaultVisibilityPolicy() throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);

        assertThat(
                stub.updateProfile(
                        UpdateProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId("7")
                            .setProfileJson(
                                """
                                {"displayName":"Demo-7","bio":null,"presenceVisibilityPolicy":"PRIVATE"}
                                """)
                            .build())
                    .getSuccess())
            .isTrue();

        server.resetRuntimeState();

        AccountProfileJson resetProfile =
            AccountProfileJson.parse(
                stub.getProfile(
                        GetProfileRequest.newBuilder().setTenantId("1").setAccountId("7").build())
                    .getProfileJson(),
                "FRIENDS_ONLY");
        assertThat(resetProfile.presenceVisibilityPolicy()).isEqualTo("FRIENDS_ONLY");
      } finally {
        channel.shutdownNow();
      }
    }
  }
}
