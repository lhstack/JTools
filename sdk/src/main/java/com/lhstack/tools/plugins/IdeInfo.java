package com.lhstack.tools.plugins;

import java.util.Calendar;

public class IdeInfo {

    private final String apiVersion;

    private final String fullVersion;

    private final String majorVersion;

    private final String minorVersion;

    private final Integer buildBaselineVersion;

    private final Calendar buildDate;

    private final String versionName;

    private final String fullApplicationName;

    public IdeInfo(String apiVersion, String fullVersion, String majorVersion, String minorVersion, Integer buildBaselineVersion,  Calendar buildDate, String versionName, String fullApplicationName) {
        this.apiVersion = apiVersion;
        this.fullVersion = fullVersion;
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.buildBaselineVersion = buildBaselineVersion;
        this.buildDate = buildDate;
        this.versionName = versionName;
        this.fullApplicationName = fullApplicationName;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getFullVersion() {
        return fullVersion;
    }

    public String getMajorVersion() {
        return majorVersion;
    }

    public String getMinorVersion() {
        return minorVersion;
    }

    public Integer getBuildBaselineVersion() {
        return buildBaselineVersion;
    }

    public Calendar getBuildDate() {
        return buildDate;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getFullApplicationName() {
        return fullApplicationName;
    }

    @Override
    public String toString() {
        return "IdeInfo{" +
                "apiVersion='" + apiVersion + '\'' +
                ", fullVersion='" + fullVersion + '\'' +
                ", majorVersion='" + majorVersion + '\'' +
                ", minorVersion='" + minorVersion + '\'' +
                ", buildBaselineVersion=" + buildBaselineVersion +
                ", buildDate=" + buildDate +
                ", versionName='" + versionName + '\'' +
                ", fullApplicationName='" + fullApplicationName + '\'' +
                '}';
    }
}
