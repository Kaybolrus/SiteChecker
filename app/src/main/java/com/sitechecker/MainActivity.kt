package com.sitechecker

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

data class SiteResult(val code: Int, val responseMs: Long)

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnCheck: Button
    private lateinit var btnAdd: Button
    private lateinit var llResults: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var tvEmpty: TextView

    private val urlList = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl       = findViewById(R.id.etUrl)
        btnCheck    = findViewById(R.id.btnCheck)
        btnAdd      = findViewById(R.id.btnAdd)
        llResults   = findViewById(R.id.llResults)
        scrollView  = findViewById(R.id.scrollView)
        tvEmpty     = findViewById(R.id.tvEmpty)

        urlList.addAll(listOf(
            "https://telegram.org",
            "https://youtube.com",
            "https://instagram.com",
            "https://whatsapp.com",
            "https://chatgpt.com",
            "https://gemini.google.com",
            "https://x.ai"
        ))
        refreshList()

        btnAdd.setOnClickListener {
            val raw = etUrl.text.toString().trim()
            if (raw.isEmpty()) { etUrl.error = "Введите URL"; return@setOnClickListener }
            val url = if (!raw.startsWith("http")) "https://$raw" else raw
            if (urlList.contains(url)) {
                Toast.makeText(this, "Уже добавлено", Toast.LENGTH_SHORT).show()
            } else {
                urlList.add(url); etUrl.setText(""); refreshList()
            }
        }

        btnCheck.setOnClickListener { checkAllSites() }
    }

    private fun refreshList() {
        llResults.removeAllViews()
        if (urlList.isEmpty()) { tvEmpty.visibility = View.VISIBLE; return }
        tvEmpty.visibility = View.GONE

        for (url in urlList) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_site, llResults, false)
            v.findViewById<TextView>(R.id.tvUrl).text = url
            v.findViewById<TextView>(R.id.tvStatus).apply {
                text = "Не проверено"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_unknown))
            }
            v.findViewById<TextView>(R.id.tvPing).visibility = View.GONE
            v.findViewById<ImageView>(R.id.ivIndicator)
                .setColorFilter(ContextCompat.getColor(this, R.color.status_unknown))
            v.findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
            v.tag = url

            v.findViewById<ImageButton>(R.id.btnRemove).setOnClickListener {
                urlList.remove(url); refreshList()
            }
            v.findViewById<ImageButton>(R.id.btnEdit).setOnClickListener {
                showEditDialog(url)
            }
            llResults.addView(v)
        }
    }

    private fun showEditDialog(oldUrl: String) {
        val et = EditText(this).apply {
            setText(oldUrl)
            setPadding(48, 32, 48, 32)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Редактировать")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                val raw = et.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val newUrl = if (!raw.startsWith("http")) "https://$raw" else raw
                val idx = urlList.indexOf(oldUrl)
                if (idx >= 0) { urlList[idx] = newUrl; refreshList() }
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        val ivIndicator = v.findViewById<ImageView>(R.id.ivIndicator)
        val progressBar = v.findViewById<ProgressBar>(R.id.progressBar)

        withContext(Dispatchers.Main) {
            progressBar.visibility = View.VISIBLE
            tvPing.visibility = View.GONE
            tvStatus.text = "Проверяю..."
            tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_unknown))
            ivIndicator.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.status_unknown))
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
            }

            when {
                result.code in 200..399 -> {
                    tvStatus.text = "✓ Доступен  ·  HTTP ${result.code}"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_ok))
                    ivIndicator.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.status_ok))
                }
                result.code in 400..599 -> {
                    tvStatus.text = "⚠ Ошибка  ·  HTTP ${result.code}"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_warn))
                    ivIndicator.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.status_warn))
                }
                else -> {
                    tvStatus.text = "✗ Недоступен"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                    ivIndicator.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                }
            }
            v.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.fade_in))
        }
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
