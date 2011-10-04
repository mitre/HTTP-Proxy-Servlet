package org.mitre.dsmiley.httpproxy; //originally net.edwardstx

/**
 * Copyright 2010 Jason Edwards, from Texas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * A servlet that proxies the request to a configured HTTP resource.
 * <br/>
 * <h3>Origin and history</h3>
 * Copied and modified from http://edwardstx.net/2010/06/http-proxy-servlet/
 * <br />
 * History (newest on top):<br/>
 * <ul>
 *   <li>Feature: "log" parameter to log requests</li>
 *   <li>Bug fix: init() should call super.init()</li>
 *   <li>Bug fix: PATH_INFO could be null</li>
 * </ul>
 */
public class ProxyServlet extends HttpServlet
{

  private static final long serialVersionUID = 2L;

  /* HEADER NAME CONSTANTS */
  private static final String STRING_LOCATION_HEADER = "Location";

  private static final String STRING_CONTENT_TYPE_HEADER_NAME = "Content-Type";

  private static final String STRING_CONTENT_LENGTH_HEADER_NAME = "Content-Length";

  private static final String STRING_HOST_HEADER_NAME = "Host";

  /* INIT PARAMETER NAME CONSTANTS */

  /** The target proxy host init parameter. */
  public static final String P_PROXY_HOST = "proxyHost";
  /** The target proxy port init parameter. */
  public static final String P_PROXY_PORT = "proxyPort";
  /** The target proxy path init parameter. This follows the port. Is should be blank or start with a '/'. */
  public static final String P_PROXY_PATH = "proxyPath";
  /** The maximum upload size, in bytes. */
  public static final String P_MAX_FILE_UPLOAD_SIZE = "maxFileUploadSize";
  /** A boolean parameter then when enabled will log input and target URLs to the servlet log. */
  public static final String P_LOG = "log";

  /* MISC */

  /** The directory to use to temporarily store uploaded files. */
  private static final File FILE_UPLOAD_TEMP_DIRECTORY = new File(System.getProperty("java.io.tmpdir"));

  /** The host to which we are proxying requests. */
  private String stringProxyHost;

  /** The port on the proxy host to wihch we are proxying requests. Default value is 80. */
  private int intProxyPort = 80;

  /** The (optional) path on the proxy host to which we are proxying requests. Default value is "". */
  private String stringProxyPath = "";

  /** The maximum size for uploaded files in bytes. Default value is 5MB. */
  private int maxFileUploadSize = 5 * 1024 * 1024;

  private boolean doLog = false;

  /**
   * Initialize the <code>ProxyServlet</code>
   *
   * @param servletConfig The Servlet configuration passed in by the servlet container.
   */
  public void init(ServletConfig servletConfig) throws ServletException {
    super.init(servletConfig);
    String stringProxyHostNew = servletConfig.getInitParameter(P_PROXY_HOST);
    if (stringProxyHostNew == null || stringProxyHostNew.length() == 0) {
      throw new IllegalArgumentException("Proxy host not set, please set init-param 'proxyHost' in web.xml");
    }
    this.stringProxyHost = stringProxyHostNew;
    String stringProxyPortNew = servletConfig.getInitParameter(P_PROXY_PORT);
    if (stringProxyPortNew != null && stringProxyPortNew.length() > 0) {
      this.intProxyPort = Integer.parseInt(stringProxyPortNew);
    }
    String stringProxyPathNew = servletConfig.getInitParameter(P_PROXY_PATH);
    if (stringProxyPathNew != null && stringProxyPathNew.length() > 0) {
      this.stringProxyPath = stringProxyPathNew;
    }
    String stringMaxFileUploadSize = servletConfig.getInitParameter(P_MAX_FILE_UPLOAD_SIZE);
    if (stringMaxFileUploadSize != null && stringMaxFileUploadSize.length() > 0) {
      this.maxFileUploadSize = Integer.parseInt(stringMaxFileUploadSize);
    }
    String stringDoLog = servletConfig.getInitParameter(P_LOG);
    if (stringDoLog != null && stringDoLog.length() > 0) {
      this.doLog = Boolean.parseBoolean(stringDoLog);
    }
  }

  /**
   * Performs an HTTP GET request
   *
   * @param httpServletRequest  The {@link HttpServletRequest} object passed
   *                            in by the servlet engine representing the
   *                            client request to be proxied
   * @param httpServletResponse The {@link HttpServletResponse} object by which
   *                            we can send a proxied response to the client
   */
  public void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
      throws IOException, ServletException {
    // Create a GET request
    GetMethod getMethodProxyRequest = new GetMethod(this.getProxyURL(httpServletRequest));
    // Forward the request headers
    setProxyRequestHeaders(httpServletRequest, getMethodProxyRequest);
    // Execute the proxy request
    this.executeProxyRequest(getMethodProxyRequest, httpServletRequest, httpServletResponse);
  }

  /**
   * Performs an HTTP POST request
   *
   * @param httpServletRequest  The {@link HttpServletRequest} object passed
   *                            in by the servlet engine representing the
   *                            client request to be proxied
   * @param httpServletResponse The {@link HttpServletResponse} object by which
   *                            we can send a proxied response to the client
   */
  public void doPost(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
      throws IOException, ServletException {
    // Create a standard POST request
    PostMethod postMethodProxyRequest = new PostMethod(this.getProxyURL(httpServletRequest));
    // Forward the request headers
    setProxyRequestHeaders(httpServletRequest, postMethodProxyRequest);
    // Check if this is a mulitpart (file upload) POST
    if (ServletFileUpload.isMultipartContent(httpServletRequest)) {
      this.handleMultipartPost(postMethodProxyRequest, httpServletRequest);
    } else {
      this.handleStandardPost(postMethodProxyRequest, httpServletRequest);
    }
    // Execute the proxy request
    this.executeProxyRequest(postMethodProxyRequest, httpServletRequest, httpServletResponse);
  }

  /**
   * Sets up the given {@link PostMethod} to send the same multipart POST
   * data as was sent in the given {@link HttpServletRequest}
   *
   * @param postMethodProxyRequest The {@link PostMethod} that we are
   *                               configuring to send a multipart POST request
   * @param httpServletRequest     The {@link HttpServletRequest} that contains
   *                               the mutlipart POST data to be sent via the {@link PostMethod}
   */
  @SuppressWarnings("unchecked")
  private void handleMultipartPost(PostMethod postMethodProxyRequest, HttpServletRequest httpServletRequest)
      throws ServletException {
    // Create a factory for disk-based file items
    DiskFileItemFactory diskFileItemFactory = new DiskFileItemFactory();
    // Set factory constraints
    diskFileItemFactory.setSizeThreshold(this.maxFileUploadSize);
    diskFileItemFactory.setRepository(FILE_UPLOAD_TEMP_DIRECTORY);
    // Create a new file upload handler
    ServletFileUpload servletFileUpload = new ServletFileUpload(diskFileItemFactory);
    // Parse the request
    try {
      // Get the multipart items as a list
      List<FileItem> listFileItems = (List<FileItem>) servletFileUpload.parseRequest(httpServletRequest);
      // Create a list to hold all of the parts
      List<Part> listParts = new ArrayList<Part>();
      // Iterate the multipart items list
      for (FileItem fileItemCurrent : listFileItems) {
        // If the current item is a form field, then create a string part
        if (fileItemCurrent.isFormField()) {
          StringPart stringPart = new StringPart(
              fileItemCurrent.getFieldName(), // The field name
              fileItemCurrent.getString()     // The field value
          );
          // Add the part to the list
          listParts.add(stringPart);
        } else {
          // The item is a file upload, so we create a FilePart
          FilePart filePart = new FilePart(
              fileItemCurrent.getFieldName(),    // The field name
              new ByteArrayPartSource(
                  fileItemCurrent.getName(), // The uploaded file name
                  fileItemCurrent.get()      // The uploaded file contents
              )
          );
          // Add the part to the list
          listParts.add(filePart);
        }
      }
      MultipartRequestEntity multipartRequestEntity = new MultipartRequestEntity(
          listParts.toArray(new Part[listParts.size()]),
          postMethodProxyRequest.getParams()
      );
      postMethodProxyRequest.setRequestEntity(multipartRequestEntity);
      // The current content-type header (received from the client) IS of
      // type "multipart/form-data", but the content-type header also
      // contains the chunk boundary string of the chunks. Currently, this
      // header is using the boundary of the client request, since we
      // blindly copied all headers from the client request to the proxy
      // request. However, we are creating a new request with a new chunk
      // boundary string, so it is necessary that we re-set the
      // content-type string to reflect the new chunk boundary string
      postMethodProxyRequest.setRequestHeader(STRING_CONTENT_TYPE_HEADER_NAME, multipartRequestEntity.getContentType());
    } catch (FileUploadException fileUploadException) {
      throw new ServletException(fileUploadException);
    }
  }

  /**
   * Sets up the given {@link PostMethod} to send the same standard POST
   * data as was sent in the given {@link HttpServletRequest}
   *
   * @param postMethodProxyRequest The {@link PostMethod} that we are
   *                               configuring to send a standard POST request
   * @param httpServletRequest     The {@link HttpServletRequest} that contains
   *                               the POST data to be sent via the {@link PostMethod}
   */
  @SuppressWarnings("unchecked")
  private void handleStandardPost(PostMethod postMethodProxyRequest, HttpServletRequest httpServletRequest) {
    // Get the client POST data as a Map
    Map<String, String[]> mapPostParameters = (Map<String, String[]>) httpServletRequest.getParameterMap();
    // Create a List to hold the NameValuePairs to be passed to the PostMethod
    List<NameValuePair> listNameValuePairs = new ArrayList<NameValuePair>();
    // Iterate the parameter names
    for (String stringParameterName : mapPostParameters.keySet()) {
      // Iterate the values for each parameter name
      String[] stringArrayParameterValues = mapPostParameters.get(stringParameterName);
      for (String stringParamterValue : stringArrayParameterValues) {
        // Create a NameValuePair and store in list
        NameValuePair nameValuePair = new NameValuePair(stringParameterName, stringParamterValue);
        listNameValuePairs.add(nameValuePair);
      }
    }
    // Set the proxy request POST data
    postMethodProxyRequest.setRequestBody(listNameValuePairs.toArray(new NameValuePair[listNameValuePairs.size()]));
  }

  /**
   * Executes the {@link HttpMethod} passed in and sends the proxy response
   * back to the client via the given {@link HttpServletResponse}
   *
   * @param httpMethodProxyRequest An object representing the proxy request to be made
   * @param httpServletResponse    An object by which we can send the proxied
   *                               response back to the client
   * @throws IOException      Can be thrown by the {@link HttpClient}.executeMethod
   * @throws ServletException Can be thrown to indicate that another error has occurred
   */
  private void executeProxyRequest(
      HttpMethod httpMethodProxyRequest,
      HttpServletRequest httpServletRequest,
      HttpServletResponse httpServletResponse)
      throws IOException, ServletException {

    if (doLog) {
      log("proxy: "+httpServletRequest.getRequestURI()+" -- "+httpMethodProxyRequest.getURI());
    }

    // Create a default HttpClient
    HttpClient httpClient = new HttpClient();
    httpMethodProxyRequest.setFollowRedirects(false);
    // Execute the request
    int intProxyResponseCode = httpClient.executeMethod(httpMethodProxyRequest);

    // Check if the proxy response is a redirect
    // The following code is adapted from org.tigris.noodle.filters.CheckForRedirect
    // Hooray for open source software
    if (intProxyResponseCode >= HttpServletResponse.SC_MULTIPLE_CHOICES /* 300 */
        && intProxyResponseCode < HttpServletResponse.SC_NOT_MODIFIED /* 304 */) {
      String stringStatusCode = Integer.toString(intProxyResponseCode);
      String stringLocation = httpMethodProxyRequest.getResponseHeader(STRING_LOCATION_HEADER).getValue();
      if (stringLocation == null) {
        throw new ServletException("Recieved status code: " + stringStatusCode
            + " but no " + STRING_LOCATION_HEADER + " header was found in the response");
      }
      // Modify the redirect to go to this proxy servlet rather that the proxied host
      String stringMyHostName = httpServletRequest.getServerName();
      if (httpServletRequest.getServerPort() != 80) {
        stringMyHostName += ":" + httpServletRequest.getServerPort();
      }
      stringMyHostName += httpServletRequest.getContextPath();
      httpServletResponse.sendRedirect(stringLocation.replace(getProxyHostAndPort() + this.stringProxyPath, stringMyHostName));
      return;
    } else if (intProxyResponseCode == HttpServletResponse.SC_NOT_MODIFIED) {
      // 304 needs special handling.  See:
      // http://www.ics.uci.edu/pub/ietf/http/rfc1945.html#Code304
      // We get a 304 whenever passed an 'If-Modified-Since'
      // header and the data on disk has not changed; server
      // responds w/ a 304 saying I'm not going to send the
      // body because the file has not changed.
      httpServletResponse.setIntHeader(STRING_CONTENT_LENGTH_HEADER_NAME, 0);
      httpServletResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
      return;
    }

    // Pass the response code back to the client
    httpServletResponse.setStatus(intProxyResponseCode);

    // Pass response headers back to the client
    Header[] headerArrayResponse = httpMethodProxyRequest.getResponseHeaders();
    for (Header header : headerArrayResponse) {
      httpServletResponse.setHeader(header.getName(), header.getValue());
    }

    // Send the content to the client
    InputStream inputStreamProxyResponse = httpMethodProxyRequest.getResponseBodyAsStream();
    BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStreamProxyResponse);
    OutputStream outputStreamClientResponse = httpServletResponse.getOutputStream();
    int intNextByte;
    while ((intNextByte = bufferedInputStream.read()) != -1) {
      outputStreamClientResponse.write(intNextByte);
    }
  }

  public String getServletInfo() {
    return "Jason's Proxy Servlet (modified by David Smiley, dsmiley@mitre.org)";
  }

  /**
   * Retreives all of the headers from the servlet request and sets them on
   * the proxy request
   *
   * @param httpServletRequest     The request object representing the client's
   *                               request to the servlet engine
   * @param httpMethodProxyRequest The request that we are about to send to
   *                               the proxy host
   */
  private void setProxyRequestHeaders(HttpServletRequest httpServletRequest, HttpMethod httpMethodProxyRequest) {
    // Get an Enumeration of all of the header names sent by the client
    Enumeration enumerationOfHeaderNames = httpServletRequest.getHeaderNames();
    while (enumerationOfHeaderNames.hasMoreElements()) {
      String stringHeaderName = (String) enumerationOfHeaderNames.nextElement();
      if (stringHeaderName.equalsIgnoreCase(STRING_CONTENT_LENGTH_HEADER_NAME))
        continue;
      // As per the Java Servlet API 2.5 documentation:
      //		Some headers, such as Accept-Language can be sent by clients
      //		as several headers each with a different value rather than
      //		sending the header as a comma separated list.
      // Thus, we get an Enumeration of the header values sent by the client
      Enumeration enumerationOfHeaderValues = httpServletRequest.getHeaders(stringHeaderName);
      while (enumerationOfHeaderValues.hasMoreElements()) {
        String stringHeaderValue = (String) enumerationOfHeaderValues.nextElement();
        // In case the proxy host is running multiple virtual servers,
        // rewrite the Host header to ensure that we get content from
        // the correct virtual server
        if (stringHeaderName.equalsIgnoreCase(STRING_HOST_HEADER_NAME)) {
          stringHeaderValue = getProxyHostAndPort();
        }
        Header header = new Header(stringHeaderName, stringHeaderValue);
        // Set the same header on the proxy request
        httpMethodProxyRequest.setRequestHeader(header);
      }
    }
  }

  // Accessors
  private String getProxyURL(HttpServletRequest httpServletRequest) {
    // Set the protocol to HTTP
    String stringProxyURL = "http://" + this.getProxyHostAndPort();
    // Check if we are proxying to a path other that the document root
    if (!this.stringProxyPath.equalsIgnoreCase("")) {
      stringProxyURL += this.stringProxyPath;
    }
    // Handle the path given to the servlet
    if (httpServletRequest.getPathInfo() != null) {
      stringProxyURL += httpServletRequest.getPathInfo();
    }
    // Handle the query string
    if (httpServletRequest.getQueryString() != null) {
      stringProxyURL += "?" + httpServletRequest.getQueryString();
    }
    return stringProxyURL;
  }

  private String getProxyHostAndPort() {
    if (this.intProxyPort == 80) {
      return this.stringProxyHost;
    } else {
      return this.stringProxyHost + ":" + this.intProxyPort;
    }
  }

}
