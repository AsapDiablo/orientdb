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

package com.orientechnologies.orient.core.storage.index.hashindex.local;

import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurableComponent;

import java.io.IOException;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 5/14/14
 */
public class OHashTableDirectory extends ODurableComponent {
  static final int ITEM_SIZE = OLongSerializer.LONG_SIZE;

  private static final int LEVEL_SIZE = OLocalHashTable.MAX_LEVEL_SIZE;

  static final int BINARY_LEVEL_SIZE = LEVEL_SIZE * ITEM_SIZE + 3 * OByteSerializer.BYTE_SIZE;

  private long fileId;

  private final long firstEntryIndex;

  OHashTableDirectory(String defaultExtension, String name, String lockName, OAbstractPaginatedStorage storage) {
    super(storage, name, defaultExtension, lockName);
    this.firstEntryIndex = 0;
  }

  public void create() throws IOException {
    fileId = addFile(getFullName());
    init();
  }

  private void init() throws IOException {
    OCacheEntry firstEntry = loadPageForWrite(fileId, firstEntryIndex, true);

    if (firstEntry == null) {
      firstEntry = addPage(fileId, false);
      assert firstEntry.getPageIndex() == 0;
    }

    pinPage(firstEntry);

    ODirectoryFirstPage firstPage = null;
    try {
      firstPage = new ODirectoryFirstPage(firstEntry, firstEntry);

      firstPage.setTreeSize(0);
      firstPage.setTombstone(-1);

    } finally {
      releasePageFromWrite(firstPage);
    }
  }

  public void open() throws IOException {
    fileId = openFile(getFullName());
    final int filledUpTo = (int) getFilledUpTo(fileId);

    for (int i = 0; i < filledUpTo; i++) {
      final OCacheEntry entry = loadPageForRead(fileId, i, true);
      assert entry != null;

      pinPage(entry);
      releasePageFromRead(entry);
    }
  }

  public void close() throws IOException {
    readCache.closeFile(fileId, true, writeCache);
  }

  public void delete() throws IOException {
    deleteFile(fileId);
  }

  void deleteWithoutOpen() throws IOException {
    if (isFileExists(getFullName())) {
      fileId = openFile(getFullName());
      deleteFile(fileId);
    }
  }

  int addNewNode(byte maxLeftChildDepth, byte maxRightChildDepth, byte nodeLocalDepth, long[] newNode) throws IOException {
    int nodeIndex;
    ODirectoryFirstPage firstPage = null;
    OCacheEntry firstEntry = loadPageForWrite(fileId, firstEntryIndex, true);
    try {
      firstPage = new ODirectoryFirstPage(firstEntry, firstEntry);

      final int tombstone = firstPage.getTombstone();

      if (tombstone >= 0) {
        nodeIndex = tombstone;
      } else {
        nodeIndex = firstPage.getTreeSize();
        firstPage.setTreeSize(nodeIndex + 1);
      }

      if (nodeIndex < ODirectoryFirstPage.NODES_PER_PAGE) {
        final int localNodeIndex = nodeIndex;

        firstPage.setMaxLeftChildDepth(localNodeIndex, maxLeftChildDepth);
        firstPage.setMaxRightChildDepth(localNodeIndex, maxRightChildDepth);
        firstPage.setNodeLocalDepth(localNodeIndex, nodeLocalDepth);

        if (tombstone >= 0) {
          firstPage.setTombstone((int) firstPage.getPointer(nodeIndex, 0));
        }

        for (int i = 0; i < newNode.length; i++) {
          firstPage.setPointer(localNodeIndex, i, newNode[i]);
        }

      } else {
        final int pageIndex = nodeIndex / ODirectoryPage.NODES_PER_PAGE;
        final int localLevel = nodeIndex % ODirectoryPage.NODES_PER_PAGE;

        OCacheEntry cacheEntry = loadPageForWrite(fileId, pageIndex, true);
        while (cacheEntry == null || cacheEntry.getPageIndex() < pageIndex) {
          if (cacheEntry != null) {
            releaseEntryFromWrite(cacheEntry);
          }

          cacheEntry = addPage(fileId, false);
        }

        ODirectoryPage page = null;
        try {
          page = new ODirectoryPage(cacheEntry, cacheEntry);

          page.setMaxLeftChildDepth(localLevel, maxLeftChildDepth);
          page.setMaxRightChildDepth(localLevel, maxRightChildDepth);
          page.setNodeLocalDepth(localLevel, nodeLocalDepth);

          if (tombstone >= 0) {
            firstPage.setTombstone((int) page.getPointer(localLevel, 0));
          }

          for (int i = 0; i < newNode.length; i++) {
            page.setPointer(localLevel, i, newNode[i]);
          }

        } finally {
          releasePageFromWrite(page);
        }
      }

    } finally {
      releasePageFromWrite(firstPage);
    }

    return nodeIndex;
  }

  void deleteNode(int nodeIndex) throws IOException {
    ODirectoryFirstPage firstPage = null;
    OCacheEntry firstEntry = loadPageForWrite(fileId, firstEntryIndex, true);
    try {
      firstPage = new ODirectoryFirstPage(firstEntry, firstEntry);
      if (nodeIndex < ODirectoryFirstPage.NODES_PER_PAGE) {
        firstPage.setPointer(nodeIndex, 0, firstPage.getTombstone());
        firstPage.setTombstone(nodeIndex);
      } else {
        final int pageIndex = nodeIndex / ODirectoryPage.NODES_PER_PAGE;
        final int localNodeIndex = nodeIndex % ODirectoryPage.NODES_PER_PAGE;

        ODirectoryPage page = null;
        final OCacheEntry cacheEntry = loadPageForWrite(fileId, pageIndex, true);
        try {
          page = new ODirectoryPage(cacheEntry, cacheEntry);

          page.setPointer(localNodeIndex, 0, firstPage.getTombstone());
          firstPage.setTombstone(nodeIndex);

        } finally {
          releasePageFromWrite(page);
        }
      }
    } finally {
      releasePageFromWrite(firstPage);
    }
  }

  byte getMaxLeftChildDepth(int nodeIndex) throws IOException {
    final ODirectoryPage page = loadPage(nodeIndex, false);
    try {
      return page.getMaxLeftChildDepth(getLocalNodeIndex(nodeIndex));
    } finally {
      releasePage(page, false);
    }
  }

  void setMaxLeftChildDepth(int nodeIndex, byte maxLeftChildDepth) throws IOException {
    final ODirectoryPage page = loadPage(nodeIndex, true);
    try {
      page.setMaxLeftChildDepth(getLocalNodeIndex(nodeIndex), maxLeftChildDepth);
    } finally {
      releasePage(page, true);
    }
  }

  byte getMaxRightChildDepth(int nodeIndex) throws IOException {
    final ODirectoryPage page = loadPage(nodeIndex, false);
    try {
      return page.getMaxRightChildDepth(getLocalNodeIndex(nodeIndex));
    } finally {
      releasePage(page, false);
    }
  }

  void setMaxRightChildDepth(int nodeIndex, byte maxRightChildDepth) throws IOException {
    final ODirectoryPage page = loadPage(nodeIndex, true);
    try {
      page.setMaxRightChildDepth(getLocalNodeIndex(nodeIndex), maxRightChildDepth);
    } finally {
      releasePage(page, true);
    }
  }

  byte getNodeLocalDepth(int nodeIndex) throws IOException {
    final ODirectoryPage page = loadPage(nodeIndex, false);
    try {
      return page.getNodeLocalDepth(getLocalNodeIndex(nodeIndex));
    } finally {
      releasePage(page, false);
    }
  }

  void setNodeLocalDepth(int nodeIndex, byte localNodeDepth) throws IOException {
    final ODirectoryPage page = loadPage(nodeIndex, true);
    try {
      page.setNodeLocalDepth(getLocalNodeIndex(nodeIndex), localNodeDepth);
    } finally {
      releasePage(page, true);
    }
  }

  long[] getNode(int nodeIndex) throws IOException {
    final long[] node = new long[LEVEL_SIZE];
    final ODirectoryPage page = loadPage(nodeIndex, false);

    try {
      final int localNodeIndex = getLocalNodeIndex(nodeIndex);
      for (int i = 0; i < LEVEL_SIZE; i++) {
        node[i] = page.getPointer(localNodeIndex, i);
      }
    } finally {
      releasePage(page, false);
    }

    return node;
  }

  void setNode(int nodeIndex, long[] node) throws IOException {
    final ODirectoryPage page = loadPage(nodeIndex, true);
    try {
      final int localNodeIndex = getLocalNodeIndex(nodeIndex);
      for (int i = 0; i < LEVEL_SIZE; i++) {
        page.setPointer(localNodeIndex, i, node[i]);
      }
    } finally {
      releasePage(page, true);
    }
  }

  long getNodePointer(int nodeIndex, int index) throws IOException {
    final ODirectoryPage page = loadPage(nodeIndex, false);
    try {
      return page.getPointer(getLocalNodeIndex(nodeIndex), index);
    } finally {
      releasePage(page, false);
    }
  }

  void setNodePointer(int nodeIndex, int index, long pointer) throws IOException {
    final ODirectoryPage page = loadPage(nodeIndex, true);
    try {
      page.setPointer(getLocalNodeIndex(nodeIndex), index, pointer);
    } finally {
      releasePage(page, true);
    }
  }

  public void clear() throws IOException {
    truncateFile(fileId);

    init();
  }

  public void flush() {
    writeCache.flush(fileId);
  }

  private ODirectoryPage loadPage(int nodeIndex, boolean exclusiveLock) throws IOException {
    if (nodeIndex < ODirectoryFirstPage.NODES_PER_PAGE) {
      OCacheEntry cacheEntry;

      if (exclusiveLock) {
        cacheEntry = loadPageForWrite(fileId, firstEntryIndex, true);
      } else {
        cacheEntry = loadPageForRead(fileId, firstEntryIndex, true);
      }

      return new ODirectoryFirstPage(cacheEntry, cacheEntry);
    }

    final int pageIndex = nodeIndex / ODirectoryPage.NODES_PER_PAGE;

    final OCacheEntry cacheEntry;

    if (exclusiveLock) {
      cacheEntry = loadPageForWrite(fileId, pageIndex, true);
    } else {
      cacheEntry = loadPageForRead(fileId, pageIndex, true);
    }

    return new ODirectoryPage(cacheEntry, cacheEntry);
  }

  private void releasePage(ODirectoryPage page, boolean exclusiveLock) throws IOException {
    final OCacheEntry cacheEntry = page.getEntry();

    if (exclusiveLock) {
      releasePageFromWrite(page);
    } else {
      releasePageFromRead(cacheEntry);
    }

  }

  private static int getLocalNodeIndex(int nodeIndex) {
    if (nodeIndex < ODirectoryFirstPage.NODES_PER_PAGE) {
      return nodeIndex;
    }

    return (nodeIndex - ODirectoryFirstPage.NODES_PER_PAGE) % ODirectoryPage.NODES_PER_PAGE;
  }
}
