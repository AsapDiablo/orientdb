/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */

package com.orientechnologies.orient.core.storage.cluster.v0;

import com.orientechnologies.common.util.OCommonConst;
import com.orientechnologies.orient.core.exception.OClusterPositionMapException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.cluster.OClusterPositionMap;
import com.orientechnologies.orient.core.storage.cluster.OClusterPositionMapBucket;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;

import java.io.IOException;
import java.util.Arrays;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 10/7/13
 */
public class OClusterPositionMapV0 extends OClusterPositionMap {
  private long fileId;

  OClusterPositionMapV0(OAbstractPaginatedStorage storage, String name, String lockName) {
    super(storage, name, DEF_EXTENSION, lockName);
  }

  public void open() throws IOException {
    fileId = openFile(getFullName());
  }

  public void create(OAtomicOperation atomicOperation) throws IOException {
    fileId = addFile(getFullName(), atomicOperation);
  }

  public void flush() {
    writeCache.flush(fileId);
  }

  public void close(boolean flush) throws IOException {
    readCache.closeFile(fileId, flush, writeCache);
  }

  public void truncate() throws IOException {
    truncateFile(fileId);
  }

  public void delete(OAtomicOperation atomicOperation) throws IOException {
    deleteFile(fileId, atomicOperation);
  }

  void rename(String newName) throws IOException {
    writeCache.renameFile(fileId, newName + getExtension());
    setName(newName);
  }

  public long add(long pageIndex, int recordPosition, OAtomicOperation atomicOperation) throws IOException {
    long lastPage = getFilledUpTo(fileId) - 1;
    OCacheEntry cacheEntry;
    boolean clear = false;

    if (lastPage < 0) {
      cacheEntry = addPage(fileId, false);
      clear = true;
    } else {
      cacheEntry = loadPageForWrite(fileId, lastPage, false);
    }

    OClusterPositionMapBucket bucket = null;
    try {
      bucket = new OClusterPositionMapBucket(cacheEntry, clear);
      if (bucket.isFull()) {
        releasePageFromWrite(bucket, atomicOperation);

        cacheEntry = addPage(fileId, false);

        bucket = new OClusterPositionMapBucket(cacheEntry, true);
      }

      final long index = bucket.add(pageIndex, recordPosition);
      return index + cacheEntry.getPageIndex() * OClusterPositionMapBucket.MAX_ENTRIES;
    } finally {
      assert bucket != null;

      releasePageFromWrite(bucket, atomicOperation);
    }
  }

  public long allocate(OAtomicOperation atomicOperation) throws IOException {
    boolean clear = false;
    long lastPage = getFilledUpTo(fileId) - 1;
    OCacheEntry cacheEntry;
    if (lastPage < 0) {
      cacheEntry = addPage(fileId, false);
      clear = true;
    } else {
      cacheEntry = loadPageForWrite(fileId, lastPage, false);
    }

    OClusterPositionMapBucket bucket = null;
    try {
      bucket = new OClusterPositionMapBucket(cacheEntry, clear);
      if (bucket.isFull()) {
        releasePageFromWrite(bucket, atomicOperation);

        cacheEntry = addPage(fileId, false);

        bucket = new OClusterPositionMapBucket(cacheEntry, clear);
      }

      final long index = bucket.allocate();
      return index + cacheEntry.getPageIndex() * OClusterPositionMapBucket.MAX_ENTRIES;
    } finally {
      assert bucket != null;
      releasePageFromWrite(bucket, atomicOperation);
    }
  }

  public void update(final long clusterPosition, final OClusterPositionMapBucket.PositionEntry entry,
      OAtomicOperation atomicOperation) throws IOException {

    final long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
    final int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

    if (pageIndex >= getFilledUpTo(fileId)) {
      throw new OClusterPositionMapException(
          "Passed in cluster position " + clusterPosition + " is outside of range of cluster-position map", this);
    }

    final OCacheEntry cacheEntry = loadPageForWrite(fileId, pageIndex, false);
    OClusterPositionMapBucket bucket = null;
    try {
      bucket = new OClusterPositionMapBucket(cacheEntry, false);
      bucket.set(index, entry);
    } finally {
      assert bucket != null;
      releasePageFromWrite(bucket, atomicOperation);
    }
  }

  void resurrect(final long clusterPosition, final OClusterPositionMapBucket.PositionEntry entry, OAtomicOperation atomicOperation)
      throws IOException {
    final long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
    final int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

    if (pageIndex >= getFilledUpTo(fileId)) {
      throw new OClusterPositionMapException(
          "Passed in cluster position " + clusterPosition + " is outside of range of cluster-position map", this);
    }

    final OCacheEntry cacheEntry = loadPageForWrite(fileId, pageIndex, false);
    OClusterPositionMapBucket bucket = null;
    try {
      bucket = new OClusterPositionMapBucket(cacheEntry, false);
      bucket.resurrect(index, entry);
    } finally {
      releasePageFromWrite(bucket, atomicOperation);
    }
  }

  public OClusterPositionMapBucket.PositionEntry get(final long clusterPosition, final int pageCount) throws IOException {
    long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
    int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

    if (pageIndex >= getFilledUpTo(fileId)) {
      return null;
    }

    final OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false, pageCount);
    try {
      final OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, false);
      return bucket.get(index);
    } finally {
      releasePageFromRead(cacheEntry);
    }
  }

  public void remove(final long clusterPosition, OAtomicOperation atomicOperation) throws IOException {
    long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
    int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

    final OCacheEntry cacheEntry = loadPageForWrite(fileId, pageIndex, false);
    OClusterPositionMapBucket bucket = null;
    try {
      bucket = new OClusterPositionMapBucket(cacheEntry, false);

      bucket.remove(index);
    } finally {
      releasePageFromWrite(bucket, atomicOperation);
    }
  }

  long[] higherPositions(final long clusterPosition) throws IOException {
    if (clusterPosition == Long.MAX_VALUE) {
      return OCommonConst.EMPTY_LONG_ARRAY;
    }

    return ceilingPositions(clusterPosition + 1);
  }

  OClusterPositionEntry[] higherPositionsEntries(final long clusterPosition) throws IOException {
    long realPosition = clusterPosition + 1;
    if (clusterPosition == Long.MAX_VALUE) {
      return new OClusterPositionEntry[] {};
    }

    if (realPosition < 0) {
      realPosition = 0;
    }

    long pageIndex = realPosition / OClusterPositionMapBucket.MAX_ENTRIES;
    int index = (int) (realPosition % OClusterPositionMapBucket.MAX_ENTRIES);

    final long filledUpTo = getFilledUpTo(fileId);

    if (pageIndex >= filledUpTo) {
      return new OClusterPositionEntry[] {};
    }

    OClusterPositionEntry[] result = null;
    do {
      OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false, 1);

      OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, false);
      int resultSize = bucket.getSize() - index;

      if (resultSize <= 0) {
        releasePageFromRead(cacheEntry);
        pageIndex++;
        index = 0;
      } else {
        int entriesCount = 0;
        long startIndex = cacheEntry.getPageIndex() * OClusterPositionMapBucket.MAX_ENTRIES + index;

        result = new OClusterPositionEntry[resultSize];
        for (int i = 0; i < resultSize; i++) {
          if (bucket.exists(i + index)) {
            OClusterPositionMapBucket.PositionEntry val = bucket.get(i + index);
            assert val != null;

            result[entriesCount] = new OClusterPositionEntry(startIndex + i, val.getPageIndex(), val.getRecordPosition());
            entriesCount++;
          }
        }

        if (entriesCount == 0) {
          result = null;
          pageIndex++;
          index = 0;
        } else {
          result = Arrays.copyOf(result, entriesCount);
        }

        releasePageFromRead(cacheEntry);
      }
    } while (result == null && pageIndex < filledUpTo);

    if (result == null) {
      result = new OClusterPositionEntry[] {};
    }

    return result;
  }

  long[] ceilingPositions(long clusterPosition) throws IOException {
    if (clusterPosition < 0) {
      clusterPosition = 0;
    }

    long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
    int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

    final long filledUpTo = getFilledUpTo(fileId);

    if (pageIndex >= filledUpTo) {
      return OCommonConst.EMPTY_LONG_ARRAY;
    }

    long[] result = null;
    do {
      OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false, 1);

      OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, false);
      int resultSize = bucket.getSize() - index;

      if (resultSize <= 0) {
        releasePageFromRead(cacheEntry);
        pageIndex++;
        index = 0;
      } else {
        int entriesCount = 0;
        long startIndex = cacheEntry.getPageIndex() * OClusterPositionMapBucket.MAX_ENTRIES + index;

        result = new long[resultSize];
        for (int i = 0; i < resultSize; i++) {
          if (bucket.exists(i + index)) {
            result[entriesCount] = startIndex + i;
            entriesCount++;
          }
        }

        if (entriesCount == 0) {
          result = null;
          pageIndex++;
          index = 0;
        } else {
          result = Arrays.copyOf(result, entriesCount);
        }

        releasePageFromRead(cacheEntry);
      }
    } while (result == null && pageIndex < filledUpTo);

    if (result == null) {
      result = OCommonConst.EMPTY_LONG_ARRAY;
    }

    return result;
  }

  long[] lowerPositions(final long clusterPosition) throws IOException {
    if (clusterPosition == 0) {
      return OCommonConst.EMPTY_LONG_ARRAY;
    }

    return floorPositions(clusterPosition - 1);
  }

  long[] floorPositions(final long clusterPosition) throws IOException {
    if (clusterPosition < 0) {
      return OCommonConst.EMPTY_LONG_ARRAY;
    }

    long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
    int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

    final long filledUpTo = getFilledUpTo(fileId);
    long[] result;

    if (pageIndex >= filledUpTo) {
      pageIndex = filledUpTo - 1;
      index = Integer.MIN_VALUE;
    }

    if (pageIndex < 0) {
      return OCommonConst.EMPTY_LONG_ARRAY;
    }

    do {
      OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false, 1);

      OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, false);
      if (index == Integer.MIN_VALUE) {
        index = bucket.getSize() - 1;
      }

      int resultSize = index + 1;
      int entriesCount = 0;

      long startPosition = cacheEntry.getPageIndex() * OClusterPositionMapBucket.MAX_ENTRIES;
      result = new long[resultSize];

      for (int i = 0; i < resultSize; i++) {
        if (bucket.exists(i)) {
          result[entriesCount] = startPosition + i;
          entriesCount++;
        }
      }

      if (entriesCount == 0) {
        result = null;
        pageIndex--;
        index = Integer.MIN_VALUE;
      } else {
        result = Arrays.copyOf(result, entriesCount);
      }

      releasePageFromRead(cacheEntry);
    } while (result == null && pageIndex >= 0);

    if (result == null) {
      result = OCommonConst.EMPTY_LONG_ARRAY;
    }

    return result;
  }

  long getFirstPosition() throws IOException {
    final long filledUpTo = getFilledUpTo(fileId);
    for (long pageIndex = 0; pageIndex < filledUpTo; pageIndex++) {
      OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false, 1);
      try {
        OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, false);
        int bucketSize = bucket.getSize();

        for (int index = 0; index < bucketSize; index++) {
          if (bucket.exists(index)) {
            return pageIndex * OClusterPositionMapBucket.MAX_ENTRIES + index;
          }
        }
      } finally {
        releasePageFromRead(cacheEntry);
      }
    }

    return ORID.CLUSTER_POS_INVALID;
  }

  public byte getStatus(final long clusterPosition) throws IOException {
    final long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
    final int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

    if (pageIndex >= getFilledUpTo(fileId)) {
      return OClusterPositionMapBucket.NOT_EXISTENT;
    }

    final OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false, 1);
    try {
      final OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, false);

      return bucket.getStatus(index);

    } finally {
      releasePageFromRead(cacheEntry);
    }
  }

  public long getLastPosition() throws IOException {
    final long filledUpTo = getFilledUpTo(fileId);

    for (long pageIndex = filledUpTo - 1; pageIndex >= 0; pageIndex--) {
      OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false, 1);
      try {
        OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, false);
        final int bucketSize = bucket.getSize();

        for (int index = bucketSize - 1; index >= 0; index--) {
          if (bucket.exists(index)) {
            return pageIndex * OClusterPositionMapBucket.MAX_ENTRIES + index;
          }
        }
      } finally {
        releasePageFromRead(cacheEntry);
      }
    }

    return ORID.CLUSTER_POS_INVALID;
  }

  /**
   * Returns the next position available.
   */
  long getNextPosition() throws IOException {
    final long filledUpTo = getFilledUpTo(fileId);

    final long pageIndex = filledUpTo - 1;
    OCacheEntry cacheEntry = loadPageForRead(fileId, pageIndex, false, 1);
    try {
      OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, false);
      final int bucketSize = bucket.getSize();
      return pageIndex * OClusterPositionMapBucket.MAX_ENTRIES + bucketSize;
    } finally {
      releasePageFromRead(cacheEntry);
    }
  }

  public long getFileId() {
    return fileId;
  }

  void replaceFileId(long newFileId) {
    this.fileId = newFileId;
  }

  public static class OClusterPositionEntry {
    private final long position;
    private final long page;
    private final int  offset;

    OClusterPositionEntry(long position, long page, int offset) {
      this.position = position;
      this.page = page;
      this.offset = offset;
    }

    public long getPosition() {
      return position;
    }

    public long getPage() {
      return page;
    }

    public int getOffset() {
      return offset;
    }
  }
}
