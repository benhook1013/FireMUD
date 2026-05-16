package net.firedevops.firemud.socialgroups.dto;

import java.util.List;

public record FriendRosterViewDto(
    FriendRosterFilter filter, int totalCount, int matchCount, List<FriendRosterEntryDto> friends) {
  public FriendRosterViewDto {
    friends = List.copyOf(friends);
  }
}
