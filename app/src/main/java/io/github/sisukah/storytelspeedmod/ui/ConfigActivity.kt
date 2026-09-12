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
import io.github.sisukah.storytelspeedmod.Config
import io.github.sisukah.storytelspeedmod.ConfigReceiver
import io.github.sisukah.storytelspeedmod.HookPoint
import io.github.sisukah.storytelspeedmod.Keys
import io.github.sisukah.storytelspeedmod.Mode

/**
 * Optional configuration UI. Only needed to CHANGE the mapping: in ladder mode (the default) the
 * speeds are picked in Storytel itself, so day to day this app is not opened at all.
 *
 * It stores nothing: it sends the same CONFIG broadcast adb can send, and shows the reply from the
 * hook running inside Storytel. Storytel (patched) must be running.
 */
class ConfigActivity : Activity() {

    private lateinit var pkgEdit: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var ladderEdit: EditText
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

        root.addView(note(
            "Ladder mode (default): pick the speed inside Storytel. Its four fastest buttons play " +
                "faster than they say. Changes apply the moment you tap a speed in Storytel — no restart."))

        root.addView(label("Mode"))
        modeGroup = RadioGroup(this)
        modeGroup.addView(RadioButton(this).apply { id = ID_MODE_LADDER; text = "Ladder — Storytel's buttons become fast speeds (recommended)" })
        modeGroup.addView(RadioButton(this).apply { id = ID_MODE_REMAP; text = "Remap only 2x to one target speed" })
        modeGroup.addView(RadioButton(this).apply { id = ID_MODE_FORCE; text = "Force one target speed for everything" })
        modeGroup.addView(RadioButton(this).apply { id = ID_MODE_OFF; text = "Off (no speed is changed)" })
        modeGroup.check(ID_MODE_LADDER)
        root.addView(modeGroup)

        root.addView(label("Speed ladder  (Storytel button : speed actually played)"))
        ladderEdit = EditText(this).apply {
            setText(Config.DEFAULT_LADDER)
            inputType = InputType.TYPE_CLASS_TEXT
            typeface = Typeface.MONOSPACE
        }
        root.addView(ladderEdit)

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PRESETS.forEach { (name, spec) ->
            presets.addView(Button(this).apply {
                text = name
                setOnClickListener { ladderEdit.setText(spec); modeGroup.check(ID_MODE_LADDER) }
            })
        }
        root.addView(presets)
        root.addView(note(
            "Untouched buttons keep their real speed, so 1x stays 1x. Storytel's own display still " +
                "shows the button value, so \"time left\" will read high while a boosted step plays."))

        root.addView(label("Target speed  (used by Remap and Force only)"))
        targetGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        TARGETS.forEachIndexed { i, v ->
            targetGroup.addView(RadioButton(this).apply { id = ID_TARGET_BASE + i; text = "${v}x" })
        }
        targetGroup.check(ID_TARGET_BASE + 1)
        root.addView(targetGroup)
        customTarget = EditText(this).apply {
            hint = "custom target, e.g. 2.75 (overrides the presets when filled)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(customTarget)

        root.addView(label("Discovery / debug"))
        discoveryBox = CheckBox(this).apply { text = "Discovery mode (verbose logcat under tag StorytelSpeedMod)" }
        root.addView(discoveryBox)
        ctorBox = CheckBox(this).apply { text = "Force the constructor hook point" }
        root.addView(ctorBox)

        root.addView(label("Advanced (one key=value per line)"))
        advancedEdit = EditText(this).apply {
            hint = "max_speed=4.0\ncaller_filter=\ncls_playback_parameters="
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            typeface = Typeface.MONOSPACE
        }
        root.addView(advancedEdit)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply { text = "Apply"; setOnClickListener { apply() } })
        buttons.addView(Button(this).apply {
            text = "Show current"
            setOnClickListener { send(Bundle().apply { putBoolean(ConfigReceiver.EXTRA_SHOW, true) }) }
        })
        buttons.addView(Button(this).apply {
            text = "Reset"
            setOnClickListener { send(Bundle().apply { putBoolean(ConfigReceiver.EXTRA_RESET, true) }) }
        })
        root.addView(buttons)

        root.addView(label("Target package (patched Storytel)"))
        pkgEdit = EditText(this).apply { setText(DEFAULT_PACKAGE); inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(pkgEdit)

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
            ID_MODE_REMAP -> Mode.REMAP_2X
            ID_MODE_FORCE -> Mode.FORCE_TARGET
            ID_MODE_OFF -> Mode.OFF
            else -> Mode.LADDER
        }
        val target = customTarget.text.toString().trim().toFloatOrNull()
            ?: TARGETS.getOrElse(targetGroup.checkedRadioButtonId - ID_TARGET_BASE) { 3.0f }
        val ladder = ladderEdit.text.toString().trim().ifEmpty { Config.DEFAULT_LADDER }
        val kv = buildString {
            append(Keys.MODE).append('=').append(mode.key).append('\n')
            append(Keys.LADDER).append('=').append(ladder).append('\n')
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
                        "- Did the module load? The reply starts with a pid line when it did.\n" +
                        "- Is the package name right?"
                }
            }
        }, null, Activity.RESULT_CANCELED, null, null)
    }

    private fun save() {
        prefs.edit()
            .putString("pkg", pkgEdit.text.toString())
            .putInt("mode", modeGroup.checkedRadioButtonId)
            .putString("ladder", ladderEdit.text.toString())
            .putInt("target", targetGroup.checkedRadioButtonId)
            .putString("custom", customTarget.text.toString())
            .putBoolean("discovery", discoveryBox.isChecked)
            .putBoolean("ctor", ctorBox.isChecked)
            .putString("advanced", advancedEdit.text.toString())
            .apply()
    }

    private fun restore() {
        pkgEdit.setText(prefs.getString("pkg", DEFAULT_PACKAGE))
        modeGroup.check(prefs.getInt("mode", ID_MODE_LADDER))
        ladderEdit.setText(prefs.getString("ladder", Config.DEFAULT_LADDER))
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

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_PACKAGE = "grit.storytel.app"
        val TARGETS = listOf(2.5f, 3.0f, 3.5f, 4.0f)

        /**
         * One-tap ladders, gentle to fastest. Every output is above Storytel's own 2.0 maximum on
         * purpose: an output equal to a button value would be mapped a second time when Media3
         * rebuilds the parameters (see Config.parseLadder), so such rungs are refused.
         */
        val PRESETS = listOf(
            "Gentle" to "1.75:2.25,2.0:2.5",
            "Medium" to "1.25:2.25,1.5:2.5,1.75:2.75,2.0:3.0",
            "Fast" to Config.DEFAULT_LADDER,
        )

        const val ID_MODE_LADDER = 1000
        const val ID_MODE_REMAP = 1001
        const val ID_MODE_FORCE = 1002
        const val ID_MODE_OFF = 1003
        const val ID_TARGET_BASE = 2000
    }
}
