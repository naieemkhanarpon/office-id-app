package com.officeid.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject

/**
 * Office ID - Android shell for the idcard PHP site.
 * - loads your server in a WebView (URL asked on first start)
 * - native NFC (read UID + NDEF, write URL) exposed to the page as NativeBridge
 * - camera for QR scan, file chooser for photos, CSV downloads, printing
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var nfc: NfcAdapter? = null

    @Volatile private var nfcMode = "off"      // off | read | write | uid
    @Volatile private var nfcPayload = ""

    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var permReq: PermissionRequest? = null

    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        setContentView(web)
        nfc = NfcAdapter.getDefaultAdapter(this)
        setupWeb()
        if (Build.VERSION.SDK_INT < 29) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 11)
        }
        val url = prefs.getString("url", null)
        if (url.isNullOrEmpty()) askUrl(true) else web.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.setSupportZoom(false)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.addJavascriptInterface(Bridge(), "NativeBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript("window.print=function(){NativeBridge.printPage()};", null)
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val base = Uri.parse(prefs.getString("url", "") ?: "")
                if (u.host == base.host) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                }
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    view?.loadDataWithBaseURL(null, errHtml(), "text/html", "utf-8", null)
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val r = request ?: return
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        r.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        permReq = r
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), 12)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams?.createIntent(), 13)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val et = EditText(this@MainActivity)
                et.setText(defaultValue ?: "")
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setView(et)
                    .setPositiveButton("OK") { _, _ -> result?.confirm(et.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url))
                req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                req.addRequestHeader("User-Agent", userAgent)
                req.setTitle(name)
                req.setMimeType(mimeType)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Downloading: $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- NFC ----------------

    private fun applyNfc() {
        val a = nfc ?: return
        try {
            if (nfcMode == "off") {
                a.disableReaderMode(this)
            } else {
                a.enableReaderMode(
                    this,
                    NfcAdapter.ReaderCallback { tag -> onTag(tag) },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                    null
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun onTag(tag: Tag) {
        val uid = tag.id.joinToString("") { String.format("%02X", it) }
        val mode = nfcMode
        var text = ""
        var err = ""
        if (mode == "write") err = writeUrl(tag, nfcPayload) else text = readText(tag)
        vibrate()
        val js = "window.__nfc&&window.__nfc(" + JSONObject.quote(mode) + "," +
            JSONObject.quote(uid) + "," + JSONObject.quote(text) + "," + JSONObject.quote(err) + ")"
        web.post { web.evaluateJavascript(js, null) }
    }

    private fun readText(tag: Tag): String {
        val ndef = Ndef.get(tag) ?: return ""
        var msg: NdefMessage? = ndef.cachedNdefMessage
        if (msg == null) {
            try {
                ndef.connect()
                msg = ndef.ndefMessage
            } catch (e: Exception) {
            } finally {
                try {
                    ndef.close()
                } catch (e: Exception) {
                }
            }
        }
        val recs = msg?.records ?: return ""
        for (r in recs) {
            val u = r.toUri()
            if (u != null) return u.toString()
            if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) && r.payload.isNotEmpty()) {
                val p = r.payload
                val l = p[0].toInt() and 0x3F
                return String(p, 1 + l, p.size - 1 - l, Charsets.UTF_8)
            }
        }
        return ""
    }

    /** returns "" on success, otherwise an error message */
    private fun writeUrl(tag: Tag, url: String): String {
        val msg = NdefMessage(arrayOf(NdefRecord.createUri(url)))
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) {
                    ndef.close()
                    return "Tag is read-only"
                }
                if (ndef.maxSize < msg.byteArrayLength) {
                    ndef.close()
                    return "Tag too small"
                }
                ndef.writeNdefMessage(msg)
                ndef.close()
                return ""
            }
            val f = NdefFormatable.get(tag) ?: return "Tag does not support NDEF"
            f.connect()
            f.format(msg)
            f.close()
            return ""
        } catch (e: Exception) {
            return e.message ?: "Write failed"
        }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    // ---------------- JS bridge ----------------

    inner class Bridge {
        @JavascriptInterface
        fun isApp(): Boolean = true

        @JavascriptInterface
        fun hasNfc(): Boolean = nfc != null

        @JavascriptInterface
        fun nfcEnabled(): Boolean = nfc?.isEnabled == true

        @JavascriptInterface
        fun setNfcMode(mode: String, payload: String) {
            runOnUiThread {
                nfcMode = mode
                nfcPayload = payload
                applyNfc()
            }
        }

        @JavascriptInterface
        fun printPage() {
            runOnUiThread {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print("Office ID", web.createPrintDocumentAdapter("Office ID"), PrintAttributes.Builder().build())
            }
        }

        @JavascriptInterface
        fun changeServer() {
            runOnUiThread { askUrl(false) }
        }

        @JavascriptInterface
        fun reload() {
            runOnUiThread {
                val u = prefs.getString("url", null)
                if (!u.isNullOrEmpty()) web.loadUrl(u)
            }
        }
    }

    // ---------------- misc ----------------

    private fun askUrl(first: Boolean) {
        val et = EditText(this)
        et.setSingleLine()
        et.setText(prefs.getString("url", "https://"))
        AlertDialog.Builder(this)
            .setTitle("Server URL")
            .setMessage("e.g. https://yourdomain.com/idcard/")
            .setView(et)
            .setCancelable(!first)
            .setPositiveButton("OK") { _, _ ->
                var u = et.text.toString().trim()
                if (u.isEmpty()) {
                    if (first) askUrl(true)
                } else {
                    if (!u.startsWith("http")) u = "https://$u"
                    prefs.edit().putString("url", u).apply()
                    web.loadUrl(u)
                }
            }
            .show()
    }

    private fun errHtml(): String =
        """<html><body style="background:#0b1020;color:#ececf4;font-family:sans-serif;text-align:center;padding:40px 20px"><h2>Cannot reach the server</h2><p style="color:#9a9ab0">Check internet and server URL</p><button style="padding:14px 22px;border:0;border-radius:12px;background:#3b82f6;color:#fff;font-size:16px;margin:6px" onclick="NativeBridge.reload()">Retry</button><br><button style="padding:14px 22px;border:0;border-radius:12px;background:#26263a;color:#fff;font-size:16px;margin:6px" onclick="NativeBridge.changeServer()">Change server</button></body></html>"""

    override fun onResume() {
        super.onResume()
        applyNfc()
    }

    override fun onPause() {
        super.onPause()
        try {
            nfc?.disableReaderMode(this)
        } catch (e: Exception) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 13) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 12) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val r = permReq
            permReq = null
            if (ok) r?.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else r?.deny()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
