package net.firedevops.firemud.socialgroups.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.socialgroups.dto.AddGuildMemberRequest;
import net.firedevops.firemud.socialgroups.dto.AddGuildStorageItemRequest;
import net.firedevops.firemud.socialgroups.dto.CreateAllianceRequest;
import net.firedevops.firemud.socialgroups.dto.CreateGuildRequest;
import net.firedevops.firemud.socialgroups.dto.GuildAllianceDto;
import net.firedevops.firemud.socialgroups.dto.GuildDto;
import net.firedevops.firemud.socialgroups.dto.GuildMemberDto;
import net.firedevops.firemud.socialgroups.dto.GuildStorageItemDto;
import net.firedevops.firemud.socialgroups.dto.UpdateGuildMemberRoleRequest;
import net.firedevops.firemud.socialgroups.service.GuildService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/guilds")
public class GuildController {
  private final GuildService guildService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring manages GuildService bean lifecycle")
  public GuildController(GuildService guildService) {
    this.guildService = guildService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<GuildDto>> createGuild(
      @Valid @RequestBody CreateGuildRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    GuildDto dto = guildService.createGuild(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PostMapping("/storage")
  public ResponseEntity<ApiResponse<GuildStorageItemDto>> addItem(
      @Valid @RequestBody AddGuildStorageItemRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    GuildStorageItemDto dto = guildService.addStorageItem(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PostMapping("/alliances")
  public ResponseEntity<ApiResponse<GuildAllianceDto>> createAlliance(
      @Valid @RequestBody CreateAllianceRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    GuildAllianceDto dto = guildService.createAlliance(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PostMapping("/members")
  public ResponseEntity<ApiResponse<GuildMemberDto>> addMember(
      @Valid @RequestBody AddGuildMemberRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    GuildMemberDto dto = guildService.addMember(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PostMapping("/members/role")
  public ResponseEntity<ApiResponse<GuildMemberDto>> updateMemberRole(
      @Valid @RequestBody UpdateGuildMemberRoleRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    GuildMemberDto dto = guildService.updateMemberRole(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PostMapping("/members/remove")
  public ResponseEntity<ApiResponse<String>> removeMember(
      @Valid @RequestBody AddGuildMemberRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    guildService.removeMember(request.tenantId(), request.guildId(), request.accountId());
    return ResponseEntity.ok(ApiResponse.success("removed"));
  }
}
