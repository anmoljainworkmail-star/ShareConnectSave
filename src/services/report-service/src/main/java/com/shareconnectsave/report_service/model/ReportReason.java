package com.shareconnectsave.report_service.model;

/**
 * Open/Closed (SOLID O): report reason categories live here as enum
 * constants, not as a free-text string validated by scattered if/else
 * checks scattered across controllers/services. Adding a new reason later
 * means adding one line to this enum — no existing validation logic has to
 * change, and every switch/when over ReportReason the compiler can check
 * will immediately flag the new case as unhandled instead of silently
 * falling through.
 */
public enum ReportReason {
    HARASSMENT,
    FAKE_PROFILE,
    INAPPROPRIATE_CONTENT,
    ASKED_FOR_MONEY,
    OTHER
}
