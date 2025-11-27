package unit.net.firedevops.firemud.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.firedevops.firemud.command.text.LookCommandHandler;
import org.junit.jupiter.api.Test;

class LookCommandHandlerTest {
  @Test
  void returnsDefaultDescription() {
    LookCommandHandler handler = new LookCommandHandler();
    assertEquals(LookCommandHandler.DEFAULT_ROOM_DESCRIPTION, handler.describe("123"));
  }
}
