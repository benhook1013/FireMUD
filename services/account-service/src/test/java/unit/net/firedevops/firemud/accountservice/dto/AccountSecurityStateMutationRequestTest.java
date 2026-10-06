package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountSecurityStateMutationRequest;
import net.firedevops.firemud.accountservice.dto.AccountSecurityStateRequestDigest;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountState;
import org.junit.jupiter.api.Test;

class AccountSecurityStateMutationRequestTest {
  private static final UUID REQUEST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final List<String> KINDS = List.of("EMAIL_LOGIN_ELIGIBILITY_CHANGED");

  @Test
  void exactRequestFramesRetainCallerAndStateBytesAndFixedDigest() {
    var request =
        request(
            REQUEST, ACCOUNT, caller(ACCOUNT, REQUEST, "a".repeat(64)), 1L, 1L, KINDS, state(true));
    ByteBuffer bytes = ByteBuffer.wrap(AccountSecurityStateRequestDigest.requestBytes(request));
    assertThat(readFrame(bytes)).isEqualTo("firemud/account/security-state/request/v1");
    assertThat(readFrame(bytes)).isEqualTo(REQUEST.toString());
    assertThat(readFrame(bytes)).isEqualTo(ACCOUNT.toString());
    assertThat(readFrame(bytes))
        .isEqualTo(new String(request.callerProofBinding(), StandardCharsets.UTF_8));
    assertThat(readFrame(bytes)).isEqualTo("1");
    assertThat(readFrame(bytes)).isEqualTo("1");
    assertThat(readFrame(bytes)).isEqualTo("1");
    assertThat(readFrame(bytes)).isEqualTo("EMAIL_LOGIN_ELIGIBILITY_CHANGED");
    assertThat(readFrame(bytes))
        .isEqualTo(
            "{\"emailVerified\":true,\"globalRoles\":[],\"lifecycleState\":\"ACTIVE\",\"loginAuthModes\":[\"EMAIL_OTP\",\"PASSWORD\"]}");
    assertThat(bytes.hasRemaining()).isFalse();
    assertThat(AccountSecurityStateRequestDigest.digest(request))
        .isEqualTo("604a354594c3a0b5f882224d44ad40bc8a5ce67c2afa704e6f470f79d0e8ee30");
  }

  @Test
  void everyOriginalSemanticBindingChangesDigestIncludingCallerTargetStateAndKinds() {
    var original =
        request(
            REQUEST, ACCOUNT, caller(ACCOUNT, REQUEST, "a".repeat(64)), 1L, 1L, KINDS, state(true));
    UUID other = UUID.fromString("33333333-3333-4333-8333-333333333333");
    for (var changed :
        List.of(
            request(other, ACCOUNT, original.callerProofBinding(), 1L, 1L, KINDS, state(true)),
            request(REQUEST, other, original.callerProofBinding(), 1L, 1L, KINDS, state(true)),
            request(
                REQUEST,
                ACCOUNT,
                caller(other, REQUEST, "a".repeat(64)),
                1L,
                1L,
                KINDS,
                state(true)),
            request(
                REQUEST,
                ACCOUNT,
                caller(ACCOUNT, other, "a".repeat(64)),
                1L,
                1L,
                KINDS,
                state(true)),
            request(
                REQUEST,
                ACCOUNT,
                caller(ACCOUNT, REQUEST, "b".repeat(64)),
                1L,
                1L,
                KINDS,
                state(true)),
            request(REQUEST, ACCOUNT, original.callerProofBinding(), 2L, 1L, KINDS, state(true)),
            request(REQUEST, ACCOUNT, original.callerProofBinding(), 1L, 2L, KINDS, state(true)),
            request(REQUEST, ACCOUNT, original.callerProofBinding(), 1L, 1L, KINDS, state(false)),
            request(
                REQUEST,
                ACCOUNT,
                original.callerProofBinding(),
                1L,
                1L,
                List.of("LOGIN_AUTH_MODES_CHANGED"),
                state(true)))) {
      assertThat(AccountSecurityStateRequestDigest.digest(changed))
          .isNotEqualTo(AccountSecurityStateRequestDigest.digest(original));
    }
  }

  @Test
  void closedCorrelationRejectsAssertionsCredentialsNullsAndUnknownFields() {
    byte[] original = caller(ACCOUNT, REQUEST, "a".repeat(64));
    for (String extra : List.of("password", "token", "otp", "authenticated", "globalRoles")) {
      String value = new String(original, StandardCharsets.UTF_8);
      byte[] changed =
          (value.substring(0, value.length() - 1) + ",\"" + extra + "\":\"asserted\"}")
              .getBytes(StandardCharsets.UTF_8);
      assertThatThrownBy(() -> request(REQUEST, ACCOUNT, changed, 1L, 1L, KINDS, state(true)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                request(
                    REQUEST,
                    ACCOUNT,
                    "{}".getBytes(StandardCharsets.UTF_8),
                    1L,
                    1L,
                    KINDS,
                    state(true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(REQUEST, ACCOUNT, original, 0L, 1L, KINDS, state(true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                request(REQUEST, ACCOUNT, original, 1L, 1L, List.of("PASSWORD_RESET"), state(true)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestCopiesInputsAndStateHasOnlySharedCodecPolicyVocabulary() {
    byte[] bytes = caller(ACCOUNT, REQUEST, "a".repeat(64));
    var request = request(REQUEST, ACCOUNT, bytes, 1L, 1L, KINDS, state(true));
    byte[] retained = request.callerProofBinding();
    bytes[0] = 0;
    retained[0] = 0;
    assertThat(request.callerProofBinding()[0]).isEqualTo((byte) '{');
    assertThatThrownBy(() -> request.mutationKinds().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                request(
                    REQUEST,
                    ACCOUNT,
                    request.callerProofBinding(),
                    1L,
                    1L,
                    KINDS,
                    new AccountState(true, List.of("PASSWORD"), List.of("admin"), "ACTIVE")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            AccountSecurityStateMutationRequest.parseCanonicalState(
                request.canonicalDesiredState()))
        .isEqualTo(request.desiredState());
  }

  private static AccountSecurityStateMutationRequest request(
      UUID request,
      UUID account,
      byte[] caller,
      long generation,
      long version,
      List<String> kinds,
      AccountState state) {
    return new AccountSecurityStateMutationRequest(
        request, account, caller, generation, version, kinds, state);
  }

  private static AccountState state(boolean verified) {
    return new AccountState(verified, List.of("EMAIL_OTP", "PASSWORD"), List.of(), "ACTIVE");
  }

  private static byte[] caller(UUID actor, UUID operation, String digest) {
    return ("{\"actorAccountUuid\":\""
            + actor
            + "\",\"ownerEvidenceDigest\":\"sha256:"
            + digest
            + "\",\"ownerOperationId\":\""
            + operation
            + "\",\"schemaVersion\":\"account-security-state-caller-correlation/v1\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String readFrame(ByteBuffer bytes) {
    byte[] field = new byte[bytes.getInt()];
    bytes.get(field);
    return new String(field, StandardCharsets.UTF_8);
  }
}
