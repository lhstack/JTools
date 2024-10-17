package com.lhstack.tools.plugins;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.actionSystem.impl.PresentationFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.IconLoader;
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

    public static JComponent actionButton(Icon icon, String title, int width, int height, Consumer<String> action) {
        return actionButton(icon, null, title, null, width, height, action);
    }

    public static JComponent actionButton(Icon icon, String title, Consumer<String> action) {

        return actionButton(icon, null, title, null,  ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.width,  ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE.height, action);
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
