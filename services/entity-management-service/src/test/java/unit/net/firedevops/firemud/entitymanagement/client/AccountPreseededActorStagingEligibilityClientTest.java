package unit.net.firedevops.firemud.entitymanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountActorStagingEligibilityServiceGrpc;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityCurrentness;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityDecision;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityPurpose;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityRequest;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityResponse;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Decision;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.client.AccountPreseededActorStagingEligibilityClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AccountPreseededActorStagingEligibilityClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REQUEST_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID TENANT_SOURCE_OPERATION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID MEMBERSHIP_EVENT_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final Instant OBSERVED_AT = Instant.parse("2026-09-01T12:34:56.123456789Z");
  private static final String SOURCE_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void forwardsExactClosedRequestAndReturnsTypedCurrentAccountEvidence() throws Exception {
    AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub
        stub = mockStub();
    when(stub.resolvePreseededActorStagingEligibility(any())).thenReturn(validResponse());
    AccountPreseededActorStagingEligibilityClient client = newClient(stub);

    AccountActorStagingEligibilityEvidence evidence =
        client.resolvePreseededActorStagingEligibility(
            ACCOUNT_ID.toString(), TENANT_ID.toString(), REQUEST_ID.toString());

    assertThat(evidence.schemaVersion()).isEqualTo(1);
    assertThat(evidence.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(evidence.requestId()).isEqualTo(REQUEST_ID);
    assertThat(evidence.canonicalAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(evidence.canonicalTenantId()).isEqualTo(TENANT_ID);
    assertThat(evidence.currentness())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Currentness.CURRENT_AT_REVALIDATION);
    assertThat(evidence.decision()).isEqualTo(Decision.STAGING_ELIGIBLE);
    assertThat(evidence.observedAt()).isEqualTo(OBSERVED_AT);
    assertThat(evidence.membershipVersion()).isEqualTo(17L);
    assertThat(evidence.membershipAuthorityGeneration()).isEqualTo(19L);
    assertThat(evidence.membershipEventSequence()).isEqualTo(23L);
    assertThat(evidence.gameplayAdmissionAllowed()).isTrue();

    ArgumentCaptor<ResolvePreseededActorStagingEligibilityRequest> requestCaptor =
        ArgumentCaptor.forClass(ResolvePreseededActorStagingEligibilityRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).resolvePreseededActorStagingEligibility(requestCaptor.capture());
    ResolvePreseededActorStagingEligibilityRequest request = requestCaptor.getValue();
    assertThat(request.getSchemaVersion()).isEqualTo(1);
    assertThat(request.getTargetNamespace()).isEqualTo(NAMESPACE);
    assertThat(request.getRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(request.getCanonicalAccountId()).isEqualTo(ACCOUNT_ID.toString());
    assertThat(request.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(request.getPurpose())
        .isEqualTo(ActorStagingEligibilityPurpose.PUBLIC_PRODUCTION_STAGING_ONLY);
    assertThat(request.getUnknownFields().asMap()).isEmpty();
  }

  @Test
  void returnsStagingIneligibleAsTypedObservation() throws Exception {
    AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub
        stub = mockStub();
    when(stub.resolvePreseededActorStagingEligibility(any()))
        .thenReturn(validResponse(false, "DEACTIVATED_PENDING_DELETE", "INACTIVE"));
    AccountPreseededActorStagingEligibilityClient client = newClient(stub);

    AccountActorStagingEligibilityEvidence evidence =
        client.resolvePreseededActorStagingEligibility(
            ACCOUNT_ID.toString(), TENANT_ID.toString(), REQUEST_ID.toString());

    assertThat(evidence.decision()).isEqualTo(Decision.STAGING_INELIGIBLE);
    assertThat(evidence.gameplayAdmissionAllowed()).isFalse();
  }

  @Test
  void rejectsMalformedInputsBeforeStubOrChannelUse() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AccountPreseededActorStagingEligibilityClient client = newClientWithoutStub(channelFactory);
    List<String[]> invalidRequests =
        List.of(
            new String[] {null, TENANT_ID.toString(), REQUEST_ID.toString()},
            new String[] {"", TENANT_ID.toString(), REQUEST_ID.toString()},
            new String[] {
              "AB426BB3-A733-43F0-9C8E-2E379CBDF7EC", TENANT_ID.toString(), REQUEST_ID.toString()
            },
            new String[] {
              ACCOUNT_ID.toString(), "22222222-2222-4222-8222-22222222222", REQUEST_ID.toString()
            },
            new String[] {
              ACCOUNT_ID.toString(), "00000000-0000-0000-0000-000000000000", REQUEST_ID.toString()
            },
            new String[] {ACCOUNT_ID.toString(), TENANT_ID.toString(), null},
            new String[] {ACCOUNT_ID.toString(), TENANT_ID.toString(), ""},
            new String[] {
              ACCOUNT_ID.toString(), TENANT_ID.toString(), "33333333-3333-4333-8333-33333333333"
            },
            new String[] {
              ACCOUNT_ID.toString(), TENANT_ID.toString(), "00000000-0000-0000-0000-000000000000"
            });

    for (String[] invalid : invalidRequests) {
      assertThatThrownBy(
              () ->
                  client.resolvePreseededActorStagingEligibility(
                      invalid[0], invalid[1], invalid[2]))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsUninitializedClientAndAbsentResponseWithoutActivation() throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AccountPreseededActorStagingEligibilityClient uninitialized =
        newClientWithoutStub(channelFactory);
    assertThatThrownBy(
            () ->
                uninitialized.resolvePreseededActorStagingEligibility(
                    ACCOUNT_ID.toString(), TENANT_ID.toString(), REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(channelFactory);

    AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub
        stub = mockStub();
    when(stub.resolvePreseededActorStagingEligibility(any())).thenReturn(null);
    AccountPreseededActorStagingEligibilityClient client = newClient(stub);
    assertThatThrownBy(
            () ->
                client.resolvePreseededActorStagingEligibility(
                    ACCOUNT_ID.toString(), TENANT_ID.toString(), REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("absent");
  }

  @Test
  void rejectsResponseRequestMismatchUnknownFieldsAndUnknownEnums() throws Exception {
    ResolvePreseededActorStagingEligibilityResponse valid = validResponse();
    assertRejected(valid.toBuilder().setSchemaVersion(2).build());
    assertRejected(valid.toBuilder().setTargetNamespace("other").build());
    assertRejected(
        valid.toBuilder().setCanonicalAccountId("66666666-6666-4666-8666-666666666666").build());
    assertRejected(
        valid.toBuilder().setCanonicalTenantId("66666666-6666-4666-8666-666666666666").build());
    assertRejected(valid.toBuilder().setRequestId("66666666-6666-4666-8666-666666666666").build());
    assertRejected(valid.toBuilder().setRequestId("33333333-3333-4333-8333-33333333333").build());
    assertRejected(
        valid.toBuilder().setCanonicalAccountId("00000000-0000-0000-0000-000000000000").build());
    assertRejected(valid.toBuilder().setCanonicalTenantId("not-a-uuid").build());
    assertRejected(valid.toBuilder().setPurposeValue(99).build());
    assertRejected(valid.toBuilder().setCurrentnessValue(99).build());
    assertRejected(valid.toBuilder().setDecisionValue(99).build());
    assertRejected(
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build());
  }

  @Test
  void rejectsChangedAuthorityFieldsAndChangedDigests() throws Exception {
    ResolvePreseededActorStagingEligibilityResponse valid = validResponse();
    assertRejected(valid.toBuilder().setMembershipVersion(18L).build());
    assertRejected(valid.toBuilder().setGameplayAdmissionAllowed(false).build());
    assertRejected(valid.toBuilder().setEligibilityDecisionDigest("0".repeat(64)).build());
    assertRejected(valid.toBuilder().setAuthoritySnapshotDigest("0".repeat(64)).build());
  }

  @Test
  void rejectsMissingMalformedAndOutOfRangeTimestamps() throws Exception {
    ResolvePreseededActorStagingEligibilityResponse valid = validResponse();
    assertRejected(valid.toBuilder().clearObservedAt().build());
    assertRejected(
        valid.toBuilder()
            .setObservedAt(Timestamp.newBuilder().setSeconds(0L).setNanos(1_000_000_000).build())
            .build());
    assertRejected(
        valid.toBuilder()
            .setObservedAt(Timestamp.newBuilder().setSeconds(253_402_300_800L).setNanos(0).build())
            .build());
    assertRejected(
        valid.toBuilder()
            .setObservedAt(Timestamp.newBuilder().setSeconds(-62_135_596_801L).setNanos(0).build())
            .build());
    assertRejected(
        valid.toBuilder()
            .setObservedAt(Timestamp.newBuilder().setSeconds(0L).setNanos(-1).build())
            .build());
    assertRejected(
        valid.toBuilder()
            .setObservedAt(
                valid.getObservedAt().toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build())
                    .build())
            .build());

    Instant minimum = Instant.ofEpochSecond(-62_135_596_800L);
    Instant maximum = Instant.ofEpochSecond(253_402_300_799L, 999_999_999);
    assertThat(resolve(validResponseAt(minimum)).observedAt()).isEqualTo(minimum);
    assertThat(resolve(validResponseAt(maximum)).observedAt()).isEqualTo(maximum);
  }

  @Test
  void rejectsNonpositiveCountersAndAcceptsLargePositiveCounters() throws Exception {
    ResolvePreseededActorStagingEligibilityResponse valid = validResponse();
    assertRejected(valid.toBuilder().setMembershipVersion(0L).build());
    assertRejected(valid.toBuilder().setMembershipVersion(-1L).build());
    assertRejected(valid.toBuilder().setMembershipAuthorityGeneration(0L).build());
    assertRejected(valid.toBuilder().setMembershipAuthorityGeneration(-1L).build());
    assertRejected(valid.toBuilder().setMembershipEventSequence(0L).build());
    assertRejected(valid.toBuilder().setMembershipEventSequence(-1L).build());

    AccountActorStagingEligibilityEvidence largeCounterEvidence =
        resolve(validResponse(true, "ACTIVE", "ACTIVE", Long.MAX_VALUE));
    assertThat(largeCounterEvidence.membershipVersion()).isEqualTo(Long.MAX_VALUE);
    assertThat(largeCounterEvidence.membershipAuthorityGeneration()).isEqualTo(Long.MAX_VALUE);
    assertThat(largeCounterEvidence.membershipEventSequence()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void propagatesProviderFailuresWithFiniteDeadline() throws Exception {
    AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub
        stub = mockStub();
    StatusRuntimeException timeout =
        new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Account timed out"));
    StatusRuntimeException unavailable =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Account unavailable"));
    when(stub.resolvePreseededActorStagingEligibility(any()))
        .thenThrow(timeout)
        .thenThrow(unavailable);
    AccountPreseededActorStagingEligibilityClient client = newClient(stub);

    assertThatThrownBy(
            () ->
                client.resolvePreseededActorStagingEligibility(
                    ACCOUNT_ID.toString(), TENANT_ID.toString(), REQUEST_ID.toString()))
        .isSameAs(timeout);
    assertThatThrownBy(
            () ->
                client.resolvePreseededActorStagingEligibility(
                    ACCOUNT_ID.toString(), TENANT_ID.toString(), REQUEST_ID.toString()))
        .isSameAs(unavailable);

    verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void rejectsPlaintextMissingClasspathTlsAndInvalidNamespaceBeforeChannelCreation() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new AccountPreseededActorStagingEligibilityClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new AccountPreseededActorStagingEligibilityClient(
                    new ServiceEndpointsProperties(), null, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration is required");

    for (CommonGrpcClientProperties incomplete :
        List.of(
            incompleteMtlsProperties(null, "entity.key", "account-ca.crt"),
            incompleteMtlsProperties("entity.crt", null, "account-ca.crt"),
            incompleteMtlsProperties("entity.crt", "entity.key", null))) {
      assertThatThrownBy(
              () ->
                  new AccountPreseededActorStagingEligibilityClient(
                      new ServiceEndpointsProperties(), incomplete, channelFactory, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("certificate, key, and CA");
    }

    for (CommonGrpcClientProperties classpath :
        List.of(
            incompleteMtlsProperties("classpath:entity.crt", "entity.key", "account-ca.crt"),
            incompleteMtlsProperties("entity.crt", "classpath:entity.key", "account-ca.crt"),
            incompleteMtlsProperties("entity.crt", "entity.key", "classpath:account-ca.crt"))) {
      assertThatThrownBy(
              () ->
                  new AccountPreseededActorStagingEligibilityClient(
                      new ServiceEndpointsProperties(), classpath, channelFactory, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("file-backed");
    }

    assertThatThrownBy(
            () ->
                new AccountPreseededActorStagingEligibilityClient(
                    new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, "bad.ns"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one DNS label");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void initUsesConfiguredAccountTargetAndFileBackedTls(@TempDir Path directory) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account.internal:6565");
    CommonGrpcClientProperties tls = mtlsProperties(directory);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("account.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    AccountPreseededActorStagingEligibilityClient client =
        new AccountPreseededActorStagingEligibilityClient(
            endpoints, tls, channelFactory, NAMESPACE);

    verifyNoInteractions(channelFactory);
    try {
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("account.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  private static void assertRejected(ResolvePreseededActorStagingEligibilityResponse response)
      throws Exception {
    AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub
        stub = mockStub();
    when(stub.resolvePreseededActorStagingEligibility(any())).thenReturn(response);
    AccountPreseededActorStagingEligibilityClient client = newClient(stub);

    assertThatThrownBy(
            () ->
                client.resolvePreseededActorStagingEligibility(
                    ACCOUNT_ID.toString(), TENANT_ID.toString(), REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static AccountActorStagingEligibilityEvidence resolve(
      ResolvePreseededActorStagingEligibilityResponse response) throws Exception {
    AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub
        stub = mockStub();
    when(stub.resolvePreseededActorStagingEligibility(any())).thenReturn(response);
    return newClient(stub)
        .resolvePreseededActorStagingEligibility(
            ACCOUNT_ID.toString(), TENANT_ID.toString(), REQUEST_ID.toString());
  }

  private static AccountPreseededActorStagingEligibilityClient newClient(
      AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub
          stub)
      throws Exception {
    AccountPreseededActorStagingEligibilityClient client =
        newClientWithoutStub(mock(GrpcChannelFactory.class));
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static AccountPreseededActorStagingEligibilityClient newClientWithoutStub(
      GrpcChannelFactory channelFactory) {
    return new AccountPreseededActorStagingEligibilityClient(
        new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, NAMESPACE);
  }

  private static AccountActorStagingEligibilityServiceGrpc
          .AccountActorStagingEligibilityServiceBlockingStub
      mockStub() {
    AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub
        stub =
            mock(
                AccountActorStagingEligibilityServiceGrpc
                    .AccountActorStagingEligibilityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static ResolvePreseededActorStagingEligibilityResponse validResponse() {
    return validResponse(true, "ACTIVE", "ACTIVE");
  }

  private static ResolvePreseededActorStagingEligibilityResponse validResponse(
      boolean gameplayAdmissionAllowed,
      String accountLifecycleState,
      String membershipLifecycleState) {
    return validResponse(
        gameplayAdmissionAllowed, accountLifecycleState, membershipLifecycleState, 17L, 19L, 23L);
  }

  private static ResolvePreseededActorStagingEligibilityResponse validResponse(
      boolean gameplayAdmissionAllowed,
      String accountLifecycleState,
      String membershipLifecycleState,
      long largeCounter) {
    return validResponse(
        gameplayAdmissionAllowed,
        accountLifecycleState,
        membershipLifecycleState,
        largeCounter,
        largeCounter,
        largeCounter);
  }

  private static ResolvePreseededActorStagingEligibilityResponse validResponse(
      boolean gameplayAdmissionAllowed,
      String accountLifecycleState,
      String membershipLifecycleState,
      long membershipVersion,
      long membershipAuthorityGeneration,
      long membershipEventSequence) {
    return validResponse(
        gameplayAdmissionAllowed,
        accountLifecycleState,
        membershipLifecycleState,
        membershipVersion,
        membershipAuthorityGeneration,
        membershipEventSequence,
        OBSERVED_AT);
  }

  private static ResolvePreseededActorStagingEligibilityResponse validResponseAt(
      Instant observedAt) {
    return validResponse(true, "ACTIVE", "ACTIVE", 17L, 19L, 23L, observedAt);
  }

  private static ResolvePreseededActorStagingEligibilityResponse validResponse(
      boolean gameplayAdmissionAllowed,
      String accountLifecycleState,
      String membershipLifecycleState,
      long membershipVersion,
      long membershipAuthorityGeneration,
      long membershipEventSequence,
      Instant observedAt) {
    AccountActorStagingEligibilityEvidence evidence =
        AccountActorStagingEligibilityEvidence.seal(
            NAMESPACE,
            REQUEST_ID,
            ACCOUNT_ID,
            TENANT_ID,
            AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
            observedAt,
            "ACCOUNT_REPOSITORY_INSERT",
            accountLifecycleState,
            membershipLifecycleState,
            gameplayAdmissionAllowed,
            "EXPLICIT_JOIN",
            membershipVersion,
            membershipAuthorityGeneration,
            "FRESH_GAME_DESIGN",
            TENANT_SOURCE_OPERATION_ID,
            SOURCE_DIGEST,
            membershipEventSequence,
            MEMBERSHIP_EVENT_ID,
            SOURCE_DIGEST,
            false);
    return ResolvePreseededActorStagingEligibilityResponse.newBuilder()
        .setSchemaVersion(evidence.schemaVersion())
        .setTargetNamespace(evidence.targetNamespace())
        .setRequestId(evidence.requestId().toString())
        .setCanonicalAccountId(evidence.canonicalAccountId().toString())
        .setCanonicalTenantId(evidence.canonicalTenantId().toString())
        .setPurpose(ActorStagingEligibilityPurpose.PUBLIC_PRODUCTION_STAGING_ONLY)
        .setCurrentness(ActorStagingEligibilityCurrentness.CURRENT_AT_REVALIDATION)
        .setObservedAt(
            Timestamp.newBuilder()
                .setSeconds(observedAt.getEpochSecond())
                .setNanos(observedAt.getNano())
                .build())
        .setDecision(
            evidence.decision() == Decision.STAGING_ELIGIBLE
                ? ActorStagingEligibilityDecision.STAGING_ELIGIBLE
                : ActorStagingEligibilityDecision.STAGING_INELIGIBLE)
        .setAccountUuidProvenance(evidence.accountUuidProvenance())
        .setAccountLifecycleState(evidence.accountLifecycleState())
        .setMembershipLifecycleState(evidence.membershipLifecycleState())
        .setGameplayAdmissionAllowed(evidence.gameplayAdmissionAllowed())
        .setMembershipAuthorityProvenance(evidence.membershipAuthorityProvenance())
        .setMembershipVersion(evidence.membershipVersion())
        .setMembershipAuthorityGeneration(evidence.membershipAuthorityGeneration())
        .setTenantProvenanceKind(evidence.tenantProvenanceKind())
        .setTenantSourceOperationId(evidence.tenantSourceOperationId().toString())
        .setTenantProvenanceDigest(evidence.tenantProvenanceDigest())
        .setMembershipEventSequence(evidence.membershipEventSequence())
        .setMembershipEventId(evidence.membershipEventId().toString())
        .setMembershipEventDigest(evidence.membershipEventDigest())
        .setLastTransitionInvalidated(evidence.lastTransitionInvalidated())
        .setEligibilityDecisionDigest(evidence.eligibilityDecisionDigest())
        .setAuthoritySnapshotDigest(evidence.authoritySnapshotDigest())
        .build();
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    return incompleteMtlsProperties(
        "entity-management-client.crt", "entity-management-client.key", "account-ca.crt");
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    return incompleteMtlsProperties(
        Files.createFile(directory.resolve("entity-management-client.crt")).toString(),
        Files.createFile(directory.resolve("entity-management-client.key")).toString(),
        Files.createFile(directory.resolve("account-ca.crt")).toString());
  }

  private static CommonGrpcClientProperties incompleteMtlsProperties(
      String certificate, String privateKey, String caCertificate) {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate);
    tls.setPrivateKey(privateKey);
    tls.setCaCert(caCertificate);
    return tls;
  }
}
