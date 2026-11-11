package com.lhstack.tools.ide;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/** Selects the host editor API once; invocation failures never retry through another API. */
public final class EditorNavigation {
    private static final Method OPEN;
    private static final Object REQUEST;

    static {
        try {
            Method requestOpen = java.util.Arrays.stream(FileEditorManager.class.getMethods())
                    .filter(method -> method.getName().equals("requestOpenFile")
                            && method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == VirtualFile.class)
                    .findFirst().orElse(null);
            if (requestOpen == null) {
                OPEN = FileEditorManager.class.getMethod("openFile", VirtualFile.class, boolean.class);
                REQUEST = null;
            } else {
                OPEN = requestOpen;
                Class<?> requestType = requestOpen.getParameterTypes()[1];
                Object defaults = requestType.getMethod("defaults").invoke(null);
                REQUEST = requestType.getMethod("withRequestFocus", boolean.class).invoke(defaults, true);
            }
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private EditorNavigation() {}

    public static CompletableFuture<Void> openFile(Project project, VirtualFile file) {
        try {
            Object result = OPEN.invoke(FileEditorManager.getInstance(project), file,
                    REQUEST == null ? Boolean.TRUE : REQUEST);
            if (REQUEST == null) return CompletableFuture.completedFuture(null);
            return ((CompletableFuture<?>) result).thenApply(editor -> null);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Failed to open editor: " + file.getPath(), error.getCause());
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Editor API is not accessible", error);
        }
    }
}
