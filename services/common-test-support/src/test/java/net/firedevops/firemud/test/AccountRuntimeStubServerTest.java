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
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.common.account.AccountProfileJson;
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
                .setAccountId("7")
                .setTenantId("1")
                .setRequestId("request-1")
                .build();
        var activeMembership = stub.getTenantMembershipForRuntime(request);
        assertThat(activeMembership.getMembershipExists()).isTrue();
        assertThat(activeMembership.getGameplayAdmissionAllowed()).isTrue();
        assertThat(activeMembership.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(activeMembership.getMembershipVersion()).isEqualTo(1L);
        assertThat(activeMembership.getMembershipAuthorityGeneration()).isPositive();
        assertFresh(activeMembership.getEvaluatedAt());

        server.setGameplayAdmissionAllowed(false);
        var deniedMembership =
            stub.getTenantMembershipForRuntime(
                GetTenantMembershipForRuntimeRequest.newBuilder()
                    .setAccountId("7")
                    .setTenantId("1")
                    .setRequestId("request-denied")
                    .build());
        assertThat(deniedMembership.getMembershipExists()).isTrue();
        assertThat(deniedMembership.getGameplayAdmissionAllowed()).isFalse();
        assertThat(deniedMembership.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(deniedMembership.getMembershipVersion()).isEqualTo(1L);
        assertThat(deniedMembership.getMembershipAuthorityGeneration()).isEqualTo(1L);
        assertFresh(deniedMembership.getEvaluatedAt());

        server.setMembershipInactive();
        var inactive = stub.getTenantMembershipForRuntime(request);
        assertThat(inactive.getMembershipExists()).isTrue();
        assertThat(inactive.getGameplayAdmissionAllowed()).isFalse();
        assertThat(inactive.getMembershipLifecycleState()).isEqualTo("INACTIVE");

        server.setMembershipExists(false);
        var membership = stub.getTenantMembershipForRuntime(request);
        assertThat(membership.getMembershipExists()).isFalse();
        assertThat(membership.getGameplayAdmissionAllowed()).isFalse();
        assertThat(membership.getMembershipLifecycleState()).isEqualTo("MISSING");
        assertThat(membership.getMembershipVersion()).isZero();
        assertThat(membership.getMembershipAuthorityGeneration()).isZero();
        assertFresh(membership.getEvaluatedAt());

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
