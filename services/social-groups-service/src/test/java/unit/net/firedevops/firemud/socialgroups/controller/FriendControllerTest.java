package net.firedevops.firemud.socialgroups.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresencePolicyViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue;
import net.firedevops.firemud.socialgroups.dto.FriendRosterEntryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterSummaryDto;
import net.firedevops.firemud.socialgroups.dto.UpdateFriendPresencePolicyRequest;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.FriendService;
import net.firedevops.firemud.test.WithFiremudHttpAuthTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(FriendController.class)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudHttpAuthTestProperties
class FriendControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private FriendService friendService;
  @MockitoBean private SocialAccessGuard socialAccessGuard;

  @Test
  void addFriendReturnsDto() throws Exception {
    AddFriendRequest request = new AddFriendRequest(1L, 2L, 3L);
    FriendLinkDto response = new FriendLinkDto(1L, 1L, 2L, 3L, "active", null);
    when(friendService.addFriend(request)).thenReturn(response);

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            post("/friends")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.accountId").value(2L));
  }

  @Test
  void addFriendRejectsSelfLinkAsBadRequest() throws Exception {
    AddFriendRequest request = new AddFriendRequest(1L, 2L, 2L);
    when(friendService.addFriend(request))
        .thenThrow(
            new IllegalArgumentException("Cannot add or remove your own account as a friend"));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            post("/friends")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(
            jsonPath("$.error.message").value("Cannot add or remove your own account as a friend"));
  }

  @Test
  void removeFriendReturnsSuccess() throws Exception {
    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            delete("/friends/3")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void removeFriendRejectsSelfLinkAsBadRequest() throws Exception {
    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    org.mockito.Mockito.doThrow(
            new IllegalArgumentException("Cannot add or remove your own account as a friend"))
        .when(friendService)
        .removeFriend(1L, 2L, 2L);

    mockMvc
        .perform(
            delete("/friends/2")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(
            jsonPath("$.error.message").value("Cannot add or remove your own account as a friend"));
  }

  @Test
  void removeFriendRejectsMalformedFriendAccountIdBeforeAccessCheck() throws Exception {
    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));

    mockMvc
        .perform(
            delete("/friends/not-a-number")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("friendAccountId must be numeric"));

    verifyNoInteractions(friendService, socialAccessGuard);
  }

  @Test
  void getFriendReturnsCanonicalRosterEntry() throws Exception {
    when(friendService.getFriend(1L, 2L, 3L))
        .thenReturn(
            java.util.Optional.of(
                new FriendRosterEntryDto(
                    1,
                    7L,
                    1L,
                    2L,
                    3L,
                    "active",
                    java.time.Instant.parse("2026-04-10T01:02:03Z"),
                    new FriendPresenceDto(
                        3L,
                        true,
                        9L,
                        "SHARED",
                        "demo",
                        "Demo World",
                        "production",
                        "Live Realm",
                        17L,
                        99L,
                        "Ben",
                        null,
                        null,
                        null))));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            get("/friends/3")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.friendLinkId").value(7L))
        .andExpect(jsonPath("$.data.friendAccountId").value(3L))
        .andExpect(jsonPath("$.data.presence.characterName").value("Ben"));
  }

  @Test
  void getFriendByOrdinalReturnsCanonicalRosterEntry() throws Exception {
    when(friendService.getFriendByOrdinal(1L, 2L, 1))
        .thenReturn(
            java.util.Optional.of(
                new FriendRosterEntryDto(
                    1,
                    7L,
                    1L,
                    2L,
                    3L,
                    "active",
                    java.time.Instant.parse("2026-04-10T01:02:03Z"),
                    new FriendPresenceDto(
                        3L,
                        true,
                        9L,
                        "SHARED",
                        "demo",
                        "Demo World",
                        "production",
                        "Live Realm",
                        17L,
                        99L,
                        "Ben",
                        null,
                        null,
                        null))));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            get("/friends/entry/1")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.ordinal").value(1))
        .andExpect(jsonPath("$.data.friendAccountId").value(3L));
  }

  @Test
  void getFriendByOrdinalRejectsMalformedOrdinalBeforeAccessCheck() throws Exception {
    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));

    mockMvc
        .perform(
            get("/friends/entry/not-a-number")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("ordinal must be numeric"));

    verifyNoInteractions(friendService, socialAccessGuard);
  }

  @Test
  void listFriendPresenceReturnsPresenceList() throws Exception {
    when(friendService.listFriendPresence(
            1L, 2L, net.firedevops.firemud.socialgroups.dto.FriendRosterFilter.FRIENDS_ONLY))
        .thenReturn(
            new net.firedevops.firemud.socialgroups.dto.FriendPresenceViewDto(
                net.firedevops.firemud.socialgroups.dto.FriendRosterFilter.FRIENDS_ONLY,
                2,
                1,
                List.of(
                    new FriendPresenceDto(
                        3L,
                        true,
                        9L,
                        "demo",
                        "Demo World",
                        "production",
                        "Live Realm",
                        99L,
                        "Ben",
                        null,
                        null,
                        null))));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            get("/friends/presence")
                .param("tenantId", "1")
                .param("accountId", "2")
                .param("filter", "FRIENDS_ONLY")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.filter").value("FRIENDS_ONLY"))
        .andExpect(jsonPath("$.data.totalCount").value(2))
        .andExpect(jsonPath("$.data.matchCount").value(1))
        .andExpect(jsonPath("$.data.presences[0].friendAccountId").value(3L))
        .andExpect(jsonPath("$.data.presences[0].online").value(true))
        .andExpect(jsonPath("$.data.presences[0].worldSlug").value("demo"))
        .andExpect(jsonPath("$.data.presences[0].realmSlug").value("production"));
  }

  @Test
  void getFriendPresencePolicyReturnsCanonicalPolicy() throws Exception {
    when(friendService.getFriendPresencePolicy(1L, 2L))
        .thenReturn(new FriendPresencePolicyViewDto(FriendPresenceVisibilityPolicyValue.PRIVATE));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            get("/friends/visibility")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.currentPolicy").value("PRIVATE"));
  }

  @Test
  void getFriendPresencePolicyRejectsMalformedAccountIdBeforeAccessCheck() throws Exception {
    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));

    mockMvc
        .perform(
            get("/friends/visibility")
                .param("tenantId", "1")
                .param("accountId", "bad-account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must be numeric"));

    verifyNoInteractions(friendService, socialAccessGuard);
  }

  @Test
  void updateFriendPresencePolicyReturnsCanonicalPolicy() throws Exception {
    UpdateFriendPresencePolicyRequest request =
        new UpdateFriendPresencePolicyRequest(
            1L, 2L, FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY);
    when(friendService.updateFriendPresencePolicy(
            1L, 2L, FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY))
        .thenReturn(
            new FriendPresencePolicyViewDto(FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            put("/friends/visibility")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.currentPolicy").value("FRIENDS_ONLY"));
  }

  @Test
  void updateFriendPresencePolicyRejectsReservedHiddenStaff() throws Exception {
    UpdateFriendPresencePolicyRequest request =
        new UpdateFriendPresencePolicyRequest(
            1L, 2L, FriendPresenceVisibilityPolicyValue.HIDDEN_STAFF);
    when(friendService.updateFriendPresencePolicy(
            1L, 2L, FriendPresenceVisibilityPolicyValue.HIDDEN_STAFF))
        .thenThrow(
            new IllegalArgumentException(
                "Friend presence visibility policy HIDDEN_STAFF is reserved"));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            put("/friends/visibility")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(
            jsonPath("$.error.message")
                .value("Friend presence visibility policy HIDDEN_STAFF is reserved"));
  }

  @Test
  void listFriendsReturnsRosterList() throws Exception {
    when(friendService.listFriends(
            1L, 2L, net.firedevops.firemud.socialgroups.dto.FriendRosterFilter.FRIENDS_ONLY))
        .thenReturn(
            new net.firedevops.firemud.socialgroups.dto.FriendRosterViewDto(
                net.firedevops.firemud.socialgroups.dto.FriendRosterFilter.FRIENDS_ONLY,
                1,
                1,
                List.of(
                    new FriendRosterEntryDto(
                        1,
                        7L,
                        1L,
                        2L,
                        3L,
                        "active",
                        java.time.Instant.parse("2026-04-10T01:02:03Z"),
                        new FriendPresenceDto(
                            3L,
                            true,
                            9L,
                            "SHARED",
                            "demo",
                            "Demo World",
                            "production",
                            "Live Realm",
                            17L,
                            99L,
                            "Ben",
                            null,
                            null,
                            null)))));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            get("/friends")
                .param("tenantId", "1")
                .param("accountId", "2")
                .param("filter", "FRIENDS_ONLY")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.filter").value("FRIENDS_ONLY"))
        .andExpect(jsonPath("$.data.totalCount").value(1))
        .andExpect(jsonPath("$.data.friends[0].friendLinkId").value(7L))
        .andExpect(jsonPath("$.data.friends[0].friendAccountId").value(3L))
        .andExpect(jsonPath("$.data.friends[0].status").value("active"))
        .andExpect(jsonPath("$.data.friends[0].presence.online").value(true))
        .andExpect(jsonPath("$.data.friends[0].presence.worldSlug").value("demo"));
  }

  @Test
  void listFriendsRejectsZeroTenantIdBeforeAccessCheck() throws Exception {
    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));

    mockMvc
        .perform(
            get("/friends")
                .param("tenantId", "0")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(friendService, socialAccessGuard);
  }

  @Test
  void getFriendRosterSummaryReturnsCanonicalCounts() throws Exception {
    when(friendService.getFriendRosterSummary(1L, 2L))
        .thenReturn(new FriendRosterSummaryDto(4, 1, 3, 2, 1, 2, 1, 0, 0, 2, 1, 1));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            get("/friends/summary")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.totalCount").value(4))
        .andExpect(jsonPath("$.data.onlineCount").value(1))
        .andExpect(jsonPath("$.data.offlineCount").value(3))
        .andExpect(jsonPath("$.data.recentCount").value(2))
        .andExpect(jsonPath("$.data.publicCount").value(1))
        .andExpect(jsonPath("$.data.friendsOnlyCount").value(2))
        .andExpect(jsonPath("$.data.privateCount").value(1))
        .andExpect(jsonPath("$.data.hiddenStaffCount").value(0))
        .andExpect(jsonPath("$.data.unspecifiedVisibilityCount").value(0))
        .andExpect(jsonPath("$.data.sharedCount").value(2))
        .andExpect(jsonPath("$.data.isolatedCount").value(1))
        .andExpect(jsonPath("$.data.unspecifiedScopeCount").value(1));
  }

  @Test
  void removeFriendByOrdinalReturnsRemovedEntry() throws Exception {
    when(friendService.removeFriendByOrdinal(1L, 2L, 1))
        .thenReturn(
            java.util.Optional.of(
                new FriendRosterEntryDto(
                    1,
                    7L,
                    1L,
                    2L,
                    3L,
                    "active",
                    java.time.Instant.parse("2026-04-10T01:02:03Z"),
                    new FriendPresenceDto(
                        3L, false, null, null, null, null, null, null, null, null, null, null, null,
                        null, null))));

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    mockMvc
        .perform(
            delete("/friends/entry/1")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.friendAccountId").value(3L));
  }

  @Test
  void removeFriendByOrdinalRejectsMalformedOrdinalBeforeAccessCheck() throws Exception {
    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));

    mockMvc
        .perform(
            delete("/friends/entry/not-a-number")
                .param("tenantId", "1")
                .param("accountId", "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("ordinal must be numeric"));

    verifyNoInteractions(friendService, socialAccessGuard);
  }
}
