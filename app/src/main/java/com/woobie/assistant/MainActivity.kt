package com.woobie.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshAllPermissionStates(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFF7C4DFF),
                    background = Color(0xFF0B0F19),
                    surface = Color(0xFF161C2E),
                    onPrimary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                WoobieDashboardScreen(
                    onToggleOverlayService = { shouldStart ->
                        toggleOverlayService(shouldStart)
                    }
                )
            }
        }
    }

    private fun toggleOverlayService(start: Boolean) {
        val serviceIntent = Intent(this, WoobieOverlayService::class.java)
        if (start) {
            if (!Settings.canDrawOverlays(this)) {
                WoobieState.log(LogSource.SYSTEM, "Cannot launch bubble: Overlay permission missing.")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            WoobieState.setOverlayRunning(true)
            WoobieState.setStatus(AssistantStatus.ACTIVE)
            WoobieState.log(LogSource.SYSTEM, "Woobie Floating Overlay & Voice Service started.")
        } else {
            stopService(serviceIntent)
            WoobieState.setOverlayRunning(false)
            WoobieState.setStatus(AssistantStatus.IDLE)
            WoobieState.log(LogSource.SYSTEM, "Woobie Service stopped.")
        }
    }

    companion object {
        fun refreshAllPermissionStates(context: Context) {
            val accGranted = isAccessibilityServiceEnabled(context)
            val overlayGranted = Settings.canDrawOverlays(context)
            val audioGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val screenGranted = WoobieState.mediaProjectionData != null

            WoobieState.updatePermissions(
                accessibility = accGranted,
                overlay = overlayGranted,
                audio = audioGranted,
                screenCapture = screenGranted
            )
        }

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            return enabledServices.any {
                it.resolveInfo.serviceInfo.packageName == context.packageName &&
                        it.resolveInfo.serviceInfo.name == WoobieAccessibilityService::class.java.name
            }
        }
    }
}

@Composable
fun WoobieDashboardScreen(
    onToggleOverlayService: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val status by WoobieState.status.collectAsState()
    val isOverlayRunning by WoobieState.isOverlayRunning.collectAsState()
    val permissions by WoobieState.permissions.collectAsState()
    val logs by WoobieState.commandLogs.collectAsState()

    var manualCommandText by remember { mutableStateOf("") }

    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        MainActivity.refreshAllPermissionStates(context)
        WoobieState.log(LogSource.SYSTEM, "Microphone & Notification permissions updated.")
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            WoobieState.mediaProjectionResultCode = result.resultCode
            WoobieState.mediaProjectionData = result.data
            MainActivity.refreshAllPermissionStates(context)
            WoobieState.log(LogSource.SYSTEM, "Visual Screen Capture (MediaProjection) token acquired.")
        } else {
            WoobieState.log(LogSource.SYSTEM, "Screen Capture permission declined.")
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                MainActivity.refreshAllPermissionStates(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        StatusHeroCard(
            status = status,
            isOverlayRunning = isOverlayRunning,
            allCoreGranted = permissions.allCoreGranted,
            onToggleMaster = { onToggleOverlayService(!isOverlayRunning) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SYSTEM PERMISSIONS & SENSORS",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PermissionTile(
                modifier = Modifier.weight(1f),
                title = "Phone Control",
                subtitle = if (permissions.accessibilityGranted) "Accessibility ON" else "Tap to Enable",
                icon = Icons.Default.AccessibilityNew,
                isGranted = permissions.accessibilityGranted,
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            )
            PermissionTile(
                modifier = Modifier.weight(1f),
                title = "Floating Bubble",
                subtitle = if (permissions.overlayGranted) "Overlay ON" else "Tap to Grant",
                icon = Icons.Default.BubbleChart,
                isGranted = permissions.overlayGranted,
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PermissionTile(
                modifier = Modifier.weight(1f),
                title = "Offline Voice",
                subtitle = if (permissions.audioGranted) "Mic Ready" else "Tap to Allow",
                icon = Icons.Default.Mic,
                isGranted = permissions.audioGranted,
                onClick = {
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    runtimePermissionsLauncher.launch(perms.toTypedArray())
                }
            )
            PermissionTile(
                modifier = Modifier.weight(1f),
                title = "Visual OCR",
                subtitle = if (permissions.screenCaptureGranted) "Vision Armed" else "Arm MediaProjection",
                icon = Icons.Default.RemoveRedEye,
                isGranted = permissions.screenCaptureGranted,
                onClick = {
                    val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualCommandText,
                onValueChange = { manualCommandText = it },
                placeholder = { Text("Command Woobie (e.g. 'Open WhatsApp', 'Read screen')", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF334155),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (manualCommandText.isNotBlank()) {
                        WoobieState.dispatchCommand(manualCommandText)
                        manualCommandText = ""
                    }
                },
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Execute Command",
                    tint = Color.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "COMMAND & VISION LOG",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )
            TextButton(onClick = { WoobieState.clearLogs() }) {
                Text("Clear", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs, key = { it.id }) { entry ->
                LogItemRow(entry)
            }
        }
    }
}

@Composable
fun StatusHeroCard(
    status: AssistantStatus,
    isOverlayRunning: Boolean,
    allCoreGranted: Boolean,
    onToggleMaster: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == AssistantStatus.LISTENING || status == AssistantStatus.ACTIVE) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val statusColor by animateColorAsState(
        targetValue = when (status) {
            AssistantStatus.IDLE -> Color(0xFF64748B)
            AssistantStatus.ACTIVE -> Color(0xFF00E5FF)
            AssistantStatus.LISTENING -> Color(0xFF00E676)
            AssistantStatus.PROCESSING -> Color(0xFFFFEA00)
            AssistantStatus.SPEAKING -> Color(0xFFD500F9)
        },
        label = "statusColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(statusColor.copy(alpha = 0.45f), Color.Transparent)
                            )
                        )
                        .border(2.dp, statusColor, CircleShape)
                ) {
                    Icon(
                        imageVector = if (status == AssistantStatus.LISTENING) Icons.Default.GraphicEq else Icons.Default.Memory,
                        contentDescription = "Status",
                        tint = statusColor,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "WOOBIE CORE",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = "STATUS: ${status.name}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                    Text(
                        text = if (allCoreGranted) "100% Offline • Ready" else "Grant permissions below",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Button(
                onClick = onToggleMaster,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOverlayRunning) Color(0xFFFF1744) else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (isOverlayRunning) "STOP" else "LAUNCH",
                    color = if (isOverlayRunning) Color.White else Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun PermissionTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isGranted) Color(0xFF00E676).copy(alpha = 0.6f) else Color(0xFF334155)
    val iconTint = if (isGranted) Color(0xFF00E676) else Color(0xFFFF9100)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = if (isGranted) Color(0xFF00E676) else Color(0xFF94A3B8),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun LogItemRow(entry: CommandLogEntry) {
    val badgeColor = when (entry.source) {
        LogSource.USER -> Color(0xFF00E5FF)
        LogSource.WOOBIE -> Color(0xFF00E676)
        LogSource.SCREEN_OCR -> Color(0xFFFFEA00)
        LogSource.SYSTEM -> Color(0xFF94A3B8)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "[${entry.timestamp}]",
            color = Color(0xFF64748B),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${entry.source.name}:",
            color = badgeColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.message,
            color = Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}
