package net.firedevops.firemud.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.services")
public class ServiceEndpointsProperties {
  private String accountService;
  private String gameSessionService;
  private String gameDesignService;
  private String gameLogicService;
  private String worldManagementService;
  private String entityManagementService;
  private String loggingAdminService;
  private String automationScriptingService;
  private String socialGroupsService;
}
