package com.darkbit.bypass

enum class LogLevel {
    INFO, SUCCESS, WARNING, ERROR
}

data class LogEntry(
    val time: String,
    val message: String,
    val level: LogLevel
)

object LogFormatter {

    private val appTimestampRegex = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s*(.*)""")
    private val xrayTimestampRegex = Regex("""^(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2})(?:\.\d+)?\s+(.*)""")

    private val skipPatterns = listOf(
        Regex("""logcat:""", RegexOption.IGNORE_CASE),
        Regex("""GoLog""", RegexOption.IGNORE_CASE),
        Regex("""nativeloader""", RegexOption.IGNORE_CASE),
        Regex("""accepted\s+(tcp|udp):""", RegexOption.IGNORE_CASE),
        Regex("""from\s+(tcp|udp):""", RegexOption.IGNORE_CASE),
        Regex("""\[LOGS CLEARED""", RegexOption.IGNORE_CASE),
        Regex("""\[Info\]""", RegexOption.IGNORE_CASE),
        Regex("""\[Debug\]""", RegexOption.IGNORE_CASE),
        Regex("""app/dispatcher""", RegexOption.IGNORE_CASE),
        Regex("""app/proxyman""", RegexOption.IGNORE_CASE),
        Regex("""app/router""", RegexOption.IGNORE_CASE),
        Regex("""transport/""", RegexOption.IGNORE_CASE),
        Regex("""proxy/""", RegexOption.IGNORE_CASE),
        Regex("""sniffed\s+domain""", RegexOption.IGNORE_CASE),
        Regex("""default\s+route\s+for""", RegexOption.IGNORE_CASE),
        Regex("""taking\s+detour""", RegexOption.IGNORE_CASE),
        Regex("""core:""", RegexOption.IGNORE_CASE),
        Regex("""Xray \d+\.\d+""", RegexOption.IGNORE_CASE),
    )

    private val stackTracePatterns = listOf(
        Regex("""^\s*at\s+"""),
        Regex("""^Caused by:"""),
        Regex("""^\.\.\.\s+\d+\s+more"""),
    )

    private val messageRules: List<Triple<Regex, String, LogLevel>> = listOf(
        Triple(Regex("Starting proxy mode", RegexOption.IGNORE_CASE), "Запускаем режим прокси…", LogLevel.INFO),
        Triple(Regex("Starting local SOCKS proxy", RegexOption.IGNORE_CASE), "Запускаем локальный SOCKS-прокси…", LogLevel.INFO),
        Triple(Regex("Proxy listening on", RegexOption.IGNORE_CASE), "Прокси запущен и готов к работе", LogLevel.SUCCESS),
        Triple(Regex("Proxy core running", RegexOption.IGNORE_CASE), "Прокси активен", LogLevel.SUCCESS),
        Triple(Regex("Starting connection sequence", RegexOption.IGNORE_CASE), "Начинаем подключение…", LogLevel.INFO),
        Triple(Regex("Establishing VPN Tunnel", RegexOption.IGNORE_CASE), "Создаём VPN-туннель…", LogLevel.INFO),
        Triple(Regex("VPN Tunnel successfully configured", RegexOption.IGNORE_CASE), "VPN-туннель создан", LogLevel.SUCCESS),
        Triple(Regex("Invoking Xray core", RegexOption.IGNORE_CASE), "Запускаем ядро Xray…", LogLevel.INFO),
        Triple(Regex("Successfully started Xray", RegexOption.IGNORE_CASE), "Подключение установлено", LogLevel.SUCCESS),
        Triple(Regex("Xray core running", RegexOption.IGNORE_CASE), "VPN активен и работает", LogLevel.SUCCESS),
        Triple(Regex("Stopping Xray", RegexOption.IGNORE_CASE), "Отключаем VPN…", LogLevel.INFO),
        Triple(Regex("VPN revoked by system", RegexOption.IGNORE_CASE), "Подключение прервано: запущено другое VPN-приложение", LogLevel.WARNING),
        Triple(Regex("Failed to establish VPN interface", RegexOption.IGNORE_CASE), "Не удалось создать VPN-туннель", LogLevel.ERROR),
        Triple(Regex("Failed to dup2 tun fd", RegexOption.IGNORE_CASE), "Ошибка настройки VPN-туннеля", LogLevel.ERROR),
        Triple(Regex("Failed to invoke libv2ray", RegexOption.IGNORE_CASE), "Не удалось запустить ядро Xray", LogLevel.ERROR),
        Triple(Regex("Error running xray", RegexOption.IGNORE_CASE), "Ошибка при подключении", LogLevel.ERROR),
        Triple(Regex("Error stopping libv2ray", RegexOption.IGNORE_CASE), "Ошибка при отключении", LogLevel.WARNING),
        Triple(Regex("Failed to add allowed application (.+): (.+)", RegexOption.IGNORE_CASE), "Не удалось добавить приложение в туннель", LogLevel.WARNING),
        Triple(Regex("Failed to exclude application (.+): (.+)", RegexOption.IGNORE_CASE), "Не удалось исключить приложение из туннеля", LogLevel.WARNING),
        Triple(Regex("Failed to exclude app from VPN", RegexOption.IGNORE_CASE), "Не удалось настроить исключение приложения", LogLevel.WARNING),
    )

    fun parseUserFriendly(lines: List<String>, errorsOnly: Boolean): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        for (line in lines) {
            val entry = parseLine(line) ?: continue
            if (errorsOnly && entry.level != LogLevel.WARNING && entry.level != LogLevel.ERROR) continue
            entries.add(entry)
        }
        return entries
    }

    fun formatDetailed(lines: List<String>, errorsOnly: Boolean): String {
        val filtered = if (errorsOnly) {
            lines.filter { isErrorLine(it) }
        } else {
            lines
        }
        return filtered.joinToString("\n")
    }

    fun formatUserFriendlyForCopy(entries: List<LogEntry>): String {
        return entries.joinToString("\n") { entry ->
            val prefix = when (entry.level) {
                LogLevel.SUCCESS -> "✓"
                LogLevel.WARNING -> "!"
                LogLevel.ERROR -> "✗"
                LogLevel.INFO -> "·"
            }
            "$prefix [${entry.time}] ${entry.message}"
        }
    }

    private fun parseLine(line: String): LogEntry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (shouldSkip(trimmed)) return null
        if (stackTracePatterns.any { it.containsMatchIn(trimmed) }) return null

        val (time, body) = extractTimeAndBody(trimmed) ?: return null

        for ((pattern, message, level) in messageRules) {
            if (pattern.containsMatchIn(body)) {
                return LogEntry(time, message, level)
            }
        }

        when {
            body.contains("[Error]", ignoreCase = true) ||
                body.contains("Error:", ignoreCase = true) ||
                body.contains("E/", ignoreCase = false) -> {
                val msg = simplifyError(stripXrayPrefix(body))
                return LogEntry(time, msg, LogLevel.ERROR)
            }
            body.contains("[Warning]", ignoreCase = true) ||
                body.contains("Warning:", ignoreCase = true) ||
                body.contains("W/", ignoreCase = false) -> {
                val msg = simplifyWarning(stripXrayPrefix(body))
                return LogEntry(time, msg, LogLevel.WARNING)
            }
            looksTechnical(body) -> return null
            else -> return null
        }
    }

    private fun stripXrayPrefix(body: String): String {
        return body
            .replace(Regex("""\[(Error|Warning|Info|Debug)]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[\d+]"""), "")
            .replace(Regex("""\S+/\S+:"""), "")
            .trim()
    }

    private fun extractTimeAndBody(line: String): Pair<String, String>? {
        appTimestampRegex.matchEntire(line)?.let { match ->
            return match.groupValues[1] to match.groupValues[2]
        }
        xrayTimestampRegex.matchEntire(line)?.let { match ->
            val rawTime = match.groupValues[1]
            val body = match.groupValues[2]
            if (isXrayInternalLog(body)) return null
            val formatted = rawTime.replace('/', '-')
            return formatted to body
        }
        if (isXrayInternalLog(line)) return null
        return null
    }

    private fun isXrayInternalLog(body: String): Boolean {
        if (body.contains("[Error]", ignoreCase = true) ||
            body.contains("[Warning]", ignoreCase = true)
        ) {
            return false
        }
        return skipPatterns.any { it.containsMatchIn(body) }
    }

    private fun shouldSkip(line: String): Boolean {
        return skipPatterns.any { it.containsMatchIn(line) }
    }

    private fun looksTechnical(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("libv2ray") ||
            lower.contains("initcoreenv") ||
            lower.contains("startloop") ||
            lower.contains("stoploop") ||
            lower.contains("corecallback") ||
            lower.contains("fd 0") ||
            lower.contains("stacktrace") ||
            lower.contains("app/") ||
            lower.contains("common/") ||
            lower.contains("core/") ||
            body.length > 160
    }

    private fun simplifyError(body: String): String {
        val cleaned = body
            .removePrefix("Error:")
            .removePrefix("error:")
            .trim()
        return when {
            cleaned.contains("connection refused", ignoreCase = true) -> "Сервер отклонил подключение"
            cleaned.contains("timeout", ignoreCase = true) -> "Превышено время ожидания сервера"
            cleaned.contains("no such host", ignoreCase = true) -> "Сервер не найден — проверьте адрес"
            cleaned.contains("certificate", ignoreCase = true) -> "Ошибка сертификата TLS"
            cleaned.contains("authentication", ignoreCase = true) -> "Ошибка аутентификации"
            cleaned.length > 120 -> cleaned.take(117) + "…"
            else -> cleaned.ifEmpty { "Произошла ошибка" }
        }
    }

    private fun simplifyWarning(body: String): String {
        val cleaned = body
            .removePrefix("Warning:")
            .removePrefix("warning:")
            .trim()
        return if (cleaned.length > 120) cleaned.take(117) + "…" else cleaned.ifEmpty { "Предупреждение" }
    }

    private fun isErrorLine(line: String): Boolean {
        return line.contains("Warning", ignoreCase = true) ||
            line.contains("Error", ignoreCase = true) ||
            line.contains("E/", ignoreCase = false) ||
            line.contains("W/", ignoreCase = false)
    }
}
