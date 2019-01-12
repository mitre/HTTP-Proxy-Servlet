# Version 1.12 (unreleased)

# Version 1.11 2019-01-12

\#155: Add OSGI manifiest headers.
Thanks Abhishek Jain.

\#153: New settings: http.maxConnections and http.connectionrequest.timeout
Thanks Gotzon Illarramendi.

\#149: Introduced overrideable method rewritePathInfoFromRequest.
Thanks Oren Efraty.

\#151: Copy `HttpOnly` flag of proxy cookie to request clients, for fixing security vulnerabilities in cookies.
This also updates `javax.servlet-api` to `v3.0.1`.

\#150 Setting the read timeout in the `RequestConfig` is not enough.
The read timeout must be set in the `SocketConfig` as well.
Setting the read timeout only in the `RequestConfig` can cause hangs which could
block the whole proxy forever.
Attention: Method signature of `createHttpClient(RequestConfig)` changed to
`createHttpClient()`.
Please override `buildRequestConfig()` and `buildSocketConfig()` to configure the
Apache HttpClient.
Thanks Martin Wegner.

\#139: Use Java system properties for http proxy (and other settings) by default.
This is a regression; it used to work this way in 1.8 and prior.
Thanks Thorsten Möller.

\#132: The proxy was erroneously sometimes appending "?null=" to a
request that had no query portion of the URL.
Thanks @pjunlin.

README updates.  Thanks Bruce Taylor, Gonzalo Fernández-Victorio

# Version 1.10, 2017-11-26

... TODO; I should have started this list long ago
