package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
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
import tools.jackson.databind.json.JsonMapper;

class StartSessionLaunchDescriptorProducerTest {
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final UUID VERSION = uuid("03333333-3333-4333-8333-333333333333");
  private static final UUID COMMIT = uuid("04444444-4444-4444-8444-444444444444");
  private static final String NAMESPACE = "world-runtime";
  private static final long FENCE = 8L;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void requiresExactReplayAndSameNamespacePeerBeforeOwnerWork() {
    var associationReader = mock(StartSessionTemplateAssociationReadService.class);
    var transactions = mock(PlatformTransactionManager.class);
    var producer =
        new StartSessionLaunchDescriptorProducer(
            mock(DSLContext.class),
            transactions,
            NAMESPACE,
            associationReader,
            mock(PublishedReleaseBundleService.class),
            JSON);
    var initial = request(new InitialConfigured());

    assertThatThrownBy(() -> withPeer(() -> producer.resolve(initial), "game-session-service"))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(associationReader, transactions);

    assertThatThrownBy(() -> withPeer(() -> producer.resolve(null), "game-session-service"))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(associationReader, transactions);

    assertThatThrownBy(
            () ->
                withPeer(
                    () ->
                        producer.resolve(
                            request(
                                new ExactReplay(
                                    VERSION, COMMIT, "selected-workflow", digest('a')))),
                    "account-service"))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(associationReader, transactions);
  }

  @Test
  void delegatesExactReplayToAssociationReaderBeforeOpeningLocalWriteTransaction() {
    // This is an explicit upstream boundary double, not Account/mTLS or owner-storage proof.
    var associationReader = mock(StartSessionTemplateAssociationReadService.class);
    var transactions = mock(PlatformTransactionManager.class);
    var producer =
        new StartSessionLaunchDescriptorProducer(
            mock(DSLContext.class),
            transactions,
            NAMESPACE,
            associationReader,
            mock(PublishedReleaseBundleService.class),
            JSON);
    var request = request(new ExactReplay(VERSION, COMMIT, "selected-workflow", digest('a')));
    when(associationReader.read(request))
        .thenThrow(
            Status.UNAVAILABLE
                .withDescription("test seam: independent Account projection unavailable")
                .asRuntimeException());

    assertThatThrownBy(() -> withPeer(() -> producer.resolve(request), "game-session-service"))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
    verifyNoInteractions(transactions);
  }

  private static void withPeer(Runnable action, String workload) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + workload, NAMESPACE, workload);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  private static StartSessionTemplateAssociationReadEvidence.Request request(
      StartSessionTemplateAssociationReadEvidence.Selection selection) {
    return new StartSessionTemplateAssociationReadEvidence.Request(
        1,
        NAMESPACE,
        uuid("01111111-1111-4111-8111-111111111111"),
        postTuple("launch-descriptor-test").canonicalBytes(),
        ATTEMPT,
        FENCE,
        selection);
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(String requestId) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact StartSession descriptor selection"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
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
                "scope", Map.of("tenantId", tenant, "targetNamespace", NAMESPACE),
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

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
