// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

/** The names and limits of ATP v0 (SPEC.md); `fixtures/names.json` and `fixtures/consent.json` hold the same values. */
object Atp {
    const val BINDING_VERSION = 1
    const val SPEC_VERSION = "0.1"
    const val FRAMING = "ndjson"

    /** The MCP protocol version both sides speak in v0. */
    const val ERA = "2025-06-18"

    const val AIDL_PACKAGE = "com.nujan_agent.atp.v1"
    const val ACTION_PROVIDER = "com.nujan_agent.atp.action.PROVIDER"
    const val ACTION_CONNECT = "com.nujan_agent.atp.action.CONNECT"
    const val ACTION_REQUEST_CONNECT = "com.nujan_agent.atp.action.REQUEST_CONNECT"

    /** The provider's connected-apps screen, where the person switches groups per app (SPEC.md 6.4). */
    const val ACTION_CONNECTED_APPS = "com.nujan_agent.atp.action.CONNECTED_APPS"

    const val META_DATA_PROVIDER = "com.nujan_agent.atp.provider"
    const val MANIFEST_ROOT = "atp-provider"

    const val EXTRA_NONCE = "com.nujan_agent.atp.extra.NONCE"
    const val EXTRA_GROUPS = "com.nujan_agent.atp.extra.GROUPS"
    const val EXTRA_PURPOSE = "com.nujan_agent.atp.extra.PURPOSE"
    const val EXTRA_OUTCOME = "com.nujan_agent.atp.extra.OUTCOME"

    /** `Activity.RESULT_FIRST_USER`: a consent result that completed carries this code and an outcome extra. */
    const val CONSENT_RESULT_CODE = 1
    const val PURPOSE_MAX_CHARS = 120

    const val META_GROUP = "com.nujan-agent.atp/group"
    const val META_ORIGIN = "com.nujan-agent.atp/origin"
    const val META_EPHEMERAL = "com.nujan-agent.atp/ephemeral"
    const val META_MISSING_GROUPS = "com.nujan-agent.atp/missingGroups"

    /** On a progress notice for a call: true while the provider asks the person about it, false once that is over. */
    const val META_ASKING = "com.nujan-agent.atp/asking"

    /** On a call: the turn of the host's agent it belongs to, an opaque string (SPEC.md 7.5). */
    const val META_TURN = "com.nujan-agent.atp/turn"

    /** On a result: true when the person stopped the turn on the provider's screen (SPEC.md 7.5). */
    const val META_STOPPED = "com.nujan-agent.atp/stopped"

    /** The notification a host sends when a turn ends, with params `{"turn": "<id>"}` (SPEC.md 7.5). */
    const val TURN_ENDED = "notifications/com.nujan-agent.atp/turn_ended"

    /** The notification a provider sends when the person stops a turn on its screen, with params `{"turn": "<id>"}` (SPEC.md 7.5). */
    const val TURN_STOPPED = "notifications/com.nujan-agent.atp/turn_stopped"

    /** The longest turn id a provider reads; a longer one is cut to this. */
    const val TURN_MAX_CHARS = 64

    /** Lines up to this size must be accepted. */
    const val MAX_LINE_BYTES = 16 * 1024 * 1024

    /** A receiver may close a channel whose line exceeds this. */
    const val HARD_LINE_BYTES = 64 * 1024 * 1024

    const val WRITE_QUEUE_BYTES = 32L * 1024 * 1024
    const val CHANNELS_PER_HOST = 8
    const val REQUESTS_IN_FLIGHT = 32
    const val OPENS_PER_MINUTE = 10

    const val NONCE_TTL_MS = 5 * 60 * 1000L
    const val NONCE_HEX_CHARS = 32
    const val TOOL_TIMEOUT_MS = 300_000L
    const val PROVIDER_ANSWER_MS = 240_000L
    const val STALL_RESPONSE_MS = 10 * 60 * 1000L
    const val STALL_NOTIFICATION_MS = 60_000L
    const val UI_REQUEST_TTL_MS = 60_000L

    /** A grant whose host has not opened a channel for this long expires, and the person is asked again (SPEC.md 6.4). */
    const val GRANT_UNUSED_MS = 90L * 24 * 60 * 60 * 1000
}
