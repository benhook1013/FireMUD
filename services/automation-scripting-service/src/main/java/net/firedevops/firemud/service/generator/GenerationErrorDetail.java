package net.firedevops.firemud.service.generator;

/** Structured error returned when generation fails. */
public record GenerationErrorDetail(String code, String message) {}
