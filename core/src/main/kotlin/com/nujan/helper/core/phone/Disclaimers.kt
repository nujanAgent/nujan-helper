package com.nujan.helper.core.phone

/** Something the person reads and accepts before going on, and the version their acceptance is recorded against. */
data class Disclaimer(val id: String, val version: Int, val title: String, val paragraphs: List<String>, val ticks: List<String>, val accept: String)

/** The Helper's warnings, in one place so what is shown and what TERMS.md says can be checked against each other. */
object Disclaimers {
    /** Shown once, before the Helper does anything. */
    val USING_THE_HELPER = Disclaimer(
        id = "helper",
        version = 2,
        title = "Use NuJan Helper at your own risk",
        paragraphs = listOf(
            "NuJan Helper runs programs and tools on this phone, for you and for the apps you connect to it, often because an AI " +
                "model asked. Models make mistakes, and what they read can mislead them.",
            "It can change or delete files, use your data and network, install software, and write to devices you plug in.",
            "NuJan Helper is free software and comes as it is, with no warranty of any kind, as far as the law allows. You use it at " +
                "your own risk. As far as the law allows, its authors are not liable for what happens from using it, including lost " +
                "data, damage to a phone or device, costs, and anything a connected app or model does with it.",
            "This does not remove what the law keeps: liability for death or injury caused by negligence, for fraud, for deliberate " +
                "harm or gross negligence, and any right the law does not let you give up.",
        ),
        ticks = listOf("I am 18 or over, and I have read the terms", "I understand the risk and accept it"),
        accept = "Continue",
    )

    /** Shown before the Helper can drive this phone, each time its version rises. */
    val DRIVING_THE_PHONE = Disclaimer(
        id = "phone",
        version = 2,
        title = "Let an app and its AI model use this phone",
        paragraphs = listOf(
            "With this on, you authorise an app you connect, and the AI model behind it, to see your screen and act on it: open " +
                "apps, tap, type, change settings, install and remove apps, and run commands with the same power as a computer " +
                "plugged in for USB debugging.",
            "What is on your screen goes to that app, and from there to the model it uses. That can include messages, photos, " +
                "accounts and other people's information, so share only what you are allowed to. The Helper itself does not send " +
                "your screen or files to its authors.",
            "A model can get it wrong, and a web page or message it reads can try to trick it. It could send something, buy " +
                "something, delete something or change a setting, and the Helper cannot catch everything.",
            "Banks, shops and other services may treat what it does as your activity. Some forbid automated use, and may block or " +
                "close an account over it. Use this only on a phone and accounts that are yours.",
            "Use it while you can watch. Never use it where a mistake could injure someone or cost more than you can afford to lose.",
            "The Helper asks before steps that are hard to undo, shows when it is in use, and Stop on its notification ends it at " +
                "any moment. Even so, you use it at your own risk. Except for what the law keeps, its authors are not liable for " +
                "what happens from it.",
        ),
        ticks = listOf("I understand the risk and accept it"),
        accept = "Turn on",
    )
}
