package com.crissuper20.lightning.util;

import java.util.logging.Logger;

public class DebugLogger {
    private final Logger logger;
    private final boolean debugEnabled;

    public DebugLogger(Logger logger, boolean debugEnabled) {
        this.logger = logger;
        this.debugEnabled = debugEnabled;
    }

    public void info(String msg) {
        logger.info(msg);
    }

    public void debug(String msg) {
        if (debugEnabled) {
            logger.info("[DEBUG] " + msg);
        }
    }

    public void warn(String msg) {
        logger.warning(msg);
    }

    public void error(String msg, Throwable t) {
        logger.severe("[ERROR] " + msg);
        if (debugEnabled) {
            t.printStackTrace();
        }
    }
}/* might be useful later
    public DebugLogger getDebugLogger() {
        return debugLogger;
    }

    public LNService getLnService() {
        return lnService;
    }
}*/