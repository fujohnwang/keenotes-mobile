package cn.keevol.keenotes.data.entity

/**
 * Data class for yearly note count aggregation query result.
 */
data class YearlyNoteCount(
    val year: Int,
    val count: Int
)
