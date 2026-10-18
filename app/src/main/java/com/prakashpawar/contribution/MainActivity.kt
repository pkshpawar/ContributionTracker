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
    fun net(m: String): Double {
        val given = colls.filter { it.member == m && isMember(it.toWhom) && !it.toWhom.trim().equals(m, true) }.sumOf { it.amount }
        val recv = colls.filter { it.toWhom.trim().equals(m, true) && it.member != m }.sumOf { it.amount }
        return paid(m) + given - recv - share
    }
    // Paid = member's expense; Difference = contributed + expense; Balance = equal share - Difference
    fun contributed(m: String) = colls.filter { it.member == m }.sumOf { it.amount }
    fun difference(m: String) = contributed(m) + paid(m)
    fun stmtBalance(m: String) = share - difference(m)
    fun settlements(): List<Triple<String, String, Double>> {
        val deb = members.associateWith { -net(it) }.filter { it.value > 0.005 }.toMutableMap()
        val cre = members.associateWith { net(it) }.filter { it.value > 0.005 }.toMutableMap()
        val out = mutableListOf<Triple<String, String, Double>>()
        while (deb.isNotEmpty() && cre.isNotEmpty()) {
            val d = deb.maxByOrNull { it.value }!!; val c = cre.maxByOrNull { it.value }!!
            val a = minOf(d.value, c.value)
            out.add(Triple(d.key, c.key, up(a)))
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

fun csvq(t: String) = "\"" + t.replace("\"", "\"\"") + "\""
const val FOOTER = "Thank you! Developed by Prakash Pawar,Contact: 9892399306"
fun dkey(d: String) = if (d.length >= 10) d.substring(6, 10) + d.substring(3, 5) + d.substring(0, 2) else "0"
fun cDate(c: Coll) = c.at.take(10).ifEmpty { "Unknown" }
fun eDate(e: Exp) = e.date.ifEmpty { "Unknown" }
fun dates(s: Store) = (s.colls.map { cDate(it) } + s.exps.map { eDate(it) }).distinct().sortedBy { dkey(it) }

fun csv(s: Store): String {
    val b = StringBuilder()
    b.appendLine(csvq("Contribution For: " + s.title))
    b.appendLine(); b.appendLine("Category,Entries,Total")
    CATEGORIES.forEach { c -> val l = s.exps.filter { it.category == c }; b.appendLine("${csvq(c)},${l.size},${l.sumOf { it.amount }}") }
    b.appendLine("Total Expenses,,${s.totalExp}"); b.appendLine("Total Collection,,${s.totalColl}")
    b.appendLine("Total Members,,${s.members.size}"); b.appendLine("Equal Share Per Person,,${s.share}")
    b.appendLine("Collector,,${csvq(s.collector)}"); b.appendLine("Collected,,${s.collected}"); b.appendLine("Expense Paid By Collector,,${s.collectorSpent}"); b.appendLine("${s.cashLabel()},,${abs(s.collectorCash)}")
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
    b.appendLine("Total Members,${s.members.size}"); b.appendLine("Total Expense,${s.totalExp}"); b.appendLine("Equal Share Per Person,${s.share}")
    b.appendLine("Collector,${csvq(s.collector)}"); b.appendLine("Collected,${s.collected}"); b.appendLine("Expense Paid By Collector,${s.collectorSpent}"); b.appendLine("${s.cashLabel()},${abs(s.collectorCash)}")
    b.appendLine(); b.appendLine("Member,Paid,Difference,Balance")
    s.members.forEach { b.appendLine("${csvq(it)},${s.paid(it)},${s.difference(it)},${s.stmtBalance(it)}") }
    b.appendLine(); b.appendLine("Who Gives,To Whom,Amount")
    s.settlements().forEach { b.appendLine("${csvq(it.first)},${csvq(it.second)},${it.third}") }
    b.appendLine(); b.appendLine(FOOTER)
    return b.toString()
}

// mode: "full" | "settle" | "coll" | "exp"
fun writePdf(ctx: Context, uri: Uri, s: Store, mode: String = "full") {
    val doc = PdfDocument(); var n = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); var y = 50f
    val p = Paint()
    fun row(cols: List<String>, size: Float = 11f, bold: Boolean = false, cx: List<Float>? = null, lim: List<Int>? = null) {
        if (y > 790) { doc.finishPage(page); n++; page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); y = 50f }
        p.textSize = size; p.isFakeBoldText = bold
        val xs = cx ?: if (cols.size == 4) listOf(40f, 220f, 330f, 440f) else listOf(40f, 320f, 430f)
        xs.forEachIndexed { i, x -> cols.getOrNull(i)?.let { c ->
            p.color = if (c.startsWith("-₹")) android.graphics.Color.RED else android.graphics.Color.BLACK
            page.canvas.drawText(c.take(lim?.getOrNull(i) ?: if (i == 0) (if (cols.size == 4) 26 else 48) else 16), x, y, p) } }
        p.color = android.graphics.Color.BLACK
        y += size + 8
    }
    fun expHeader() = row(listOf("Purchase date", "Member", "Category", "Mode", "Amount", "Updated on"), 10f, true, EXP_X, EXP_L)
    fun expRow(e: Exp) = row(listOf(e.date, e.member, e.category, e.mode, money(e.amount), e.updated), 10f, false, EXP_X, EXP_L)
    fun memberTable() {
        row(listOf("Member", "Paid", "Difference", "Balance"), bold = true)
        s.members.forEach { row(listOf(it, money(s.paid(it)), money(s.difference(it)), money(s.stmtBalance(it)))) }
        row(listOf("Paid = expense by member")); row(listOf("Difference = contributed + expense")); row(listOf("Balance = equal share - difference"))
    }
    fun whoGives() {
        y += 8; row(listOf("Who gives -> To whom", "", "Amount"), bold = true)
        s.settlements().forEach { row(listOf("${it.first} -> ${it.second}", "", money(it.third))) }
        if (s.settlements().isEmpty()) row(listOf("All settled"))
    }
    fun collectorRows() {
        row(listOf("Collector: ${s.collector.ifEmpty { "-" }}   Collected: ${money(s.collected)}"), bold = true)
        row(listOf("Expense by collector: ${money(s.collectorSpent)}"), bold = true)
        row(listOf("${s.cashLabel()}: ${money(abs(s.collectorCash))}"), bold = true)
    }
    row(listOf(s.title.ifBlank { "Contribution Information" }), 18f, true)
    when (mode) { "settle" -> "Members & Settlement Report"; "coll" -> "Collection Report"; "exp" -> "Expense Report"; else -> "" }
        .takeIf { it.isNotEmpty() }?.let { row(listOf(it), 13f, true) }
    row(listOf("Report generated: " + now())); y += 8

    when (mode) {
        "settle" -> {
            row(listOf("Total Members: ${s.members.size}"), bold = true)
            row(listOf("Total Expense: ${money(s.totalExp)}"), bold = true)
            row(listOf("Equal share per person: ${money(s.share)}"), bold = true)
            collectorRows(); y += 8
            memberTable(); whoGives()
        }
        "coll" -> {
            val cx = listOf(40f, 140f, 260f, 380f); val lim = listOf(16, 16, 16, 14)
            row(listOf("Collector: ${s.collector.ifEmpty { "-" }}"), bold = true); y += 4
            row(listOf("Entered on", "Who gave", "To whom", "Amount"), 10f, true, cx, lim)
            s.colls.sortedWith(compareBy({ dkey(cDate(it)) }, { it.at.drop(11) })).forEach { row(listOf(it.at.ifEmpty { "-" }, it.member, it.toWhom, money(it.amount)), 10f, false, cx, lim) }
            y += 4; row(listOf("Total collection", "", "", money(s.totalColl)), 10f, true, cx, lim)
        }
        "exp" -> {
            expHeader()
            s.exps.sortedWith(compareBy({ dkey(eDate(it)) }, { it.updated.drop(11) })).forEach { expRow(it) }
            y += 4; row(listOf("Total", "", "", "", money(s.totalExp), ""), 10f, true, EXP_X, EXP_L)
        }
        else -> {
            row(listOf("Datewise collection & expense"), 13f, true)
            dates(s).forEach { d ->
                val cl = s.colls.filter { cDate(it) == d }; val ex = s.exps.filter { eDate(it) == d }
                y += 4; row(listOf(d), 12f, true)
                cl.forEach { row(listOf("Collection: ${it.member} -> ${it.toWhom}", it.at, money(it.amount))) }
                if (cl.isNotEmpty()) row(listOf("Collection total", "", money(cl.sumOf { it.amount })), bold = true)
                if (ex.isNotEmpty()) { expHeader(); ex.forEach { expRow(it) }; row(listOf("Total", "", "", "", money(ex.sumOf { it.amount }), ""), 10f, true, EXP_X, EXP_L) }
            }
            y += 8; row(listOf("Category", "Entries", "Total"), bold = true)
            CATEGORIES.forEach { c -> val l = s.exps.filter { it.category == c }; row(listOf(c, "${l.size}", money(l.sumOf { it.amount }))) }
            row(listOf("Total Expenses", "", money(s.totalExp)), bold = true)
            row(listOf("Total Collection", "", money(s.totalColl)), bold = true)
            row(listOf("Difference", "", money(s.totalColl - s.totalExp)), bold = true)
            row(listOf("Total Members: ${s.members.size}"), bold = true)
            row(listOf("Equal share per person: ${money(s.share)}"), bold = true)
            collectorRows(); y += 8
            memberTable(); whoGives()
        }
    }
    y += 10; row(listOf("Thank you! Developed by Prakash Pawar"), bold = true); row(listOf("Contact: 9892399306"))
    doc.finishPage(page)
    ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
    doc.close()
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
        Button({ xls.launch("$name.csv") }, Modifier.weight(1f)) { Text("Export Excel") }
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
            Text("@Prakash Pawar Ver 1.0", Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 1.dp), color = Color.White, fontSize = 10.sp)
        }
        if (showThanks) AlertDialog(
            onDismissRequest = { showThanks = false },
            title = { Text("Thank You!") },
            text = { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.prakash_id), "Developer photo", Modifier.size(84.dp, 108.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(8.dp))
                Text("Thank you for using Contribution Tracker.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Total members: ${s.members.size}", fontWeight = FontWeight.Bold, color = Teal)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("Member name") }, modifier = Modifier.weight(1f), singleLine = true)
            Button({ val n = name.trim(); if (n.isNotEmpty() && n !in s.members) { s.members.add(n); s.save(); name = "" } }) { Text("Add") }
        }
        Drop("Money Collector", s.members, s.collector) { s.collector = it; s.save() }
        Text(if (s.collector.isEmpty()) "Select one collector (can be yourself)" else "Collector: ${s.collector}", fontSize = 12.sp)
        LazyColumn { items(s.members.toList()) { m ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(m + if (m == s.collector) "  (Collector)" else "", fontSize = 18.sp)
                TextButton({ del = m }) { Text("Delete") } } } }
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
        Text("Datewise collection & expense", fontWeight = FontWeight.Bold)
        if (dates(s).isEmpty()) Text("No entries yet", fontSize = 13.sp)
        dates(s).forEach { d ->
            val cl = s.colls.filter { cDate(it) == d }; val ex = s.exps.filter { eDate(it) == d }
            Text(d, Modifier.padding(top = 6.dp), fontWeight = FontWeight.Bold, color = Teal)
            cl.forEach { Text("+ ${it.member} → ${it.toWhom}: ${money(it.amount)}", fontSize = 13.sp) }
            if (cl.isNotEmpty()) Text("Collection total: ${money(cl.sumOf { it.amount })}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            ex.forEach { Text("− ${it.member} • ${it.category.take(22)} • ${it.mode}: ${money(it.amount)}", fontSize = 13.sp) }
            if (ex.isNotEmpty()) Text("Expense total: ${money(ex.sumOf { it.amount })}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            HorizontalDivider()
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
    }
}

@Composable
fun SettleScreen(s: Store) {
    val sets = s.settlements()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Total members: ${s.members.size}", fontWeight = FontWeight.Bold)
        Text("Total expense: ${money(s.totalExp)}")
        Text("Equal share per person (rounded up): ${money(s.share)}", fontWeight = FontWeight.Bold, color = Teal)
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
