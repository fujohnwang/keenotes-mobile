package cn.keevol.keenotes.ui.note

import cn.keevol.keenotes.data.entity.Note
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object OnThisDayDebugMock {
    private val utcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun build(
        localDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<Note> {
        val currentYear = localDate.year
        val candidateYears = listOf(currentYear - 1, currentYear - 2, currentYear - 4)
            .filter { it >= 2000 }

        return candidateYears.mapIndexed { index, year ->
            val utcCreatedAt = localDate
                .withYear(year)
                .atTime(9 + index, 12 + index * 7, 0)
                .atZone(zoneId)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime()
                .format(utcFormatter)

            Note(
                id = -(index + 1).toLong(),
                content = buildContent(year, index),
                channel = "debug-mock",
                createdAt = utcCreatedAt
            )
        }.sortedByDescending { it.createdAt }
    }

    private fun buildContent(year: Int, index: Int): String {
        return when (index) {
            0 -> "Mock note from $year for On This Day testing. This entry is generated from Android debug mode."
            1 -> "Another historical note from $year. Use this to verify the left-top entry, list rendering, and enlarged card."
            else -> "Third mock note from $year. Toggle off debug mode to go back to real on-this-day data only."
        }
    }
}
