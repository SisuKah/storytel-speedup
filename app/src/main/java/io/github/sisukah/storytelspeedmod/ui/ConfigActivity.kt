package io.github.sisukah.storytelspeedmod.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.sisukah.storytelspeedmod.ConfigReceiver
import io.github.sisukah.storytelspeedmod.HookPoint
import io.github.sisukah.storytelspeedmod.Keys
import io.github.sisukah.storytelspeedmod.Mode

/**
 * Optional, dependency-free configuration UI. Only available when this module APK is ALSO
 * installed as a normal app. It does not store the config itself: it sends the same CONFIG
 * broadcast that adb can send, and shows the reply from the hook running inside Storytel.
 *
 * Storytel (patched) must be running for the broadcast to be answered.
 */
class ConfigActivity : Activity() {

    private lateinit var pkgEdit: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var targetGroup: RadioGroup
    private lateinit var customTarget: EditText
    private lateinit var discoveryBox: CheckBox
    private lateinit var ctorBox: CheckBox
    private lateinit var advancedEdit: EditText
    private lateinit var output: TextView

    private val prefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        restore()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

        root.addView(label("Target package (patched Storytel)"))
        pkgEdit = EditText(this).apply { setText(DEFAULT_PACKAGE); inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(pkgEdit)

        root.addView(label("Mode"))
        modeGroup = RadioGroup(this)
        modeGroup.addView(RadioButton(this).apply { id = ID_MODE_REMAP; text = "Remap 2x -> target (default, safest)" })
        modeGroup.addView(RadioButton(this).apply { id = ID_MODE_FORCE; text = "Force target for every speed request" })
        modeGroup.addView(RadioButton(this).apply { id = ID_MODE_OFF; text = "Off (hooks only log)" })
        modeGroup.check(ID_MODE_REMAP)
        root.addView(modeGroup)

        root.addView(label("Target speed"))
        targetGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        PRESETS.forEachIndexed { i, v ->
            targetGroup.addView(RadioButton(this).apply { id = ID_TARGET_BASE + i; text = "${v}x" })
        }
        targetGroup.check(ID_TARGET_BASE + 1) // 3.0x
        root.addView(targetGroup)
        customTarget = EditText(this).apply {
            hint = "custom target, e.g. 2.75 (overrides presets when filled)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(customTarget)

        root.addView(label("Discovery / debug"))
        discoveryBox = CheckBox(this).apply { text = "Discovery mode (log every speed change to logcat, tag StorytelSpeedMod)" }
        root.addView(discoveryBox)
        ctorBox = CheckBox(this).apply { text = "Use constructor hook point instead of player funnel (fallback)" }
        root.addView(ctorBox)

        root.addView(label("Advanced (one key=value per line, see docs)"))
        advancedEdit = EditText(this).apply {
            hint = "remap_from=2.0\ncaller_filter=\ncls_playback_parameters=\nui_speed_list_class="
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            typeface = Typeface.MONOSPACE
        }
        root.addView(advancedEdit)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply { text = "Apply"; setOnClickListener { apply() } })
        buttons.addView(Button(this).apply { text = "Show current"; setOnClickListener { send(Bundle().apply { putBoolean(ConfigReceiver.EXTRA_SHOW, true) }) } })
        buttons.addView(Button(this).apply { text = "Reset"; setOnClickListener { send(Bundle().apply { putBoolean(ConfigReceiver.EXTRA_RESET, true) }) } })
        root.addView(buttons)

        root.addView(label("Reply from Storytel process"))
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "Open Storytel first, then press Apply or Show current."
        }
        root.addView(output)

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun apply() {
        val mode = when (modeGroup.checkedRadioButtonId) {
            ID_MODE_FORCE -> Mode.FORCE_TARGET
            ID_MODE_OFF -> Mode.OFF
            else -> Mode.REMAP_2X
        }
        val target = customTarget.text.toString().trim().toFloatOrNull()
            ?: PRESETS.getOrElse(targetGroup.checkedRadioButtonId - ID_TARGET_BASE) { 3.0f }
        val kv = buildString {
            append(Keys.MODE).append('=').append(mode.key).append('\n')
            append(Keys.TARGET).append('=').append(target).append('\n')
            append(Keys.DISCOVERY).append('=').append(discoveryBox.isChecked).append('\n')
            append(Keys.HOOK_POINT).append('=').append(if (ctorBox.isChecked) HookPoint.CTOR.key else HookPoint.PLAYER.key).append('\n')
            append(advancedEdit.text.toString())
        }
        save()
        send(Bundle().apply { putString(ConfigReceiver.EXTRA_SET, kv) })
    }

    private fun send(extras: Bundle) {
        val pkg = pkgEdit.text.toString().trim().ifEmpty { DEFAULT_PACKAGE }
        val intent = Intent(ConfigReceiver.ACTION).setPackage(pkg).putExtras(extras)
        output.text = "Sending to $pkg ..."
        sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                val data = resultData
                output.text = if (resultCode == Activity.RESULT_OK && data != null) {
                    data
                } else {
                    "No reply from $pkg.\n\nChecklist:\n" +
                        "- Is the PATCHED Storytel installed and currently running (open it, start playback)?\n" +
                        "- Did the module load? adb logcat -s StorytelSpeedMod should show a LOADED line.\n" +
                        "- Is the package name right? adb shell pm list packages | grep -i storytel"
                }
            }
        }, null, Activity.RESULT_CANCELED, null, null)
    }

    private fun save() {
        prefs.edit()
            .putString("pkg", pkgEdit.text.toString())
            .putInt("mode", modeGroup.checkedRadioButtonId)
            .putInt("target", targetGroup.checkedRadioButtonId)
            .putString("custom", customTarget.text.toString())
            .putBoolean("discovery", discoveryBox.isChecked)
            .putBoolean("ctor", ctorBox.isChecked)
            .putString("advanced", advancedEdit.text.toString())
            .apply()
    }

    private fun restore() {
        pkgEdit.setText(prefs.getString("pkg", DEFAULT_PACKAGE))
        modeGroup.check(prefs.getInt("mode", ID_MODE_REMAP))
        targetGroup.check(prefs.getInt("target", ID_TARGET_BASE + 1))
        customTarget.setText(prefs.getString("custom", ""))
        discoveryBox.isChecked = prefs.getBoolean("discovery", false)
        ctorBox.isChecked = prefs.getBoolean("ctor", false)
        advancedEdit.setText(prefs.getString("advanced", ""))
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_PACKAGE = "grit.storytel.app"
        val PRESETS = listOf(2.5f, 3.0f, 3.5f, 4.0f)
        const val ID_MODE_REMAP = 1001
        const val ID_MODE_FORCE = 1002
        const val ID_MODE_OFF = 1003
        const val ID_TARGET_BASE = 2000
    }
}
