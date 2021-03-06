/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.logging.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.google.cloud.MonitoredResource;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.Logging.WriteOption;
import com.google.cloud.logging.LoggingEnhancer;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.MonitoredResourceUtil;
import com.google.cloud.logging.Payload;
import com.google.cloud.logging.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <a href="https://logback.qos.ch/">Logback</a> appender for StackDriver Cloud Logging.
 *
 * <p>Appender configuration in logback.xml:
 *
 * <ul>
 * <li>&lt;appender name="CLOUD" class="com.google.cloud.logging.logback.LoggingAppender"&gt;
 * <li>&lt;log&gt;application.log&lt;/log&gt; (Optional, defaults to "java.log" : Stackdriver log
 * name)
 * <li>&lt;level&gt;ERROR&lt;/level&gt; (Optional, defaults to "INFO" : logs at or above this
 * level)
 * <li>&lt;flushLevel&gt;WARNING&lt;/flushLevel&gt; (Optional, defaults to "ERROR")
 * <li>&lt;resourceType&gt;&lt;/resourceType&gt; (Optional, auto detects on App Engine Flex,
 * Standard, GCE and GKE, defaults to "global". See <a
 * href="https://cloud.google.com/logging/docs/api/v2/resource-list">supported resource
 * types</a>
 * <li>(Optional) add custom labels to log entries using {@link LoggingEnhancer} classes.
 * <li>&lt;enhancer&gt;com.example.enhancer1&lt;/enhancer&gt;
 * <li>&lt;enhancer&gt;com.example.enhancer2&lt;/enhancer&gt;
 * <li>&lt;/appender&gt;
 * </ul>
 */
public class LoggingAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private static final String LEVEL_NAME_KEY = "levelName";
  private static final String LEVEL_VALUE_KEY = "levelValue";

  private volatile Logging logging;
  private List<LoggingEnhancer> loggingEnhancers;
  private WriteOption[] defaultWriteOptions;

  private Level flushLevel;
  private String log;
  private String resourceType;
  private Set<String> enhancerClassNames = new HashSet<>();

  /**
   * Batched logging requests get immediately flushed for logs at or above this level.
   *
   * <p>Defaults to Error if not set.
   *
   * @param flushLevel Logback log level
   */
  public void setFlushLevel(Level flushLevel) {
    this.flushLevel = flushLevel;
  }

  /**
   * Sets the log filename.
   *
   * @param log filename
   */
  public void setLog(String log) {
    this.log = log;
  }

  /**
   * Sets the name of the monitored resource (Optional).
   *
   * <p>Must be a <a href="https://cloud.google.com/logging/docs/api/v2/resource-list">supported</a>
   * resource type. gae_app, gce_instance and container are auto-detected.
   *
   * <p>Defaults to "global"
   *
   * @param resourceType name of the monitored resource
   */
  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  /**
   * Add extra labels using classes that implement {@link LoggingEnhancer}.
   */
  public void addEnhancer(String enhancerClassName) {
    this.enhancerClassNames.add(enhancerClassName);
  }

  Level getFlushLevel() {
    return (flushLevel != null) ? flushLevel : Level.ERROR;
  }

  String getLogName() {
    return (log != null) ? log : "java.log";
  }

  MonitoredResource getMonitoredResource(String projectId) {
    return MonitoredResourceUtil.getResource(projectId, resourceType);
  }

  List<LoggingEnhancer> getLoggingEnhancers() {
    List<LoggingEnhancer> loggingEnhancers = new ArrayList<>();
    if (enhancerClassNames != null) {
      for (String enhancerClassName : enhancerClassNames) {
        if (enhancerClassName != null) {
          LoggingEnhancer enhancer = getEnhancer(enhancerClassName);
          if (enhancer != null) {
            loggingEnhancers.add(enhancer);
          }
        }
      }
    }
    return loggingEnhancers;
  }

  private LoggingEnhancer getEnhancer(String enhancerClassName) {
    try {
      Class<? extends LoggingEnhancer> clz =
          (Class<? extends LoggingEnhancer>)
              ClassLoader.getSystemClassLoader().loadClass(enhancerClassName.trim());
      return clz.newInstance();
    } catch (Exception ex) {
      // If we cannot create the enhancer we fallback to null
    }
    return null;
  }

  /**
   * Initialize and configure the cloud logging service.
   */
  @Override
  public synchronized void start() {
    if (isStarted()) {
      return;
    }
    MonitoredResource resource = getMonitoredResource(getProjectId());
    defaultWriteOptions =
        new WriteOption[]{WriteOption.logName(getLogName()), WriteOption.resource(resource)};
    getLogging().setFlushSeverity(severityFor(getFlushLevel()));
    loggingEnhancers = new ArrayList<>();
    List<LoggingEnhancer> resourceEnhancers = MonitoredResourceUtil.getResourceEnhancers();
    loggingEnhancers.addAll(resourceEnhancers);
    loggingEnhancers.addAll(getLoggingEnhancers());
    super.start();
  }

  String getProjectId() {
    return LoggingOptions.getDefaultInstance().getProjectId();
  }

  @Override
  protected void append(ILoggingEvent e) {
    LogEntry logEntry = logEntryFor(e);
    getLogging().write(Collections.singleton(logEntry), defaultWriteOptions);
  }

  @Override
  public synchronized void stop() {
    if (logging != null) {
      try {
        logging.close();
      } catch (Exception ex) {
        // ignore
      }
    }
    logging = null;
    super.stop();
  }

  Logging getLogging() {
    if (logging == null) {
      synchronized (this) {
        if (logging == null) {
          logging = LoggingOptions.getDefaultInstance().getService();
        }
      }
    }
    return logging;
  }

  private LogEntry logEntryFor(ILoggingEvent e) {
    String payload = e.getFormattedMessage();
    Level level = e.getLevel();
    LogEntry.Builder builder =
        LogEntry.newBuilder(Payload.StringPayload.of(payload))
            .setTimestamp(e.getTimeStamp())
            .setSeverity(severityFor(level));

    builder
        .addLabel(LEVEL_NAME_KEY, level.toString())
        .addLabel(LEVEL_VALUE_KEY, String.valueOf(level.toInt()));

    if (loggingEnhancers != null) {
      for (LoggingEnhancer enhancer : loggingEnhancers) {
        enhancer.enhanceLogEntry(builder);
      }
    }

    return builder.build();
  }

  /**
   * Transforms Logback logging levels to Cloud severity.
   *
   * @param level Logback logging level
   * @return Cloud severity level
   */
  private static Severity severityFor(Level level) {
    switch (level.toInt()) {
      // TRACE
      case 5000:
        return Severity.DEBUG;
      // DEBUG
      case 10000:
        return Severity.DEBUG;
      // INFO
      case 20000:
        return Severity.INFO;
      // WARNING
      case 30000:
        return Severity.WARNING;
      // ERROR
      case 40000:
        return Severity.ERROR;
      default:
        return Severity.DEFAULT;
    }
  }
}
