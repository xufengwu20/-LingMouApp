package com.example.lingmouapp.service

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 把用户送到正确的系统设置页。
 *
 * 为什么不能只用一个 Intent：
 *  - 「直达本应用通知使用权开关」的 action 是 Android 11 (API 30) 才有的；
 *  - 精确闹钟页在部分 ROM 上不响应标准 action，或者需要带 package: 的 data；
 *  - 各家 ROM 还会把设置页改得面目全非。
 *
 * 所以每个入口都做多级回退，并把「最终落在哪一页」返回给调用方，
 * 让界面能明确告诉用户接下来该点哪个开关。
 */
object SystemSettingsNavigator {

    data class Jump(val ok: Boolean, val page: String)

    /** 通知使用权：跨应用媒体控制的前提 */
    fun openNotificationListener(activity: Activity): Jump {
        // 1) Android 11+：直达本应用的通知使用权开关页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = ComponentName(activity, LingMouNotificationListener::class.java)
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    component.flattenToString()
                )
            }
            if (start(activity, detail)) {
                return Jump(true, "通知使用权 → 灵眸，请把开关打开")
            }
        }
        // 2) 通知使用权列表页，让用户自己找到「灵眸」
        if (start(activity, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            return Jump(true, "通知使用权列表，请找到「灵眸」并打开")
        }
        // 3) 兜底：本应用详情页
        val fallback = openAppDetails(activity)
        return if (fallback.ok) {
            Jump(true, "本应用详情页，请从「权限」里找通知使用权")
        } else {
            fallback
        }
    }

    /** 精确闹钟：定时提醒能否准点触发的前提 */
    fun openExactAlarm(activity: Activity): Jump {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 带 package: 的写法在原生与多数 ROM 上会直接落到本应用的「闹钟和提醒」
            val withPackage = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.fromParts("package", activity.packageName, null)
            )
            if (start(activity, withPackage)) {
                return Jump(true, "闹钟和提醒，请选择「允许」")
            }
            if (start(activity, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))) {
                return Jump(true, "闹钟和提醒，请选择「允许」")
            }
        }
        val fallback = openAppDetails(activity)
        return if (fallback.ok) {
            Jump(true, "本应用详情页，请找「闹钟和提醒」")
        } else {
            fallback
        }
    }

    /** 本应用的通知开关（POST_NOTIFICATIONS 被关掉时用） */
    fun openAppNotificationSettings(activity: Activity): Jump {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        if (start(activity, intent)) return Jump(true, "本应用的通知设置")
        return openAppDetails(activity)
    }

    /** 最后的兜底：应用详情页 */
    fun openAppDetails(activity: Activity): Jump {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)
        )
        return if (start(activity, intent)) {
            Jump(true, "本应用详情页")
        } else {
            Jump(false, "打不开系统设置，请手动进入：设置 → 应用 → 灵眸")
        }
    }

    private fun start(activity: Activity, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}
