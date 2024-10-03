package com.lhstack.tools.plugins;

import javax.swing.*;

public interface Action {

    Icon icon();

    String title();

    default String description() {
        return title();
    }

    void actionPerformed();

    default boolean isSelected() {
        return false;
    }

}
