package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest;
import org.junit.jupiter.api.Test;

class GameSessionJwtReadinessReceiverProtoMapperTest {
  private final GameSessionJwtReadinessReceiverProtoMapper mapper =
      new GameSessionJwtReadinessReceiverProtoMapper();

  @Test
  void acceptsOnlyTheCanaryAndProductionProfilesWithTypedExpectedOutcomes() {
    Map<String, ReadinessProbeCoordinates.ExpectedOutcome> outcomes =
        Map.of(
            GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE,
            ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT,
            "game-session-account-delegation",
            ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT,
            ControlUiJwtProfileValidator.PROFILE,
            ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT,
            PlayerBootstrapJwtProfileValidator.PROFILE,
            ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT);

    outcomes.forEach(
        (profile, expectedOutcome) -> {
          var parsed = mapper.parse(request(profile, expectedOutcome).build());
          assertThat(parsed.coordinates().getExpectedOutcome()).isEqualTo(expectedOutcome);
          assertThat(parsed.expectedOutcome()).isEqualTo(expectedOutcome);
          assertThat(parsed.compactTokenSha256()).matches("[0-9a-f]{64}");
        });
  }

  @Test
  void rejectsUnknownFieldsUnsupportedProfilesAndOutcomeMismatch() {
    assertInvalid(
        request(
                GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE,
                ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
            .setUnknownFields(unknownFields())
            .build());
    assertInvalid(
        request(
                ControlUiJwtProfileValidator.PROFILE,
                ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT)
            .setCoordinates(
                request(
                        ControlUiJwtProfileValidator.PROFILE,
                        ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT)
                    .getCoordinates()
                    .toBuilder()
                    .setUnknownFields(unknownFields()))
            .build());
    assertInvalid(
        request(
                ControlUiJwtProfileValidator.PROFILE,
                ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
            .build());
    assertInvalid(
        request("account-login", ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT).build());
  }

  @Test
  void rejectsMalformedCoordinatesAndUnboundedOrNonAsciiTokens() {
    assertInvalid(
        request(
                GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE,
                ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
            .setCoordinates(
                request(
                        GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE,
                        ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
                    .getCoordinates()
                    .toBuilder()
                    .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.UNKNOWN))
            .build());
    assertInvalid(
        request(
                GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE,
                ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
            .setCompactJwt(
                "x".repeat(GameSessionJwtReadinessReceiverProtoMapper.MAX_COMPACT_JWT_BYTES + 1))
            .build());
    assertInvalid(
        request(
                GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE,
                ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
            .setCompactJwt("header.payload.é")
            .build());
  }

  private static void assertInvalid(ReceiveReadinessProbeRequest request) {
    assertThatThrownBy(() -> new GameSessionJwtReadinessReceiverProtoMapper().parse(request))
        .isInstanceOf(
            GameSessionJwtReadinessReceiverProtoMapper.InvalidReceiverRequestException.class);
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static ReceiveReadinessProbeRequest.Builder request(
      String profile, ReadinessProbeCoordinates.ExpectedOutcome expectedOutcome) {
    boolean canary = GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE.equals(profile);
    String audience =
        switch (profile) {
          case GameSessionJwtReadinessReceiverProtoMapper.CANARY_PROFILE ->
              GameSessionJwtReadinessReceiverProtoMapper.CANARY_AUDIENCE;
          case ControlUiJwtProfileValidator.PROFILE -> ControlUiJwtProfileValidator.AUDIENCE;
          case PlayerBootstrapJwtProfileValidator.PROFILE ->
              PlayerBootstrapJwtProfileValidator.AUDIENCE;
          case "game-session-account-delegation" -> "account-service";
          default -> "account-login";
        };
    ReadinessProbeCoordinates coordinates =
        ReadinessProbeCoordinates.newBuilder()
            .setRotationOperationId(UUID.randomUUID().toString())
            .setOperationDigest("a".repeat(64))
            .setPlanDigest("b".repeat(64))
            .setPlanVersion(GameSessionJwtReadinessReceiverProtoMapper.CURRENT_PLAN_VERSION)
            .setRegistryVersion(GameSessionJwtReadinessReceiverProtoMapper.CURRENT_REGISTRY_VERSION)
            .setValidatorId(GameSessionJwtReadinessReceiverProtoMapper.GAME_SESSION_VALIDATOR)
            .setTokenProfile(profile)
            .setAudience(audience)
            .setProbeKind(
                canary
                    ? ReadinessProbeCoordinates.ProbeKind.CANARY
                    : ReadinessProbeCoordinates.ProbeKind.REPRESENTATIVE)
            .setExpectedOutcome(expectedOutcome)
            .setJti(UUID.randomUUID().toString())
            .setEntryVersion(1)
            .setTargetGeneration("7")
            .setTargetKid("pending-key-7")
            .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.ABSENT)
            .setIssuedAtEpochSeconds(1_900_000_000L)
            .setExpiresAtEpochSeconds(1_900_000_120L)
            .setPlanExpiresAtEpochSeconds(1_900_000_180L)
            .build();
    return ReceiveReadinessProbeRequest.newBuilder()
        .setSchemaVersion(GameSessionJwtReadinessReceiverProtoMapper.SCHEMA_VERSION)
        .setCoordinates(coordinates)
        .setCompactJwt("header.payload.signature");
  }
}
