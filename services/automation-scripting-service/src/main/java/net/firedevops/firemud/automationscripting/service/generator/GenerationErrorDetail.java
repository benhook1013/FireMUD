package net.firedevops.firemud.automationscripting.service.generator;

/** Structured error returned when generation fails. */
public record GenerationErrorDetail(String code, String message) {}
