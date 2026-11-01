package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
          "RetireInitialGameplayLogin",
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
      server.mapAccountId("demo@example.com", 7L);
      String accountUuid = AccountRuntimeStubServer.accountUuidForTestFixture(7L);
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);

        server.denyGameplayAdmission();
        var existingButNonAdmitting =
            stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11"));
        assertThat(existingButNonAdmitting.getMembershipExists()).isTrue();
        assertThat(existingButNonAdmitting.getGameplayAdmissionAllowed()).isFalse();
        assertThat(existingButNonAdmitting.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(existingButNonAdmitting.getMembershipAuthorityGeneration()).isEqualTo(1L);

        server.setMembershipExists(false);
        var missing = stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11"));
        assertThat(missing.getMembershipExists()).isFalse();
        assertThat(missing.getGameplayAdmissionAllowed()).isFalse();
        assertThat(missing.getMembershipLifecycleState()).isEqualTo("MISSING");
        assertThat(missing.getMembershipAuthorityGeneration()).isZero();

        server.setRealmAccessGranted(false);
        PlayerExecutionContext caller =
            PlayerExecutionContext.newBuilder()
                .setAccountId(accountUuid)
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

        var afterJoin = stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11"));
        assertThat(afterJoin.getMembershipExists()).isTrue();
        assertThat(afterJoin.getGameplayAdmissionAllowed()).isTrue();
        assertThat(afterJoin.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(afterJoin.getMembershipAuthorityGeneration()).isEqualTo(1L);

        var realmGrant =
            stub.getRealmAccessGrantForRuntime(
                GetRealmAccessGrantForRuntimeRequest.newBuilder()
                    .setAccountId(accountUuid)
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
        server.mapAccountId("demo@example.com", 7L);
        String accountUuid = AccountRuntimeStubServer.accountUuidForTestFixture(7L);
        IssueDirectTextConnectScopeRequest validRequest = validConnectScopeRequest(accountUuid);
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
      server.mapAccountId("demo@example.com", 7L);
      String accountUuid = AccountRuntimeStubServer.accountUuidForTestFixture(7L);
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        IssueDirectTextConnectScopeRequest scopeRequest = validConnectScopeRequest(accountUuid);
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

  @Test
  void directTextScopeIssuanceAndJoinRequireRegisteredAccountUuidAndKeepClockSnapshot()
      throws Exception {
    Instant evaluatedAt = Instant.parse("2026-10-30T12:34:56Z");
    try (AccountRuntimeStubServer server =
        new AccountRuntimeStubServer(0, Clock.fixed(evaluatedAt, ZoneOffset.UTC))) {
      server.mapAccountId("demo@example.com", 7L);
      server.setMembershipExists(false);
      String accountUuid = AccountRuntimeStubServer.accountUuidForTestFixture(7L);
      IssueDirectTextConnectScopeRequest scopeRequest = validConnectScopeRequest(accountUuid);
      List<String> unregisteredCallers =
          List.of(
              "7",
              "00000000-0000-0000-0000-000000000000",
              "not-a-uuid",
              AccountRuntimeStubServer.accountUuidForTestFixture(9L));
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);

        for (String unregisteredCaller : unregisteredCallers) {
          var refusedScope =
              stub.issueDirectTextConnectScope(
                  scopeRequest.toBuilder()
                      .setPlayerContext(
                          scopeRequest.getPlayerContext().toBuilder()
                              .setAccountId(unregisteredCaller))
                      .build());
          assertThat(refusedScope.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
          assertThat(refusedScope.getConnectScopeId()).isEmpty();
          assertThat(refusedScope.getConnectScopeExpiresAt()).isEmpty();
          assertThat(refusedScope.getEvaluatedAt()).isEmpty();
        }

        var issuedScope = stub.issueDirectTextConnectScope(scopeRequest);
        assertThat(issuedScope.getError().getCode()).isEmpty();
        assertThat(issuedScope.getConnectScopeId()).isEqualTo("stub-connect-scope-1");
        assertThat(issuedScope.getEvaluatedAt()).isEqualTo(evaluatedAt.toString());
        assertThat(issuedScope.getConnectScopeExpiresAt())
            .isEqualTo(evaluatedAt.plusSeconds(300).toString());

        JoinPublicProductionMembershipRequest validJoin =
            JoinPublicProductionMembershipRequest.newBuilder()
                .setPlayerContext(scopeRequest.getPlayerContext())
                .setConnectScopeId(issuedScope.getConnectScopeId())
                .setRequestId("join-request-1")
                .build();
        for (String unregisteredCaller : unregisteredCallers) {
          var refusedJoin =
              stub.joinPublicProductionMembership(
                  validJoin.toBuilder()
                      .setPlayerContext(
                          validJoin.getPlayerContext().toBuilder().setAccountId(unregisteredCaller))
                      .build());
          assertThat(refusedJoin.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
          assertThat(refusedJoin.getSuccess()).isFalse();
          var membership = stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11"));
          assertThat(membership.getMembershipExists()).isFalse();
          assertThat(membership.getGameplayAdmissionAllowed()).isFalse();
        }

        assertThat(stub.joinPublicProductionMembership(validJoin).getSuccess()).isTrue();
        var membershipAfterJoin =
            stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11"));
        assertThat(membershipAfterJoin.getMembershipExists()).isTrue();
        assertThat(membershipAfterJoin.getGameplayAdmissionAllowed()).isTrue();
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private static IssueDirectTextConnectScopeRequest validConnectScopeRequest(String accountUuid) {
    PlayerExecutionContext caller =
        PlayerExecutionContext.newBuilder()
            .setAccountId(accountUuid)
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

  @Test
  void runtimeMembershipSnapshotsKeepLifecycleAndAuthorityCountersIndependent() throws Exception {
    Instant evaluatedAt = Instant.parse("2026-10-30T12:34:56Z");
    String accountId = AccountRuntimeStubServer.accountUuidForTestFixture(7L);
    try (AccountRuntimeStubServer server =
        new AccountRuntimeStubServer(0, Clock.fixed(evaluatedAt, ZoneOffset.UTC))) {
      server.mapAccountId("demo@example.com", 7L);
      server.setMembershipVersion(9L);
      server.setMembershipAuthorityGeneration(4L);
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        GetTenantMembershipForRuntimeRequest request =
            GetTenantMembershipForRuntimeRequest.newBuilder()
                .setAccountId(accountId)
                .setTenantId("11")
                .setRequestId("membership-request-1")
                .build();

        server.denyGameplayAdmission();
        var activeDenied = stub.getTenantMembershipForRuntime(request);
        assertThat(activeDenied.getMembershipExists()).isTrue();
        assertThat(activeDenied.getGameplayAdmissionAllowed()).isFalse();
        assertThat(activeDenied.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(activeDenied.getMembershipVersion()).isEqualTo(9L);
        assertThat(activeDenied.getMembershipAuthorityGeneration()).isEqualTo(4L);

        server.setMembershipInactive();
        var inactive = stub.getTenantMembershipForRuntime(request);
        assertThat(inactive.getMembershipExists()).isTrue();
        assertThat(inactive.getGameplayAdmissionAllowed()).isFalse();
        assertThat(inactive.getMembershipLifecycleState()).isEqualTo("INACTIVE");
        assertThat(inactive.getMembershipVersion()).isEqualTo(9L);
        assertThat(inactive.getMembershipAuthorityGeneration()).isEqualTo(4L);

        server.setMembershipExists(false);
        server.setGameplayAdmissionAllowed(true);
        var missing = stub.getTenantMembershipForRuntime(request);
        assertThat(missing.getMembershipExists()).isFalse();
        assertThat(missing.getGameplayAdmissionAllowed()).isFalse();
        assertThat(missing.getMembershipLifecycleState()).isEqualTo("MISSING");
        assertThat(missing.getMembershipVersion()).isZero();
        assertThat(missing.getMembershipAuthorityGeneration()).isZero();

        for (var response : List.of(activeDenied, inactive, missing)) {
          assertThat(response.getAccountId()).isEqualTo(accountId);
          assertThat(response.getTenantId()).isEqualTo("11");
          assertThat(response.getEvaluatedAt()).isEqualTo(evaluatedAt.toString());
        }
      } finally {
        channel.shutdownNow();
      }
    }
  }
}
