package com.forrestgump.leadapi.interfaces.rest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LeadRequest(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "CPF is required") @Pattern(regexp = "\\d{11}", message = "CPF must be 11 digits") @ValidCpf String cpf,
        @NotBlank(message = "Phone is required") String phone,
        @NotBlank(message = "Email is required") @Email(message = "Invalid email format") String email
) {}