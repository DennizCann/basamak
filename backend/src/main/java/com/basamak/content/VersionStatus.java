package com.basamak.content;

/**
 * Lifecycle of one saved version.
 * DRAFT -> IN_REVIEW -> (APPROVED | CHANGES_REQUESTED) ; APPROVED -> (SCHEDULED ->) PUBLISHED -> ARCHIVED.
 * SUPERSEDED: a draft that was replaced by a newer save before it was ever submitted.
 * ARCHIVED versions were published once and can be re-published (rollback) without a new review.
 */
public enum VersionStatus {
    DRAFT, IN_REVIEW, CHANGES_REQUESTED, APPROVED, SCHEDULED, PUBLISHED, ARCHIVED, SUPERSEDED
}
