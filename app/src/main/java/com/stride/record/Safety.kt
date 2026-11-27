package com.stride.record

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.stride.domain.SafetyContact
import com.stride.domain.SafetyText

/**
 * Sends safety check-ins as plain text messages — works for any contact on any phone, no app or
 * account needed on their side.
 *
 * Automatic sending needs the SEND_SMS permission (asked for when you turn safety texts on).
 * Without it, [composeIntent] opens your messaging app with the text filled in, and you tap Send.
 */
object Safety {
    private const val TAG = "StrideSafety"

    fun canAutoSend(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Returns how many contacts the text went to (0 if it couldn't be sent automatically). */
    fun send(ctx: Context, contacts: List<SafetyContact>, text: String): Int {
        if (contacts.isEmpty() || !canAutoSend(ctx)) return 0
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        var sent = 0
        for (c in contacts) {
            val number = SafetyText.cleanPhone(c.phone)
            if (number.length < 3) continue
            try {
                val parts = sms.divideMessage(text)
                if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
                else sms.sendTextMessage(number, null, text, null, null)
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "SMS to contact failed", e) // never log the number or message body
            }
        }
        return sent
    }

    /** Opens the messaging app addressed to all contacts with [text] ready to send. */
    fun composeIntent(contacts: List<SafetyContact>, text: String): Intent {
        val to = contacts.joinToString(";") { SafetyText.cleanPhone(it.phone) }
        return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to"))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun batteryPercent(ctx: Context): Int? =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
}
