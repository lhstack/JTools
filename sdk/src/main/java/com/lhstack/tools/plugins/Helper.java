package com.lhstack.tools.plugins;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

public class Helper {
    public static Icon findIcon(String path,ClassLoader classLoader){
        return IconLoader.findIcon(path,classLoader);
    }

    public static Icon findIcon(String path,Class<?> clazz){
        return IconLoader.findIcon(path,clazz);
    }

    public static void restart(){
        ApplicationManager.getApplication().restart();
    }




}
