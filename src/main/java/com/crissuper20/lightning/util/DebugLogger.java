package com.crissuper20.lightning.util;

import java.util.logging.Level;
import java.util.logging.Logger;

public class DebugLogger {
    private final Logger logger;
    private final boolean debugEnabled;
    private final String prefix;

    public DebugLogger(Logger logger, boolean debugEnabled) {
        this(logger, debugEnabled, "");
    }

    public DebugLogger(Logger logger, boolean debugEnabled, String prefix) {
        this.logger = logger;
        this.debugEnabled = debugEnabled;
        this.prefix = prefix.isEmpty() ? "" : "[" + prefix + "] ";
    }

    public void info(String msg) {
        logger.info(prefix + msg);
    }

    public void debug(String msg) {
        if (debugEnabled) {
            logger.info(prefix + "[DEBUG] " + msg);
        }
    }

    public void warn(String msg) {
        logger.warning(prefix + msg);
    }

    public void warning(String msg) {
        warn(msg);
    }

    public void error(String msg) {
        error(msg, null);
    }

    public void error(String msg, Throwable t) {
        logger.log(Level.SEVERE, prefix + "[ERROR] " + msg, t);
        if (debugEnabled && t != null) {
            t.printStackTrace();
        }
    }

    public void severe(String msg) {
        logger.severe(prefix + msg);
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public DebugLogger withPrefix(String prefix) {
        return new DebugLogger(logger, debugEnabled, prefix);
    }
}