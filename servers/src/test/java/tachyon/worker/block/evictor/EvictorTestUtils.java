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

package tachyon.worker.block.evictor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;

import com.google.common.base.Preconditions;

import tachyon.Pair;
import tachyon.exception.NotFoundException;
import tachyon.worker.block.BlockMetadataManager;
import tachyon.worker.block.BlockStoreLocation;
import tachyon.worker.block.meta.BlockMeta;
import tachyon.worker.block.meta.StorageDir;

/**
 * This class provides utility methods for testing Evictors.
 */
public class EvictorTestUtils {
  /**
   * Whether blocks in the EvictionPlan are in the same StorageDir.
   *
   * @param plan the eviction plan
   * @param meta the meta data manager
   * @return true if blocks are in the same dir otherwise false
   * @throws NotFoundException if fail to get meta data of a block
   */
  public static boolean blocksInTheSameDir(EvictionPlan plan, BlockMetadataManager meta)
      throws NotFoundException {
    Preconditions.checkNotNull(plan);

    StorageDir dir = null;
    List<Long> blockIds = new ArrayList<Long>();
    for (Pair<Long, BlockStoreLocation> evict : plan.toEvict()) {
      blockIds.add(evict.getFirst());
    }
    for (BlockTransferInfo move : plan.toMove()) {
      blockIds.add(move.getBlockId());
    }

    for (long blockId : blockIds) {
      StorageDir blockDir = meta.getBlockMeta(blockId).getParentDir();
      if (dir == null) {
        dir = blockDir;
      } else if (dir != blockDir) {
        return false;
      }
    }
    return true;
  }

  /**
   * Assume the plan is returned by a non-cascading evictor, check whether it is valid. a cascading
   * evictor is an evictor that always tries to move from the target tier to the next tier and
   * recursively move down 1 tier until finally blocks are evicted from the final tier.
   *
   * @param bytesToBeAvailable the requested bytes to be available
   * @param plan the eviction plan, should not be null
   * @param metaManager the meta data manager
   * @return true if and only if the plan is not null and both {@link #blocksInTheSameDir} and
   *         {@link #requestSpaceSatisfied} are true, otherwise false
   * @throws tachyon.exception.NotFoundException when fail to get meta data of a block
   */
  public static boolean validNonCascadingPlan(long bytesToBeAvailable, EvictionPlan plan,
      BlockMetadataManager metaManager) throws NotFoundException {
    Preconditions.checkNotNull(plan);
    return blocksInTheSameDir(plan, metaManager)
        && requestSpaceSatisfied(bytesToBeAvailable, plan, metaManager);
  }

  /**
   * Checks whether the plan of a cascading evictor is valid.
   *
   * A cascading evictor will try to free space by recursively moving blocks to next 1 tier and
   * evict blocks only in the bottom tier.
   *
   * The plan is invalid when the requested space can not be satisfied or lower level of tiers do
   * not have enough space to hold blocks moved from higher level of tiers.
   *
   * @param bytesToBeAvailable requested bytes to be available after eviction
   * @param plan the eviction plan, should not be empty
   * @param metaManager the meta data manager
   * @return true if the above requirements are satisfied, otherwise false
   * @throws NotFoundException if a block for which metadata cannot be found is encountered
   */
  // TODO: unit test this method
  public static boolean validCascadingPlan(long bytesToBeAvailable, EvictionPlan plan,
      BlockMetadataManager metaManager) throws NotFoundException {
    // reassure the plan is feasible: enough free space to satisfy bytesToBeAvailable, and enough
    // space in lower tier to move blocks in upper tier there

    // Map from dir to a pair of bytes to be available in this dir and bytes to move into this dir
    // after the plan taking action
    Map<StorageDir, Pair<Long, Long>> spaceInfoInDir = new HashMap<StorageDir, Pair<Long, Long>>();

    for (Pair<Long, BlockStoreLocation> blockInfo : plan.toEvict()) {
      BlockMeta block = metaManager.getBlockMeta(blockInfo.getFirst());
      StorageDir dir = block.getParentDir();
      if (spaceInfoInDir.containsKey(dir)) {
        Pair<Long, Long> spaceInfo = spaceInfoInDir.get(dir);
        spaceInfo.setFirst(spaceInfo.getFirst() + block.getBlockSize());
      } else {
        spaceInfoInDir.put(dir, new Pair<Long, Long>(
            dir.getAvailableBytes() + block.getBlockSize(), 0L));
      }
    }

    for (BlockTransferInfo move : plan.toMove()) {
      long blockId = move.getBlockId();
      BlockMeta block = metaManager.getBlockMeta(blockId);
      long blockSize = block.getBlockSize();
      StorageDir srcDir = block.getParentDir();
      StorageDir destDir = metaManager.getDir(move.getDstLocation());

      if (spaceInfoInDir.containsKey(srcDir)) {
        Pair<Long, Long> spaceInfo = spaceInfoInDir.get(srcDir);
        spaceInfo.setFirst(spaceInfo.getFirst() + blockSize);
      } else {
        spaceInfoInDir
            .put(srcDir, new Pair<Long, Long>(srcDir.getAvailableBytes() + blockSize, 0L));
      }

      if (spaceInfoInDir.containsKey(destDir)) {
        Pair<Long, Long> spaceInfo = spaceInfoInDir.get(destDir);
        spaceInfo.setSecond(spaceInfo.getSecond() + blockSize);
      } else {
        spaceInfoInDir.put(destDir, new Pair<Long, Long>(destDir.getAvailableBytes(), blockSize));
      }
    }

    // the top tier among all tiers where blocks in the plan reside in
    int topTierAlias = Integer.MAX_VALUE;
    for (StorageDir dir : spaceInfoInDir.keySet()) {
      topTierAlias = Math.min(topTierAlias, dir.getParentTier().getTierAlias());
    }
    long maxSpace = Long.MIN_VALUE; // maximum bytes to be available in a dir in the top tier
    for (StorageDir dir : spaceInfoInDir.keySet()) {
      if (dir.getParentTier().getTierAlias() == topTierAlias) {
        Pair<Long, Long> space = spaceInfoInDir.get(dir);
        maxSpace = Math.max(maxSpace, space.getFirst() - space.getSecond());
      }
    }
    if (maxSpace < bytesToBeAvailable) {
      // plan is invalid because requested space can not be satisfied in the top tier
      return false;
    }

    for (StorageDir dir : spaceInfoInDir.keySet()) {
      Pair<Long, Long> spaceInfo = spaceInfoInDir.get(dir);
      if (spaceInfo.getFirst() < spaceInfo.getSecond()) {
        // plan is invalid because there is not enough space in this dir to hold the blocks waiting
        // to be moved into this dir
        return false;
      }
    }

    return true;
  }

  /**
   * Only when plan is not null and at least one of {@link #validCascadingPlan},
   * {@link #validNonCascadingPlan} is true, the assertion will be passed, used in unit test.
   *
   * @param bytesToBeAvailable the requested bytes to be available
   * @param plan the eviction plan, should not be null
   * @param metaManager the meta data manager
   * @throws Exception when fail
   */
  public static void assertEvictionPlanValid(long bytesToBeAvailable, EvictionPlan plan,
      BlockMetadataManager metaManager) throws Exception {
    Assert.assertNotNull(plan);
    Assert.assertTrue(validNonCascadingPlan(bytesToBeAvailable, plan, metaManager)
        || validCascadingPlan(bytesToBeAvailable, plan, metaManager));
  }

  /**
   * Whether the plan can satisfy the requested free bytes to be available, assume all blocks in the
   * plan are in the same dir.
   *
   * @param bytesToBeAvailable the requested bytes to be available
   * @param plan the eviction plan, should not be null
   * @param meta the metadata manager
   * @return true if the request can be satisfied otherwise false
   * @throws tachyon.exception.NotFoundException if can not get meta data of a block
   */
  public static boolean requestSpaceSatisfied(long bytesToBeAvailable, EvictionPlan plan,
      BlockMetadataManager meta) throws NotFoundException {
    Preconditions.checkNotNull(plan);

    List<Long> blockIds = new ArrayList<Long>();
    for (Pair<Long, BlockStoreLocation> evict : plan.toEvict()) {
      blockIds.add(evict.getFirst());
    }
    for (BlockTransferInfo move : plan.toMove()) {
      blockIds.add(move.getBlockId());
    }

    long evictedOrMovedBytes = 0;
    for (long blockId : blockIds) {
      evictedOrMovedBytes += meta.getBlockMeta(blockId).getBlockSize();
    }

    BlockStoreLocation location =
        meta.getBlockMeta(blockIds.get(0)).getParentDir().toBlockStoreLocation();
    return (meta.getAvailableBytes(location) + evictedOrMovedBytes) >= bytesToBeAvailable;
  }
}
