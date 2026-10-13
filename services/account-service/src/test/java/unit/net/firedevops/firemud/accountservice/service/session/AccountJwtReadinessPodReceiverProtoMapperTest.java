package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeRequest;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerSelector.LocalIdentity;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;
import org.junit.jupiter.api.Test;

class AccountJwtReadinessPodReceiverProtoMapperTest {
  private final AccountJwtReadinessPodReceiverProtoMapper mapper =
      new AccountJwtReadinessPodReceiverProtoMapper();

  @Test
  void acceptsOnlyTheCanaryAndThreeAccountProductionProfilesAsApplicable() {
    Map<String, String> profiles =
        Map.of(
            "account-jwt-readiness-canary",
            "firemud-account-jwt-readiness",
            ControlUiJwtProfileValidator.PROFILE,
            ControlUiJwtProfileValidator.AUDIENCE,
            PlayerBootstrapJwtProfileValidator.PROFILE,
            PlayerBootstrapJwtProfileValidator.AUDIENCE,
            GameSessionAccountDelegationProfile.PROFILE,
            GameSessionAccountDelegationProfile.AUDIENCE);

    for (Map.Entry<String, String> profile : profiles.entrySet()) {
      var parsed = mapper.parse(request(profile.getKey(), profile.getValue()).build());
      assertThat(parsed.coordinates().getExpectedOutcome())
          .isEqualTo(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT);
      assertThat(parsed.compactTokenSha256()).matches("[0-9a-f]{64}");
      assertThat(parsed.probeKind())
          .isEqualTo(
              "account-jwt-readiness-canary".equals(profile.getKey())
                  ? AccountMountedJwtSignerBundle.ProbeKind.CANARY
                  : AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE);
    }
  }

  @Test
  void rejectsUnknownFieldsUnsupportedProfilesAndTypedInapplicableSuccess() {
    var unknownRequest =
        request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
            .setUnknownFields(unknownFields())
            .build();
    assertInvalid(unknownRequest);

    var unknownCoordinates =
        request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
            .setCoordinates(
                request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
                    .getCoordinates()
                    .toBuilder()
                    .setUnknownFields(unknownFields()))
            .build();
    assertInvalid(unknownCoordinates);

    assertInvalid(
        request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
            .setCoordinates(
                request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
                    .getCoordinates()
                    .toBuilder()
                    .setExpectedOutcome(
                        ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT))
            .build());
    assertInvalid(
        request("account-login", "account-service")
            .setCoordinates(
                request("account-login", "account-service").getCoordinates().toBuilder()
                    .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT))
            .build());
  }

  @Test
  void rejectsMalformedCoordinatesAndUnboundedOrNonAsciiCompactTokens() {
    assertInvalid(
        request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
            .setCoordinates(
                request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
                    .getCoordinates()
                    .toBuilder()
                    .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.UNKNOWN))
            .build());
    assertInvalid(
        request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
            .setCompactJwt("x".repeat(16 * 1024 + 1))
            .build());
    assertInvalid(
        request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
            .setCompactJwt("header.payload.é")
            .build());
  }

  @Test
  void derivesSelectorOnlyFromValidatedCoordinatesAndProtectedLocalIdentity() {
    var parsed =
        mapper.parse(
            request(ControlUiJwtProfileValidator.PROFILE, ControlUiJwtProfileValidator.AUDIENCE)
                .build());
    LocalIdentity identity =
        new LocalIdentity(
            "account-service",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "10.0.0.12",
            "https://10.0.0.12:6565",
            "spiffe://firemud/ns/firemud-prod/sa/account-service",
            "registry.example/account@sha256:" + "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "inventory-r1",
            "d".repeat(64),
            "e".repeat(64));

    var selector = mapper.toOwnerSelector(parsed, identity);

    assertThat(selector.validatorId()).isEqualTo("account-service");
    assertThat(selector.expectedOutcome())
        .isEqualTo(AccountJwtReadinessReceiverInvocationPort.ProbeExpectation.ACCEPT);
    assertThat(selector.localIdentity()).isEqualTo(identity);
  }

  private static ReceiveAccountJwtReadinessProbeRequest.Builder request(
      String profile, String audience) {
    boolean canary = "account-jwt-readiness-canary".equals(profile);
    ReadinessProbeCoordinates coordinates =
        ReadinessProbeCoordinates.newBuilder()
            .setRotationOperationId(UUID.randomUUID().toString())
            .setOperationDigest("a".repeat(64))
            .setPlanDigest("b".repeat(64))
            .setPlanVersion(2)
            .setRegistryVersion(1)
            .setValidatorId("account-service")
            .setTokenProfile(profile)
            .setAudience(audience)
            .setProbeKind(
                canary
                    ? ReadinessProbeCoordinates.ProbeKind.CANARY
                    : ReadinessProbeCoordinates.ProbeKind.REPRESENTATIVE)
            .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
            .setJti(UUID.randomUUID().toString())
            .setEntryVersion(4)
            .setTargetGeneration("7")
            .setTargetKid("pending-key-7")
            .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.ABSENT)
            .setIssuedAtEpochSeconds(1_900_000_000L)
            .setExpiresAtEpochSeconds(1_900_000_120L)
            .setPlanExpiresAtEpochSeconds(1_900_000_180L)
            .build();
    return ReceiveAccountJwtReadinessProbeRequest.newBuilder()
        .setSchemaVersion(1)
        .setCoordinates(coordinates)
        .setCompactJwt("header.payload.signature");
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private void assertInvalid(ReceiveAccountJwtReadinessProbeRequest request) {
    assertThatThrownBy(() -> mapper.parse(request))
        .isInstanceOf(
            AccountJwtReadinessPodReceiverProtoMapper.InvalidReceiverRequestException.class);
  }
}
