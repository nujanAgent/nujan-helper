package com.nujan.helper.phone

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.ssl.SSLSockets
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nujan.helper.core.phone.AdbKeyFormat
import com.nujan.helper.core.phone.AdbPairing
import com.nujan.helper.core.phone.AdbPairingException
import com.nujan.helper.core.phone.AdbSession
import com.nujan.helper.core.phone.ShellOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.coroutines.resume

/**
 * The Helper's own connection to this phone's Android shell, over Wireless debugging on the phone's own loopback, as a
 * computer's `adb` would reach it. The key that unlocks it is in the Android Keystore, so nothing in the Linux toolbox can
 * read it or use it, and no port is opened for anything else on the phone to reach.
 */
class PhoneLink(private val context: Context) {

    enum class Status { NOT_PAIRED, PAIRED, CONNECTED }

    private val prefs = context.getSharedPreferences("phone_link", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(if (prefs.getBoolean(KEY_PAIRED, false)) Status.PAIRED else Status.NOT_PAIRED)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val connecting = Mutex()

    @Volatile private var session: AdbSession? = null

    /** Whether this Android can pair here: reading the secret out of the TLS channel needs Android 12. */
    val canPair: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun refreshStatus() {
        _status.value = when {
            session?.isOpen == true -> Status.CONNECTED
            prefs.getBoolean(KEY_PAIRED, false) -> Status.PAIRED
            else -> Status.NOT_PAIRED
        }
    }

    /** The port Android's pairing dialog has open, while it is open; null when none appears within [timeoutMs]. */
    suspend fun findPairingPort(timeoutMs: Long): Int? = discover(PAIRING_SERVICE, timeoutMs)

    /** Pairs with the dialog at [port] using its [code]. Throws an exception saying why it did not work. */
    @SuppressLint("NewApi") // canPair is checked first; the Helper offers pairing only where it is true.
    suspend fun pair(port: Int, code: String): Unit = withContext(Dispatchers.IO) {
        if (!canPair) throw AdbPairingException("Pairing here needs Android 12 or later.")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = PAIR_TIMEOUT_MS
            val tls = secure(socket, port)
            val exported = SSLSockets.exportKeyingMaterial(tls, AdbPairing.EXPORT_LABEL, null, AdbPairing.EXPORT_BYTES)
                ?: throw AdbPairingException("This phone would not share the secret pairing needs.")
            val name = "nujan-helper@" + Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "")
            AdbPairing.pair(tls.inputStream, tls.outputStream, code.trim(), exported, AdbKeyFormat.encode(publicKey()) + " " + name)
            prefs.edit().putBoolean(KEY_PAIRED, true).apply()
            refreshStatus()
        } catch (refused: java.net.ConnectException) {
            throw AdbPairingException("Nothing is waiting for a code at that port. Open Pair device with pairing code again.")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** The connection, made when there is none. Throws an IOException saying what the person has to do. */
    suspend fun connection(): AdbSession = connecting.withLock {
        session?.takeIf { it.isOpen }?.let { return it }
        if (!prefs.getBoolean(KEY_PAIRED, false)) throw IOException(NOT_PAIRED)
        val remembered = prefs.getInt(KEY_PORT, 0)
        val opened = withContext(Dispatchers.IO) { remembered.takeIf { it > 0 }?.let { runCatching { open(it) }.getOrNull() } }
            ?: run {
                val port = discover(CONNECT_SERVICE, DISCOVER_TIMEOUT_MS) ?: throw IOException(WIRELESS_DEBUGGING_OFF)
                val made = withContext(Dispatchers.IO) { open(port) }
                prefs.edit().putInt(KEY_PORT, port).apply()
                made
            }
        session = opened
        refreshStatus()
        opened
    }

    private fun open(port: Int): AdbSession {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val rawInput = socket.getInputStream()
            val rawOutput = socket.getOutputStream()
            return AdbSession.handshake(
                rawInput,
                rawOutput,
                upgrade = {
                    val tls = secure(socket, port)
                    socket.soTimeout = 0
                    Triple(tls.inputStream, tls.outputStream) { runCatching { tls.close() }.let { } }
                },
                onRawClose = {
                    runCatching { socket.close() }
                    refreshStatus()
                },
            )
        } catch (failed: IOException) {
            runCatching { socket.close() }
            throw failed
        }
    }

    /** Runs [command] in Android's shell, with its output kept apart by stream. */
    suspend fun shell(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, limit: Int = OUTPUT_LIMIT): ShellResult {
        val session = connection()
        return withContext(Dispatchers.IO) {
            val output = session.run("shell,v2,raw:$command", timeoutMs, limit)
            ShellResult(ShellOutcome.decode(output.bytes), output.truncated, output.timedOut)
        }
    }

    /** What [command] writes, as bytes: a screenshot. */
    suspend fun bytes(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray {
        val session = connection()
        return withContext(Dispatchers.IO) {
            val output = session.run("exec:$command", timeoutMs)
            if (output.timedOut) throw IOException("The phone did not finish '$command' in time.")
            output.bytes
        }
    }

    /** Ends the connection; every call running over it ends too. */
    fun disconnect() {
        session?.close()
        session = null
        refreshStatus()
    }

    /** Forgets the pairing here; the phone's own list of paired devices is the person's to clear. */
    fun forget() {
        disconnect()
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
        refreshStatus()
    }

    data class ShellResult(val outcome: ShellOutcome, val truncated: Boolean, val timedOut: Boolean)

    private fun secure(socket: Socket, port: Int): SSLSocket {
        val tls = tlsContext().socketFactory.createSocket(socket, LOOPBACK, port, true) as SSLSocket
        tls.useClientMode = true
        tls.enabledProtocols = arrayOf("TLSv1.3")
        tls.startHandshake()
        return tls
    }

    private fun tlsContext(): SSLContext = SSLContext.getInstance("TLSv1.3").apply {
        init(arrayOf(KeyManager(privateKey(), certificate())), arrayOf(AnyServer), SecureRandom())
    }

    private fun store() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ensureKey(store: KeyStore) {
        if (store.containsAlias(ALIAS)) return
        val now = System.currentTimeMillis()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
            initialize(
                // TLS 1.3 signs through raw RSA here, so the key allows no-padding as well as its signatures.
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                    .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(RSA_BITS, RSAKeyGenParameterSpec.F4))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setCertificateSubject(X500Principal("CN=NuJan Helper"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(now - DAY_MS))
                    .setCertificateNotAfter(Date(now + CERTIFICATE_DAYS * DAY_MS))
                    .build(),
            )
        }.generateKeyPair()
    }

    private fun privateKey(): PrivateKey = store().let { ensureKey(it); it.getKey(ALIAS, null) as PrivateKey }
    private fun certificate(): X509Certificate = store().let { ensureKey(it); it.getCertificate(ALIAS) as X509Certificate }
    private fun publicKey(): RSAPublicKey = certificate().publicKey as RSAPublicKey

    /**
     * The port of the [type] service this phone advertises. Other devices on the Wi-Fi advertise the same service, so only one
     * at one of this phone's own addresses counts.
     */
    private suspend fun discover(type: String, timeoutMs: Long): Int? = withTimeoutOrNull(timeoutMs) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val started = CompletableDeferred<Boolean>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { started.complete(true) }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { started.complete(false) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            if (!started.await()) return@withTimeoutOrNull null
            val mine = ownAddresses()
            while (true) {
                val resolved = resolve(nsd, found.receive()) ?: continue
                @Suppress("DEPRECATION") // The call that replaces it needs targetSdk 34.
                if (resolved.host in mine) return@withTimeoutOrNull resolved.port
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION") // The call that replaces it needs targetSdk 34.
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { waiter ->
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { if (waiter.isActive) waiter.resume(null) }
                override fun onServiceResolved(resolved: NsdServiceInfo) { if (waiter.isActive) waiter.resume(resolved) }
            },
        )
    }

    private fun ownAddresses(): Set<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
    }.getOrDefault(emptySet())

    private class KeyManager(private val key: PrivateKey, private val certificate: X509Certificate) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
    }

    /**
     * adbd's certificate is self-signed and names nothing to check. What proves the other end is the pairing code for a
     * pairing, and for a connection that it is on this phone's own loopback and accepted this key.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AnyServer : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val NOT_PAIRED = "This phone's shell has not been paired. The person pairs it on the Helper's Drive this phone page."
        const val WIRELESS_DEBUGGING_OFF =
            "Wireless debugging is off, or this phone is not on Wi-Fi. Android turns it off when Wi-Fi changes and after a restart. " +
                "Ask the person to turn it back on in Developer options; the pairing still holds."
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "phone-link"
        private const val KEY_PAIRED = "paired"
        private const val KEY_PORT = "port"
        private const val LOOPBACK = "127.0.0.1"
        private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
        private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private const val PAIR_TIMEOUT_MS = 20_000
        private const val DISCOVER_TIMEOUT_MS = 6_000L
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val OUTPUT_LIMIT = 8 * 1024 * 1024
        private const val RSA_BITS = 2048
        private const val DAY_MS = 86_400_000L
        private const val CERTIFICATE_DAYS = 3650L
    }
}
