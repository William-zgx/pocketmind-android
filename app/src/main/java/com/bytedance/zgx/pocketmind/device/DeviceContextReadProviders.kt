package com.bytedance.zgx.pocketmind.device

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat

private const val DEFAULT_MAX_NOTIFICATION_LOOKBACK_MS = 15 * 60 * 1000L
private const val DEFAULT_MAX_NOTIFICATION_COUNT = 5
private const val DEFAULT_MAX_CONTACT_SUMMARY_COUNT = 5
private const val DEFAULT_MAX_CONTACT_SUMMARY_LOOKBACK = 20

interface ForegroundAppProvider {
    fun currentForegroundApp(): ForegroundAppReadResult
}

interface NotificationSummaryProvider {
    fun recentNotifications(maxCount: Int = DEFAULT_MAX_NOTIFICATION_COUNT): NotificationSummaryReadResult
}

interface ContactSummaryProvider {
    fun queryContacts(query: String, maxCount: Int = DEFAULT_MAX_CONTACT_SUMMARY_COUNT): ContactSummaryReadResult
}

data class ForegroundAppInfo(
    val packageName: String,
    val appLabel: String,
    val lastTimeUsedMillis: Long,
)

sealed class ForegroundAppReadResult {
    data class Available(val appInfo: ForegroundAppInfo) : ForegroundAppReadResult()
    data class PermissionDenied(val reason: String) : ForegroundAppReadResult()
    data class Failed(val reason: String) : ForegroundAppReadResult()
}

data class NotificationSummaryItem(
    val id: Int,
    val title: String,
    val isOngoing: Boolean,
    val postTimeMillis: Long,
)

sealed class NotificationSummaryReadResult {
    data class Available(val items: List<NotificationSummaryItem>) : NotificationSummaryReadResult()
    data class PermissionDenied(val reason: String) : NotificationSummaryReadResult()
    data class Failed(val reason: String) : NotificationSummaryReadResult()
}

data class ContactSummaryItem(
    val name: String,
    val phone: String,
)

sealed class ContactSummaryReadResult {
    data class Available(val items: List<ContactSummaryItem>) : ContactSummaryReadResult()
    data class PermissionDenied(val reason: String) : ContactSummaryReadResult()
    data class Failed(val reason: String) : ContactSummaryReadResult()
}

class AndroidContactSummaryProvider(
    private val context: Context,
) : ContactSummaryProvider {
    override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return ContactSummaryReadResult.Failed("查询关键词不能为空")
        }
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ContactSummaryReadResult.PermissionDenied("未授权“读取联系人”权限")
        }
        val normalizedMax = maxCount.coerceIn(1, DEFAULT_MAX_CONTACT_SUMMARY_LOOKBACK)
        val likeArg = "%$trimmedQuery%"
        val selection =
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"

        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                CONTACT_PROJECTION,
                selection,
                arrayOf(likeArg, likeArg),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC",
            ) ?: return ContactSummaryReadResult.Failed("通讯录数据源不可用")

            val results = linkedMapOf<String, ContactSummaryItem>()
            cursor.use {
                val nameIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val phoneIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex).orEmpty().trim().ifBlank { "未知联系人" }
                    val phone = it.getString(phoneIndex).orEmpty().trim()
                    if (phone.isBlank()) continue

                    val key = "$name|${phone.replace("\\s+".toRegex(), "")}"
                    if (!results.containsKey(key)) {
                        results[key] = ContactSummaryItem(
                            name = name,
                            phone = phone,
                        )
                        if (results.size >= normalizedMax) break
                    }
                }
            }
            ContactSummaryReadResult.Available(results.values.toList())
        } catch (_: SecurityException) {
            ContactSummaryReadResult.PermissionDenied("未授权“读取联系人”权限")
        } catch (throwable: Throwable) {
            ContactSummaryReadResult.Failed(
                throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName,
            )
        }
    }

    private companion object {
        val CONTACT_PROJECTION = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
    }
}

class AndroidForegroundAppProvider(
    private val context: Context,
) : ForegroundAppProvider {
    private val usageStatsManager: UsageStatsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        } else {
            null
        }
    private val packageManager = context.packageManager

    override fun currentForegroundApp(): ForegroundAppReadResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || usageStatsManager == null) {
            return ForegroundAppReadResult.Failed(
                "当前系统版本不支持查询前台应用",
            )
        }
        if (!hasUsageStatsPermission()) {
            return ForegroundAppReadResult.PermissionDenied("未授权“查看应用使用情况”权限")
        }
        val now = System.currentTimeMillis()
        val usages = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - DEFAULT_MAX_NOTIFICATION_LOOKBACK_MS,
            now,
        ) ?: emptyList()
        if (usages.isEmpty()) {
            return ForegroundAppReadResult.Failed("未能查询到应用使用统计")
        }
        val current = usages.maxByOrNull { it.lastTimeUsed } ?: return ForegroundAppReadResult.Failed(
            "未能识别当前应用",
        )
        if (current.packageName.isBlank()) {
            return ForegroundAppReadResult.Failed("当前前台应用包名为空")
        }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(current.packageName, 0)).toString()
        }.getOrNull() ?: current.packageName
        return ForegroundAppReadResult.Available(
            ForegroundAppInfo(
                packageName = current.packageName,
                appLabel = appLabel,
                lastTimeUsedMillis = current.lastTimeUsed,
            ),
        )
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val packageName = context.packageName
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

class AndroidNotificationSummaryProvider(
    private val context: Context,
) : NotificationSummaryProvider {
    override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult {
        val normalized = maxCount.coerceIn(1, 20)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return NotificationSummaryReadResult.Failed("通知服务不可用")
        if (!manager.areNotificationsEnabled()) {
            return NotificationSummaryReadResult.PermissionDenied("未开启应用通知权限")
        }
        val notifications = runCatching {
            manager.activeNotifications
        }.getOrNull() ?: return NotificationSummaryReadResult.Failed("读取通知失败")

        val packageName = context.packageName
        val items = notifications
            .asSequence()
            .filter { it.packageName == packageName }
            .sortedByDescending { it.postTime }
            .take(normalized)
            .map { statusBarNotification ->
                val extras = statusBarNotification.notification.extras
                val title = extras.getCharSequence("android.title")?.toString().orEmpty()
                NotificationSummaryItem(
                    id = statusBarNotification.id,
                    title = title.ifBlank { "(无标题)" },
                    isOngoing = statusBarNotification.isOngoing,
                    postTimeMillis = statusBarNotification.postTime,
                )
            }
            .toList()
        return NotificationSummaryReadResult.Available(items)
    }
}
