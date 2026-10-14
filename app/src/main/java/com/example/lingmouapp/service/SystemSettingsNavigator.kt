package com.example.lingmouapp.service

import android.app.Activity
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 把用户送到正确的系统设置页，并在已经授权时直接短路（不再跳转）。
 *
 * 为什么不能只用一个 Intent：
 *  - 「通知使用权」属于特殊权限，系统不允许应用自己弹窗申请，只能跳设置页；
 *  - `ACTION_NOTIFICATION_LISTENER_SETTINGS` **不接受任何包名 extra**，
 *    想「带着包名」直达本应用开关，必须用 API 30+ 的
 *    `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` +
 *    `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME`（传 ComponentName 的扁平化字符串）；
 *  - 精确闹钟页在部分 ROM 上不响应标准 action，或者需要带 `package:` 的 data；
 *  - 各家 ROM（ColorOS / MIUI / EMUI）还会把设置页改得面目全非。
 *
 * 所以每个入口都做多级回退，并把「最终落在哪一页」返回给调用方，
 * 让界面能明确告诉用户接下来该点哪个开关。
 */
object SystemSettingsNavigator {

    /**
     * @param alreadySatisfied 权限本来就是满足的，没有发生跳转
     */
    data class Jump(
        val ok: Boolean,
        val page: String,
        val alreadySatisfied: Boolean = false
    )

    // ------------------------------------------------------------ 通知使用权

    /** 通知使用权：跨应用媒体控制（MediaSession）的前提 */
    fun openNotificationListener(activity: Activity): Jump {
        if (isNotificationAccessGranted(activity)) {
            return Jump(
                ok = true,
                page = "通知使用权已授予，无需操作",
                alreadySatisfied = true
            )
        }

        // 1) Android 11+：带组件名直达本应用的开关页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = ComponentName(activity, MediaNotificationListener::class.java)
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

        // 2) 通知使用权列表页（这个 action 会忽略包名 extra，只能让用户自己找）
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

    fun isNotificationAccessGranted(activity: Activity): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(activity)
            .contains(activity.packageName)
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------ 精确闹钟

    /**
     * 精确闹钟：定时提醒能否准点触发的前提。
     *
     * Android 14 起，targetSdk >= 33 的应用默认**不会**拿到这个权限，
     * 所以初次进来显示「未授予」是正常的，不是 Bug。
     */
    fun openExactAlarm(activity: Activity): Jump {
        val manager = activity.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager?.canScheduleExactAlarms() == true
        } else {
            true
        }
        if (granted) {
            return Jump(
                ok = true,
                page = "精确闹钟已是允许状态，无需操作",
                alreadySatisfied = true
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 起必须带 package: 才会落到本应用的「闹钟和提醒」
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

    // ------------------------------------------------------------ 其它

    /** 本应用的通知开关（POST_NOTIFICATIONS 被关掉时用） */
    fun openAppNotificationSettings(activity: Activity): Jump {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        if (start(activity, intent)) return Jump(true, "本应用的通知设置")
        return openAppDetails(activity)
    }

    /**
     * 应用详情页。
     * ColorOS / MIUI 的「自启动」「后台运行管理」「电池优化」入口都挂在这一页下面，
     * 这些开关直接决定息屏后闹钟能不能按时响，所以单独做成一个按钮。
     */
    fun openAppDetails(activity: Activity): Jump {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)
        )
        return if (start(activity, intent)) {
            Jump(true, "本应用详情页（ColorOS 的自启动/电池管理都在这里）")
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
