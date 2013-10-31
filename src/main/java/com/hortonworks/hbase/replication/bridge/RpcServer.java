/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hortonworks.hbase.replication.bridge;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.hadoop.hbase94.ipc.HBaseRPCErrorHandler;
import org.apache.hadoop.hbase94.ipc.HBaseRpcMetrics;
import org.apache.hadoop.hbase94.ipc.VersionedProtocol;
import org.apache.hadoop.hbase94.monitoring.MonitoredRPCHandler;
import org.apache.hadoop.io.Writable;

import com.google.common.base.Function;

/**
 */
public interface RpcServer {

  void setSocketSendBufSize(int size);

  void start();

  void stop();

  void join() throws InterruptedException;

  InetSocketAddress getListenerAddress();

  /** Called for each call.
   * @param param writable parameter
   * @param receiveTime time
   * @return Writable
   * @throws java.io.IOException e
   */
  Writable call(Class<? extends VersionedProtocol> protocol,
      Writable param, long receiveTime, MonitoredRPCHandler status)
      throws IOException;

  void setErrorHandler(HBaseRPCErrorHandler handler);

  void setQosFunction(Function<Writable, Integer> newFunc);

  void openServer();

  void startThreads();


  /**
   * Returns the metrics instance for reporting RPC call statistics
   */
  HBaseRpcMetrics getRpcMetrics();
}
