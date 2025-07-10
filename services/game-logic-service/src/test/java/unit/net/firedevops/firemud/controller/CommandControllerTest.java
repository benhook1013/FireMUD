package net.firedevops.firemud.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.logic.dto.CommandResult;
import net.firedevops.firemud.logic.service.CommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommandController.class)
class CommandControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CommandService commandService;

  @Test
  void executeReturnsResult() throws Exception {
    when(commandService.handleCommand("north"))
        .thenReturn(new CommandResult("You move north", null));

    mockMvc
        .perform(post("/command").contentType(MediaType.TEXT_PLAIN).content("north"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("You move north"));
  }

  @Test
  void executeReturnsError() throws Exception {
    CommandResult errorResult =
        new CommandResult(
            "Unknown action",
            new net.firedevops.firemud.common.ErrorDetail(
                "UNKNOWN_COMMAND", "Command not recognized"));
    when(commandService.handleCommand("foo")).thenReturn(errorResult);

    mockMvc
        .perform(post("/command").contentType(MediaType.TEXT_PLAIN).content("foo"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("UNKNOWN_COMMAND"));
  }
}
