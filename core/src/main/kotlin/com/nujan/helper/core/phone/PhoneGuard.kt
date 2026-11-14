package com.nujan.helper.core.phone

/**
 * What the person is asked about. A list of dangerous commands can always be spelled another way, so the shell works the other
 * way round: only commands that plainly just read go ahead unasked, and the person is asked about the rest. They may allow
 * commands for a while, which [commandRisk]'s commands never get: those are asked about every time.
 */
object PhoneGuard {
    private class Rule(val pattern: Regex, val why: String)

    private fun rule(pattern: String, why: String) = Rule(Regex(pattern, RegexOption.IGNORE_CASE), why)

    private val COMMANDS = listOf(
        rule("""master_clear|factory_reset|\brecovery\b|--wipe_data|\bsm\s+(partition|forget)|\bmkfs|\bdd\s""", "It can erase this phone or a storage volume."),
        rule("""\b(pm|cmd\s+package)\s+(uninstall|clear|disable|hide|suspend)""", "It removes an app, its data, or turns it off."),
        rule("""\brm\s""", "It deletes files, and nothing brings them back."),
        rule("""locksettings|\bdpm\b|device_policy|lockscreen|lock_screen""", "It changes the lock screen or who manages this phone."),
        rule("""\bsettings\s+put\b.*(verifier|adb_|development_settings|install_non_market|accessibility|autofill)""", "It changes a setting that protects this phone."),
        rule("""\b(pm|cmd\s+package)\s+(grant|revoke|set-installer)|\bappops\s+set""", "It changes what an app is allowed to do."),
        rule("""\breboot\b|\bsvc\s+power\b""", "It restarts or shuts down this phone."),
        rule("""\bisms\b|action\.(CALL|SENDTO|SEND)\b|content\s+(insert|update|delete)\b""", "It sends a message, makes a call or changes stored data."),
        rule("""\bcmd\s+account\b|\bam\s+(force-stop|kill-all)\b""", "It changes accounts or stops other apps."),
        rule("""\bbase64\s+(-d|--decode)|\beval\b|\bcurl\b.*\|\s*(sh|bash)|\bwget\b.*\|\s*(sh|bash)""", "It runs commands it spells out only when it runs."),
    )

    /** Words that mean money, passwords or sign-in codes wherever they appear in an app's package name. */
    private val MONEY_WORDS = listOf(
        "bank", "wallet", "paytm", "phonepe", "paisa", "razorpay", "paypal", "venmo", "revolut", "transferwise", "payoneer",
        "americanexpress", "wellsfargo", "coinbase", "binance", "crypto", "metamask", "kraken", "robinhood", "zerodha", "upstox",
        "groww", "mobikwik", "freecharge", "zellepay", "squareup.cash", "dreamplug", "klarna", "afterpay",
        "password", "bitwarden", "lastpass", "keepass", "dashlane", "enpass", "nordpass", "authenticator", "authy",
    )

    /** Short names that mean money only as a whole part of a package name: "upi" in in.org.npci.upiapp, never in "groupie". */
    private val MONEY_PARTS = setOf(
        "upi", "upiapp", "bhim", "sbi", "hdfc", "icici", "axis", "kotak", "idfc", "indusind", "canara", "chase", "citi", "bofa",
        "usaa", "schwab", "fidelity", "vanguard", "etrade", "getmondo", "number26", "nubank", "ledger",
    )

    /** Words in an app's name that mean money, passwords or sign-in codes. */
    private val MONEY_NAME_WORDS = setOf(
        "bank", "banking", "upi", "wallet", "cash", "loan", "loans", "crypto", "bitcoin", "trading", "broker", "stocks",
        "password", "passwords", "authenticator",
    )

    /** Android's own screens where a tap can hand out a permission or install something. */
    private val SYSTEM_GATES = listOf(
        "com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.vending",
        "com.google.android.gms",
    )

    /** Commands that read and change nothing. Each part of a pipeline must be one of these. */
    private val ONLY_READ = listOf(
        Regex("""^(ls|cat|head|tail|grep|egrep|wc|stat|id|whoami|date|df|du|ps|uptime|uname|getprop|sort|uniq|cut|which|pwd|echo|dumpsys)(\s.*)?$"""),
        Regex("""^logcat(\s+\S+)*\s+-d(\s+\S+)*$|^logcat\s+-d$"""),
        Regex("""^(pm|cmd\s+package)\s+(list|path|dump)\b.*$"""),
        Regex("""^settings\s+(get|list)\b.*$"""),
        Regex("""^wm\s+(size|density)\s*$"""),
    )

    /** What lets a shell do more than one thing, or write: it takes a command out of [ONLY_READ] whatever it starts with. */
    private val SHELL_SYNTAX = Regex("""[;&<>`\n]|\$\(""")

    /** What the person is told about a command that is neither known to be dangerous nor plainly just reading. */
    const val UNCERTAIN_COMMAND = "The Helper can tell only that a command reads things when it is plain about it, and this one is not."

    /** Whether [command] plainly only reads, so that it goes ahead without asking. */
    fun onlyReads(command: String): Boolean {
        if (SHELL_SYNTAX.containsMatchIn(command) || Regex("""\s-c(\s|$)""").containsMatchIn(command) && command.trim().startsWith("logcat")) return false
        return command.split('|').map { it.trim() }.all { part -> part.isNotEmpty() && ONLY_READ.any { it.matches(part) } }
    }

    /** Why [command] is asked about every time, however much has been allowed, or null when it is not. */
    fun commandRisk(command: String, ownPackage: String): String? {
        if (command.contains(ownPackage, ignoreCase = true)) return "It touches NuJan Helper itself."
        return COMMANDS.firstOrNull { it.pattern.containsMatchIn(command) }?.why
    }

    /** Why acting in [packageName] is asked about, or null when it is not. */
    fun appRisk(packageName: String): String? =
        if (packageName in SYSTEM_GATES) "A tap here can change a permission, a setting or what is installed." else null

    /**
     * Whether the app [packageName], called [label], holds money, passwords or sign-in codes: a bank, a payment or trading app,
     * a wallet, a password manager or an authenticator. Phone driving neither reads nor acts in such an app, and the Hands
     * add-on turns itself off when one comes to the front.
     */
    fun holdsMoneyOrPasswords(packageName: String, label: String?): Boolean {
        val pkg = packageName.lowercase()
        if (MONEY_WORDS.any { it in pkg }) return true
        if (pkg.split('.', '_', '-').any { it in MONEY_PARTS }) return true
        val words = label?.lowercase()?.split(Regex("""[^\p{L}\p{N}]+"""))?.filter { it.isNotEmpty() }.orEmpty()
        return words.any { it in MONEY_NAME_WORDS || it.startsWith("pay") || (it.endsWith("bank") && it.length > 4) }
    }

    /**
     * What a button says when tapping it installs, pays, buys, sends, posts, calls, deletes, accepts terms or makes an account:
     * acts done in the person's name that are hard to take back.
     */
    private val CONSEQUENTIAL = Regex(
        """^(install|install now|update|reinstall|buy|buy now|pay|pay now|purchase|subscribe|place order|order now|checkout|""" +
            """check out|proceed to (checkout|payment)|confirm( (payment|purchase|order))?|delete|delete all|uninstall|send|""" +
            """transfer|donate|post|publish|call|remove|erase|reset|format|accept|accept all|agree|i agree|sign up|""" +
            """create account|register)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Why a tap at [x], [y] is asked about, or null when it may go ahead. [target] is the listed thing it was aimed at, null
     * when it was aimed by coordinates; [nodes] is the screen as last read. Asked every time, never allowed for a while:
     * an ad, a spot on a web page with nothing labelled there (where an ad's frame sits unseen), and a button that installs,
     * pays, buys, sends or deletes.
     */
    fun tapRisk(nodes: List<PhoneNode>, target: PhoneNode?, x: Int, y: Int): TapRisk? {
        if (target?.ad == true) return TapRisk.Ad
        if (PhoneUi.adZones(nodes).any { it.contains(x, y) }) {
            // A tap there reaches what is drawn on top: words over the ad's space, such as a dialog or the next tile, are the page's.
            val reached = target ?: nodes.lastOrNull { it.contains(x, y) && it.words.isNotEmpty() }
            if (reached == null || !reached.overAd) return TapRisk.Ad
        }
        val under = nodes.filter { it.contains(x, y) }
        if (target == null && under.any { it.inWeb } && under.none { it.kind != "WebView" && (it.actionable || it.words.isNotEmpty()) }) {
            return TapRisk.Unlabelled
        }
        val aimedAt = target ?: under.filter { it.actionable }.minByOrNull { it.width.toLong() * it.height }
        val words = aimedAt?.words?.trim().orEmpty()
        return if (CONSEQUENTIAL.matches(words)) TapRisk.Consequential(words) else null
    }

    /** What tapping a button that says [words] does, by its verb, as a question says it. */
    fun consequence(words: String): String = when (words.trim().lowercase().substringBefore(' ')) {
        "install", "update", "reinstall" -> "installs an app on this phone, with the access it asks for."
        "buy", "pay", "purchase", "subscribe", "place", "order", "checkout", "check", "proceed", "confirm" -> "can spend money."
        "delete", "uninstall", "remove", "erase", "reset", "format" -> "deletes something, which may not come back."
        "send", "transfer", "donate" -> "sends something, or money, to someone."
        "post", "publish" -> "posts something other people can see."
        "call" -> "places a phone call."
        "accept", "agree", "i" -> "accepts terms in your name."
        "sign", "create", "register" -> "makes an account in your name."
        else -> "may install, pay, buy, send or delete something."
    }

    /** At most this many of a screen's lines are quoted in a question, each cut to [QUOTED_CHARS]. */
    const val QUOTED_LINES = 4
    const val QUOTED_CHARS = 80

    /** Characters that hide words or reorder them, and line breaks: a quoted line shows as it reads. */
    private val UNSEEN = Regex("""[\p{Cc}\p{Cf}  ]+""")

    /** [text] on one line, without what hides or reorders words, cut to [QUOTED_CHARS]; empty when nothing readable is left. */
    fun readable(text: String): String {
        val line = text.replace(UNSEEN, " ").replace(Regex("""\s+"""), " ").trim()
        return if (line.length <= QUOTED_CHARS) line else line.take(QUOTED_CHARS - 1).trimEnd() + "…"
    }

    /** [text] in quotation marks, as a question names what a control or a screen says. */
    fun quoted(text: String): String = "“${readable(text)}”"

    /**
     * What a person reads first on the screen [nodes] of [packageName]'s window: its titles and messages, top to bottom, not its
     * controls, ads, password fields or words written to an AI. A question quotes them, so the person knows which app is being
     * installed, or what the screen is about to change, without seeing the screen behind the question.
     */
    fun screenSays(nodes: List<PhoneNode>, packageName: String?): List<String> = nodes.asSequence()
        .filter { packageName == null || it.packageName == packageName }
        .filter { !it.actionable && !it.password && !it.ad && !it.toAnAi }
        .sortedWith(compareBy({ it.top }, { it.left }))
        .map { readable(it.words) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(QUOTED_LINES)
        .toList()

    /** A `settings put` in [command], as what to read first so it can be put back; null when there is none or it is not the whole command. */
    fun settingsChange(command: String): SettingChange? =
        Regex("""^\s*settings\s+put\s+(global|secure|system)\s+(\S+)\s+\S+\s*$""").find(command)?.let {
            SettingChange(it.groupValues[1], it.groupValues[2])
        }

    /** The package of the window in front, from `dumpsys window`'s focus lines; null when nothing has focus. */
    fun foregroundIn(dumpsysWindow: String): String? =
        Regex("""mCurrentFocus=Window\{\S+ \S+ ([\w.]+)/""").find(dumpsysWindow)?.groupValues?.get(1)
            ?: Regex("""mFocusedApp=.*? ([\w.]+)/""").find(dumpsysWindow)?.groupValues?.get(1)

    /** The top of the keyboard from `dumpsys window InputMethod`; null while it is not on screen. */
    fun keyboardTopIn(dumpsysInputMethod: String): Int? {
        if (!Regex("""\bisVisible=true\b""").containsMatchIn(dumpsysInputMethod)) return null
        return Regex("""touchable region=SkRegion\(\(-?\d+,(-?\d+),""").find(dumpsysInputMethod)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** [text] as one argument for Android's shell, whatever it holds. */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}

/** A system setting about to change. */
data class SettingChange(val namespace: String, val key: String)

/** Why a tap is put to the person first: [what] it is, as the question names it, and [why] that matters. */
sealed class TapRisk(val what: String, val why: String) {
    data object Ad : TapRisk(
        "an advertisement",
        "It is marked as an ad. Ads pose as Download, Install or Play buttons, and can open sites or install apps you did not ask for.",
    )

    data object Unlabelled : TapRisk(
        "a spot on a web page where nothing is labelled",
        "Nothing the page names is there, and an unlabelled area on a web page is often an ad's frame.",
    )

    class Consequential(words: String) :
        TapRisk(PhoneGuard.quoted(words), "Tapping ${PhoneGuard.quoted(words)} ${PhoneGuard.consequence(words)}")
}
