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

package org.apache.hadoop.hdfs.server.datanode;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.*;
import org.apache.hadoop.hdfs.protocol.BlockLocalPathInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.tools.DFSAdmin;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.apache.hadoop.hdfs.MiniDFSCluster.*;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

/**
 * Ensure that the DataNode correctly handles rolling upgrade
 * finalize and rollback.
 */
public class TestDataNodeRollingUpgrade {
  private static final Log LOG = LogFactory.getLog(TestDataNodeRollingUpgrade.class);

  private static final short REPL_FACTOR = 1;
  private static final int BLOCK_SIZE = 1024 * 1024;
  private static final long FILE_SIZE = BLOCK_SIZE;
  private static final long SEED = 0x1BADF00DL;

  Configuration conf;
  MiniDFSCluster cluster = null;
  DistributedFileSystem fs = null;
  DataNode dn = null;
  NameNode nn = null;
  String blockPoolId = null;

  private void startCluster() throws IOException {
    conf = new HdfsConfiguration();
    cluster = new Builder(conf).numDataNodes(REPL_FACTOR).build();
    cluster.waitActive();
    fs = cluster.getFileSystem();
    nn = cluster.getNameNode(0);
    assertNotNull(nn);
    dn = cluster.getDataNodes().get(0);
    assertNotNull(dn);
    blockPoolId = cluster.getNameNode(0).getNamesystem().getBlockPoolId();
  }

  private void shutdownCluster() {
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
    fs = null;
    nn = null;
    dn = null;
    blockPoolId = null;
  }

  private void triggerHeartBeats() throws Exception {
    // Sleep briefly so that DN learns of the rolling upgrade
    // state and other states from heartbeats.
    cluster.triggerHeartbeats();
    Thread.sleep(5000);
  }

  /** Test assumes that the file has a single block */
  private File getBlockForFile(Path path, boolean exists) throws IOException {
    LocatedBlocks blocks = nn.getRpcServer().getBlockLocations(path.toString(),
        0, Long.MAX_VALUE);
    assertEquals(1, blocks.getLocatedBlocks().size());
    ExtendedBlock block = blocks.getLocatedBlocks().get(0).getBlock();
    BlockLocalPathInfo bInfo = dn.getFSDataset().getBlockLocalPathInfo(block);
    File blockFile = new File(bInfo.getBlockPath());
    assertEquals(exists, blockFile.exists());
    return blockFile;
  }

  private File getTrashFileForBlock(File blockFile, boolean exists) {
    File trashFile = new File(
        dn.getStorage().getTrashDirectoryForBlockFile(blockPoolId, blockFile));
    assertEquals(exists, trashFile.exists());
    return trashFile;
  }

  /**
   * Ensures that the blocks belonging to the deleted file are in trash
   */
  private void deleteAndEnsureInTrash(Path pathToDelete,
      File blockFile, File trashFile) throws Exception {
    assertTrue(blockFile.exists());
    assertFalse(trashFile.exists());

    // Now delete the file and ensure the corresponding block in trash
    LOG.info("Deleting file " + pathToDelete + " during rolling upgrade");
    fs.delete(pathToDelete, false);
    assert(!fs.exists(pathToDelete));
    triggerHeartBeats();
    assertTrue(trashFile.exists());
    assertFalse(blockFile.exists());
  }

  private void ensureTrashDisabled() {
    // Trash is disabled; trash root does not exist
    assertFalse(dn.getFSDataset().trashEnabled(blockPoolId));
    BlockPoolSliceStorage bps = dn.getStorage().getBPStorage(blockPoolId);
    assertFalse(bps.trashEnabled());
  }

  /**
   * Ensures that the blocks from trash are restored
   */
  private void ensureTrashRestored(File blockFile, File trashFile)
      throws Exception {
    assertTrue(blockFile.exists());
    assertFalse(trashFile.exists());
    ensureTrashDisabled();
  }

  private void startRollingUpgrade() throws Exception {
    LOG.info("Starting rolling upgrade");
    final DFSAdmin dfsadmin = new DFSAdmin(conf);
    TestRollingUpgrade.runCmd(dfsadmin, true, "-rollingUpgrade", "start");
    triggerHeartBeats();

    // Ensure datanode rolling upgrade is started
    assertTrue(dn.getFSDataset().trashEnabled(blockPoolId));
  }

  private void finalizeRollingUpgrade() throws Exception {
    LOG.info("Finalizing rolling upgrade");
    final DFSAdmin dfsadmin = new DFSAdmin(conf);
    TestRollingUpgrade.runCmd(dfsadmin, true, "-rollingUpgrade", "finalize");
    triggerHeartBeats();

    // Ensure datanode rolling upgrade is started
    assertFalse(dn.getFSDataset().trashEnabled(blockPoolId));
    BlockPoolSliceStorage bps = dn.getStorage().getBPStorage(blockPoolId);
    assertFalse(bps.trashEnabled());
  }

  private void rollbackRollingUpgrade() throws Exception {
    // Shutdown datanodes and namenodes
    // Restart the namenode with rolling upgrade rollback
    LOG.info("Starting rollback of the rolling upgrade");
    MiniDFSCluster.DataNodeProperties dnprop = cluster.stopDataNode(0);
    cluster.shutdownNameNodes();
    cluster.restartNameNode("-rollingupgrade", "rollback");
    cluster.restartDataNode(dnprop);
    cluster.waitActive();
    nn = cluster.getNameNode(0);
    dn = cluster.getDataNodes().get(0);
    triggerHeartBeats();
  }

  @Test (timeout=600000)
  public void testDatanodeRollingUpgradeWithFinalize() throws Exception {
    try {
      startCluster();

      // Create files in DFS.
      Path testFile1 = new Path("/TestDataNodeRollingUpgrade1.dat");
      Path testFile2 = new Path("/TestDataNodeRollingUpgrade2.dat");
      DFSTestUtil.createFile(fs, testFile1, FILE_SIZE, REPL_FACTOR, SEED);
      DFSTestUtil.createFile(fs, testFile2, FILE_SIZE, REPL_FACTOR, SEED);

      startRollingUpgrade();
      File blockFile = getBlockForFile(testFile2, true);
      File trashFile = getTrashFileForBlock(blockFile, false);
      deleteAndEnsureInTrash(testFile2, blockFile, trashFile);
      finalizeRollingUpgrade();

      // Ensure that delete file testFile2 stays deleted after finalize
      ensureTrashDisabled();
      assert(!fs.exists(testFile2));
      assert(fs.exists(testFile1));

    } finally {
      shutdownCluster();
    }
  }

  @Test (timeout=600000)
  public void testDatanodeRollingUpgradeWithRollback() throws Exception {
    try {
      startCluster();

      // Create files in DFS.
      Path testFile1 = new Path("/TestDataNodeRollingUpgrade1.dat");
      DFSTestUtil.createFile(fs, testFile1, FILE_SIZE, REPL_FACTOR, SEED);
      String fileContents1 = DFSTestUtil.readFile(fs, testFile1);

      startRollingUpgrade();

      File blockFile = getBlockForFile(testFile1, true);
      File trashFile = getTrashFileForBlock(blockFile, false);
      deleteAndEnsureInTrash(testFile1, blockFile, trashFile);

      // Now perform a rollback to restore DFS to the pre-rollback state.
      rollbackRollingUpgrade();

      // Ensure that block was restored from trash
      ensureTrashRestored(blockFile, trashFile);

      // Ensure that files exist and restored file contents are the same.
      assert(fs.exists(testFile1));
      String fileContents2 = DFSTestUtil.readFile(fs, testFile1);
      assertThat(fileContents1, is(fileContents2));
    } finally {
      shutdownCluster();
    }
  }
}
