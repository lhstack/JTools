package com.lhstack.examples.maven;

import com.lhstack.tools.plugins.IPlugin;
import java.awt.BorderLayout;
import java.util.Objects;
import javax.swing.*;

/** 纯 Swing 示例：每次创建独立组件，不保存跨项目的 currentPanel。 */
public final class MavenExamplePlugin implements IPlugin {
    @Override
    public JComponent createPanel(String locationHash) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JTextField input = new JTextField("Hello Maven");
        input.setName("example-input");
        JLabel result = new JLabel("尚未应用");
        result.setName("example-result");
        JButton apply = new JButton("应用");
        apply.setName("example-apply");
        apply.addActionListener(event -> result.setText(formatMessage(input.getText())));
        panel.add(input, BorderLayout.NORTH);
        panel.add(result, BorderLayout.CENTER);
        panel.add(apply, BorderLayout.SOUTH);
        return panel;
    }

    // 可在这里设置 JVM 行断点并检查 text 局部变量。
    public String formatMessage(String text) {
        return "已应用：" + text.trim();
    }

    @Override public String pluginName() { return "JTools Maven Example"; }
    @Override public String pluginDesc() { return "Java 11 / Swing / Maven Shade 示例"; }
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
