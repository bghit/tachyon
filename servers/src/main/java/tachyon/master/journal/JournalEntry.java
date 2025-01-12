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

package tachyon.master.journal;

import java.util.Map;

// TODO: In the future, implementations of this interface can be represented as ProtoBuf
public interface JournalEntry {

  /**
   * @return the {@link JournalEntryType} of this entry.
   */
  JournalEntryType getType();

  /**
   *
   * @return parameters of this entry which is a map from parameter name to parameter value.
   */
  Map<String, Object> getParameters();
}
