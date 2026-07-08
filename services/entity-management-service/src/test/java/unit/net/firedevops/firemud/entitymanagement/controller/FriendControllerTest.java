package net.firedevops.firemud.entitymanagement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import net.firedevops.firemud.entitymanagement.service.FriendService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.WithFiremudHttpAuthTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FriendController.class)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudHttpAuthTestProperties
@TestPropertySource(
    properties = {
      "firemud.auth.http.authenticated-path-patterns[0]=/tenants/*/characters/*/friends/**"
    })
class FriendControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private FriendService friendService;

  @AfterEach
  void clearSecurityContext() {
    SessionContext.clear();
  }

  @Test
  void listUsesTenantScopedPath() throws Exception {
    when(friendService.listFriends(
            eq(1L),
            eq(2L),
            eq("live"),
            eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
            any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(new CharacterFriendDto(2L, 3L, 123L))));

    mockMvc
        .perform(
            get("/tenants/1/characters/2/friends")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken("1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content[0].friendId").value(3));
  }

  @Test
  void addAndRemoveUseTenantScopedPath() throws Exception {
    when(friendService.addFriend(
            1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, 3L))
        .thenReturn(new CharacterFriendDto(2L, 3L, 123L));

    mockMvc
        .perform(
            post("/tenants/1/characters/2/friends")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken("1"))
                .content("{\"friendId\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.friendId").value(3));

    mockMvc
        .perform(
            delete("/tenants/1/characters/2/friends/3")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken("1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void listRejectsCallerWithoutTenantAccess() throws Exception {
    mockMvc
        .perform(
            get("/tenants/1/characters/2/friends")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken("9")))
        .andExpect(status().isForbidden());
  }

  @Test
  void listRejectsMalformedTenantIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            get("/tenants/not-a-number/characters/2/friends")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken("1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be numeric"));

    verifyNoInteractions(friendService);
  }

  @Test
  void removeRejectsZeroFriendIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            delete("/tenants/1/characters/2/friends/0")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken("1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("friendId must be positive"));

    verifyNoInteractions(friendService);
  }

  @Test
  void addRejectsZeroFriendIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            post("/tenants/1/characters/2/friends")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken("1"))
                .content("{\"friendId\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("friendId must be positive"));

    verifyNoInteractions(friendService);
  }

  private String tenantToken(String tenantId) {
    return jwtUtil.generateToken(
        "test-account", Map.of("scopedRoles", Map.of(tenantId, List.of("tenantAdmin"))));
  }
}
