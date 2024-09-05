package com.lhstack.tools.plugins;

import com.intellij.openapi.project.Project;

import javax.swing.*;

public interface IPlugin {

    /**
     * 加载函数 每个项目打开都会加载一次
     *
     * @param project      项目
     * @param openThisPage 打开此页面,此功能仅UIPlugin有效
     */
    default void openProject(Project project, Runnable openThisPage) {
        this.openProject(project.getLocationHash(), openThisPage);
    }

    default void openProject(String projectHash, Runnable openThisPage) {

    }

    default PluginType pluginType() {
        return PluginType.JAVA;
    }

    /**
     * 插件每次打开会调用
     *
     * @param project
     * @return
     */
    default JComponent createPanel(Project project) {
        return createPanel(project.getLocationHash());
    }


    default JComponent createPanel(String projectHash) {
        return null;
    }

    /**
     * 插件每次打开回调
     *
     * @param project
     */
    default void showPanel(Project project) {
        showPanel(project.getLocationHash());
    }

    default void showPanel(String projectHash) {

    }

    /**
     * 插件每次关闭回调
     *
     * @param project
     */
    default void closePanel(Project project) {
        closePanel(project.getLocationHash());
    }

    default void closePanel(String projectHash) {

    }

    /**
     * 项目关闭会回调
     */
    default void closeProject(Project project) {
        closeProject(project.getLocationHash());
    }

    default void closeProject(String projectHash) {

    }

    /**
     * app启动时会触发 安装
     */
    default void install() {

    }

    /**
     * 卸载
     */
    default void unInstall() {

    }

    /**
     * app关闭时触发
     */
    default void appClose() {

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
