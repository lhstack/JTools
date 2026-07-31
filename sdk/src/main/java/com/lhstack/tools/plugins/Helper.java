package com.lhstack.tools.plugins;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.ide.highlighter.HighlighterFactory;
import com.intellij.lang.Language;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileChooser.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.TreeUIHelper;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class Helper {

    public static Key<Logger> JTOOLS_SYS_LOGGER = Key.create("JTOOLS_SYS_LOGGER");

    public static Integer JTOOLS_VERSION = 1151;

    private static final IdeInfo IDE_INFO;

    static {
        ApplicationInfo applicationInfo = ApplicationInfo.getInstance();
        IDE_INFO = new IdeInfo(applicationInfo.getApiVersion(),
                applicationInfo.getFullVersion(),
                applicationInfo.getMajorVersion(),
                applicationInfo.getMinorVersion(),
                applicationInfo.getBuild().getBaselineVersion(),
                applicationInfo.getBuildDate(),
                applicationInfo.getVersionName(),
                applicationInfo.getFullApplicationName());
    }

    public static IdeInfo getIdeInfo() {
        return IDE_INFO;
    }

    public static Icon findIcon(String path, ClassLoader classLoader) {
        return IconLoader.findIcon(path, classLoader);
    }

    public static Icon findIcon(String path, Class<?> clazz) {
        return IconLoader.findIcon(path, clazz);
    }

    public static void restart() {
        ApplicationManager.getApplication().restart();
    }


    /**
     * @since 1.0.2
     * @param icon
     * @param hoverIcon
     * @param title
     * @param description
     * @param width
     * @param height
     * @param isSelected
     * @param action
     * @return
     */
    @Since(value = "1.0.1",changeNotes = "1.0.2修改: 新增isSelected参数")
    public static JComponent actionButton(Icon icon, Icon hoverIcon, String title, String description, int width, int height, Supplier<Boolean> isSelected, Consumer<String> action) {
        Presentation presentation = new Presentation();
        Optional.ofNullable(title).ifPresent(presentation::setText);
        Optional.ofNullable(icon).ifPresent(presentation::setIcon);
        Optional.ofNullable(hoverIcon).ifPresent(presentation::setHoveredIcon);
        Optional.ofNullable(description).ifPresent(presentation::setDescription);
        return new ActionButton(new AnAction() {

            @Override
            public void update(@NotNull AnActionEvent e) {
                super.update(e);
                Toggleable.setSelected(e.getPresentation(), isSelected.get());
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                action.accept(Optional.ofNullable(e.getData(LangDataKeys.PROJECT)).map(Project::getLocationHash).orElse(""));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        }, presentation, ActionPlaces.UNKNOWN, new Dimension(width, height));
    }


    /**
     * @since 1.0.2
     * @param icon
     * @param title
     * @param width
     * @param height
     * @param isSelected
     * @param action
     * @return
     */
    @Since("1.0.2")
    public static JComponent actionButton(Icon icon, String title, int width, int height, Supplier<Boolean> isSelected, Consumer<String> action) {
        return actionButton(icon, null, title, null, width, height, isSelected, action);
    }

    public static JComponent actionButton(Icon icon, String title, int width, int height, Consumer<String> action) {
        return actionButton(icon, null, title, null, width, height, () -> false, action);
    }

    public static JComponent actionButton(Icon icon, String title, Consumer<String> action) {

        return actionButton(icon, null, title, null, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.width, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.height, () -> false, action);
    }


    /**
     * @since 1.0.2
     * @param icon
     * @param title
     * @param isSelected
     * @param action
     * @return
     */
    @Since("1.0.2")
    public static JComponent actionButton(Icon icon, String title, Supplier<Boolean> isSelected, Consumer<String> action) {

        return actionButton(icon, null, title, null, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.width, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.height, isSelected, action);
    }


    /**
     * @since 1.0.2
     * @param targetComponent
     * @param horizontal
     * @param place
     * @param actions
     * @return
     */
    @Since("1.0.2")
    public static JComponent createActionToolbar(JComponent targetComponent, boolean horizontal, String place, Action... actions) {
        DefaultActionGroup defaultActionGroup = new DefaultActionGroup();
        Stream.of(actions).map(item -> new AnAction(item::title, item::description, item.icon()) {

            @Override
            public void update(@NotNull AnActionEvent e) {
                super.update(e);
                Toggleable.setSelected(e.getPresentation(), item.isSelected());
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                item.actionPerformed();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        }).forEach(defaultActionGroup::addAction);
        ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(place, defaultActionGroup, horizontal);
        actionToolbar.setTargetComponent(targetComponent);
        return actionToolbar.getComponent();
    }


    /**
     * 语言文本字段
     *
     * @param language               语言
     * @param locationHash           位置哈希
     * @param setValueSupplier       设定值供应商 设置languageTextField值
     * @param dispose                回收languageTextField回调
     * @param documentChangeListener 文档更改侦听器
     * @return {@link JComponent }
     */
    public static JComponent languageTextField(String language, String locationHash, Consumer<Consumer<String>> setValueSupplier, Consumer<Runnable> dispose, Consumer<String> documentChangeListener) {
        return languageTextField(language, locationHash, "", false, false, setValueSupplier, dispose, documentChangeListener);
    }

    public static JComponent languageTextField(String language,
                                               String locationHash,
                                               String defaultValue,
                                               Boolean oneLineMode,
                                               Boolean isViewer,
                                               Consumer<Consumer<String>> setValueSupplier,
                                               Consumer<Runnable> dispose,
                                               Consumer<String> documentChangeListener) {
        for (@NotNull Project openProject : ProjectManager.getInstance().getOpenProjects()) {
            if (StringUtils.equals(openProject.getLocationHash(), locationHash)) {
                LanguageTextField textField = new LanguageTextField(Language.findLanguageByID(language), openProject, defaultValue, oneLineMode) {
                    @Override
                    protected @NotNull EditorEx createEditor() {

                        EditorEx editorEx = (EditorEx) EditorFactory.getInstance()
                                .createEditor(getDocument(), getProject(), getFileType(), isViewer);
                        editorEx.setHighlighter(HighlighterFactory.createHighlighter(getProject(), getFileType()));
                        PsiFile psiFile = PsiDocumentManager.getInstance(getProject()).getPsiFile(
                                editorEx.getDocument()
                        );
                        if (psiFile != null) {
                            DaemonCodeAnalyzer.getInstance(getProject()).setHighlightingEnabled(psiFile, true);
                        }
                        editorEx.setBorder(null);
                        EditorSettings settings = editorEx.getSettings();
                        settings.setAdditionalLinesCount(0);
                        settings.setAdditionalColumnsCount(1);
                        settings.setLineNumbersShown(true);
                        settings.setLineCursorWidth(1);
                        settings.setLineMarkerAreaShown(false);
                        settings.setRightMargin(-1);
                        dispose.accept(() -> {
                            if (!editorEx.isDisposed()) {
                                EditorFactory.getInstance().releaseEditor(editorEx);
                            }
                        });
                        return editorEx;
                    }
                };
                setValueSupplier.accept(str -> {
                    if (str != null) {
                        textField.setText(str);
                    }
                });
                textField.addDocumentListener(new DocumentListener() {
                    @Override
                    public void documentChanged(@NotNull DocumentEvent event) {
                        documentChangeListener.accept(event.getDocument().getText());
                    }
                });
                return textField;
            }
        }
        return null;
    }


    /**
     *
     * 在编辑器中打开文件
     *
     * @param locationHash
     * @since 1.0.2
     * @param path
     */
    @Since("1.0.2")
    public static void openFileInEditor(String locationHash, Path path) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (StringUtils.equals(locationHash, project.getLocationHash())) {
                    VirtualFile virtualFile = VirtualFileManager.getInstance().findFileByNioPath(path);
                    if (virtualFile == null) {
                        throw new RuntimeException("Can't find file,path: " + path);
                    }
                    FileEditorManager.getInstance(project).openFile(virtualFile, true);
                }
            }
        });
    }


    /**
     * 通知
     *
     * @param locationHash
     * @param title        标题
     * @param content      内容
     * @param type         类型 IDE_UPDATE,INFORMATION,WARNING,ERROR
     */
    @Since(value = "1.0.1",changeNotes = "type: IDE_UPDATE,INFORMATION,WARNING,ERROR")
    public static void notify(String locationHash, String title, String content, String type) {
        for (@NotNull Project openProject : ProjectManager.getInstance().getOpenProjects()) {
            if (StringUtils.equals(locationHash, openProject.getLocationHash())) {
                Notifications.Bus.notify(new Notification("", title, content, NotificationType.valueOf(type)), openProject);
            }
        }
    }


    public static Logger getSysLogger(String locationHash) {
        for (@NotNull Project openProject : ProjectManager.getInstance().getOpenProjects()) {
            if (StringUtils.equals(locationHash, openProject.getLocationHash())) {
                return openProject.getUserData(JTOOLS_SYS_LOGGER);
            }
        }
        return null;
    }


    public static String getProjectBasePath(String locationHash) {
        for (@NotNull Project openProject : ProjectManager.getInstance().getOpenProjects()) {
            if (StringUtils.equals(locationHash, openProject.getLocationHash())) {
                return openProject.getBasePath();
            }
        }
        throw new RuntimeException("Can't find project base path");
    }

    public static void treeSpeedSearch(JTree tree, boolean canExpand, @NotNull Function<? super TreePath, String> presentableStringFunction) {
        TreeUIHelper instance = TreeUIHelper.getInstance();
        instance.installTreeSpeedSearch(tree, presentableStringFunction::apply, canExpand);
    }


    /**
     * @since 1.0.2
     * @param locationHash
     * @param title
     * @param description
     * @param filter
     * @param fileConsumer
     */
    @Since("1.0.2")
    public static void chooseFile(String locationHash, String title, String description, Function<String, Boolean> filter, Consumer<String> fileConsumer) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (@NotNull Project openProject : ProjectManager.getInstance().getOpenProjects()) {
                if (StringUtils.equals(locationHash, openProject.getLocationHash())) {
                    FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(true, false, true, true, false, false)
                            .withTitle(title)
                            .withDescription(description)
                            .withFileFilter(item -> filter.apply(item.getPresentableUrl()));
                    FileChooserDialog fileChooser = FileChooserFactory.getInstance().createFileChooser(fileChooserDescriptor, openProject, null);
                    VirtualFile[] choose = fileChooser.choose(openProject);
                    if (choose.length > 0) {
                        fileConsumer.accept(choose[0].getPresentableUrl());
                    }
                }
            }
        });
    }


    /**
     * @since 1.0.2
     * @param locationHash
     * @param title
     * @param description
     * @param filter
     * @param fileConsumer
     */
    @Since("1.0.2")
    public static void chooseFiles(String locationHash, String title, String description, Function<String, Boolean> filter, Consumer<String[]> fileConsumer) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (@NotNull Project openProject : ProjectManager.getInstance().getOpenProjects()) {
                if (StringUtils.equals(locationHash, openProject.getLocationHash())) {
                    FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(true, false, true, true, false, true)
                            .withTitle(title)
                            .withDescription(description)
                            .withFileFilter(item -> filter.apply(item.getPresentableUrl()));
                    FileChooserDialog fileChooser = FileChooserFactory.getInstance().createFileChooser(fileChooserDescriptor, openProject, null);
                    VirtualFile[] choose = fileChooser.choose(openProject);
                    if (choose.length > 0) {
                        fileConsumer.accept(Stream.of(choose).map(VirtualFile::getPresentableUrl).toArray(String[]::new));
                    }
                }
            }
        });
    }

    /**
     * @since 1.0.2
     * @param locationHash
     * @param title
     * @param description
     * @param filter
     * @param fileConsumer
     */
    @Since("1.0.2")
    public static void chooseDirector(String locationHash, String title, String description, Function<String, Boolean> filter, Consumer<String> fileConsumer) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (@NotNull Project openProject : ProjectManager.getInstance().getOpenProjects()) {
                if (StringUtils.equals(locationHash, openProject.getLocationHash())) {
                    FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(false, true, false, false, false, false)
                            .withTitle(title)
                            .withDescription(description)
                            .withFileFilter(item -> filter.apply(item.getPresentableUrl()));
                    FileChooserDialog fileChooser = FileChooserFactory.getInstance().createFileChooser(fileChooserDescriptor, openProject, null);
                    VirtualFile[] choose = fileChooser.choose(openProject);
                    if (choose.length > 0) {
                        fileConsumer.accept(choose[0].getPresentableUrl());
                    }
                }
            }
        });
    }

    /**
     * @since 1.0.2
     * @param locationHash
     * @param title
     * @param description
     * @param filename
     * @param fileConsumer
     * @param extension
     */
    @Since("1.0.2")
    public static void chooseSaveFile(String locationHash, String title, String description, String filename, Consumer<File> fileConsumer, String... extension) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (@NotNull Project openProject : ProjectManager.getInstance().getOpenProjects()) {
                if (StringUtils.equals(locationHash, openProject.getLocationHash())) {
                    FileSaverDescriptor descriptor = new FileSaverDescriptor(title, description);
                    descriptor.withExtensionFilter("扩展",extension);
                    FileSaverDialog saveFileDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, openProject);
                    VirtualFileWrapper wrapper = saveFileDialog.save(filename);
                    if (wrapper != null) {
                        fileConsumer.accept(wrapper.getFile());
                    }
                }
            }
        });
    }

}
