package com.openat.drop.domain.event;

import java.time.Instant;
import java.util.UUID;

public record DropRegisteredEvent(UUID dropId, Instant openAt, Instant closeAt) {}
