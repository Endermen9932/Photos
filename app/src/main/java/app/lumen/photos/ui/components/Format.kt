package app.lumen.photos.ui.components

import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object Format {
    private val locale = Locale.GERMANY
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, d. MMMM", locale)
    private val dayYearFormat = DateTimeFormatter.ofPattern("EEE, d. MMMM yyyy", locale)
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    private val fullFormat = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy · HH:mm", locale)
    private val shortFormat = DateTimeFormatter.ofPattern("d. MMM yyyy", locale)

    fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun dayHeader(millis: Long): String {
        val date = localDate(millis)
        val today = LocalDate.now(zone)
        return when (ChronoUnit.DAYS.between(date, today)) {
            0L -> "Heute"
            1L -> "Gestern"
            in 2L..6L -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> if (date.year == today.year) dayFormat.format(date) else dayYearFormat.format(date)
        }
    }

    fun monthHeader(millis: Long): String = monthFormat.format(localDate(millis)).replaceFirstChar { it.uppercase() }
    fun year(millis: Long): String = localDate(millis).year.toString()
    fun full(millis: Long): String = fullFormat.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun short(millis: Long): String = shortFormat.format(localDate(millis))

    private val oneDecimal = DecimalFormat("0.0")
    private val noDecimal = DecimalFormat("0")

    fun bytes(bytes: Long): String {
        val abs = kotlin.math.abs(bytes.toDouble())
        return when {
            abs >= 1e9 -> "${oneDecimal.format(bytes / 1e9).replace('.', ',')} GB"
            abs >= 1e6 -> "${noDecimal.format(bytes / 1e6)} MB"
            abs >= 1e3 -> "${noDecimal.format(bytes / 1e3)} KB"
            else -> "$bytes B"
        }
    }

    fun count(n: Int): String = String.format(locale, "%,d", n)

    fun duration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s) else String.format(locale, "%d:%02d", m, s)
    }

    fun etaSeconds(seconds: Long): String = when {
        seconds < 60 -> "< 1 Min."
        seconds < 3600 -> "${seconds / 60} Min."
        seconds < 86_400 -> "${seconds / 3600} Std. ${(seconds % 3600) / 60} Min."
        else -> "${seconds / 86_400} Tg. ${(seconds % 86_400) / 3600} Std."
    }

    fun percent(f: Float): String = "${(f * 100).toInt()} %"
}
