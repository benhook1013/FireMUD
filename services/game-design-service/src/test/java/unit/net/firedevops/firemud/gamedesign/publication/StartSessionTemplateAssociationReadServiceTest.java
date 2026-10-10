package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class StartSessionTemplateAssociationReadServiceTest {
  private static final UUID TENANT = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID ATTEMPT = UUID.fromString("02222222-2222-4222-8222-222222222222");
  private static final UUID RESERVATION_OWNER =
      UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final long FENCE = 8L;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final DSLContext dsl = mock(DSLContext.class);
  private final PublishedReleaseBundleService releases = mock(PublishedReleaseBundleService.class);

  @Test
  void independentlyChecksExactAccountAttemptBeforeOpeningAnyLocalSnapshot() {
    var tuple = postTuple("account-before-local-snapshot");
    var request = request(tuple);
    var accountCalled = new AtomicBoolean();
    PlatformTransactionManager transactions =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (!accountCalled.get()) {
              throw new AssertionError("Local snapshot opened before Account projection");
            }
            throw new IllegalStateException("test stops at the first local transaction boundary");
          }

          @Override
          public void commit(TransactionStatus status) {
            throw new AssertionError("No transaction should be committed");
          }

          @Override
          public void rollback(TransactionStatus status) {
            throw new AssertionError("No transaction should be rolled back");
          }
        };
    var service =
        new StartSessionTemplateAssociationReadService(
            dsl,
            transactions,
            "world-runtime",
            (actualTuple, attempt, fence) -> {
              accountCalled.set(true);
              if (!tuple.controlPlaneRequestId().equals(actualTuple.controlPlaneRequestId())
                  || !ATTEMPT.equals(attempt)
                  || FENCE != fence) {
                throw new AssertionError("Changed exact Account projection selector");
              }
              return projection(tuple, ATTEMPT, FENCE);
            },
            releases);

    assertThatThrownBy(() -> withPeer(service, request, "game-session-service"))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(dsl);
    verifyNoInteractions(releases);
  }

  @Test
  void deniesWrongWorkloadBeforeCallingAccountOrOpeningLocalStorage() {
    var tuple = postTuple("wrong-peer");
    var service =
        new StartSessionTemplateAssociationReadService(
            dsl,
            unusedTransactionManager(),
            "world-runtime",
            (ignored, attempt, fence) -> {
              throw new AssertionError("Account must not be called for a different workload");
            },
            releases);
    assertThatThrownBy(() -> withPeer(service, request(tuple), "account-service"))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(dsl);
    verifyNoInteractions(releases);
  }

  @Test
  void rejectsMismatchedAccountAttemptFenceBeforeLocalRead() {
    var tuple = postTuple("changed-account-fence");
    var service =
        new StartSessionTemplateAssociationReadService(
            dsl,
            unusedTransactionManager(),
            "world-runtime",
            (actual, attempt, fence) -> projection(actual, attempt, fence + 1L),
            releases);
    assertThatThrownBy(() -> withPeer(service, request(tuple), "game-session-service"))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(dsl);
    verifyNoInteractions(releases);
  }

  private static void withPeer(
      StartSessionTemplateAssociationReadService service,
      StartSessionTemplateAssociationReadEvidence.Request request,
      String workload) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/world-runtime/sa/" + workload, "world-runtime", workload);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.read(request);
    } finally {
      context.detach(previous);
    }
  }

  private static StartSessionTemplateAssociationReadEvidence.Request request(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return new StartSessionTemplateAssociationReadEvidence.Request(
        1,
        "world-runtime",
        UUID.fromString("01111111-1111-4111-8111-111111111111"),
        tuple.canonicalBytes(),
        ATTEMPT,
        FENCE,
        new StartSessionTemplateAssociationReadEvidence.InitialConfigured());
  }

  private static ReadRedeemedOperationProjectionResponse projection(
      StartSessionPostAuthorizationExecutionTuple tuple, UUID attempt, long fence) {
    return ReadRedeemedOperationProjectionResponse.newBuilder()
        .setControlPlaneRequestId(tuple.controlPlaneRequestId())
        .setCanonicalPreAuthorizationTupleBytes(
            ByteString.copyFrom(
                tuple
                    .preAuthorizationTuple()
                    .canonicalJson()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .setMutationDigest(tuple.preAuthorizationTuple().mutationDigest())
        .setOwnerAttemptId(attempt.toString())
        .setOwnerFence(fence)
        .setAuthenticatedRedeemerWorkloadIdentity(
            "spiffe://firemud/ns/world-runtime/sa/game-session-service")
        .build();
  }

  private static PlatformTransactionManager unusedTransactionManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        throw new AssertionError("Local storage must not be reached");
      }

      @Override
      public void commit(TransactionStatus status) {
        throw new AssertionError("No transaction should be committed");
      }

      @Override
      public void rollback(TransactionStatus status) {
        throw new AssertionError("No transaction should be rolled back");
      }
    };
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(String requestId) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, "world-runtime"),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact StartSession account projection"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/world-runtime/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenant = TENANT.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenant, "targetNamespace", "world-runtime"),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenant),
            "accountProjectionEvidence",
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", digest('a'),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId", ISSUANCE_ID.toString(),
                "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind",
                        "controlPlaneRequestId",
                        "requestId",
                        tuple.controlPlaneRequestId()),
                "mutationDigest", tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenant, 3L),
                "membershipAuthorityGeneration", Map.of(tenant, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                ACTOR.toString(),
                "controlUiTokenJti",
                TOKEN_JTI.toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
