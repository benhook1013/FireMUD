package net.firedevops.firemud.gamedesign.dto;

public record TemplateRemapEntryDto(
    String mappingDomain, String mappingType, String sourceTemplateKey, String targetTemplateKey) {}
