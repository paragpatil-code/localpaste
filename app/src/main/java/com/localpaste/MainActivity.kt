package com.localpaste

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.util.Date

class MainActivity : Activity() {

    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var copyUrl: Button
    private lateinit var input: EditText
    private lateinit var pasteList: LinearLayout
    private lateinit var empty: TextView
    private lateinit var clearAll: Button

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var wifiNetwork: Network? = null
    private var wifiIp: String? = null
    private var qrFor: String? = null
    private var localNetworkDenied = false

    // Tracks the Wi-Fi network directly (not just the default network), so a Wi-Fi network
    // without internet access still counts — the phone may have switched its default to mobile data.
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            wifiNetwork = network
            wifiIp = lp.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
            render()
        }

        override fun onLost(network: Network) {
            if (network != wifiNetwork) return
            wifiNetwork = null
            wifiIp = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IdleShutdown.start(this)
        IdleShutdown.activity = this
        setContentView(R.layout.activity_main)
        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        qr = findViewById(R.id.qr)
        copyUrl = findViewById(R.id.copy_url)
        input = findViewById(R.id.input)
        pasteList = findViewById(R.id.pastes)
        empty = findViewById(R.id.empty)
        clearAll = findViewById(R.id.clear_all)

        applySystemBarInsets(findViewById(R.id.root))
        toggle.setOnClickListener { onToggle() }
        copyUrl.setOnClickListener { ServerService.url?.let { copyToClipboard("URL", it) } }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        clearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete all pastes?")
                .setPositiveButton("Clear all") { _, _ -> PasteStore.clear() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, wifiCallback, Handler(mainLooper))
        ServerService.listener = ::render
        PasteStore.listener = ::renderPastes
        render()
        renderPastes()
    }

    override fun onStop() {
        connectivity.unregisterNetworkCallback(wifiCallback)
        ServerService.listener = null
        PasteStore.listener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        IdleShutdown.touch()
    }

    override fun onDestroy() {
        if (IdleShutdown.activity === this) IdleShutdown.activity = null
        super.onDestroy()
    }

    // Any touch or key press in the app counts as activity for the idle shutdown.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        IdleShutdown.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        IdleShutdown.touch()
        return super.dispatchKeyEvent(event)
    }

    private fun onToggle() {
        if (ServerService.url != null) {
            stopService(Intent(this, ServerService::class.java))
            return
        }
        if (wifiIp == null) {
            Toast.makeText(this, "Connect to Wi-Fi first", Toast.LENGTH_SHORT).show()
            return
        }
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= SDK_LOCAL_NETWORK) add(PERMISSION_LOCAL_NETWORK)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 0)
            return
        }
        startServer()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        // Notifications are optional (the notification is just hidden); local network access is not.
        localNetworkDenied = Build.VERSION.SDK_INT >= SDK_LOCAL_NETWORK &&
            checkSelfPermission(PERMISSION_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (localNetworkDenied) render() else startServer()
    }

    private fun startServer() {
        val ip = wifiIp ?: return render()
        localNetworkDenied = false
        startForegroundService(Intent(this, ServerService::class.java).putExtra(ServerService.EXTRA_IP, ip))
    }

    private fun render() {
        val url = ServerService.url
        val on = url != null
        toggle.text = if (on) "ON" else "OFF"
        toggle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF2E7D32.toInt() else 0xFF757575.toInt())
        }
        status.text = when {
            on -> url
            wifiIp == null -> "Not connected to Wi-Fi.\nConnect to a Wi-Fi network to start the server."
            localNetworkDenied -> "Server stopped.\nOther devices can't connect without the \"Nearby devices\" " +
                "(local network) permission. Allow it, or enable it in Settings › Apps › LocalPaste › Permissions."
            ServerService.lastError != null -> "Server stopped (${ServerService.lastError})"
            else -> "Server stopped"
        }
        qr.visibility = if (on) View.VISIBLE else View.GONE
        copyUrl.visibility = qr.visibility
        if (url != null && url != qrFor) {
            qr.setImageBitmap(qrBitmap(url))
            qrFor = url
        }
    }

    private fun save() {
        val text = input.text.toString()
        if (text.isBlank()) return
        PasteStore.add(text)
        input.text.clear()
    }

    /** Rebuilds the list on every change; at most [PasteStore.MAX_PASTES] small rows. */
    private fun renderPastes() {
        val pastes = PasteStore.all()
        empty.visibility = if (pastes.isEmpty()) View.VISIBLE else View.GONE
        clearAll.isEnabled = pastes.isNotEmpty()
        pasteList.removeAllViews()
        val timeFormat = DateFormat.getTimeFormat(this)
        for (p in pastes) {
            val row = layoutInflater.inflate(R.layout.item_paste, pasteList, false)
            row.findViewById<TextView>(R.id.text).text = p.text
            row.findViewById<TextView>(R.id.time).text = timeFormat.format(Date(p.time))
            row.findViewById<Button>(R.id.copy).setOnClickListener { copyToClipboard("Paste", p.text) }
            row.findViewById<Button>(R.id.delete).setOnClickListener { PasteStore.delete(p.id) }
            pasteList.addView(row)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun qrBitmap(text: String, size: Int = 512): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** targetSdk 35+ is always edge-to-edge, so keep content clear of the system bars and keyboard. */
    private fun applySystemBarInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val base = root.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }
    }

    private companion object {
        /** Android 17 (API 37) blocks LAN traffic unless the app holds ACCESS_LOCAL_NETWORK. */
        const val SDK_LOCAL_NETWORK = 37
        const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
