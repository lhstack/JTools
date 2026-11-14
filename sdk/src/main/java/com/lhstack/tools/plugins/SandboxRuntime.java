package com.lhstack.tools.plugins;

import com.intellij.openapi.project.Project;

/** 仅供独立 IDE 沙箱使用；被调试插件不登记到正式插件管理器。 */
public interface SandboxRuntime {
    void start(Project project, SandboxHost host) throws Exception;
}
