package net.firedevops.firemud.gamesession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.gamesession.controller.PingController;
import net.firedevops.firemud.gamesession.service.impl.PingServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Startup smoke test for the game-session ping endpoint. */
@WebMvcTest(PingController.class)
@Import(PingServiceImpl.class)
class GameSessionCrossServiceIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void gameSessionRunsAlongsideGameLogicService() throws Exception {
    mockMvc.perform(get("/ping")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("pong")));
  }
}
