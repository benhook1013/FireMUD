package net.firedevops.firemud.service.generator;

/** Simple DTO representing a procedurally generated room. */
public record GeneratedRoom(long id, long connectedTo) {}
