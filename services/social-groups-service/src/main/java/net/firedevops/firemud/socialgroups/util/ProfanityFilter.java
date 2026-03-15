package net.firedevops.firemud.socialgroups.util;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ProfanityFilter {
  private final Set<String> bannedWords = new HashSet<>();

  @PostConstruct
  void loadWords() throws IOException {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                getClass().getResourceAsStream("/profanity-words.txt"), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        bannedWords.add(line.trim().toLowerCase());
      }
    }
  }

  public String filter(String message) {
    String[] parts = message.split(" ");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      String word = parts[i];
      if (bannedWords.contains(word.toLowerCase())) {
        sb.append("*".repeat(word.length()));
      } else {
        sb.append(word);
      }
      if (i < parts.length - 1) {
        sb.append(' ');
      }
    }
    return sb.toString();
  }
}
