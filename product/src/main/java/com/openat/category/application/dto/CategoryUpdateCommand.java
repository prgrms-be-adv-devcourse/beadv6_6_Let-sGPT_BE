package com.openat.category.application.dto;

import java.util.UUID;

public record CategoryUpdateCommand(UUID id, String name) {}
