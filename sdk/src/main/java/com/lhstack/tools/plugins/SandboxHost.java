package com.lhstack.tools.plugins;

import javax.swing.JComponent;

/** 宿主拥有生命周期和面板；调试基础设施拥有通信、自动化和进程管理。 */
public interface SandboxHost {
    String execute(String command, String argumentsJson) throws Exception;
    JComponent panel(String panelId);
    Object browser(String panelId);
}
