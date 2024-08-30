package com.lhstack.example;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.lhstack.tools.plugins.IPlugin;

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
    public Boolean isUIPlugin() {
        return false;
    }

    @Override
    public void openProject(Project project, Runnable openThisPage) {
        openThisPage.run();
    }

    @Override
    public JComponent createPanel(Project project) {
        return button;
    }


    @Override
    public void closeProject(Project project) {
        System.out.println("插件关闭: " + this.pluginName());
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
