package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal.Outcome;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class WorldStartSessionExecutionTerminalTest {
  private static final UUID TENANT = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER =
      UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final UUID PARTICIPATION_ID =
      UUID.fromString("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID GAME_SESSION_ATTEMPT_ID =
      UUID.fromString("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final String REQUEST_ID = "start-world-terminal-vector";
  private static final String NAMESPACE = "world-runtime";
  private static final String PREPARATION_INPUT =
      "{ \"selected\" : \"immutable source\", \"revision\": 3 }\n";
  private static final String WORKLOAD =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String EXPECTED_PREPARATION_INPUT_DIGEST =
      "sha256:0b98b4ce717296fdaaeece6709c7c7d7b1b4c149ed1dc5b0c1afc43c91fe2bb1";
  private static final String EXPECTED_ORIGINAL_TUPLE_BASE64 =
      String.join(
          "",
          "eyJhY3Rpb25GYW1pbHkiOiJTdGFydFNlc3Npb24iLCJhY3Rpb25GYW1pbHlSZXF1ZXN0SWRlbnRpdHkiOnsicmVxdWVzdElkIjoi",
          "c3RhcnQtd29ybGQtdGVybWluYWwtdmVjdG9yIiwicmVxdWVzdElkZW50aXR5S2luZCI6ImNvbnRyb2xQbGFuZVJlcXVlc3RJZCJ9",
          "LCJhY3Rpb25GYW1pbHlTY2hlbWFJZCI6ImZpcmVtdWQuZ2FtZS1zZXNzaW9uLnN0YXJ0LXNlc3Npb24iLCJhY3Rpb25GYW1pbHlT",
          "Y2hlbWFWZXJzaW9uIjoiMSIsImFjdG9yIjp7ImFjY291bnRJZCI6ImE0ZjVmNGViLTgyNDMtNGQ0Mi05MDNhLTMzNDk1NDU2YTYy",
          "MiIsImtpbmQiOiJIVU1BTiJ9LCJhdWRpdFJlYXNvbiI6ImNhbm9uaWNhbCBTdGFydFNlc3Npb24gb3duZXIgYXR0ZW1wdCIsImF1",
          "dGhlbnRpY2F0ZWRXb3JrbG9hZElkZW50aXR5Ijp7ImlkZW50aXR5S2luZCI6Ik1VVFVBTF9UTFNfU1BJRkZFIiwidXJpIjoic3Bp",
          "ZmZlOi8vZmlyZW11ZC9ucy93b3JsZC1ydW50aW1lL3NhL2xvZ2dpbmctYWRtaW4tc2VydmljZSJ9LCJhdXRob3JpdHlFdmlkZW5j",
          "ZUJ1bmRsZSI6eyJhY2NvdW50UHJvamVjdGlvbkV2aWRlbmNlIjp7ImV2YWx1YXRlZEF0IjoiMjAyNi0xMC0wOVQwMDowMDowMFoi",
          "LCJleHBpcmVzQXQiOiIyMDI2LTEwLTA5VDAwOjA1OjAwWiIsInByb2plY3Rpb25TdGF0dXMiOiJDVVJSRU5UIiwic291cmNlRXZp",
          "ZGVuY2VJZCI6InNoYTI1NjphYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh",
          "YWFhYWFhIiwic291cmNlRXZpZGVuY2VWZXJzaW9uIjoiMTciLCJzb3VyY2VUeXBlIjoiQUNDT1VOVCJ9LCJhdXRob3JpdHlTY29w",
          "ZSI6eyJhY3Rpb25GYW1pbHkiOiJTdGFydFNlc3Npb24iLCJhcHBsaWNhYmxlQWNjb3VudElkIjoiYTRmNWY0ZWItODI0My00ZDQy",
          "LTkwM2EtMzM0OTU0NTZhNjIyIiwiYXBwbGljYWJsZVRlbmFudElkIjoiOWY4ZjA2YjQtMzZlNS00ZDExLTljMmEtNWFkZmQ3ZjQx",
          "NTMxIiwic2NvcGUiOnsidGFyZ2V0TmFtZXNwYWNlIjoid29ybGQtcnVudGltZSIsInRlbmFudElkIjoiOWY4ZjA2YjQtMzZlNS00",
          "ZDExLTljMmEtNWFkZmQ3ZjQxNTMxIn19LCJhdXRob3JpdHlUdXBsZSI6eyJhY2NvdW50QXV0aG9yaXR5R2VuZXJhdGlvbiI6Miwi",
          "aXNzdWVyQXV0aEdlbmVyYXRpb24iOjEsIm1lbWJlcnNoaXBBdXRob3JpdHlHZW5lcmF0aW9uIjp7IjlmOGYwNmI0LTM2ZTUtNGQx",
          "MS05YzJhLTVhZGZkN2Y0MTUzMSI6NH0sInByaXZhdGVSZWFsbUdyYW50VmVyc2lvbnMiOltdLCJ0ZW5hbnRBdXRob3JpdHlHZW5l",
          "cmF0aW9uIjp7IjlmOGYwNmI0LTM2ZTUtNGQxMS05YzJhLTVhZGZkN2Y0MTUzMSI6M319LCJidW5kbGVWZXJzaW9uIjoiYXV0aG9y",
          "aXR5RXZpZGVuY2VCdW5kbGUvdjEiLCJpc3N1YW5jZUV2aWRlbmNlIjp7ImFjY291bnRHZW5lcmF0aW9uIjoiMiIsImFjdG9yQWNj",
          "b3VudElkIjoiYTRmNWY0ZWItODI0My00ZDQyLTkwM2EtMzM0OTU0NTZhNjIyIiwiY29udHJvbFVpVG9rZW5KdGkiOiJhNjgxYmJh",
          "Ny1jMjE1LTRjZjEtYTM1Yi0xNDM0ODkxMmNiZGMiLCJldmlkZW5jZVR5cGUiOiJIdW1hbkF1dGhvcml0eUV2aWRlbmNlL3YxIiwi",
          "cm9sZSI6InRlbmFudEFkbWluIiwidGVuYW50R2VuZXJhdGlvbiI6IjMifSwiaXNzdWFuY2VGZW5jZSI6IjIzIiwiaXNzdWFuY2VL",
          "aW5kIjoiaHVtYW5fb3BlcmF0b3IiLCJpc3N1YW5jZU9wZXJhdGlvbklkZW50aXR5Ijp7ImFjdGlvbkZhbWlseVJlcXVlc3RJZGVu",
          "dGl0eSI6eyJyZXF1ZXN0SWQiOiJzdGFydC13b3JsZC10ZXJtaW5hbC12ZWN0b3IiLCJyZXF1ZXN0SWRlbnRpdHlLaW5kIjoiY29u",
          "dHJvbFBsYW5lUmVxdWVzdElkIn0sImNvbnRyb2xQbGFuZVJlcXVlc3RJZCI6InN0YXJ0LXdvcmxkLXRlcm1pbmFsLXZlY3RvciIs",
          "Imlzc3VhbmNlT3BlcmF0aW9uSWQiOiJmNWQwNDRiZC03ZTVmLTRlMmQtOTg1OS05MDI1Y2JkY2M2MGYiLCJtdXRhdGlvbkRpZ2Vz",
          "dCI6ImQyMGY5MDVkYjdiNmUzOTVjODAwOGY2ZjZlNDk4NjY0MWM1NTk1ZTEzNWRjZDkwN2E0YzQ2NGZmNjQyMDEwZTAifSwibWVt",
          "YmVyc2hpcFZlcnNpb24iOnsiOWY4ZjA2YjQtMzZlNS00ZDExLTljMmEtNWFkZmQ3ZjQxNTMxIjo1fX0sImF1dGhvcml0eUV2aWRl",
          "bmNlQnVuZGxlUmVmZXJlbmNlIjp7ImJ1bmRsZVZlcnNpb24iOiJhdXRob3JpdHlFdmlkZW5jZUJ1bmRsZS92MSIsImxpbmVhcml6",
          "YXRpb24iOiIxODQ0Njc0NDA3MzcwOTU1MTYxNSIsInNvdXJjZUZlbmNlIjoiMjMiLCJzb3VyY2VWZXJzaW9uIjoiMTcifSwiYXV0",
          "aG9yaXR5VHVwbGUiOnsiYWNjb3VudEF1dGhvcml0eUdlbmVyYXRpb24iOjIsImlzc3VlckF1dGhHZW5lcmF0aW9uIjoxLCJtZW1i",
          "ZXJzaGlwQXV0aG9yaXR5R2VuZXJhdGlvbiI6eyI5ZjhmMDZiNC0zNmU1LTRkMTEtOWMyYS01YWRmZDdmNDE1MzEiOjR9LCJwcml2",
          "YXRlUmVhbG1HcmFudFZlcnNpb25zIjpbXSwidGVuYW50QXV0aG9yaXR5R2VuZXJhdGlvbiI6eyI5ZjhmMDZiNC0zNmU1LTRkMTEt",
          "OWMyYS01YWRmZDdmNDE1MzEiOjN9fSwiYXV0aG9yaXphdGlvblJlZmVyZW5jZUZpbmdlcnByaW50IjoiYXJmcC92MS90ZXN0LWtl",
          "eS9iYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiIiwiYXV0b21h",
          "dGlvblBvbGljeSI6eyJwcmVzZW5jZSI6IkFCU0VOVCJ9LCJjb250cm9sUGxhbmVSZXF1ZXN0SWQiOiJzdGFydC13b3JsZC10ZXJt",
          "aW5hbC12ZWN0b3IiLCJleHBlY3RlZFZlcnNpb24iOnsicHJlc2VuY2UiOiJBQlNFTlQifSwiaXNzdWFuY2VGZW5jZSI6IjIzIiwi",
          "aXNzdWFuY2VLaW5kIjoiaHVtYW5fb3BlcmF0b3IiLCJtZW1iZXJzaGlwVmVyc2lvbiI6eyI5ZjhmMDZiNC0zNmU1LTRkMTEtOWMy",
          "YS01YWRmZDdmNDE1MzEiOjV9LCJtdXRhdGlvbiI6eyJjbGllbnRJcCI6eyJwcmVzZW5jZSI6IkFCU0VOVCJ9fSwibXV0YXRpb25E",
          "aWdlc3QiOiJkMjBmOTA1ZGI3YjZlMzk1YzgwMDhmNmY2ZTQ5ODY2NDFjNTU5NWUxMzVkY2Q5MDdhNGM0NjRmZjY0MjAxMGUwIiwi",
          "cHJlQXV0aG9yaXphdGlvblJlc2VydmF0aW9uVHVwbGUiOnsiYWN0aW9uRmFtaWx5IjoiU3RhcnRTZXNzaW9uIiwiYWN0aW9uRmFt",
          "aWx5UmVxdWVzdElkZW50aXR5Ijp7InJlcXVlc3RJZCI6InN0YXJ0LXdvcmxkLXRlcm1pbmFsLXZlY3RvciIsInJlcXVlc3RJZGVu",
          "dGl0eUtpbmQiOiJjb250cm9sUGxhbmVSZXF1ZXN0SWQifSwiYWN0aW9uRmFtaWx5U2NoZW1hSWQiOiJmaXJlbXVkLmdhbWUtc2Vz",
          "c2lvbi5zdGFydC1zZXNzaW9uIiwiYWN0aW9uRmFtaWx5U2NoZW1hVmVyc2lvbiI6IjEiLCJhY3RvciI6eyJhY2NvdW50SWQiOiJh",
          "NGY1ZjRlYi04MjQzLTRkNDItOTAzYS0zMzQ5NTQ1NmE2MjIiLCJraW5kIjoiSFVNQU4ifSwiYXVkaXRSZWFzb24iOiJjYW5vbmlj",
          "YWwgU3RhcnRTZXNzaW9uIG93bmVyIGF0dGVtcHQiLCJhdXRvbWF0aW9uUG9saWN5Ijp7InByZXNlbmNlIjoiQUJTRU5UIn0sImV4",
          "cGVjdGVkVmVyc2lvbiI6eyJwcmVzZW5jZSI6IkFCU0VOVCJ9LCJtdXRhdGlvbiI6eyJjbGllbnRJcCI6eyJwcmVzZW5jZSI6IkFC",
          "U0VOVCJ9fSwic2NvcGUiOnsidGFyZ2V0TmFtZXNwYWNlIjoid29ybGQtcnVudGltZSIsInRlbmFudElkIjoiOWY4ZjA2YjQtMzZl",
          "NS00ZDExLTljMmEtNWFkZmQ3ZjQxNTMxIn0sInRhcmdldCI6eyJnYW1lVGVtcGxhdGVJZCI6IjkxIiwib3duZXJBY2NvdW50SWQi",
          "OiIzNmFhOWNlNS0wZWJjLTRjMTQtOWY2Yi1kMTYwZWRjNjA1OWEifSwidGFyZ2V0T3duZXIiOnsib3duZXJTZXJ2aWNlIjoiZ2Ft",
          "ZS1zZXNzaW9uLXNlcnZpY2UifSwidHVwbGVTY2hlbWFJZCI6InByZUF1dGhvcml6YXRpb25SZXNlcnZhdGlvblR1cGxlIiwidHVw",
          "bGVTY2hlbWFWZXJzaW9uIjoiMSJ9LCJyZXNlcnZhdGlvbkNsYWltRmVuY2UiOjE5LCJyZXNlcnZhdGlvbk93bmVySWQiOiI3YzAw",
          "NWI2NS1mY2IxLTRhYzktYTcxNC1mM2QwZjQ0OWVkY2YiLCJzY29wZSI6eyJ0YXJnZXROYW1lc3BhY2UiOiJ3b3JsZC1ydW50aW1l",
          "IiwidGVuYW50SWQiOiI5ZjhmMDZiNC0zNmU1LTRkMTEtOWMyYS01YWRmZDdmNDE1MzEifSwidGFyZ2V0Ijp7ImdhbWVUZW1wbGF0",
          "ZUlkIjoiOTEiLCJvd25lckFjY291bnRJZCI6IjM2YWE5Y2U1LTBlYmMtNGMxNC05ZjZiLWQxNjBlZGM2MDU5YSJ9LCJ0YXJnZXRP",
          "d25lciI6eyJvd25lclNlcnZpY2UiOiJnYW1lLXNlc3Npb24tc2VydmljZSJ9LCJ0dXBsZVNjaGVtYUlkIjoicG9zdEF1dGhvcml6",
          "YXRpb25FeGVjdXRpb25UdXBsZSIsInR1cGxlU2NoZW1hVmVyc2lvbiI6IjEifQ==");
  private static final String EXPECTED_DIGEST =
      "sha256:a3304a2e145bd7eec557cd87d233e9dd399d5c3a5c658438f2039eb7e0898eb1";
  private static final String EXPECTED_CANONICAL_PREIMAGE =
      "{\"accountWorldParticipationFence\":\"31\","
          + "\"accountWorldParticipationId\":\"a8c1e8c8-f237-41b7-918d-ec2281bcac10\","
          + "\"canonicalGameInstanceId\":\"c0e30aea-1549-43d9-93af-0e44a3deae32\","
          + "\"canonicalTenantId\":\"9f8f06b4-36e5-4d11-9c2a-5adfd7f41531\","
          + "\"controlPlaneRequestId\":\"start-world-terminal-vector\","
          + "\"gameSessionOwnerAttemptId\":\"b9d2f9d9-0438-42c8-829e-fd3392cd9d21\","
          + "\"gameSessionOwnerFence\":\"37\","
          + "\"originalPostAuthorizationTupleBase64\":\""
          + EXPECTED_ORIGINAL_TUPLE_BASE64
          + "\",\"outcome\":\"COMMITTED\","
          + "\"preparationInputDigest\":\""
          + EXPECTED_PREPARATION_INPUT_DIGEST
          + "\",\"preparationInputJson\":\"{ \\\"selected\\\" : \\\"immutable source\\\", \\\"revision\\\": 3 }\\n\","
          + "\"schema\":\"world-start-session-execution-terminal/v1\","
          + "\"targetNamespace\":\"world-runtime\",\"worldExecutionFence\":\"41\"}";

  @Test
  void committedTerminalMatchesFrozenCanonicalVectorAndPreservesExactTupleAndInput()
      throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple();
    byte[] originalTupleBytes = tuple.canonicalBytes();
    WorldStartSessionExecutionTerminal terminal = terminal(tuple, Outcome.COMMITTED);

    assertThat(new String(terminal.canonicalBytes(), StandardCharsets.UTF_8))
        .isEqualTo(EXPECTED_CANONICAL_PREIMAGE);
    assertThat(terminal.digest()).isEqualTo(EXPECTED_DIGEST);
    assertThat(terminal.originalPostAuthorizationTupleBase64())
        .isEqualTo(EXPECTED_ORIGINAL_TUPLE_BASE64);
    assertThat(terminal.originalPostAuthorizationTuple()).containsExactly(originalTupleBytes);
    assertThat(terminal.preparationInputJson()).isEqualTo(PREPARATION_INPUT);

    WorldStartSessionExecutionTerminal decoded =
        WorldStartSessionExecutionTerminal.fromStored(terminal.canonicalBytes());
    assertThat(decoded.canonicalBytes()).containsExactly(terminal.canonicalBytes());
    assertThat(decoded.digest()).isEqualTo(terminal.digest());
    assertThat(decoded.originalPostAuthorizationTuple()).containsExactly(originalTupleBytes);
    assertThat(decoded.originalPostAuthorizationTupleBase64())
        .isEqualTo(Base64.getEncoder().encodeToString(originalTupleBytes));
    assertThat(decoded.preparationInputJson()).isEqualTo(PREPARATION_INPUT);
    assertThat(decoded.accountWorldParticipationFence()).isEqualTo(31L);
    assertThat(decoded.gameSessionOwnerFence()).isEqualTo(37L);
    assertThat(decoded.worldExecutionFence()).isEqualTo(41L);
  }

  @Test
  void abortedAndCommittedOutcomesAreStableExactReadbacksWithoutObservationFields()
      throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple();
    WorldStartSessionExecutionTerminal committed = terminal(tuple, Outcome.COMMITTED);
    WorldStartSessionExecutionTerminal aborted = terminal(tuple, Outcome.ABORTED);

    assertThat(committed.canonicalBytes()).isNotEqualTo(aborted.canonicalBytes());
    assertThat(committed.digest()).isNotEqualTo(aborted.digest());
    for (WorldStartSessionExecutionTerminal expected : List.of(committed, aborted)) {
      byte[] firstRead = expected.canonicalBytes();
      WorldStartSessionExecutionTerminal firstDecoded =
          WorldStartSessionExecutionTerminal.fromStored(firstRead);
      WorldStartSessionExecutionTerminal secondDecoded =
          WorldStartSessionExecutionTerminal.fromStored(firstDecoded.canonicalBytes());
      assertThat(secondDecoded.canonicalBytes()).containsExactly(firstRead);
      assertThat(secondDecoded.digest()).isEqualTo(expected.digest());
    }

    assertInvalid(
        withField(committed.canonicalBytes(), "currentReadCorrelationId", "later-read-id"));
    assertInvalid(
        withField(committed.canonicalBytes(), "authorizationExpiry", "2099-01-01T00:00:00Z"));
    assertInvalid(withField(committed.canonicalBytes(), "outcome", "PENDING"));
    assertInvalid(withField(committed.canonicalBytes(), "outcome", "TIMEOUT"));
  }

  @Test
  void retainsEveryBindingAndFenceAndRejectsScopeSubstitution() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple();
    WorldStartSessionExecutionTerminal baseline = terminal(tuple, Outcome.COMMITTED);
    byte[] original = baseline.canonicalBytes();

    assertChanged(
        new WorldStartSessionExecutionTerminal(
            tuple.canonicalBytes(),
            uuid("d1e40bfb-265a-44ea-84b0-1f55b4efbf43"),
            31L,
            GAME_SESSION_ATTEMPT_ID,
            37L,
            NAMESPACE,
            TENANT,
            REQUEST_ID,
            GAME_INSTANCE_ID,
            digest(PREPARATION_INPUT),
            PREPARATION_INPUT,
            41L,
            Outcome.COMMITTED),
        original);
    assertChanged(terminal(originalTuple(92L), Outcome.COMMITTED), original);
    assertChanged(
        new WorldStartSessionExecutionTerminal(
            tuple.canonicalBytes(),
            PARTICIPATION_ID,
            43L,
            GAME_SESSION_ATTEMPT_ID,
            37L,
            NAMESPACE,
            TENANT,
            REQUEST_ID,
            GAME_INSTANCE_ID,
            digest(PREPARATION_INPUT),
            PREPARATION_INPUT,
            41L,
            Outcome.COMMITTED),
        original);
    assertChanged(
        new WorldStartSessionExecutionTerminal(
            tuple.canonicalBytes(),
            PARTICIPATION_ID,
            31L,
            uuid("e2f51c0c-376b-45fb-95b1-2066c5f0c054"),
            37L,
            NAMESPACE,
            TENANT,
            REQUEST_ID,
            GAME_INSTANCE_ID,
            digest(PREPARATION_INPUT),
            PREPARATION_INPUT,
            41L,
            Outcome.COMMITTED),
        original);
    assertChanged(
        new WorldStartSessionExecutionTerminal(
            tuple.canonicalBytes(),
            PARTICIPATION_ID,
            31L,
            GAME_SESSION_ATTEMPT_ID,
            47L,
            NAMESPACE,
            TENANT,
            REQUEST_ID,
            GAME_INSTANCE_ID,
            digest(PREPARATION_INPUT),
            PREPARATION_INPUT,
            41L,
            Outcome.COMMITTED),
        original);
    assertChanged(
        new WorldStartSessionExecutionTerminal(
            tuple.canonicalBytes(),
            PARTICIPATION_ID,
            31L,
            GAME_SESSION_ATTEMPT_ID,
            37L,
            NAMESPACE,
            TENANT,
            REQUEST_ID,
            uuid("f3042d1d-487c-46ac-a6c2-3177d6f1d165"),
            digest(PREPARATION_INPUT),
            PREPARATION_INPUT,
            41L,
            Outcome.COMMITTED),
        original);
    assertChanged(
        new WorldStartSessionExecutionTerminal(
            tuple.canonicalBytes(),
            PARTICIPATION_ID,
            31L,
            GAME_SESSION_ATTEMPT_ID,
            37L,
            NAMESPACE,
            TENANT,
            REQUEST_ID,
            GAME_INSTANCE_ID,
            digest(PREPARATION_INPUT),
            PREPARATION_INPUT,
            43L,
            Outcome.COMMITTED),
        original);

    assertThatThrownBy(
            () ->
                new WorldStartSessionExecutionTerminal(
                    tuple.canonicalBytes(),
                    PARTICIPATION_ID,
                    31L,
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    "another-runtime",
                    TENANT,
                    REQUEST_ID,
                    GAME_INSTANCE_ID,
                    digest(PREPARATION_INPUT),
                    PREPARATION_INPUT,
                    41L,
                    Outcome.COMMITTED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scope");
    assertThatThrownBy(
            () ->
                new WorldStartSessionExecutionTerminal(
                    tuple.canonicalBytes(),
                    PARTICIPATION_ID,
                    31L,
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    NAMESPACE,
                    uuid("d4053e2e-598d-47bd-b7d3-4288e702e276"),
                    REQUEST_ID,
                    GAME_INSTANCE_ID,
                    digest(PREPARATION_INPUT),
                    PREPARATION_INPUT,
                    41L,
                    Outcome.COMMITTED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scope");
    assertThatThrownBy(
            () ->
                new WorldStartSessionExecutionTerminal(
                    tuple.canonicalBytes(),
                    PARTICIPATION_ID,
                    31L,
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    NAMESPACE,
                    TENANT,
                    "substituted-request-id",
                    GAME_INSTANCE_ID,
                    digest(PREPARATION_INPUT),
                    PREPARATION_INPUT,
                    41L,
                    Outcome.COMMITTED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scope");

    WorldStartSessionExecutionTerminal equalFenceValues =
        new WorldStartSessionExecutionTerminal(
            tuple.canonicalBytes(),
            PARTICIPATION_ID,
            31L,
            GAME_SESSION_ATTEMPT_ID,
            31L,
            NAMESPACE,
            TENANT,
            REQUEST_ID,
            GAME_INSTANCE_ID,
            digest(PREPARATION_INPUT),
            PREPARATION_INPUT,
            31L,
            Outcome.COMMITTED);
    WorldStartSessionExecutionTerminal equalFenceReadback =
        WorldStartSessionExecutionTerminal.fromStored(equalFenceValues.canonicalBytes());
    assertThat(equalFenceReadback.accountWorldParticipationFence()).isEqualTo(31L);
    assertThat(equalFenceReadback.gameSessionOwnerFence()).isEqualTo(31L);
    assertThat(equalFenceReadback.worldExecutionFence()).isEqualTo(31L);
    assertThat(new String(equalFenceReadback.canonicalBytes(), StandardCharsets.UTF_8))
        .contains(
            "\"accountWorldParticipationFence\":\"31\"",
            "\"gameSessionOwnerFence\":\"31\"",
            "\"worldExecutionFence\":\"31\"");
  }

  @Test
  void rejectsMalformedDuplicateUnknownTrailingAndNoncanonicalStoredValues() throws Exception {
    WorldStartSessionExecutionTerminal terminal = terminal(originalTuple(), Outcome.COMMITTED);
    String canonical = new String(terminal.canonicalBytes(), StandardCharsets.UTF_8);

    assertInvalid(canonical + " ");
    assertInvalid(canonical + "{}");
    assertInvalid(canonical.replace("\"schema\":", "\"extra\":true,\"schema\":"));
    assertInvalid(
        canonical.replace(
            "\"schema\":\"" + WorldStartSessionExecutionTerminal.SCHEMA + "\"",
            "\"schema\":\""
                + WorldStartSessionExecutionTerminal.SCHEMA
                + "\",\"schema\":\""
                + WorldStartSessionExecutionTerminal.SCHEMA
                + "\""));
    assertInvalid(
        canonical.replace(
            "\"schema\":\"world-start-session-execution-terminal/v1\"", "\"schema\":\"v2\""));
    assertInvalid(
        new StringBuilder(canonical).insert(canonical.indexOf("\"schema\""), " ").toString());
    assertThatThrownBy(
            () -> WorldStartSessionExecutionTerminal.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strict UTF-8");
    assertThatThrownBy(
            () ->
                WorldStartSessionExecutionTerminal.fromStored(
                    new byte[WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte limit");

    byte[] tupleReencodedWithoutPadding =
        withField(
            terminal.canonicalBytes(),
            "originalPostAuthorizationTupleBase64",
            terminal.originalPostAuthorizationTupleBase64().replaceAll("=+$", ""));
    if (!terminal.originalPostAuthorizationTupleBase64().endsWith("=")) {
      String padded = terminal.originalPostAuthorizationTupleBase64() + "=";
      tupleReencodedWithoutPadding =
          withField(terminal.canonicalBytes(), "originalPostAuthorizationTupleBase64", padded);
    }
    assertInvalid(tupleReencodedWithoutPadding);
  }

  @Test
  void requiresCanonicalPositiveBigintStringsNonNilUuidsAndExactInputDigest() throws Exception {
    WorldStartSessionExecutionTerminal terminal = terminal(originalTuple(), Outcome.COMMITTED);
    for (String field :
        List.of("accountWorldParticipationFence", "gameSessionOwnerFence", "worldExecutionFence")) {
      for (String invalidCounter : List.of("0", "01", "-1", "9223372036854775808", "٣")) {
        assertInvalid(withField(terminal.canonicalBytes(), field, invalidCounter));
      }
    }
    assertInvalid(withField(terminal.canonicalBytes(), "worldExecutionFence", 41));
    assertInvalid(
        withField(
            terminal.canonicalBytes(),
            "accountWorldParticipationId",
            "00000000-0000-0000-0000-000000000000"));

    assertThat(
            new WorldStartSessionExecutionTerminal(
                    originalTuple().canonicalBytes(),
                    PARTICIPATION_ID,
                    31L,
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    NAMESPACE,
                    TENANT,
                    REQUEST_ID,
                    GAME_INSTANCE_ID,
                    digest(PREPARATION_INPUT),
                    PREPARATION_INPUT,
                    Long.MAX_VALUE,
                    Outcome.ABORTED)
                .worldExecutionFence())
        .isEqualTo(Long.MAX_VALUE);

    assertThatThrownBy(
            () ->
                new WorldStartSessionExecutionTerminal(
                    originalTuple().canonicalBytes(),
                    PARTICIPATION_ID,
                    31L,
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    NAMESPACE,
                    TENANT,
                    REQUEST_ID,
                    GAME_INSTANCE_ID,
                    digest("changed input"),
                    PREPARATION_INPUT,
                    41L,
                    Outcome.COMMITTED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("preparationInputDigest");
    assertThatThrownBy(
            () ->
                new WorldStartSessionExecutionTerminal(
                    originalTuple().canonicalBytes(),
                    PARTICIPATION_ID,
                    31L,
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    NAMESPACE,
                    TENANT,
                    REQUEST_ID,
                    GAME_INSTANCE_ID,
                    digest(PREPARATION_INPUT),
                    "{ \"selected\" : \"different exact bytes\" }",
                    41L,
                    Outcome.COMMITTED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("preparationInputDigest");
  }

  @Test
  void defensivelyCopiesTupleAndCanonicalBytesAndKeepsPreparationStringUnchanged() {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple();
    byte[] suppliedTuple = tuple.canonicalBytes();
    WorldStartSessionExecutionTerminal terminal = terminal(tuple, Outcome.COMMITTED);
    byte[] stored = terminal.canonicalBytes();
    byte[] exposedTuple = terminal.originalPostAuthorizationTuple();
    suppliedTuple[0] = (byte) '!';
    stored[0] = (byte) '!';
    exposedTuple[0] = (byte) '!';

    assertThat(terminal.canonicalBytes()[0]).isNotEqualTo((byte) '!');
    assertThat(terminal.originalPostAuthorizationTuple()[0]).isNotEqualTo((byte) '!');
    assertThat(terminal.preparationInputJson()).isEqualTo(PREPARATION_INPUT);
  }

  private static WorldStartSessionExecutionTerminal terminal(
      StartSessionPostAuthorizationExecutionTuple tuple, Outcome outcome) {
    return new WorldStartSessionExecutionTerminal(
        tuple.canonicalBytes(),
        PARTICIPATION_ID,
        31L,
        GAME_SESSION_ATTEMPT_ID,
        37L,
        NAMESPACE,
        TENANT,
        REQUEST_ID,
        GAME_INSTANCE_ID,
        digest(PREPARATION_INPUT),
        PREPARATION_INPUT,
        41L,
        outcome);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    return originalTuple(91L);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(long gameTemplateId) {
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            REQUEST_ID,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(gameTemplateId, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical StartSession owner attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple, WORKLOAD, FINGERPRINT, RESERVATION_OWNER, 19L, bundle(preTuple), reference());
  }

  private static byte[] bundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenantId = TENANT.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> identity =
        Map.of(
            "issuanceOperationId", ISSUANCE_ID.toString(),
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenantId, 3L),
            "membershipAuthorityGeneration", Map.of(tenantId, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> evidence =
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
            "3");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            identity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static StartSessionAuthorityEvidenceBundle.BundleReference reference() {
    return new StartSessionAuthorityEvidenceBundle.BundleReference(
        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
  }

  private static byte[] withField(byte[] source, String field, Object replacement)
      throws IOException {
    Map<String, Object> value =
        JSON.readValue(source, new TypeReference<LinkedHashMap<String, Object>>() {});
    value.put(field, replacement);
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static void assertInvalid(String value) {
    assertThatThrownBy(
            () ->
                WorldStartSessionExecutionTerminal.fromStored(
                    value.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertInvalid(byte[] value) {
    assertThatThrownBy(() -> WorldStartSessionExecutionTerminal.fromStored(value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertChanged(
      WorldStartSessionExecutionTerminal changed, byte[] baselineCanonical) {
    assertThat(changed.canonicalBytes()).isNotEqualTo(baselineCanonical);
    assertThat(changed.digest()).isNotEqualTo("sha256:" + digestBytes(baselineCanonical));
    assertThat(
            WorldStartSessionExecutionTerminal.fromStored(changed.canonicalBytes())
                .canonicalBytes())
        .containsExactly(changed.canonicalBytes());
  }

  private static String digest(String value) {
    return "sha256:" + digestBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String digestBytes(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
