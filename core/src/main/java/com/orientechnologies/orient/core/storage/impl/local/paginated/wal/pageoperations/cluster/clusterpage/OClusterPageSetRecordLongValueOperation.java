package com.orientechnologies.orient.core.storage.impl.local.paginated.wal.pageoperations.cluster.clusterpage;

import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.cluster.OClusterPage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OPageOperationRecord;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.WALRecordTypes;

import java.nio.ByteBuffer;

public final class OClusterPageSetRecordLongValueOperation extends OPageOperationRecord<OClusterPage> {
  private int recordPosition;
  private int recordOffset;

  private long value;
  private long oldValue;

  public OClusterPageSetRecordLongValueOperation() {
  }

  public OClusterPageSetRecordLongValueOperation(int recordPosition, int recordOffset, long value, long oldValue) {
    this.recordPosition = recordPosition;
    this.recordOffset = recordOffset;
    this.value = value;
    this.oldValue = oldValue;
  }

  public int getRecordPosition() {
    return recordPosition;
  }

  int getRecordOffset() {
    return recordOffset;
  }

  public long getValue() {
    return value;
  }

  public long getOldValue() {
    return oldValue;
  }

  @Override
  protected OClusterPage createPageInstance(OCacheEntry cacheEntry) {
    return new OClusterPage(cacheEntry, false);
  }

  @Override
  protected void doRedo(OClusterPage clusterPage) {
    clusterPage.setRecordLongValue(recordPosition, recordOffset, value);
  }

  @Override
  protected void doUndo(OClusterPage clusterPage) {
    clusterPage.setRecordLongValue(recordPosition, recordOffset, oldValue);
  }

  @Override
  public boolean isUpdateMasterRecord() {
    return false;
  }

  @Override
  public byte getId() {
    return WALRecordTypes.CLUSTER_SET_RECORD_LONG_VALUE;
  }

  @Override
  public int toStream(byte[] content, int offset) {
    offset = super.toStream(content, offset);

    OIntegerSerializer.INSTANCE.serializeNative(recordPosition, content, offset);
    offset += OIntegerSerializer.INT_SIZE;

    OIntegerSerializer.INSTANCE.serializeNative(recordOffset, content, offset);
    offset += OIntegerSerializer.INT_SIZE;

    OLongSerializer.INSTANCE.serializeNative(value, content, offset);
    offset += OLongSerializer.LONG_SIZE;

    OLongSerializer.INSTANCE.serializeNative(oldValue, content, offset);
    offset += OLongSerializer.LONG_SIZE;

    return offset;
  }

  @Override
  public void toStream(ByteBuffer buffer) {
    super.toStream(buffer);

    buffer.putInt(recordPosition);
    buffer.putInt(recordOffset);

    buffer.putLong(value);
    buffer.putLong(oldValue);
  }

  @Override
  public int fromStream(byte[] content, int offset) {
    offset = super.fromStream(content, offset);

    recordPosition = OIntegerSerializer.INSTANCE.deserializeNative(content, offset);
    offset += OIntegerSerializer.INT_SIZE;

    recordOffset = OIntegerSerializer.INSTANCE.deserializeNative(content, offset);
    offset += OIntegerSerializer.INT_SIZE;

    value = OLongSerializer.INSTANCE.deserializeNative(content, offset);
    offset += OLongSerializer.LONG_SIZE;

    oldValue = OLongSerializer.INSTANCE.deserializeNative(content, offset);
    offset += OLongSerializer.LONG_SIZE;

    return offset;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + 2 * OIntegerSerializer.INT_SIZE + 2 * OLongSerializer.LONG_SIZE;
  }
}
