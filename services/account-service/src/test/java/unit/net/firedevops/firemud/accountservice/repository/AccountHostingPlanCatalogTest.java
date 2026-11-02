package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.firedevops.firemud.accountservice.repository.AccountHostingPlanCatalog.ConfiguredPlan;
import org.junit.jupiter.api.Test;

class AccountHostingPlanCatalogTest {
  @Test
  void onlyExplicitConfigurationResolvesAndTheConfigurationAndQuoteBytesCannotBeMutated() {
    var entry = configuration("basic-hosting", "price_explicit");
    var source = new ArrayList<>(List.of(entry));
    var catalog = new AccountHostingPlanCatalog(source);
    source.clear();
    var quote = catalog.resolve("basic-hosting");
    assertThat(quote)
        .isEqualTo(
            new AccountHostingPlanQuote("basic-hosting", "price_explicit", 3, 100, 2, 1048576));
    assertThat(quote.digest()).startsWith("sha256:").hasSize(71);
    byte[] original = quote.payload();
    byte[] escaped = quote.payload();
    escaped[0] ^= 1;
    assertThat(quote.payload()).containsExactly(original);
    assertThat(quote.payload().length).isLessThanOrEqualTo(4096);
    quote.requireRetained(original, quote.digest());
    assertThatThrownBy(() -> quote.requireRetained(escaped, quote.digest()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> quote.requireRetained(original, "sha256:" + "f".repeat(64)))
        .isInstanceOf(IllegalStateException.class);
    var alteredFields =
        new AccountHostingPlanQuote("basic-hosting", "price_other", 3, 100, 2, 1048576);
    assertThatThrownBy(() -> alteredFields.requireRetained(original, quote.digest()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void noDefaultPlanOrAmbiguousDuplicateCanBecomeAQuote() {
    var empty = new AccountHostingPlanCatalog(List.of());
    assertThatThrownBy(() -> empty.resolve("basic-hosting"))
        .isInstanceOf(AccountHostingPlanCatalog.PlanUnavailableException.class);
    assertThatThrownBy(() -> new AccountHostingPlanCatalog(null))
        .isInstanceOf(IllegalArgumentException.class);
    var entry = configuration("basic-hosting", "price_explicit");
    assertThatThrownBy(() -> new AccountHostingPlanCatalog(List.of(entry, entry)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Ambiguous");
    assertThatThrownBy(
            () ->
                new AccountHostingPlanCatalog(
                    List.of(entry, configuration("basic-hosting", "price_other"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccountHostingPlanCatalog(Arrays.asList(entry, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void integralConfigurationRejectsIncompleteFractionalNegativeAndOverflowValuesWithoutFallback() {
    for (String malformed :
        Arrays.asList(null, "", "-1", "+1", "01", "1.5", "1e3", " 1", "9223372036854775808")) {
      for (int position = 0; position < 4; position++) {
        String[] fields = {"1", "100", "2", "1048576"};
        fields[position] = malformed;
        var invalid =
            new ConfiguredPlan(
                "basic-hosting", "price_explicit", fields[0], fields[1], fields[2], fields[3]);
        assertThatThrownBy(() -> new AccountHostingPlanCatalog(List.of(invalid)))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
    assertThatThrownBy(
            () ->
                new AccountHostingPlanCatalog(
                    List.of(
                        new ConfiguredPlan(
                            "basic-hosting", "price_explicit", "0", "100", "2", "1048576"))))
        .isInstanceOf(IllegalArgumentException.class);
    var maximum =
        new AccountHostingPlanCatalog(
                List.of(
                    new ConfiguredPlan(
                        "exact",
                        "price_max",
                        Long.toString(Long.MAX_VALUE),
                        "0",
                        "0",
                        Long.toString(Long.MAX_VALUE))))
            .resolve("exact");
    assertThat(maximum.providerQuantity()).isEqualTo(Long.MAX_VALUE);
    assertThat(maximum.maxActiveSessions())
        .isZero(); // Explicit zero is a bound, never an unlimited default.
    assertThat(new String(maximum.payload(), StandardCharsets.UTF_8))
        .contains(
            "\"providerQuantity\":\"9223372036854775807\"",
            "\"maxStorageBytes\":\"9223372036854775807\"");
  }

  @Test
  void planCodesRetainExactUnicodeAndNeverIntroduceUuidOrNormalizationFallbacks() {
    String composed = "hébergement";
    String decomposed = "he\u0301bergement";
    var catalog =
        new AccountHostingPlanCatalog(
            List.of(configuration(composed, "price_one"), configuration(decomposed, "price_two")));
    assertThat(catalog.resolve(composed).planCode()).isEqualTo(composed);
    assertThat(catalog.resolve(decomposed).planCode()).isEqualTo(decomposed);
    assertThat(catalog.resolve(composed).digest())
        .isNotEqualTo(catalog.resolve(decomposed).digest());
    for (String bad : Arrays.asList(null, "", " basic", "basic ", "\uD800", "a".repeat(256))) {
      assertThatThrownBy(
              () -> new AccountHostingPlanCatalog(List.of(configuration(bad, "price_one"))))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String price : Arrays.asList(null, "", "prod_notAPrice", "price_contains-dash")) {
      assertThatThrownBy(
              () -> new AccountHostingPlanCatalog(List.of(configuration("basic-hosting", price))))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static ConfiguredPlan configuration(String code, String price) {
    return new ConfiguredPlan(code, price, "3", "100", "2", "1048576");
  }
}
