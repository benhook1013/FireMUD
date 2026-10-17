package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.junit.jupiter.api.Test;

class WorldCanonicalInstanceExecutionIdentityTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID PARTICIPATION_ID = uuid("13131313-1313-4313-8313-131313131313");
  private static final UUID ATTEMPT_ID = uuid("12121212-1212-4121-8121-121212121212");
  private static final UUID MUTATION_ID = uuid("14141414-1414-4414-8414-141414141414");
  private static final UUID GAME_INSTANCE_ID = uuid("23232323-2323-4323-8323-232323232323");
  private static final long ACCOUNT_FENCE = 31L;
  private static final long OWNER_FENCE = 37L;

  @Test
  void observationWithNullMutationIdIsRejectedAsIncomplete() {
    var tupleBytes = originalTuple().canonicalBytes();
    var observation = observation(tupleBytes);
    var observationBytes =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observation).toByteArray();

    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceExecutionIdentity(
                    tupleBytes,
                    PARTICIPATION_ID,
                    ACCOUNT_FENCE,
                    null,
                    ATTEMPT_ID,
                    OWNER_FENCE,
                    observation,
                    GAME_INSTANCE_ID,
                    "{}",
                    observationBytes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Account original-attempt observation is incomplete");
  }

  @Test
  void validCompleteAndLegacyObservationFreeFormsRemainConstructible() {
    var tupleBytes = originalTuple().canonicalBytes();
    var observation = observation(tupleBytes);
    var observationBytes =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observation).toByteArray();
    var complete =
        new WorldCanonicalInstanceExecutionIdentity(
            tupleBytes,
            PARTICIPATION_ID,
            ACCOUNT_FENCE,
            MUTATION_ID,
            ATTEMPT_ID,
            OWNER_FENCE,
            observation,
            GAME_INSTANCE_ID,
            "{}",
            observationBytes);

    assertThat(complete.hasOriginalAttemptEvidence()).isTrue();
    assertThat(complete.originalAttemptEvidenceBytes()).containsExactly(observationBytes);

    var legacy =
        new WorldCanonicalInstanceExecutionIdentity(
            tupleBytes,
            PARTICIPATION_ID,
            ACCOUNT_FENCE,
            ATTEMPT_ID,
            OWNER_FENCE,
            GAME_INSTANCE_ID,
            "{}");

    assertThat(legacy.gameSessionOwnerMutationId()).isNull();
    assertThat(legacy.hasOriginalAttemptEvidence()).isFalse();
  }

  private static OriginalStartSessionCurrentAttemptEvidence.Result observation(byte[] tupleBytes) {
    var request =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            uuid("15151515-1515-4515-8515-151515151515"),
            "test",
            tupleBytes,
            ATTEMPT_ID,
            MUTATION_ID,
            OWNER_FENCE);
    return new OriginalStartSessionCurrentAttemptEvidence.Result(
        request, Instant.parse("2030-01-01T00:00:00Z"), tupleBytes);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "execution-identity-test",
            uuid("a4f5f4eb-8243-4d42-903a-33495456a622"),
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, "test"),
                new StartSessionOperatorAction.Target(
                    91L, uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "synthetic execution identity constructor test"));
    byte[] bundle =
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            preTuple,
            new Values(
                uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f"),
                "sha256:" + "a".repeat(64),
                "17",
                "CURRENT",
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"),
                1L,
                2L,
                3L,
                4L,
                5L,
                23L,
                uuid("a681bba7-c215-4cf1-a35b-14348912cbdc"),
                "2",
                "3"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/test/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
        19L,
        bundle,
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
