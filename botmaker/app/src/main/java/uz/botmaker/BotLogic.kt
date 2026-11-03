package uz.botmaker

/** Чистая логика без Android: проверки и ответы созданных ботов. */
object BotLogic {
    private val USERNAME_RE = Regex("[A-Za-z][A-Za-z0-9_]*[Bb][Oo][Tt]")
    private val SPACES = Regex("\\s+")

    fun validUsername(u: String): Boolean = u.length in 5..32 && USERNAME_RE.matches(u)

    /** Строки вида «слово1, слово2 | ответ» → список автоответов (не более 20). */
    fun parseFaq(text: String): List<Faq> {
        val out = ArrayList<Faq>()
        for (line in text.lines()) {
            val i = line.indexOf('|')
            if (i < 0) continue
            val keys = line.substring(0, i).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val answer = line.substring(i + 1).trim().take(1000)
            if (keys.isNotEmpty() && answer.isNotEmpty()) out.add(Faq(keys, answer))
            if (out.size >= 20) break
        }
        return out
    }

    fun faqToText(faq: List<Faq>): String =
        faq.joinToString("\n") { it.keys.joinToString(", ") + " | " + it.answer }

    fun reply(cfg: BotConfig, raw: String): String {
        val text = raw.trim()
        val low = text.lowercase()
        if (low.startsWith("/")) {
            val cmd = low.split(SPACES)[0].substringBefore("@")
            return when (cmd) {
                "/start" -> cfg.welcome.ifBlank { "Здравствуйте!" }
                "/help" -> {
                    if (cfg.faq.isEmpty()) {
                        "Напишите мне сообщение."
                    } else {
                        "Я отвечаю на вопросы по темам:\n• " + cfg.faq.joinToString("\n• ") { it.keys.joinToString(", ") }
                    }
                }
                else -> "Неизвестная команда. Попробуйте /help"
            }
        }
        for (f in cfg.faq) {
            if (f.keys.any { low.contains(it) }) return f.answer
        }
        return if (cfg.faq.isEmpty()) text else "Не нашёл ответа. Список тем: /help"
    }
}
