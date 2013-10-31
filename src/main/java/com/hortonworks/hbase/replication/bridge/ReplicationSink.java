/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hortonworks.hbase.replication.bridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase94.HConstants;
import org.apache.hadoop.hbase94.KeyValue;
import org.apache.hadoop.hbase94.regionserver.wal.HLog;
import org.apache.hadoop.hbase94.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase94.util.Bytes;

/**
 * This class is responsible for replicating the edits coming
 * from another cluster.
 * <p/>
 * This replication process is currently waiting for the edits to be applied
 * before the method can return. This means that the replication of edits
 * is synchronized (after reading from HLogs in ReplicationSource) and that a
 * single region server cannot receive edits from two sources at the same time
 * <p/>
 * This class uses the native HBase client in order to replicate entries.
 * <p/>
 *
 */
public class ReplicationSink {

  private static final Log LOG = LogFactory.getLog(ReplicationSink.class);
  private final Configuration conf;
  private final HConnection sharedHtableCon;
  private final AtomicLong totalReplicatedEdits = new AtomicLong();

  /**
   * Create a sink for replication
   *
   * @param conf                conf object
   * @throws IOException thrown when HDFS goes bad or bad file name
   */
  public ReplicationSink(Configuration conf)
      throws IOException {
    this.conf = HBaseConfiguration.create(conf);
    decorateConf();
    this.sharedHtableCon = HConnectionManager.createConnection(this.conf);
  }

  /**
   * decorate the Configuration object to make replication more receptive to delays:
   * lessen the timeout and numTries.
   */
  private void decorateConf() {
    this.conf.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER,
        this.conf.getInt("replication.sink.client.retries.number", 4));
    this.conf.setInt(HConstants.HBASE_CLIENT_OPERATION_TIMEOUT,
        this.conf.getInt("replication.sink.client.ops.timeout", 10000));
   }

  /**
   * Replicate this array of entries directly into the local cluster using the native client. Only
   * operates against raw protobuf type saving on a conversion from pb to pojo.
   * @param entries
   * @param cells
   * @throws IOException
   */
  public void replicateEntries(HLog.Entry[] entries) throws IOException {
    if (entries.length == 0) {
      return;
    }
    try {
      long totalReplicated = 0;
      // Map of table => list of Rows
      Map<byte[], List<Row>> rowMap = new TreeMap<byte[], List<Row>>(Bytes.BYTES_COMPARATOR);
      for (HLog.Entry entry : entries) {
        WALEdit edit = entry.getEdit();
        byte[] table = entry.getKey().getTablename();
        if(Bytes.equals(table, HConstants.ROOT_TABLE_NAME) || 
           Bytes.equals(table,  HConstants.META_TABLE_NAME)){
          continue;
        }
        Put put = null;
        Delete del = null;
        KeyValue lastKV = null;
        List<KeyValue> kvs = edit.getKeyValues();
        for (KeyValue kv : kvs) {
          // filtering HLog meta entries
          if (kv.matchingFamily(HLog.METAFAMILY)) continue;
          
          // convert to KeyValue in HBase96 version
          org.apache.hadoop.hbase.KeyValue kv96 = new org.apache.hadoop.hbase.KeyValue(kv.getBuffer());
          if (lastKV == null || lastKV.getType() != kv.getType() || !lastKV.matchingRow(kv)) {
            if (kv.isDelete()) {
              del = new Delete(kv.getRow());
              addToHashMultiMap(rowMap, table, del);
            } else {
              put = new Put(kv.getRow());
              addToHashMultiMap(rowMap, table, put);
            }
          }
          if (kv.isDelete()) {
            del.addDeleteMarker(kv96);
          } else {
            put.add(kv96);
          }
          lastKV = kv;
        }
        totalReplicated++;
      }
      for(Map.Entry<byte[], List<Row>> entry : rowMap.entrySet()) {
        batch(entry.getKey(), entry.getValue());
      };

      LOG.info("Total replicated: " + totalReplicated);
      this.totalReplicatedEdits.addAndGet(totalReplicated);
    } catch (IOException ex) {
      LOG.error("Unable to accept edit because:", ex);
      throw ex;
    }
  }

  /**
   * Simple helper to a map from key to (a list of) values
   * TODO: Make a general utility method
   * @param map
   * @param key1
   * @param key2
   * @param value
   * @return the list of values corresponding to key1 and key2
   */
  private <K1,V> List<V> addToHashMultiMap(Map<K1, List<V>> map, K1 key1, V value) {
    List<V> values = map.get(key1);
    if (values == null) {
      values = new ArrayList<V>();
      map.put(key1, values);
    }
    values.add(value);
    return values;
  }
  
  /**
   * stop the thread pool executor. It is called when the regionserver is stopped.
   */
  public void stopReplicationSinkServices() {
    try {
      this.sharedHtableCon.close();
    } catch (IOException e) {
      LOG.warn("IOException while closing the connection", e); // ignoring as we are closing.
    }
  }


  /**
   * Do the changes and handle the pool
   * @param tableName table to insert into
   * @param rows list of actions
   * @throws IOException
   */
  private void batch(byte[] tableName, List<Row> rows) throws IOException {
    if (rows.isEmpty()) {
      return;
    }
    HTableInterface table = null;
    try {
      table = this.sharedHtableCon.getTable(tableName);
      table.batch(rows);
    } catch (InterruptedException ix) {
      throw new IOException(ix);
    } finally {
      if (table != null) {
        table.close();
      }
    }
  }
}
