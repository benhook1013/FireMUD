package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request object for public email verification initiation. */
public record EmailVerificationRequest(@NotNull @Email @Size(max = 100) String email) {}
