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
            .isEqualTo(AccountRuntimeStubServer.accountUuidForTestFixture(7L));

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
        server.mapAccountId("demo@example.com", 7L);
        String accountUuid = AccountRuntimeStubServer.accountUuidForTestFixture(7L);

        AccountProfileJson initialProfile =
            AccountProfileJson.parse(
                stub.getProfile(
                        GetProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId(accountUuid)
                            .build())
                    .getProfileJson(),
                "FRIENDS_ONLY");
        assertThat(initialProfile.presenceVisibilityPolicy()).isEqualTo("FRIENDS_ONLY");

        assertThat(
                stub.updateProfile(
                        UpdateProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId(accountUuid)
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
                        GetProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId(accountUuid)
                            .build())
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
        server.setDefaultAccountId(7L);
        String accountUuid = authenticate(stub);

        assertThat(
                stub.updateProfile(
                        UpdateProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId(accountUuid)
                            .setProfileJson(
                                """
                                {"displayName":"Demo-7","bio":null,"presenceVisibilityPolicy":"PRIVATE"}
                                """)
                            .build())
                    .getSuccess())
            .isTrue();

        server.resetRuntimeState();
        var deniedAfterReset =
            stub.getProfile(
                GetProfileRequest.newBuilder().setTenantId("1").setAccountId(accountUuid).build());
        assertThat(deniedAfterReset.getProfileJson()).isEmpty();
        assertThat(deniedAfterReset.getError().getCode()).isEqualTo("ACCOUNT_NOT_FOUND");

        accountUuid = authenticate(stub);

        AccountProfileJson resetProfile =
            AccountProfileJson.parse(
                stub.getProfile(
                        GetProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId(accountUuid)
                            .build())
                    .getProfileJson(),
                "FRIENDS_ONLY");
        assertThat(resetProfile.presenceVisibilityPolicy()).isEqualTo("FRIENDS_ONLY");
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void profileReadAndWriteRejectUnknownAndMalformedAccountUuids() throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        String unregisteredUuid = AccountRuntimeStubServer.accountUuidForTestFixture(9L);

        for (String accountId : new String[] {unregisteredUuid, "9", "not-a-uuid"}) {
          var read =
              stub.getProfile(
                  GetProfileRequest.newBuilder().setTenantId("1").setAccountId(accountId).build());
          assertThat(read.getProfileJson()).isEmpty();
          assertThat(read.getError().getCode()).isEqualTo("ACCOUNT_NOT_FOUND");

          var write =
              stub.updateProfile(
                  UpdateProfileRequest.newBuilder()
                      .setTenantId("1")
                      .setAccountId(accountId)
                      .setProfileJson(
                          "{\"displayName\":\"Changed\",\"bio\":null,\"presenceVisibilityPolicy\":\"PRIVATE\"}")
                      .build());
          assertThat(write.getSuccess()).isFalse();
          assertThat(write.getError().getCode()).isEqualTo("ACCOUNT_NOT_FOUND");
        }

        server.setDefaultAccountId(9L);
        String authenticatedUuid = authenticate(stub);
        AccountProfileJson profile =
            AccountProfileJson.parse(
                stub.getProfile(
                        GetProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId(authenticatedUuid)
                            .build())
                    .getProfileJson(),
                "FRIENDS_ONLY");
        assertThat(profile.displayName()).isEqualTo("Demo-9");
        assertThat(profile.presenceVisibilityPolicy()).isEqualTo("FRIENDS_ONLY");
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private static String authenticate(AccountServiceGrpc.AccountServiceBlockingStub stub) {
    return stub.authenticate(AuthenticateRequest.newBuilder().setEmail("demo@example.com").build())
        .getAccountId();
  }
}
