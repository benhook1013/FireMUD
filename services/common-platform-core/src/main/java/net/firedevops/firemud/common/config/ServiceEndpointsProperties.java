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

  public ServiceEndpointsProperties copy() {
    ServiceEndpointsProperties copy = new ServiceEndpointsProperties();
    copy.setAccountService(accountService);
    copy.setGameSessionService(gameSessionService);
    copy.setGameDesignService(gameDesignService);
    copy.setGameLogicService(gameLogicService);
    copy.setWorldManagementService(worldManagementService);
    copy.setEntityManagementService(entityManagementService);
    copy.setLoggingAdminService(loggingAdminService);
    copy.setAutomationScriptingService(automationScriptingService);
    copy.setSocialGroupsService(socialGroupsService);
    return copy;
  }
}
