package example;

import com.lhstack.tools.plugins.IPlugin;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.net.URL;
import java.util.Objects;

public final class DemoPlugin implements IPlugin {
    // 宿主可在多个项目/容器请求面板；每次返回新组件，不保存全局 currentPanel。
    @Override
    public boolean supportMultiOpens() { return false; }

    @Override
    public JComponent createPanel(String locationHash) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel("Hello JTools", JLabel.CENTER), BorderLayout.CENTER);
        return panel;
    }

    @Override public Icon pluginIcon() { return icon("/icons/plugin.png", 48); }
    @Override public Icon pluginTabIcon() { return icon("/icons/tab.png", 14); }
    @Override public String pluginName() { return "JTools Demo"; }
    @Override public String pluginDesc() { return "JTools 扩展插件示例"; }
    @Override public String pluginVersion() { return "1.0.0"; }

    private Icon icon(String path, int size) {
        URL resource = Objects.requireNonNull(DemoPlugin.class.getResource(path), path);
        ImageIcon icon = new ImageIcon(resource);
        if (icon.getIconWidth() != size || icon.getIconHeight() != size) {
            throw new IllegalArgumentException(path + " 必须为 " + size + "×" + size);
        }
        return icon;
    }
}
