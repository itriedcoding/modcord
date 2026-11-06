package io.nebula.mobile.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nebula.mobile.AddonRow
import io.nebula.mobile.Nebula
import io.nebula.mobile.NebulaApp
import io.nebula.mobile.R
import io.nebula.mobile.core.AddonType

class PluginsFragment : Fragment(R.layout.fragment_list) {

    private lateinit var app: NebulaApp
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: AddonAdapter

    private val refresh: () -> Unit = {
        if (isAdded) {
            val rows = app.plugins.rows(AddonType.PLUGIN)
            adapter.submitList(rows.toList())
            empty.isVisible = rows.isEmpty()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        app = Nebula.get()
        list = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        empty.setText(R.string.empty_plugins)
        empty.typeface = Typeface.DEFAULT

        adapter = AddonAdapter(
            palette = app.palette(),
            isTheme = false,
            isChecked = { row -> row.enabled },
            onToggle = { row: AddonRow, checked: Boolean ->
                app.plugins.setEnabled(row.addon.id, checked) { result ->
                    val error = result.exceptionOrNull()?.message
                    if (error != null) {
                        view.post {
                            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onClick = { row -> AddonDialogs.showPluginDetails(requireActivity(), app, row) },
        )

        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        app.addAddonListener(refresh)
        refresh()
    }

    override fun onDestroyView() {
        app.removeAddonListener(refresh)
        super.onDestroyView()
    }
}

class ThemesFragment : Fragment(R.layout.fragment_list) {

    private lateinit var app: NebulaApp
    private lateinit var empty: TextView
    private lateinit var adapter: AddonAdapter

    private val refresh: () -> Unit = {
        if (isAdded) {
            val rows = app.plugins.rows(AddonType.THEME)
            adapter.submitList(rows.toList())
            empty.isVisible = rows.isEmpty()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        app = Nebula.get()
        val list: RecyclerView = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        empty.setText(R.string.empty_themes)

        adapter = AddonAdapter(
            palette = app.palette(),
            isTheme = true,
            isChecked = { row -> app.activeThemeId() == row.addon.id },
            onToggle = { row: AddonRow, checked: Boolean ->
                app.plugins.setThemeActive(if (checked) row.addon.id else null)
            },
            onClick = { row -> AddonDialogs.showThemeDetails(requireActivity(), app, row) },
        )

        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        app.addAddonListener(refresh)
        refresh()
    }

    override fun onDestroyView() {
        app.removeAddonListener(refresh)
        super.onDestroyView()
    }
}
