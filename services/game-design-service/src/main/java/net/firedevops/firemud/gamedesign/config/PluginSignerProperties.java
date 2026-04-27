package net.firedevops.firemud.gamedesign.config;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "plugin.signer")
public class PluginSignerProperties {
  private Map<String, String> publicKeys = new HashMap<>();
}
