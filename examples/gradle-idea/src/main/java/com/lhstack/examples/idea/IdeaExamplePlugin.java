package com.lhstack.examples.idea;

import com.intellij.openapi.project.Project;
import com.lhstack.tools.plugins.IPlugin;
import java.awt.BorderLayout;
import java.util.Objects;
import javax.swing.*;

/** 直接使用 IDEA Project API；平台由宿主提供，不打入插件。 */
public final class IdeaExamplePlugin implements IPlugin {
    @Override
    public JComponent createPanel(Project project) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JTextField input = new JTextField("Hello IDEA");
        input.setName("example-input");
        JLabel result = new JLabel("尚未应用");
        result.setName("example-result");
        JLabel projectName = new JLabel("项目：" + project.getName());
        projectName.setName("example-project");
        JButton apply = new JButton("应用");
        apply.setName("example-apply");
        apply.addActionListener(event -> result.setText(formatMessage(input.getText())));
        JPanel content = new JPanel(new BorderLayout());
        content.add(projectName, BorderLayout.NORTH);
        content.add(result, BorderLayout.CENTER);
        panel.add(input, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        panel.add(apply, BorderLayout.SOUTH);
        return panel;
    }

    // 可在这里设置 JVM 行断点并检查 text 局部变量。
    public String formatMessage(String text) {
        return "已应用：" + text.trim();
    }

    @Override public String pluginName() { return "JTools IDEA Example"; }
    @Override public String pluginDesc() { return "Java 11 / IDEA Project API / Gradle Shadow 示例"; }
    @Override public String pluginVersion() { return "1.0.0"; }
    @Override public Icon pluginIcon() { return icon("/icons/plugin.png", 48); }
    @Override public Icon pluginTabIcon() { return icon("/icons/tab.png", 14); }

    private Icon icon(String path, int size) {
        ImageIcon icon = new ImageIcon(Objects.requireNonNull(getClass().getResource(path), path));
        if (icon.getIconWidth() != size || icon.getIconHeight() != size) {
            throw new IllegalArgumentException(path + " 必须为 " + size + "×" + size);
        }
        return icon;
    }
}
