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

package tachyon.master.rawtable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;

import org.apache.thrift.TProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tachyon.Constants;
import tachyon.TachyonURI;
import tachyon.conf.TachyonConf;
import tachyon.master.MasterBase;
import tachyon.master.file.FileSystemMaster;
import tachyon.master.journal.Journal;
import tachyon.master.journal.JournalEntry;
import tachyon.master.journal.JournalOutputStream;
import tachyon.master.rawtable.journal.RawTableEntry;
import tachyon.master.rawtable.journal.UpdateMetadataEntry;
import tachyon.master.rawtable.meta.RawTables;
import tachyon.thrift.FileAlreadyExistException;
import tachyon.thrift.FileDoesNotExistException;
import tachyon.thrift.FileInfo;
import tachyon.thrift.InvalidPathException;
import tachyon.thrift.RawTableInfo;
import tachyon.thrift.RawTableMasterService;
import tachyon.thrift.TableColumnException;
import tachyon.thrift.TableDoesNotExistException;
import tachyon.thrift.TachyonException;
import tachyon.util.ThreadFactoryUtils;
import tachyon.util.io.PathUtils;

public class RawTableMaster extends MasterBase {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  private final TachyonConf mTachyonConf;
  private final long mMaxTableMetadataBytes;
  private final int mMaxColumns;

  private final FileSystemMaster mFileSystemMaster;
  private final RawTables mRawTables = new RawTables();

  public static String getJournalDirectory(String baseDirectory) {
    return PathUtils.concatPath(baseDirectory, Constants.RAW_TABLE_MASTER_SERVICE_NAME);
  }

  public RawTableMaster(TachyonConf tachyonConf, FileSystemMaster fileSystemMaster,
      Journal journal) {
    super(journal,
        Executors.newFixedThreadPool(2, ThreadFactoryUtils.build("raw-table-master-%d", true)));
    mTachyonConf = tachyonConf;
    mMaxTableMetadataBytes = mTachyonConf.getBytes(Constants.MAX_TABLE_METADATA_BYTE);
    mMaxColumns = mTachyonConf.getInt(Constants.MAX_COLUMNS);
    mFileSystemMaster = fileSystemMaster;
  }

  @Override
  public TProcessor getProcessor() {
    return new RawTableMasterService.Processor<RawTableMasterServiceHandler>(
        new RawTableMasterServiceHandler(this));
  }

  @Override
  public String getServiceName() {
    return Constants.RAW_TABLE_MASTER_SERVICE_NAME;
  }

  @Override
  public void processJournalEntry(JournalEntry entry) throws IOException {
    if (entry instanceof RawTableEntry) {
      RawTableEntry tableEntry = (RawTableEntry) entry;
      mRawTables.add(tableEntry.mId, tableEntry.mColumns, tableEntry.mMetadata);
    } else if (entry instanceof UpdateMetadataEntry) {
      UpdateMetadataEntry updateEntry = (UpdateMetadataEntry) entry;
      try {
        mRawTables.updateMetadata(updateEntry.mId, updateEntry.mMetadata);
      } catch (TableDoesNotExistException tdnee) {
        // should not reach here since before writing the journal, the same operation succeeded
        throw new IOException(tdnee);
      }
    } else {
      throw new IOException("Unknown entry type " + entry.getType());
    }
  }

  @Override
  public void streamToJournalCheckpoint(JournalOutputStream outputStream) throws IOException {
    mRawTables.streamToJournalCheckpoint(outputStream);
  }

  @Override
  public void start(boolean isLeader) throws IOException {
    super.start(isLeader);
  }

  @Override
  public void stop() throws IOException {
    super.stop();
  }

  /**
   * Create a raw table. A table is a directory with sub-directories representing columns.
   *
   * @param path the path where the table is placed
   * @param columns the number of columns in the table
   * @param metadata additional metadata about the table
   * @return the id of the table
   * @throws tachyon.thrift.FileAlreadyExistException when the path already represents a file
   * @throws tachyon.thrift.InvalidPathException when path is invalid
   * @throws tachyon.thrift.TableColumnException when number of columns is out of range
   * @throws TachyonException when metadata size is too large
   */
  public long createRawTable(TachyonURI path, int columns, ByteBuffer metadata)
      throws FileAlreadyExistException, InvalidPathException, TableColumnException,
      TachyonException {
    LOG.info("createRawTable with " + columns + " columns at " + path);

    validateColumnSize(columns);
    validateMetadataSize(metadata);

    // Create a directory at path to hold the columns
    mFileSystemMaster.mkdirs(path, true);
    long id = mFileSystemMaster.getFileId(path);

    // Add the table
    if (!mRawTables.add(id, columns, metadata)) {
      // Should not enter this block in normal case, because id should not be duplicated, so the
      // table should not exist before, also it should be fine to create the new RawTable and add
      // it to internal collection.
      throw new TachyonException("Failed to create raw table.");
    }

    // Create directories in the table directory as columns
    for (int k = 0; k < columns; k ++) {
      mFileSystemMaster.mkdirs(columnPath(path, k), true);
    }

    writeJournalEntry(new RawTableEntry(id, columns, metadata));
    flushJournal();

    return id;
  }

  /**
   * Update the metadata of a table.
   *
   * @param tableId The id of the table to update
   * @param metadata The new metadata to update the table with
   * @throws TableDoesNotExistException when no table has the specified id
   * @throws TachyonException when metadata is too large
   */
  public void updateRawTableMetadata(long tableId, ByteBuffer metadata)
      throws TableDoesNotExistException, TachyonException {
    if (!mFileSystemMaster.isDirectory(tableId)) {
      throw new TableDoesNotExistException("Table with id " + tableId + " does not exist.");
    }
    mRawTables.updateMetadata(tableId, metadata);

    writeJournalEntry(new UpdateMetadataEntry(tableId, metadata));
    flushJournal();
  }

  /**
   * Return the path for the column in the table.
   *
   * @param tablePath the path of the table
   * @param column column number
   * @return the column path
   */
  public TachyonURI columnPath(TachyonURI tablePath, int column) {
    return tablePath.join(Constants.MASTER_COLUMN_FILE_PREFIX + column);
  }

  /**
   * Get the id of the table at the given path.
   *
   * @param path The path of the table
   * @return the id of the table
   * @throws InvalidPathException when path is invalid
   * @throws TableDoesNotExistException when the path does not refer to a table
   */
  public long getRawTableId(TachyonURI path)
      throws InvalidPathException, TableDoesNotExistException {
    long tableId = mFileSystemMaster.getFileId(path);
    if (!mRawTables.contains(tableId) || !mFileSystemMaster.isDirectory(tableId)) {
      throw new TableDoesNotExistException("Table does not exist at path " + path);
    }
    return tableId;
  }

  /**
   * Get the raw table info associated with the given id, the raw table info format is defined in
   * thrift.
   *
   * @param id the id of the table
   * @return the table info
   * @throws TableDoesNotExistException when no table has the id
   */
  public RawTableInfo getClientRawTableInfo(long id) throws TableDoesNotExistException {
    if (!mRawTables.contains(id)) {
      throw new TableDoesNotExistException("Table with id " + id + " does not exist.");
    }

    try {
      FileInfo fileInfo = mFileSystemMaster.getFileInfo(id);
      if (!fileInfo.isFolder) {
        throw new TableDoesNotExistException("Table with id " + id + " does not exist.");
      }

      RawTableInfo ret = new RawTableInfo();
      ret.id = fileInfo.getFileId();
      ret.name = fileInfo.getName();
      ret.path = fileInfo.getPath();
      ret.columns = mRawTables.getColumns(ret.id);
      ret.metadata = mRawTables.getMetadata(ret.id);
      return ret;
    } catch (FileDoesNotExistException fne) {
      throw new TableDoesNotExistException("Table with id " + id + " does not exist.");
    } catch (InvalidPathException e) {
      throw new TableDoesNotExistException("Table id " + id + " is invalid.");
    }
  }

  /**
   * Get the raw table info of the table at the given path, the raw table info format is defined in
   * thrift.
   *
   * @param path the path of the table
   * @return the table info
   * @throws TableDoesNotExistException when the path does not refer to a table
   * @throws InvalidPathException when path is invalid
   */
  public RawTableInfo getClientRawTableInfo(TachyonURI path)
      throws TableDoesNotExistException, InvalidPathException {
    return getClientRawTableInfo(getRawTableId(path));
  }

  /**
   * Validate that the number of columns is in the range from 0 to configured maximum number,
   * non-inclusive.
   *
   * @param columns number of columns
   * @throws TableColumnException if number of columns is out of range
   */
  private void validateColumnSize(int columns) throws TableColumnException {
    if (columns <= 0 || columns >= mMaxColumns) {
      throw new TableColumnException("Number of columns: " + columns + " should range from 0 to "
          + mMaxColumns + ", non-inclusive");
    }
  }

  /**
   * Validate that the size of metadata is smaller than the configured maximum size. This should be
   * called whenever a metadata wants to be set.
   *
   * @param metadata the metadata to be validated
   * @throws TachyonException if the metadata is too large
   */
  // TODO(cc) have a more explicit TableMetaException ?
  private void validateMetadataSize(ByteBuffer metadata) throws TachyonException {
    if (metadata.limit() - metadata.position() >= mMaxTableMetadataBytes) {
      throw new TachyonException("Too big table metadata: " + metadata.toString());
    }
  }
}
