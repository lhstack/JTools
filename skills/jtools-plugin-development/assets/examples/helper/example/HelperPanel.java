package example;

import com.lhstack.tools.plugins.Helper;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.nio.file.Path;

/** 只依赖 SDK 编译；Helper 必须在真实 JTools/IDE 中运行。 */
public final class HelperPanel extends JPanel {
    private final String projectLocationHash;
    private final JLabel status = new JLabel("选择文件后请求在 IDE 中打开");
    private boolean closed;

    public HelperPanel(String projectLocationHash) {
        super(new BorderLayout());
        requireEdt();
        if (projectLocationHash == null || projectLocationHash.isEmpty()) {
            throw new IllegalArgumentException("缺少项目标识");
        }
        this.projectLocationHash = projectLocationHash;
        Icon icon = Helper.findIcon("/icons/plugin.png", HelperPanel.class);
        if (icon == null) throw new IllegalStateException("缺少图标资源");
        JComponent button = Helper.actionButton(icon, "选择并打开文件", eventHash -> chooseFile());
        add(button, BorderLayout.NORTH);
        add(status, BorderLayout.CENTER);
    }

    private void chooseFile() {
        requireEdt();
        if (closed) return;
        Helper.chooseFile(projectLocationHash, "选择文件", "在绑定的项目中打开", path -> true, selected -> {
            requireEdt();
            if (closed) return; // 用户选择期间面板可能已关闭。
            Helper.openFileInEditor(projectLocationHash, Path.of(selected));
            status.setText("已请求打开：" + selected); // void 异步返回，不能声称已成功打开。
            Helper.notify(projectLocationHash, "JTools Demo", "已提交编辑器打开请求", "INFORMATION");
        });
    }

    public void close() {
        requireEdt();
        if (closed) return;
        closed = true;
        removeAll();
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("UI 操作必须在 EDT");
    }
}
