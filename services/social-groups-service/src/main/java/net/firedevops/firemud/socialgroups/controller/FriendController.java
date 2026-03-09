package net.firedevops.firemud.socialgroups.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.service.FriendService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/friends")
public class FriendController {
  private final FriendService friendService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring manages FriendService bean lifecycle")
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
