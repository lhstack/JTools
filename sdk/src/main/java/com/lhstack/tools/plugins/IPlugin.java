package com.lhstack.tools.plugins;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public interface IPlugin {

    /**
     * 加载函数 每个项目打开都会加载一次
     *
     * @param project      项目
     * @param openThisPage 打开此页面,此功能仅UIPlugin有效
     */
    default void openProject(Project project, Logger logger, Runnable openThisPage) {
        this.openProject(project.getLocationHash(), logger, openThisPage);
    }

    default void openProject(String locationHash, Logger logger, Runnable openThisPage) {

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


    default JComponent createPanel(String locationHash) {
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

    default void showPanel(String locationHash) {

    }

    /**
     * 插件每次关闭回调
     *
     * @param project
     */
    default void closePanel(Project project) {
        closePanel(project.getLocationHash());
    }

    default void closePanel(String locationHash) {

    }

    /**
     * 项目关闭会回调
     */
    default void closeProject(Project project) {
        closeProject(project.getLocationHash());
    }

    default void closeProject(String locationHash) {

    }

    /**
     * 安装成功之后是否需要重启
     *
     * @return
     */
    default boolean installRestart() {
        return false;
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


    /**
     * 支持jtools版本
     * @param jToolsVersion
     * @return
     */
    default boolean support(Integer jToolsVersion){
        return true;
    }

    default List<AnAction> tabPanelActions(Project project) {
        return this.swingTabPanelActions(project.getLocationHash()).stream().map(item -> {
            AnAction action = new AnAction(item::title) {

                @Override
                public void update(@NotNull AnActionEvent e) {
                    super.update(e);
                    Toggleable.setSelected(e.getPresentation(), item.isSelected());
                }

                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    item.actionPerformed();
                }

                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.BGT;
                }
            };
            Presentation presentation = action.getTemplatePresentation();
            Optional.ofNullable(item.description()).filter(str -> !str.isEmpty()).ifPresent(presentation::setDescription);
            Optional.ofNullable(item.icon()).ifPresent(presentation::setIcon);
            return action;
        }).collect(Collectors.toList());
    }

    default List<Action> swingTabPanelActions(String locationHash) {
        return Collections.emptyList();
    }
}
