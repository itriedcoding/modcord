package io.nebula.mobile.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.nebula.mobile.Nebula
import io.nebula.mobile.R
import io.nebula.mobile.core.NebulaLog
import io.nebula.mobile.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConsoleFragment : Fragment(R.layout.fragment_console) {

    private lateinit var adapter: LogAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var count: TextView

    private val listener: (NebulaLog.Entry) -> Unit = { reload() }

    private val reload = {
        if (isAdded && ::adapter.isInitialized) {
            val entries = NebulaLog.entries()
            adapter.submitList(entries.toList())
            count.text = "${entries.size} entries"
            if (entries.isNotEmpty()) recycler.scrollToPosition(entries.size - 1)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = Nebula.get()
        recycler = view.findViewById(R.id.logs)
        count = view.findViewById(R.id.count)
        adapter = LogAdapter(app.palette())
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<MaterialButton>(R.id.clear).setOnClickListener {
            NebulaLog.clear()
            reload()
        }

        NebulaLog.addListener(listener)
        reload()
    }

    override fun onDestroyView() {
        NebulaLog.removeListener(listener)
        super.onDestroyView()
    }
}

class LogAdapter(private val palette: Palette) :
    ListAdapter<NebulaLog.Entry, LogAdapter.Holder>(DIFF) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val padding = (10 * density).toInt()
        val text = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setPadding(padding, (4 * density).toInt(), padding, (4 * density).toInt())
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return Holder(text)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        val colour = when (entry.level) {
            NebulaLog.Level.ERROR -> if (palette.isDark) 0xFFFF8A80.toInt() else 0xFFB3261E.toInt()
            NebulaLog.Level.WARN -> if (palette.isDark) 0xFFFFD180.toInt() else 0xFF8A5300.toInt()
            NebulaLog.Level.DEBUG -> palette.textMuted
            else -> palette.text
        }
        holder.text.setTextColor(colour)
        holder.text.text = "${timeFormat.format(Date(entry.timestamp))}  ${entry.level.name.padEnd(5)}  ${entry.scope}: ${entry.message}"
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NebulaLog.Entry>() {
            override fun areItemsTheSame(oldItem: NebulaLog.Entry, newItem: NebulaLog.Entry): Boolean =
                oldItem.timestamp == newItem.timestamp &&
                    oldItem.scope == newItem.scope &&
                    oldItem.message == newItem.message

            override fun areContentsTheSame(oldItem: NebulaLog.Entry, newItem: NebulaLog.Entry): Boolean =
                oldItem == newItem
        }
    }
}

class AboutFragment : Fragment(R.layout.fragment_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = Nebula.get()
        val palette = app.palette()
        val plugins = app.plugins.rows(io.nebula.mobile.core.AddonType.PLUGIN)
        val themes = app.plugins.rows(io.nebula.mobile.core.AddonType.THEME)
        val text = view.findViewById<TextView>(R.id.about)
        text.setTextColor(palette.text)
        text.text = """
            Nebula ${io.nebula.mobile.BuildConfig.VERSION_NAME}
            Android client-mod host for Discord addons

            INSTALLED
            ${plugins.size} plugins · ${plugins.count { it.enabled }} enabled
            ${themes.size} themes · active: ${app.activeThemeId() ?: "default"}

            WHAT THIS DOES
            • Runs BetterDiscord-format plugins (.plugin.js) in a sandboxed JavaScript
              engine, one isolated scope per plugin, with a real BdApi implementation.
            • Applies BetterDiscord-format themes (.theme.css) by resolving the Discord
              CSS custom properties they declare and painting this app with them.
            • Exposes Data, Patcher, Events, UI, Net, Plugins, Themes and a native
              settings screen to plugin code.

            COMPATIBILITY, HONESTLY
            Plugins that only use Data, Patcher, Events, Logger, UI, Net and Settings run
            here as-is. Plugins that reach into Discord through BdApi.Webpack, BdApi.DOM,
            BdApi.React, BdApi.Hooks, BdApi.ContextMenu or BdApi.Components cannot run:
            those APIs need Discord's own React renderer, which is not part of this app
            and is not redistributed. Calling them raises an explicit error instead of
            silently doing nothing.

            Themes that only override custom properties are applied. Selectors that
            target Discord's DOM are parsed and stored, but there is no Discord DOM to
            paint them on.

            STORAGE
            ${app.store.dirFor(io.nebula.mobile.core.AddonType.PLUGIN).absolutePath}
            ${app.store.dirFor(io.nebula.mobile.core.AddonType.THEME).absolutePath}

            JAVASCRIPT ENGINE
            QuickJS (ES2020: classes, arrow functions, destructuring, async syntax),
            one runtime per plugin, no Java bridge exposed to addon code. Addon code runs
            on a dedicated thread, so a runaway plugin cannot freeze this UI.
        """.trimIndent()
    }
}
