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

package tachyon.master.block.journal;

import java.util.Map;

import com.google.common.collect.Maps;

import tachyon.master.journal.JournalEntry;
import tachyon.master.journal.JournalEntryType;

/**
 * The {@link JournalEntry} representing the state of a worker in the block master.
 */
public class WorkerIdGeneratorEntry implements JournalEntry {
  private final long mNextWorkerId;

  public WorkerIdGeneratorEntry(long nextWorkerId) {
    mNextWorkerId = nextWorkerId;
  }

  public long getNextWorkerId() {
    return mNextWorkerId;
  }

  @Override
  public JournalEntryType getType() {
    return JournalEntryType.WORKER_ID_GENERATOR;
  }

  @Override
  public Map<String, Object> getParameters() {
    Map<String, Object> parameters = Maps.newHashMapWithExpectedSize(1);
    parameters.put("nextWorkerId", mNextWorkerId);
    return parameters;
  }
}
