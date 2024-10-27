package com.lhstack.tools.plugins;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.ide.highlighter.HighlighterFactory;
import com.intellij.lang.Language;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.TreeSpeedSearch;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class Helper {

    public static Key<Logger> JTOOLS_SYS_LOGGER = Key.create("JTOOLS_SYS_LOGGER");


    public static Icon findIcon(String path, ClassLoader classLoader) {
        return IconLoader.findIcon(path, classLoader);
    }

    public static Icon findIcon(String path, Class<?> clazz) {
        return IconLoader.findIcon(path, clazz);
    }

    public static void restart() {
        ApplicationManager.getApplication().restart();
    }


    public static JComponent actionButton(Icon icon, Icon hoverIcon, String title, String description, int width, int height, Consumer<String> action) {

        Presentation presentation = new Presentation();
        Optional.ofNullable(title).ifPresent(presentation::setText);
        Optional.ofNullable(icon).ifPresent(presentation::setIcon);
        Optional.ofNullable(hoverIcon).ifPresent(presentation::setHoveredIcon);
        Optional.ofNullable(description).ifPresent(presentation::setDescription);
        return new ActionButton(new AnAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                action.accept(Optional.ofNullable(e.getData(LangDataKeys.PROJECT)).map(Project::getLocationHash).orElse(""));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        }, presentation, ActionPlaces.UNKNOWN, new Dimension(width, height));
    }

    /**
     * 通知
     *
     * @param locationHash
     * @param title        标题
     * @param content      内容
     * @param type         类型 IDE_UPDATE,INFORMATION,WARNING,ERROR
     */
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
                            EditorFactory.getInstance().releaseEditor(editorEx);
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

    public static JComponent actionButton(Icon icon, String title, int width, int height, Consumer<String> action) {
        return actionButton(icon, null, title, null, width, height, action);
    }

    public static JComponent actionButton(Icon icon, String title, Consumer<String> action) {

        return actionButton(icon, null, title, null, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.width, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.height, action);
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
        new TreeSpeedSearch(tree, canExpand, presentableStringFunction) {
            @Override
            protected boolean compare(@NotNull String text, @Nullable String pattern) {
                if (pattern != null) {
                    return text.contains(pattern);
                }
                return false;
            }

            @Override
            protected @Nullable Object findElement(@NotNull String s) {
                Object element = super.findElement(s);
                if (element != null) {
                    return element;
                }
                tree.clearSelection();
                return null;
            }

        };
    }
}
