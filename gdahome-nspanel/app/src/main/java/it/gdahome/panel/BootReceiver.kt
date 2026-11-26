package it.gdahome.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import it.gdahome.panel.ui.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GdaService.start(context)
        // Porta in primo piano il pannello anche se un altro launcher fosse ancora predefinito
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
