package net.firedevops.firemud.gamelogic.logic.command;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration for command parsing beans. */
@Configuration
public class CommandConfig {
  @Bean
  public CommandParser commandParser() {
    return new DefaultCommandParser();
  }
}
