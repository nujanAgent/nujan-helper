package com.nujan.helper.core.mcp

/**
 * The Helper-side, model-independent capability toggles. Each is a
 * coarse permission the *device owner* grants in the Helper UI; a tool that needs one is refused at
 * the MCP boundary when its capability is off — regardless of what NuJan (the brain) asks. This is
 * the belt-and-suspenders half of the authorization model: even a hijacked brain cannot exceed what
 * the owner toggled on here.
 *
 * `id`s are the stable strings [CapabilityGate] names in a refusal and the toggles are stored under; [label] and
 * [detail] are what the Helper's Rules screen shows for the switch.
 */
enum class Capability(val id: String, val label: String, val detail: String, val onUntilChanged: Boolean = true) {
    SHELL("shell", "Run commands", "Programs in the Linux toolbox, and an app's own project code"),
    NETWORK("network", "Reach the internet", "Commands, package installs and MCP servers can all go online, so each needs this too"),
    PKG("pkg", "Install Linux packages", "Toolchains such as Python and Node, set up with apt"),
    APK_INSTALL("apk-install", "Install apps", "An app built in the toolbox; Android asks you before each one"),
    MCP("mcp", "MCP servers", "Start servers in the toolbox and use their tools"),
    USB("usb", "USB devices", "Boards plugged into the phone, each once Android has asked you"),

    /** Off until the person accepts its warning on the Drive this phone page, which also holds its switch. */
    PHONE("phone", "Drive this phone", "See the screen, act on it and use Android's shell", onUntilChanged = false);

    companion object {
        fun fromId(id: String): Capability? = entries.firstOrNull { it.id == id }

        /** The default grant: everything enabled until the owner narrows it. */
        fun all(): Set<Capability> = entries.toSet()
    }
}
