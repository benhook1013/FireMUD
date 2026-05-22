package net.firedevops.firemud.socialgroups.dto;

import java.util.List;

public record FriendPresenceViewDto(
    FriendRosterFilter filter, int totalCount, int matchCount, List<FriendPresenceDto> presences) {
  public FriendPresenceViewDto {
    presences = List.copyOf(presences);
  }
}
