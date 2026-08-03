package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.common.account.AccountProfileJson;
import org.junit.jupiter.api.Test;

class AccountRuntimeStubServerTest {
  @Test
  void authenticationCanonicalizesMappedEmailAndMissingMembershipDeniesAdmission()
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

        server.setMembershipExists(false);

        var membership =
            stub.getTenantMembershipForRuntime(
                GetTenantMembershipForRuntimeRequest.newBuilder()
                    .setAccountId("7")
                    .setTenantId("1")
                    .setRequestId("request-1")
                    .build());
        assertThat(membership.getMembershipExists()).isFalse();
        assertThat(membership.getGameplayAdmissionAllowed()).isFalse();
      } finally {
        channel.shutdownNow();
      }
    }
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
