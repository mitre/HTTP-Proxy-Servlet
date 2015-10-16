package org.mitre.dsmiley.httpproxy;

import javax.servlet.http.HttpServletRequest;

public interface QueryStringRewriter {
  String rewriteQueryStringFromRequest(HttpServletRequest servletRequest, String queryString);
}
