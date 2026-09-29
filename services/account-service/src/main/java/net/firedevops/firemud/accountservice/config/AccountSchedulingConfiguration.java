package net.firedevops.firemud.accountservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables Account maintenance jobs only during ordinary service operation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.account-tenant-migration",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true)
@EnableScheduling
public class AccountSchedulingConfiguration {}
