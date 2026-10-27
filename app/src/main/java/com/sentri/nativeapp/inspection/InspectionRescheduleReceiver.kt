package com.sentri.nativeapp.inspection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sentri.nativeapp.SentriApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restores pending check-in jobs after reboot or an app update without sending before account validation. */
class InspectionRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        Log.i("SentriCheckInReschedule", "received")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as SentriApplication).container
                val account = container.farms.cachedAccountOrNull() ?: run {
                    Log.i("SentriCheckInReschedule", "no cached account")
                    return@launch
                }
                container.inspectionCheckInWork.ensureScheduled(container.scopeGate.lease(account))
                Log.i("SentriCheckInReschedule", "scheduled")
            } catch (error: Exception) {
                Log.w("SentriCheckInReschedule", "reschedule failed: ${error::class.java.simpleName}")
                // App launch also reschedules the same durable commands.
            } finally { pending.finish() }
        }
    }
}
