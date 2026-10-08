package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityPurpose;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityRequest;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityResponse;
import net.firedevops.firemud.accountservice.service.AccountActorStagingEligibilityService;
import net.firedevops.firemud.accountservice.service.impl.AccountActorStagingEligibilityGrpcService;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Purpose;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.StreamUtils;

class AccountActorStagingEligibilityGrpcServiceTest {
  private static final String AUTH_METHOD =
      "account.v1.AccountActorStagingEligibilityService/ResolvePreseededActorStagingEligibility";
  private static final String NAMESPACE = "test";
  private static final String ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final UUID ACCOUNT_UUID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID TENANT_UUID = UUID.fromString("f8871fb0-7810-4b72-bb13-09e29a3509f2");
  private static final UUID REQUEST_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SOURCE_OPERATION_UUID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID EVENT_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");

  private final AccountActorStagingEligibilityService ownerService =
      mock(AccountActorStagingEligibilityService.class);
  private final AccountActorStagingEligibilityGrpcService service =
      new AccountActorStagingEligibilityGrpcService(ownerService, NAMESPACE);

  @Test
  void grpcRegistrationIsOptInAndTheExactMethodHasMtlSHandlerCoverage() throws IOException {
    ConditionalOnProperty condition =
        AccountActorStagingEligibilityGrpcService.class.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("firemud.account.actor-staging-eligibility");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();

    String baseConfiguration = resource("application.yml");
    String productionConfiguration = resource("application-prod.yml");
    assertThat(baseConfiguration)
        .contains("enabled: ${FIREMUD_ACCOUNT_ACTOR_STAGING_ELIGIBILITY_ENABLED:false}")
        .contains("- " + AUTH_METHOD);
    assertThat(productionConfiguration).contains("- " + AUTH_METHOD);
  }

  @Test
  void returnsExactStagingOnlySnapshotToSameNamespaceEntityPeer() {
    AccountActorStagingEligibilityEvidence evidence = evidence();
    when(ownerService.resolve(
            1,
            NAMESPACE,
            REQUEST_UUID,
            ACCOUNT_UUID,
            TENANT_UUID,
            Purpose.PUBLIC_PRODUCTION_STAGING_ONLY))
        .thenReturn(evidence);

    TestObserver observer = call(request(), ENTITY_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    ResolvePreseededActorStagingEligibilityResponse response = observer.value;
    assertThat(response.getSchemaVersion()).isEqualTo(1);
    assertThat(response.getTargetNamespace()).isEqualTo(NAMESPACE);
    assertThat(response.getRequestId()).isEqualTo(REQUEST_UUID.toString());
    assertThat(response.getCanonicalAccountId()).isEqualTo(ACCOUNT_UUID.toString());
    assertThat(response.getCanonicalTenantId()).isEqualTo(TENANT_UUID.toString());
    assertThat(response.getPurpose())
        .isEqualTo(ActorStagingEligibilityPurpose.PUBLIC_PRODUCTION_STAGING_ONLY);
    assertThat(response.getCurrentness().name()).isEqualTo("CURRENT_AT_REVALIDATION");
    assertThat(response.getDecision().name()).isEqualTo("STAGING_ELIGIBLE");
    assertThat(response.getMembershipVersion()).isEqualTo(2L);
    assertThat(response.getMembershipEventSequence()).isEqualTo(1L);
    assertThat(response.getMembershipEventId()).isEqualTo(EVENT_UUID.toString());
    assertThat(response.getTenantSourceOperationId()).isEqualTo(SOURCE_OPERATION_UUID.toString());
    assertThat(response.getAuthoritySnapshotDigest()).isEqualTo(evidence.authoritySnapshotDigest());
    verify(ownerService)
        .resolve(
            1,
            NAMESPACE,
            REQUEST_UUID,
            ACCOUNT_UUID,
            TENANT_UUID,
            Purpose.PUBLIC_PRODUCTION_STAGING_ONLY);
  }

  @Test
  void deniesNonEntityOrWrongNamespacePeersBeforeReadingAccountSources() {
    assertThat(status(call(request(), null))).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status(call(request(), "spiffe://firemud/ns/other/sa/entity-management-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status(call(request(), "spiffe://firemud/ns/test/sa/game-session-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status(call(request(), "spiffe://firemud/ns/test/sa/account-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(ownerService);
  }

  @Test
  void rejectsOpenOrUnsupportedRequestsBeforeReadingAccountSources() {
    ResolvePreseededActorStagingEligibilityRequest unknownField =
        request().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    ResolvePreseededActorStagingEligibilityRequest unsupportedPurpose =
        request().toBuilder()
            .setPurpose(
                ActorStagingEligibilityPurpose.ACTOR_STAGING_ELIGIBILITY_PURPOSE_UNSPECIFIED)
            .build();
    ResolvePreseededActorStagingEligibilityRequest unsupportedSchema =
        request().toBuilder().setSchemaVersion(2).build();
    ResolvePreseededActorStagingEligibilityRequest wrongNamespace =
        request().toBuilder().setTargetNamespace("other").build();

    assertThat(status(call(unknownField, ENTITY_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(status(call(unsupportedPurpose, ENTITY_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(status(call(unsupportedSchema, ENTITY_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(status(call(wrongNamespace, ENTITY_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            status(
                call(request().toBuilder().setCanonicalTenantId("not-a-uuid").build(), ENTITY_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(ownerService);
  }

  @Test
  void distinguishesMissingCurrentEvidenceFromTemporarySourceFailure() {
    when(ownerService.resolve(
            1,
            NAMESPACE,
            REQUEST_UUID,
            ACCOUNT_UUID,
            TENANT_UUID,
            Purpose.PUBLIC_PRODUCTION_STAGING_ONLY))
        .thenThrow(new IllegalStateException("private Account source detail"))
        .thenThrow(new DataAccessResourceFailureException("private database endpoint"));

    TestObserver missing = call(request(), ENTITY_URI);
    TestObserver unavailable = call(request(), ENTITY_URI);

    assertThat(status(missing)).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(missing.failure.getDescription())
        .isEqualTo("Current Account membership evidence is absent or contradictory");
    assertThat(status(unavailable)).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  void classifiesTransactionCreationFailureAsSanitizedUnavailable() {
    when(ownerService.resolve(
            1,
            NAMESPACE,
            REQUEST_UUID,
            ACCOUNT_UUID,
            TENANT_UUID,
            Purpose.PUBLIC_PRODUCTION_STAGING_ONLY))
        .thenThrow(new CannotCreateTransactionException("private database endpoint"));

    TestObserver unavailable = call(request(), ENTITY_URI);

    assertThat(status(unavailable)).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.failure.getDescription())
        .isEqualTo("Account owner evidence is temporarily unavailable")
        .doesNotContain("private database endpoint");
  }

  private TestObserver call(
      ResolvePreseededActorStagingEligibilityRequest request, String peerUri) {
    TestObserver observer = new TestObserver();
    Runnable invocation = () -> service.resolvePreseededActorStagingEligibility(request, observer);
    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(invocation);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return observer;
  }

  private static ResolvePreseededActorStagingEligibilityRequest request() {
    return ResolvePreseededActorStagingEligibilityRequest.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(NAMESPACE)
        .setRequestId(REQUEST_UUID.toString())
        .setCanonicalAccountId(ACCOUNT_UUID.toString())
        .setCanonicalTenantId(TENANT_UUID.toString())
        .setPurpose(ActorStagingEligibilityPurpose.PUBLIC_PRODUCTION_STAGING_ONLY)
        .build();
  }

  private static AccountActorStagingEligibilityEvidence evidence() {
    return AccountActorStagingEligibilityEvidence.seal(
        NAMESPACE,
        REQUEST_UUID,
        ACCOUNT_UUID,
        TENANT_UUID,
        Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
        Instant.parse("2026-10-08T00:00:00Z"),
        "ACCOUNT_V29_MIGRATION",
        "ACTIVE",
        "ACTIVE",
        true,
        "EXPLICIT_JOIN",
        2L,
        1L,
        "FRESH_GAME_DESIGN",
        SOURCE_OPERATION_UUID,
        "sha256:" + "a".repeat(64),
        1L,
        EVENT_UUID,
        "sha256:" + "b".repeat(64),
        false);
  }

  private static String resource(String name) throws IOException {
    try (var input = new FileSystemResource("src/main/resources/" + name).getInputStream()) {
      return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
    }
  }

  private static Status.Code status(TestObserver observer) {
    assertThat(observer.failure).isNotNull();
    return observer.failure.getCode();
  }

  private static final class TestObserver
      implements StreamObserver<ResolvePreseededActorStagingEligibilityResponse> {
    private ResolvePreseededActorStagingEligibilityResponse value;
    private Status failure;
    private boolean completed;

    @Override
    public void onNext(ResolvePreseededActorStagingEligibilityResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
