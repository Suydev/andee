package net.kuafuai.andee.config

/**
 * What kind of thing an installed app is, only as far as deciding whether the brain should be told it exists.
 *
 * The categories are few on purpose. The point is not a taxonomy; it is to let the user keep a kind of app
 * out of what the model reads, and to keep one kind — adult content — out unless they ask for it.
 *
 * Classification is a **guess from the name and id**, so it errs toward hiding: a false hide costs one
 * "list_gear" and one sentence from the user, a false show puts something the user would not want named in
 * every request. The user's explicit setting always wins over the guess, and the brain can correct a
 * category with `note_gear`.
 */
object Categories {

    const val ADULT = "adult"
    const val FINANCE = "finance"
    const val HEALTH = "health"
    const val DATING = "dating"
    const val OTHER = "other"

    val ALL = listOf(ADULT, FINANCE, HEALTH, DATING, OTHER)

    /** Shown unless the user says otherwise. Adult is the only one hidden by default; the rest are the user's call to hide. */
    fun defaultShown(category: String): Boolean = category != ADULT

    // Lower-cased fragments. Matched against "id label" together. Kept conservative and plain — a list that
    // tries to be clever is a list nobody can audit.
    private val ADULT_WORDS = listOf(
        "porn", "xvideos", "xnxx", "pornhub", "xhamster", "redtube", "youporn", "onlyfans", "fansly",
        "hentai", "nsfw", "erotic", "adult video", "r18", "18+", "adult", "racy", "nude chat", "hookup", "av ",
        "caoliu", "cherry", "adult videos",
    )
    private val DATING_WORDS = listOf("tinder", "bumble", "hinge", "grindr", "okcupid", "soul", "tantan", "momo", "dating", "blind date", "jiayuan", "zhenai")
    private val FINANCE_WORDS = listOf(
        "alipay", "wechat pay", "bank", "stock", "trading stocks", "tonghuashun", "east money", "robinhood", "coinbase", "binance",
        "fund", "securities", "paypal", "venmo", "wallet", "investing", "china merchants", "icbc", "china construction bank", "unionpay",
    )
    private val HEALTH_WORDS = listOf("health", "medical", "hospital", "clinic appointment", "doctor", "dxy", "ping an good doctor", "period", "pregnan", "fitness", "mental", "therapy")

    /** Best guess; deliberately leans to the more private reading. Order matters: adult is tested first. */
    fun guess(id: String, label: String): String {
        val hay = "$id $label".lowercase()
        return when {
            ADULT_WORDS.any { hay.contains(it) } -> ADULT
            DATING_WORDS.any { hay.contains(it) } -> DATING
            FINANCE_WORDS.any { hay.contains(it) } -> FINANCE
            HEALTH_WORDS.any { hay.contains(it) } -> HEALTH
            else -> OTHER
        }
    }
}
