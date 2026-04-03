package unit.net.firedevops.firemud.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.i18n.LocalizedTextVariants;
import org.junit.jupiter.api.Test;

class LocalizedTextVariantsTest {

  @Test
  void resolveReturnsExactStoredVariantWhenPresent() {
    LocalizedTextVariants variants =
        LocalizedTextVariants.source(
                "en-NZ", "A narrow stone corridor runs along the cliff face.")
            .withVariant("fr-FR", "Un étroit couloir de pierre longe la falaise.");

    LocalizedTextVariants.ResolvedLocalizedText resolved = variants.resolve("fr-FR");

    assertThat(resolved.localeTag()).isEqualTo("fr-FR");
    assertThat(resolved.text()).isEqualTo("Un étroit couloir de pierre longe la falaise.");
    assertThat(resolved.sourceFallbackUsed()).isFalse();
  }

  @Test
  void resolveFallsBackByLanguageBeforeUsingSourceText() {
    LocalizedTextVariants variants =
        LocalizedTextVariants.source("en-NZ", "The forge glows red in the mountain dusk.")
            .withVariant("fr", "La forge luit d'un rouge vif dans le crépuscule de la montagne.");

    LocalizedTextVariants.ResolvedLocalizedText resolved = variants.resolve("fr-CA");

    assertThat(resolved.localeTag()).isEqualTo("fr");
    assertThat(resolved.text())
        .isEqualTo("La forge luit d'un rouge vif dans le crépuscule de la montagne.");
    assertThat(resolved.sourceFallbackUsed()).isFalse();
  }

  @Test
  void resolveFallsBackToCanonicalSourceWhenNoVariantExists() {
    LocalizedTextVariants variants =
        LocalizedTextVariants.source(
                "en-NZ", "A narrow stone corridor runs along the cliff face.")
            .withVariant("fr-FR", "Un étroit couloir de pierre longe la falaise.");

    LocalizedTextVariants.ResolvedLocalizedText resolved = variants.resolve("de-DE");

    assertThat(resolved.localeTag()).isEqualTo("en-NZ");
    assertThat(resolved.text()).isEqualTo("A narrow stone corridor runs along the cliff face.");
    assertThat(resolved.sourceFallbackUsed()).isTrue();
  }
}
