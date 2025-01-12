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

package tachyon.web;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;

import tachyon.Constants;
import tachyon.TachyonURI;
import tachyon.client.TachyonStorageType;
import tachyon.client.ClientOptions;
import tachyon.client.file.FileInStream;
import tachyon.client.file.TachyonFile;
import tachyon.client.file.TachyonFileSystem;
import tachyon.master.file.FileSystemMaster;
import tachyon.thrift.FileDoesNotExistException;
import tachyon.thrift.FileInfo;
import tachyon.thrift.InvalidPathException;

/**
 * Servlet for downloading a file
 */
public final class WebInterfaceDownloadServlet extends HttpServlet {
  private static final long serialVersionUID = 7329267100965731815L;

  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  private final transient FileSystemMaster mFsMaster;

  public WebInterfaceDownloadServlet(FileSystemMaster fsMaster) {
    mFsMaster = Preconditions.checkNotNull(fsMaster);
  }

  /**
   * Prepares for downloading a file
   *
   * @param request The HttpServletRequest object
   * @param response The HttpServletResponse object
   * @throws ServletException
   * @throws IOException
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    String requestPath = request.getParameter("path");
    if (requestPath == null || requestPath.isEmpty()) {
      requestPath = TachyonURI.SEPARATOR;
    }
    TachyonURI currentPath = new TachyonURI(requestPath);
    try {
      long fileId = mFsMaster.getFileId(currentPath);
      FileInfo fileInfo = mFsMaster.getFileInfo(fileId);
      if (null == fileInfo) {
        throw new FileDoesNotExistException(currentPath.toString());
      }
      downloadFile(new TachyonURI(fileInfo.getPath()), request, response);
    } catch (FileDoesNotExistException fdne) {
      request.setAttribute("invalidPathError", "Error: Invalid Path " + fdne.getMessage());
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
    } catch (InvalidPathException ipe) {
      request.setAttribute("invalidPathError", "Error: Invalid Path " + ipe.getLocalizedMessage());
      getServletContext().getRequestDispatcher("/browse.jsp").forward(request, response);
    }
  }

  /**
   * This function prepares for downloading a file.
   *
   * @param path The path of the file to download
   * @param request The HttpServletRequest object
   * @param response The HttpServletResponse object
   * @throws FileDoesNotExistException
   * @throws IOException
   */
  private void downloadFile(TachyonURI path, HttpServletRequest request,
      HttpServletResponse response) throws FileDoesNotExistException, IOException {
    TachyonFileSystem tachyonClient = TachyonFileSystem.get();
    TachyonFile fd = tachyonClient.open(path);
    FileInfo tFile = tachyonClient.getInfo(fd);
    long len = tFile.getLength();
    String fileName = path.getName();
    response.setContentType("application/octet-stream");
    if (len <= Integer.MAX_VALUE) {
      response.setContentLength((int) len);
    } else {
      response.addHeader("Content-Length", Long.toString(len));
    }
    response.addHeader("Content-Disposition", "attachment;filename=" + fileName);

    FileInStream is = null;
    ServletOutputStream out = null;
    try {
      ClientOptions op = new ClientOptions.Builder(mFsMaster.getTachyonConf()).setTachyonStoreType(
          TachyonStorageType.NO_STORE).build();
      is = tachyonClient.getInStream(fd, op);
      out = response.getOutputStream();
      ByteStreams.copy(is, out);
    } finally {
      if (out != null) {
        out.flush();
        out.close();
      }
      if (is != null) {
        is.close();
      }
    }
    tachyonClient.close();
  }
}
