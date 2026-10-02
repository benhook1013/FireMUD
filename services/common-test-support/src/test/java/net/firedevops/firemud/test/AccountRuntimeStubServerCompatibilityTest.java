package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.Set;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeRequest;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipRequest;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;

class AccountRuntimeStubServerCompatibilityTest {
  private static final Set<String> NON_RUNTIME_METHODS =
      Set.of(
          "CreateAccount",
          "GetProfile",
          "ListPresenceVisibilityPolicies",
          "UpdateProfile",
          "ExportAccount",
          "ExportTenantData",
          "DeleteAccount",
          "RequestPasswordReset",
          "CompletePasswordReset",
          "LinkExternalAccount",
          "RequestEmailLoginOtp",
          "RequestEmailVerification",
          "VerifyEmail",
          "VerifyEmailLoginOtp");

  @Test
  void accountServiceMethodSetIsFullyCategorized() {
    Set<String> actualMethods =
        AccountServiceGrpc.getServiceDescriptor().getMethods().stream()
            .map(method -> method.getBareMethodName())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(actualMethods)
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.Stream.concat(
                    AccountRuntimeStubServer.implementedRuntimeMethodNames().stream(),
                    NON_RUNTIME_METHODS.stream())
                .collect(java.util.stream.Collectors.toSet()));

    assertThat(AccountRuntimeStubServer.implementedRuntimeMethodNames())
        .contains("IssueDirectTextConnectScope", "JoinPublicProductionMembership");
  }

  @Test
  void runtimeMembershipLifecycleRemainsSeparateFromAdmissionAndJoinDoesNotGrantRealmAccess()
      throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);

        server.denyGameplayAdmission();
        var existingButNonAdmitting =
            stub.getTenantMembershipForRuntime(membershipRequest("7", "11"));
        assertThat(existingButNonAdmitting.getMembershipExists()).isTrue();
        assertThat(existingButNonAdmitting.getGameplayAdmissionAllowed()).isFalse();
        assertThat(existingButNonAdmitting.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(existingButNonAdmitting.getMembershipAuthorityGeneration()).isEqualTo(1L);

        server.setMembershipExists(false);
        var missing = stub.getTenantMembershipForRuntime(membershipRequest("7", "11"));
        assertThat(missing.getMembershipExists()).isFalse();
        assertThat(missing.getGameplayAdmissionAllowed()).isFalse();
        assertThat(missing.getMembershipLifecycleState()).isEqualTo("MISSING");
        assertThat(missing.getMembershipAuthorityGeneration()).isZero();

        server.setRealmAccessGranted(false);
        PlayerExecutionContext caller =
            PlayerExecutionContext.newBuilder()
                .setAccountId("7")
                .setTenantId("11")
                .setRealmId("22")
                .setGameInstanceId("33")
                .setPlayableStateNamespaceId("namespace-1")
                .setPlayableStateScope("SHARED")
                .setRequestId("join-request-1")
                .build();
        var connectScope =
            stub.issueDirectTextConnectScope(
                IssueDirectTextConnectScopeRequest.newBuilder()
                    .setPlayerContext(caller)
                    .setTenantId("11")
                    .setWorldSlug("demo")
                    .setRealmSlug("live")
                    .setRealmId("22")
                    .setPlayableStateNamespaceId("namespace-1")
                    .setPlayableStateScope("SHARED")
                    .setGameInstanceId("33")
                    .build());
        assertThat(connectScope.getError().getCode()).isEmpty();

        var join =
            stub.joinPublicProductionMembership(
                JoinPublicProductionMembershipRequest.newBuilder()
                    .setPlayerContext(caller)
                    .setConnectScopeId(connectScope.getConnectScopeId())
                    .setRequestId("join-request-1")
                    .build());
        assertThat(join.getSuccess()).isTrue();

        var afterJoin = stub.getTenantMembershipForRuntime(membershipRequest("7", "11"));
        assertThat(afterJoin.getMembershipExists()).isTrue();
        assertThat(afterJoin.getGameplayAdmissionAllowed()).isTrue();
        assertThat(afterJoin.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(afterJoin.getMembershipAuthorityGeneration()).isEqualTo(1L);

        var realmGrant =
            stub.getRealmAccessGrantForRuntime(
                GetRealmAccessGrantForRuntimeRequest.newBuilder()
                    .setAccountId("7")
                    .setTenantId("11")
                    .setWorldSlug("demo")
                    .setRealmSlug("live")
                    .build());
        assertThat(realmGrant.getGranted()).isFalse();
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private static GetTenantMembershipForRuntimeRequest membershipRequest(
      String accountId, String tenantId) {
    return GetTenantMembershipForRuntimeRequest.newBuilder()
        .setAccountId(accountId)
        .setTenantId(tenantId)
        .setRequestId("membership-request")
        .build();
  }
}
