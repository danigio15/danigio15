package com.gdahome.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gdahome.os.ui.AvvioActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GdaService.start(context)
        // All'accensione apre l'avvio (e da lì GdAHome) anche se un altro launcher fosse ancora predefinito.
        // Dopo un aggiornamento di GdAHome OS invece non si porta niente davanti a GdAHome.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startActivity(Intent(context, AvvioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
