package net.firedevops.firemud.entitymanagement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import net.firedevops.firemud.entitymanagement.security.JwtAuthInterceptor;
import net.firedevops.firemud.entitymanagement.service.FriendService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FriendController.class)
class FriendControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FriendService friendService;
  @MockitoBean private JwtAuthInterceptor jwtAuthInterceptor;

  @BeforeEach
  void setUpSecurityContext() throws Exception {
    installTenantContext(Map.of("1", List.of("admin")));
  }

  @AfterEach
  void clearSecurityContext() {
    SessionContext.clear();
  }

  @Test
  void listUsesTenantScopedPath() throws Exception {
    when(friendService.listFriends(eq(1L), eq(2L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(new CharacterFriendDto(2L, 3L, 123L))));

    mockMvc
        .perform(get("/tenants/1/characters/2/friends"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content[0].friendId").value(3));
  }

  @Test
  void addAndRemoveUseTenantScopedPath() throws Exception {
    when(friendService.addFriend(1L, 2L, 3L)).thenReturn(new CharacterFriendDto(2L, 3L, 123L));

    mockMvc
        .perform(
            post("/tenants/1/characters/2/friends")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"friendId\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.friendId").value(3));

    mockMvc
        .perform(delete("/tenants/1/characters/2/friends/3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void listRejectsCallerWithoutTenantAccess() throws Exception {
    installTenantContext(Map.of("9", List.of("admin")));

    mockMvc.perform(get("/tenants/1/characters/2/friends")).andExpect(status().isForbidden());
  }

  private void installTenantContext(Map<String, List<String>> scopedRoles) throws Exception {
    doAnswer(
            invocation -> {
              SessionContext.setContext("test-account", List.of(), scopedRoles);
              return true;
            })
        .when(jwtAuthInterceptor)
        .preHandle(any(), any(), any());
  }
}
