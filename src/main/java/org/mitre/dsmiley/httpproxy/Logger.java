package org.mitre.dsmiley.httpproxy;

public interface Logger {
  void logMessage(String s, Exception e);
  void logMessage(String s);
}
