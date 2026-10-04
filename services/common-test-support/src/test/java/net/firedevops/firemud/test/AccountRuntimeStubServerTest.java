package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.RuntimeOutboxCheckpoint;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.common.account.AccountProfileJson;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccountRuntimeStubServerTest {
  private static final String ACCOUNT_UUID = "c91fb96e-5ad8-4e4e-a12d-2838640093b2";
  private static final String TENANT_UUID = "784e0d9c-714f-4a22-9404-04b8b37c8ef1";
  private static final String NIL_ACCOUNT_UUID = "00000000-0000-0000-0000-000000000000";
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  @Test
  void authenticationCanonicalizesEmailAndRuntimeAuthoritySnapshotsAreFreshAndComplete()
      throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        server.mapAccountUuid("demo@example.com", ACCOUNT_UUID);

        assertThat(
                stub.authenticate(
                        AuthenticateRequest.newBuilder()
                            .setEmail("  DEMO@EXAMPLE.COM ")
                            .setPassword("password")
                            .build())
                    .getAccountId())
            .isEqualTo(ACCOUNT_UUID);

        var request =
            GetTenantMembershipForRuntimeRequest.newBuilder()
                .setPlayerContext(
                    net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                        .setAccountId(ACCOUNT_UUID)
                        .setTenantId(TENANT_UUID)
                        .setRequestId("request-1"))
                .build();
        Instant activeBefore = Instant.now();
        GetTenantMembershipForRuntimeResponse active = stub.getTenantMembershipForRuntime(request);
        Instant activeAfter = Instant.now();
        assertThat(active.getMembershipLifecycleState()).isEqualTo("ACTIVE");
        assertThat(active.getAccountId()).isEqualTo(ACCOUNT_UUID);
        assertThat(active.getRequestAccountId()).isEqualTo(ACCOUNT_UUID);
        assertThat(active.getMembershipExists()).isTrue();
        assertThat(active.getGameplayAdmissionAllowed()).isTrue();
        assertThat(active.getMembershipVersionMap()).containsEntry(active.getTenantId(), "1");
        assertThat(active.getRequestId()).isEqualTo("request-1");
        assertThat(active.getMembershipAuthorityGeneration()).isEqualTo("1");
        assertMembershipEventMatches(active, "ACTIVE", true, "request-1");
        assertThat(Instant.parse(active.getEvaluatedAt())).isBetween(activeBefore, activeAfter);

        var secondRequest =
            request.toBuilder()
                .setPlayerContext(
                    request.getPlayerContext().toBuilder().setRequestId("request-2").build())
                .build();
        var secondActive = stub.getTenantMembershipForRuntime(secondRequest);
        assertThat(secondActive.getRequestId()).isEqualTo("request-2");
        assertMembershipEventMatches(secondActive, "ACTIVE", true, "request-2");
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
        assertMembershipEventMatches(deniedMembership, "ACTIVE", false, "request-denied");
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
        assertMembershipEventMatches(inactive, "INACTIVE", false, "request-1");
        assertThat(Instant.parse(inactive.getEvaluatedAt()))
            .isBetween(inactiveBefore, inactiveAfter);

        server.setMembershipExists(false);
        Instant missingBefore = Instant.now();
        var membership = stub.getTenantMembershipForRuntime(request);
        Instant missingAfter = Instant.now();
        assertThat(membership.getMembershipExists()).isFalse();
        assertThat(membership.getGameplayAdmissionAllowed()).isFalse();
        assertThat(membership.getMembershipLifecycleState()).isEqualTo("MISSING");
        assertMembershipSnapshotMatches(membership, "MISSING", false, false, "request-1", "0");
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
                    .setAccountId(ACCOUNT_UUID)
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

  @Test
  void rejectsNonCanonicalRuntimeTenantIdsWithoutReturningMembershipEvidence() throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        List<String> invalidTenantIds =
            List.of(
                "1",
                "00000000-0000-0000-0000-000000000000",
                TENANT_UUID.toUpperCase(Locale.ROOT),
                "not-a-uuid");

        for (int index = 0; index < invalidTenantIds.size(); index++) {
          String tenantId = invalidTenantIds.get(index);
          var response =
              stub.withDeadlineAfter(1, TimeUnit.SECONDS)
                  .getTenantMembershipForRuntime(membershipRequest(tenantId, "invalid-" + index));

          assertThat(response.hasError()).isTrue();
          assertThat(response.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
          assertThat(response.getError().getMessage()).isNotEmpty();
          assertThat(response.getAccountId()).isEmpty();
          assertThat(response.getTenantId()).isEmpty();
          assertThat(response.getRequestAccountId()).isEmpty();
          assertThat(response.getRequestTenantId()).isEmpty();
          assertThat(response.getRequestId()).isEmpty();
          assertThat(response.getAuthorityAvailability()).isEmpty();
          assertThat(response.getMembershipVersionMap()).isEmpty();
          assertThat(response.getOutboxCheckpointsCount()).isZero();
          assertThat(response.getOutboxSourceEvidenceCount()).isZero();
          assertThat(response.hasMembershipBaseline()).isFalse();
          assertThat(response.hasAuthorityTuple()).isFalse();
        }
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void activeMembershipCanBeDeniedWhileInactiveMembershipCannotBeAdmitted() throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);

        server.setGameplayAdmissionAllowed(false);
        var activeButDenied =
            stub.getTenantMembershipForRuntime(membershipRequest(TENANT_UUID, "contradictory-1"));
        assertMembershipEventMatches(activeButDenied, "ACTIVE", false, "contradictory-1");

        server.denyGameplayAdmission();
        server.setMembershipInactive();
        server.setGameplayAdmissionAllowed(true);
        var inactiveButAllowed =
            stub.getTenantMembershipForRuntime(membershipRequest(TENANT_UUID, "contradictory-2"));
        assertContradictoryMembershipDenied(inactiveButAllowed, "INACTIVE", "contradictory-2");
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private static GetTenantMembershipForRuntimeRequest membershipRequest(
      String tenantId, String requestId) {
    return GetTenantMembershipForRuntimeRequest.newBuilder()
        .setPlayerContext(
            net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                .setAccountId(ACCOUNT_UUID)
                .setTenantId(tenantId)
                .setRequestId(requestId))
        .build();
  }

  private static void assertContradictoryMembershipDenied(
      GetTenantMembershipForRuntimeResponse response, String lifecycle, String requestId) {
    assertThat(response.getAccountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(response.getTenantId()).isEqualTo(TENANT_UUID);
    assertThat(response.getRequestAccountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(response.getRequestTenantId()).isEqualTo(TENANT_UUID);
    assertThat(response.getRequestId()).isEqualTo(requestId);
    assertThat(response.getMembershipExists()).isTrue();
    assertThat(response.getMembershipLifecycleState()).isEqualTo(lifecycle);
    assertThat(response.getGameplayAdmissionAllowed()).isFalse();
    assertThat(response.getAuthorityAvailability()).isEmpty();
    assertThat(response.getMembershipVersionMap()).isEmpty();
    assertThat(response.getOutboxCheckpointsCount()).isZero();
    assertThat(response.getOutboxSourceEvidenceCount()).isZero();
    assertThat(response.hasMembershipBaseline()).isFalse();
    assertThat(response.hasAuthorityTuple()).isFalse();
  }

  private static void assertMembershipEventMatches(
      GetTenantMembershipForRuntimeResponse response,
      String lifecycle,
      boolean admitted,
      String requestId) {
    assertMembershipSnapshotMatches(response, lifecycle, true, admitted, requestId, "1");
    var source = response.getOutboxSourceEvidence(0);
    var event = MembershipAuthorityEventV1Codec.verify(source.getCanonicalEventJson());
    String membershipStream =
        "account:auth-authority:v1:membership/" + ACCOUNT_UUID + "/" + TENANT_UUID;
    var expectedEvent =
        MembershipAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", "00000000-0000-0000-0000-000000000099"),
                Map.entry("requestId", "runtime-membership-request"),
                Map.entry("outboxStreamKey", membershipStream),
                Map.entry("outboxSequence", "1"),
                Map.entry("sourceScope", "membership/" + ACCOUNT_UUID + "/" + TENANT_UUID),
                Map.entry("accountId", ACCOUNT_UUID),
                Map.entry("tenantId", TENANT_UUID),
                Map.entry("membershipExists", true),
                Map.entry("membershipLifecycleState", lifecycle),
                Map.entry("membershipVersion", Map.of(TENANT_UUID, "1")),
                Map.entry("membershipAuthorityGeneration", "1"),
                Map.entry(
                    "authorityTuple",
                    Map.of(
                        "issuerAuthGeneration", "1",
                        "accountAuthorityGeneration", "1",
                        "tenantAuthorityGeneration", Map.of(TENANT_UUID, "1"),
                        "membershipAuthorityGeneration", Map.of(TENANT_UUID, "1"),
                        "privateRealmGrantVersions", List.of())),
                Map.entry("issuanceFence", "1"),
                Map.entry("roles", lifecycle.equals("ACTIVE") ? List.of("player") : List.of()),
                Map.entry("gameplayAdmissionAllowed", admitted),
                Map.entry("callerBoundAuthorityInvalidated", false)));

    assertThat(event.canonicalJson()).isEqualTo(source.getCanonicalEventJson());
    assertThat(event.canonicalJson()).isEqualTo(expectedEvent.canonicalJson());
    assertThat(event.eventDigest()).isEqualTo(source.getEventDigest());
    assertThat(event.eventDigest()).isEqualTo(expectedEvent.eventDigest());
    assertThat(event.schemaVersion()).isEqualTo(MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    assertThat(event.eventType()).isEqualTo(MembershipAuthorityEventV1Codec.EVENT_TYPE);
    assertThat(event.eventId()).isEqualTo(source.getEventId());
    assertThat(event.requestId()).isEqualTo("runtime-membership-request");
    assertThat(event.accountId()).isEqualTo(response.getAccountId());
    assertThat(event.tenantId()).isEqualTo(response.getTenantId());
    assertThat(event.sourceScope()).isEqualTo("membership/" + ACCOUNT_UUID + "/" + TENANT_UUID);
    assertThat(event.outboxStreamKey()).isEqualTo(membershipStream);
    assertThat(event.outboxStreamKey()).isEqualTo(source.getOutboxStreamKey());
    assertThat(event.outboxSequence()).isEqualTo("1");
    assertThat(event.outboxSequence()).isEqualTo(source.getOutboxSequence());
    assertThat(event.membershipLifecycleState()).isEqualTo(lifecycle);
    assertThat(event.membershipVersion()).containsExactlyEntriesOf(Map.of(TENANT_UUID, "1"));
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

  private static void assertMembershipSnapshotMatches(
      GetTenantMembershipForRuntimeResponse response,
      String lifecycle,
      boolean exists,
      boolean admitted,
      String requestId,
      String membershipSequence) {
    String membershipStream =
        "account:auth-authority:v1:membership/" + ACCOUNT_UUID + "/" + TENANT_UUID;
    assertThat(response.getAccountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(response.getTenantId()).isEqualTo(TENANT_UUID);
    assertThat(response.getRequestAccountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(response.getRequestTenantId()).isEqualTo(TENANT_UUID);
    assertThat(response.getRequestId()).isEqualTo(requestId);
    assertThat(response.getAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(response.getMembershipExists()).isEqualTo(exists);
    assertThat(response.getMembershipLifecycleState()).isEqualTo(lifecycle);
    assertThat(response.getGameplayAdmissionAllowed()).isEqualTo(admitted);
    assertThat(response.getMembershipVersionMap())
        .containsExactlyEntriesOf(Map.of(TENANT_UUID, "1"));
    assertThat(response.getMembershipAuthorityGeneration()).isEqualTo("1");
    assertThat(response.hasMembershipBaseline()).isTrue();
    assertThat(response.getMembershipBaseline().getMembershipLifecycleState()).isEqualTo(lifecycle);
    assertThat(response.getMembershipBaseline().getMembershipVersionMap())
        .containsExactlyEntriesOf(Map.of(TENANT_UUID, "1"));
    assertThat(response.getMembershipBaseline().getMembershipAuthorityGeneration()).isEqualTo("1");
    assertThat(response.hasAuthorityTuple()).isTrue();
    assertThat(response.getAuthorityTuple().getIssuerAuthGeneration()).isEqualTo("1");
    assertThat(response.getAuthorityTuple().getAccountAuthorityGeneration()).isEqualTo("1");
    assertThat(response.getAuthorityTuple().getTenantAuthorityGenerationMap())
        .containsExactlyEntriesOf(Map.of(TENANT_UUID, "1"));
    assertThat(response.getAuthorityTuple().getMembershipAuthorityGenerationMap())
        .containsExactlyEntriesOf(Map.of(TENANT_UUID, "1"));
    assertThat(response.getAuthorityTuple().getPrivateRealmGrantVersionsList()).isEmpty();
    assertThat(response.getAuthorityTuple().hasAccountSecurityCutoff()).isFalse();
    assertThat(response.getAuthorityTuple().hasTenantBillingCutoff()).isFalse();
    assertThat(response.getIssuanceFence()).isEqualTo("1");
    assertThat(response.getRolesList())
        .containsExactlyElementsOf(lifecycle.equals("ACTIVE") ? List.of("player") : List.of());
    assertThat(response.getOutboxCheckpointsList())
        .containsExactly(
            checkpoint("account:auth-authority:v1:account/" + ACCOUNT_UUID, "0"),
            checkpoint("account:auth-authority:v1:issuer/firemud-account-service", "0"),
            checkpoint(membershipStream, membershipSequence),
            checkpoint("account:auth-authority:v1:tenant/" + TENANT_UUID, "0"));
    assertThat(response.getOutboxSourceEvidenceCount()).isEqualTo(exists ? 1 : 0);
    if (exists) {
      var source = response.getOutboxSourceEvidence(0);
      assertThat(source.getOutboxStreamKey()).isEqualTo(membershipStream);
      assertThat(source.getOutboxSequence()).isEqualTo("1");
      assertThat(source.getCanonicalEventJson()).isNotEmpty();
      assertThat(source.getEventId()).isNotEmpty();
      assertThat(source.getEventDigest()).matches("sha256:[0-9a-f]{64}");
    }
  }

  private static RuntimeOutboxCheckpoint checkpoint(String streamKey, String sequence) {
    return RuntimeOutboxCheckpoint.newBuilder()
        .setOutboxStreamKey(streamKey)
        .setOutboxSequence(sequence)
        .build();
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

        GetProfileResponse initialProfileResponse =
            stub.getProfile(
                GetProfileRequest.newBuilder().setTenantId("1").setAccountId(ACCOUNT_UUID).build());
        assertProfileIdentity(initialProfileResponse, ACCOUNT_UUID, 1L);
        AccountProfileJson initialProfile =
            AccountProfileJson.parse(initialProfileResponse.getProfileJson(), "FRIENDS_ONLY");
        assertThat(initialProfile.presenceVisibilityPolicy()).isEqualTo("FRIENDS_ONLY");

        UpdateProfileResponse updateResponse =
            stub.updateProfile(
                UpdateProfileRequest.newBuilder()
                    .setTenantId("1")
                    .setAccountId(ACCOUNT_UUID)
                    .setProfileJson(
                        "{\"displayName\":\"Demo-%s\",\"bio\":null,\"presenceVisibilityPolicy\":\"PRIVATE\"}"
                            .formatted(ACCOUNT_UUID))
                    .build());
        assertThat(updateResponse.getSuccess()).isTrue();
        assertThat(updateResponse.getAccountId()).isEqualTo(ACCOUNT_UUID);
        assertThat(updateResponse.getTenantId()).isEqualTo("1");

        UpdateProfileResponse wrongTenantUpdate =
            stub.updateProfile(
                UpdateProfileRequest.newBuilder()
                    .setTenantId("2")
                    .setAccountId(ACCOUNT_UUID)
                    .setProfileJson(
                        "{\"displayName\":\"Wrong tenant\",\"bio\":null,\"presenceVisibilityPolicy\":\"PUBLIC\"}")
                    .build());
        assertThat(wrongTenantUpdate.getSuccess()).isFalse();
        assertThat(wrongTenantUpdate.getAccountId()).isEmpty();
        assertThat(wrongTenantUpdate.getTenantId()).isEmpty();

        GetProfileResponse wrongTenantRead =
            stub.getProfile(
                GetProfileRequest.newBuilder().setTenantId("2").setAccountId(ACCOUNT_UUID).build());
        assertThat(wrongTenantRead.getProfileJson()).isEmpty();

        GetProfileResponse updatedProfileResponse =
            stub.getProfile(
                GetProfileRequest.newBuilder().setTenantId("1").setAccountId(ACCOUNT_UUID).build());
        assertProfileIdentity(updatedProfileResponse, ACCOUNT_UUID, 1L);
        AccountProfileJson updatedProfile =
            AccountProfileJson.parse(updatedProfileResponse.getProfileJson(), "FRIENDS_ONLY");
        assertThat(updatedProfile.presenceVisibilityPolicy()).isEqualTo("PRIVATE");
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void rejectsInvalidAccountSelectorsForRegistrationAndProfileMutation() throws Exception {
    try (AccountRuntimeStubServer server = new AccountRuntimeStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        AccountServiceGrpc.AccountServiceBlockingStub stub =
            AccountServiceGrpc.newBlockingStub(channel);
        server.setDefaultAccountUuid(ACCOUNT_UUID);
        assertThat(
                stub.updateProfile(profileUpdate(ACCOUNT_UUID, "Protected profile")).getSuccess())
            .isTrue();

        String[] invalidAccountUuids = {
          "7", ACCOUNT_UUID.toUpperCase(Locale.ROOT), NIL_ACCOUNT_UUID
        };
        for (String invalidAccountUuid : invalidAccountUuids) {
          assertThatThrownBy(
                  () -> server.mapAccountUuid("candidate@example.com", invalidAccountUuid))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(() -> server.setDefaultAccountUuid(invalidAccountUuid))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () -> server.setPresenceVisibilityPolicy(invalidAccountUuid, "PRIVATE"))
              .isInstanceOf(IllegalArgumentException.class);
          UpdateProfileResponse invalidAccountUpdate =
              stub.updateProfile(profileUpdate(invalidAccountUuid, "Overwritten profile"));
          assertThat(invalidAccountUpdate.getSuccess()).isFalse();
          assertThat(invalidAccountUpdate.getAccountId()).isEmpty();
          assertThat(invalidAccountUpdate.getTenantId()).isEmpty();
        }

        assertThatThrownBy(() -> server.mapAccountUuid("candidate@example.com", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> server.setDefaultAccountUuid(null))
            .isInstanceOf(IllegalArgumentException.class);
        AuthenticateRequest candidateRequest =
            AuthenticateRequest.newBuilder()
                .setEmail("candidate@example.com")
                .setPassword("password")
                .build();
        assertThat(stub.authenticate(candidateRequest).getAccountId()).isEqualTo(ACCOUNT_UUID);

        AccountProfileJson protectedProfile =
            AccountProfileJson.parse(
                stub.getProfile(
                        GetProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId(ACCOUNT_UUID)
                            .build())
                    .getProfileJson(),
                "FRIENDS_ONLY");
        assertThat(protectedProfile.displayName()).isEqualTo("Protected profile");
        assertThat(protectedProfile.presenceVisibilityPolicy()).isEqualTo("PRIVATE");
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
                            .setAccountId(ACCOUNT_UUID)
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
                        GetProfileRequest.newBuilder()
                            .setTenantId("1")
                            .setAccountId(ACCOUNT_UUID)
                            .build())
                    .getProfileJson(),
                "FRIENDS_ONLY");
        assertThat(resetProfile.presenceVisibilityPolicy()).isEqualTo("FRIENDS_ONLY");
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private static UpdateProfileRequest profileUpdate(String accountUuid, String displayName) {
    return UpdateProfileRequest.newBuilder()
        .setTenantId("1")
        .setAccountId(accountUuid)
        .setProfileJson(
            "{\"displayName\":\"%s\",\"bio\":null,\"presenceVisibilityPolicy\":\"PRIVATE\"}"
                .formatted(displayName))
        .build();
  }

  private static void assertProfileIdentity(
      GetProfileResponse response, String accountUuid, long tenantId) throws Exception {
    JsonNode profile = JSON_MAPPER.readTree(response.getProfileJson());
    assertThat(profile.path("accountId").isTextual()).isTrue();
    assertThat(profile.path("accountId").asString()).isEqualTo(accountUuid);
    assertThat(profile.path("tenantId").isIntegralNumber()).isTrue();
    assertThat(profile.path("tenantId").asLong()).isEqualTo(tenantId);
  }
}
