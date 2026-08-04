package com.bytedance.zgx.solin

import android.Manifest
import android.os.Build
import android.provider.Settings
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.tool.AndroidRuntimePermissionKind
import com.bytedance.zgx.solin.tool.AndroidRuntimePermissionSpec
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimePermissionPolicyTest {
    /**
     * Shared built-in registry for the cases that assert on built-in tools only.
     *
     * The policy functions take `toolRegistry` without a default so production call sites cannot
     * silently resolve a module tool against a built-in-only registry (see the file header on
     * AgentRuntimePermissionPolicy). Tests that only exercise built-in tools still want a terse
     * call, and one shared instance avoids re-parsing every tool schema per assertion. Cases that
     * are specifically about module tools build their own registries instead.
     */
    private val builtInRegistry = ToolRegistry()

    @Test
    fun backgroundNotificationToolsRequestNotificationPermissionOnlyOnAndroid13Plus() {
        val confirmation = confirmationFor(MobileActionFunctions.SCHEDULE_REMINDER)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry).isEmpty())
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry),
        )

        val periodicCheckConfirmation = confirmationFor(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK)
        assertTrue(periodicCheckConfirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry).isEmpty())
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            periodicCheckConfirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry),
        )
    }

    @Test
    fun calendarAndContactToolsRequestTheirRuntimePermissions() {
        assertEquals(
            listOf(Manifest.permission.READ_CALENDAR),
            confirmationFor(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY).runtimePermissionsFor(toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmationFor(MobileActionFunctions.QUERY_CONTACTS).runtimePermissionsFor(toolRegistry = builtInRegistry),
        )
    }

    @Test
    fun contactLookupSkillFirstConfirmationStillRequestsContactsPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_CONTACTS,
            arguments = mapOf("query" to "Alice"),
            skillId = "contact_lookup_skill",
        )

        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmation.runtimePermissionsFor(toolRegistry = builtInRegistry),
        )
        assertTrue(confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry).isEmpty())
    }

    @Test
    fun contactDraftSkillFirstConfirmationDoesNotRequestContactsPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.CREATE_CONTACT_DRAFT,
            arguments = mapOf("name" to "Alice"),
            skillId = BuiltInSkillRuntime.CONTACT_DRAFT_SKILL,
        )

        assertTrue(confirmation.runtimePermissionsFor(toolRegistry = builtInRegistry).isEmpty())
        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
        assertTrue(confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry).isEmpty())
    }

    @Test
    fun calendarAvailabilitySkillFirstConfirmationStillRequestsCalendarPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            arguments = mapOf(
                "start" to "2026-06-01T09:00:00Z",
                "end" to "2026-06-01T10:00:00Z",
            ),
            skillId = "calendar_availability_skill",
        )

        assertEquals(
            listOf(Manifest.permission.READ_CALENDAR),
            confirmation.runtimePermissionsFor(toolRegistry = builtInRegistry),
        )
        assertEquals(
            "用于只读查询忙闲时间段，不读取标题、地点或参与人。",
            confirmation.runtimePermissionRequirementsFor(toolRegistry = builtInRegistry).single().rationale,
        )
        assertTrue(confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry).isEmpty())
    }

    @Test
    fun recentFilesUsesLegacyStoragePermissionBeforeAndroid13() {
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "images"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "screenshots"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmationFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmationFor(MobileActionFunctions.READ_RECENT_IMAGE_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry),
        )
    }

    @Test
    fun recentFilesUsesMediaSpecificPermissionsOnAndroid13Plus() {
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "images"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "screenshots"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmationFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmationFor(MobileActionFunctions.READ_RECENT_IMAGE_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
            confirmationFor(MobileActionFunctions.QUERY_RECENT_FILES)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry),
        )
    }

    @Test
    fun recentVisualMediaModelsSelectedPhotoAccessOnAndroid14Plus() {
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "images"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "videos"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            confirmationFor(MobileActionFunctions.READ_RECENT_IMAGE_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
            confirmationFor(MobileActionFunctions.QUERY_RECENT_FILES)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, toolRegistry = builtInRegistry),
        )
    }

    @Test
    fun recentNonMediaFilesDoNotPretendToHaveARequestableAndroid13Permission() {
        listOf("documents", "downloads", "others").forEach { kind ->
            val confirmation = confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to kind),
            )

            assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
            assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, toolRegistry = builtInRegistry).isEmpty())
        }
    }

    @Test
    fun recentScreenshotOcrSkillFirstConfirmationStillRequestsImageReadPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            arguments = mapOf("maxCount" to "1"),
            skillId = BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL,
        )

        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry),
        )
        assertTrue(confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry).isEmpty())
    }

    @Test
    fun recentImageOcrSkillFirstConfirmationStillRequestsImageReadPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            arguments = mapOf("maxCount" to "3"),
            skillId = BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL,
        )

        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry),
        )
        assertTrue(confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry).isEmpty())
    }

    @Test
    fun runtimePermissionRequirementsExposeFriendlyLabelsAndRationales() {
        val requirements = confirmationFor(MobileActionFunctions.QUERY_CONTACTS)
            .runtimePermissionRequirementsFor(toolRegistry = builtInRegistry)

        assertEquals(1, requirements.size)
        assertEquals(listOf(Manifest.permission.READ_CONTACTS), requirements.single().permissions)
        assertEquals("联系人权限", requirements.single().title)
        assertTrue(requirements.single().rationale.contains("只读查询联系人"))
        assertEquals("联系人权限", runtimePermissionDenialSummary(listOf(Manifest.permission.READ_CONTACTS)))
    }

    @Test
    fun recentScreenshotOcrPermissionRationaleDisclosesPixelAndOcrRead() {
        val requirement = confirmationFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR)
            .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry)
            .single()

        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES), requirement.permissions)
        assertEquals("照片和图片权限", requirement.title)
        assertTrue(requirement.rationale.contains("读取最近 1 张截图像素"))
        assertTrue(requirement.rationale.contains("OCR 文本"))
    }

    @Test
    fun recentImageOcrPermissionRationaleDisclosesBoundedPixelAndOcrRead() {
        val requirement = confirmationFor(MobileActionFunctions.READ_RECENT_IMAGE_OCR)
            .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry)
            .single()

        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES), requirement.permissions)
        assertEquals("照片和图片权限", requirement.title)
        assertTrue(requirement.rationale.contains("最多扫描最近 3 张图片像素"))
        assertTrue(requirement.rationale.contains("第一条 OCR 文本"))
    }

    @Test
    fun runtimePermissionRequirementsCoverNotificationCalendarMediaAndLegacyStorage() {
        assertEquals(
            "通知权限",
            confirmationFor(MobileActionFunctions.SCHEDULE_REMINDER)
                .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry)
                .single()
                .title,
        )
        assertEquals(
            "日历权限",
            confirmationFor(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY)
                .runtimePermissionRequirementsFor(toolRegistry = builtInRegistry)
                .single()
                .title,
        )
        assertEquals(
            listOf("照片和图片权限", "视频权限", "音频权限"),
            confirmationFor(MobileActionFunctions.QUERY_RECENT_FILES)
                .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry)
                .map { it.title },
        )
        assertEquals(
            "文件读取权限",
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "downloads"),
            )
                .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry)
                .single()
                .title,
        )
    }

    @Test
    fun deepLinkAndAppIntentDoNotRequestRuntimePermissions() {
        assertTrue(
            confirmationFor(
                toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                arguments = mapOf("uri" to "https://example.com"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty(),
        )
        assertTrue(
            confirmationFor(
                toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                arguments = mapOf("appName" to "淘宝"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty(),
        )
        assertTrue(
            confirmationFor(
                toolName = MobileActionFunctions.OPEN_APP_INTENT,
                arguments = mapOf("packageName" to "com.example.app"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty(),
        )
        assertTrue(
            confirmationFor(
                toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                arguments = mapOf(
                    "targetId" to "android_app_details_settings",
                    "packageName" to "com.example.app",
                ),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty(),
        )
    }

    @Test
    fun runtimePermissionRegistryMarkerMatchesPolicyTools() {
        val registry = ToolRegistry()
        val runtimePermissionTools = registry.specs()
            .filter { ToolPermission.RequiresAndroidRuntimePermission in it.permissions }
            .map { it.name }
            .toSet()
        val descriptorTools = registry.specs()
            .filter { it.androidRuntimePermissions.isNotEmpty() }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                MobileActionFunctions.SCHEDULE_REMINDER,
                MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                MobileActionFunctions.QUERY_CONTACTS,
                MobileActionFunctions.QUERY_RECENT_FILES,
                MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            ),
            runtimePermissionTools,
        )
        assertEquals(
            "Android runtime permission marker and descriptor must stay in lockstep",
            runtimePermissionTools,
            descriptorTools,
        )
    }

    @Test
    fun runtimePermissionRequirementsCanComeFromRegistryProviderDescriptors() {
        val toolName = "custom_contact_context"
        val confirmation = confirmationFor(toolName)
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    ToolSpec(
                        name = toolName,
                        title = "Custom contact context",
                        description = "Custom contact context",
                        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
                        capability = ToolCapability.DeviceContext,
                        permissions = setOf(ToolPermission.RequiresAndroidRuntimePermission),
                        androidRuntimePermissions = listOf(
                            AndroidRuntimePermissionSpec(AndroidRuntimePermissionKind.ReadContacts),
                        ),
                    ),
                )
            },
        )

        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmation.runtimePermissionsFor(toolRegistry = registry),
        )
        assertEquals(
            "联系人权限",
            confirmation.runtimePermissionRequirementsFor(toolRegistry = registry).single().title,
        )
    }

    @Test
    fun foregroundAppDeclaresUsageAccessAsSpecialAccessNotRuntimePermission() {
        val confirmation = confirmationFor(MobileActionFunctions.QUERY_FOREGROUND_APP)
        val requirements = confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
        assertEquals(1, requirements.size)
        assertEquals(SPECIAL_ACCESS_USAGE_STATS, requirements.single().id)
        assertEquals("使用情况访问权限", requirements.single().title)
        assertEquals(
            "用于通过 UsageStats 估计当前前台应用名和包名；不是窗口真值，不读取使用历史或屏幕内容，需要在系统设置中手动开启。",
            requirements.single().rationale,
        )
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, requirements.single().settingsAction)
    }

    @Test
    fun usageAccessSettingsDeclaresNoRuntimePermissionOrSpecialAccess() {
        val confirmation = confirmationFor(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
        assertTrue(confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry).isEmpty())
    }

    @Test
    fun recentNotificationsDeclareNoRuntimePermissionOrSpecialAccess() {
        val confirmation = confirmationFor(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
        assertTrue(confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry).isEmpty())
    }

    @Test
    fun backgroundTasksQueryDeclaresNoRuntimePermissionOrSpecialAccess() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
            arguments = mapOf("scope" to "all"),
            skillId = BuiltInSkillRuntime.BACKGROUND_TASKS_CONTEXT_SKILL,
        )

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S, toolRegistry = builtInRegistry).isEmpty())
        assertTrue(confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry).isEmpty())
    }

    @Test
    fun currentScreenTextDeclaresAccessibilityAsSpecialAccessNotRuntimePermission() {
        val confirmation = confirmationFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)
        val requirements = confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
        assertEquals(1, requirements.size)
        assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT, requirements.single().id)
        assertEquals("无障碍屏幕文本权限", requirements.single().title)
        assertTrue(requirements.single().rationale.contains("当前屏幕"))
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, requirements.single().settingsAction)
    }

    @Test
    fun specialAccessRequirementsCanComeFromRegistryProviderTags() {
        val toolName = "custom_accessibility_tool"
        val confirmation = confirmationFor(toolName)
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    ToolSpec(
                        name = toolName,
                        title = "Custom accessibility tool",
                        description = "Custom accessibility tool",
                        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
                        capability = ToolCapability.DeviceControl,
                        tags = setOf(ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess),
                    ),
                )
            },
        )

        val requirements = confirmation.specialAccessRequirementsFor(registry)

        assertEquals(1, requirements.size)
        assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL, requirements.single().id)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, requirements.single().settingsAction)
    }

    @Test
    fun deviceControlToolsDeclareAccessibilityControlSpecialAccessOnly() {
        val deviceControlTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_PRESS_BACK,
            MobileActionFunctions.UI_WAIT,
        )

        deviceControlTools.forEach { toolName ->
            val confirmation = confirmationFor(
                toolName = toolName,
                arguments = when (toolName) {
                    MobileActionFunctions.UI_TAP -> mapOf("target" to "Continue")
                    MobileActionFunctions.UI_TYPE_TEXT -> mapOf("text" to "hello")
                    MobileActionFunctions.UI_SCROLL -> mapOf("direction" to "down")
                    else -> emptyMap()
                },
            )
            val requirements = confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry)

            assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
            assertEquals(1, requirements.size)
            assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL, requirements.single().id)
            assertEquals("无障碍设备控制权限", requirements.single().title)
            assertTrue(requirements.single().rationale.contains("点击"))
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, requirements.single().settingsAction)
        }
    }

    @Test
    fun currentScreenshotOcrDeclaresMediaProjectionConsentNotRuntimePermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
        assertTrue(confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry).isEmpty())
        val spec = requireNotNull(ToolRegistry().specFor(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR))
        assertTrue(spec.permissions.contains(ToolPermission.RequiresMediaProjectionConsent))
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in spec.permissions)
        assertTrue(ToolPermission.ReadsAccessibilityText in spec.permissions)
    }

    @Test
    fun specialAccessDenialSummaryUsesRequirementTitles() {
        assertEquals(
            "使用情况访问权限, 无障碍屏幕文本权限",
            specialAccessDenialSummary(
                listOf(
                    confirmationFor(MobileActionFunctions.QUERY_FOREGROUND_APP)
                        .specialAccessRequirementsFor(toolRegistry = builtInRegistry)
                        .single(),
                    confirmationFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)
                        .specialAccessRequirementsFor(toolRegistry = builtInRegistry)
                        .single(),
                    confirmationFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)
                        .specialAccessRequirementsFor(toolRegistry = builtInRegistry)
                        .single(),
                ),
            ),
        )
    }

    @Test
    fun currentScreenTextSkillFirstConfirmationDeclaresAccessibilitySpecialAccessOnly() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            arguments = mapOf("maxChars" to "1200"),
            skillId = BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_CONTEXT_SKILL,
        )
        val requirements = confirmation.specialAccessRequirementsFor(toolRegistry = builtInRegistry)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU, toolRegistry = builtInRegistry).isEmpty())
        assertEquals(1, requirements.size)
        assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT, requirements.single().id)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, requirements.single().settingsAction)
    }

    @Test
    fun pendingSpecialAccessRequirementRestoresFromCurrentPendingConfirmationOnly() {
        val usageConfirmation = confirmationFor(MobileActionFunctions.QUERY_FOREGROUND_APP)
        val screenTextConfirmation = confirmationFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)

        assertEquals(
            SPECIAL_ACCESS_USAGE_STATS,
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_USAGE_STATS,
                pendingConfirmation = usageConfirmation,
                toolRegistry = builtInRegistry,
            )?.id,
        )
        assertEquals(
            SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
                pendingConfirmation = screenTextConfirmation,
                toolRegistry = builtInRegistry,
            )?.id,
        )
        assertNull(
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
                pendingConfirmation = usageConfirmation,
                toolRegistry = builtInRegistry,
            ),
        )
        assertNull(
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_USAGE_STATS,
                pendingConfirmation = null,
                toolRegistry = builtInRegistry,
            ),
        )
    }

    @Test
    fun deniedGrantResultKeepsToolFromExecutingUntilPermissionIsActuallyGranted() {
        val confirmation = confirmationFor(MobileActionFunctions.QUERY_CONTACTS)
        val permission = Manifest.permission.READ_CONTACTS

        assertEquals(
            listOf(permission),
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(permission to false),
                hasRuntimePermission = { false },
                toolRegistry = builtInRegistry,
            ),
        )
        assertEquals(
            listOf(permission),
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = emptyMap(),
                hasRuntimePermission = { false },
                toolRegistry = builtInRegistry,
            ),
        )
        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(permission to true),
                hasRuntimePermission = { false },
                toolRegistry = builtInRegistry,
            ).isEmpty(),
        )
        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = emptyMap(),
                hasRuntimePermission = { it == permission },
                toolRegistry = builtInRegistry,
            ).isEmpty(),
        )
    }

    @Test
    fun android14VisualMediaGrantAcceptsEitherFullOrUserSelectedAccess() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_RECENT_FILES,
            arguments = mapOf("kind" to "images"),
        )
        val imagePermission = Manifest.permission.READ_MEDIA_IMAGES
        val selectedVisualPermission = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED

        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to true,
                    selectedVisualPermission to false,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
                toolRegistry = builtInRegistry,
            ).isEmpty(),
        )
        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    selectedVisualPermission to true,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
                toolRegistry = builtInRegistry,
            ).isEmpty(),
        )
        assertEquals(
            listOf(imagePermission, selectedVisualPermission),
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    selectedVisualPermission to false,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
                toolRegistry = builtInRegistry,
            ),
        )
    }

    @Test
    fun android14RecentFilesAllAcceptsPartialMediaGrantWithoutAudio() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_RECENT_FILES,
            arguments = mapOf("kind" to "all"),
        )
        val imagePermission = Manifest.permission.READ_MEDIA_IMAGES
        val videoPermission = Manifest.permission.READ_MEDIA_VIDEO
        val selectedVisualPermission = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        val audioPermission = Manifest.permission.READ_MEDIA_AUDIO

        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    videoPermission to false,
                    selectedVisualPermission to true,
                    audioPermission to false,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
                toolRegistry = builtInRegistry,
            ).isEmpty(),
        )
        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    videoPermission to false,
                    selectedVisualPermission to false,
                    audioPermission to true,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
                toolRegistry = builtInRegistry,
            ).isEmpty(),
        )
        assertEquals(
            listOf(imagePermission, videoPermission, selectedVisualPermission, audioPermission),
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    videoPermission to false,
                    selectedVisualPermission to false,
                    audioPermission to false,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
                toolRegistry = builtInRegistry,
            ),
        )
    }

    @Test
    fun runtimePermissionResultCanMatchCurrentPendingAfterActivityRecreation() {
        val contacts = confirmationFor(MobileActionFunctions.QUERY_CONTACTS)
        val screenshot = confirmationFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR)

        assertTrue(
            contacts.requiresRuntimePermissionResult(
                resultPermissions = setOf(Manifest.permission.READ_CONTACTS),
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                toolRegistry = builtInRegistry,
            ),
        )
        assertTrue(
            contacts.requiresRuntimePermissionResult(
                resultPermissions = emptySet(),
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                toolRegistry = builtInRegistry,
            ),
        )
        assertTrue(
            contacts.matchesExecution(contacts.copy()),
        )
        assertTrue(
            !contacts.matchesExecution(screenshot),
        )
        assertTrue(
            !contacts.requiresRuntimePermissionResult(
                resultPermissions = setOf(Manifest.permission.READ_MEDIA_IMAGES),
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                toolRegistry = builtInRegistry,
            ),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression guard for the silent fail-OPEN documented in AgentRuntimePermissionPolicy.kt.
    //
    // These functions answer "does this tool need a runtime permission / special access /
    // MediaProjection consent?" by looking the tool up in the registry they are handed. A
    // built-in-only registry does not know module-contributed tools, so it answers "unknown",
    // and an unknown tool yields NO requirement at all — the gate opens without asking.
    //
    // The tests below pin BOTH halves of that contract, so the pair fails loudly if someone
    // makes these functions read a registry other than the one passed in (e.g. reintroduces a
    // hard-coded default inside the body):
    //   1. built-in-only registry + module tool  -> answers "no requirement" (the hazard)
    //   2. module-aware registry + same tool     -> answers with the real requirement
    // Only (2) is the correct production answer, so (2) proves the argument is actually used
    // while (1) documents exactly why passing the wrong registry is unsafe.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun moduleToolPermissionGateUsesSuppliedRegistryNotABuiltInOnlyOne() {
        val confirmation = confirmationFor(MODULE_CONTACT_TOOL)
        val builtInOnlyRegistry = ToolRegistry()
        val moduleAwareRegistry = ToolRegistry(moduleContactToolProvider())

        // (1) The hazard: a built-in-only registry cannot see the module tool, so the runtime
        // permission gate finds nothing to request and would let execution through unasked.
        assertTrue(
            "A built-in-only registry cannot know module tools; this asserts the hazard exists, " +
                "which is why production must pass the module-aware registry.",
            confirmation.runtimePermissionsFor(
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                toolRegistry = builtInOnlyRegistry,
            ).isEmpty(),
        )
        assertNull(builtInOnlyRegistry.specFor(MODULE_CONTACT_TOOL))

        // (2) Fail-closed: handed the module-aware registry, the same confirmation demands the
        // real permission. This is what proves the `toolRegistry` argument is honoured.
        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmation.runtimePermissionsFor(
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                toolRegistry = moduleAwareRegistry,
            ),
        )
        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmation.runtimePermissionRequirementsFor(
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                toolRegistry = moduleAwareRegistry,
            ).flatMap { requirement -> requirement.permissions },
        )
        // The permission result matcher must also route through the supplied registry, otherwise
        // a granted/denied callback for a module tool would never be attributed to its pending
        // confirmation and the denial path would be skipped.
        assertTrue(
            confirmation.requiresRuntimePermissionResult(
                resultPermissions = setOf(Manifest.permission.READ_CONTACTS),
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                toolRegistry = moduleAwareRegistry,
            ),
        )
    }

    @Test
    fun moduleToolSpecialAccessGateUsesSuppliedRegistryNotABuiltInOnlyOne() {
        val confirmation = confirmationFor(MODULE_SCREEN_CONTROL_TOOL)
        val moduleAwareRegistry = ToolRegistry(moduleScreenControlToolProvider())

        assertTrue(
            "Built-in-only registry cannot see the module tool, so no special access is demanded.",
            confirmation.specialAccessRequirementsFor(ToolRegistry()).isEmpty(),
        )

        val requirements = confirmation.specialAccessRequirementsFor(moduleAwareRegistry)
        assertEquals(1, requirements.size)
        assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL, requirements.single().id)

        // Restore-after-recreation must resolve against the same registry; otherwise a module
        // tool's pending special-access requirement silently vanishes across a config change.
        assertEquals(
            requirements.single(),
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL,
                pendingConfirmation = confirmation,
                toolRegistry = moduleAwareRegistry,
            ),
        )
        assertNull(
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL,
                pendingConfirmation = confirmation,
                toolRegistry = ToolRegistry(),
            ),
        )
    }

    @Test
    fun moduleToolMediaProjectionConsentGateUsesSuppliedRegistryNotABuiltInOnlyOne() {
        val confirmation = confirmationFor(MODULE_SCREENSHOT_TOOL)

        // Screen-pixel capture without consent is the most sensitive of the three gates: with a
        // built-in-only registry the module tool reads as "no consent needed" and the system
        // MediaProjection dialog would never be shown.
        assertTrue(
            !confirmation.requiresCurrentScreenshotOcrConsent(ToolRegistry()),
        )
        assertTrue(
            confirmation.requiresCurrentScreenshotOcrConsent(
                ToolRegistry(moduleScreenshotToolProvider()),
            ),
        )
    }

    @Test
    fun moduleToolDeniedPermissionsAreComputedFromSuppliedRegistry() {
        val confirmation = confirmationFor(MODULE_CONTACT_TOOL)

        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(Manifest.permission.READ_CONTACTS to false),
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                hasRuntimePermission = { false },
                toolRegistry = ToolRegistry(moduleContactToolProvider()),
            ),
        )
        // Same call with a registry that does not know the tool reports nothing denied, which
        // would read as "all permissions satisfied" and confirm the action.
        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(Manifest.permission.READ_CONTACTS to false),
                apiLevel = Build.VERSION_CODES.TIRAMISU,
                hasRuntimePermission = { false },
                toolRegistry = ToolRegistry(),
            ).isEmpty(),
        )
    }

    private fun moduleContactToolProvider(): ToolProvider =
        ToolProvider {
            listOf(
                ToolSpec(
                    name = MODULE_CONTACT_TOOL,
                    title = "Module contact context",
                    description = "Module-contributed contact context tool",
                    inputSchemaJson = EMPTY_OBJECT_SCHEMA_JSON,
                    capability = ToolCapability.DeviceContext,
                    permissions = setOf(ToolPermission.RequiresAndroidRuntimePermission),
                    androidRuntimePermissions = listOf(
                        AndroidRuntimePermissionSpec(AndroidRuntimePermissionKind.ReadContacts),
                    ),
                ),
            )
        }

    private fun moduleScreenControlToolProvider(): ToolProvider =
        ToolProvider {
            listOf(
                ToolSpec(
                    name = MODULE_SCREEN_CONTROL_TOOL,
                    title = "Module screen control",
                    description = "Module-contributed device control tool",
                    inputSchemaJson = EMPTY_OBJECT_SCHEMA_JSON,
                    capability = ToolCapability.DeviceControl,
                    tags = setOf(ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess),
                ),
            )
        }

    private fun moduleScreenshotToolProvider(): ToolProvider =
        ToolProvider {
            listOf(
                ToolSpec(
                    name = MODULE_SCREENSHOT_TOOL,
                    title = "Module screenshot OCR",
                    description = "Module-contributed screenshot OCR tool",
                    inputSchemaJson = EMPTY_OBJECT_SCHEMA_JSON,
                    capability = ToolCapability.DeviceContext,
                    permissions = setOf(ToolPermission.RequiresMediaProjectionConsent),
                ),
            )
        }

    private fun confirmationFor(
        toolName: String,
        arguments: Map<String, String> = emptyMap(),
        skillId: String? = null,
    ): PendingAgentConfirmation =
        PendingAgentConfirmation(
            runId = "run-1",
            draft = ActionDraft(
                functionName = toolName,
                title = "Test",
                summary = "Test",
                parameters = arguments,
            ),
            toolRequest = ToolRequest(
                id = "request-1",
                toolName = toolName,
                arguments = arguments,
                reason = "test",
            ),
            skillId = skillId,
            plannedByModel = false,
            fallbackReason = null,
        )

    private companion object {
        // Names deliberately absent from the built-in registry: they stand in for tools a
        // SolinModule contributes at runtime (plan tools, MCP tools, …).
        const val MODULE_CONTACT_TOOL = "module_contact_context"
        const val MODULE_SCREEN_CONTROL_TOOL = "module_screen_control"
        const val MODULE_SCREENSHOT_TOOL = "module_screenshot_ocr"
        const val EMPTY_OBJECT_SCHEMA_JSON =
            """{"type":"object","properties":{},"additionalProperties":false}"""
    }
}
