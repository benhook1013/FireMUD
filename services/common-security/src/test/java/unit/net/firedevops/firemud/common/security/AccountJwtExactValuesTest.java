package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountJwtExactValuesTest {
  @Test
  void adjacentLargeCountersDifferThroughoutNestedMapsAndLists() {
    BigInteger first = new BigInteger("9007199254740992");
    BigInteger next = first.add(BigInteger.ONE);
    Map<String, Object> left = Map.of("membershipVersion", Map.of("tenant", List.of(first)));
    Map<String, Object> right = Map.of("membershipVersion", Map.of("tenant", List.of(next)));
    assertThat(AccountJwtExactValues.sameJsonValue(left, right)).isFalse();
    assertThat(
            AccountJwtExactValues.sameJsonValue(
                left,
                Map.of("membershipVersion", Map.of("tenant", List.of(first.longValueExact())))))
        .isTrue();
    assertThat(
            AccountJwtExactValues.sameJsonValue(
                Map.of("identity", "1"), Map.of("identity", BigInteger.ONE)))
        .isFalse();
    assertThat(
            AccountJwtExactValues.sameJsonValue(
                List.of("account", "tenant"), List.of("tenant", "account")))
        .isFalse();
    assertThat(
            AccountJwtExactValues.sameJsonValue(Map.of("account", first), Map.of("tenant", first)))
        .isFalse();
  }

  @Test
  void oversizedPositiveCountersAndVersionMapsRetainTheirExactValues() {
    BigInteger value = BigInteger.TEN.pow(100).add(BigInteger.ONE);
    assertThat(AccountJwtExactValues.positiveCounter(value)).isEqualTo(value);
    assertThat(AccountJwtExactValues.positiveDecimalCounter(value.toString())).isEqualTo(value);
    assertThat(
            AccountJwtProfileClaimSupport.requireGenerationMap(
                    Map.of("11111111-1111-4111-8111-111111111111", value.toString()))
                .values())
        .containsExactly(value);
    assertThat(
            AccountJwtProfileClaimSupport.requireVersionMap(
                    Map.of("11111111-1111-4111-8111-111111111111", value.toString()))
                .values())
        .containsExactly(value);
  }

  @Test
  void zeroNegativeFractionalAndMalformedCountersAreRejectedWithoutStringAliases() {
    for (Object value :
        List.of(
            BigInteger.ZERO,
            BigInteger.valueOf(-1L),
            new BigDecimal("1.000000000000000000001"),
            "1",
            "bad",
            1.0d)) {
      assertThatThrownBy(() -> AccountJwtExactValues.positiveCounter(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Object value : List.of("0", "-1", "+1", "01", "1.0", "1e3", " 1", "1 ", "", "bad", 1L)) {
      assertThatThrownBy(() -> AccountJwtExactValues.positiveDecimalCounter(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void membershipVersionsPermitOnlyCanonicalZeroOrPositiveDecimalStrings() {
    assertThat(AccountJwtExactValues.nonNegativeDecimalCounter("0")).isZero();
    assertThat(
            AccountJwtProfileClaimSupport.requireVersionMap(
                    Map.of("11111111-1111-4111-8111-111111111111", "0"))
                .values())
        .containsExactly(BigInteger.ZERO);
    for (Object value : List.of("-0", "+0", "00", "-1", "01", "0.0", "0e0", " 0", "0 ", "", 0L)) {
      assertThatThrownBy(() -> AccountJwtExactValues.nonNegativeDecimalCounter(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void decimalStringGoldenVectorsSurviveCanonicalizationExactlyWithoutNumericAliases() {
    for (String value :
        List.of(
            "9007199254740992", "9007199254740993", "9223372036854775808", "9223372036854775809")) {
      assertThat(AccountJwtExactValues.positiveDecimalCounter(value))
          .isEqualTo(new BigInteger(value));
      assertThat(
              new String(
                  AccountJwtExactValues.losslessCanonicalBytes(Map.of("tokenGeneration", value)),
                  StandardCharsets.UTF_8))
          .isEqualTo("{\"tokenGeneration\":\"" + value + "\"}");
    }
    assertThat(
            AccountJwtExactValues.sameJsonValue(
                Map.of("membershipVersion", Map.of("tenant", "9007199254740992")),
                Map.of("membershipVersion", Map.of("tenant", "9007199254740993"))))
        .isFalse();
    assertThat(AccountJwtExactValues.sameJsonValue("1", BigInteger.ONE)).isFalse();
  }

  @Test
  void canonicalizationCannotRoundANumericCandidateAndAnExactLargeValueRemainsNumeric() {
    assertThatThrownBy(
            () ->
                AccountJwtExactValues.losslessCanonicalBytes(
                    Map.of("counter", new BigInteger("9007199254740993"))))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] bytes =
        AccountJwtExactValues.losslessCanonicalBytes(Map.of("counter", BigInteger.TEN.pow(30)));
    assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("{\"counter\":1e+30}");
  }
}
