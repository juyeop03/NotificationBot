package kr.hs.dgsw.juyeop.notification

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.Html
import android.text.SpannableString
import android.util.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import com.faendir.rhino_android.RhinoAndroidHelper
import kr.hs.dgsw.juyeop.notification.api.ApiClass
import kr.hs.dgsw.juyeop.notification.view.MainActivity
import java.io.File
import java.io.FileReader

@Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
class NotificationListenerService: NotificationListenerService() {

    object data {
        lateinit var responder: Function
        lateinit var execScope: ScriptableObject
        lateinit var execContext: android.content.Context
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        with(MainActivity.key) {
            if (!getState(applicationContext)) return
        }

        if (sbn!!.packageName == "com.kakao.talk") {
            val wearableExtender = Notification.WearableExtender(sbn.notification)
            wearableExtender.actions.forEach { action ->
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                    if (action.title.toString().toLowerCase().contains("reply") ||
                            action.title.toString().toLowerCase().contains("Reply") ||
                            action.title.toString().toLowerCase().contains("답장")) {
                        data.execContext = applicationContext
                        callResponder(sbn.notification.extras.getString("android.title").toString(), sbn.notification.extras.get("android.text").toString(), action)
                    }
                }
            }
        }
    }

    fun initialListScript() {
        try {
            val scriptDir = File(Environment.getExternalStorageDirectory().toString() + File.separator + "bot")
            val script = File(scriptDir, "response.js")

            val parseContext = Context.enter()
            parseContext.optimizationLevel = -1

            val rhino = RhinoAndroidHelper().enterContext()
            val scriptReal = parseContext.compileReader(FileReader(script), script.name, 0, null)

            // 아래로 내려가지 못함

            val scope = rhino.initStandardObjects()
            ScriptableObject.defineClass(scope, ApiClass.Utils::class.java, false, true)

            scriptReal.exec(parseContext, scope)
            data.execScope = scope
            data.responder = scope.get("response", scope) as Function

            Context.exit()
        } catch (e: Exception) {
            Log.e("Exception", e.printStackTrace().toString())
            Process.killProcess(Process.myPid())
        }
    }

    fun callResponder(room: String, msg: Any, session: Notification.Action) {
        val parseContext = Context.enter()
        parseContext.optimizationLevel = -1

        var sender = ""
        var msg2 = ""

        if (msg is String) {
            sender = room
            msg2 = msg.toString()
        } else {
            val html = Html.toHtml(msg as SpannableString)
            sender = Html.fromHtml(html.split("<b>")[1].split("</b>")[0]).toString()
            msg2 = Html.fromHtml(html.split("</b>")[1].split("</p>")[0].substring(1)).toString()
        }

        try {
            data.responder.call(parseContext,
                data.execScope,
                data.execScope, arrayOf<Any> (room, msg2, sender, msg is SpannableString, SessionCacheReplier(session)))
        } catch (e: Exception) {
            Log.e("Exception", e.printStackTrace().toString())
        }
    }

    class SessionCacheReplier(val session: Notification.Action) {
        fun reply(value: String) {
            val sendIntent = Intent()
            val msg = Bundle()

            session.remoteInputs.forEach { remoteInput ->
                msg.putCharSequence(remoteInput.resultKey, value)
            }
            RemoteInput.addResultsToIntent(session.remoteInputs, sendIntent, msg)

            try {
                session.actionIntent.send(data.execContext, 0, sendIntent)
            } catch (e: Exception) {
                Log.e("Exception", e.printStackTrace().toString())
            }
        }
    }
}