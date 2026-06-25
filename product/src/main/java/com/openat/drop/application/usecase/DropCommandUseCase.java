package com.openat.drop.application.usecase;

import com.openat.drop.application.dto.DropCreateCommand;
import java.util.UUID;

public interface DropCommandUseCase {
  UUID create(DropCreateCommand command);
}
