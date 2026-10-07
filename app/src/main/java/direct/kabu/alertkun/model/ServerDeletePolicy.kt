package direct.kabu.alertkun.model

/** Stable names are persisted per account; unknown/legacy policies must retain mail. */
enum class ServerDeletePolicy {
    IMMEDIATELY,
    AFTER_1_DAY,
    AFTER_1_WEEK,
    AFTER_10_DAYS,
    AFTER_15_DAYS,
    AFTER_1_MONTH,
    NEVER,
}
