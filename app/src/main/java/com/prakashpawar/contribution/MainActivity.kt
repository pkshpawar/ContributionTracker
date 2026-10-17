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
import androidx.compose.foundation.background
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

data class Coll(val member: String, val toWhom: String, val amount: Double)
data class Exp(val member: String, val date: String, val category: String, val mode: String, val desc: String, val amount: Double)

class Store(private val ctx: Context) {
    var title by mutableStateOf("")
    var collector by mutableStateOf("")
    val members = mutableStateListOf<String>()
    val colls = mutableStateListOf<Coll>()
    val exps = mutableStateListOf<Exp>()
    val totalColl get() = colls.sumOf { it.amount }
    val totalExp get() = exps.sumOf { it.amount }
    val share get() = if (members.isEmpty()) 0.0 else totalExp / members.size
    fun paid(m: String) = exps.filter { it.member == m }.sumOf { it.amount }
    private fun isMember(n: String) = members.any { it.equals(n.trim(), true) }
    // balance > 0: should receive, < 0: should pay. Collections between members adjust it.
    fun balance(m: String): Double {
        val given = colls.filter { it.member == m && isMember(it.toWhom) && !it.toWhom.trim().equals(m, true) }.sumOf { it.amount }
        val recv = colls.filter { it.toWhom.trim().equals(m, true) && it.member != m }.sumOf { it.amount }
        return paid(m) + given - recv - share
    }
    fun settlements(): List<Triple<String, String, Double>> {
        val deb = members.associateWith { -balance(it) }.filter { it.value > 0.005 }.toMutableMap()
        val cre = members.associateWith { balance(it) }.filter { it.value > 0.005 }.toMutableMap()
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

    fun toJson() = JSONObject().apply {
        put("title", title)
        put("collector", collector)
        put("members", JSONArray(members))
        put("collections", JSONArray().also { a -> colls.forEach { a.put(JSONObject().put("member", it.member).put("toWhom", it.toWhom).put("amount", it.amount)) } })
        put("expenses", JSONArray().also { a -> exps.forEach { a.put(JSONObject().put("member", it.member).put("date", it.date).put("category", it.category).put("mode", it.mode).put("desc", it.desc).put("amount", it.amount)) } })
    }

    fun fromJson(j: JSONObject) {
        title = j.optString("title"); collector = j.optString("collector"); members.clear(); colls.clear(); exps.clear()
        j.optJSONArray("members")?.let { for (i in 0 until it.length()) members.add(it.getString(i)) }
        j.optJSONArray("collections")?.let { for (i in 0 until it.length()) it.getJSONObject(i).let { o -> colls.add(Coll(o.getString("member"), o.getString("toWhom"), o.getDouble("amount"))) } }
        j.optJSONArray("expenses")?.let { for (i in 0 until it.length()) it.getJSONObject(i).let { o -> exps.add(Exp(o.getString("member"), o.getString("date"), o.getString("category"), o.getString("mode"), o.getString("desc"), o.getDouble("amount"))) } }
        save()
    }
}

fun today(): String = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
fun money(d: Double) = "₹" + String.format(Locale.getDefault(), "%.2f", d)
fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

fun write(ctx: Context, uri: Uri, text: String) =
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }

fun csv(s: Store): String {
    fun q(t: String) = "\"" + t.replace("\"", "\"\"") + "\""
    val b = StringBuilder()
    b.appendLine(q("Contribution For: " + s.title))
    b.appendLine(); b.appendLine("Category,Entries,Total")
    CATEGORIES.forEach { c -> val l = s.exps.filter { it.category == c }; b.appendLine("${q(c)},${l.size},${l.sumOf { it.amount }}") }
    b.appendLine("Total Expenses,,${s.totalExp}")
    b.appendLine("Total Members,,${s.members.size}"); b.appendLine("Equal Share Per Person,,${s.share}")
    b.appendLine(); b.appendLine("Collections"); b.appendLine("Who Gave,To Whom,Amount")
    s.colls.forEach { b.appendLine("${q(it.member)},${q(it.toWhom)},${it.amount}") }
    b.appendLine("Total Collection,,${s.totalColl}")
    b.appendLine(); b.appendLine("Expenses"); b.appendLine("Member,Date,Category,Mode,Description,Amount")
    s.exps.forEach { b.appendLine("${q(it.member)},${it.date},${q(it.category)},${it.mode},${q(it.desc)},${it.amount}") }
    b.appendLine(); b.appendLine("Difference (Collection - Expense),,${s.totalColl - s.totalExp}")
    b.appendLine(); b.appendLine("Member,Paid,Share,Balance")
    s.members.forEach { b.appendLine("${q(it)},${s.paid(it)},${s.share},${s.balance(it)}") }
    b.appendLine(); b.appendLine("Who Gives,To Whom,Amount")
    s.settlements().forEach { b.appendLine("${q(it.first)},${q(it.second)},${it.third}") }
    return b.toString()
}

fun csvSettle(s: Store): String {
    fun q(t: String) = "\"" + t.replace("\"", "\"\"") + "\""
    val b = StringBuilder()
    b.appendLine(q("Members Report: " + s.title))
    b.appendLine("Total Members,${s.members.size}"); b.appendLine("Total Expense,${s.totalExp}"); b.appendLine("Equal Share Per Person,${s.share}")
    b.appendLine(); b.appendLine("Member,Paid,Share,Balance")
    s.members.forEach { b.appendLine("${q(it)},${s.paid(it)},${s.share},${s.balance(it)}") }
    b.appendLine(); b.appendLine("Who Gives,To Whom,Amount")
    s.settlements().forEach { b.appendLine("${q(it.first)},${q(it.second)},${it.third}") }
    return b.toString()
}

fun writePdf(ctx: Context, uri: Uri, s: Store, settle: Boolean = false) {
    val doc = PdfDocument(); var n = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); var y = 50f
    val p = Paint()
    fun row(cols: List<String>, size: Float = 11f, bold: Boolean = false) {
        if (y > 790) { doc.finishPage(page); n++; page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); y = 50f }
        p.textSize = size; p.isFakeBoldText = bold
        listOf(40f, 340f, 430f).forEachIndexed { i, x -> cols.getOrNull(i)?.let { page.canvas.drawText(it.take(if (i == 0) 48 else 14), x, y, p) } }
        y += size + 8
    }
    row(listOf(s.title.ifBlank { "Contribution Information" }), 18f, true)
    row(listOf("Report generated: " + today())); y += 8
    if (settle) {
        row(listOf("Total Members: ${s.members.size}", "", ""), bold = true)
        row(listOf("Total Expense: ${money(s.totalExp)}", "", ""), bold = true)
        row(listOf("Equal share per person: ${money(s.share)}", "", ""), bold = true); y += 8
        row(listOf("Member", "Paid", "Balance"), bold = true)
        s.members.forEach { row(listOf(it, money(s.paid(it)), money(s.balance(it)))) }
        y += 8; row(listOf("Who gives -> To whom", "", "Amount"), bold = true)
        s.settlements().forEach { row(listOf("${it.first} -> ${it.second}", "", money(it.third))) }
        if (s.settlements().isEmpty()) row(listOf("All settled"))
        doc.finishPage(page)
        ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close(); return
    }
    row(listOf("Category", "Entries", "Total"), bold = true)
    CATEGORIES.forEach { c -> val l = s.exps.filter { it.category == c }; row(listOf(c, "${l.size}", money(l.sumOf { it.amount }))) }
    row(listOf("Total Expenses", "", money(s.totalExp)), bold = true); y += 8
    row(listOf("Total Collection", "", money(s.totalColl)), bold = true)
    row(listOf("Difference", "", money(s.totalColl - s.totalExp)), bold = true)
    row(listOf("Total Members: ${s.members.size}   Equal share per person: ${money(s.share)}"), bold = true); y += 8
    row(listOf("Collections (who gave -> to whom)", "", "Amount"), bold = true)
    s.colls.forEach { row(listOf("${it.member} -> ${it.toWhom}", "", money(it.amount))) }
    y += 8; row(listOf("Expense details (date, member, category)", "Mode", "Amount"), bold = true)
    s.exps.forEach { row(listOf("${it.date} ${it.member}: ${it.category}", it.mode, money(it.amount))) }
    y += 8; row(listOf("Member", "Paid", "Balance"), bold = true)
    s.members.forEach { row(listOf(it, money(s.paid(it)), money(s.balance(it)))) }
    y += 8; row(listOf("Who gives -> To whom", "", "Amount"), bold = true)
    s.settlements().forEach { row(listOf("${it.first} -> ${it.second}", "", money(it.third))) }
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
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (screen) {
                "main" -> MainPage(s, { screen = it }, onExit)
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
fun ExportRow(s: Store, settle: Boolean = false) {
    val ctx = LocalContext.current
    val name = if (settle) "members_settlement" else "contribution_report"
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        u?.let { runCatching { writePdf(ctx, it, s, settle); toast(ctx, "PDF saved") }.onFailure { toast(ctx, "PDF failed") } } }
    val xls = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u ->
        u?.let { write(ctx, it, if (settle) csvSettle(s) else csv(s)); toast(ctx, "Excel saved") } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ pdf.launch("$name.pdf") }, Modifier.weight(1f)) { Text("Export PDF") }
        Button({ xls.launch("$name.csv") }, Modifier.weight(1f)) { Text("Export Excel") }
    }
}

@Composable
fun StatCard(label: String, value: String, mod: Modifier, onClick: (() -> Unit)? = null) {
    Card(if (onClick != null) mod.clickable { onClick() } else mod, colors = CardDefaults.cardColors(containerColor = Teal), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White, fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MainPage(s: Store, go: (String) -> Unit, onExit: () -> Unit) {
    var showMembers by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Contribution Manager", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Teal)
        OutlinedTextField(s.title, { s.title = it; s.save() }, label = { Text("Contribution For") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Collection (${s.collector.ifEmpty { "No collector" }})", money(s.totalColl), Modifier.weight(1f))
            StatCard("Total Expenses", money(s.totalExp), Modifier.weight(1f))
            StatCard("Difference", money(s.totalColl - s.totalExp), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Members ▸ (tap)", "${s.members.size}", Modifier.weight(1f)) { showMembers = true }
            StatCard("Share / Person", money(s.share), Modifier.weight(2f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ go("member") }, Modifier.weight(1f)) { Text("Member Entry") }
            Button({ go("coll") }, Modifier.weight(1f)) { Text("Collection") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ go("exp") }, Modifier.weight(1f)) { Text("Expenses") }
            Button({ go("report") }, Modifier.weight(1f)) { Text("Report") }
        }
        Button({ go("settle") }, Modifier.fillMaxWidth()) { Text("Members List & Who Pays Whom") }
        Button(onExit, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("Exit") }
        if (showMembers) AlertDialog(
            onDismissRequest = { showMembers = false },
            title = { Text("Members (${s.members.size})") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (s.members.isEmpty()) Text("No members yet")
                s.members.forEachIndexed { i, m -> Text("${i + 1}. $m" + if (m == s.collector) "  (Collector)" else "") } } },
            confirmButton = { TextButton({ showMembers = false }) { Text("Close") } })
    }
}

@Composable
fun Drop(label: String, options: List<String>, sel: String, onSel: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(if (sel.isEmpty()) "Select $label" else "$label: $sel", maxLines = 1) }
        DropdownMenu(open, { open = false }) { options.forEach { DropdownMenuItem({ Text(it) }, { onSel(it); open = false }) } }
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
                Text(m + if (m == s.collector) "  (Collector)" else "")
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
    var member by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Money Collector: ${s.collector.ifEmpty { "not selected (set in Member Entry)" }}", fontWeight = FontWeight.Bold, color = Teal)
        Drop("Who gives (collector may give too)", s.members, member) { member = it }
        OutlinedTextField(amt, { amt = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button({
            val a = amt.toDoubleOrNull()
            if (member.isNotEmpty() && s.collector.isNotEmpty() && a != null && a > 0) { s.colls.add(Coll(member, s.collector, a)); s.save(); amt = "" }
        }, Modifier.fillMaxWidth()) { Text("Add Collection") }
        if (member.isNotEmpty()) {
            val given = s.colls.filter { it.member == member }.sumOf { it.amount }
            val recv = s.colls.filter { it.toWhom == member }.sumOf { it.amount }
            Column(Modifier.fillMaxWidth().background(Teal, RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text("$member gave (+ added): ${money(given)}", color = Color.White)
                Text("$member received (− subtracted): ${money(recv)}", color = Color.White)
                Text("Net: ${money(given - recv)}", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Balance after expense share: ${money(s.balance(member))}", color = Color.White, fontSize = 12.sp)
            }
        }
        s.colls.filter { member.isEmpty() || it.member == member || it.toWhom == member }.forEach { c ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${c.member} → ${c.toWhom}: ${money(c.amount)}", Modifier.weight(1f), fontSize = 14.sp)
                TextButton({ s.colls.remove(c); s.save() }) { Text("✕") } }
        }
        Text("Total collection (Collector: ${s.collector.ifEmpty { "-" }}): ${money(s.totalColl)}", fontWeight = FontWeight.Bold)
        ExportRow(s)
    }
}

@Composable
fun ExpScreen(s: Store) {
    var member by remember { mutableStateOf(s.collector) }; var date by remember { mutableStateOf(today()) }
    var cat by remember { mutableStateOf("") }; var mode by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Drop("Member", s.members, member) { member = it }
            OutlinedTextField(date, { date = it }, label = { Text("Date") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Drop("Category", CATEGORIES, cat) { cat = it }
            Drop("Payment", MODES, mode) { mode = it }
            OutlinedTextField(desc, { desc = it }, label = { Text("Description / Details") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amt, { amt = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button({
                val a = amt.toDoubleOrNull()
                if (member.isNotEmpty() && cat.isNotEmpty() && a != null) {
                    s.exps.add(Exp(member, date, cat, mode.ifEmpty { "Cash" }, desc.trim(), a)); s.save(); amt = ""; desc = ""
                }
            }, Modifier.fillMaxWidth()) { Text("Add Expense") }
            ExportRow(s)
            s.exps.filter { member.isEmpty() || it.member == member }.takeLast(15).reversed().forEach { e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.date} • ${e.category.take(18)} • ${e.mode} • ${money(e.amount)}", Modifier.weight(1f), fontSize = 12.sp)
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
        Text("Equal share per person: ${money(s.share)}", fontWeight = FontWeight.Bold, color = Teal)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) { Text("Member", Modifier.weight(2f), fontWeight = FontWeight.Bold); Text("Paid", Modifier.weight(1.5f), fontWeight = FontWeight.Bold); Text("Balance", Modifier.weight(1.5f), fontWeight = FontWeight.Bold) }
        s.members.forEach { m ->
            Row(Modifier.fillMaxWidth()) { Text(m, Modifier.weight(2f)); Text(money(s.paid(m)), Modifier.weight(1.5f), fontSize = 13.sp); Text(money(s.balance(m)), Modifier.weight(1.5f), fontSize = 13.sp) }
        }
        Text("Balance: + will receive, − will pay", fontSize = 11.sp)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Who gives whom", fontWeight = FontWeight.Bold, color = Teal)
        if (sets.isEmpty()) Text("All settled")
        sets.forEach { Text("${it.first}  →  ${it.second}:  ${money(it.third)}") }
        Spacer(Modifier.height(8.dp))
        ExportRow(s, true)
    }
}
