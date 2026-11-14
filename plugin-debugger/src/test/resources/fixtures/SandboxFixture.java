package fixture;

import com.lhstack.tools.plugins.IPlugin;
import javax.swing.*;

public class SandboxFixture implements IPlugin {
    private final JLabel label = new JLabel("ready");
    public String pluginName() { return "Sandbox acceptance fixture"; }
    public String pluginDesc() { return "Acceptance test only"; }
    public String pluginVersion() { return "1"; }
    public Icon pluginIcon() { return UIManager.getIcon("FileView.computerIcon"); }
    public Icon pluginTabIcon() { return pluginIcon(); }
    public void install() {
        int answer = 42;
        System.out.println("SANDBOX_FIXTURE_INSTALLED=" + answer);
    }
    public JComponent createPanel(String project) {
        JPanel panel = new JPanel();
        JTextField input = new JTextField("initial", 20);
        input.setName("fixture-input");
        JButton button = new JButton("Apply");
        button.setName("fixture-apply");
        button.addActionListener(event -> label.setText(input.getText()));
        panel.add(input);
        panel.add(button);
        panel.add(label);
        return panel;
    }
}
