package net.firedevops.firemud.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.firedevops.firemud.dto.AddFriendRequest;
import net.firedevops.firemud.dto.FriendLinkDto;
import net.firedevops.firemud.service.FriendService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FriendController.class)
class FriendControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private FriendService friendService;

  @Test
  void addFriendReturnsDto() throws Exception {
    AddFriendRequest request = new AddFriendRequest(1L, 2L, 3L);
    FriendLinkDto response = new FriendLinkDto(1L, 1L, 2L, 3L, "active", null);
    when(friendService.addFriend(request)).thenReturn(response);

    mockMvc
        .perform(
            post("/friends")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.accountId").value(2L));
  }
}
