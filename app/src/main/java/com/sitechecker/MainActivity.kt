package com.sitechecker

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

data class SiteResult(val code: Int, val responseMs: Long)

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnCheck: Button
    private lateinit var btnAdd: Button
    private lateinit var btnTheme: ImageButton
    private lateinit var llResults: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var tvEmpty: TextView

    private val urlList = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isDarkMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl      = findViewById(R.id.etUrl)
        btnCheck   = findViewById(R.id.btnCheck)
        btnAdd     = findViewById(R.id.btnAdd)
        btnTheme   = findViewById(R.id.btnTheme)
        llResults  = findViewById(R.id.llResults)
        scrollView = findViewById(R.id.scrollView)
        tvEmpty    = findViewById(R.id.tvEmpty)

        // Load saved sites
        val prefs = getSharedPreferences("sitechecker", MODE_PRIVATE)
        val saved = prefs.getStringSet("urls", null)
        if (saved != null && saved.isNotEmpty()) {
            urlList.addAll(saved.toSortedSet())
        } else {
            urlList.addAll(listOf(
                "https://telegram.org",
                "https://youtube.com",
                "https://instagram.com",
                "https://whatsapp.com",
                "https://chatgpt.com",
                "https://gemini.google.com",
                "https://x.ai"
            ))
        }
        refreshList()

        // Move cursor to end of https://
        etUrl.setSelection(etUrl.text.length)

        btnAdd.setOnClickListener {
            val raw = etUrl.text.toString().trim()
            if (raw.isEmpty() || raw == "https://" || raw == "http://") {
                etUrl.error = "Введите адрес сайта"
                return@setOnClickListener
            }
            val url = when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> "https://$raw"
            }
            if (urlList.contains(url)) {
                Toast.makeText(this, "Уже добавлено", Toast.LENGTH_SHORT).show()
            } else {
                urlList.add(url)
                etUrl.setText("https://")
                etUrl.setSelection(etUrl.text.length)
                saveSites()
                refreshList()
            }
        }

        btnCheck.setOnClickListener { checkAllSites() }

        btnTheme.setOnClickListener {
            isDarkMode = !isDarkMode
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun saveSites() {
        getSharedPreferences("sitechecker", MODE_PRIVATE)
            .edit().putStringSet("urls", urlList.toSet()).apply()
    }

    private fun refreshList() {
        llResults.removeAllViews()
        if (urlList.isEmpty()) { tvEmpty.visibility = View.VISIBLE; return }
        tvEmpty.visibility = View.GONE

        for (url in urlList) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_site, llResults, false)
            val tvUrl      = v.findViewById<TextView>(R.id.tvUrl)
            val tvStatus   = v.findViewById<TextView>(R.id.tvStatus)
            val tvPing     = v.findViewById<TextView>(R.id.tvPing)
            val ivIndicator= v.findViewById<View>(R.id.ivIndicator)
            val progressBar= v.findViewById<ProgressBar>(R.id.progressBar)
            val btnEdit    = v.findViewById<ImageButton>(R.id.btnEdit)
            val btnRemove  = v.findViewById<ImageButton>(R.id.btnRemove)

            // Show just the domain part as name, full url as subtitle
            val displayName = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
            tvUrl.text = displayName
            tvStatus.text = "Не проверено"
            tvPing.visibility = View.GONE
            progressBar.visibility = View.GONE
            ivIndicator.background = ContextCompat.getDrawable(this, R.drawable.ic_circle)
            ivIndicator.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_unknown)
            v.tag = url

            btnRemove.setOnClickListener {
                urlList.remove(url); saveSites(); refreshList()
            }
            btnEdit.setOnClickListener { showEditDialog(url) }
            llResults.addView(v)
        }
    }

    private fun showEditDialog(oldUrl: String) {
        val et = EditText(this).apply {
            setText(oldUrl)
            setPadding(48, 32, 48, 32)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Редактировать сайт")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                val raw = et.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val newUrl = if (!raw.startsWith("http")) "https://$raw" else raw
                val idx = urlList.indexOf(oldUrl)
                if (idx >= 0) { urlList[idx] = newUrl; saveSites(); refreshList() }
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun checkAllSites() {
        if (urlList.isEmpty()) {
            Toast.makeText(this, "Добавьте хотя бы один сайт", Toast.LENGTH_SHORT).show()
            return
        }
        btnCheck.isEnabled = false
        btnCheck.text = "Проверяю..."
        scope.launch {
            urlList.map { url -> async { checkSite(url) } }.awaitAll()
            btnCheck.isEnabled = true
            btnCheck.text = "▶  Проверить все"
        }
    }

    private suspend fun checkSite(url: String) {
        val v           = llResults.findViewWithTag<View>(url) ?: return
        val tvStatus    = v.findViewById<TextView>(R.id.tvStatus)
        val tvPing      = v.findViewById<TextView>(R.id.tvPing)
        val ivIndicator = v.findViewById<View>(R.id.ivIndicator)
        val progressBar = v.findViewById<ProgressBar>(R.id.progressBar)

        withContext(Dispatchers.Main) {
            progressBar.visibility = View.VISIBLE
            tvPing.visibility = View.GONE
            tvStatus.text = "Проверяю..."
            ivIndicator.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_unknown)
        }

        val result = withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = true
                conn.connect()
                val code = conn.responseCode
                val ms = System.currentTimeMillis() - start
                conn.disconnect()
                SiteResult(code, ms)
            } catch (e: Exception) {
                SiteResult(-1, -1)
            }
        }

        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE

            if (result.responseMs >= 0) {
                tvPing.visibility = View.VISIBLE
                tvPing.text = "${result.responseMs} ms"
                tvPing.setBackgroundResource(when {
                    result.responseMs < 300  -> R.drawable.bg_ping_good
                    result.responseMs < 1000 -> R.drawable.bg_ping_ok
                    else                     -> R.drawable.bg_ping_slow
                })
                tvPing.setTextColor(ContextCompat.getColor(this@MainActivity, when {
                    result.responseMs < 300  -> R.color.ping_text_good
                    result.responseMs < 1000 -> R.color.ping_text_ok
                    else                     -> R.color.ping_text_slow
                }))
            }

            when {
                result.code in 200..399 -> {
                    tvStatus.text = "Доступен · HTTP ${result.code}"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_ok))
                    ivIndicator.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_ok)
                }
                result.code in 400..599 -> {
                    tvStatus.text = "Ошибка · HTTP ${result.code}"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_warn))
                    ivIndicator.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_warn)
                }
                else -> {
                    tvStatus.text = "Недоступен"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                    ivIndicator.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_error)
                }
            }
            v.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.fade_in))
        }
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
