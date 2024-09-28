package com.lhstack.tools.plugins;

public interface Logger {

    void info(Object msg);

    void warn(Object msg);

    void debug(Object msg);

    void error(Object msg);

    void activeConsolePanel();
}
