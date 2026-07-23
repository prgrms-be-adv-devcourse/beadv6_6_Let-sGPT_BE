package com.openat.recommendation.application.port.out;

import com.openat.recommendation.domain.model.DropMeta;
import java.util.List;

public interface OpenDropClient {

  List<DropMeta> getAllOpenDrops();
}
