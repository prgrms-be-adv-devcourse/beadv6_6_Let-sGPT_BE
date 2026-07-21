package com.openat.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("product.image.s3")
public record S3StorageProperties(
    @NotBlank String bucket,
    @NotBlank String stagingPrefix,
    @NotBlank String finalPrefix,
    @NotNull Duration presignExpiry,
    @NotNull DataSize maxUploadSize,
    String endpointOverride,
    String publicEndpointOverride,
    String accessKey,
    String secretKey) {}
