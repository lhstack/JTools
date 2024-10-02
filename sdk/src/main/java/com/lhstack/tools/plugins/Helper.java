package com.lhstack.tools.plugins;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;
import java.util.function.Consumer;

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
        }, presentation, ActionPlaces.UNKNOWN, new Dimension(width, height));
    }
}
