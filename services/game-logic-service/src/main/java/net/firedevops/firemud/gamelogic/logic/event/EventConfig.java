package net.firedevops.firemud.gamelogic.logic.event;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration for game event dispatching. */
@Configuration
public class EventConfig {
  @Bean
  public EventDispatcher eventDispatcher() {
    return new EventDispatcher();
  }
}
