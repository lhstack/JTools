package com.lhstack.example;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.lhstack.tools.plugins.Helper;
import com.lhstack.tools.plugins.IPlugin;
import com.lhstack.tools.plugins.Logger;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class PluginImpl implements IPlugin {
    JButton button = new JButton("测试按钮");

    public PluginImpl() {
        button.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                Messages.showInfoMessage("点击", "111");
            }
        });
    }


    @Override
    public void openProject(Project project, Logger logger, Runnable openThisPage) {
//        throw new RuntimeException("111");
        openThisPage.run();
    }

    @Override
    public void install() {
//        throw new RuntimeException("111");
    }

    @Override
    public JComponent createPanel(Project project) {
//        throw new RuntimeException("111");
        return Helper.actionButton(AllIcons.Actions.AddFile, null, null, null, 24, 24, str -> {
            Messages.showInfoMessage(str, str);
        });
    }

    @Override
    public void showPanel(String projectHash) {
//        throw new RuntimeException("111");
    }

    @Override
    public void closePanel(String projectHash) {
//        throw new RuntimeException("222");
    }

    @Override
    public void closeProject(Project project) {
        System.out.println("插件关闭: " + this.pluginName());
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
