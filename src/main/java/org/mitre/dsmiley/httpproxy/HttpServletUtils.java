package org.mitre.dsmiley.httpproxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

/**
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

/**
 * utils for HttpServlet
 *
 * @author Chang Chao chang-chao@hotmail.com
 * 
 */
public class HttpServletUtils {

    /**
     * Retrieves the body of the request as binary data.<br>
     * In case of form data got consumed by calling getParameterXXX before this
     * method, the method returns a byte
     * 
     * <p>
     * Inspired by :<a href=
     * "https://github.com/spring-projects/spring-framework/blob/master/spring-web/src/main/java/org/springframework/web/util/ContentCachingRequestWrapper.java/">
     * Spring ContentCachingRequestWrapper class</a>.
     * </p>
     * 
     * @param request
     *            the http servlet request
     * @return a {@link InputStream} object containing the body of the request
     * @exception IOException
     *                if an input or output exception occurred
     * 
     */
    public static InputStream getInputStreamPostFormConsidered(HttpServletRequest request) throws IOException {

        String contentType = request.getContentType();

        boolean isFormPost = (contentType != null && contentType.contains("application/x-www-form-urlencoded")
                && "POST".equalsIgnoreCase(request.getMethod()));

        if (!isFormPost) {
            return request.getInputStream();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            String requestEncoding = request.getCharacterEncoding();
            if (requestEncoding == null) {
                // the request does not specify a character encoding,use utf-8
                requestEncoding = "utf-8";
            }
            Map<String, String[]> form = request.getParameterMap();
            for (Iterator<String> nameIterator = form.keySet().iterator(); nameIterator.hasNext();) {
                String name = nameIterator.next();
                List<String> values = Arrays.asList(form.get(name));
                for (Iterator<String> valueIterator = values.iterator(); valueIterator.hasNext();) {
                    String value = valueIterator.next();
                    baos.write(URLEncoder.encode(name, requestEncoding).getBytes());
                    if (value != null) {
                        baos.write('=');
                        baos.write(URLEncoder.encode(value, requestEncoding).getBytes());
                        if (valueIterator.hasNext()) {
                            baos.write('&');
                        }
                    }
                }
                if (nameIterator.hasNext()) {
                    baos.write('&');
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write request parameters to cache", ex);
        }

        return new ByteArrayInputStream(baos.toByteArray());

    }
}
