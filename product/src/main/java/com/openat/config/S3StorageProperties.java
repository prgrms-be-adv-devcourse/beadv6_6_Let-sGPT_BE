package com.openat.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("product.image.s3")
public record S3StorageProperties(@NotBlank String bucket) {}
