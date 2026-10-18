package com.woobie.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT
}

data class UiElementInfo(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect
)

data class ScreenTreeSnapshot(
    val packageName: String,
    val appName: String,
    val elements: List<UiElementInfo>,
    val readableSummary: String
)

class WoobieAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WoobieAccessibilityService? = null
            private set

        fun isReady(): Boolean = instance != null
    }

    private var currentForegroundPackage: String = ""
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        WoobieState.updatePermissions(accessibility = true)
        WoobieState.log(
            LogSource.SYSTEM,
            "WoobieAccessibilityService connected. Full phone control enabled."
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && pkg != "com.android.systemui") {
                currentForegroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        WoobieState.log(LogSource.SYSTEM, "Accessibility Service interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        WoobieState.updatePermissions(accessibility = false)
        WoobieState.log(LogSource.SYSTEM, "WoobieAccessibilityService disconnected.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    fun pressBack(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        WoobieState.log(LogSource.WOOBIE, "Action: Pressed Back ($success)")
        return success
    }

    fun pressHome(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_HOME)
        WoobieState.log(LogSource.WOOBIE, "Action: Pressed Home ($success)")
        return success
    }

    fun pressRecents(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_RECENTS)
        WoobieState.log(LogSource.WOOBIE, "Action: Opened Recent Apps ($success)")
        return success
    }

    fun openNotifications(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        WoobieState.log(LogSource.WOOBIE, "Action: Opened Notification Shade ($success)")
        return success
    }

    fun openQuickSettings(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        WoobieState.log(LogSource.WOOBIE, "Action: Opened Quick Settings ($success)")
        return success
    }

    fun lockPhoneScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val success = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            WoobieState.log(LogSource.WOOBIE, "Action: Locked Screen ($success)")
            success
        } else {
            WoobieState.log(LogSource.SYSTEM, "Lock Screen requires Android 9+.")
            false
        }
    }

    fun openApp(appNameOrPackage: String): Boolean {
        val query = appNameOrPackage.trim().lowercase()
        if (query.isEmpty()) return false

        val pm = packageManager

        val directIntent = pm.getLaunchIntentForPackage(query)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(directIntent)
            WoobieState.log(LogSource.WOOBIE, "Opened package: $query")
            return true
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                mainIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        var partialMatchPkg: String? = null
        var partialMatchLabel: String? = null

        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString().trim()
            val pkg = info.activityInfo.packageName

            if (label.lowercase() == query) {
                return launchPackage(pkg, label)
            }
            if (partialMatchPkg == null &&
                (label.lowercase().contains(query) || query.contains(label.lowercase()))
            ) {
                partialMatchPkg = pkg
                partialMatchLabel = label
            }
        }

        if (partialMatchPkg != null) {
            return launchPackage(partialMatchPkg, partialMatchLabel ?: partialMatchPkg)
        }

        WoobieState.log(LogSource.SYSTEM, "Could not find installed app matching: '$appNameOrPackage'")
        return false
    }

    private fun launchPackage(pkg: String, label: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launchIntent)
        WoobieState.log(LogSource.WOOBIE, "Launched app: $label ($pkg)")
        return true
    }

    fun clickByText(targetText: String): Boolean {
        val query = targetText.trim().lowercase()
        if (query.isEmpty()) return false

        val root = getBestRootNode() ?: run {
            WoobieState.log(LogSource.SYSTEM, "No active window found to click '$targetText'")
            return false
        }

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, allNodes)

        var matchedNode = allNodes.firstOrNull { node ->
            val t = node.text?.toString()?.trim()?.lowercase()
            val d = node.contentDescription?.toString()?.trim()?.lowercase()
            t == query || d == query
        }

        if (matchedNode == null) {
            matchedNode = allNodes.firstOrNull { node ->
                val t = node.text?.toString()?.trim()?.lowercase() ?: ""
                val d = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                (t.isNotEmpty() && t.contains(query)) || (d.isNotEmpty() && d.contains(query))
            }
        }

        if (matchedNode != null) {
            val clicked = performClickOrTapBounds(matchedNode)
            WoobieState.log(LogSource.WOOBIE, "Clicked on '$targetText': $clicked")
            return clicked
        }

        WoobieState.log(LogSource.SYSTEM, "Element '$targetText' not found in UI tree.")
        return false
    }

    fun clickByViewId(viewIdResourceName: String): Boolean {
        val root = getBestRootNode() ?: return false
        val matched = root.findAccessibilityNodeInfosByViewId(viewIdResourceName)
        val target = matched?.firstOrNull() ?: return false
        val clicked = performClickOrTapBounds(target)
        WoobieState.log(LogSource.WOOBIE, "Clicked View ID '$viewIdResourceName': $clicked")
        return clicked
    }

    private fun performClickOrTapBounds(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            current = current.parent
            depth++
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.centerX() >= 0 && rect.centerY() >= 0) {
            return tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        WoobieState.log(LogSource.WOOBIE, "Tapped screen at (${x.toInt()}, ${y.toInt()})")
        return dispatched
    }

    fun typeText(textToType: String, targetFieldHint: String? = null): Boolean {
        val root = getBestRootNode() ?: return false

        var targetNode: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (targetNode == null || !targetNode.isEditable || !targetFieldHint.isNullOrBlank()) {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, allNodes)
            val editableNodes = allNodes.filter {
                it.isEditable || it.className?.toString()?.contains("EditText", ignoreCase = true) == true
            }

            targetNode = if (!targetFieldHint.isNullOrBlank()) {
                val hintQuery = targetFieldHint.trim().lowercase()
                editableNodes.firstOrNull { node ->
                    val t = node.text?.toString()?.lowercase() ?: ""
                    val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString()?.lowercase() ?: ""
                    } else ""
                    val d = node.contentDescription?.toString()?.lowercase() ?: ""
                    t.contains(hintQuery) || h.contains(hintQuery) || d.contains(hintQuery)
                } ?: editableNodes.firstOrNull()
            } else {
                editableNodes.firstOrNull()
            }
        }

        if (targetNode == null) {
            WoobieState.log(LogSource.SYSTEM, "No editable text box found on screen.")
            return false
        }

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textToType
            )
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        WoobieState.log(LogSource.WOOBIE, "Typed '$textToType' into input field ($success)")
        return success
    }

    fun scroll(direction: ScrollDirection): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        return when (direction) {
            ScrollDirection.DOWN -> {
                performSwipeGesture(w * 0.5f, h * 0.78f, w * 0.5f, h * 0.22f, 350L)
            }
            ScrollDirection.UP -> {
                performSwipeGesture(w * 0.5f, h * 0.25f, w * 0.5f, h * 0.78f, 350L)
            }
            ScrollDirection.LEFT -> {
                performSwipeGesture(w * 0.85f, h * 0.5f, w * 0.15f, h * 0.5f, 300L)
            }
            ScrollDirection.RIGHT -> {
                performSwipeGesture(w * 0.15f, h * 0.5f, w * 0.85f, h * 0.5f, 300L)
            }
        }
    }

    fun performSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 350L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        WoobieState.log(
            LogSource.WOOBIE,
            "Swiped (${startX.toInt()},${startY.toInt()}) -> (${endX.toInt()},${endY.toInt()})"
        )
        return dispatched
    }

    fun extractScreenContent(): ScreenTreeSnapshot {
        val root = getBestRootNode()
        if (root == null) {
            val emptyMsg = "No active app window detected."
            WoobieState.log(LogSource.SYSTEM, emptyMsg)
            return ScreenTreeSnapshot(
                packageName = "unknown",
                appName = "Unknown App",
                elements = emptyList(),
                readableSummary = emptyMsg
            )
        }

        val pkgName = root.packageName?.toString()
            ?: currentForegroundPackage.ifEmpty { "unknown" }
        val appName = resolveAppLabel(pkgName)

        val rawNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, rawNodes)

        val elements = mutableListOf<UiElementInfo>()
        val seenStrings = linkedSetOf<String>()

        for (node in rawNodes) {
            if (!node.isVisibleToUser) continue

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val className = node.className?.toString()?.substringAfterLast(".") ?: "View"

            if (text.isEmpty() && desc.isEmpty() && !node.isClickable && !node.isEditable) {
                continue
            }

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            elements.add(
                UiElementInfo(
                    text = text,
                    contentDescription = desc,
                    viewId = viewId,
                    className = className,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    bounds = bounds
                )
            )

            val label = when {
                text.isNotEmpty() && desc.isNotEmpty() && !text.equals(desc, ignoreCase = true) -> "$text ($desc)"
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                else -> ""
            }
            if (label.isNotEmpty()) {
                seenStrings.add(label)
            }
        }

        elements.sortWith(compareBy<UiElementInfo> { it.bounds.top }.thenBy { it.bounds.left })

        val summary = buildString {
            append("App open: $appName. ")
            if (seenStrings.isEmpty()) {
                append("No standard UI text found in window hierarchy.")
            } else {
                append("On screen content: ")
                append(seenStrings.take(40).joinToString(separator = " | "))
            }
        }

        WoobieState.log(LogSource.WOOBIE, "UI Tree Read [$appName]: ${seenStrings.size} text items found.")
        return ScreenTreeSnapshot(
            packageName = pkgName,
            appName = appName,
            elements = elements,
            readableSummary = summary
        )
    }

    fun captureNativeScreenshot(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()
                        onResult(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        WoobieState.log(LogSource.SYSTEM, "Native screenshot fallback code: $errorCode")
                        onResult(null)
                    }
                }
            )
        } else {
            onResult(null)
        }
    }

    private fun getBestRootNode(): AccessibilityNodeInfo? {
        val windowList = windows
        if (!windowList.isNullOrEmpty()) {
            for (window in windowList) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (pkg != packageName) {
                    return root
                }
            }
        }
        return rootInActiveWindow
    }

    private fun collectAllNodes(
        node: AccessibilityNodeInfo?,
        outList: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        outList.add(node)
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllNodes(child, outList)
            }
        }
    }

    private fun resolveAppLabel(pkgName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkgName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkgName
        }
    }
}
