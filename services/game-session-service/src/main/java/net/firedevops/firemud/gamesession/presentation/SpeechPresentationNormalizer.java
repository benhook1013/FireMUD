package net.firedevops.firemud.gamesession.presentation;

import org.springframework.util.StringUtils;

/** Shared conservative normalization for spoken dialogue presentation. */
final class SpeechPresentationNormalizer {
  private SpeechPresentationNormalizer() {}

  static String normalize(String text) {
    if (!StringUtils.hasText(text)) {
      return "";
    }
    String normalized = text.trim();
    normalized = capitalizeFirstAlphabeticCharacter(normalized);
    if (!hasTerminalPunctuation(normalized)) {
      normalized = normalized + ".";
    }
    return normalized;
  }

  private static String capitalizeFirstAlphabeticCharacter(String text) {
    StringBuilder builder = new StringBuilder(text);
    for (int index = 0; index < builder.length(); index++) {
      char current = builder.charAt(index);
      if (!Character.isAlphabetic(current)) {
        continue;
      }
      if (Character.isLowerCase(current)) {
        builder.setCharAt(index, Character.toUpperCase(current));
      }
      break;
    }
    return builder.toString();
  }

  private static boolean hasTerminalPunctuation(String text) {
    int end = text.length() - 1;
    while (end >= 0 && Character.isWhitespace(text.charAt(end))) {
      end--;
    }
    while (end >= 0 && isTrailingCloser(text.charAt(end))) {
      end--;
    }
    if (end < 0) {
      return false;
    }
    return switch (text.charAt(end)) {
      case '.', '!', '?' -> true;
      default -> false;
    };
  }

  private static boolean isTrailingCloser(char value) {
    return switch (value) {
      case '"', '\'', ')', ']', '}' -> true;
      default -> false;
    };
  }
}
