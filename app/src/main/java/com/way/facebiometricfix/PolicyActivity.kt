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
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
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
    private lateinit var showSystemApps: CheckBox
    private lateinit var selectionView: TextView
    private lateinit var batchSpinner: Spinner
    private lateinit var batchApplyButton: Button

    private var allCandidates: List<BiometricCandidateApp> = emptyList()
    private var visibleCandidates: List<BiometricCandidateApp> = emptyList()
    private val selectedPackages = LinkedHashSet<String>()
    private var scanGeneration = 0

    private val serviceListener: (Boolean) -> Unit = {
        runOnUiThread {
            updateFrameworkStatus()
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
            updateSummary()
            updateSelectionUi()
        }
    }

    private val darkMode: Boolean
        get() =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = PolicyApplication.from(this)
        store = app.policyStore

        val surface = if (darkMode) Color.rgb(18, 18, 20) else Color.rgb(248, 249, 252)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = surface
        window.setDecorFitsSystemWindows(false)

        val content = buildContent()
        setContentView(content)
        installSystemBarInsets(content)

        app.addServiceListener(serviceListener)
        startScan()
    }

    override fun onDestroy() {
        app.removeServiceListener(serviceListener)
        super.onDestroy()
    }

    private fun installSystemBarInsets(root: View) {
        val baseLeft = dp(20)
        val baseTop = dp(18)
        val baseRight = dp(20)
        val baseBottom = dp(12)

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(
                baseLeft,
                baseTop + bars.top,
                baseRight,
                baseBottom + bars.bottom,
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun buildContent(): View {
        val background = if (darkMode) Color.rgb(18, 18, 20) else Color.rgb(248, 249, 252)
        val primaryText = if (darkMode) Color.rgb(240, 240, 245) else Color.rgb(24, 25, 28)
        val secondaryText = if (darkMode) Color.rgb(178, 179, 187) else Color.rgb(91, 94, 103)
        val panelColor = if (darkMode) Color.rgb(29, 30, 34) else Color.WHITE
        val panelStroke = if (darkMode) Color.rgb(57, 59, 66) else Color.rgb(218, 221, 228)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
            this.background = rounded(
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
            this.background = rounded(
                if (darkMode) Color.rgb(31, 32, 36) else Color.WHITE,
                14f,
                strokeColor = panelStroke,
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyFilter()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(
            searchBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) },
        )

        showSystemApps = CheckBox(this).apply {
            text = "显示系统应用"
            setTextColor(primaryText)
            textSize = 12f
            isChecked = false
            setOnCheckedChangeListener { _, _ -> applyFilter() }
        }
        root.addView(showSystemApps)

        val batchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            this.background = rounded(panelColor, 14f, panelStroke)
        }

        val selectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        selectionView = TextView(this).apply {
            setTextColor(primaryText)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        selectionRow.addView(
            selectionView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        fun compactButton(label: String, action: () -> Unit): Button =
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { action() }
            }

        selectionRow.addView(compactButton("全选当前") {
            visibleCandidates.forEach { selectedPackages += it.packageName }
            adapter.notifyDataSetChanged()
            updateSelectionUi()
        })
        selectionRow.addView(compactButton("反选") {
            visibleCandidates.forEach {
                if (!selectedPackages.remove(it.packageName)) {
                    selectedPackages += it.packageName
                }
            }
            adapter.notifyDataSetChanged()
            updateSelectionUi()
        })
        selectionRow.addView(compactButton("清空") {
            selectedPackages.clear()
            adapter.notifyDataSetChanged()
            updateSelectionUi()
        })
        batchPanel.addView(selectionRow)

        val batchActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val batchLabels = listOf(
            "人脸或指纹",
            "仅人脸",
            "仅指纹",
            "人脸 + 指纹",
        )
        batchSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PolicyActivity,
                android.R.layout.simple_spinner_item,
                batchLabels,
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        batchActionRow.addView(
            batchSpinner,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        batchApplyButton = Button(this).apply {
            text = "应用到已选"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { applyBatchPolicy() }
        }
        batchActionRow.addView(batchApplyButton)
        batchPanel.addView(batchActionRow)

        root.addView(
            batchPanel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            },
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
            adapter = this@PolicyActivity.adapter
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
        updateSelectionUi()
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
                selectedPackages.retainAll(result.mapTo(HashSet()) { it.packageName })
                scanButton.isEnabled = true
                applyFilter()
                updateSummary()
                updateSelectionUi()
            }
        }, "FaceBiometricFix-app-scan").start()
    }

    private fun applyFilter() {
        if (!::adapter.isInitialized || !::searchBox.isInitialized || !::showSystemApps.isInitialized) return

        val query = searchBox.text?.toString().orEmpty()
            .trim()
            .lowercase(Locale.getDefault())

        visibleCandidates = allCandidates.filter { candidate ->
            val systemMatch = showSystemApps.isChecked || !candidate.isSystemApp
            val queryMatch =
                query.isBlank() ||
                    candidate.label.lowercase(Locale.getDefault()).contains(query) ||
                    candidate.packageName.lowercase(Locale.ROOT).contains(query)
            systemMatch && queryMatch
        }

        adapter.submit(visibleCandidates)
        updateSummary()
        updateSelectionUi()
    }

    private fun applyBatchPolicy() {
        if (selectedPackages.isEmpty()) return

        val mode =
            when (batchSpinner.selectedItemPosition) {
                1 -> BiometricPolicyMode.FACE_ONLY
                2 -> BiometricPolicyMode.FINGERPRINT_ONLY
                3 -> BiometricPolicyMode.FACE_AND_FINGERPRINT
                else -> BiometricPolicyMode.ANY
            }

        store.setModes(selectedPackages, mode)
        adapter.notifyDataSetChanged()
        updateSummary()
        updateSelectionUi()
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
            "候选 ${allCandidates.size} · 当前 ${visibleCandidates.size} · 已配置 ${store.configuredCount()}"
    }

    private fun updateSelectionUi() {
        if (!::selectionView.isInitialized || !::batchApplyButton.isInitialized) return
        val visibleSelected = visibleCandidates.count { it.packageName in selectedPackages }
        selectionView.text =
            if (selectedPackages.isEmpty()) {
                "批量设置 · 未选择"
            } else if (visibleSelected == selectedPackages.size) {
                "已选择 ${selectedPackages.size}"
            } else {
                "已选择 ${selectedPackages.size} · 当前列表中 $visibleSelected"
            }
        batchApplyButton.isEnabled = selectedPackages.isNotEmpty()
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
                this.background = rounded(cardColor, 18f)
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
                setPadding(dp(12), 0, dp(8), 0)
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

            val select = CheckBox(this@PolicyActivity).apply {
                contentDescription = "选择应用"
            }
            top.addView(select)
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
                select = select,
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

            holder.select.setOnCheckedChangeListener(null)
            holder.select.isChecked = item.packageName in selectedPackages
            holder.select.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedPackages += item.packageName
                } else {
                    selectedPackages -= item.packageName
                }
                updateSelectionUi()
            }

            holder.root.setOnLongClickListener {
                holder.select.isChecked = !holder.select.isChecked
                true
            }

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
        val select: CheckBox,
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
