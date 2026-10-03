package dev.taufeeqahmed.servicepulse.registration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

public record CreateServiceRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotBlank(message = "url must not be blank")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        @HttpUrl
        String url,

        @Positive(message = "failureThreshold must be greater than zero")
        Integer failureThreshold,

        @Size(max = 2048, message = "webhookUrl must be at most 2048 characters")
        @Pattern(regexp = "\\S+", message = "webhookUrl must not be blank or contain whitespace")
        @HttpUrl(message = "webhookUrl must be a valid HTTP or HTTPS URL")
        String webhookUrl) {
}
