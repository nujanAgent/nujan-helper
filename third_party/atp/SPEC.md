<!-- SPDX-License-Identifier: Apache-2.0 -->

# App Tools Protocol (ATP) for Android, compatible with MCP — specification v0

**Status: v0, the contract that NuJan (host), NuJan Helper and GDatp (providers) build to.** Version `0.1`. It
freezes the Binder surface, the wire values and the fixtures in `fixtures/` (`atp/fixtures/` in the ATP repository);
everything else may still change before
1.0. Proposals are numbered ATP-RFC-n. Licence: Apache-2.0 (see `LICENSE`).

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are used as in RFC 2119.

ATP carries MCP's data layer unchanged and specifies only what Android lacks: a same-device transport,
discovery, two-sided consent, identity on every call and lifecycle rules. "Model Context Protocol" and "MCP"
are trademarks of LF Projects, LLC; ATP is an independent protocol for use with MCP. Android is a trademark
of Google LLC.

## 1. Roles and terms

| Term | Meaning |
|---|---|
| Host | An app that runs an agent and calls tools (NuJan). |
| Provider | An app that serves MCP tools over ATP (NuJan Helper, GDatp, or any third party). |
| Channel | One byte stream pair per (host uid, provider package, `serverId`). It has no MCP-session meaning. |
| Group | A named set of a provider's tools that is granted or refused together (`files`, `exec`, …). |
| Grant | A provider's record of what one host may use: groups, each ON, ASK or OFF. |
| Era | The MCP protocol version a channel speaks. v0 uses `2025-06-18` only. |

Identifiers use the namespace `com.nujan_agent.atp` on Android and `com.nujan-agent.atp` in MCP `_meta` keys.

## 2. Principles

1. The MCP data layer travels verbatim.
2. ATP specifies only what Android lacks.
3. Every trust anchor is platform-enforced: the kernel uid, the signer PackageManager reports, file
   descriptors held by two processes, system URI grants and the PendingIntent creator. A peer's claims about
   itself are never a trust anchor.
4. Authorization happens twice, independently of any model: in the provider (per-host groups) and in the host.
5. A process that might be frozen is never called synchronously, and no binder thread is ever pinned.
6. A channel is a transport connection. No MCP state may depend on it staying open.

## 3. Transport

### 3.1 Planes

| Plane | Carried by | Purpose |
|---|---|---|
| Control | Binder (§4) | identity, lifecycle, death and freeze signals, platform objects |
| Data | two pipes the **host** creates and hands over in `openChannel` | MCP stdio framing |

### 3.2 Rules

1. The host MUST create both pipes with `ParcelFileDescriptor.createPipe()`: `toProvider` (host writes,
   provider reads) and `fromProvider` (provider writes, host reads).
2. Immediately after `openChannel` returns, the host MUST close its copies of the two ends it handed over,
   or end-of-stream never arrives.
3. The provider MUST `fstat()` each received descriptor and accept only a FIFO (`S_ISFIFO`); anything else
   is refused with status `BAD_STREAM`. It MUST set `FD_CLOEXEC` and MUST keep channel descriptors out of
   every child process.
4. Framing is `ndjson`: one UTF-8 JSON-RPC message per line, terminated by `\n`. A message MUST NOT contain
   a raw newline.
5. Lines up to **16 MiB** (16,777,216 bytes, excluding the newline) MUST be accepted. A receiver MAY close
   the channel with `PROTOCOL_ERROR` when a line exceeds **64 MiB**; between the two it MAY accept or close.
6. A file travels by handoff (§9). A picture in a result (§8 item 4) and the bytes of a file argument (§8 item 7)
   travel inline as base64, within the line limit of item 5.
7. Close reasons travel on Binder (`IAtpHost.onClosed`), never in-band.
8. Each channel has exactly one writer per side and a bounded write queue of **32 MiB**. A writer that
   cannot drain coalesces list-change notifications, keeps only the latest progress per token and drops
   log messages once the queue is half full.

Rejected and not part of ATP: oneway AIDL messages as the data plane (they drop messages and cap size),
abstract-namespace sockets, loopback HTTP.

## 4. Binder surface

### 4.1 AIDL (package `com.nujan_agent.atp.v1`, frozen at binding version 1)

```aidl
interface IAtpProvider {
    byte[] hello(in byte[] helloJson);
    byte[] openChannel(String serverId, in ParcelFileDescriptor toProvider,
                       in ParcelFileDescriptor fromProvider, IAtpHost host, in byte[] openJson);
    oneway void closeChannel(String serverId, int reason);
    oneway void uiResult(String serverId, int requestId, int resultCode);
    void releaseGrant();
}

interface IAtpHost {
    oneway void onUiRequest(int requestId, in PendingIntent intent, in byte[] reasonJson);
    oneway void onClosed(int reason, in byte[] detailJson);
    oneway void onServersChanged();
}
```

The binding version travels in `hello`'s answer (`binding`); the AIDL compiler reserves `getInterfaceVersion` for
its own versioned interfaces, so the interface declares no such method.

Errors are returned as status JSON, never thrown: `ServiceSpecificException` is not in the public SDK, and
Binder carries only a fixed set of exception types from app code.

### 4.2 Wire rules

1. Parameters are only `int`, `long`, `String`, `byte[]`, `ParcelFileDescriptor`, `PendingIntent` and
   binders. No `Bundle`, no custom `Parcelable`.
2. JSON travels as UTF-8 `byte[]`. Receivers MUST ignore unknown keys.
3. The interface descriptors and transaction codes are pinned by `fixtures/aidl-table.json`; a conformance
   test compares the generated stubs with it.

### 4.3 Identity on every call

1. A provider MUST check `Binder.getCallingUid()` on **every** call into `IAtpProvider` (§6.1). Each channel
   is bound to the uid that opened it; a call about a channel from another uid is ignored.
2. A host MUST check, on every `IAtpHost` callback, that `Binder.getCallingUid()` equals the provider's uid
   (`PackageManager.getPackageUid(provider, 0)`); other callers are ignored.
3. Handed-over descriptors are bearer capabilities. A host MUST NOT pass them on.

### 4.4 Status JSON

`openChannel` returns `{"status":0}` on success, otherwise
`{"status":<code>,"name":"<NAME>","detail":"<readable text>"}`:

| Code | Name | Meaning |
|---|---|---|
| 0 | OK | The channel is open. |
| 1 | NOT_GRANTED | This host has no grant; run consent (§6.3). |
| 2 | BLOCKED | The person blocked this host in the provider. |
| 3 | UNKNOWN_SERVER | No server with that id. |
| 4 | TOO_MANY_CHANNELS | The host already holds 8 channels with this provider. |
| 5 | UNSUPPORTED_VERSION | The binding or spec version in `openJson` is not served. |
| 6 | NOT_READY | The provider needs set-up first ("Open [App]"). |
| 7 | TRANSPORT_UNAVAILABLE | The provider cannot use the descriptors on this device. |
| 8 | BAD_STREAM | A descriptor is not a pipe. |
| 9 | RATE_LIMITED | More than 10 opens in the last minute. |
| 10 | IDENTITY_REFUSED | The caller failed the identity checks (§6.1). |

### 4.5 Close reasons

`IAtpHost.onClosed(reason, detailJson)` and `IAtpProvider.closeChannel(serverId, reason)` use:

| Value | Name | Meaning |
|---|---|---|
| 0 | NORMAL | Closed on purpose, nothing wrong. |
| 1 | REVOKED | The person revoked this host's grant. |
| 2 | BLOCKED | The person blocked this host. |
| 3 | PROVIDER_SHUTDOWN | The provider is stopping. |
| 4 | STALLED | The peer stopped reading (§7.3). |
| 5 | PROTOCOL_ERROR | Bad framing, an oversized line or invalid JSON-RPC. |
| 6 | IDLE | Reserved for a close after a period without traffic, which version 0.1 does not define; nothing sends it, and a receiver treats it as `NORMAL`. |
| 7 | IDENTITY_CHANGED | Reserved for a peer whose signer changed while a channel was open. A signer changes only by reinstalling, which ends the channel first (§6.1 item 4), so nothing sends it; a receiver treats it as `REVOKED`. |
| 8 | EXPIRED | The grant expired from disuse. |

`detailJson` is `{"reason":<value>,"name":"<NAME>","detail":"<readable text>"}`.

### 4.6 ATP JSON-RPC errors

ATP's own JSON-RPC errors use codes outside −32768…−32000:

| Code | Name | When |
|---|---|---|
| −31000 | TOO_MANY_REQUESTS | More than 32 requests in flight on the channel. |
| −31001 | SHUTTING_DOWN | The channel is closing and the request was not started. A side MAY send it; one that closes the channel at once answers nothing more, and the host fails the calls still in flight when `onClosed` arrives. |

An unknown tool is JSON-RPC `-32602` as in MCP. A call to a tool the host is not granted is **not** a
JSON-RPC error: it is a `tools/call` result with `isError: true`, readable text, and
`_meta {"com.nujan-agent.atp/missingGroups": ["<group>", …]}`.

### 4.7 hello

Request (`helloJson`):

```json
{"binding": 1, "spec": "0.1", "wantNonce": true, "groups": ["files", "exec"]}
```

`wantNonce` and `groups` are optional; `groups` names what the host intends to ask for at consent. `hello`
MUST NOT throw for an ungranted caller and reveals only that caller's own grant state, the provider's static
servers and its limits:

```json
{"binding": 1, "spec": "0.1",
 "grant": {"state": "granted", "groups": {"files": "on", "exec": "ask", "mcp": "off"}},
 "servers": [{"id": "main", "mcpEras": ["2025-06-18"]}],
 "limits": {"channels": 8, "inFlight": 32, "opensPerMinute": 10, "maxLineBytes": 16777216},
 "nonce": "9f2c4e1a7b3d5f60a8c2e4d6f1b3a5c7", "nonceTtlMs": 300000}
```

`grant.state` is `none`, `granted` or `blocked`. `nonce` is present only when requested and the caller is not
blocked; it is 32 lowercase hex digits (128 bits).

### 4.8 openJson

```json
{"framing": "ndjson", "binding": 1, "spec": "0.1", "era": "2025-06-18"}
```

A provider that does not serve the `framing`, `binding` or `era` returns `UNSUPPORTED_VERSION`.

## 5. Discovery

### 5.1 Provider manifest

A provider declares:

- an exported service with an intent filter for `com.nujan_agent.atp.action.PROVIDER` and **no**
  `android:permission`, and `<meta-data android:name="com.nujan_agent.atp.provider"
  android:resource="@xml/atp_provider" />` on it;
- an exported consent activity with an intent filter for `com.nujan_agent.atp.action.CONNECT` and
  `android.intent.category.DEFAULT`, `launchMode="standard"`, in its **own `android:process`**, so the
  service process never shows UI;
- `<uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS" />`;
- optionally, an exported connected-apps activity with an intent filter for
  `com.nujan_agent.atp.action.CONNECTED_APPS` and `android.intent.category.DEFAULT` (§6.4).

### 5.2 `res/xml/atp_provider.xml`

```xml
<atp-provider xmlns:android="http://schemas.android.com/apk/res/android"
    spec="1" privacyPolicy="https://example.com/privacy" contact="mailto:dev@example.com">
  <server id="main" mcpEras="2025-06-18" toolFamily="code files" android:title="@string/server_title" />
  <group id="files" risk="low" kind="read write" grant="in-flow"
         android:title="@string/g_files" android:summary="@string/g_files_s" />
  <group id="exec" risk="critical" kind="exec egress" grant="provider-ui"
         android:title="@string/g_exec" android:summary="@string/g_exec_s" />
</atp-provider>
```

The example's addresses are reserved names, as an example's are; a provider's file gives its own.

| Attribute | Rule |
|---|---|
| `spec` | `1` for this version. |
| `privacyPolicy`, `contact` | Required, and the provider's own: `privacyPolicy` is an `https:` URL and `contact` an `https:` or `mailto:` URL, neither at a reserved name (RFC 2606, RFC 6761): `localhost`, `example.com`, `example.net`, `example.org`, or a name under `.example`, `.invalid`, `.localhost` or `.test`. |
| `server@id`, `group@id` | `[a-z0-9-]{1,32}`, unique. |
| `mcpEras` | Space-separated MCP versions; v0 requires `2025-06-18`. |
| `toolFamily` | Space-separated, from `files code device web media productivity`. Advisory only. |
| `risk` | `low`, `medium`, `high` or `critical`. |
| `kind` | Space-separated, from `read write exec egress install device`. |
| `grant` | `in-flow` (may be granted on the consent screen) or `provider-ui` (only inside the provider). |
| `server@entry` | Optional: the class the provider's own kit makes to list the server's tools. Hosts ignore it. |

A host ignores attributes and elements this section does not name.

Caps: 64 KB in total, 16 servers, 32 groups, strings at most 200 characters, summaries at most 80. Everything
renders as plain text. `risk` and `kind` are claims that may only **raise** a host's caution; a host treats a
group it does not recognise as high risk.

### 5.3 Host side

1. A host MUST declare `<queries><intent><action android:name="com.nujan_agent.atp.action.PROVIDER" />
   </intent></queries>`. That is necessary and sufficient to see providers.
2. A host discovers providers with `queryIntentServices` and reads the manifest with
   `ServiceInfo.loadXmlMetaData` and `getResourcesForApplication`, so no provider code runs.
3. A host rescans when its provider screen opens, at launch, and on `PACKAGE_ADDED`, `PACKAGE_REPLACED` and
   `PACKAGE_REMOVED`.
4. A host shows **platform facts** for a provider: label and icon from PackageManager, package, a short
   signer hash, installer (as neutral text), install age and version. It never renders a provider-supplied
   icon. Of the manifest's URLs it renders only `privacyPolicy` and `contact`.

### 5.4 Address

`atp:PACKAGE[/SERVER_ID]`, for configuration files and skill requirements, for example
`atp:com.nujan.helper/main`. The scheme is `atp:` with no `//`, so it is not Bluesky's `at://`.

## 6. Identity, consent and grants

### 6.1 Caller checks (provider, on every call)

1. Take `Binder.getCallingUid()`.
2. Require exactly one package for that uid; refuse none or several (a shared uid) with `IDENTITY_REFUSED`.
3. Refuse the provider's own uid.
4. Read the caller's **current** signer: on API 28+ the last `SigningInfo.getSigningCertificateHistory`
   entry, or the exact `apkContentsSigners` set when there are several; below API 28, `GET_SIGNATURES` as an
   exact set. A stored digest is accepted only if it is the current signer or in the current lineage, and is
   then moved forward to the current signer (the ratchet). A digest outside the lineage is another app: its
   `open` is refused with `NOT_GRANTED`, and it needs consent again. Such a signer arrives only by
   reinstalling, which ends the caller's channels first.
5. Never trust extras, `clientInfo` or names.

### 6.2 Host checks

On every `IAtpHost` callback the host compares the calling uid with the provider's (§4.3). Before binding,
and again before each open, it compares the provider's current signer with the one it recorded at consent;
a change is shown as a different app.

### 6.3 Consent

1. **The host's own sheet first.** It names what the host will send, to which app, and that results may reach
   the host's AI model, and links the provider's privacy policy. Back or dismiss is never consent. Nothing is
   bound before the person confirms.
2. **The host calls `hello` with `wantNonce`** and receives a single-use nonce, bound to the caller's
   (uid, package, signer, groups) and valid for 5 minutes.
3. **The host starts the provider's CONNECT activity for a result**: an explicit intent with the extras below,
   from a live activity that is not finishing, with `requestCode ≥ 0`, without `FLAG_ACTIVITY_NEW_TASK`,
   `NEW_DOCUMENT` or `MULTIPLE_TASK`, and on API 34+ with `ActivityOptions.setShareIdentityEnabled(true)`.

   | Extra | Type | Value |
   |---|---|---|
   | `com.nujan_agent.atp.extra.NONCE` | String | the nonce from `hello` |
   | `com.nujan_agent.atp.extra.GROUPS` | String[] | the groups asked for |
   | `com.nujan_agent.atp.extra.PURPOSE` | String | at most 120 characters, plain text |

4. **The provider checks who asked**: on API 34+ `getLaunchedFromUid()` equals the nonce's uid; below API 34
   `getCallingPackage()` belongs to that uid. The nonce is consumed whatever the outcome.
5. **The outcome is explicit**: `resultCode = RESULT_FIRST_USER` (1) with
   `com.nujan_agent.atp.extra.OUTCOME` set to `granted`, `denied`, `identity_refused` or `error`. A bare
   `RESULT_CANCELED` means the flow did not complete, because the system also sends it for launch failures.
6. **The host calls `hello` again** to confirm the grant before opening a channel.
7. A provider MUST NOT start consent from an IPC call. A host MUST NOT bind with `BIND_ALLOW_ACTIVITY_STARTS`.
8. A host MAY export an activity for `com.nujan_agent.atp.action.REQUEST_CONNECT` that a provider starts for a
   result; the host verifies the caller by the same rules, rate-limits it, and opens its own sheet.

Consent screens apply, on every API level where they exist: `filterTouchesWhenObscured` on the window that
holds the buttons; rejecting `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`; `setHideOverlayWindows(true)`;
`setAccessibilityDataSensitive` on Allow; Allow armed only after about one second of focus; at most three
launches per host per 24 hours, a cool-down after two denials, and a Block option. Below API 31 another app's
overlay cannot be hidden and every touch is rejected while one shows, so a consent screen SHOULD say why it
ignored a touch, and a host that draws an overlay of its own SHOULD hide it while a consent screen it started is
in front.

Text another app supplies, such as its label and the purpose, is shown on these screens without control
characters (other than line breaks and tabs), bidirectional controls, zero-width characters, variation selectors and
Unicode Tags, and inside a sentence between U+2068 FIRST STRONG ISOLATE and U+2069 POP DIRECTIONAL ISOLATE, so it
can neither hide words nor reorder the text the person decides on. A label with nothing left is shown as the
package name.

### 6.4 Groups and grants

1. Every tool belongs to at most one group, named in `_meta {"com.nujan-agent.atp/group": "<id>"}`. A tool
   without that key is **implicit**: always available to a granted host (for example `echo`, a status tool, or
   the caller's own jobs).
2. A grant holds one state per group: `on`, `ask` or `off`. `ask` is answered in the provider's own activity
   by a person's tap, never through notification actions; with notifications blocked, `ask` denies. Each call
   in an `ask` group is asked about on its own, one question at a time, naming the tool by its `title` when it has
   one (§8, item 2), and runs only when the person allows it; a call refused, or not answered within two minutes,
   is an `isError` result that names no missing group. The person chooses `ask` on the screen of item 3, which
   applies the consent screen's protections to it as well. When the call carries `_meta.progressToken`, the
   provider sends MCP `notifications/progress` for that token once the question is in front of the person, with
   `_meta {"com.nujan-agent.atp/asking": true}`, and again with `false` when it is answered or given up; a call
   without a token gets no notice. A host that draws an overlay of its own SHOULD hide it from the first notice to
   the second, or to the call's result: below API 31 the question's screen refuses every touch while another app's
   overlay shows (§6.3).
3. Groups declared `grant="provider-ui"` can be turned on only inside the provider, on a screen with the
   consent screen's protections (§6.3) that asks the person again before it turns on a `high` or `critical`
   group, its confirming button armed only after about one second of focus. A provider MAY export that
   screen for `com.nujan_agent.atp.action.CONNECTED_APPS`; a host starts it with the provider's package set,
   and only when the person asks to manage the connection.
4. `tools/list` is filtered by the grant. A call to a filtered-out tool is the `isError` result of §4.6.
5. A grant change is pushed to the host as `notifications/tools/list_changed` (era `2025-06-18`).
6. `releaseGrant()` deletes the caller's grant (the host's Disconnect).
7. A provider MAY pin trusted hosts as (package, SHA-256 of the signing certificate) pairs committed in its
   source. Pinned hosts get the publisher's defaults; consent is never skipped. A release provider build MUST
   NOT treat a caller whose current certificate subject is `CN=Android Debug` as pinned.
8. A grant expires when its host has not opened a channel for 90 days, as Android resets the permissions of an
   app left unused. The provider deletes it, so `hello` reports no grant, `openChannel` answers `NOT_GRANTED`
   and the person is asked again. A provider that ends a channel because its grant expired closes it with
   `EXPIRED`. A blocked host's record does not expire.

## 7. Lifecycle

### 7.1 Binding

1. A host holds a `BIND_AUTO_CREATE` binding for the whole life of its channels and MUST NOT pass
   `BIND_WAIVE_PRIORITY`, `BIND_NOT_PERCEPTIBLE`, `BIND_ALLOW_ACTIVITY_STARTS` or
   `BIND_INCLUDE_CAPABILITIES`.
2. **Freezer rule.** While a channel is open the host MUST be in an active state (visible, a foreground
   service, a running job or a running broadcast). It closes its channels and unbinds when it leaves that
   state.
3. On `onServiceDisconnected` the host keeps the binding (the system restarts the provider) and reopens its
   channels. On `onBindingDied` it unbinds, rebinds after a back-off starting at 500 ms or on
   `PACKAGE_REPLACED`, and discovers again.

### 7.2 Channel start

After `openChannel` returns OK the host sends MCP `initialize` (protocol `2025-06-18`), then
`notifications/initialized`, then `tools/list`. Limits per host uid and provider: 8 channels, 32 requests in
flight per channel, 10 opens per minute.

### 7.3 Stalls and timeouts

1. A side closes a channel with `STALLED` after 10 minutes with a response it owes still unwritten, or 60 s
   with a notification still unwritten, counted in wall time. A side cannot see whether its peer is frozen;
   the freezer rule (§7.1 item 2) keeps a host active, and the provider it binds unfrozen, while a channel is
   open.
2. Tool calls get 300 s. A provider answers within 240 s or returns a job handle (start, status and cancel
   tools). Progress notifications extend the deadline.
3. A host that gives up on a call sends `notifications/cancelled` for it.
4. Retry safety: a request line not fully written MAY be retried once; a fully written one MUST NOT be
   retried automatically.

### 7.4 Revocation and disconnect

1. Provider revoke: `onClosed(REVOKED)` and end-of-stream within 1 s, and the host's jobs and servers stop.
2. Host disconnect: close the channel, call `releaseGrant()`, unbind, delete the record.

### 7.5 Turns and Stop

A turn is the work a host's agent does on one request from the person: every call it makes until it answers,
stops or is stopped.

1. A host SHOULD name the turn each `tools/call` belongs to, in `_meta {"com.nujan-agent.atp/turn": "<id>"}`: an
   opaque string of at most 64 characters, the same for every call of the turn and new for the next. A provider
   treats it as opaque and MUST NOT require it.
2. When a turn ends, however it ends, the host SHOULD send `notifications/com.nujan-agent.atp/turn_ended` with
   params `{"turn": "<id>"}` on each channel that carried a call of it. A provider that shows the person a sign that
   the host is at work (an overlay, a notification) takes it down then, and otherwise after an idle time of its own,
   at most 30 s after the last call. A provider that does not know the notification ignores it, as JSON-RPC lets it.
3. A provider that offers the person a Stop on such a sign stops the turn it belongs to, not the connection. It
   sends `notifications/com.nujan-agent.atp/turn_stopped` with params `{"turn": "<id>"}` at once on the caller's
   channels, so the host need not make another call to learn of it. It answers every later call carrying that
   turn's id with an `isError` result whose text says the person stopped it, carrying
   `_meta {"com.nujan-agent.atp/stopped": true}`, and MAY end that turn's calls in flight the same way. A call
   with no turn id is answered the same way for as long as the sign the person pressed Stop on would have shown. The
   next turn runs as usual. A provider MAY also let the person turn its tools off for every turn; that is a setting
   in its own screens, not Stop.
4. A host that receives `turn_stopped` for a turn in progress, or a `stopped` result, ends that turn as its own
   Stop would, including the model request in flight. It MUST NOT repeat the call or try the same work another way,
   and it tells the person the turn was stopped on the provider's screen. A `turn_stopped` for a turn that has
   already ended is ignored.

## 8. MCP in v0

1. Era: `2025-06-18` on both sides. No era probe in v0.
2. `tools/list` is one page; hosts ignore `nextCursor`. Tools carry MCP `annotations` (`readOnlyHint`,
   `destructiveHint`, `idempotentHint`, `openWorldHint`) and the `_meta` keys of §6.4. A tool SHOULD carry MCP's
   `title`, the name a person reads: the provider's question about a call in an `ask` group names the tool by it
   (§6.4), and a host MAY show it.
3. A provider declares `tools.listChanged: true` and pushes `notifications/tools/list_changed`.
4. Results are text content with `isError`, then any pictures as MCP image content
   (`{"type": "image", "data": <base64>, "mimeType": "image/png"}`), together under 8 MiB of image data; a
   picture over that is left out with a line saying so. A host MAY show pictures, pass them to a model that
   reads images under the person's data settings, or name them only. `resource_link` arrives with handoffs
   (§9).
5. Client features are host policy: a host MAY offer no sampling, elicitation or roots. A provider MAY send
   `instructions` with its `initialize` result: plain text of at most 8,000 characters on how its tools are meant
   to be used together. A host MAY give them to its model, MUST present them as the provider's text rather than
   the person's or the host's, and MAY ignore them. A provider in v0 sends no server-to-client requests.
6. A provider MUST NOT re-export tools it got from another ATP provider. It MAY re-export MCP servers it runs
   inside its own sandbox, tagging each such tool `_meta {"com.nujan-agent.atp/origin": "<server>"}` and, when
   the tool exists only while that server runs, `"com.nujan-agent.atp/ephemeral": true`.
7. A string argument that carries a file's bytes is marked with JSON Schema's `contentEncoding: "base64"`. A
   host MAY let its model name a file the host holds there in place of the bytes, and send the file's bytes in
   its place; the provider receives base64 either way. A host does this only for a provider on the same phone,
   since its checks on what leaves the phone read the arguments its model wrote.

## 9. Handoffs

1. **UI.** A provider sends `onUiRequest` with an immutable, explicit, activity `PendingIntent`, only during
   an in-flight call from that host, at most one per channel, expiring after 60 s. `reasonJson` is
   `{"reason":"<readable text, at most 200 characters>"}`. The host checks the creator uid and `isActivity()`
   (API 31+; below 31 it declines), and sends the intent only while it is visible: at once when the person
   already approved the call that asked, otherwise after its own card. The result comes back with `uiResult`:
   `RESULT_OK` (-1) when the host sent the intent, `RESULT_CANCELED` (0) when it did not. A provider with one
   of its own windows on screen starts its activity itself and sends no request; below API 31, where hosts
   decline, it starts the activity itself while a visible host is bound to it.
2. **Provider to host files.** A content URI in an MCP `resource_link`. The host MUST verify that the URI's
   authority belongs to the provider package (`resolveContentProvider(authority).packageName`), opens it only
   through `ContentResolver`, caps its size and treats it as untrusted.
3. **Host to provider files.** Only files the person picked for this call, read-only, single documents, as a
   per-call grant revoked after the call.

## 10. Tool names in a host

A host names a provider's tool `atp_<slug>_<tool>`: `<tool>` is the wire name lowercased with anything outside
`[a-z0-9_]` mapped to `_`; `<slug>` is the provider package's last segment reduced to `[a-z0-9]`, or the segment
before it when the last only says what the package is (`atp`, `app`, `android`, `mobile`), stored at first consent
and never derived again. A collision appends `2`, `3`… in order of first consent. A host treats every provider
alike: it finds them through this protocol and names none in its own code. Calls use the provider's wire name.

A provider's descriptions, instructions and results name its tools by their wire names. A host that names them
otherwise SHOULD write its own name for each of that provider's tools in their place in what its model reads, so the
model calls a tool by a name the host knows.

## 11. Conformance

`fixtures/` (`atp/fixtures/` in the ATP repository) holds the golden values both sides test against: `hello` requests and responses, `openJson`, the
status, close-reason and error tables, the consent extras, an ungranted-call result and the AIDL table. The
provider kit's tests cover the provider's side and NuJan's host tests the host's; not every MUST has a test of
its own yet.
