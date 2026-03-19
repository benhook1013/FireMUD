package net.firedevops.firemud.common.config;

import net.firedevops.firemud.common.security.RequireAdminRoleAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import(RequireAdminRoleAspect.class)
public class CommonSecurityAutoConfiguration {}
