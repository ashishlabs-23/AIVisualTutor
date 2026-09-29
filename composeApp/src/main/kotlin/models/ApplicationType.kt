package models

/**
 * The set of applications the AI Visual Tutor knows how to tutor.
 * Phase 1 only needs this to drive the mock tutorial data and the
 * application selector UI - there is no real detection of what app
 * the user has open yet.
 */
enum class ApplicationType(val displayName: String) {
    BLENDER("Blender"),
    PDF("PDF"),
    EXCEL("Excel")
}
