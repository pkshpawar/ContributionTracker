package com.prakashpawar.contribution

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import kotlin.math.abs
import kotlin.math.ceil
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

const val APP_VERSION = "@Prakash Pawar 1.0"
val Teal = Color(0xFF156082)
val CATEGORIES = listOf(
    "Train Reservation/Ticket", "Common Travel (Auto, Bus, Local Vehicle etc)", "Toll", "Water",
    "Room Booking", "Sweeper / Cleaning", "Breakfast/Lunch/Dinner", "Coldrinks/Hot drinks", "Snacks", "Others"
)
val MODES = listOf("Cash", "UPI", "Online", "Others")

data class Coll(val member: String, val toWhom: String, val amount: Double, val at: String = "")
data class Exp(val member: String, val date: String, val category: String, val mode: String, val desc: String, val amount: Double, val updated: String = "")

class Store(private val ctx: Context) {
    var title by mutableStateOf("")
    var collector by mutableStateOf("")
    val members = mutableStateListOf<String>()
    val colls = mutableStateListOf<Coll>()
    val exps = mutableStateListOf<Exp>()
    val totalColl get() = colls.sumOf { it.amount }
    val totalExp get() = exps.sumOf { it.amount }
    val collected get() = colls.filter { it.toWhom.trim().equals(collector, true) }.sumOf { it.amount }
    val collectorSpent get() = if (collector.isEmpty()) 0.0 else paid(collector)
    val collectorCash get() = collected - collectorSpent   // negative = borrowed (collector's own money)
    fun cashLabel() = if (collectorCash >= 0) "Remaining with collector" else "Borrowed by collector"
    val share get() = if (members.isEmpty()) 0.0 else up(totalExp / members.size)   // equal share, rounded up
    fun paid(m: String) = exps.filter { it.member == m }.sumOf { it.amount }
    private fun isMember(n: String) = members.any { it.equals(n.trim(), true) }
    // balance > 0: should receive, < 0: should pay. Collections between members adjust it.
    val absorberName get() = members.find { it.equals(collector, true) } ?: members.firstOrNull() ?: ""
    // everyone pays the same rounded share; the collector absorbs the rounding difference
    fun shareOf(m: String): Double =
        if (members.isEmpty()) 0.0 else if (m == absorberName) totalExp - share * (members.size - 1) else share
    fun net(m: String): Double {
        val given = colls.filter { it.member == m && isMember(it.toWhom) && !it.toWhom.trim().equals(m, true) }.sumOf { it.amount }
        val recv = colls.filter { it.toWhom.trim().equals(m, true) && it.member != m }.sumOf { it.amount }
        return paid(m) + given - recv - shareOf(m)
    }
    // Paid = member's expense; Difference = contributed + expense; Balance = equal share - Difference
    fun contributed(m: String) = colls.filter { it.member == m }.sumOf { it.amount }
    fun difference(m: String) = contributed(m) + paid(m)
    fun stmtBalance(m: String) = shareOf(m) - difference(m)
    fun settlements(): List<Triple<String, String, Double>> {
        val nets = members.associateWith { Math.round(net(it)).toDouble() }.toMutableMap()
        val ab = absorberName
        if (ab.isNotEmpty()) nets[ab] = -nets.filterKeys { it != ab }.values.sum()   // rounding difference goes to the collector
        val deb = nets.filter { it.value < -0.5 }.mapValues { -it.value }.toMutableMap()
        val cre = nets.filter { it.value > 0.5 }.toMutableMap()
        val out = mutableListOf<Triple<String, String, Double>>()
        while (deb.isNotEmpty() && cre.isNotEmpty()) {
            val d = deb.maxByOrNull { it.value }!!; val c = cre.maxByOrNull { it.value }!!
            val a = minOf(d.value, c.value)
            out.add(Triple(d.key, c.key, a))
            deb[d.key] = d.value - a; cre[c.key] = c.value - a
            if (deb[d.key]!! < 0.005) deb.remove(d.key)
            if (cre[c.key]!! < 0.005) cre.remove(c.key)
        }
        return out
    }

    init {
        val s = ctx.getSharedPreferences("data", Context.MODE_PRIVATE).getString("json", null)
        if (s != null) runCatching { fromJson(JSONObject(s)) }
    }

    fun save() = ctx.getSharedPreferences("data", Context.MODE_PRIVATE).edit().putString("json", toJson().toString()).apply()

    fun backupCsv(): String {
        val b = StringBuilder()
        b.appendLine("#ContributionTrackerBackup,1")
        b.appendLine("TITLE,${csvq(title)}"); b.appendLine("COLLECTOR,${csvq(collector)}")
        members.forEach { b.appendLine("MEMBER,${csvq(it)}") }
        colls.forEach { b.appendLine("COLLECTION,${csvq(it.member)},${csvq(it.toWhom)},${it.amount},${csvq(it.at)}") }
        exps.forEach { b.appendLine("EXPENSE,${csvq(it.member)},${csvq(it.date)},${csvq(it.category)},${csvq(it.mode)},${csvq(it.desc)},${it.amount},${csvq(it.updated)}") }
        return b.toString()
    }

    fun importCsv(text: String): Boolean {
        val rows = parseCsv(text)
        if (rows.isEmpty() || rows[0].firstOrNull() != "#ContributionTrackerBackup") return false
        val ms = mutableListOf<String>(); val cs = mutableListOf<Coll>(); val es = mutableListOf<Exp>()
        var ti = ""; var co = ""
        rows.drop(1).forEach { r ->
            fun g(i: Int) = r.getOrElse(i) { "" }
            when (r.firstOrNull()) {
                "TITLE" -> ti = g(1)
                "COLLECTOR" -> co = g(1)
                "MEMBER" -> if (g(1).isNotEmpty()) ms.add(g(1))
                "COLLECTION" -> cs.add(Coll(g(1), g(2), g(3).toDoubleOrNull() ?: 0.0, g(4)))
                "EXPENSE" -> es.add(Exp(g(1), g(2), g(3), g(4), g(5), g(6).toDoubleOrNull() ?: 0.0, g(7)))
            }
        }
        title = ti; collector = co
        members.clear(); members.addAll(ms); colls.clear(); colls.addAll(cs); exps.clear(); exps.addAll(es)
        save(); return true
    }

    fun toJson() = JSONObject().apply {
        put("title", title)
        put("collector", collector)
        put("members", JSONArray(members))
        put("collections", JSONArray().also { a -> colls.forEach { a.put(JSONObject().put("member", it.member).put("toWhom", it.toWhom).put("amount", it.amount).put("at", it.at)) } })
        put("expenses", JSONArray().also { a -> exps.forEach { a.put(JSONObject().put("member", it.member).put("date", it.date).put("category", it.category).put("mode", it.mode).put("desc", it.desc).put("amount", it.amount).put("updated", it.updated)) } })
    }

    fun fromJson(j: JSONObject) {
        title = j.optString("title"); collector = j.optString("collector"); members.clear(); colls.clear(); exps.clear()
        j.optJSONArray("members")?.let { for (i in 0 until it.length()) members.add(it.getString(i)) }
        j.optJSONArray("collections")?.let { for (i in 0 until it.length()) it.getJSONObject(i).let { o -> colls.add(Coll(o.getString("member"), o.getString("toWhom"), o.getDouble("amount"), o.optString("at"))) } }
        j.optJSONArray("expenses")?.let { for (i in 0 until it.length()) it.getJSONObject(i).let { o -> exps.add(Exp(o.getString("member"), o.getString("date"), o.getString("category"), o.getString("mode"), o.getString("desc"), o.getDouble("amount"), o.optString("updated"))) } }
        save()
    }
}

fun up(d: Double) = ceil(d - 1e-6)   // round up to whole rupee
fun now(): String = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())
val EXP_X = listOf(40f, 108f, 182f, 290f, 335f, 405f)
val EXP_L = listOf(10, 12, 18, 6, 10, 16)
fun today(): String = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
fun money(d: Double) = (if (d < -0.004) "-" else "") + "₹" + String.format(Locale.getDefault(), "%.2f", abs(d))
fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

fun write(ctx: Context, uri: Uri, text: String) =
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }

fun parseCsv(text: String): List<List<String>> {
    val s = text.removePrefix("\uFEFF")
    val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val sb = StringBuilder()
    var inQ = false; var i = 0
    while (i < s.length) {
        val c = s[i]
        if (inQ) {
            if (c == '"') { if (i + 1 < s.length && s[i + 1] == '"') { sb.append('"'); i++ } else inQ = false } else sb.append(c)
        } else if (c == '"') inQ = true
        else if (c == ',') { row.add(sb.toString()); sb.clear() }
        else if (c == '\n') { row.add(sb.toString().trimEnd('\r')); sb.clear(); rows.add(row); row = mutableListOf() }
        else sb.append(c)
        i++
    }
    if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString().trimEnd('\r')); rows.add(row) }
    return rows
}

fun backupName(title: String): String {
    val base = title.trim().ifEmpty { "Contribution" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return base + "_" + SimpleDateFormat("ddMMyyyyHHmmss", Locale.getDefault()).format(Date()) + ".csv"
}

fun csvq(t: String) = "\"" + t.replace("\"", "\"\"") + "\""
const val FOOTER = "Thank you! Developed by Prakash Pawar,Contact: 9892399306"
fun dkey(d: String) = if (d.length >= 10) d.substring(6, 10) + d.substring(3, 5) + d.substring(0, 2) else "0"
fun cDate(c: Coll) = c.at.take(10).ifEmpty { "Unknown" }
fun eDate(e: Exp) = e.date.ifEmpty { "Unknown" }
data class StRow(val date: String, val part: String, val coll: Double, val exp: Double, val bal: Double, val isDate: Boolean = false)
fun statement(s: Store): List<StRow> {
    val out = mutableListOf<StRow>(); var bal = 0.0
    dates(s).forEach { d ->
        out.add(StRow(d, d, 0.0, 0.0, bal, true))
        s.colls.filter { cDate(it) == d }.forEach { bal += it.amount; out.add(StRow(d, "Collection: ${it.member} -> ${it.toWhom}", it.amount, 0.0, bal)) }
        s.exps.filter { eDate(it) == d }.forEach { bal -= it.amount; out.add(StRow(d, "${it.category} (${it.member})", 0.0, it.amount, bal)) }
    }
    return out
}
fun dates(s: Store) = (s.colls.map { cDate(it) } + s.exps.map { eDate(it) }).distinct().sortedBy { dkey(it) }

fun csv(s: Store): String {
    val b = StringBuilder()
    b.appendLine(csvq("Contribution For: " + s.title))
    b.appendLine(); b.appendLine("Category,Entries,Total")
    CATEGORIES.forEach { c -> val l = s.exps.filter { it.category == c }; b.appendLine("${csvq(c)},${l.size},${l.sumOf { it.amount }}") }
    b.appendLine("Total Expenses,,${s.totalExp}"); b.appendLine("Total Collection,,${s.totalColl}")
    b.appendLine("Total Members,,${s.members.size}"); b.appendLine("Equal Share Per Person,,${s.share}"); b.appendLine("Collector Share (rounding adjusted),,${s.shareOf(s.absorberName)}")
    b.appendLine("Collector,,${csvq(s.collector)}"); b.appendLine("Collected,,${s.collected}"); b.appendLine("Expense Paid By Collector,,${s.collectorSpent}"); b.appendLine("${s.cashLabel()},,${abs(s.collectorCash)}")
    b.appendLine(); b.appendLine("Statement"); b.appendLine("Date,Particular,Collection,Expense,Balance")
    statement(s).filter { !it.isDate }.forEach { b.appendLine("${it.date},${csvq(it.part)},${if (it.coll != 0.0) it.coll.toString() else ""},${if (it.exp != 0.0) it.exp.toString() else ""},${it.bal}") }
    b.appendLine(); b.appendLine("Datewise Report"); b.appendLine("Date,Type,Member,To Whom / Category,Mode,Description,Amount,Entered / Updated On")
    dates(s).forEach { d ->
        val cl = s.colls.filter { cDate(it) == d }; val ex = s.exps.filter { eDate(it) == d }
        cl.forEach { b.appendLine("$d,Collection,${csvq(it.member)},${csvq(it.toWhom)},,,${it.amount},${csvq(it.at)}") }
        ex.forEach { b.appendLine("$d,Expense,${csvq(it.member)},${csvq(it.category)},${it.mode},${csvq(it.desc)},${it.amount},${csvq(it.updated)}") }
        b.appendLine("$d,Collection Total,,,,,${cl.sumOf { it.amount }}"); b.appendLine("$d,Expense Total,,,,,${ex.sumOf { it.amount }}")
    }
    b.appendLine(); b.appendLine("Difference (Collection - Expense),,${s.totalColl - s.totalExp}")
    b.appendLine(); b.appendLine("Member,Paid,Difference,Balance")
    s.members.forEach { b.appendLine("${csvq(it)},${s.paid(it)},${s.difference(it)},${s.stmtBalance(it)}") }
    b.appendLine(); b.appendLine("Who Gives,To Whom,Amount")
    s.settlements().forEach { b.appendLine("${csvq(it.first)},${csvq(it.second)},${it.third}") }
    b.appendLine(); b.appendLine(FOOTER)
    return b.toString()
}

fun csvColl(s: Store): String {
    val b = StringBuilder()
    b.appendLine(csvq("Collection Report: " + s.title)); b.appendLine("Collector,${csvq(s.collector)}")
    b.appendLine(); b.appendLine("Date,Who Gave,To Whom,Amount,Entered On")
    s.colls.sortedWith(compareBy({ dkey(cDate(it)) }, { it.at.drop(11) })).forEach {
        b.appendLine("${cDate(it)},${csvq(it.member)},${csvq(it.toWhom)},${it.amount},${csvq(it.at)}") }
    b.appendLine("Total Collection,,,${s.totalColl}")
    b.appendLine(); b.appendLine(FOOTER)
    return b.toString()
}

fun csvExp(s: Store): String {
    val b = StringBuilder()
    b.appendLine(csvq("Expense Report: " + s.title))
    b.appendLine(); b.appendLine("Purchase Date,Member,Category,Mode,Description,Amount,Updated On")
    s.exps.sortedWith(compareBy({ dkey(eDate(it)) }, { it.updated.drop(11) })).forEach {
        b.appendLine("${eDate(it)},${csvq(it.member)},${csvq(it.category)},${it.mode},${csvq(it.desc)},${it.amount},${csvq(it.updated)}") }
    b.appendLine("Total Expenses,,,,,${s.totalExp}")
    b.appendLine(); b.appendLine(FOOTER)
    return b.toString()
}

fun csvSettle(s: Store): String {
    val b = StringBuilder()
    b.appendLine(csvq("Members Report: " + s.title))
    b.appendLine("Total Members,${s.members.size}"); b.appendLine("Total Expense,${s.totalExp}"); b.appendLine("Equal Share Per Person,${s.share}"); b.appendLine("Collector Share (rounding adjusted),${s.shareOf(s.absorberName)}")
    b.appendLine("Collector,${csvq(s.collector)}"); b.appendLine("Collected,${s.collected}"); b.appendLine("Expense Paid By Collector,${s.collectorSpent}"); b.appendLine("${s.cashLabel()},${abs(s.collectorCash)}")
    b.appendLine(); b.appendLine("Member,Paid,Difference,Balance")
    s.members.forEach { b.appendLine("${csvq(it)},${s.paid(it)},${s.difference(it)},${s.stmtBalance(it)}") }
    b.appendLine(); b.appendLine("Who Gives,To Whom,Amount")
    s.settlements().forEach { b.appendLine("${csvq(it.first)},${csvq(it.second)},${it.third}") }
    b.appendLine(); b.appendLine(FOOTER)
    return b.toString()
}

val C_TEAL = 0xFF156082.toInt(); val C_ZEBRA = 0xFFEAF3F8.toInt(); val C_GROUP = 0xFFD6EAF3.toInt()
val C_TOTAL = 0xFFFFE9B3.toInt(); val C_LINE = 0xFFB0C4CE.toInt(); val C_RED = 0xFFB3261E.toInt()
val C_ORANGE = 0xFFE67E22.toInt(); val C_GREEN = 0xFF2E8B57.toInt(); val C_PURPLE = 0xFF6A4C93.toInt(); val C_WHITE = 0xFFFFFFFF.toInt()

class TRow(val cells: List<String>, val kind: Int = 0)   // kind: 0 normal, 1 group (date), 2 total

fun pdfFit(t: String, w: Float, p: Paint): String {
    if (p.measureText(t) <= w) return t
    var e = t.length
    while (e > 0 && p.measureText(t, 0, e) + p.measureText("…") > w) e--
    return t.substring(0, e) + "…"
}

class PdfB(val s: Store) {
    val doc = PdfDocument()
    private var n = 0
    private lateinit var page: PdfDocument.Page
    private var y = 0f
    private val W = 595f; private val H = 842f; private val M = 36f
    private val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fp = Paint()
    private val sp = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = C_LINE }
    init { start() }

    private fun start() { n++; page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), n).create()); y = 40f }
    private fun end() {
        page.canvas.drawLine(M, H - 36f, W - M, H - 36f, sp)
        tp.textSize = 8f; tp.isFakeBoldText = false; tp.color = 0xFF555555.toInt()
        tp.textAlign = Paint.Align.LEFT
        page.canvas.drawText("ContributionTracker Dev. by Prakash Pawar - 9892399306  $date", M, H - 22f, tp)
        tp.textAlign = Paint.Align.RIGHT
        page.canvas.drawText("Page $n", W - M, H - 22f, tp)
        tp.textAlign = Paint.Align.LEFT
        doc.finishPage(page)
    }
    fun section(name: String) { end(); start(); banner(name) }

    fun banner(name: String) {
        fp.style = Paint.Style.FILL; fp.color = C_TEAL
        page.canvas.drawRect(0f, 0f, W, 56f, fp)
        tp.color = C_WHITE; tp.textSize = 17f; tp.isFakeBoldText = true
        page.canvas.drawText(pdfFit(s.title.ifBlank { "Contribution Information" }, W - 2 * M, tp), M, 27f, tp)
        tp.textSize = 10f; tp.isFakeBoldText = false
        page.canvas.drawText(name, M, 45f, tp)
        tp.textAlign = Paint.Align.RIGHT
        page.canvas.drawText("Collector: ${s.collector.ifEmpty { "-" }}  |  Members: ${s.members.size}", W - M, 45f, tp)
        tp.textAlign = Paint.Align.LEFT
        y = 70f
    }

    fun boxes(items: List<Triple<String, String, Int>>) {
        val gap = 8f; val bw = (W - 2 * M - gap * (items.size - 1)) / items.size; val bh = 40f
        items.forEachIndexed { i, (lab, v, col) ->
            val x = M + i * (bw + gap)
            fp.style = Paint.Style.FILL; fp.color = col
            page.canvas.drawRoundRect(x, y, x + bw, y + bh, 6f, 6f, fp)
            tp.color = C_WHITE; tp.isFakeBoldText = false; tp.textSize = 8.5f
            page.canvas.drawText(pdfFit(lab, bw - 12f, tp), x + 8f, y + 14f, tp)
            tp.isFakeBoldText = true; tp.textSize = 13f
            page.canvas.drawText(pdfFit(v, bw - 12f, tp), x + 8f, y + 32f, tp)
        }
        y += bh + 12f
    }

    fun label(text: String) {
        tp.color = C_TEAL; tp.isFakeBoldText = true; tp.textSize = 11f
        page.canvas.drawText(text, M, y + 11f, tp); y += 18f
    }
    fun note(text: String) {
        tp.color = 0xFF666666.toInt(); tp.isFakeBoldText = false; tp.textSize = 8f
        page.canvas.drawText(text, M, y + 9f, tp); y += 12f
    }

    fun table(heads: List<String>, widths: List<Float>, right: List<Boolean>, rows: List<TRow>) {
        val rh = 17f; val tw = widths.sum()
        fun cell(text: String, x: Float, w: Float, base: Float, r: Boolean) {
            val tx = pdfFit(text, w - 8f, tp)
            if (r) { tp.textAlign = Paint.Align.RIGHT; page.canvas.drawText(tx, x + w - 4f, base, tp) }
            else { tp.textAlign = Paint.Align.LEFT; page.canvas.drawText(tx, x + 4f, base, tp) }
            tp.textAlign = Paint.Align.LEFT
        }
        fun header() {
            var x = M
            tp.textSize = 9f; tp.isFakeBoldText = true
            heads.forEachIndexed { i, h ->
                fp.style = Paint.Style.FILL; fp.color = C_TEAL
                page.canvas.drawRect(x, y, x + widths[i], y + rh, fp); page.canvas.drawRect(x, y, x + widths[i], y + rh, sp)
                tp.color = C_WHITE; cell(h, x, widths[i], y + 12f, right[i]); x += widths[i]
            }
            y += rh
        }
        header()
        var k = 0
        rows.forEach { r ->
            if (y + rh > H - 48f) { end(); start(); header() }   // more entries -> extra page, header repeats
            tp.textSize = 9f
            if (r.kind == 1) {
                fp.style = Paint.Style.FILL; fp.color = C_GROUP
                page.canvas.drawRect(M, y, M + tw, y + rh, fp); page.canvas.drawRect(M, y, M + tw, y + rh, sp)
                tp.isFakeBoldText = true; tp.color = C_TEAL; cell(r.cells[0], M, tw, y + 12f, false)
            } else {
                val bg = if (r.kind == 2) C_TOTAL else if (k++ % 2 == 1) C_ZEBRA else C_WHITE
                var x = M
                widths.forEachIndexed { i, w ->
                    fp.style = Paint.Style.FILL; fp.color = bg
                    page.canvas.drawRect(x, y, x + w, y + rh, fp); page.canvas.drawRect(x, y, x + w, y + rh, sp)
                    val txt = r.cells.getOrElse(i) { "" }
                    tp.isFakeBoldText = r.kind == 2
                    tp.color = if (txt.startsWith("-₹")) C_RED else 0xFF222222.toInt()
                    cell(txt, x, w, y + 12f, right[i]); x += w
                }
            }
            y += rh
        }
        y += 10f
    }

    fun finish(ctx: Context, uri: Uri) {
        end()
        ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
    }
}

// mode: "full" | "settle" | "coll" | "exp"
fun writePdf(ctx: Context, uri: Uri, s: Store, mode: String = "full") {
    val b = PdfB(s)
    fun expTable() {
        val rows = s.exps.sortedWith(compareBy({ dkey(eDate(it)) }, { it.updated.drop(11) }))
            .map { TRow(listOf(eDate(it), it.member, it.category, it.mode, it.desc, money(it.amount))) }
        b.table(listOf("Purchase date", "Member", "Category", "Mode", "Description", "Amount"), listOf(66f, 80f, 130f, 45f, 117f, 85f),
            listOf(false, false, false, false, false, true), rows + TRow(listOf("Total", "", "", "", "", money(s.totalExp)), 2))
    }
    fun summaryTable() {
        val kv = mutableListOf("Total members" to "${s.members.size}", "Equal share per person (rounded up)" to money(s.share))
        if (s.members.isNotEmpty() && abs(s.shareOf(s.absorberName) - s.share) > 0.004) kv.add("Collector share (incl. rounding difference)" to money(s.shareOf(s.absorberName)))
        kv.add("Collector" to s.collector.ifEmpty { "-" }); kv.add("Collected by collector" to money(s.collected))
        kv.add("Expense paid by collector" to money(s.collectorSpent)); kv.add(s.cashLabel() to money(abs(s.collectorCash)))
        b.table(listOf("Item", "Value"), listOf(350f, 173f), listOf(false, true), kv.map { TRow(listOf(it.first, it.second)) })
    }
    fun memberTable() {
        b.table(listOf("Member", "Paid", "Difference", "Balance"), listOf(193f, 110f, 110f, 110f), listOf(false, true, true, true),
            s.members.map { TRow(listOf(it, money(s.paid(it)), money(s.difference(it)), money(s.stmtBalance(it)))) })
        b.note("Paid = expense by member   |   Difference = contributed + expense   |   Balance = equal share - difference (minus = to receive)")
    }
    fun whoGives() {
        val st = s.settlements()
        if (st.isEmpty()) { b.note("All settled - nobody needs to pay."); return }
        b.table(listOf("#", "Who gives", "To whom", "Amount"), listOf(30f, 195f, 195f, 103f), listOf(false, false, false, true),
            st.mapIndexed { i, x -> TRow(listOf("${i + 1}", x.first, x.second, money(x.third))) } + TRow(listOf("", "Total", "", money(st.sumOf { it.third })), 2))
    }
    val diff = s.totalColl - s.totalExp
    when (mode) {
        "settle" -> {
            b.banner("Members & Settlement Report")
            summaryTable(); b.label("Members"); memberTable(); b.label("Who gives whom"); whoGives()
        }
        "coll" -> {
            b.banner("Collection Report")
            b.boxes(listOf(Triple("Collector", s.collector.ifEmpty { "-" }, C_TEAL), Triple("Total collection", money(s.totalColl), C_GREEN), Triple("Entries", "${s.colls.size}", C_PURPLE)))
            val rows = s.colls.sortedWith(compareBy({ dkey(cDate(it)) }, { it.at.drop(11) }))
                .map { TRow(listOf(it.at.ifEmpty { "-" }, it.member, it.toWhom, money(it.amount))) }
            b.table(listOf("Entered on", "Who gave", "To whom", "Amount"), listOf(120f, 150f, 150f, 103f), listOf(false, false, false, true),
                rows + TRow(listOf("Total", "", "", money(s.totalColl)), 2))
        }
        "exp" -> {
            b.banner("Expense Report")
            b.boxes(listOf(Triple("Total expense", money(s.totalExp), C_ORANGE), Triple("Entries", "${s.exps.size}", C_PURPLE), Triple("Share / person", money(s.share), C_TEAL)))
            expTable()
        }
        else -> {
            // Page 1: statement (date wise)
            b.banner("Statement of Collection & Expense")
            b.boxes(listOf(Triple("Total collection", money(s.totalColl), C_TEAL), Triple("Total expense", money(s.totalExp), C_ORANGE),
                Triple("Balance", money(diff), if (diff < -0.004) C_RED else C_GREEN), Triple("Share / person", money(s.share), C_PURPLE)))
            val rows = mutableListOf<TRow>()
            statement(s).forEach { r ->
                if (r.isDate) rows.add(TRow(listOf(r.date), 1))
                else rows.add(TRow(listOf(r.part, if (r.coll != 0.0) money(r.coll) else "", if (r.exp != 0.0) money(r.exp) else "", money(r.bal))))
            }
            rows.add(TRow(listOf("Total", money(s.totalColl), money(s.totalExp), money(diff)), 2))
            b.table(listOf("Particular", "Collection", "Expense", "Balance"), listOf(263f, 80f, 80f, 100f), listOf(false, true, true, true), rows)
            // Page 2: expense entries
            b.section("Expense Entries")
            expTable()
            b.label("Category-wise tally")
            val cats = CATEGORIES.map { c -> val l = s.exps.filter { it.category == c }; TRow(listOf(c, "${l.size}", money(l.sumOf { it.amount }))) }
            b.table(listOf("Category", "Entries", "Total"), listOf(323f, 70f, 130f), listOf(false, true, true), cats + TRow(listOf("Total", "${s.exps.size}", money(s.totalExp)), 2))
            // Page 3: differences
            b.section("Differences")
            summaryTable(); b.label("Members"); memberTable()
            // Page 4: who gives whom
            b.section("Who Gives Whom")
            b.note("Equal share per person: ${money(s.share)}"); whoGives()
        }
    }
    b.finish(ctx, uri)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Teal)) { App(store) { finishAndRemoveTask() } } }
    }
}

@Composable
fun App(s: Store, onExit: () -> Unit) {
    var screen by remember { mutableStateOf("main") }
    var showWelcome by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(10_000); showWelcome = false }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (screen) {
                "main" -> MainPage(s, { screen = it }, onExit, showWelcome)
                "member" -> Scaffold1("Member Entry", { screen = "main" }) { MemberScreen(s) }
                "coll" -> Scaffold1("Collection", { screen = "main" }) { CollScreen(s) }
                "exp" -> Scaffold1("Expenses", { screen = "main" }) { ExpScreen(s) }
                "report" -> Scaffold1("Report", { screen = "main" }) { ReportScreen(s) }
                "settle" -> Scaffold1("Members & Settlement", { screen = "main" }) { SettleScreen(s) }
            }
        }
        Text(APP_VERSION, Modifier.fillMaxWidth().background(Teal).padding(6.dp).navigationBarsPadding(),
            color = Color.White, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun Scaffold1(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Teal).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("← Back", color = Color.White) }
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f).padding(12.dp)) { content() }
        OutlinedButton(back, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { Text("Back") }
    }
}

@Composable
fun ExportRow(s: Store, mode: String = "full") {
    val ctx = LocalContext.current
    val name = when (mode) { "settle" -> "members_settlement"; "coll" -> "collection_report"; "exp" -> "expense_report"; else -> "contribution_report" }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        u?.let { runCatching { writePdf(ctx, it, s, mode); toast(ctx, "PDF saved") }.onFailure { toast(ctx, "PDF failed") } } }
    val xls = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u ->
        u?.let { write(ctx, it, when (mode) { "settle" -> csvSettle(s); "coll" -> csvColl(s); "exp" -> csvExp(s); else -> csv(s) }); toast(ctx, "Excel saved") } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ pdf.launch("$name.pdf") }, Modifier.weight(1f)) { Text("Export PDF") }
        if (mode != "coll" && mode != "exp") Button({ xls.launch("$name.csv") }, Modifier.weight(1f)) { Text("Export Excel") }
    }
}

@Composable
fun StatCard(label: String, value: String, mod: Modifier, neg: Boolean = false, onClick: (() -> Unit)? = null) {
    val fg = if (neg) Color(0xFFB3261E) else Color.White
    Card(if (onClick != null) mod.clickable { onClick() } else mod,
        colors = CardDefaults.cardColors(containerColor = if (neg) Color(0xFFFDECEA) else Teal), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(value, color = fg, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

@Composable
fun Bar(left: String, right: String, mod: Modifier = Modifier) {
    Row(mod.fillMaxWidth().background(Teal, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, Modifier.weight(1f), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(right, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
fun MainPage(s: Store, go: (String) -> Unit, onExit: () -> Unit, welcome: Boolean) {
    var showMembers by remember { mutableStateOf(false) }
    var showThanks by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(s.title.isBlank()) }   // locks after leaving the page
    val nav: (String) -> Unit = { editing = false; go(it) }
    val diff = s.totalColl - s.totalExp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Contribution Manager", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Teal)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (editing) OutlinedTextField(s.title, { s.title = it; s.save() }, label = { Text("Contribution For") }, modifier = Modifier.weight(1f), singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold))
            else Column(Modifier.weight(1f)) {
                Text("Contribution For", fontSize = 10.sp, color = Teal)
                Text(s.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Button({ if (editing) { if (s.title.isNotBlank()) editing = false } else editing = true }) { Text(if (editing) "Save" else "Edit", fontSize = 13.sp) }
        }
        Bar("Collection (Collector: ${s.collector.ifEmpty { "none" }})", money(s.totalColl))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatCard("Total Expenses", money(s.totalExp), Modifier.weight(1f))
            StatCard("Difference", money(diff), Modifier.weight(1f), neg = diff < -0.004)
            StatCard("Share / Person", money(s.share), Modifier.weight(1f))
        }
        Bar("Members (tap to view list)", "${s.members.size}", Modifier.clickable { showMembers = true })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ nav("coll") }, Modifier.weight(1f)) { Text("Collection", fontSize = 13.sp) }
            Button({ nav("exp") }, Modifier.weight(1f)) { Text("Expenses", fontSize = 13.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ nav("member") }, Modifier.weight(1f)) { Text("Member Entry", fontSize = 13.sp) }
            Button({ nav("report") }, Modifier.weight(1f)) { Text("Report", fontSize = 13.sp) }
        }
        Button({ nav("settle") }, Modifier.fillMaxWidth()) { Text("Members List & Who Pays Whom", fontSize = 13.sp) }
        Button({ editing = false; showThanks = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("Exit", fontSize = 13.sp) }
        Box(Modifier.fillMaxWidth().aspectRatio(969f / 667f).clip(RoundedCornerShape(12.dp))) {
            Image(painterResource(R.drawable.welcome_picnic), "Welcome", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (welcome) Text("Welcome", Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                .background(Color.White.copy(alpha = 0.65f), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 2.dp),
                fontFamily = FontFamily.Cursive, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Color(0xFFB0245B))
        }
        if (showThanks) AlertDialog(
            onDismissRequest = { showThanks = false },
            title = { Text("Thank You!") },
            text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                IndianFlag()
                Spacer(Modifier.height(4.dp))
                Text("जय हिंद", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9933))
                Text("धन्यवाद", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF138808))
                Text("@प्रकाश पवार Ver. १.०", fontSize = 14.sp, color = Teal)
                Spacer(Modifier.height(8.dp))
                Image(painterResource(R.drawable.prakash_id), "Developer photo", Modifier.size(84.dp, 108.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(8.dp))
                Text("Developed by Prakash Pawar", fontWeight = FontWeight.Bold, color = Teal)
                Text("Contact: 9892399306") } },
            confirmButton = { TextButton(onExit) { Text("Close App") } },
            dismissButton = { TextButton({ showThanks = false }) { Text("Cancel") } })
        if (showMembers) AlertDialog(
            onDismissRequest = { showMembers = false },
            title = { Text("Members (${s.members.size})") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (s.members.isEmpty()) Text("No members yet")
                s.members.forEachIndexed { i, m -> Text("${i + 1}. $m" + if (m == s.collector) "  (Collector)" else "", fontSize = 18.sp, modifier = Modifier.padding(vertical = 2.dp)) } } },
            confirmButton = { TextButton({ showMembers = false }) { Text("Close") } })
    }
}

@Composable
fun Drop(label: String, options: List<String>, sel: String, onSel: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(if (sel.isEmpty()) "Select $label" else "$label: $sel", maxLines = 1) }
        DropdownMenu(open, { open = false }) { options.forEach { DropdownMenuItem({ Text(it, fontSize = 16.sp) }, { onSel(it); open = false }) } }
    }
}

@Composable
fun MemberScreen(s: Store) {
    var name by remember { mutableStateOf("") }
    var del by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Members: ${s.members.size}   |   Collector: ${s.collector.ifEmpty { "not selected" }}", fontWeight = FontWeight.Bold, color = Teal, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("Member name") }, modifier = Modifier.weight(1f), singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp))
            Button({ val n = name.trim(); if (n.isNotEmpty() && n !in s.members) { s.members.add(n); s.save(); name = "" } }) { Text("Add") }
        }
        Drop("Money Collector", s.members, s.collector) { s.collector = it; s.save() }
        LazyColumn { itemsIndexed(s.members.toList()) { i, m ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. $m" + if (m == s.collector) "  (Collector)" else "", fontSize = 16.sp)
                Text("✕", Modifier.clickable { del = m }.padding(horizontal = 14.dp, vertical = 5.dp), color = Color(0xFFB3261E), fontSize = 16.sp)
            } } }
    }
    del?.let { m ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("Remove member") },
            text = { Text("Do you want to remove $m?") },
            confirmButton = { TextButton({ s.members.remove(m); if (s.collector == m) s.collector = ""; s.save(); del = null }) { Text("Remove") } },
            dismissButton = { TextButton({ del = null }) { Text("No") } })
    }
}

@Composable
fun CollScreen(s: Store) {
    var member by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }; var allDlg by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Money Collector: ${s.collector.ifEmpty { "not selected (set in Member Entry)" }}", fontWeight = FontWeight.Bold, color = Teal)
        Drop("Who gives (collector may give too)", s.members, member) { member = it }
        OutlinedTextField(amt, { amt = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button({
            val a = amt.toDoubleOrNull()
            if (member.isNotEmpty() && s.collector.isNotEmpty() && a != null && a > 0) { s.colls.add(Coll(member, s.collector, a, now())); s.save(); amt = "" }
        }, Modifier.fillMaxWidth()) { Text("Add Collection") }
        OutlinedButton({ val a = amt.toDoubleOrNull(); if (s.collector.isNotEmpty() && a != null && a > 0 && s.members.isNotEmpty()) allDlg = true },
            Modifier.fillMaxWidth()) { Text("Add same amount for ALL members") }
        if (s.collector.isNotEmpty()) Text("Collected ${money(s.collected)} − Expense ${money(s.collectorSpent)} → ${s.cashLabel()}: ${money(abs(s.collectorCash))}",
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (s.collectorCash < 0) Color(0xFFB3261E) else Teal)
        if (allDlg) AlertDialog(
            onDismissRequest = { allDlg = false },
            title = { Text("Common collection") },
            text = { Text("Add ${money(amt.toDoubleOrNull() ?: 0.0)} from each of ${s.members.size} members to ${s.collector}?") },
            confirmButton = { TextButton({ val a = amt.toDoubleOrNull() ?: 0.0; s.members.forEach { s.colls.add(Coll(it, s.collector, a, now())) }; s.save(); amt = ""; allDlg = false }) { Text("Add") } },
            dismissButton = { TextButton({ allDlg = false }) { Text("No") } })
        if (member.isNotEmpty()) {
            val given = s.colls.filter { it.member == member }.sumOf { it.amount }
            val recv = s.colls.filter { it.toWhom == member }.sumOf { it.amount }
            Column(Modifier.fillMaxWidth().background(Teal, RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text("$member gave (+ added): ${money(given)}", color = Color.White)
                Text("$member received (− subtracted): ${money(recv)}", color = Color.White)
                Text("Net: ${money(given - recv)}", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Difference: ${money(s.difference(member))}", color = Color.White, fontSize = 12.sp)
            }
        }
        s.colls.filter { member.isEmpty() || it.member == member || it.toWhom == member }.forEach { c ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${c.member} → ${c.toWhom}: ${money(c.amount)}", fontSize = 14.sp)
                    if (c.at.isNotEmpty()) Text("Entered: ${c.at}", fontSize = 10.sp, color = Color.Gray)
                }
                TextButton({ s.colls.remove(c); s.save() }) { Text("✕") } }
        }
        Text("Total collection (Collector: ${s.collector.ifEmpty { "-" }}): ${money(s.totalColl)}", fontWeight = FontWeight.Bold)
        ExportRow(s, "coll")
    }
}

@Composable
fun ExpScreen(s: Store) {
    val ctx = LocalContext.current
    var member by remember { mutableStateOf(s.collector) }; var date by remember { mutableStateOf(today()) }
    var cat by remember { mutableStateOf("") }; var mode by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Drop("Member", s.members, member) { member = it }
            OutlinedButton({
                val c = Calendar.getInstance()
                runCatching { SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).parse(date)?.let { c.time = it } }
                android.app.DatePickerDialog(ctx, { _, yy, mm, dd -> date = String.format(Locale.getDefault(), "%02d-%02d-%04d", dd, mm + 1, yy) },
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }, Modifier.fillMaxWidth()) { Text("Purchase date: $date  (tap to change)") }
            Drop("Category", CATEGORIES, cat) { cat = it }
            Drop("Payment", MODES, mode) { mode = it }
            OutlinedTextField(desc, { desc = it }, label = { Text("Description / Details") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amt, { amt = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button({
                val a = amt.toDoubleOrNull()
                if (member.isNotEmpty() && cat.isNotEmpty() && a != null) {
                    s.exps.add(Exp(member, date, cat, mode.ifEmpty { "Cash" }, desc.trim(), a, now())); s.save(); amt = ""; desc = ""
                }
            }, Modifier.fillMaxWidth()) { Text("Add Expense") }
            ExportRow(s, "exp")
            s.exps.filter { member.isEmpty() || it.member == member }.takeLast(15).reversed().forEach { e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${e.date} • ${e.category.take(18)} • ${e.mode} • ${money(e.amount)}", fontSize = 12.sp)
                        if (e.updated.isNotEmpty()) Text("Updated: ${e.updated}", fontSize = 10.sp, color = Color.Gray)
                    }
                    TextButton({ s.exps.remove(e); s.save() }) { Text("✕") } }
            }
        }
        Text("Expense of ${member.ifEmpty { "all members" }}: ${money(s.exps.filter { member.isEmpty() || it.member == member }.sumOf { it.amount })}",
            Modifier.fillMaxWidth().background(Teal, RoundedCornerShape(8.dp)).padding(10.dp), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ReportScreen(s: Store) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(s.title.ifBlank { "Contribution Information" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Teal)
        Text("Statement (date wise)", fontWeight = FontWeight.Bold, color = Teal)
        Row(Modifier.fillMaxWidth().background(Teal).padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text("Particular", Modifier.weight(2.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White); Text("Collection", Modifier.weight(1.3f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Expense", Modifier.weight(1.3f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White); Text("Balance", Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        if (dates(s).isEmpty()) Text("No entries yet", fontSize = 13.sp)
        var k = 0
        statement(s).forEach { r ->
            if (r.isDate) Text(r.date, Modifier.fillMaxWidth().background(Color(0xFFD6EAF3)).padding(horizontal = 4.dp, vertical = 2.dp), fontWeight = FontWeight.Bold, color = Teal, fontSize = 12.sp)
            else { k++
                Row(Modifier.fillMaxWidth().background(if (k % 2 == 0) Color(0xFFEAF3F8) else Color.White).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Text(r.part.replace("->", "→"), Modifier.weight(2.6f), fontSize = 11.sp)
                    Text(if (r.coll != 0.0) money(r.coll) else "", Modifier.weight(1.3f), fontSize = 11.sp)
                    Text(if (r.exp != 0.0) money(r.exp) else "", Modifier.weight(1.3f), fontSize = 11.sp)
                    Amt(r.bal, Modifier.weight(1.4f))
                } }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFFFFE9B3)).padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text("Total", Modifier.weight(2.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(money(s.totalColl), Modifier.weight(1.3f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(money(s.totalExp), Modifier.weight(1.3f), fontSize = 11.sp, fontWeight = FontWeight.Bold); Amt(s.totalColl - s.totalExp, Modifier.weight(1.4f))
        }
        Text("Category-wise tally", Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth()) { Text("Category", Modifier.weight(3f), fontWeight = FontWeight.Bold); Text("Qty", Modifier.weight(1f), fontWeight = FontWeight.Bold); Text("Total", Modifier.weight(1.5f), fontWeight = FontWeight.Bold) }
        CATEGORIES.forEach { c ->
            val l = s.exps.filter { it.category == c }
            Row(Modifier.fillMaxWidth()) { Text(c, Modifier.weight(3f), fontSize = 13.sp); Text("${l.size}", Modifier.weight(1f)); Text(money(l.sumOf { it.amount }), Modifier.weight(1.5f), fontSize = 13.sp) }
        }
        HorizontalDivider()
        Text("Total Expenses: ${money(s.totalExp)}", fontWeight = FontWeight.Bold)
        Text("Total Collection: ${money(s.totalColl)}", fontWeight = FontWeight.Bold)
        Text("Difference: ${money(s.totalColl - s.totalExp)}", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        ExportRow(s)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        BackupRow(s)
    }
}

@Composable
fun SettleScreen(s: Store) {
    val sets = s.settlements()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Total members: ${s.members.size}", fontWeight = FontWeight.Bold)
        Text("Total expense: ${money(s.totalExp)}")
        Text("Equal share per person (rounded up): ${money(s.share)}", fontWeight = FontWeight.Bold, color = Teal)
        if (s.members.isNotEmpty() && abs(s.shareOf(s.absorberName) - s.share) > 0.004)
            Text("${s.absorberName} (collector) share incl. rounding difference: ${money(s.shareOf(s.absorberName))}", fontSize = 12.sp)
        Text("Collector: ${s.collector.ifEmpty { "-" }}   Collected: ${money(s.collected)}")
        Text("Expense paid by collector: ${money(s.collectorSpent)}")
        Text("${s.cashLabel()}: ${money(abs(s.collectorCash))}", fontWeight = FontWeight.Bold, color = if (s.collectorCash < 0) Color(0xFFB3261E) else Teal)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("Member", Modifier.weight(1.8f), fontWeight = FontWeight.Bold, fontSize = 12.sp); Text("Paid", Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Difference", Modifier.weight(1.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp); Text("Balance", Modifier.weight(1.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        s.members.forEach { m ->
            Row(Modifier.fillMaxWidth()) {
                Text(m, Modifier.weight(1.8f), fontSize = 12.sp)
                Amt(s.paid(m), Modifier.weight(1.3f)); Amt(s.difference(m), Modifier.weight(1.5f)); Amt(s.stmtBalance(m), Modifier.weight(1.5f))
            }
        }
        Text("Paid = expense by member", fontSize = 11.sp)
        Text("Difference = contributed + expense (collector: money received is not counted)", fontSize = 11.sp)
        Text("Balance = equal share − difference (minus = to receive)", fontSize = 11.sp)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Who gives whom", fontWeight = FontWeight.Bold, color = Teal)
        if (sets.isEmpty()) Text("All settled")
        sets.forEach { Text("${it.first}  →  ${it.second}:  ${money(it.third)}") }
        Spacer(Modifier.height(8.dp))
        ExportRow(s, "settle")
    }
}

@Composable
fun Amt(v: Double, mod: Modifier) =
    Text(money(v), mod, fontSize = 12.sp, color = if (v < -0.004) Color(0xFFB3261E) else Color.Unspecified)

@Composable
fun BackupRow(s: Store) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val exp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u ->
        u?.let { write(ctx, it, s.backupCsv()); toast(ctx, "Backup saved") } }
    val imp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> pending = u }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Backup / transfer to another phone (CSV)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ exp.launch(backupName(s.title)) }, Modifier.weight(1f)) { Text("Export CSV") }
            OutlinedButton({ imp.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Text("Import CSV") }
        }
    }
    pending?.let { u ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Import CSV") },
            text = { Text("Importing replaces all current data in this app. Continue?") },
            confirmButton = { TextButton({
                runCatching {
                    val txt = ctx.contentResolver.openInputStream(u)!!.bufferedReader().readText()
                    toast(ctx, if (s.importCsv(txt)) "Imported" else "Not a valid backup file")
                }.onFailure { toast(ctx, "Import failed") }
                pending = null }) { Text("Import") } },
            dismissButton = { TextButton({ pending = null }) { Text("No") } })
    }
}

@Composable
fun IndianFlag() {
    androidx.compose.foundation.Canvas(Modifier.size(72.dp, 48.dp)) {
        val h = size.height / 3
        val w = size.width
        drawRect(Color(0xFFFF9933), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, h))
        drawRect(Color.White, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Size(w, h))
        drawRect(Color(0xFF138808), androidx.compose.ui.geometry.Offset(0f, 2 * h), androidx.compose.ui.geometry.Size(w, h))
        val c = center; val r = h * 0.42f; val navy = Color(0xFF000080)
        drawCircle(navy, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        for (i in 0 until 24) {
            val a = Math.toRadians(i * 15.0)
            drawLine(navy, c, androidx.compose.ui.geometry.Offset(c.x + r * Math.cos(a).toFloat(), c.y + r * Math.sin(a).toFloat()), 1f)
        }
    }
}
