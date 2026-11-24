package com.yogi.ecall

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Big photos of the people you call most, three in a row like a keypad. Tap a photo to call.
 * Family sets it up (hold ⚙ for two seconds), so it can't be changed by accident.
 */
class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var setup = false
    private var tts: TextToSpeech? = null
    private var pendingCall: String? = null
    private var editing: Contact? = null

    private fun cols() = Store.int("cols", 3).coerceIn(1, 4)
    private fun nameSize() = Store.int("name_size", 22)
    /** 0 = 3-second countdown · 1 = ask first · 2 = call at once */
    private fun callMode() = Store.int("call_mode", 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        root = FrameLayout(this)
        root.setBackgroundColor(C.BG)
        setContentView(root)
        tts = TextToSpeech(this) { }
    }

    override fun onResume() {
        super.onResume()
        draw()
        h.removeCallbacks(tick)
        h.postDelayed(tick, 30_000)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!setup) draw()
            h.postDelayed(this, 30_000)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (setup) { setup = false; draw() }
        // as the home screen, Back does nothing (no getting lost)
        else if (!isHome()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun isHome(): Boolean {
        val r = try { packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0) } catch (_: Exception) { null }
        return r?.activityInfo?.packageName == packageName
    }

    // ===================================================================== screen

    private fun draw() {
        root.removeAllViews()
        val sv = ScrollView(this)
        val col = vbox()
        col.setPadding(dp(10), dp(26), dp(10), dp(20))
        sv.addView(col)
        root.addView(sv)

        if (Store.bool("clock", true) && !setup) {
            val now = Date()
            col.addView(label(SimpleDateFormat("h:mm", Locale.getDefault()).format(now), 54f, C.TEXT, true).apply { gravity = Gravity.CENTER })
            val bat = try {
                val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lv = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val sc = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val ch = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                if (lv >= 0) "   ·   " + (if (ch) "⚡" else if (lv * 100 / sc <= 20) "🪫" else "🔋") + " ${lv * 100 / sc}%" else ""
            } catch (_: Exception) { "" }
            col.addView(label(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now) + bat, 20f, C.SUB).apply {
                gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12))
            })
        }
        if (setup) {
            col.addView(label("⚙ Setup", 28f, C.GOLD, true).apply { setPadding(dp(8), 0, 0, 0) })
            col.addView(label("Tap a photo to change it. Add the people Aama and Buwa call most.", 15f, C.SUB).apply { setPadding(dp(8), 0, dp(8), dp(10)) })
        }

        val people = People.all()
        val cols = cols()
        val gap = dp(8)
        val w = (resources.displayMetrics.widthPixels - dp(20) - gap * (cols + 1)) / cols
        var row: LinearLayout? = null
        val items: List<Contact?> = people + if (setup) listOf(null) else emptyList()
        for ((i, c) in items.withIndex()) {
            if (i % cols == 0) {
                row = hbox()
                row.gravity = Gravity.TOP
                col.addView(row)
            }
            val tile = if (c == null) addTile(w) else tile(c, w)
            row?.addView(tile, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(gap / 2 + dp(2), gap / 2, gap / 2 + dp(2), gap / 2) })
        }
        if (people.isEmpty() && !setup) {
            val c = card()
            c.addView(label("No contacts yet", 22f, C.TEXT, true))
            c.addView(label("Hold the ⚙ button below for two seconds, then add the people to call.", 17f, C.SUB).lin(t = dp(6)))
            col.addView(c)
        }

        if (Store.bool("emergency", true) && !setup) {
            col.addView(label("EMERGENCY", 13f, C.SUB, true).apply { setPadding(dp(10), dp(18), 0, dp(6)) })
            val er = hbox()
            for ((icon, name, num) in listOf(Triple("🚓", "Police", "100"), Triple("🚑", "Ambulance", "102"), Triple("🚒", "Fire", "101"))) {
                val b = vbox()
                b.gravity = Gravity.CENTER
                b.background = ripple(0xFF3A1414.toInt(), 18f)
                b.setPadding(0, dp(12), 0, dp(10))
                b.addView(label(icon, 30f).apply { gravity = Gravity.CENTER })
                b.addView(label("$name $num", 15f, C.TEXT, true).apply { gravity = Gravity.CENTER })
                b.setOnClickListener {
                    AlertDialog.Builder(this).setTitle("Call $name ($num)?")
                        .setPositiveButton("📞 Call") { _, _ -> call(num) }.setNegativeButton("No", null).show().big()
                }
                er.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
            }
            col.addView(er)
        }

        if (setup) settings(col)

        // the setup button: hold for 2 seconds (so it isn't opened by accident)
        val gear = label(if (setup) "✓ Done" else "⚙", if (setup) 18f else 22f, if (setup) Color.BLACK else C.SUB, setup)
        gear.gravity = Gravity.CENTER
        gear.setPadding(dp(18), dp(10), dp(18), dp(10))
        gear.background = rounded(if (setup) C.GOLD else C.CARD, dpf(22f))
        if (setup) gear.setOnClickListener { setup = false; draw() }
        else {
            val hold = Runnable { gear.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); setup = true; draw() }
            gear.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { (v as TextView).text = "⏳"; h.postDelayed(hold, 2000) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(hold); (v as TextView).text = "⚙" }
                }
                true
            }
        }
        col.addView(gear.lin(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, t = dp(22)).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    private fun tile(c: Contact, w: Int): View {
        val t = vbox()
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.background = ripple(C.CARD, 22f)
        t.setPadding(dp(6), dp(6), dp(6), dp(10))
        val pic = FrameLayout(this)
        pic.background = rounded(c.color, dpf(18f))
        pic.clipToOutline = true
        val f = c.photo(this)
        if (f.exists()) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(BitmapFactory.decodeFile(f.path))
            pic.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            pic.addView(label(c.name.trim().take(1).uppercase(), w / resources.displayMetrics.density * 0.32f, Color.WHITE, true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH, MATCH))
        }
        if (setup) pic.addView(label("✎", 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = rounded(0x99000000.toInt(), dpf(18f))
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        val side = w - dp(12)
        t.addView(pic, LinearLayout.LayoutParams(side, side))
        t.addView(label(c.name, nameSize().toFloat() * (if (cols() >= 4) 0.75f else 1f), C.TEXT, true).apply {
            gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(6), 0, 0)
        })
        t.setOnClickListener { if (setup) edit(c) else tapCall(c) }
        t.setOnLongClickListener { if (!setup) more(c); true }
        return t
    }

    private fun addTile(w: Int): View {
        val t = vbox()
        t.gravity = Gravity.CENTER
        t.background = rounded(C.CARD, dpf(22f), dp(2), C.GOLD)
        t.minimumHeight = w
        t.addView(label("＋", 44f, C.GOLD, true).apply { gravity = Gravity.CENTER })
        t.addView(label("Add", 18f, C.GOLD, true).apply { gravity = Gravity.CENTER })
        t.setOnClickListener { add() }
        return t
    }

    // ===================================================================== calling

    private fun tapCall(c: Contact) {
        if (Store.bool("speak", true)) try { tts?.speak(c.name, TextToSpeech.QUEUE_FLUSH, null, "n") } catch (_: Exception) {}
        when (callMode()) {
            2 -> call(c.number)
            1 -> AlertDialog.Builder(this).setTitle("Call ${c.name}?")
                .setPositiveButton("📞 Yes, call") { _, _ -> call(c.number) }.setNegativeButton("No", null).show().big()
            else -> countdown(c)
        }
    }

    /** "Calling Aama… 3 2 1" with a big Cancel, so a wrong tap is easy to undo */
    private fun countdown(c: Contact) {
        val ov = vbox()
        ov.gravity = Gravity.CENTER
        ov.setBackgroundColor(0xF20B0F14.toInt())
        ov.isClickable = true
        val f = c.photo(this)
        val pic = FrameLayout(this)
        pic.background = rounded(c.color, dpf(28f))
        pic.clipToOutline = true
        if (f.exists()) pic.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeFile(f.path)) })
        else pic.addView(label(c.name.take(1).uppercase(), 80f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
        ov.addView(pic, LinearLayout.LayoutParams(dp(200), dp(200)))
        ov.addView(label("Calling ${c.name}…", 30f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(18), dp(20), 0) })
        val n = label("3", 64f, C.GREEN, true).apply { gravity = Gravity.CENTER }
        ov.addView(n)
        val cancel = label("✕  Cancel", 26f, Color.WHITE, true)
        cancel.gravity = Gravity.CENTER
        cancel.background = ripple(C.RED, 30f)
        cancel.setPadding(dp(40), dp(18), dp(40), dp(18))
        ov.addView(cancel.lin(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, t = dp(20)))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        var left = 3
        val step = object : Runnable {
            override fun run() {
                left--
                if (left <= 0) { root.removeView(ov); call(c.number) }
                else { n.text = "$left"; h.postDelayed(this, 1000) }
            }
        }
        cancel.setOnClickListener { h.removeCallbacks(step); root.removeView(ov) }
        h.postDelayed(step, 1000)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun call(number: String) {
        val num = People.clean(number)
        if (num.isEmpty()) { toast("No number saved"); return }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCall = num
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 5)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))
        } catch (_: Exception) {
            dial(num)
        }
    }

    private fun dial(num: String) {
        try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))) } catch (_: Exception) { toast("No phone app found") }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        val n = pendingCall ?: return
        pendingCall = null
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) call(n) else dial(n)
    }

    /** hold a photo: WhatsApp, message, or dial pad */
    private fun more(c: Contact) {
        val num = People.clean(c.number)
        AlertDialog.Builder(this).setTitle(c.name).setItems(arrayOf("📞  Call", "🟢  WhatsApp", "💬  Message", "🔢  Show number")) { _, w ->
            when (w) {
                0 -> call(c.number)
                1 -> {
                    val wa = num.removePrefix("+").let { if (it.length == 10 && it.startsWith("9")) "977$it" else it }
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$wa"))) } catch (_: Exception) { toast("WhatsApp isn't installed") }
                }
                2 -> try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))) } catch (_: Exception) {}
                else -> dial(num)
            }
        }.show().big()
    }

    // ===================================================================== setup

    private fun add() {
        AlertDialog.Builder(this).setTitle("Add a person").setItems(arrayOf("📇  From phone contacts", "⌨️  Type the name and number")) { _, w ->
            if (w == 0) try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT)
            } catch (_: Exception) { toast("No contacts app found") }
            else edit(Contact(People.newId(), "", "", Contact.COLORS[People.all().size % Contact.COLORS.size]), isNew = true)
        }.show()
    }

    private fun edit(c: Contact, isNew: Boolean = false) {
        val box = vbox()
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val name = EditText(this).apply { hint = "Name (e.g. Chhora, Didi)"; setText(c.name); textSize = 20f; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val num = EditText(this).apply { hint = "Phone number"; setText(c.number); textSize = 20f; inputType = InputType.TYPE_CLASS_PHONE }
        box.addView(name)
        box.addView(num)
        box.addView(button("🖼  Choose photo", C.CARD2, C.TEXT) {
            editing = c.also { it.name = name.text.toString().trim(); it.number = num.text.toString().trim() }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_PHOTO)
            } catch (_: Exception) { toast("No photo picker found") }
        }.lin(t = dp(10)))
        val colors = hbox()
        fun drawColors() {
            colors.removeAllViews()
            for (col in Contact.COLORS) {
                val v = View(this)
                v.background = rounded(col, dpf(16f), if (col == c.color) dp(3) else 0, Color.WHITE)
                v.setOnClickListener { c.color = col; drawColors() }
                colors.addView(v, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(8) })
            }
        }
        drawColors()
        box.addView(HorizontalScrollView(this).apply { addView(colors) }.lin(t = dp(10)))
        if (!isNew) {
            val mv = hbox()
            mv.addView(button("◀ Move", C.CARD2, C.TEXT) { People.move(c, -1); toast("Moved"); draw() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            mv.addView(button("Move ▶", C.CARD2, C.TEXT) { People.move(c, 1); toast("Moved"); draw() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(mv.lin(t = dp(10)))
        }
        val d = AlertDialog.Builder(this).setTitle(if (isNew) "New person" else c.name).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                c.name = name.text.toString().trim()
                c.number = num.text.toString().trim()
                if (c.name.isEmpty() || People.clean(c.number).isEmpty()) { toast("Write a name and a number"); return@setPositiveButton }
                People.put(c)
                draw()
            }
            .setNegativeButton("Cancel", null)
        if (!isNew) d.setNeutralButton("Delete") { _, _ ->
            AlertDialog.Builder(this).setTitle("Remove ${c.name}?").setPositiveButton("Remove") { _, _ -> People.remove(this, c); draw() }
                .setNegativeButton("Cancel", null).show()
        }
        d.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        val uri = data?.data ?: return
        when (req) {
            REQ_CONTACT -> try {
                contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val c = Contact(People.newId(), cur.getString(0) ?: "", cur.getString(1) ?: "", Contact.COLORS[People.all().size % Contact.COLORS.size])
                        cur.getString(2)?.let { p -> People.savePhoto(this, Uri.parse(p), c) }
                        edit(c, isNew = true)
                    }
                }
            } catch (_: Exception) { toast("Couldn't read that contact") }
            REQ_PHOTO -> editing?.let { c ->
                if (People.savePhoto(this, uri, c)) toast("Photo saved") else toast("Couldn't use that picture")
                if (c.name.isNotEmpty() && People.clean(c.number).isNotEmpty()) People.put(c)
                draw()
                edit(c, isNew = People.all().none { it.id == c.id })
            }
        }
    }

    private fun settings(col: LinearLayout) {
        val c = card()
        fun row(name: String, opts: List<String>, sel: Int, pick: (Int) -> Unit) {
            c.addView(label(name, 14f, C.SUB, true).lin(t = dp(10), b = dp(4)))
            val r = hbox()
            opts.forEachIndexed { i, o -> r.addView(chip(o, i == sel) { pick(i); draw() }) }
            c.addView(HorizontalScrollView(this).apply { addView(r) })
        }
        row("Photos in a row", listOf("1", "2", "3", "4"), cols() - 1) { Store.setInt("cols", it + 1) }
        row("Name size", listOf("Small", "Medium", "Large", "Huge"), listOf(16, 22, 28, 34).indexOf(nameSize()).coerceAtLeast(0)) {
            Store.setInt("name_size", listOf(16, 22, 28, 34)[it])
        }
        row("When a photo is tapped", listOf("3-second countdown", "Ask first", "Call at once"), callMode()) { Store.setInt("call_mode", it) }
        row("Say the name aloud", listOf("Yes", "No"), if (Store.bool("speak", true)) 0 else 1) { Store.setBool("speak", it == 0) }
        row("Clock and battery", listOf("Show", "Hide"), if (Store.bool("clock", true)) 0 else 1) { Store.setBool("clock", it == 0) }
        row("Emergency buttons (100, 101, 102)", listOf("Show", "Hide"), if (Store.bool("emergency", true)) 0 else 1) { Store.setBool("emergency", it == 0) }
        c.addView(button(if (isHome()) "🏠 It is the home screen" else "🏠 Make it the home screen", C.CARD2, C.TEXT) {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
            }
        }.lin(t = dp(14)))
        c.addView(label("As the home screen, the phone always opens on these photos. Press Home to come back from any call.", 12f, C.SUB).lin(t = dp(6)))
        col.addView(c)
    }

    /** bigger buttons in dialogs */
    private fun AlertDialog.big(): AlertDialog {
        for (b in listOf(android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE)) {
            getButton(b)?.textSize = 22f
        }
        return this
    }

    companion object {
        const val REQ_CONTACT = 11
        const val REQ_PHOTO = 12
    }
}
