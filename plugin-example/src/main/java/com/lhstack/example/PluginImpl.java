package com.lhstack.example;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.lhstack.tools.plugins.*;

import javax.swing.*;
import java.util.HashMap;
import java.util.Map;

public class PluginImpl implements IPlugin {

    private final Map<String, Runnable> disables = new HashMap<>();

    public PluginImpl() {

    }

    @Override
    public JComponent createPanel(Project project) {
        return Helper.languageTextField("JAVA",project.getLocationHash(),consumer -> {
           consumer.accept("Hello World");
        },run -> disables.put(project.getLocationHash(),run),str -> {
            System.out.println("str");
        });
    }

    @Override
    public boolean supportMultiOpens() {
        return true;
    }

    @Override
    public void openProject(Project project, Logger logger, Runnable openThisPage) {
//        throw new RuntimeException("111");
    }

    @Override
    public Support support(Integer jToolsVersion, IdeInfo ideInfo) {
        return Support.NOT_SUPPORT;
    }

    @Override
    public void closeProject(String projectHash) {
        Runnable remove = disables.remove(projectHash);
        if(remove != null) {
            remove.run();
        }
    }

    @Override
    public void install() {
//        throw new RuntimeException("111");
    }



    @Override
    public void unInstall() {
//        throw new RuntimeException("111");
    }

    @Override
    public void appClose() {
//        throw new RuntimeException("111");
    }

    @Override
    public Icon pluginIcon() {
        return IconLoader.findIcon("icons/logo.svg", PluginImpl.class);
    }

    @Override
    public Icon pluginTabIcon() {
        return IconLoader.findIcon("icons/tabIcon.svg", PluginImpl.class);
    }


    @Override
    public String pluginName() {
        return "Demo插件";
    }

    @Override
    public String pluginDesc() {
        return "这是一个测试插件";
    }

    @Override
    public String pluginVersion() {
        return "0.0.2";
    }
}
