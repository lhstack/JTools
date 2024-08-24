package com.lhstack.tools.plugins;

import com.intellij.openapi.project.Project;

import javax.swing.*;

public interface IPlugin {

    /**
     * 加载函数 每个项目打开都会加载一次
     */
    default void openProject(Project project) {

    }

    /**
     * 插件每次打开会调用
     *
     * @param project
     * @return
     */
    default JComponent createPanel(Project project) {
        return createPanel();
    }


    default JComponent createPanel() {
        return null;
    }

    /**
     * 插件每次打开回调
     *
     * @param project
     */
    default void showPanel(Project project) {

    }

    /**
     * 插件每次关闭回调
     *
     * @param project
     */
    default void closePanel(Project project) {

    }

    /**
     * 项目关闭会回调
     */
    default void closeProject(Project project) {

    }

    /**
     * 安装
     */
    default void install() {

    }

    /**
     * 卸载
     */
    default void unInstall() {

    }

    /**
     * 插件图标
     *
     * @return
     */
    Icon pluginIcon();

    /**
     * 插件在tab中的图标 13*13
     *
     * @return
     */
    Icon pluginTabIcon();

    /**
     * 插件名称
     *
     * @return
     */
    String pluginName();

    /**
     * 插件描述
     *
     * @return
     */
    String pluginDesc();

    /**
     * 插件版本
     *
     * @return
     */
    String pluginVersion();
}
