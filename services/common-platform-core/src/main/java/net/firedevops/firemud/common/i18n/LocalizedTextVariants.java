package net.firedevops.firemud.common.i18n;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical source text plus optional stored locale-specific variants.
 *
 * <p>This is intentionally a small content-model helper rather than a runtime translation service.
 */
public record LocalizedTextVariants(
    String sourceLocaleTag, String sourceText, Map<String, String> localizedVariants) {

  public LocalizedTextVariants {
    sourceLocaleTag = normalizeLocaleTag(sourceLocaleTag);
    sourceText = requireNonBlank(sourceText, "sourceText");
    localizedVariants = normalizeVariants(localizedVariants);
  }

  public static LocalizedTextVariants source(String sourceLocaleTag, String sourceText) {
    return new LocalizedTextVariants(sourceLocaleTag, sourceText, Map.of());
  }

  public LocalizedTextVariants withVariant(String localeTag, String text) {
    String normalizedLocaleTag = normalizeLocaleTag(localeTag);
    String normalizedText = requireNonBlank(text, "text");
    if (normalizedLocaleTag.equals(sourceLocaleTag)) {
      return new LocalizedTextVariants(sourceLocaleTag, normalizedText, localizedVariants);
    }
    Map<String, String> updated = new LinkedHashMap<>(localizedVariants);
    updated.put(normalizedLocaleTag, normalizedText);
    return new LocalizedTextVariants(sourceLocaleTag, sourceText, updated);
  }

  public ResolvedLocalizedText resolve(String preferredLocaleTag) {
    String normalizedPreferred = normalizeLocaleTag(preferredLocaleTag);
    if (normalizedPreferred.isBlank()) {
      return new ResolvedLocalizedText(sourceLocaleTag, sourceText, true);
    }

    String exactVariant = localizedVariants.get(normalizedPreferred);
    if (exactVariant != null) {
      return new ResolvedLocalizedText(normalizedPreferred, exactVariant, false);
    }
    if (sourceLocaleTag.equals(normalizedPreferred)) {
      return new ResolvedLocalizedText(sourceLocaleTag, sourceText, true);
    }

    String preferredLanguage = localeLanguage(normalizedPreferred);
    if (!preferredLanguage.isBlank()) {
      for (Map.Entry<String, String> entry : localizedVariants.entrySet()) {
        if (preferredLanguage.equals(localeLanguage(entry.getKey()))) {
          return new ResolvedLocalizedText(entry.getKey(), entry.getValue(), false);
        }
      }
      if (preferredLanguage.equals(localeLanguage(sourceLocaleTag))) {
        return new ResolvedLocalizedText(sourceLocaleTag, sourceText, true);
      }
    }
    return new ResolvedLocalizedText(sourceLocaleTag, sourceText, true);
  }

  public record ResolvedLocalizedText(String localeTag, String text, boolean sourceFallbackUsed) {
    public ResolvedLocalizedText {
      localeTag = normalizeLocaleTag(localeTag);
      text = requireNonBlank(text, "text");
    }
  }

  private static Map<String, String> normalizeVariants(Map<String, String> variants) {
    if (variants == null || variants.isEmpty()) {
      return Map.of();
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : variants.entrySet()) {
      String localeTag = normalizeLocaleTag(entry.getKey());
      String text = entry.getValue();
      if (localeTag.isBlank() || text == null || text.isBlank()) {
        continue;
      }
      normalized.put(localeTag, text);
    }
    return Map.copyOf(normalized);
  }

  private static String requireNonBlank(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  private static String normalizeLocaleTag(String localeTag) {
    if (localeTag == null || localeTag.isBlank()) {
      return "";
    }
    return Locale.forLanguageTag(localeTag).toLanguageTag();
  }

  private static String localeLanguage(String localeTag) {
    if (localeTag == null || localeTag.isBlank()) {
      return "";
    }
    return Locale.forLanguageTag(localeTag).getLanguage();
  }
}
