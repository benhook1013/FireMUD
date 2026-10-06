package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequestDigest;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation;
import org.junit.jupiter.api.Test;

class AccountControlUiIssuanceOperationTest {
  private static final String REQUEST = "11111111-1111-4111-8111-111111111111";
  private static final String OTHER_REQUEST = "22222222-2222-4222-8222-222222222222";
  private static final String ACCOUNT = "33333333-3333-4333-8333-333333333333";
  private static final String OTHER_ACCOUNT = "44444444-4444-4444-8444-444444444444";

  @Test
  void semanticDigestHasClosedCredentialFreeVersionedTargetlessFrame() throws Exception {
    var request = new AccountControlUiIssuanceRequest(REQUEST, ACCOUNT);
    ByteBuffer frames =
        ByteBuffer.wrap(AccountControlUiIssuanceRequestDigest.canonicalBytes(request));
    assertThat(readFrame(frames)).isEqualTo("firemud/account/control-ui-issuance/request/v1");
    assertThat(readFrame(frames)).isEqualTo("1");
    assertThat(readFrame(frames)).isEqualTo("LOGIN_ISSUANCE");
    assertThat(readFrame(frames)).isEqualTo(ACCOUNT);
    assertThat(readFrame(frames)).isEqualTo("control-ui");
    assertThat(readFrame(frames)).isEqualTo("control-ui");
    assertThat(frames.hasRemaining()).isFalse();
    assertThat(AccountControlUiIssuanceRequest.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("requestId", "accountUuid");
    assertThat(AccountControlUiIssuanceRequestDigest.digest(request))
        .containsExactly(
            MessageDigest.getInstance("SHA-256")
                .digest(AccountControlUiIssuanceRequestDigest.canonicalBytes(request)));
    assertThat(
            AccountControlUiIssuanceRequestDigest.digest(
                new AccountControlUiIssuanceRequest(OTHER_REQUEST, ACCOUNT)))
        .containsExactly(AccountControlUiIssuanceRequestDigest.digest(request));
    assertThat(
            AccountControlUiIssuanceRequestDigest.digest(
                new AccountControlUiIssuanceRequest(REQUEST, OTHER_ACCOUNT)))
        .isNotEqualTo(AccountControlUiIssuanceRequestDigest.digest(request));
  }

  @Test
  void requestRequiresCanonicalNonNilUuidStrings() {
    for (String invalid :
        List.of(
            "00000000-0000-0000-0000-000000000000",
            "11111111-1111-4111-8111-11111111111",
            "11111111-1111-4111-8111-111111111111 ",
            "11111111-1111-4111-8111-11111111111A")) {
      assertThatThrownBy(() -> new AccountControlUiIssuanceRequest(invalid, ACCOUNT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new AccountControlUiIssuanceRequest(REQUEST, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void originalCaptureCopiesAndRedactsCompleteEvidenceBytes() {
    byte[] authority = "complete authority capture bytes".getBytes(StandardCharsets.UTF_8);
    byte[] fence = "complete issuance fence bytes".getBytes(StandardCharsets.UTF_8);
    var capture =
        new AccountControlUiIssuanceOperation.OriginalCapture(
            73L, AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT, authority, fence);
    byte[] expectedAuthorityDigest = capture.authorityCaptureDigest();
    byte[] expectedFenceDigest = capture.issuanceFenceDigest();
    authority[0] = 0;
    fence[0] = 0;
    byte[] returnedAuthority = capture.authorityCapture();
    byte[] returnedFence = capture.issuanceFenceCapture();
    returnedAuthority[0] = 0;
    returnedFence[0] = 0;

    assertThat(capture.authorityCapture())
        .containsExactly("complete authority capture bytes".getBytes(StandardCharsets.UTF_8));
    assertThat(capture.issuanceFenceCapture())
        .containsExactly("complete issuance fence bytes".getBytes(StandardCharsets.UTF_8));
    assertThat(capture.authorityCaptureDigest()).containsExactly(expectedAuthorityDigest);
    assertThat(capture.issuanceFenceDigest()).containsExactly(expectedFenceDigest);
    assertThat(capture.toString()).doesNotContain("complete authority", "complete issuance");
    assertThat(capture)
        .isEqualTo(
            new AccountControlUiIssuanceOperation.OriginalCapture(
                73L,
                AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT,
                "complete authority capture bytes".getBytes(StandardCharsets.UTF_8),
                "complete issuance fence bytes".getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(
            () ->
                new AccountControlUiIssuanceOperation.OriginalCapture(
                    73L, AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT, new byte[0], fence))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String readFrame(ByteBuffer bytes) {
    byte[] value = new byte[bytes.getInt()];
    bytes.get(value);
    return new String(value, StandardCharsets.UTF_8);
  }
}
