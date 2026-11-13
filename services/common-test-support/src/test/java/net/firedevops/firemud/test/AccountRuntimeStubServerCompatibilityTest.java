package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeRequest;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipRequest;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountRuntimeStubServerCompatibilityTest {
  private static final String TENANT_ID = "5c03483e-d14c-4dc4-806e-297cdd209612";
  private static final String GAME_INSTANCE_ID = "fceab4e7-2922-4e6f-a4f4-dbaab3e25875";
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
  // Private runtime mutations require their actual bounded mTLS owner, never fake authority.
  private static final Set<String> UNIMPLEMENTED_RUNTIME_METHODS =
      Set.of("CommitTenantCapacityAdmission");

  @Test
  void accountServiceMethodSetIsFullyCategorized() {
    Set<String> actualMethods =
        AccountServiceGrpc.getServiceDescriptor().getMethods().stream()
            .map(method -> method.getBareMethodName())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(actualMethods)
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.Stream.of(
                    AccountRuntimeStubServer.implementedRuntimeMethodNames().stream(),
                    NON_RUNTIME_METHODS.stream(),
                    UNIMPLEMENTED_RUNTIME_METHODS.stream())
                .flatMap(java.util.function.Function.identity())
                .collect(java.util.stream.Collectors.toSet()));

    assertThat(AccountRuntimeStubServer.implementedRuntimeMethodNames())
        .contains("IssueDirectTextConnectScope", "JoinPublicProductionMembership");
  }

  @Test
  void privateCapacityMutationRemainsUnimplementedOnSharedFakeRuntimeServer() throws Exception {
    assertThat(AccountRuntimeStubServer.implementedRuntimeMethodNames())
        .doesNotContain("CommitTenantCapacityAdmission");
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        var stub = AccountServiceGrpc.newBlockingStub(channel);
        assertThatThrownBy(
                () ->
                    stub.commitTenantCapacityAdmission(
                        CommitTenantCapacityAdmissionRequest.getDefaultInstance()))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure ->
                    assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED));
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void joinMembershipVersionsRemainUnsignedOnTheirExistingFieldNumbers() {
    Descriptor descriptor =
        net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse.getDescriptor();

    assertField(descriptor, "membership_version", 6, FieldDescriptor.Type.UINT64);
    assertField(descriptor, "membership_authority_generation", 7, FieldDescriptor.Type.UINT64);
    assertField(descriptor, "gameplay_admission_allowed", 10, FieldDescriptor.Type.BOOL);
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
                .setTenantId(TENANT_ID)
                .setRealmId(REALM_ID)
                .setGameInstanceId(GAME_INSTANCE_ID)
                .setSessionId("1")
                .setPlayableStateNamespaceId(NAMESPACE_ID)
                .setPlayableStateScope("SHARED")
                .setRequestId("join-request-1")
                .build();
        var connectScope =
            stub.issueDirectTextConnectScope(
                IssueDirectTextConnectScopeRequest.newBuilder()
                    .setPlayerContext(caller)
                    .setTenantId(TENANT_ID)
                    .setWorldSlug("demo")
                    .setRealmSlug("live")
                    .setRealmId(REALM_ID)
                    .setPlayableStateNamespaceId(NAMESPACE_ID)
                    .setPlayableStateScope("SHARED")
                    .setGameInstanceId(GAME_INSTANCE_ID)
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
                validRequest.toBuilder()
                    .setPlayableStateScope("PRIVATE")
                    .setPlayerContext(
                        validRequest.getPlayerContext().toBuilder()
                            .setPlayableStateScope("PRIVATE"))
                    .build(),
                validRequest.toBuilder().setWorldSlug("w".repeat(129)).build(),
                validRequest.toBuilder().setRealmSlug("r".repeat(129)).build(),
                validRequest.toBuilder()
                    .setPlayerContext(
                        validRequest.getPlayerContext().toBuilder().setRequestId("r".repeat(129)))
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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "11", "00000000-0000-0000-0000-000000000000",
        "5C03483E-D14C-4DC4-806E-297CDD209612", "1-1-1-1-1"
      })
  void directTextScopeAndJoinRejectNoncanonicalTargetIds(String invalidId) throws Exception {
    try (var server = new AccountRuntimeStubServer(0)) {
      server.mapAccountId("demo@example.com", 7L);
      String accountUuid = AccountRuntimeStubServer.accountUuidForTestFixture(7L);
      var channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        var stub = AccountServiceGrpc.newBlockingStub(channel);
        var request = validConnectScopeRequest(accountUuid);
        var caller = request.getPlayerContext();
        var invalidRequests =
            List.of(
                request.toBuilder()
                    .setTenantId(invalidId)
                    .setPlayerContext(caller.toBuilder().setTenantId(invalidId))
                    .build(),
                request.toBuilder()
                    .setRealmId(invalidId)
                    .setPlayerContext(caller.toBuilder().setRealmId(invalidId))
                    .build(),
                request.toBuilder()
                    .setGameInstanceId(invalidId)
                    .setPlayerContext(caller.toBuilder().setGameInstanceId(invalidId))
                    .build(),
                request.toBuilder()
                    .setPlayableStateNamespaceId(invalidId)
                    .setPlayerContext(caller.toBuilder().setPlayableStateNamespaceId(invalidId))
                    .build());
        var join =
            JoinPublicProductionMembershipRequest.newBuilder()
                .setPlayerContext(caller)
                .setConnectScopeId(stub.issueDirectTextConnectScope(request).getConnectScopeId())
                .setRequestId(caller.getRequestId())
                .build();
        assertThat(stub.joinPublicProductionMembership(join).getSuccess()).isTrue();
        for (var invalidRequest : invalidRequests) {
          var scope = stub.issueDirectTextConnectScope(invalidRequest);
          assertThat(scope.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
          assertThat(scope.getConnectScopeId()).isEmpty();
          var replay =
              stub.joinPublicProductionMembership(
                  join.toBuilder().setPlayerContext(invalidRequest.getPlayerContext()).build());
          assertThat(replay.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
          assertThat(replay.getSuccess()).isFalse();
          assertThat(replay.getReplayed()).isFalse();
        }
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "  ",
        "session-1",
        "0",
        "-1",
        "+1",
        "1.0",
        "9223372036854775808",
        "00000000000000000000000000000000100"
      })
  void directTextScopeAndJoinRejectInvalidTransportSessionsBeforeReplay(String session)
      throws Exception {
    try (var server = new AccountRuntimeStubServer(0)) {
      server.mapAccountId("demo@example.com", 7L);
      var channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        var stub = AccountServiceGrpc.newBlockingStub(channel);
        var request =
            validConnectScopeRequest(AccountRuntimeStubServer.accountUuidForTestFixture(7L));
        var invalidCaller = request.getPlayerContext().toBuilder().setSessionId(session).build();
        var invalidScope =
            stub.issueDirectTextConnectScope(
                request.toBuilder().setPlayerContext(invalidCaller).build());
        assertThat(invalidScope.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(invalidScope.getConnectScopeId()).isEmpty();
        var join =
            JoinPublicProductionMembershipRequest.newBuilder()
                .setPlayerContext(request.getPlayerContext())
                .setConnectScopeId(stub.issueDirectTextConnectScope(request).getConnectScopeId())
                .setRequestId(request.getPlayerContext().getRequestId())
                .build();
        var invalidJoin = join.toBuilder().setPlayerContext(invalidCaller).build();
        assertThat(stub.joinPublicProductionMembership(invalidJoin).getError().getCode())
            .isEqualTo("INVALID_ARGUMENT");
        assertThat(stub.joinPublicProductionMembership(join).getSuccess()).isTrue();
        var replay = stub.joinPublicProductionMembership(invalidJoin);
        assertThat(replay.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(replay.getSuccess()).isFalse();
        assertThat(replay.getReplayed()).isFalse();
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "0001", "9223372036854775807", "00000000000000000000000000000001"})
  void directTextScopeAndJoinAcceptAccountTransportSessionRange(String session) throws Exception {
    try (var server = new AccountRuntimeStubServer(0)) {
      server.mapAccountId("demo@example.com", 7L);
      var channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        var stub = AccountServiceGrpc.newBlockingStub(channel);
        var request =
            validConnectScopeRequest(AccountRuntimeStubServer.accountUuidForTestFixture(7L));
        request =
            request.toBuilder()
                .setPlayerContext(request.getPlayerContext().toBuilder().setSessionId(session))
                .build();
        var scope = stub.issueDirectTextConnectScope(request);
        assertThat(scope.getError().getCode()).isEmpty();
        var join =
            stub.joinPublicProductionMembership(
                JoinPublicProductionMembershipRequest.newBuilder()
                    .setPlayerContext(request.getPlayerContext())
                    .setConnectScopeId(scope.getConnectScopeId())
                    .setRequestId(request.getPlayerContext().getRequestId())
                    .build());
        assertThat(join.getSuccess()).isTrue();
        assertThat(join.getTenantId()).isEqualTo(TENANT_ID);
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void joinRequiresCallerSessionBeforeReplayAndResetClearsRetainedScopesAndOutcomes()
      throws Exception {
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

        var blankSessionRequest =
            validJoin.toBuilder()
                .setPlayerContext(validJoin.getPlayerContext().toBuilder().setSessionId("  "))
                .build();
        var blankSession = stub.joinPublicProductionMembership(blankSessionRequest);
        assertThat(blankSession.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(blankSession.getSuccess()).isFalse();

        assertThat(stub.joinPublicProductionMembership(validJoin).getSuccess()).isTrue();
        for (var invalidRequest :
            List.of(
                blankSessionRequest,
                validJoin.toBuilder().setConnectScopeId("missing-scope").build(),
                validJoin.toBuilder()
                    .setPlayerContext(
                        validJoin.getPlayerContext().toBuilder()
                            .setRealmId("a87e157e-d63b-4bac-ad7c-2bd51c7ad81c"))
                    .build())) {
          var invalidReplay = stub.joinPublicProductionMembership(invalidRequest);
          assertThat(invalidReplay.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
          assertThat(invalidReplay.getSuccess()).isFalse();
          assertThat(invalidReplay.getReplayed()).isFalse();
        }
        server.resetRuntimeState();

        var afterReset = stub.joinPublicProductionMembership(validJoin);
        assertThat(afterReset.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(afterReset.getSuccess()).isFalse();

        String freshScopeId = stub.issueDirectTextConnectScope(scopeRequest).getConnectScopeId();
        assertThat(freshScopeId).isNotEqualTo(connectScopeId);
        var freshJoin =
            stub.joinPublicProductionMembership(
                validJoin.toBuilder().setConnectScopeId(freshScopeId).build());
        assertThat(freshJoin.getError().getCode()).isEmpty();
        assertThat(freshJoin.getSuccess()).isTrue();
        assertThat(freshJoin.getOutcomeCode()).isEqualTo("ALREADY_ACTIVE");
        assertThat(freshJoin.getReplayed()).isFalse();
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

  @ParameterizedTest
  @ValueSource(strings = {"MISSING", "INACTIVE"})
  void joinRetainsTerminalOutcomesAndNewRequestObservesActiveMembership(String lifecycle)
      throws Exception {
    var clock = Clock.fixed(Instant.parse("2026-10-30T12:34:56Z"), ZoneOffset.UTC);
    try (var server = new AccountRuntimeStubServer(0, clock)) {
      server.mapAccountId("demo@example.com", 7L);
      long previousVersion = "MISSING".equals(lifecycle) ? 1L : 3L;
      if ("MISSING".equals(lifecycle)) server.setMembershipExists(false);
      else server.setMembershipInactive();
      server.setMembershipVersion(previousVersion);
      server.setAllowPublicJoin(false);
      String accountUuid = AccountRuntimeStubServer.accountUuidForTestFixture(7L);
      var scopeRequest = validConnectScopeRequest(accountUuid);
      var channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        var stub = AccountServiceGrpc.newBlockingStub(channel);
        var scope = stub.issueDirectTextConnectScope(scopeRequest);
        var request =
            JoinPublicProductionMembershipRequest.newBuilder()
                .setPlayerContext(scopeRequest.getPlayerContext())
                .setConnectScopeId(scope.getConnectScopeId())
                .setRequestId("join-request-1")
                .build();
        var before = stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11"));
        var denied = stub.joinPublicProductionMembership(request);
        assertThat(denied.getSuccess()).isFalse();
        assertThat(denied.getOutcomeCode()).isEqualTo("PUBLIC_PRODUCTION_ADMISSION_DENIED");
        assertThat(denied.getReplayed()).isFalse();
        assertThat(stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11")))
            .isEqualTo(before);

        server.setAllowPublicJoin(true);
        var deniedReplay = stub.joinPublicProductionMembership(request);
        assertThat(deniedReplay).isEqualTo(denied.toBuilder().setReplayed(true).build());
        assertThat(stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11")))
            .isEqualTo(before);

        var nextRequest = withRequestId(request, "join-request-2");
        var joined = stub.joinPublicProductionMembership(nextRequest);
        assertThat(joined.getSuccess()).isTrue();
        assertThat(joined.getOutcomeCode()).isEqualTo("JOINED");
        assertThat(joined.getReplayed()).isFalse();
        assertThat(joined.getMembershipVersion()).isEqualTo(previousVersion + 1L);
        assertThat(joined.getGameplayAdmissionAllowed()).isTrue();
        var after = stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11"));
        assertThat(after.getMembershipVersion()).isEqualTo(joined.getMembershipVersion());
        assertThat(after.getMembershipLifecycleState()).isEqualTo("ACTIVE");

        server.setAllowPublicJoin(false);
        var repeated = stub.joinPublicProductionMembership(nextRequest);
        assertThat(repeated).isEqualTo(joined.toBuilder().setReplayed(true).build());
        assertThat(stub.joinPublicProductionMembership(request))
            .isEqualTo(denied.toBuilder().setReplayed(true).build());
        var alreadyActive =
            stub.joinPublicProductionMembership(withRequestId(request, "join-request-3"));
        assertThat(alreadyActive.getSuccess()).isTrue();
        assertThat(alreadyActive.getOutcomeCode()).isEqualTo("ALREADY_ACTIVE");
        assertThat(alreadyActive.getReplayed()).isFalse();
        assertThat(alreadyActive.getMembershipVersion()).isEqualTo(joined.getMembershipVersion());
        assertThat(stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11")))
            .isEqualTo(after);

        server.denyGameplayAdmission();
        var nonAdmittingReplay =
            joined.toBuilder().setReplayed(true).setGameplayAdmissionAllowed(false).build();
        assertThat(stub.joinPublicProductionMembership(nextRequest)).isEqualTo(nonAdmittingReplay);
        server.allowGameplayAdmission();
        server.setMembershipInactive();
        assertThat(stub.joinPublicProductionMembership(nextRequest)).isEqualTo(nonAdmittingReplay);
        assertThat(stub.joinPublicProductionMembership(request))
            .isEqualTo(denied.toBuilder().setReplayed(true).build());
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void retainedJoinRejectsConflictingCallerAndScopeIdentity() throws Exception {
    try (var server = new AccountRuntimeStubServer(0)) {
      server.mapAccountId("demo@example.com", 7L);
      server.mapAccountId("other@example.com", 9L);
      String accountUuid = AccountRuntimeStubServer.accountUuidForTestFixture(7L);
      var scopeRequest = validConnectScopeRequest(accountUuid);
      var channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        var stub = AccountServiceGrpc.newBlockingStub(channel);
        var request =
            JoinPublicProductionMembershipRequest.newBuilder()
                .setPlayerContext(scopeRequest.getPlayerContext())
                .setConnectScopeId(
                    stub.issueDirectTextConnectScope(scopeRequest).getConnectScopeId())
                .setRequestId("join-request-1")
                .build();
        var original = stub.joinPublicProductionMembership(request);
        assertThat(original.getSuccess()).isTrue();
        var otherAccountScope =
            validConnectScopeRequest(AccountRuntimeStubServer.accountUuidForTestFixture(9L));
        var otherTenantScope =
            scopeRequest.toBuilder()
                .setTenantId("024c8a59-d3ee-4f64-8276-c4c43a2efe7f")
                .setPlayerContext(
                    scopeRequest.getPlayerContext().toBuilder()
                        .setTenantId("024c8a59-d3ee-4f64-8276-c4c43a2efe7f"))
                .build();
        var sameScopeNewId = stub.issueDirectTextConnectScope(scopeRequest).getConnectScopeId();
        var conflicts =
            List.of(
                request.toBuilder()
                    .setPlayerContext(request.getPlayerContext().toBuilder().setSessionId("2"))
                    .build(),
                request.toBuilder().setConnectScopeId(sameScopeNewId).build(),
                request.toBuilder()
                    .setPlayerContext(otherAccountScope.getPlayerContext())
                    .setConnectScopeId(
                        stub.issueDirectTextConnectScope(otherAccountScope).getConnectScopeId())
                    .build(),
                request.toBuilder()
                    .setPlayerContext(otherTenantScope.getPlayerContext())
                    .setConnectScopeId(
                        stub.issueDirectTextConnectScope(otherTenantScope).getConnectScopeId())
                    .build());

        for (var conflict : conflicts) {
          var rejected = stub.joinPublicProductionMembership(conflict);
          assertThat(rejected.getError().getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
          assertThat(rejected.getSuccess()).isFalse();
          assertThat(rejected.getReplayed()).isFalse();
          assertThat(rejected.getAccountId()).isEmpty();
          assertThat(rejected.getTenantId()).isEmpty();
        }
        assertThat(stub.joinPublicProductionMembership(request))
            .isEqualTo(original.toBuilder().setReplayed(true).build());
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void alreadyActiveNonAdmittingJoinReturnsMembershipReceiptWithoutGrantingAdmission(
      boolean allowPublicJoin) throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      server.mapAccountId("demo@example.com", 7L);
      server.denyGameplayAdmission();
      server.setAllowPublicJoin(allowPublicJoin);
      String accountUuid = AccountRuntimeStubServer.accountUuidForTestFixture(7L);
      var scopeRequest = validConnectScopeRequest(accountUuid);
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        String connectScopeId = stub.issueDirectTextConnectScope(scopeRequest).getConnectScopeId();
        var before = stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11"));
        var reply =
            stub.joinPublicProductionMembership(
                JoinPublicProductionMembershipRequest.newBuilder()
                    .setPlayerContext(scopeRequest.getPlayerContext())
                    .setConnectScopeId(connectScopeId)
                    .setRequestId("join-request-1")
                    .build());

        assertThat(reply.getSuccess()).isTrue();
        assertThat(reply.getOutcomeCode()).isEqualTo("ALREADY_ACTIVE");
        assertThat(reply.getGameplayAdmissionAllowed()).isFalse();
        assertThat(reply.getMembershipVersion()).isEqualTo(before.getMembershipVersion());
        assertThat(reply.getMembershipAuthorityGeneration())
            .isEqualTo(before.getMembershipAuthorityGeneration());
        assertThat(reply.getMembershipId()).matches("[1-9][0-9]{0,18}");
        var after = stub.getTenantMembershipForRuntime(membershipRequest(accountUuid, "11"));
        assertThat(after.getMembershipExists()).isEqualTo(before.getMembershipExists());
        assertThat(after.getGameplayAdmissionAllowed())
            .isEqualTo(before.getGameplayAdmissionAllowed());
        assertThat(after.getMembershipVersion()).isEqualTo(before.getMembershipVersion());
        assertThat(after.getMembershipAuthorityGeneration())
            .isEqualTo(before.getMembershipAuthorityGeneration());
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private static IssueDirectTextConnectScopeRequest validConnectScopeRequest(String accountUuid) {
    PlayerExecutionContext caller =
        PlayerExecutionContext.newBuilder()
            .setAccountId(accountUuid)
            .setTenantId(TENANT_ID)
            .setRealmId(REALM_ID)
            .setGameInstanceId(GAME_INSTANCE_ID)
            .setSessionId("1")
            .setPlayableStateNamespaceId(NAMESPACE_ID)
            .setPlayableStateScope("SHARED")
            .setRequestId("join-request-1")
            .build();
    return IssueDirectTextConnectScopeRequest.newBuilder()
        .setPlayerContext(caller)
        .setTenantId(TENANT_ID)
        .setWorldSlug("demo")
        .setRealmSlug("live")
        .setRealmId(REALM_ID)
        .setPlayableStateNamespaceId(NAMESPACE_ID)
        .setPlayableStateScope("SHARED")
        .setGameInstanceId(GAME_INSTANCE_ID)
        .setCatalogRevision(1L)
        .setPointerVersion(1L)
        .build();
  }

  private static JoinPublicProductionMembershipRequest withRequestId(
      JoinPublicProductionMembershipRequest request, String requestId) {
    return request.toBuilder()
        .setRequestId(requestId)
        .setPlayerContext(request.getPlayerContext().toBuilder().setRequestId(requestId))
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
