package net.firedevops.firemud.automationscripting.service.quota;

public interface ScriptDryRunQuotaService {
  boolean tryAcquire(String tenantId, String scriptId, String principalKey);
}
