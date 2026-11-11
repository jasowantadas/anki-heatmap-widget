package io.github.jasowantadas.ankiheatmap

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Shared receive logic: do the work off the main thread, then redraw. */
open class BaseWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, WidgetUpdater.ACTION_REFRESH -> {
                val force = intent.action == WidgetUpdater.ACTION_REFRESH
                val pending = goAsync()
                thread {
                    try {
                        WidgetUpdater.refreshAll(context, force)
                    } finally {
                        pending.finish()
                    }
                }
            }
            WidgetUpdater.ACTION_SELECT -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val day = intent.getLongExtra(WidgetUpdater.EXTRA_DAY, -1L)
                val pending = goAsync()
                thread {
                    try {
                        WidgetUpdater.select(context, id, day)
                    } finally {
                        pending.finish()
                    }
                }
            }
            WidgetUpdater.ACTION_YEAR -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val delta = intent.getIntExtra(WidgetUpdater.EXTRA_DELTA, 0)
                val pending = goAsync()
                thread {
                    try {
                        WidgetUpdater.changeYear(context, id, delta)
                    } finally {
                        pending.finish()
                    }
                }
            }
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> {
                val pending = goAsync()
                thread {
                    try {
                        WidgetUpdater.redrawAll(context, null)
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }
}

class HeatmapWidget : BaseWidget()

class TodayWidget : BaseWidget()
