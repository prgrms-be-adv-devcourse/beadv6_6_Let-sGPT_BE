package com.openat.category.domain.event;

import java.util.UUID;

public record CategoryDeletingEvent(UUID categoryId) {}
