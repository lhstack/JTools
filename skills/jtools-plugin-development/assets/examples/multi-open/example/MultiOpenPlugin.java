package example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lhstack.tools.plugins.FunctionCalling;
import com.lhstack.tools.plugins.IPlugin;
import com.lhstack.tools.plugins.Logger;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 每项目共享业务状态，每次创建独立视图；示例函数不访问敏感项目资源。 */
public final class MultiOpenPlugin implements IPlugin {
    private final Map<String, ProjectState> projects = new ConcurrentHashMap<>();
    // 仅在 EDT 访问，按实际组件身份追踪；不是 hash -> 唯一面板缓存。
    private final Map<JComponent, CounterPanel> panels = new IdentityHashMap<>();
    private boolean stopped;

    @Override public boolean supportMultiOpens() { return true; }

    @Override
    public void openProject(String projectLocationHash, Logger logger, Runnable openThisPage) {
        projectState(projectLocationHash);
    }

    @Override
    public JComponent createPanel(String projectLocationHash) {
        requireEdt();
        CounterPanel panel = new CounterPanel(projectLocationHash, projectState(projectLocationHash));
        panels.put(panel, panel);
        return panel;
    }

    @Override
    public void showPanel(String projectLocationHash, JComponent pluginPanel) {
        requireEdt();
        CounterPanel panel = panels.get(pluginPanel);
        if (panel == null || !panel.projectLocationHash.equals(projectLocationHash)) {
            throw new IllegalStateException("面板未登记或项目不匹配");
        }
        panel.refresh();
    }

    @Override
    public void closePanel(String projectLocationHash, JComponent pluginPanel) {
        requireEdt();
        CounterPanel panel = panels.get(pluginPanel);
        if (panel == null) return; // 已关闭的同一面板，无剩余资源。
        if (!panel.projectLocationHash.equals(projectLocationHash)) {
            throw new IllegalArgumentException("面板项目不匹配");
        }
        panels.remove(pluginPanel);
        panel.close();
    }

    @Override
    public void closeProject(String projectLocationHash) {
        // 先使旧函数持有的状态失效，不在函数 call 中重建项目。
        synchronized (this) {
            ProjectState state = projects.remove(projectLocationHash);
            if (state != null) state.close();
        }
        onEdt(() -> {
            for (CounterPanel panel : new ArrayList<>(panels.values())) {
                if (panel.projectLocationHash.equals(projectLocationHash)) {
                    panels.remove(panel);
                    panel.close();
                }
            }
        });
    }

    @Override public void appClose() { closeAll(); }
    @Override public void unInstall() { closeAll(); }

    private void closeAll() {
        synchronized (this) {
            stopped = true;
            projects.values().forEach(ProjectState::close);
            projects.clear();
        }
        onEdt(() -> {
            panels.values().forEach(CounterPanel::close);
            panels.clear();
        });
    }

    @Override
    public List<FunctionCalling> functionCallings(String projectLocationHash) {
        // 不依赖 createPanel；列表对象绑定项目状态，而非“最近打开的面板”。
        return Collections.singletonList(new CounterFunction(projectState(projectLocationHash)));
    }

    private synchronized ProjectState projectState(String projectLocationHash) {
        if (stopped) throw new IllegalStateException("插件已关闭");
        if (projectLocationHash == null || projectLocationHash.isEmpty()) {
            throw new IllegalArgumentException("缺少项目标识");
        }
        return projects.computeIfAbsent(projectLocationHash, ignored -> new ProjectState());
    }

    private static final class ProjectState {
        private boolean closed;
        private int value;

        synchronized int value() {
            checkOpen();
            return value;
        }

        synchronized int increment(int delta) {
            checkOpen();
            value = Math.addExact(value, delta);
            return value;
        }

        synchronized void close() { closed = true; }

        private void checkOpen() {
            if (closed) throw new IllegalStateException("项目上下文已关闭，请重新列举有效项目函数");
        }
    }

    private static final class CounterPanel extends JPanel {
        private final String projectLocationHash;
        private final ProjectState state;
        private final JLabel label = new JLabel();
        private boolean closed;

        CounterPanel(String projectLocationHash, ProjectState state) {
            super(new BorderLayout());
            this.projectLocationHash = projectLocationHash;
            this.state = state;
            add(label, BorderLayout.CENTER);
            refresh();
        }

        void refresh() {
            if (closed) throw new IllegalStateException("面板已关闭");
            label.setText(projectLocationHash + " : " + state.value());
        }

        void close() {
            if (closed) return;
            closed = true;
            // 在此关闭当前面板的订阅、任务、监听器，不关闭共享 ProjectState。
            removeAll();
        }
    }

    private static final class CounterFunction implements FunctionCalling {
        private final ProjectState state;

        CounterFunction(ProjectState state) { this.state = state; }

        @Override public String name() { return "increment_counter"; }
        @Override public String description() {
            return "增加已绑定项目的内存计数，delta 为 1–100 的整数；不读写文件。";
        }
        @Override public String parameters() {
            return "{\"type\":\"object\",\"properties\":{\"delta\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}},\"required\":[\"delta\"],\"additionalProperties\":false}";
        }
        @Override public String call(String argumentsJson) { return execute(argumentsJson, null); }
        @Override
        public String call(String args, String sessionName, String sessionId, String provider, String model) {
            // 本函数不保存会话缓存；如需缓存，键为项目标识 + sessionId。
            return execute(args, sessionId);
        }

        private String execute(String argumentsJson, String sessionId) {
            JsonElement input = JsonParser.parseString(argumentsJson);
            if (!input.isJsonObject()) throw new IllegalArgumentException("参数必须为对象");
            JsonObject object = input.getAsJsonObject();
            JsonElement delta = object.get("delta");
            if (object.size() != 1 || delta == null || !delta.isJsonPrimitive()
                    || !delta.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("只允许必填整数 delta");
            }
            int amount = delta.getAsBigDecimal().intValueExact();
            if (amount < 1 || amount > 100) throw new IllegalArgumentException("delta 必须为 1–100");
            JsonObject result = new JsonObject();
            result.addProperty("value", state.increment(amount));
            if (sessionId != null) result.addProperty("session_id", sessionId);
            return result.toString();
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("UI 操作必须在 EDT");
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 UI 清理被中断", error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("UI 清理失败", cause);
        }
    }

    @Override public Icon pluginIcon() { return icon("/icons/plugin.png"); }
    @Override public Icon pluginTabIcon() { return icon("/icons/tab.png"); }
    @Override public String pluginName() { return "JTools Multi Open"; }
    @Override public String pluginDesc() { return "项目服务隔离与独立多开面板示例"; }
    @Override public String pluginVersion() { return "1.0.0"; }

    private Icon icon(String path) {
        return new ImageIcon(Objects.requireNonNull(MultiOpenPlugin.class.getResource(path), path));
    }
}
