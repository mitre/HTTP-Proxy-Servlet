
# Version 1.11 (unreleased)

\#151: Copy `HttpOnly` flag of proxy coookie to request clients, for fixing security vulnerabilities in cookies.
This also updates `javax.servlet-api` to `v3.0.1`.

\#139: Use Java system properties for http proxy (and other settings) by default.
This is a regression; it used to work this way in 1.8 and prior.
Thanks Thorsten Möller.

\#132: The proxy was erroneously sometimes appending "?null=" to a
request that had no query portion of the URL.
Thanks @pjunlin.

README updates.  Thanks Bruce Taylor, Gonzalo Fernández-Victorio

# Version 1.10, 2017-11-26

... TODO; I should have started this list long ago