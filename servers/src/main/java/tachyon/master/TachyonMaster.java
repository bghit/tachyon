/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.master;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.thrift.TMultiplexedProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;

import tachyon.Constants;
import tachyon.TachyonURI;
import tachyon.Version;
import tachyon.conf.TachyonConf;
import tachyon.master.block.BlockMaster;
import tachyon.master.file.FileSystemMaster;
import tachyon.master.journal.Journal;
import tachyon.master.rawtable.RawTableMaster;
import tachyon.underfs.UnderFileSystem;
import tachyon.util.network.NetworkAddressUtils;
import tachyon.util.network.NetworkAddressUtils.ServiceType;
import tachyon.web.MasterUIWebServer;
import tachyon.web.UIWebServer;

/**
 * Entry point for the Master program.
 */
public class TachyonMaster {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  public static void main(String[] args) {
    if (args.length != 0) {
      LOG.info("java -cp target/tachyon-" + Version.VERSION + "-jar-with-dependencies.jar "
          + "tachyon.Master");
      System.exit(-1);
    }

    try {
      // TODO: create a master context with the tachyon conf.
      TachyonConf conf = new TachyonConf();
      TachyonMaster master;
      if (conf.getBoolean(Constants.USE_ZOOKEEPER)) {
        // fault tolerant mode.
        master = new TachyonMasterFaultTolerant(conf);
      } else {
        master = new TachyonMaster(conf);
      }
      master.start();
    } catch (Exception e) {
      LOG.error("Uncaught exception terminating Master", e);
      System.exit(-1);
    }
  }

  protected final TachyonConf mTachyonConf;
  /** Maximum number of threads to serve the rpc server */
  private final int mMaxWorkerThreads;
  /** Minimum number of threads to serve the rpc server */
  private final int mMinWorkerThreads;
  /** The port for the RPC server */
  private final int mPort;
  /** The socket for thrift rpc server */
  private final TServerSocket mTServerSocket;
  /** The address for the rpc server */
  private final InetSocketAddress mMasterAddress;

  // The masters
  /** The master managing all block metadata */
  protected BlockMaster mBlockMaster;
  /** The master managing all file system related metadata */
  protected FileSystemMaster mFileSystemMaster;
  /** The master managing all raw table related metadata */
  protected RawTableMaster mRawTableMaster;

  // The journals for the masters
  /** The journal for the block master */
  protected final Journal mBlockMasterJournal;
  /** The journal for the file system master */
  protected final Journal mFileSystemMasterJournal;
  /** The journal for the raw table master */
  protected final Journal mRawTableMasterJournal;

  /** The web ui server */
  private UIWebServer mWebServer = null;
  /** The RPC server */
  private TServer mMasterServiceServer = null;

  /** is true if the master is serving the RPC server */
  private boolean mIsServing = false;
  /** The start time for when the master started serving the RPC server */
  private long mStartTimeMs = -1;

  public TachyonMaster(TachyonConf tachyonConf) {
    mTachyonConf = tachyonConf;

    mMinWorkerThreads = mTachyonConf.getInt(Constants.MASTER_MIN_WORKER_THREADS);
    mMaxWorkerThreads = mTachyonConf.getInt(Constants.MASTER_MAX_WORKER_THREADS);

    Preconditions.checkArgument(mMaxWorkerThreads >= mMinWorkerThreads,
        Constants.MASTER_MAX_WORKER_THREADS + " can not be less than "
            + Constants.MASTER_MIN_WORKER_THREADS);

    try {
      // Extract the port from the generated socket.
      // When running tests, it is fine to use port '0' so the system will figure out what port to
      // use (any random free port).
      // In a production or any real deployment setup, port '0' should not be used as it will make
      // deployment more complicated.
      mTServerSocket = new TServerSocket(
          NetworkAddressUtils.getBindAddress(ServiceType.MASTER_RPC, mTachyonConf));
      mPort = NetworkAddressUtils.getThriftPort(mTServerSocket);
      // reset master port
      mTachyonConf.set(Constants.MASTER_PORT, Integer.toString(mPort));
      mMasterAddress = NetworkAddressUtils.getConnectAddress(ServiceType.MASTER_RPC, mTachyonConf);

      // Check the journal directory
      String journalDirectory = mTachyonConf.get(Constants.MASTER_JOURNAL_FOLDER);
      if (!journalDirectory.endsWith(TachyonURI.SEPARATOR)) {
        journalDirectory += TachyonURI.SEPARATOR;
      }
      Preconditions.checkState(isJournalFormatted(journalDirectory),
          "Tachyon was not formatted! The journal folder is " + journalDirectory);

      // Create the journals.
      mBlockMasterJournal = new Journal(BlockMaster.getJournalDirectory(journalDirectory),
          mTachyonConf);
      mFileSystemMasterJournal = new Journal(FileSystemMaster.getJournalDirectory(journalDirectory),
          mTachyonConf);
      mRawTableMasterJournal = new Journal(RawTableMaster.getJournalDirectory(journalDirectory),
          mTachyonConf);

      mBlockMaster = new BlockMaster(mTachyonConf, mBlockMasterJournal);
      mFileSystemMaster =
          new FileSystemMaster(mTachyonConf, mBlockMaster, mFileSystemMasterJournal);
      mRawTableMaster = new RawTableMaster(mTachyonConf, mFileSystemMaster, mRawTableMasterJournal);

      // TODO: implement metrics.
    } catch (Exception e) {
      LOG.error(e.getMessage(), e);
      throw Throwables.propagate(e);
    }
  }

  /**
   * @return the underlying {@link TachyonConf} instance for the master.
   */
  public TachyonConf getTachyonConf() {
    return mTachyonConf;
  }

  /**
   * @return the externally resolvable address of this master.
   */
  public InetSocketAddress getMasterAddress() {
    return mMasterAddress;
  }

  /**
   * @return the actual bind hostname on RPC service (used by unit test only).
   */
  public String getRPCBindHost() {
    return NetworkAddressUtils.getThriftSocket(mTServerSocket).getLocalSocketAddress().toString();
  }

  /**
   * @return the actual port that the RPC service is listening on (used by unit test only)
   */
  public int getRPCLocalPort() {
    return mPort;
  }

  /**
   * @return the actual bind hostname on web service (used by unit test only).
   */
  public String getWebBindHost() {
    return mWebServer.getBindHost();
  }

  /**
   * @return the actual port that the web service is listening on (used by unit test only)
   */
  public int getWebLocalPort() {
    return mWebServer.getLocalPort();
  }

  /**
   * @return internal {@link FileSystemMaster}, for unit test only.
   */
  public FileSystemMaster getFileSystemMaster() {
    return mFileSystemMaster;
  }

  /**
   * @return internal {@link RawTableMaster}, for unit test only.
   */
  public RawTableMaster getRawTableMaster() {
    return mRawTableMaster;
  }

  /**
   * @return internal {@link BlockMaster}, for unit test only.
   */
  public BlockMaster getBlockMaster() {
    return mBlockMaster;
  }

  /**
   * @return the millisecond when Tachyon Master starts serving, return -1 when not started.
   */
  public long getStarttimeMs() {
    return mStartTimeMs;
  }

  /**
   * @return true if the system is the leader (serving the rpc server), false otherwise.
   */
  boolean isServing() {
    return mIsServing;
  }

  /**
   * Starts the Tachyon master server.
   */
  public void start() throws Exception {
    startMasters(true);
    startServing();
  }

  /**
   * Stops the Tachyon master server. Should only be called by tests.
   */
  public void stop() throws Exception {
    if (mIsServing) {
      LOG.info("Stopping Tachyon Master @ " + mMasterAddress);
      stopServing();
      stopMasters();
      mTServerSocket.close();
      mIsServing = false;
    }
  }

  protected void startMasters(boolean isLeader) {
    try {
      connectToUFS();

      mBlockMaster.start(isLeader);
      mFileSystemMaster.start(isLeader);
      mRawTableMaster.start(isLeader);

    } catch (IOException e) {
      LOG.error(e.getMessage(), e);
      throw Throwables.propagate(e);
    }
  }

  protected void stopMasters() {
    try {
      mBlockMaster.stop();
      mFileSystemMaster.stop();
      mRawTableMaster.stop();
    } catch (IOException e) {
      LOG.error(e.getMessage(), e);
      throw Throwables.propagate(e);
    }
  }

  private void startServing() {
    startServingWebServer();
    LOG.info("Tachyon Master version " + Version.VERSION + " started @ " + mMasterAddress);
    startServingRPCServer();
    LOG.info("Tachyon Master version " + Version.VERSION + " ended @ " + mMasterAddress);
  }

  protected void startServingWebServer() {
    // start web ui
    mWebServer = new MasterUIWebServer(ServiceType.MASTER_WEB, NetworkAddressUtils.getBindAddress(
        ServiceType.MASTER_WEB, mTachyonConf), this, mTachyonConf);
    mWebServer.startWebServer();
  }

  protected void startServingRPCServer() {
    // set up multiplexed thrift processors
    TMultiplexedProcessor processor = new TMultiplexedProcessor();
    processor.registerProcessor(mBlockMaster.getServiceName(), mBlockMaster.getProcessor());
    processor.registerProcessor(mFileSystemMaster.getServiceName(),
        mFileSystemMaster.getProcessor());
    processor.registerProcessor(mRawTableMaster.getServiceName(), mRawTableMaster.getProcessor());

    // create master thrift service with the multiplexed processor.
    mMasterServiceServer = new TThreadPoolServer(new TThreadPoolServer.Args(mTServerSocket)
        .maxWorkerThreads(mMaxWorkerThreads).minWorkerThreads(mMinWorkerThreads)
        .processor(processor).transportFactory(new TFramedTransport.Factory())
        .protocolFactory(new TBinaryProtocol.Factory(true, true)));

    // start thrift rpc server
    mIsServing = true;
    mStartTimeMs = System.currentTimeMillis();
    mMasterServiceServer.serve();
  }

  protected void stopServing() throws Exception {
    if (mMasterServiceServer != null) {
      mMasterServiceServer.stop();
      mMasterServiceServer = null;
    }
    if (mWebServer != null) {
      mWebServer.shutdownWebServer();
      mWebServer = null;
    }
    mIsServing = false;
  }

  /**
   * Checks to see if the journal directory is formatted.
   *
   * @param journalDirectory The journal directory to check
   * @return true if the journal directory was formatted previously, false otherwise
   * @throws IOException
   */
  private boolean isJournalFormatted(String journalDirectory) throws IOException {
    UnderFileSystem ufs = UnderFileSystem.get(journalDirectory, mTachyonConf);
    if (!ufs.providesStorage()) {
      // TODO: Should the journal really be allowed on a ufs without storage?
      // This ufs doesn't provide storage. Allow the master to use this ufs for the journal.
      LOG.info("Journal directory doesn't provide storage: " + journalDirectory);
      return true;
    }
    String[] files = ufs.list(journalDirectory);
    if (files == null) {
      return false;
    }
    // Search for the format file.
    String formatFilePrefix = mTachyonConf.get(Constants.MASTER_FORMAT_FILE_PREFIX);
    for (String file : files) {
      if (file.startsWith(formatFilePrefix)) {
        return true;
      }
    }
    return false;
  }

  private void connectToUFS() throws IOException {
    String ufsAddress = mTachyonConf.get(Constants.UNDERFS_ADDRESS);
    UnderFileSystem ufs = UnderFileSystem.get(ufsAddress, mTachyonConf);
    ufs.connectFromMaster(mTachyonConf,
        NetworkAddressUtils.getConnectHost(ServiceType.MASTER_RPC, mTachyonConf));
  }
}
