package net.firedevops.firemud.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.AddFriendRequest;
import net.firedevops.firemud.dto.FriendLinkDto;
import net.firedevops.firemud.service.FriendService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/friends")
public class FriendController {
  private final FriendService friendService;

  public FriendController(FriendService friendService) {
    this.friendService = friendService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<FriendLinkDto>> addFriend(
      @Valid @RequestBody AddFriendRequest request) {
    FriendLinkDto dto = friendService.addFriend(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
