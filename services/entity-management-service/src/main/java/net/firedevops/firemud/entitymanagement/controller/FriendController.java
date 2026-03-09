package net.firedevops.firemud.entitymanagement.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.entitymanagement.dto.AddFriendRequest;
import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import net.firedevops.firemud.entitymanagement.service.FriendService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST endpoints for managing per-game friend links. */
@RestController
@RequestMapping("/characters/{characterId}/friends")
public class FriendController {
  private final FriendService friendService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring manages FriendService bean lifecycle")
  public FriendController(FriendService friendService) {
    this.friendService = friendService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Page<CharacterFriendDto>>> list(
      @PathVariable Long characterId, Pageable pageable) {
    Page<CharacterFriendDto> list = friendService.listFriends(characterId, pageable);
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
