package com.openat.support.lock;

import java.util.UUID;

/**
 * Serializes product search-snapshot writes with changes to their local reference data.
 *
 * <p>Product mutations take shared read locks because they only capture reference snapshots.
 * Reference changes take exclusive write locks. Callers must already be inside a database
 * transaction. When both references are needed, acquire the seller lock before the category lock.
 */
public interface SearchProjectionReferenceLock {

  void lockSellerForSnapshotRead(UUID sellerId);

  void lockCategoryForSnapshotRead(UUID categoryId);

  void lockSellerForReferenceWrite(UUID sellerId);

  void lockCategoryForReferenceWrite(UUID categoryId);
}
