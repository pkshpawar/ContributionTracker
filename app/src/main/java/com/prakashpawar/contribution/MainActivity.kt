package com.prakashpawar.contribution

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
    val members = mutableStateListOf<String>()
    val colls = mutableStateListOf<Coll>()
    val exps = mutableStateListOf<Exp>()
    val totalColl get() = colls.sumOf { it.amount }
    val totalExp get() = exps.sumOf { it.amount }

    init {
        val s = ctx.getSharedPreferences("data", Context.MODE_PRIVATE).getString("json", null)
        if (s != null) runCatching { fromJson(JSONObject(s)) }
    }

    fun save() = ctx.getSharedPreferences("data", Context.MODE_PRIVATE).edit().putString("json", toJson().toString()).apply()

    fun toJson() = JSONObject().apply {
        put("title", title)
        put("members", JSONArray(members))
        put("collections", JSONArray().also { a -> colls.forEach { a.put(JSONObject().put("member", it.member).put("toWhom", it.toWhom).put("amount", it.amount)) } })
        put("expenses", JSONArray().also { a -> exps.forEach { a.put(JSONObject().put("member", it.member).put("date", it.date).put("category", it.category).put("mode", it.mode).put("desc", it.desc).put("amount", it.amount)) } })
    }

    fun fromJson(j: JSONObject) {
        title = j.optString("title"); members.clear(); colls.clear(); exps.clear()
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
    b.appendLine(); b.appendLine("Collections"); b.appendLine("Member,To Whom,Amount")
    s.colls.forEach { b.appendLine("${q(it.member)},${q(it.toWhom)},${it.amount}") }
    b.appendLine("Total Collection,,${s.totalColl}")
    b.appendLine(); b.appendLine("Expenses"); b.appendLine("Member,Date,Category,Mode,Description,Amount")
    s.exps.forEach { b.appendLine("${q(it.member)},${it.date},${q(it.category)},${it.mode},${q(it.desc)},${it.amount}") }
    b.appendLine(); b.appendLine("Difference (Collection - Expense),,${s.totalColl - s.totalExp}")
    return b.toString()
}

fun writePdf(ctx: Context, uri: Uri, s: Store) {
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
    row(listOf("Category", "Entries", "Total"), bold = true)
    CATEGORIES.forEach { c -> val l = s.exps.filter { it.category == c }; row(listOf(c, "${l.size}", money(l.sumOf { it.amount }))) }
    row(listOf("Total Expenses", "", money(s.totalExp)), bold = true); y += 8
    row(listOf("Total Collection", "", money(s.totalColl)), bold = true)
    row(listOf("Difference", "", money(s.totalColl - s.totalExp)), bold = true); y += 8
    row(listOf("Collection by member", "To whom", "Amount"), bold = true)
    s.colls.forEach { row(listOf(it.member, it.toWhom, money(it.amount))) }
    doc.finishPage(page)
    ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
    doc.close()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Teal)) { App(store) { finishAffinity() } } }
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
            }
        }
        Text(APP_VERSION, Modifier.fillMaxWidth().background(Teal).padding(6.dp).navigationBarsPadding(),
            color = Color.White, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun Scaffold1(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Teal).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("← Back", color = Color.White) }
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
fun StatCard(label: String, value: String, mod: Modifier) {
    Card(mod, colors = CardDefaults.cardColors(containerColor = Teal), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White, fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MainPage(s: Store, go: (String) -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        u?.let { write(ctx, it, s.toJson().toString(2)); toast(ctx, "Backup saved") } }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { runCatching { s.fromJson(JSONObject(ctx.contentResolver.openInputStream(it)!!.bufferedReader().readText())); toast(ctx, "Imported") }.onFailure { toast(ctx, "Invalid file") } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Contribution Manager", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Teal)
        OutlinedTextField(s.title, { s.title = it; s.save() }, label = { Text("Contribution For") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Total Collection", money(s.totalColl), Modifier.weight(1f))
            StatCard("Total Expenses", money(s.totalExp), Modifier.weight(1f))
            StatCard("Difference", money(s.totalColl - s.totalExp), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ go("member") }, Modifier.weight(1f)) { Text("Member Entry") }
            Button({ go("coll") }, Modifier.weight(1f)) { Text("Collection") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ go("exp") }, Modifier.weight(1f)) { Text("Expenses") }
            Button({ go("report") }, Modifier.weight(1f)) { Text("Report") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ exportJson.launch("contribution_backup.json") }, Modifier.weight(1f)) { Text("Export & Save") }
            OutlinedButton({ importJson.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Text("Import") }
        }
        Button(onExit, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("Exit") }
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("Member name") }, modifier = Modifier.weight(1f), singleLine = true)
            Button({ val n = name.trim(); if (n.isNotEmpty() && n !in s.members) { s.members.add(n); s.save(); name = "" } }) { Text("Add") }
        }
        LazyColumn { items(s.members.toList()) { m ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(m); TextButton({ s.members.remove(m); s.save() }) { Text("Delete") } } } }
    }
}

@Composable
fun CollScreen(s: Store) {
    var member by remember { mutableStateOf("") }; var to by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Drop("Member", s.members, member) { member = it }
        OutlinedTextField(to, { to = it }, label = { Text("To whom") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(amt, { amt = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button({ val a = amt.toDoubleOrNull(); if (member.isNotEmpty() && a != null) { s.colls.add(Coll(member, to.trim(), a)); s.save(); amt = "" } }, Modifier.fillMaxWidth()) { Text("Add Collection") }
        val list = s.colls.filter { member.isEmpty() || it.member == member }
        LazyColumn(Modifier.weight(1f, false).heightIn(max = 380.dp)) { items(list) { c ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${c.member} → ${c.toWhom}: ${money(c.amount)}", Modifier.weight(1f), fontSize = 14.sp)
                TextButton({ s.colls.remove(c); s.save() }) { Text("✕") } } } }
        Text("Total (${member.ifEmpty { "all" }}): ${money(list.sumOf { it.amount })}", fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ExpScreen(s: Store) {
    var member by remember { mutableStateOf("") }; var date by remember { mutableStateOf(today()) }
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
    val ctx = LocalContext.current
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        u?.let { runCatching { writePdf(ctx, it, s); toast(ctx, "PDF saved") }.onFailure { toast(ctx, "PDF failed") } } }
    val xls = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u ->
        u?.let { write(ctx, it, csv(s)); toast(ctx, "Excel (CSV) saved") } }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ pdf.launch("contribution_report.pdf") }, Modifier.weight(1f)) { Text("Export PDF") }
            Button({ xls.launch("contribution_report.csv") }, Modifier.weight(1f)) { Text("Export Excel") }
        }
    }
}
