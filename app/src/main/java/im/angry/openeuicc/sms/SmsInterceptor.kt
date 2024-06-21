package im.angry.openeuicc.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsInterceptor: BroadcastReceiver() {
    var onSmsReceivedListener: OnSmsReceivedListener? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == (intent!!.action ?: "")){
            //收到短信，上报
            onSmsReceivedListener?.onReceived(intent)
        }
    }

    interface OnSmsReceivedListener{
        fun onReceived(intent: Intent)
    }
}