package io.nebula.mobile.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import io.nebula.mobile.AddonRow
import io.nebula.mobile.PluginManager
import io.nebula.mobile.theme.Palette

/** RecyclerView adapter used for both the plugin and theme lists. */
class AddonAdapter(
    private val palette: Palette,
    private val isTheme: Boolean,
    private val isChecked: (AddonRow) -> Boolean,
    private val onToggle: (AddonRow, Boolean) -> Unit,
    private val onClick: (AddonRow) -> Unit,
) : ListAdapter<AddonRow, AddonAdapter.Holder>(DIFF) {

    class Holder(
        val card: MaterialCardView,
        val title: TextView,
        val subtitle: TextView,
        val description: TextView,
        val status: TextView,
        val toggle: MaterialSwitch,
    ) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val card = MaterialCardView(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(12), dp(6), dp(12), dp(6))
            }
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = palette.border
            setCardBackgroundColor(palette.surface)
            isClickable = true
            isFocusable = true
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.text)
        }
        val toggle = MaterialSwitch(context).apply {
            setPadding(0, 0, 0, 0)
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val subtitle = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.textMuted)
            setPadding(0, dp(2), 0, 0)
        }
        val description = TextView(context).apply {
            textSize = 13f
            maxLines = 2
            setTextColor(palette.textMuted)
            setPadding(0, dp(6), 0, 0)
        }
        val status = TextView(context).apply {
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
            visibility = TextView.GONE
        }

        content.addView(header)
        content.addView(subtitle)
        content.addView(description)
        content.addView(status)
        card.addView(content)

        return Holder(card, title, subtitle, description, status, toggle)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val addon = row.addon
        holder.title.text = addon.name
        holder.subtitle.text = PluginManager.subtitle(addon)
        holder.description.text = addon.description.ifBlank {
            if (isTheme) "No description provided" else "No description provided"
        }
        holder.description.visibility = if (addon.description.isBlank()) TextView.GONE else TextView.VISIBLE

        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = isChecked(row)
        holder.toggle.setOnCheckedChangeListener { _, checked -> onToggle(row, checked) }

        when {
            row.error != null -> {
                holder.status.visibility = TextView.VISIBLE
                holder.status.setTextColor(if (palette.isDark) 0xFFFF8A80.toInt() else 0xFFB3261E.toInt())
                holder.status.text = "Error: ${row.error}"
            }
            isTheme && isChecked(row) -> {
                holder.status.visibility = TextView.VISIBLE
                holder.status.setTextColor(palette.accent)
                holder.status.text = "Applied"
            }
            !isTheme && row.running -> {
                holder.status.visibility = TextView.VISIBLE
                holder.status.setTextColor(palette.accent)
                holder.status.text = "Running"
            }
            !isTheme && row.enabled && !row.running -> {
                holder.status.visibility = TextView.VISIBLE
                holder.status.setTextColor(palette.textMuted)
                holder.status.text = "Enabled (not running)"
            }
            else -> holder.status.visibility = TextView.GONE
        }

        holder.card.strokeColor = if (isChecked(row)) {
            ColorUtils.setAlphaComponent(palette.accent, 160)
        } else {
            palette.border
        }
        holder.card.setOnClickListener { onClick(row) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AddonRow>() {
            override fun areItemsTheSame(oldItem: AddonRow, newItem: AddonRow): Boolean =
                oldItem.addon.id == newItem.addon.id

            override fun areContentsTheSame(oldItem: AddonRow, newItem: AddonRow): Boolean =
                oldItem == newItem
        }
    }
}
