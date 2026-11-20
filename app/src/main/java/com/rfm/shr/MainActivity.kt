package com.rfm.shr

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import java.io.*
import java.net.*
import java.util.Collections

class MainActivity : Activity() {
    private val PORT = 8765
    private val UDP = 8766
    private val WA = "https://wa.me/201027814323"
    private val W = 0xFFFFFFFF.toInt()
    private val MUTED = 0xFF9AA4C7.toInt()
    private val C1 = 0xFF7C5CFF.toInt()
    private val C2 = 0xFF00D4FF.toInt()
    private lateinit var root: FrameLayout
    private val picked = mutableListOf<Uri>()
    private val found = HashSet<String>()
    private val rows = HashMap<Long, ProgressBar>()
    private var list: LinearLayout? = null
    private var status: TextView? = null
    private var mode = ""
    @Volatile private var running = false
    private var rx: ServerSocket? = null
    private var ds: DatagramSocket? = null
    private var after: (() -> Unit)? = null
    private val myName: String = Build.MODEL ?: "SH R"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B0F1A.toInt(), 0xFF171C33.toInt()))
        window.statusBarColor = 0xFF0B0F1A.toInt()
        window.navigationBarColor = 0xFF171C33.toInt()
        setContentView(root)
        home()
    }

    // ---------- UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)
    private fun screen(v: View) { root.removeAllViews(); root.addView(v) }
    private fun tv(t: String, sz: Float, c: Int = W, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(c); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun card(c: Int = 0x1AFFFFFF) = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat(); setColor(c); setStroke(dp(1), 0x22FFFFFF)
    }
    private fun grad() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C1, C2)).apply { cornerRadius = dp(22).toFloat() }

    inner class Radar(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val an = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }
        init { an.start() }
        override fun onDetachedFromWindow() { an.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(cv: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = width / 2f
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat()
            for (i in 0..2) {
                val f = (t + i / 3f) % 1f
                p.color = Color.argb(((1 - f) * 190).toInt(), 0, 212, 255)
                cv.drawCircle(cx, cy, r * f, p)
            }
            p.style = Paint.Style.FILL; p.color = C1
            cv.drawCircle(cx, cy, dp(14).toFloat(), p)
        }
    }

    private fun badge(): View {
        val t = tv("</>  rFM", 14f, W, true)
        t.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        t.setPadding(dp(18), dp(8), dp(18), dp(8)); t.background = card(0x14FFFFFF)
        t.post {
            val w = t.paint.measureText(t.text.toString())
            val sh = LinearGradient(0f, 0f, w, 0f, intArrayOf(C1, C2, W, C1), null, Shader.TileMode.MIRROR)
            t.paint.shader = sh
            ValueAnimator.ofFloat(0f, w * 2).apply {
                duration = 2200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { val m = Matrix(); m.setTranslate(it.animatedValue as Float, 0f); sh.setLocalMatrix(m); t.invalidate() }
            }.start()
        }
        t.setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WA))) } catch (e: Exception) { toast("+201027814323") } }
        return t
    }

    private fun bigBtn(t: String, s: String, g: Boolean, a: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(18))
        background = if (g) grad() else card()
        addView(tv(t, 20f, W, true)); addView(tv(s, 13f, if (g) 0xDDFFFFFF.toInt() else MUTED))
        layoutParams = lp().apply { topMargin = dp(14) }
        setOnClickListener { a() }
    }

    private fun home() {
        mode = ""
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        val logo = tv("SH R", 44f, W, true); logo.letterSpacing = 0.1f
        logo.post { logo.paint.shader = LinearGradient(0f, 0f, logo.paint.measureText("SH R"), 0f, C1, C2, Shader.TileMode.CLAMP); logo.invalidate() }
        c.addView(logo)
        c.addView(tv("نقل الملفات بين الأجهزة بدون إنترنت", 14f, MUTED))
        c.addView(Radar(this), lp(dp(200), dp(200)).apply { topMargin = dp(24); gravity = Gravity.CENTER_HORIZONTAL })
        c.addView(bigBtn("إرسال", "اختر ملفات وأرسلها لجهاز قريب", true) { pick() })
        c.addView(bigBtn("استقبال", "اجعل جهازك ظاهراً لاستقبال الملفات", false) { need { startReceive() } })
        c.addView(tv("نصيحة: شغّل هوت سبوت على جهاز واتصل به من الجهاز الآخر، ثم ابدأ الإرسال والاستقبال.", 12f, MUTED).apply { setPadding(0, dp(16), 0, 0); gravity = Gravity.CENTER })
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(badge())
        screen(c)
    }

    private fun panel(title: String, sub: String, radar: Boolean) {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(24)) }
        val back = tv("→ رجوع", 15f, C2); back.setOnClickListener { reset(); home() }
        c.addView(back)
        c.addView(tv(title, 26f, W, true).apply { setPadding(0, dp(14), 0, 0) })
        status = tv(sub, 14f, MUTED); c.addView(status)
        if (radar) c.addView(Radar(this), lp(dp(150), dp(150)).apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL })
        val sv = ScrollView(this); val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(inner); list = inner
        c.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        c.addView(badge(), lp().apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        screen(c)
    }

    private fun addRow(id: Long, t: String) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = card(); layoutParams = lp().apply { topMargin = dp(10) } }
        r.addView(tv(t, 14f))
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.max = 100
        r.addView(pb, lp()); rows[id] = pb; list?.addView(r)
    }

    private fun devRow(label: String, a: () -> Unit) = tv(label, 17f, W, true).apply {
        setPadding(dp(18), dp(16), dp(18), dp(16)); background = card(); layoutParams = lp().apply { topMargin = dp(10) }
        setOnClickListener { a() }
    }

    private fun reset() {
        running = false
        try { rx?.close() } catch (e: Exception) {}
        try { ds?.close() } catch (e: Exception) {}
        found.clear(); rows.clear(); list = null
    }

    override fun onBackPressed() { if (mode != "") { reset(); home() } else super.onBackPressed() }
    override fun onDestroy() { running = false; super.onDestroy() }

    // ---------- permissions / files ----------
    private fun need(go: () -> Unit) {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            after = go; requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        } else go()
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (rc == 7 && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) after?.invoke() else if (rc == 7) toast("الصلاحية مطلوبة لحفظ الملفات")
    }
    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, 1)
    }
    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        super.onActivityResult(rq, rs, d)
        if (rq == 1 && rs == RESULT_OK && d != null) {
            picked.clear()
            val cd = d.clipData
            if (cd != null) for (i in 0 until cd.itemCount) picked.add(cd.getItemAt(i).uri) else d.data?.let { picked.add(it) }
            if (picked.isNotEmpty()) startSend()
        }
    }
    private fun fname(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) { val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return it.getString(i) }
        }
        return "file_${System.currentTimeMillis()}"
    }
    private fun fsize(u: Uri): Long = contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: 0L
    private fun openOut(nm: String): OutputStream {
        val safe = nm.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, safe); put(MediaStore.Downloads.RELATIVE_PATH, "Download/SH R") }
            return contentResolver.openOutputStream(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!)!!
        }
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SH R"); d.mkdirs()
        return FileOutputStream(File(d, safe))
    }

    // ---------- network ----------
    private fun ifaces(): List<NetworkInterface> = try { Collections.list(NetworkInterface.getNetworkInterfaces()) } catch (e: Exception) { emptyList() }
    private fun ip(): String {
        val pref = Regex("^(wlan|ap|swlan|eth|p2p).*")
        for (strict in listOf(true, false)) for (n in ifaces()) {
            if (strict && !pref.matches(n.name)) continue
            for (a in n.interfaceAddresses) { val h = a.address; if (h is Inet4Address && !h.isLoopbackAddress) return h.hostAddress ?: "" }
        }
        return "—"
    }
    private fun bcasts(): List<InetAddress> {
        val l = ArrayList<InetAddress>()
        for (n in ifaces()) for (a in n.interfaceAddresses) a.broadcast?.let { l.add(it) }
        try { l.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) {}
        return l
    }

    private fun startReceive() {
        mode = "r"; running = true
        panel("استقبال", "جهازك ظاهر باسم $myName\nعنوان الجهاز (IP): ${ip()}", true)
        Thread {
            try { val ss = ServerSocket(PORT); rx = ss; while (running) { val s = ss.accept(); Thread { recv(s) }.start() } } catch (e: Exception) {}
        }.start()
        Thread {
            try {
                val d = DatagramSocket(); d.broadcast = true
                val msg = "SHR|$myName".toByteArray()
                while (running) {
                    for (a in bcasts()) try { d.send(DatagramPacket(msg, msg.size, a, UDP)) } catch (e: Exception) {}
                    Thread.sleep(1000)
                }
                d.close()
            } catch (e: Exception) {}
        }.start()
    }

    private fun recv(s: Socket) {
        try {
            val i = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
            val n = i.readInt()
            repeat(n) {
                val nm = i.readUTF(); val sz = i.readLong(); val id = System.nanoTime()
                runOnUiThread { addRow(id, "⬇ $nm") }
                val out = openOut(nm); val buf = ByteArray(65536); var left = sz; var done = 0L; var last = -1
                while (left > 0) {
                    val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) throw EOFException()
                    out.write(buf, 0, r); left -= r; done += r
                    val pc = (done * 100 / maxOf(sz, 1L)).toInt()
                    if (pc != last) { last = pc; runOnUiThread { rows[id]?.progress = pc } }
                }
                out.close()
                runOnUiThread { rows[id]?.progress = 100; status?.text = "✅ تم حفظ $nm في Download/SH R" }
            }
            s.close()
        } catch (e: Exception) { runOnUiThread { status?.text = "انقطع الاتصال" } }
    }

    private fun startSend() {
        mode = "s"; running = true
        panel("إرسال", "${picked.size} ملف — جارٍ البحث عن أجهزة قريبة…", true)
        val et = EditText(this).apply {
            hint = "أو اكتب IP الجهاز المستقبل"; setTextColor(W); setHintTextColor(0xFF6B7499.toInt())
            background = card(); setPadding(dp(16), dp(12), dp(16), dp(12)); layoutParams = lp()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        list?.addView(et)
        list?.addView(devRow("اتصال بالـ IP") { if (et.text.isNotBlank()) connect(et.text.toString().trim()) })
        Thread {
            try {
                val d = DatagramSocket(null); d.reuseAddress = true; d.bind(InetSocketAddress(UDP)); ds = d
                val b = ByteArray(256)
                while (running) {
                    val p = DatagramPacket(b, b.size); d.receive(p)
                    val m = String(p.data, 0, p.length)
                    val ipa = p.address.hostAddress ?: continue
                    if (m.startsWith("SHR|") && found.add(ipa)) {
                        val nm = m.substring(4)
                        runOnUiThread { list?.addView(devRow("📱  $nm") { connect(ipa) }) }
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun connect(ipa: String) {
        status?.text = "جارٍ الإرسال إلى $ipa …"
        val files = picked.toList()
        Thread {
            try {
                val s = Socket(); s.connect(InetSocketAddress(ipa, PORT), 5000)
                val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 65536))
                o.writeInt(files.size)
                for (u in files) {
                    val nm = fname(u); val sz = fsize(u); val id = System.nanoTime()
                    runOnUiThread { addRow(id, "⬆ $nm") }
                    o.writeUTF(nm); o.writeLong(sz)
                    contentResolver.openInputStream(u)!!.use { ins ->
                        val buf = ByteArray(65536); var done = 0L; var last = -1
                        while (true) {
                            val r = ins.read(buf); if (r < 0) break
                            o.write(buf, 0, r); done += r
                            val pc = (done * 100 / maxOf(sz, 1L)).toInt()
                            if (pc != last) { last = pc; runOnUiThread { rows[id]?.progress = pc } }
                        }
                    }
                }
                o.flush(); s.close()
                runOnUiThread { status?.text = "✅ تم إرسال كل الملفات" }
            } catch (e: Exception) { runOnUiThread { status?.text = "تعذر الاتصال: ${e.message}" } }
        }.start()
    }
}
