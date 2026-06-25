package com.openat.drop.domain.event;

import java.util.UUID;

public record DropDeletedEvent(UUID dropId) {}
