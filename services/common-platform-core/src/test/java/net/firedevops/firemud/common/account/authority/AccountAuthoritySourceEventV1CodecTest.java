package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AccountAuthoritySourceEventV1CodecTest {
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String ACCOUNT_STREAM =
      AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX + "account/" + ACCOUNT_ID;

  @Test
  void issuerEventRoundTripsMaximumOwnerCountersAsCanonicalStrings() {
    var expected =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                "event-1",
                "request-1",
                AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX
                    + "issuer/firemud-account-service",
                Long.toString(Long.MAX_VALUE - 1),
                "firemud-account-service",
                Long.toString(Long.MAX_VALUE),
                Long.toString(Long.MAX_VALUE),
                "SIGNER_COMPROMISE"));

    assertThat(AccountAuthoritySourceEventV1Codec.verify(expected.canonicalJson()))
        .isEqualTo(expected);
    assertThat(expected.canonicalJson())
        .contains("\"issuerAuthGeneration\":\"9223372036854775807\"");
  }

  @Test
  void accountEventRoundTripsCompleteAuthorityStateAndSameEventCutoff() {
    var expected = accountEvent(Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE - 1));

    assertThat(AccountAuthoritySourceEventV1Codec.verify(expected.canonicalJson()))
        .isEqualTo(expected);
    assertThat(expected.canonicalJson())
        .contains("\"issuanceFenceSourceVersion\":\"9223372036854775807\"")
        .contains("\"accountSecurityCutoff\"");
  }

  @Test
  void rejectsUnknownOrDuplicatePropertiesEvenWhenOtherFieldsAreValid() {
    var event = accountEvent("9", "8");
    String unknown = event.canonicalJson().replaceFirst("}$", ",\"future\":true}");
    String duplicate =
        event.canonicalJson().replaceFirst("\\{", "{\"eventType\":\"ACCOUNT_AUTHORITY_CHANGED\",");

    assertThatThrownBy(() -> AccountAuthoritySourceEventV1Codec.verify(unknown))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountAuthoritySourceEventV1Codec.verify(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonObjectOrUnknownCutoffFieldsBeforeBuildingOwnerEvidence() {
    var event = accountEvent("9", "8");
    String cutoff =
        "\"accountSecurityCutoff\":{\"accountAuthorityGeneration\":\"9\","
            + "\"outboxSequence\":\"8\",\"outboxStreamKey\":\""
            + ACCOUNT_STREAM
            + "\"}";
    String nonObject =
        event.canonicalJson().replace(cutoff, "\"accountSecurityCutoff\":\"invalid\"");
    String unknownField =
        event
            .canonicalJson()
            .replace(
                cutoff,
                "\"accountSecurityCutoff\":{\"accountAuthorityGeneration\":\"9\","
                    + "\"future\":true,\"outboxSequence\":\"8\",\"outboxStreamKey\":\""
                    + ACCOUNT_STREAM
                    + "\"}");

    assertThat(event.canonicalJson()).contains(cutoff);
    assertThatThrownBy(() -> AccountAuthoritySourceEventV1Codec.verify(nonObject))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountAuthoritySourceEventV1Codec.verify(unknownField))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsZeroLeadingNegativeAndOutOfRangeCounters() {
    for (String invalid : List.of("0", "01", "-1", "9223372036854775808")) {
      assertThatThrownBy(() -> accountEvent(invalid, "1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must be a positive canonical decimal string in the owner range");
    }
  }

  @Test
  void rejectsMismatchBetweenAccountScopeAndCutoffOrUnsortedMutationKinds() {
    var wrongSequence =
        new AccountAuthoritySourceEventV1Codec.AccountPreimage(
            "event-1",
            "request-1",
            ACCOUNT_STREAM,
            "4",
            ACCOUNT_ID,
            "4",
            "4",
            "4",
            "4",
            List.of("PASSWORD_RESET"),
            state());
    var unsorted =
        new AccountAuthoritySourceEventV1Codec.AccountPreimage(
            "event-1",
            "request-1",
            ACCOUNT_STREAM,
            "4",
            ACCOUNT_ID,
            "4",
            "4",
            "4",
            "4",
            List.of("PASSWORD_RESET", "EMAIL_LOGIN_ELIGIBILITY_CHANGED"),
            state());

    assertThatThrownBy(() -> AccountAuthoritySourceEventV1Codec.sealAccount(wrongSequence))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountAuthoritySourceEventV1Codec.sealAccount(unsorted))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountAuthoritySourceEventV1Codec.AccountEvent accountEvent(
      String generation, String sequence) {
    return AccountAuthoritySourceEventV1Codec.sealAccount(
        new AccountAuthoritySourceEventV1Codec.AccountPreimage(
            "event-1",
            "request-1",
            ACCOUNT_STREAM,
            sequence,
            ACCOUNT_ID,
            generation,
            generation,
            generation,
            generation,
            List.of("EMAIL_LOGIN_ELIGIBILITY_CHANGED", "PASSWORD_RESET"),
            state()));
  }

  private static AccountAuthoritySourceEventV1Codec.AccountState state() {
    return new AccountAuthoritySourceEventV1Codec.AccountState(
        true, List.of("EMAIL_OTP", "PASSWORD"), "player", "ACTIVE");
  }
}
