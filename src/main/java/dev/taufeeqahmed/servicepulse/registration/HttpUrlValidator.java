package dev.taufeeqahmed.servicepulse.registration;

import java.net.URI;
import java.net.URISyntaxException;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Let @NotBlank report missing values without a second, redundant error.
        if (value == null || value.isBlank()) {
            return true;
        }

        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getPort() <= 65535;
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
