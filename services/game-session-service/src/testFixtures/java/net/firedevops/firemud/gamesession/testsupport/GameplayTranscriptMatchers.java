package net.firedevops.firemud.gamesession.testsupport;

import java.util.function.Predicate;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;

/** Shared transcript matchers for canonical gameplay LOOK and movement refresh output. */
public final class GameplayTranscriptMatchers {

  private GameplayTranscriptMatchers() {}

  public static String canonicalLook() {
    return canonicalLook(LookTestFixtures.ROOM_ID);
  }

  public static String canonicalLook(String roomId) {
    return LookTestFixtures.canonicalLookText(roomId).trim();
  }

  public static String canonicalLookWithPrompt() {
    return canonicalLookWithPrompt(LookTestFixtures.ROOM_ID);
  }

  public static String canonicalLookWithPrompt(String roomId) {
    return canonicalLook(roomId) + "\n\ndemo>";
  }

  public static Predicate<String> matchesCanonicalLookWithOptionalPrompt() {
    return matchesCanonicalLookWithOptionalPrompt(LookTestFixtures.ROOM_ID);
  }

  public static Predicate<String> matchesCanonicalLookWithOptionalPrompt(String roomId) {
    String canonical = canonicalLook(roomId);
    return promptTolerantMatcher(canonical);
  }

  public static String canonicalMoveRefresh(String roomId) {
    return LookTestFixtures.canonicalLookText(roomId).replaceFirst("\\nLong: .*\\n", "\n").trim();
  }

  public static Predicate<String> matchesCanonicalMoveRefreshWithOptionalPrompt(String roomId) {
    return promptTolerantMatcher(canonicalMoveRefresh(roomId));
  }

  public static Predicate<String> matchesCanonicalMoveOrLookWithOptionalPrompt(String roomId) {
    Predicate<String> moveRefresh = matchesCanonicalMoveRefreshWithOptionalPrompt(roomId);
    Predicate<String> canonicalLook = matchesCanonicalLookWithOptionalPrompt(roomId);
    return response -> moveRefresh.test(response) || canonicalLook.test(response);
  }

  private static Predicate<String> promptTolerantMatcher(String canonical) {
    return response -> canonical.equals(stripOptionalGameplayPrompt(response));
  }

  private static String stripOptionalGameplayPrompt(String response) {
    return stripTrailingPrompt(stripLeadingPrompt(response)).trim();
  }

  private static String stripLeadingPrompt(String response) {
    int newlineIndex = response.indexOf('\n');
    if (newlineIndex < 0) {
      return response;
    }
    String firstLine = response.substring(0, newlineIndex).trim();
    if (!looksLikeGameplayPrompt(firstLine)) {
      return response;
    }
    return response.substring(newlineIndex + 1).stripLeading();
  }

  private static String stripTrailingPrompt(String response) {
    int separatorIndex = response.lastIndexOf("\n\n");
    if (separatorIndex < 0) {
      return response;
    }
    String trailingLine = response.substring(separatorIndex + 2).trim();
    if (trailingLine.contains("\n") || !looksLikeGameplayPrompt(trailingLine)) {
      return response;
    }
    return response.substring(0, separatorIndex);
  }

  private static boolean looksLikeGameplayPrompt(String line) {
    return !line.isBlank() && line.endsWith(">");
  }
}
