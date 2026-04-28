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

  private static Predicate<String> promptTolerantMatcher(String canonical) {
    String leadingPrompt = "demo> \n" + canonical;
    String trailingPrompt = canonical + "\n\ndemo>";
    String wrappedPrompt = "demo> \n" + trailingPrompt;
    return response ->
        response.equals(canonical)
            || response.equals(leadingPrompt)
            || response.equals(trailingPrompt)
            || response.equals(wrappedPrompt);
  }
}
