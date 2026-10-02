package com.way.facebiometricfix

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.util.Locale

class PolicyActivity : Activity() {
    private lateinit var app: PolicyApplication
    private lateinit var store: PolicyStore
    private lateinit var adapter: CandidateAdapter
    private lateinit var statusView: TextView
    private lateinit var summaryView: TextView
    private lateinit var scanButton: Button
    private lateinit var searchBox: EditText

    private var allCandidates: List<BiometricCandidateApp> = emptyList()
    private var scanGeneration = 0

    private val serviceListener: (Boolean) -> Unit = {
        runOnUiThread { updateFrameworkStatus() }
    }

    private val darkMode: Boolean
        get() =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = PolicyApplication.from(this)
        store = app.policyStore

        window.statusBarColor = if (darkMode) Color.rgb(18, 18, 20) else Color.rgb(248, 249, 252)
        window.navigationBarColor =
            if (darkMode) Color.rgb(18, 18, 20) else Color.rgb(248, 249, 252)

        setContentView(buildContent())
        app.addServiceListener(serviceListener)
        startScan()
    }

    override fun onDestroy() {
        app.removeServiceListener(serviceListener)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val background = if (darkMode) Color.rgb(18, 18, 20) else Color.rgb(248, 249, 252)
        val primaryText = if (darkMode) Color.rgb(240, 240, 245) else Color.rgb(24, 25, 28)
        val secondaryText = if (darkMode) Color.rgb(178, 179, 187) else Color.rgb(91, 94, 103)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(background)
        }

        root.addView(TextView(this).apply {
            text = "生物认证策略"
            setTextColor(primaryText)
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "扫描使用系统生物识别权限的应用，并为每个应用选择认证方式。"
            setTextColor(secondaryText)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        })

        statusView = TextView(this).apply {
            setTextColor(secondaryText)
            textSize = 12f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(
                if (darkMode) Color.rgb(38, 39, 44) else Color.rgb(235, 238, 244),
                12f,
            )
        }
        root.addView(statusView)

        val scanRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }

        summaryView = TextView(this).apply {
            setTextColor(primaryText)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        scanRow.addView(
            summaryView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        scanButton = Button(this).apply {
            text = "重新扫描"
            isAllCaps = false
            setOnClickListener { startScan() }
        }
        scanRow.addView(scanButton)
        root.addView(scanRow)

        searchBox = EditText(this).apply {
            hint = "搜索应用或包名"
            isSingleLine = true
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(
                if (darkMode) Color.rgb(31, 32, 36) else Color.WHITE,
                14f,
                strokeColor = if (darkMode) Color.rgb(64, 65, 72) else Color.rgb(218, 221, 228),
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyFilter(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(
            searchBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) },
        )

        root.addView(TextView(this).apply {
            text = "检测依据是应用声明的 USE_BIOMETRIC / USE_FINGERPRINT 权限；它表示应用具备调用生物识别的能力，不代表应用一定已开启内部锁。"
            setTextColor(secondaryText)
            textSize = 11f
            setPadding(dp(2), 0, dp(2), dp(10))
        })

        adapter = CandidateAdapter()
        val list = ListView(this).apply {
            divider = null
            dividerHeight = dp(8)
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
            this.adapter = this@PolicyActivity.adapter
        }
        root.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        updateFrameworkStatus()
        updateSummary()
        return root
    }

    private fun startScan() {
        val generation = ++scanGeneration
        scanButton.isEnabled = false
        summaryView.text = "正在扫描…"

        Thread({
            val result = runCatching { BiometricAppScanner.scan(this) }.getOrElse { emptyList() }
            runOnUiThread {
                if (generation != scanGeneration) return@runOnUiThread
                allCandidates = result
                scanButton.isEnabled = true
                applyFilter(searchBox.text?.toString().orEmpty())
                updateSummary()
            }
        }, "FaceBiometricFix-app-scan").start()
    }

    private fun applyFilter(rawQuery: String) {
        if (!::adapter.isInitialized) return
        val query = rawQuery.trim().lowercase(Locale.getDefault())
        val filtered =
            if (query.isBlank()) {
                allCandidates
            } else {
                allCandidates.filter {
                    it.label.lowercase(Locale.getDefault()).contains(query) ||
                        it.packageName.lowercase(Locale.ROOT).contains(query)
                }
            }
        adapter.submit(filtered)
    }

    private fun updateFrameworkStatus() {
        if (!::statusView.isInitialized) return
        statusView.text =
            if (app.isFrameworkConnected()) {
                "LSPosed 配置通道已连接 · 修改对新认证会话实时生效"
            } else {
                "正在等待 LSPosed 配置通道 · 当前修改会先保存在本机"
            }
    }

    private fun updateSummary() {
        if (!::summaryView.isInitialized) return
        summaryView.text =
            "候选应用 ${allCandidates.size} · 已配置 ${store.configuredCount()}"
    }

    private inner class CandidateAdapter : BaseAdapter() {
        private var items: List<BiometricCandidateApp> = emptyList()

        fun submit(newItems: List<BiometricCandidateApp>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): BiometricCandidateApp = items[position]
        override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder =
                if (convertView?.tag is RowHolder) {
                    convertView.tag as RowHolder
                } else {
                    createRow().also { it.root.tag = it }
                }

            bindRow(holder, getItem(position))
            return holder.root
        }

        private fun createRow(): RowHolder {
            val primaryText = if (darkMode) Color.rgb(240, 240, 245) else Color.rgb(24, 25, 28)
            val secondaryText = if (darkMode) Color.rgb(174, 176, 184) else Color.rgb(92, 95, 104)
            val cardColor = if (darkMode) Color.rgb(29, 30, 34) else Color.WHITE

            val root = LinearLayout(this@PolicyActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(12))
                background = rounded(cardColor, 18f)
            }

            val top = LinearLayout(this@PolicyActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val icon = ImageView(this@PolicyActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            top.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))

            val textColumn = LinearLayout(this@PolicyActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

            val label = TextView(this@PolicyActivity).apply {
                setTextColor(primaryText)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }
            val packageName = TextView(this@PolicyActivity).apply {
                setTextColor(secondaryText)
                textSize = 11f
                maxLines = 1
            }
            val evidence = TextView(this@PolicyActivity).apply {
                setTextColor(secondaryText)
                textSize = 11f
                maxLines = 1
            }

            textColumn.addView(label)
            textColumn.addView(packageName)
            textColumn.addView(evidence)
            top.addView(
                textColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            root.addView(top)

            val current = TextView(this@PolicyActivity).apply {
                setTextColor(secondaryText)
                textSize = 12f
                setPadding(0, dp(10), 0, dp(4))
            }
            root.addView(current)

            val group = RadioGroup(this@PolicyActivity).apply {
                orientation = RadioGroup.HORIZONTAL
                gravity = Gravity.START
            }

            fun radio(text: String): RadioButton =
                RadioButton(this@PolicyActivity).apply {
                    this.text = text
                    setTextColor(primaryText)
                    textSize = 12f
                    id = View.generateViewId()
                    setPadding(0, 0, dp(8), 0)
                }

            val face = radio("仅人脸")
            val fingerprint = radio("仅指纹")
            val dual = radio("人脸 + 指纹")
            group.addView(face)
            group.addView(fingerprint)
            group.addView(dual)
            root.addView(group)

            val reset = Button(this@PolicyActivity).apply {
                text = "恢复为人脸或指纹"
                isAllCaps = false
                textSize = 11f
            }
            root.addView(
                reset,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            return RowHolder(
                root = root,
                icon = icon,
                label = label,
                packageName = packageName,
                evidence = evidence,
                current = current,
                group = group,
                face = face,
                fingerprint = fingerprint,
                dual = dual,
                reset = reset,
            )
        }

        private fun bindRow(holder: RowHolder, item: BiometricCandidateApp) {
            holder.icon.setImageDrawable(item.icon)
            holder.label.text = item.label
            holder.packageName.text = item.packageName
            holder.evidence.text =
                BiometricAppScanner.evidenceLabel(item) +
                    if (item.isSystemApp) " · 系统应用" else ""

            val mode = store.modeFor(item.packageName)
            holder.current.text = "当前：${modeLabel(mode)}"

            holder.group.setOnCheckedChangeListener(null)
            holder.group.clearCheck()
            when (mode) {
                BiometricPolicyMode.FACE_ONLY -> holder.group.check(holder.face.id)
                BiometricPolicyMode.FINGERPRINT_ONLY -> holder.group.check(holder.fingerprint.id)
                BiometricPolicyMode.FACE_AND_FINGERPRINT -> holder.group.check(holder.dual.id)
                BiometricPolicyMode.ANY -> Unit
            }

            holder.reset.visibility =
                if (mode == BiometricPolicyMode.ANY) View.GONE else View.VISIBLE

            holder.group.setOnCheckedChangeListener { _, checkedId ->
                val selected =
                    when (checkedId) {
                        holder.face.id -> BiometricPolicyMode.FACE_ONLY
                        holder.fingerprint.id -> BiometricPolicyMode.FINGERPRINT_ONLY
                        holder.dual.id -> BiometricPolicyMode.FACE_AND_FINGERPRINT
                        else -> return@setOnCheckedChangeListener
                    }
                store.setMode(item.packageName, selected)
                holder.current.text = "当前：${modeLabel(selected)}"
                holder.reset.visibility = View.VISIBLE
                updateSummary()
            }

            holder.reset.setOnClickListener {
                store.setMode(item.packageName, BiometricPolicyMode.ANY)
                holder.group.setOnCheckedChangeListener(null)
                holder.group.clearCheck()
                holder.current.text = "当前：${modeLabel(BiometricPolicyMode.ANY)}"
                holder.reset.visibility = View.GONE
                bindRow(holder, item)
                updateSummary()
            }
        }
    }

    private data class RowHolder(
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val packageName: TextView,
        val evidence: TextView,
        val current: TextView,
        val group: RadioGroup,
        val face: RadioButton,
        val fingerprint: RadioButton,
        val dual: RadioButton,
        val reset: Button,
    )

    private fun modeLabel(mode: BiometricPolicyMode): String =
        when (mode) {
            BiometricPolicyMode.ANY -> "人脸或指纹"
            BiometricPolicyMode.FACE_ONLY -> "仅人脸"
            BiometricPolicyMode.FINGERPRINT_ONLY -> "仅指纹"
            BiometricPolicyMode.FACE_AND_FINGERPRINT -> "人脸 + 指纹"
        }

    private fun rounded(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
