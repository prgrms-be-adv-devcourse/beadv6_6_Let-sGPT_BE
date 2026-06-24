package com.openat.member.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Size(max = 30) String nickname
) {
}
