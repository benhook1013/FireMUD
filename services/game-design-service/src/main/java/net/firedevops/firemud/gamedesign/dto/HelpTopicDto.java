package net.firedevops.firemud.gamedesign.dto;

import java.util.List;

public record HelpTopicDto(
    String canonicalTopicId, String title, String body, List<String> aliases, boolean published) {
  public HelpTopicDto {
    aliases = aliases == null ? List.of() : List.copyOf(aliases);
  }
}
