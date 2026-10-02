package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.List;
import java.util.Set;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeRequest;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipRequest;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;

class AccountRuntimeStubServerCompatibilityTest {
  private static final String REALM_ID = "8a1df0f1-1b57-465e-9c4b-bb34f8153d31";
  private static final String NAMESPACE_ID = "2ea958e0-13a2-41d0-9c39-59a96cf31412";
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
  void joinMembershipVersionsRemainUnsignedOnTheirExistingFieldNumbers() {
    Descriptor descriptor =
        net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse.getDescriptor();

    assertField(descriptor, "membership_version", 6, FieldDescriptor.Type.UINT64);
    assertField(descriptor, "membership_authority_generation", 7, FieldDescriptor.Type.UINT64);
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
                .setRealmId(REALM_ID)
                .setGameInstanceId("33")
                .setSessionId("session-1")
                .setPlayableStateNamespaceId(NAMESPACE_ID)
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
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(NAMESPACE_ID)
                    .setPlayableStateScope("SHARED")
                    .setGameInstanceId("33")
                    .setCatalogRevision(1L)
                    .setPointerVersion(1L)
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

  @Test
  void directTextConnectScopeRejectsNoncanonicalIdsAndBlankContextRequestId() throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        IssueDirectTextConnectScopeRequest validRequest = validConnectScopeRequest();
        List<IssueDirectTextConnectScopeRequest> invalidRequests =
            List.of(
                validRequest.toBuilder().setRealmId("22").build(),
                validRequest.toBuilder()
                    .setRealmId("22")
                    .setPlayerContext(validRequest.getPlayerContext().toBuilder().setRealmId("22"))
                    .build(),
                validRequest.toBuilder().setPlayableStateNamespaceId("namespace-1").build(),
                validRequest.toBuilder()
                    .setPlayableStateNamespaceId("namespace-1")
                    .setPlayerContext(
                        validRequest.getPlayerContext().toBuilder()
                            .setPlayableStateNamespaceId("namespace-1"))
                    .build(),
                validRequest.toBuilder()
                    .setPlayerContext(
                        validRequest.getPlayerContext().toBuilder().setRequestId("   "))
                    .build(),
                validRequest.toBuilder().setCatalogRevision(0L).build(),
                validRequest.toBuilder().setPointerVersion(-1L).build());

        for (IssueDirectTextConnectScopeRequest request : invalidRequests) {
          var response = stub.issueDirectTextConnectScope(request);
          assertThat(response.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
          assertThat(response.getConnectScopeId()).isEmpty();
        }
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void joinRequiresCallerSessionAndResetClearsRetainedScopes() throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        IssueDirectTextConnectScopeRequest scopeRequest = validConnectScopeRequest();
        String connectScopeId = stub.issueDirectTextConnectScope(scopeRequest).getConnectScopeId();
        JoinPublicProductionMembershipRequest validJoin =
            JoinPublicProductionMembershipRequest.newBuilder()
                .setPlayerContext(scopeRequest.getPlayerContext())
                .setConnectScopeId(connectScopeId)
                .setRequestId("join-request-1")
                .build();

        var blankSession =
            stub.joinPublicProductionMembership(
                validJoin.toBuilder()
                    .setPlayerContext(validJoin.getPlayerContext().toBuilder().setSessionId("  "))
                    .build());
        assertThat(blankSession.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(blankSession.getSuccess()).isFalse();

        assertThat(stub.joinPublicProductionMembership(validJoin).getSuccess()).isTrue();
        server.resetRuntimeState();

        var afterReset = stub.joinPublicProductionMembership(validJoin);
        assertThat(afterReset.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(afterReset.getSuccess()).isFalse();
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private static IssueDirectTextConnectScopeRequest validConnectScopeRequest() {
    PlayerExecutionContext caller =
        PlayerExecutionContext.newBuilder()
            .setAccountId("7")
            .setTenantId("11")
            .setRealmId(REALM_ID)
            .setGameInstanceId("33")
            .setSessionId("session-1")
            .setPlayableStateNamespaceId(NAMESPACE_ID)
            .setPlayableStateScope("SHARED")
            .setRequestId("join-request-1")
            .build();
    return IssueDirectTextConnectScopeRequest.newBuilder()
        .setPlayerContext(caller)
        .setTenantId("11")
        .setWorldSlug("demo")
        .setRealmSlug("live")
        .setRealmId(REALM_ID)
        .setPlayableStateNamespaceId(NAMESPACE_ID)
        .setPlayableStateScope("SHARED")
        .setGameInstanceId("33")
        .setCatalogRevision(1L)
        .setPointerVersion(1L)
        .build();
  }

  private static void assertField(
      Descriptor descriptor, String name, int number, FieldDescriptor.Type type) {
    FieldDescriptor field = descriptor.findFieldByName(name);
    assertThat(field).isNotNull();
    assertThat(field.getNumber()).isEqualTo(number);
    assertThat(field.getType()).isEqualTo(type);
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
