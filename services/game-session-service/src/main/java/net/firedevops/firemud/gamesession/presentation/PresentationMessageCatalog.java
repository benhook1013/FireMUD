package net.firedevops.firemud.gamesession.presentation;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Resolves built-in localized renderer text for the configured locale. */
@Component
public class PresentationMessageCatalog {
  private static final String BUNDLE_BASENAME = "presentation-messages";
  private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("en-NZ");

  public String render(
      String fallbackText, String messageKey, Map<String, String> arguments, String localeTag) {
    if (!StringUtils.hasText(messageKey)) {
      return fallbackText;
    }
    Locale locale =
        StringUtils.hasText(localeTag) ? Locale.forLanguageTag(localeTag) : DEFAULT_LOCALE;
    try {
      ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_BASENAME, locale);
      if (!bundle.containsKey(messageKey)) {
        return fallbackText;
      }
      return applyArguments(bundle.getString(messageKey), arguments);
    } catch (MissingResourceException ex) {
      return fallbackText;
    }
  }

  private String applyArguments(String template, Map<String, String> arguments) {
    String rendered = Objects.requireNonNull(template, "template must not be null");
    if (arguments == null || arguments.isEmpty()) {
      return rendered;
    }
    for (Map.Entry<String, String> entry : arguments.entrySet()) {
      String replacement = entry.getValue() == null ? "" : entry.getValue();
      rendered = rendered.replace("{" + entry.getKey() + "}", replacement);
    }
    return rendered;
  }
}
