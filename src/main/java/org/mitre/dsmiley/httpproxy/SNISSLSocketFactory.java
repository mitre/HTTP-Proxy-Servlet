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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.TextUtils;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * An SSL Socket Factory supporting <a href="">SNI</a>,
 * at least on the Sun/Oracle JDK.  {@link SSLConnectionSocketFactory} was introduced in
 * HttpClient 4.3; previously this was possible using
 * {@link org.apache.http.conn.ssl.SSLSocketFactory} which is deprecated.
 */
public class SNISSLSocketFactory extends SSLConnectionSocketFactory {
  /** Use commons-logging because that's what HttpClient uses (no new dependencies). */
  private final Log log = LogFactory.getLog(getClass());

  public static SNISSLSocketFactory createFromSystem() {
    // See HttpClientBuilder.build when it creates an SSLSocketFactory when systemProperties==true
    return new SNISSLSocketFactory(
            (SSLSocketFactory) SSLSocketFactory.getDefault(),
            split(System.getProperty("https.protocols")),
            split(System.getProperty("https.cipherSuites")),
            null);//hostnameVerifier will default
  }

//  public SNISSLSocketFactory(SSLSocketFactory sslSocketFactory,
//                             String[] supportedProtocols, String[] supportedCipherSuites,
//                             HostnameVerifier hostnameVerifier) {
//    super(sslSocketFactory, supportedProtocols, supportedCipherSuites, hostnameVerifier)
//  }

// copy of HttpClientBuilder.split
  private static String[] split(String s) {
    return TextUtils.isBlank(s) ? null : s.split(" *, *");
  }

  // note: the constructors of our superclass are all either introduced in v4.4 or are
  // v4.4+

  /** Note: We support HttpClient v4.3 so we must use a deprecated constructor. */
  @SuppressWarnings({"deprecation"})
  public SNISSLSocketFactory(SSLSocketFactory sslSocketFactory,
                             String[] supportedProtocols, String[] supportedCipherSuites,
                             X509HostnameVerifier hostnameVerifier) {
    super(sslSocketFactory, supportedProtocols, supportedCipherSuites, hostnameVerifier);
  }


  @Override
  public Socket connectSocket(
          int connectTimeout,
          Socket socket,
          HttpHost host,
          InetSocketAddress remoteAddress,
          InetSocketAddress localAddress,
          HttpContext context) throws IOException {
    // For SNI support, we call setHost(hostname) on the socket. But this method isn't part of
    // the SNLSocket JDK class; it's on a Sun/Oracle implementation class:
    // sun.security.ssl.SSLSocketImpl  So we invoke it via reflection.
    try {
      socket.getClass().getDeclaredMethod("setHost", String.class)
              .invoke(socket, host.getHostName());
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
      log.debug("Couldn't invoke setHost on " + socket.getClass() + " for SNI support.", ex);
    }
    return super.connectSocket(connectTimeout, socket, host, remoteAddress,
            localAddress, context);
  }
}