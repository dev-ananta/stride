package com.stride.domain

import java.util.Locale

/**
 * Text for safety check-ins. With no accounts or servers, live location is shared the only way that
 * works for any contact on any phone: an SMS with an OpenStreetMap link to where you are now.
 */
object SafetyText {
    fun mapLink(lat: Double, lon: Double): String =
        String.format(Locale.US, "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=16/%.5f/%.5f", lat, lon, lat, lon)

    fun started(who: String, sport: Sport, lat: Double?, lon: Double?, intervalMin: Int): String = buildString {
        append(nameOr(who)).append(" started a ").append(sport.label.lowercase(Locale.US)).append(" with Stride.")
        if (lat != null && lon != null) append(" Start: ").append(mapLink(lat, lon))
        if (intervalMin > 0) append(" You'll get a location update every ").append(intervalMin).append(" min.")
    }

    fun update(who: String, lat: Double, lon: Double, distanceM: Double, elapsedMs: Long, units: Units, batteryPct: Int?): String = buildString {
        append(nameOr(who)).append(" — ").append(Fmt.distance(distanceM, units)).append(" in ").append(Fmt.durationShort(elapsedMs))
        append(". Now: ").append(mapLink(lat, lon))
        if (batteryPct != null) append(" (battery ").append(batteryPct).append("%)")
    }

    fun stoppedMoving(who: String, lat: Double, lon: Double, minutes: Int): String =
        "${nameOr(who)} hasn't moved for $minutes min during an activity. Last location: ${mapLink(lat, lon)}"

    fun finished(who: String, sport: Sport, distanceM: Double, elapsedMs: Long, units: Units): String =
        "${nameOr(who)} finished their ${sport.label.lowercase(Locale.US)} safely: ${Fmt.distance(distanceM, units)} in ${Fmt.durationShort(elapsedMs)}."

    fun lowBattery(who: String, lat: Double, lon: Double, pct: Int): String =
        "${nameOr(who)}'s phone is at $pct% during an activity. Last location: ${mapLink(lat, lon)}"

    private fun nameOr(who: String) = who.ifBlank { "Your contact" }

    /** Keep only digits and a leading +, so pasted numbers like "(555) 123-4567" work. */
    fun cleanPhone(raw: String): String {
        val t = raw.trim()
        val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        return if (plus) "+$digits" else digits
    }
}
