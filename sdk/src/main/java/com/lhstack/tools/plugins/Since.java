package com.lhstack.tools.plugins;

import java.lang.annotation.*;

/**
 * @since 1.0.2
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Since {

    String value();

    /**
     * 修改笔记
     * @return
     */
    String changeNotes() default "";
}
