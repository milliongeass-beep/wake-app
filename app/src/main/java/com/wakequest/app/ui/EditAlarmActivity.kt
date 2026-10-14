package com.wakequest.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wakequest.app.R
import com.wakequest.app.alarm.AlarmService
import com.wakequest.app.alarm.Scheduler
import com.wakequest.app.data.Alarm
import com.wakequest.app.data.Mission
import com.wakequest.app.data.MissionType
import com.wakequest.app.data.Store
import java.io.File
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.concurrent.thread

class EditAlarmActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "id"
        private const val MAX_MISSIONS = 3
    }

    private lateinit var store: Store
    private lateinit var draft: Alarm
    private var isNew = true
    private lateinit var ringsIn: TextView
    private lateinit var missionsBox: LinearLayout
    private lateinit var soundBtn: MaterialButton
    private val dayChips = HashMap<Int, Chip>()

    private var photoTarget: File? = null
    private var onPhoto: ((File?) -> Unit)? = null
    private var onScan: ((String?) -> Unit)? = null
    private var pendingCamera: (() -> Unit)? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val cb = onPhoto
        onPhoto = null
        cb?.invoke(if (ok) photoTarget else null)
    }
    private val scan = registerForActivityResult(ScanContract()) { r ->
        val cb = onScan
        onScan = null
        cb?.invoke(r.contents)
    }
    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val a = pendingCamera
        pendingCamera = null
        if (granted) a?.invoke() else toast("Camera permission is needed for this mission")
    }
    private val pickSound = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            val uri = r.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            val def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            draft = draft.copy(soundUri = if (uri == null || uri == def) "" else uri.toString())
            updateSoundLabel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        store = Store.get(this)
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val existing = if (id > 0) store.alarm(id) else null
        isNew = existing == null
        draft = existing ?: Alarm(
            id = store.newAlarmId(),
            hour = 7,
            minute = 0,
            days = setOf(1, 2, 3, 4, 5),
            missions = listOf(Mission(MissionType.MATH, 2, 3)),
        )

        val root = column().apply { setBackgroundColor(C.BG) }
        val bar = row().apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        bar.add(label(if (isNew) "New alarm" else "Edit alarm", 24f, bold = true), 0, WRAP, weight = 1f)
        bar.add(button("Save", BtnStyle.PRIMARY) { save() }.apply { tag = "save_alarm" }, WRAP, WRAP)
        root.add(bar)

        val body = column(16)
        buildTime(body)
        buildDays(body)
        buildLabel(body)
        buildMissions(body)
        buildSound(body)
        buildWakeCheck(body)
        buildSnooze(body)

        body.add(button("🔔  Test this alarm now", BtnStyle.TONAL) { testNow() }.apply { tag = "test_alarm" }, topDp = 24)
        if (!isNew) {
            body.add(button("Delete alarm", BtnStyle.OUTLINED) { confirmDelete() }.apply { tag = "delete_alarm" }, topDp = 12)
        }
        body.add(Space(this), MATCH, dp(32))
        root.add(scroll(body), MATCH, 0, weight = 1f)
        setContentView(root)
        root.applySystemInsets()
    }

    // ------------------------------------------------------------------ sections

    private fun buildTime(body: LinearLayout) {
        val tp = TimePicker(ContextThemeWrapper(this, R.style.ThemeOverlay_WakeQuest_Spinner)).apply {
            setIs24HourView(DateFormat.is24HourFormat(this@EditAlarmActivity))
            hour = draft.hour
            minute = draft.minute
            setOnTimeChangedListener { _, h, m ->
                draft = draft.copy(hour = h, minute = m)
                updateRingsIn()
            }
        }
        body.add(tp, WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        ringsIn = body.add(label("", 15f, C.PRIMARY, gravity = Gravity.CENTER).apply { tag = "rings_in" }, topDp = 4)
        updateRingsIn()
    }

    private fun buildDays(body: LinearLayout) {
        body.add(section("Repeat"), topDp = 20)
        val chips = ChipGroup(this).apply { isSingleLine = false }
        for (d in 1..7) {
            val c = Chip(this).apply {
                text = DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                isCheckable = true
                isChecked = d in draft.days
                tag = "day_$d"
                setOnCheckedChangeListener { _, on ->
                    draft = draft.copy(days = if (on) draft.days + d else draft.days - d)
                    updateRingsIn()
                }
            }
            dayChips[d] = c
            chips.addView(c)
        }
        body.add(chips, topDp = 6)
        val presets = row()
        listOf(
            "Once" to emptySet(),
            "Weekdays" to setOf(1, 2, 3, 4, 5),
            "Weekends" to setOf(6, 7),
            "Every day" to (1..7).toSet(),
        ).forEach { (name, set) ->
            presets.add(
                MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = name
                    isAllCaps = false
                    setTextColor(C.PRIMARY)
                    setOnClickListener { setDays(set) }
                },
                WRAP, WRAP,
            )
        }
        body.add(presets)
    }

    private fun setDays(set: Set<Int>) {
        draft = draft.copy(days = set)
        dayChips.forEach { (d, c) -> c.isChecked = d in set }
        updateRingsIn()
    }

    private fun buildLabel(body: LinearLayout) {
        body.add(section("Label"), topDp = 16)
        body.add(
            EditText(this).apply {
                tag = "label_input"
                setText(draft.label)
                hint = "e.g. Gym day 💪"
                setTextColor(C.TEXT)
                setHintTextColor(C.MUTED)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 1
                doAfterTextChanged { draft = draft.copy(label = it?.toString()?.take(40) ?: "") }
            },
        )
    }

    private fun buildMissions(body: LinearLayout) {
        body.add(section("Wake-up missions"), topDp = 20)
        body.add(label("You can't turn the alarm off until you finish these (up to $MAX_MISSIONS).", 13f, C.MUTED), topDp = 2)
        missionsBox = column()
        body.add(missionsBox, topDp = 8)
        renderMissions()
    }

    private fun renderMissions() {
        missionsBox.removeAllViews()
        if (draft.missions.isEmpty()) {
            missionsBox.add(label("No mission — one tap turns this alarm off.", 14f, C.MUTED), topDp = 4)
        }
        draft.missions.forEachIndexed { i, m ->
            val c = card(12)
            val r = row()
            r.add(label(m.describe(), 15f), 0, WRAP, weight = 1f)
            r.add(
                MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = "Edit"
                    isAllCaps = false
                    setTextColor(C.PRIMARY)
                    setOnClickListener { configureMission(m, i) }
                },
                WRAP, WRAP,
            )
            r.add(
                MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = "✕"
                    setTextColor(C.MUTED)
                    tag = "remove_mission_$i"
                    setOnClickListener {
                        draft = draft.copy(missions = draft.missions.filterIndexed { j, _ -> j != i })
                        renderMissions()
                    }
                },
                WRAP, WRAP,
            )
            c.add(r)
            missionsBox.add(c, topDp = if (i == 0) 0 else 8)
        }
        if (draft.missions.size < MAX_MISSIONS) {
            missionsBox.add(
                button("+  Add mission", BtnStyle.OUTLINED) { chooseMissionType() }.apply { tag = "add_mission" },
                topDp = 8,
            )
        }
    }

    private fun chooseMissionType() {
        val types = MissionType.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose a mission")
            .setItems(types.map { "${it.emoji}   ${it.title}" }.toTypedArray()) { _, which ->
                val t = types[which]
                configureMission(Mission(t, 2, t.defaultAmount), -1)
            }
            .show()
    }

    private fun configureMission(start: Mission, index: Int) {
        var m = start
        val v = column(20)
        v.add(label(m.type.blurb, 14f, C.MUTED))

        if (m.type != MissionType.BARCODE) {
            v.add(label(if (m.type == MissionType.PHOTO) "How closely must it match?" else "Difficulty", 15f, bold = true), topDp = 16)
            val group = MaterialButtonToggleGroup(this).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
            val ids = List(3) { View.generateViewId() }
            listOf("Easy", "Normal", "Hard").forEachIndexed { i, n ->
                group.addView(
                    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        id = ids[i]
                        text = n
                        isAllCaps = false
                    },
                    LinearLayout.LayoutParams(0, WRAP, 1f),
                )
            }
            group.check(ids[m.difficulty.coerceIn(1, 3) - 1])
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) m = m.copy(difficulty = ids.indexOf(checkedId) + 1)
            }
            v.add(group, topDp = 6)
        }

        val t = m.type
        if (t.maxAmount > t.minAmount) {
            v.add(
                sliderRow(
                    t.unit.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    t.minAmount.toFloat(), t.maxAmount.toFloat(), t.step.toFloat(), m.amount.toFloat(),
                    { "${it.toInt()}" },
                ) { m = m.copy(amount = it.toInt()) },
                topDp = 12,
            )
        }

        if (t == MissionType.PHOTO) {
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(C.SURFACE2)
            }
            v.add(preview, MATCH, dp(180), topDp = 16)
            val status = v.add(label(if (m.param.isBlank()) "No photo registered yet." else "Photo registered ✓", 14f, C.MUTED), topDp = 6)
            val existing = File(m.param.substringBefore('|'))
            if (m.param.isNotBlank() && existing.exists()) {
                thread { PhotoUtil.loadUpright(existing, 600)?.let { b -> runOnUiThread { preview.setImageBitmap(b) } } }
            }
            v.add(
                button("📷  " + (if (m.param.isBlank()) "Register photo" else "Retake photo"), BtnStyle.TONAL) {
                    capturePhoto { file ->
                        if (file == null) return@capturePhoto
                        status.text = "Checking…"
                        thread {
                            val result = PhotoUtil.analyze(file)
                            val bmp = PhotoUtil.loadUpright(file, 600)
                            runOnUiThread {
                                when {
                                    result == null -> status.text = "Couldn't read the photo, try again."
                                    result.second < PhotoUtil.MIN_CONTRAST -> status.text = "Too dark or plain — pick a spot with some detail and the light on."
                                    else -> {
                                        m = m.copy(param = "${file.path}|${result.first}")
                                        status.text = "Photo registered ✓  Remember where you took it!"
                                        bmp?.let { preview.setImageBitmap(it) }
                                    }
                                }
                            }
                        }
                    }
                },
                topDp = 8,
            )
        }

        if (t == MissionType.BARCODE) {
            val status = v.add(
                label(if (m.param.isBlank()) "No barcode registered yet." else "Registered: ${m.param}", 15f, C.MUTED),
                topDp = 16,
            )
            v.add(
                button("🔎  Scan barcode", BtnStyle.TONAL) {
                    scanCode { code ->
                        if (code.isNullOrBlank()) return@scanCode
                        m = m.copy(param = code.trim())
                        status.text = "Registered: ${code.trim()}"
                    }
                },
                topDp = 8,
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("${t.emoji}  ${t.title}")
            .setView(scroll(v))
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if ((m.type == MissionType.PHOTO || m.type == MissionType.BARCODE) && m.param.isBlank()) {
                    toast(if (m.type == MissionType.PHOTO) "Register a photo first" else "Scan a barcode first")
                    return@setOnClickListener
                }
                val list = draft.missions.toMutableList()
                if (index in list.indices) list[index] = m else list.add(m)
                draft = draft.copy(missions = list.take(MAX_MISSIONS))
                renderMissions()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun buildSound(body: LinearLayout) {
        body.add(section("Sound"), topDp = 20)
        soundBtn = body.add(
            button("", BtnStyle.TONAL) {
                val current = if (draft.soundUri.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) else Uri.parse(draft.soundUri)
                val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                try {
                    pickSound.launch(i)
                } catch (e: ActivityNotFoundException) {
                    toast("No sound picker available — using the default alarm sound")
                }
            },
            topDp = 6,
        )
        updateSoundLabel()
        body.add(switchRow("Gradually louder", "Starts soft and builds up over ~45 s — a gentler, less groggy wake-up.", draft.gradualVolume) {
            draft = draft.copy(gradualVolume = it)
        })
        body.add(switchRow("Vibrate", null, draft.vibrate) { draft = draft.copy(vibrate = it) })
    }

    private fun updateSoundLabel() {
        val name = if (draft.soundUri.isBlank()) "Default alarm" else try {
            RingtoneManager.getRingtone(this, Uri.parse(draft.soundUri))?.getTitle(this) ?: "Custom sound"
        } catch (e: Exception) {
            "Custom sound"
        }
        soundBtn.text = "🎵  $name"
    }

    private fun buildWakeCheck(body: LinearLayout) {
        body.add(section("Wake-up check"), topDp = 20)
        val minutesRow = sliderRow("Check after", 1f, 15f, 1f, draft.wakeCheckMinutes.toFloat(), { "${it.toInt()} min" }) {
            draft = draft.copy(wakeCheckMinutes = it.toInt())
        }
        body.add(
            switchRow(
                "Make sure I'm still awake",
                "A few minutes after you dismiss, you must tap “I'm awake” within 60 s or the alarm rings again.",
                draft.wakeCheck,
                tag = "wakecheck_switch",
            ) {
                draft = draft.copy(wakeCheck = it)
                minutesRow.visibility = if (it) View.VISIBLE else View.GONE
            },
        )
        minutesRow.visibility = if (draft.wakeCheck) View.VISIBLE else View.GONE
        body.add(minutesRow)
    }

    private fun buildSnooze(body: LinearLayout) {
        body.add(section("Snooze"), topDp = 20)
        val maxRow = sliderRow("Snoozes allowed", 1f, 10f, 1f, draft.maxSnoozes.coerceIn(1, 10).toFloat(), { "${it.toInt()}" }) {
            draft = draft.copy(maxSnoozes = it.toInt())
        }
        body.add(
            sliderRow("Snooze length", 0f, 15f, 1f, draft.snoozeMinutes.coerceIn(0, 15).toFloat(), { if (it == 0f) "Off" else "${it.toInt()} min" }) {
                draft = draft.copy(snoozeMinutes = it.toInt())
                maxRow.visibility = if (it > 0f) View.VISIBLE else View.GONE
            },
        )
        maxRow.visibility = if (draft.snoozeMinutes > 0) View.VISIBLE else View.GONE
        body.add(maxRow)
        if (draft.maxSnoozes < 1) draft = draft.copy(maxSnoozes = 1)
    }

    // ------------------------------------------------------------------ actions

    private fun updateRingsIn() {
        if (!::ringsIn.isInitialized) return
        val ms = Scheduler.nextTrigger(draft).toInstant().toEpochMilli() - System.currentTimeMillis()
        ringsIn.text = "Rings in ${formatDuration(ms)}" + if (draft.days.isEmpty()) " (once)" else ""
    }

    private fun persist(): Alarm {
        val saved = draft.copy(enabled = true)
        store.saveAlarm(saved)
        Scheduler.cancel(this, saved.id, Scheduler.KIND_SNOOZE)
        Scheduler.schedule(this, saved)
        isNew = false
        draft = saved
        return saved
    }

    private fun save() {
        val saved = persist()
        val ms = Scheduler.nextTrigger(saved).toInstant().toEpochMilli() - System.currentTimeMillis()
        toast("Alarm set for ${formatDuration(ms)} from now")
        finish()
    }

    private fun testNow() {
        val saved = persist()
        AlarmService.start(this, saved.id, Scheduler.KIND_TEST)
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this alarm?")
            .setPositiveButton("Delete") { _, _ ->
                Scheduler.cancelAll(this, draft.id)
                store.deleteAlarm(draft.id)
                Scheduler.refreshBedtime(this)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ camera helpers

    private fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingCamera = action
            camPerm.launch(Manifest.permission.CAMERA)
        }
    }

    private fun capturePhoto(cb: (File?) -> Unit) = withCamera {
        val dir = File(filesDir, "photos").apply { mkdirs() }
        val f = File(dir, "ref_${System.currentTimeMillis()}.jpg")
        photoTarget = f
        onPhoto = cb
        try {
            takePicture.launch(FileProvider.getUriForFile(this, "$packageName.files", f))
        } catch (e: ActivityNotFoundException) {
            onPhoto = null
            toast("No camera app found")
        }
    }

    private fun scanCode(cb: (String?) -> Unit) = withCamera {
        onScan = cb
        scan.launch(ScanOptions().setPrompt("Scan the barcode you'll use to turn off the alarm").setBeepEnabled(false).setOrientationLocked(false))
    }
}
