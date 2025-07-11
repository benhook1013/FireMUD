package net.firedevops.firemud.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.AddGuildStorageItemRequest;
import net.firedevops.firemud.dto.CreateAllianceRequest;
import net.firedevops.firemud.dto.CreateGuildRequest;
import net.firedevops.firemud.dto.GuildAllianceDto;
import net.firedevops.firemud.dto.GuildDto;
import net.firedevops.firemud.dto.GuildStorageItemDto;
import net.firedevops.firemud.service.GuildService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/guilds")
public class GuildController {
  private final GuildService guildService;

  public GuildController(GuildService guildService) {
    this.guildService = guildService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<GuildDto>> createGuild(
      @Valid @RequestBody CreateGuildRequest request) {
    GuildDto dto = guildService.createGuild(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PostMapping("/storage")
  public ResponseEntity<ApiResponse<GuildStorageItemDto>> addItem(
      @Valid @RequestBody AddGuildStorageItemRequest request) {
    GuildStorageItemDto dto = guildService.addStorageItem(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PostMapping("/alliances")
  public ResponseEntity<ApiResponse<GuildAllianceDto>> createAlliance(
      @Valid @RequestBody CreateAllianceRequest request) {
    GuildAllianceDto dto = guildService.createAlliance(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
