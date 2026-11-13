package com.osintdesk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.net.URLEncoder

enum class Pivot(val label: String, val hint: String) {
    DOMAIN("Домен", "example.com"),
    USERNAME("Ник", "jdoe_42"),
    EMAIL("Email", "name@example.com"),
    PERSON("Человек", "Jane Doe"),
    COMPANY("Компания", "Acme Ltd"),
    IP("IP", "203.0.113.10"),
}

data class Source(val name: String, val group: String, val pivots: Set<Pivot>, val url: (String) -> String)

data class Finding(val id: Long, val title: String, val url: String, val confidence: String, val note: String)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun dorks(p: Pivot, v: String): List<String> = when (p) {
    Pivot.DOMAIN -> listOf("site:$v", "site:$v filetype:pdf", "site:$v intitle:\"index of\"", "site:github.com \"$v\" password")
    Pivot.USERNAME -> listOf("\"$v\"", "\"$v\" site:github.com", "\"$v\" site:reddit.com")
    Pivot.EMAIL -> listOf("\"$v\"", "\"$v\" filetype:pdf", "\"$v\" site:github.com")
    Pivot.PERSON -> listOf("\"$v\"", "\"$v\" site:linkedin.com/in", "\"$v\" filetype:pdf")
    Pivot.COMPANY -> listOf("\"$v\" site:linkedin.com/company", "\"$v\" filetype:pdf \"annual report\"", "\"$v\" site:crunchbase.com")
    Pivot.IP -> listOf("\"$v\"", "\"$v\" site:shodan.io", "\"$v\" site:abuseipdb.com")
}

private val sources = listOf(
    Source("Have I Been Pwned: проверка email", "Утечки", setOf(Pivot.EMAIL)) { "https://haveibeenpwned.com/account/${enc(it)}" },
    Source("Have I Been Pwned: домен", "Утечки", setOf(Pivot.DOMAIN)) { "https://haveibeenpwned.com/DomainSearch" },
    Source("Intelligence X", "Утечки", setOf(Pivot.DOMAIN, Pivot.EMAIL, Pivot.USERNAME, Pivot.IP)) { "https://intelx.io/?s=${enc(it)}" },
    Source("crt.sh сертификаты", "Инфраструктура", setOf(Pivot.DOMAIN)) { "https://crt.sh/?q=${enc(it)}" },
    Source("Wayback Machine", "Инфраструктура", setOf(Pivot.DOMAIN)) { "https://web.archive.org/web/*/$it" },
    Source("WHOIS", "Инфраструктура", setOf(Pivot.DOMAIN)) { "https://who.is/whois/$it" },
    Source("Shodan", "Инфраструктура", setOf(Pivot.IP)) { "https://www.shodan.io/host/${enc(it)}" },
    Source("AbuseIPDB", "Инфраструктура", setOf(Pivot.IP)) { "https://www.abuseipdb.com/check/${enc(it)}" },
    Source("GitHub", "Аккаунты", setOf(Pivot.USERNAME)) { "https://github.com/${enc(it)}" },
    Source("Reddit", "Аккаунты", setOf(Pivot.USERNAME)) { "https://www.reddit.com/user/${enc(it)}" },
    Source("OpenCorporates", "Компании", setOf(Pivot.COMPANY, Pivot.PERSON)) { "https://opencorporates.com/companies?q=${enc(it)}" },
    Source("SEC EDGAR", "Компании", setOf(Pivot.COMPANY)) { "https://www.sec.gov/cgi-bin/browse-edgar?company=${enc(it)}&action=getcompany" },
    Source("Companies House UK", "Компании", setOf(Pivot.COMPANY)) { "https://find-and-update.company-information.service.gov.uk/search?q=${enc(it)}" },
    Source("Google Scholar", "Люди", setOf(Pivot.PERSON)) { "https://scholar.google.com/scholar?q=${enc(it)}" },
    Source("Google News", "Люди", setOf(Pivot.PERSON, Pivot.COMPANY)) { "https://news.google.com/search?q=${enc(it)}" },
)

private fun confidenceLabel(key: String) = when (key) {
    "confirmed" -> "Подтверждено"
    "unverified" -> "Не проверено"
    else -> "Вероятно"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { DeskScreen() }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun DeskScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pivot by remember { mutableStateOf(Pivot.DOMAIN) }
    var target by remember { mutableStateOf("") }
    val findings = remember { mutableStateListOf<Finding>() }
    var title by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confidence by remember { mutableStateOf("likely") }
    val value = target.trim()

    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("OSINT Desk", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Только публичные источники", color = MaterialTheme.colorScheme.primary)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pivot.entries.forEach { p ->
                FilterChip(selected = pivot == p, onClick = { pivot = p }, label = { Text(p.label) })
            }
        }

        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text(pivot.hint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("Запросы")
        if (value.isEmpty()) Text("Введи цель, и появятся запросы.")
        if (value.isNotEmpty()) {
            dorks(pivot, value).forEach { q ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(q, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(q)) }) { Text("Копировать") }
                            TextButton(onClick = { open("https://www.google.com/search?q=${enc(q)}") }) { Text("Искать") }
                        }
                    }
                }
            }
        }

        SectionTitle("Источники")
        if (value.isNotEmpty()) {
            sources.filter { pivot in it.pivots }.groupBy { it.group }.forEach { (group, items) ->
                Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                items.forEach { s ->
                    OutlinedButton(onClick = { open(s.url(value)) }, modifier = Modifier.fillMaxWidth()) { Text(s.name) }
                }
            }
        }

        SectionTitle("Находки")
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Что нашел") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = link, onValueChange = { link = it }, label = { Text("Ссылка на источник") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Заметки") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("confirmed", "likely", "unverified").forEach { key ->
                FilterChip(selected = confidence == key, onClick = { confidence = key }, label = { Text(confidenceLabel(key)) })
            }
        }
        Button(onClick = {
            if (title.isNotBlank()) {
                findings.add(0, Finding(System.currentTimeMillis(), title.trim(), link.trim(), confidence, note.trim()))
                title = ""; link = ""; note = ""
            }
        }) { Text("Записать") }

        findings.forEach { f ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.title, fontWeight = FontWeight.SemiBold)
                    if (f.url.isNotEmpty()) Text(f.url, style = MaterialTheme.typography.bodySmall)
                    if (f.note.isNotEmpty()) Text(f.note)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(confidenceLabel(f.confidence), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { findings.remove(f) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
}
