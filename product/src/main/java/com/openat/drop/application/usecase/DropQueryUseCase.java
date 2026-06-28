package com.openat.drop.application.usecase;

import com.openat.drop.application.dto.DropInfo;
import com.openat.drop.domain.repository.DropSearchCondition;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DropQueryUseCase {
  DropInfo getDrop(UUID id);

  Page<DropInfo> searchDrops(DropSearchCondition condition, Pageable pageable);
}
