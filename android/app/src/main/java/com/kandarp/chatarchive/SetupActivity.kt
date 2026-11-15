package com.kandarp.chatarchive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.auth.api.identity.Identity
import java.io.File
import java.text.DateFormat
import java.util.Date

/** One screen, five steps. Each step shows ✓ when done. Also shows backup progress. */
class SetupActivity : Activity() {
    private val green = Color.parseColor("#008069")
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private val refresher = object : Runnable { override fun run() { refreshStatus(); ui.postDelayed(this, 1000) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val dark get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val fg get() = if (dark) Color.parseColor("#E9EDEF") else Color.parseColor("#111B21")
    private val muted get() = if (dark) Color.parseColor("#8696A0") else Color.parseColor("#667781")
    private val card get() = if (dark) Color.parseColor("#202C33") else Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = green
        render()
    }

    override fun onResume() { super.onResume(); render(); ui.post(refresher) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresher) }

    private fun text(s: String, size: Float = 15f, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Color.WHITE else green)
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            if (primary) setColor(green) else { setColor(Color.TRANSPARENT); setStroke(dp(1), green) }
        }
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(10) }
    }

    private fun step(n: Int, title: String, done: Boolean, help: String, vararg extra: View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            addView(text((if (done) "✅ " else "$n. ") + title, 17f, bold = true))
            addView(text(help, 14f, muted).apply { setPadding(0, dp(4), 0, 0) })
            extra.forEach { addView(it) }
        }

    private fun render() {
        val storageOk = Environment.isExternalStorageManager()
        val keyOk = KeyVault.has(this)
        val driveOk = getSharedPreferences("app", MODE_PRIVATE).getBoolean("drive_ok", false)
        val contactsOk = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasIndex = File(Paths.indexDir(this), "manifest.json").isFile

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
            setBackgroundColor(if (dark) Color.parseColor("#111B21") else Color.parseColor("#F0F2F5"))
        }
        root.addView(text("Chat Archive", 26f, green, true))
        root.addView(text("Keeps every WhatsApp photo, video and document safe in your Google Drive, sorted by chat and date.", 14f, muted)
            .apply { setPadding(0, dp(4), 0, dp(16)) })

        root.addView(step(1, "Allow access to WhatsApp files", storageOk,
            "Needed to read WhatsApp's backup and media. Turn on “Allow access to manage all files”.",
            *(if (!storageOk) arrayOf(button("Open setting") {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }) else emptyArray())))

        val keyField = EditText(this).apply {
            hint = "64-digit key (spaces are fine)"; setTextColor(fg); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        root.addView(step(2, "Your 64-digit backup key", keyOk,
            if (keyOk) "Saved securely on this phone. Enter it again only if you changed it in WhatsApp."
            else "WhatsApp → Settings → Chats → Chat backup → End-to-end encrypted backup → use a 64-digit key. Paste it here.",
            keyField, button(if (keyOk) "Replace key" else "Save key", !keyOk) {
                try {
                    Crypt15.parseKey(keyField.text.toString())
                    KeyVault.save(this, keyField.text.toString().trim())
                    toast("Key saved"); render()
                } catch (e: Exception) { toast(e.message ?: "Invalid key") }
            }))

        root.addView(step(3, "Connect Google Drive", driveOk,
            "Your files go to the folder “WhatsAppArchive” in your own Drive. The app can only see files it puts there.",
            button(if (driveOk) "Reconnect" else "Sign in with Google", !driveOk) { signIn() }))

        root.addView(step(4, "Show names instead of numbers", contactsOk,
            "Uses your phone contacts to name chats (WhatsApp's backup only has numbers). Optional.",
            *(if (!contactsOk) arrayOf(button("Allow contacts", false) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS), 1)
            }) else emptyArray())))

        statusText = text("", 14f, fg).apply { setPadding(0, dp(8), 0, 0) }
        val ready = storageOk && keyOk && driveOk
        root.addView(step(5, "Back up", Status.last(this, "last_ok") != null,
            "Runs every night on Wi-Fi by itself. The first backup uploads everything and can take a night: keep the phone on charge and on Wi-Fi.",
            statusText,
            button("Back up now", ready) {
                if (!ready) { toast("Finish steps 1–3 first"); return@button }
                SyncWorker.scheduleNightly(this); SyncWorker.syncNow(this); refreshStatus()
            }))

        if (hasIndex) root.addView(button("Open archive") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish()
        }.apply { tag = "open"; (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor((root.background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.WHITE) })
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val s = Status.get(this)
        val phase = s.optString("phase")
        val last = Status.last(this, "last_ok")?.toLongOrNull()
        val lines = mutableListOf<String>()
        when (phase) {
            "never" -> lines += "Not backed up yet."
            "error" -> lines += "⚠️ " + s.optString("detail")
            "done" -> lines += "✔ " + s.optString("detail")
            else -> lines += "⏳ " + s.optString("detail")
        }
        if (last != null) lines += "Last successful backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
        val count = Store.get(this).uploadCount()
        if (count > 0) lines += "$count files in Google Drive"
        statusText.text = lines.joinToString("\n")
        statusText.setTextColor(if (phase == "error") Color.parseColor("#D93025") else fg)
        // once the first index exists, offer the archive
        if (File(Paths.indexDir(this), "manifest.json").isFile && root.findViewWithTag<View>("open") == null && phase == "done") render()
    }

    private fun signIn() {
        Identity.getAuthorizationClient(this).authorize(Drive.request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) startIntentSenderForResult(r.pendingIntent!!.intentSender, 42, null, 0, 0, 0, null)
                else driveConnected()
            }
            .addOnFailureListener { e -> toast("Google sign-in failed: ${e.message}") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
            if (r.accessToken != null) driveConnected() else toast("Drive access was not granted")
        } catch (e: Exception) { toast("Google sign-in failed: ${e.message}") }
    }

    private fun driveConnected() {
        getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("drive_ok", true).apply()
        toast("Google Drive connected"); render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
