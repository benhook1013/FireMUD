package net.firedevops.firemud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "game.logic")
public class GameLogicProperties {
  private String defaultRoomId = "1021";
}
