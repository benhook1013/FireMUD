package net.firedevops.firemud.controller;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.AddFriendRequest;
import net.firedevops.firemud.dto.CharacterFriendDto;
import net.firedevops.firemud.service.FriendService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST endpoints for managing per-game friend links. */
@RestController
@RequestMapping("/characters/{characterId}/friends")
@RequiredArgsConstructor
public class FriendController {
  private final FriendService friendService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<CharacterFriendDto>>> list(
      @PathVariable Long characterId) {
    List<CharacterFriendDto> list = friendService.listFriends(characterId);
    return ResponseEntity.ok(ApiResponse.success(list));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CharacterFriendDto>> add(
      @PathVariable Long characterId, @Valid @RequestBody AddFriendRequest request) {
    CharacterFriendDto dto = friendService.addFriend(characterId, request.friendId());
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @DeleteMapping("/{friendId}")
  public ResponseEntity<ApiResponse<Void>> remove(
      @PathVariable Long characterId, @PathVariable Long friendId) {
    friendService.removeFriend(characterId, friendId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
