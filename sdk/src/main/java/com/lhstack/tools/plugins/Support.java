package com.lhstack.tools.plugins;

public class Support {

    private final Boolean support;

    private final String message;

    private final String title;

    public static final Support SUPPORT = new Support(true);

    public static final Support NOT_SUPPORT = new Support(false);

    public Support(Boolean support) {
        this(support, null, null);
    }

    public Support(Boolean support, String message) {
        this(support, null, message);
    }

    public Support(Boolean support, String title, String message) {
        this.support = support;
        this.title = title;
        this.message = message;
    }

    public String getTitle() {
        return title;
    }

    public Boolean getSupport() {
        return support;
    }

    public String getMessage() {
        return message;
    }
}