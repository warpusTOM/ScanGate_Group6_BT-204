package com.scangate.app;

/**
 * Hand-written replacement for the file Gradle normally generates.
 *
 * This project is built with the raw Android build tools instead of Gradle,
 * so nothing writes BuildConfig.java for us. Keeping the same three names
 * Gradle would use means a later move to Gradle will not clash.
 */
public final class BuildConfig {

    public static final boolean DEBUG = false;
    public static final String APPLICATION_ID = "com.scangate.app";
    public static final String VERSION_NAME = "2.2.0";

    private BuildConfig() {
    }
}
