package com.wakequest.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.wakequest.app.R
import com.wakequest.app.alarm.Scheduler
import com.wakequest.app.data.Store

abstract class Tab(val act: MainActivity) {
    abstract fun build(): View
    open fun onPause() {}
}

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        private val NAV_IDS = listOf(R.id.nav_alarms, R.id.nav_tonight, R.id.nav_sounds, R.id.nav_stats, R.id.nav_settings)
        private val TABS = listOf(
            "Alarms" to R.drawable.ic_alarm,
            "Tonight" to R.drawable.ic_moon,
            "Sounds" to R.drawable.ic_waves,
            "Stats" to R.drawable.ic_chart,
            "Settings" to R.drawable.ic_settings,
        )
    }

    private lateinit var container: FrameLayout
    private lateinit var nav: BottomNavigationView
    private var tab: Tab? = null
    private var tabIndex = 0
    private var resumed = false

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        val root = column().apply { setBackgroundColor(C.BG) }
        container = FrameLayout(this)
        root.add(container, MATCH, 0, weight = 1f)
        nav = BottomNavigationView(this).apply {
            TABS.forEachIndexed { i, (title, icon) -> menu.add(Menu.NONE, NAV_IDS[i], i, title).setIcon(icon) }
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            setBackgroundColor(C.SURFACE)
            itemIconTintList = navTint()
            itemTextColor = navTint()
            itemActiveIndicatorColor = csl(C.SURFACE2)
        }
        root.add(nav, MATCH, WRAP)
        setContentView(root)
        root.applySystemInsets()

        tabIndex = savedInstanceState?.getInt(EXTRA_TAB) ?: intent.getIntExtra(EXTRA_TAB, 0)
        nav.setOnItemSelectedListener { item -> show(NAV_IDS.indexOf(item.itemId).coerceAtLeast(0)); true }
        nav.selectedItemId = NAV_IDS[tabIndex.coerceIn(0, TABS.size - 1)]
        if (tab == null) show(tabIndex.coerceIn(0, TABS.size - 1))

        askForNotifications()
        Scheduler.rescheduleAll(this)
    }

    private fun navTint() = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(C.PRIMARY, C.MUTED),
    )

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        val store = Store.get(this)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && !store.askedNotifications) {
            store.askedNotifications = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) selectTab(intent.getIntExtra(EXTRA_TAB, 0))
    }

    fun selectTab(i: Int) {
        nav.selectedItemId = NAV_IDS[i.coerceIn(0, TABS.size - 1)]
    }

    private fun show(i: Int) {
        tab?.onPause()
        tabIndex = i
        val t = when (i) {
            0 -> AlarmsTab(this)
            1 -> TonightTab(this)
            2 -> SoundsTab(this)
            3 -> StatsTab(this)
            else -> SettingsTab(this)
        }
        tab = t
        container.removeAllViews()
        container.addView(t.build(), FrameLayout.LayoutParams(MATCH, MATCH))
    }

    fun refresh() {
        if (::container.isInitialized) show(tabIndex)
    }

    override fun onResume() {
        super.onResume()
        if (resumed) refresh() // first resume already built by onCreate
        resumed = true
    }

    override fun onPause() {
        super.onPause()
        tab?.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_TAB, tabIndex)
    }
}
