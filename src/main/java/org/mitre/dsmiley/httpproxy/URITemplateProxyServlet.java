/*
 * Copyright MITRE
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

package org.mitre.dsmiley.httpproxy;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A proxy servlet in which the target URI is templated from incoming request parameters. The
 * format adheres to the <a href="http://tools.ietf.org/html/rfc6570">URI Template RFC</a>, "Level
 * 1". Example:
 * <pre>
 *   targetUri = http://{host}:{port}/{path}
 * </pre>
 * --which has the template variables.  The incoming request must contain query args of these
 * names.  They are removed when the request is sent to the target.
 */
@SuppressWarnings({"serial"})
public class URITemplateProxyServlet extends ProxyServlet {

  /* Rich:
  * It might be a nice addition to have some syntax that allowed a proxy arg to be "optional", that is,
  * don't fail if not present, just return the empty string or a given default. But I don't see
  * anything in the spec that supports this kind of construct.
  * Notionally, it might look like {?host:google.com} would return the value of
  * the URL parameter "?hostProxyArg=somehost.com" if defined, but if not defined, return "google.com".
  * Similarly, {?host} could return the value of hostProxyArg or empty string if not present.
  * But that's not how the spec works. So for now we will require a proxy arg to be present
  * if defined for this proxy URL.
  */
  protected static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{(.+?)\\}");
  private static final String ATTR_QUERY_STRING =
          URITemplateProxyServlet.class.getSimpleName() + ".queryString";

  protected String targetUriTemplate;//has {name} parts

  @Override
  protected void initTarget() throws ServletException {
    targetUriTemplate = getConfigParam(P_TARGET_URI);
    if (targetUriTemplate == null)
      throw new ServletException(P_TARGET_URI+" is required.");

    //leave this.target* null to prevent accidental mis-use
  }

  @Override
  protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
          throws ServletException, IOException {

    //First collect params
    /*
     * Do not use servletRequest.getParameter(arg) because that will
     * typically read and consume the servlet InputStream (where our
     * form data is stored for POST). We need the InputStream later on.
     * So we'll parse the query string ourselves. A side benefit is
     * we can keep the proxy parameters in the query string and not
     * have to add them to a URL encoded form attachment.
     */
    String requestQueryString = servletRequest.getQueryString();
    String queryString = "";
    if (requestQueryString != null) {
      queryString = "?" + requestQueryString;//no "?" but might have "#"
    }
    int hash = queryString.indexOf('#');
    if (hash >= 0) {
      queryString = queryString.substring(0, hash);
    }
    List<NameValuePair> pairs;
    try {
      //note: HttpClient 4.2 lets you parse the string without building the URI
      pairs = URLEncodedUtils.parse(new URI(queryString), "UTF-8");
    } catch (URISyntaxException e) {
      throw new ServletException("Unexpected URI parsing error on " + queryString, e);
    }
    LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
    for (NameValuePair pair : pairs) {
      params.computeIfAbsent(pair.getName(), k -> new ArrayList<>()).add(pair.getValue());
    }

    //Now rewrite the URL
    StringBuffer urlBuf = new StringBuffer();//note: StringBuilder isn't supported by Matcher
    Matcher matcher = TEMPLATE_PATTERN.matcher(targetUriTemplate);
    while (matcher.find()) {
      String arg = matcher.group(1);
      List<String> replacement = params.remove(arg);//note we remove
      if (replacement == null) {
        throw new ServletException("Missing HTTP parameter "+arg+" to fill the template");
      }
      matcher.appendReplacement(urlBuf, replacement.get(0));
    }
    matcher.appendTail(urlBuf);
    String newTargetUri = urlBuf.toString();
    servletRequest.setAttribute(ATTR_TARGET_URI, newTargetUri);
    URI targetUriObj;
    try {
      targetUriObj = new URI(newTargetUri);
    } catch (Exception e) {
      throw new ServletException("Rewritten targetUri is invalid: " + newTargetUri,e);
    }
    servletRequest.setAttribute(ATTR_TARGET_HOST, URIUtils.extractHost(targetUriObj));

    //Determine the new query string based on removing the used names
    StringBuilder newQueryBuf = new StringBuilder(queryString.length());
    for (Map.Entry<String, List<String>> nameVal : params.entrySet()) {
      for (String nameValElement : nameVal.getValue()) {
        if (newQueryBuf.length() > 0)
          newQueryBuf.append('&');
        newQueryBuf.append(nameVal.getKey()).append('=');
        if (nameVal.getValue() != null)
          newQueryBuf.append(URLEncoder.encode(nameValElement, "UTF-8"));
      }
    }
    servletRequest.setAttribute(ATTR_QUERY_STRING, newQueryBuf.toString());

    super.service(servletRequest, servletResponse);
  }

  @Override
  protected String rewriteQueryStringFromRequest(HttpServletRequest servletRequest, String queryString) {
    return (String) servletRequest.getAttribute(ATTR_QUERY_STRING);
  }
}
