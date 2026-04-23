package net.firedevops.firemud.accountservice.dto;

public record TenantDataExportDto(Long tenantId, AccountDto account, ProfileDto profile) {}
