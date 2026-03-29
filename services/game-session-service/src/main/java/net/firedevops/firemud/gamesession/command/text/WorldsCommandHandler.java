package net.firedevops.firemud.gamesession.command.text;

import org.springframework.stereotype.Component;

/** Handles public world-browse commands before login and after login. */
@Component
public class WorldsCommandHandler {
  private static final String WORLDS_RESPONSE =
      "OK WORLDS\n" + "1) Demo World (demo)\n" + "2) Builder Sandbox (sandbox)\n" + "\n";

  public String describe() {
    return WORLDS_RESPONSE;
  }
}
