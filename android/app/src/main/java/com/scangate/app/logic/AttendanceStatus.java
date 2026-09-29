package com.scangate.app.logic;

/**
 * The labels a scan can get.
 *
 * These strings are stored in the database, so do not reword them without
 * also updating the laptop portal - the two are meant to agree.
 */
public final class AttendanceStatus {

    /** Scanned more than "early before" minutes ahead of the class start. */
    public static final String EARLY = "EARLY";

    /** Inside the window around the class start. */
    public static final String ON_TIME = "ON TIME";

    /** Past the end of the window. */
    public static final String LATE = "LATE";

    /** The number was read fine but is not in the roster. */
    public static final String NOT_REGISTERED = "NOT REGISTERED";

    private AttendanceStatus() {
    }
}
