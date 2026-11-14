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
    public String executionThread() { return SwingUtilities.isEventDispatchThread() ? "edt" : "worker"; }
    public static String staticEcho(String value) { return "static:" + value; }
    private int count;
    public int increment() { return ++count; }
    public void fail() { throw new IllegalStateException("reflection-fixture-failure"); }
    public java.util.List<com.lhstack.tools.plugins.FunctionCalling> functionCallings(String project) {
        return java.util.Collections.singletonList(new com.lhstack.tools.plugins.FunctionCalling() {
            public String name() { return "fixture"; }
            public String parameters() { return "{}"; }
            public String call(String args) { return "called:" + args; }
        });
    }
}
