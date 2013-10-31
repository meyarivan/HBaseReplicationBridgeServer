/**
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
import java.net.InetSocketAddress;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase94.HBaseConfiguration;
import org.apache.hadoop.hbase94.HConstants;
import org.apache.hadoop.hbase94.client.HConnectionManager;
import org.apache.hadoop.hbase94.ipc.HBaseRPCErrorHandler;
import org.apache.hadoop.hbase94.ipc.HRegionInterface;
import org.apache.hadoop.hbase94.regionserver.wal.HLog;
import org.apache.hadoop.hbase94.security.User;
import org.apache.hadoop.hbase94.util.Bytes;
import org.apache.hadoop.hbase94.util.Strings;
import org.apache.hadoop.hbase94.util.VersionInfo;
import org.apache.hadoop.hbase94.zookeeper.ZKUtil;
import org.apache.hadoop.hbase94.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.net.DNS;
import org.apache.zookeeper.KeeperException;

public class ReplicationBridgeServer extends BaseHRegionServer implements HBaseRPCErrorHandler {

  public final static int BRIDGE_SERVER_PORT = 60070;
  public final static String ROOT_ZNODE = "/hbase96";
  
  private final Configuration conf;
  private RpcServer rpcServer;

  // Our zk client.
  private ZooKeeperWatcher zooKeeper;
  private  InetSocketAddress isa;
  private  int port = 0;

  volatile boolean running = false;
  volatile boolean abortRequested = false;
  volatile boolean stopped = false;
  private Object stopRequester = new Object(); 
  private Log LOG = LogFactory.getLog(getClass());
  private ReplicationSink replicationHandler = null;
  
  /**
   * Starts a HRegionServer at the default location
   *
   * @param conf
   * @throws IOException
   * @throws InterruptedException
   * @throws KeeperException 
   * @throws ZkConnectException 
   */
  public ReplicationBridgeServer(Configuration conf)
  throws IOException, InterruptedException, KeeperException {
    this.conf = conf;
    
    // Set how many times to retry talking to another server over HConnection.
    HConnectionManager.setServerSideHConnectionRetries(this.conf, LOG);

    // Server to handle client requests.
    String hostname = conf.get("hbase.regionserver.ipc.address",
      Strings.domainNamePointerToHostName(DNS.getDefaultHost(
        conf.get("hbase.regionserver.dns.interface", "default"),
        conf.get("hbase.regionserver.dns.nameserver", "default"))));
    port = conf.getInt("hbase.bridge.server.port", BRIDGE_SERVER_PORT);
    // Creation of a HSA will force a resolve.
    InetSocketAddress initialIsa = new InetSocketAddress(hostname, port);
    if (initialIsa.getAddress() == null) {
      throw new IllegalArgumentException("Failed resolve of " + initialIsa);
    }
    
    this.rpcServer = HBaseRPC.getServer(this,
      new Class<?>[]{HRegionInterface.class},
        initialIsa.getHostName(), // BindAddress is IP we got for this server.
        initialIsa.getPort(),
        conf.getInt("hbase.regionserver.handler.count", 10),
        conf.getInt("hbase.regionserver.metahandler.count", 10),
        conf.getBoolean("hbase.rpc.verbose", false),
        conf, HConstants.QOS_THRESHOLD);
  }
  
  /**
   * @see org.apache.hadoop.hbase.regionserver.HRegionServerCommandLine
   */
  public static void main(String[] args) throws Exception {
    VersionInfo.logVersion();
    Configuration conf = HBaseConfiguration.create();
    ReplicationBridgeServer svr = new ReplicationBridgeServer(conf);
    svr.run();
  }
  
  /**
   * Cause the server to exit without closing the regions it is serving, the log
   * it is using and without notifying the master. Used unit testing and on
   * catastrophic events such as HDFS is yanked out from under hbase or we OOME.
   *
   * @param reason
   *          the reason we are aborting
   * @param cause
   *          the exception that caused the abort, or null
   */
  public void abort(String reason, Throwable cause) {
    String msg = "ABORTING region server " + this + ": " + reason;
    if (cause != null) {
      LOG.fatal(msg, cause);
    } else {
      LOG.fatal(msg);
    }
    this.abortRequested = true;
    stop(reason);
  }
  
  @Override
  public void stop(final String msg) {
    try {
      this.running = false;
      synchronized(stopRequester){
        stopRequester.notifyAll();
      }
      LOG.info("STOPPED: " + msg);
      // wait a little bit for main thread exits
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      // ignore
    }
    this.stopped = true;
  }
  
  /**
   * The HRegionServer sticks in this loop until closed.
   */
  public void run() {
    try {
      // create replication handler
      replicationHandler = new ReplicationSink(this.conf);
      this.conf.set("hbase.zookeeper.quorum", this.conf.get("hbase.bridge.source.zookeeper.quorum"));
      
      // starts RPC server
      this.rpcServer.start();
      
      // Set our address.
      this.isa = this.rpcServer.getListenerAddress();
      port = this.isa.getPort();
      this.rpcServer.setErrorHandler(this);

      // login the zookeeper client principal (if using security)
      ZKUtil.loginClient(this.conf, "hbase.zookeeper.client.keytab.file",
        "hbase.zookeeper.client.kerberos.principal", this.isa.getHostName());

      // login the server principal (if using secure Hadoop)
      User.login(this.conf, "hbase.regionserver.keytab.file",
        "hbase.regionserver.kerberos.principal", this.isa.getHostName());  
      
      this.zooKeeper = new ZooKeeperWatcher(conf, this.isa + ":" + isa.getPort(), this, true);
      
      String parentZNode = conf.get("hbase.bridge.server.root.znode", ROOT_ZNODE);
      String serverName = this.isa.getHostName() + "," + port + "," + System.currentTimeMillis();
      String zkNodePath = ZKUtil.joinZNode(parentZNode, "rs");
      // create necessary parent znode
      ZKUtil.createWithParents(zooKeeper, zkNodePath);
      
      // register ourself
      zkNodePath = ZKUtil.joinZNode(zkNodePath, serverName);
      ZKUtil.createEphemeralNodeAndWatch(zooKeeper, zkNodePath, null);
      String hbaseidZnode = ZKUtil.joinZNode(parentZNode, "hbaseid");
      if(ZKUtil.checkExists(zooKeeper,hbaseidZnode) == -1){
        ZKUtil.createSetData(zooKeeper, hbaseidZnode, Bytes.toBytes(UUID.randomUUID().toString()));
      }
      
      this.running = true;
      while(this.running) {
        synchronized(stopRequester){
          stopRequester.wait();
        }
      }
    } catch (Throwable t) {
      if (!checkOOME(t)) {
        abort("Unhandled exception: " + t.getMessage(), t);
      }
    }
    this.rpcServer.stop();
    this.replicationHandler.stopReplicationSinkServices();
    
    LOG.info(this.getClass().getName() + " exiting");
  }
  
  
  /*
   * Check if an OOME and, if so, abort immediately to avoid creating more objects.
   *
   * @param e
   *
   * @return True if we OOME'd and are aborting.
   */
  public boolean checkOOME(final Throwable e) {
    boolean stop = false;
    try {
      if (e instanceof OutOfMemoryError
          || (e.getCause() != null && e.getCause() instanceof OutOfMemoryError)
          || (e.getMessage() != null && e.getMessage().contains(
              "java.lang.OutOfMemoryError"))) {
        stop = true;
        LOG.fatal(
          "Run out of memory; HRegionServer will abort itself immediately", e);
      }
    } finally {
      if (stop) {
        Runtime.getRuntime().halt(1);
      }
    }
    return stop;
  }
 
  @Override
  public void replicateLogEntries(HLog.Entry[] entries) throws IOException {
    this.replicationHandler.replicateEntries(entries);
  }

}
