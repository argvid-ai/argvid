package ai.argvid.gen0

import ai.argvid.gen0.session.AppPermission
import ai.argvid.gen0.session.DomainSessionCapture
import ai.argvid.gen0.session.DomainSessionGimbal
import ai.argvid.gen0.session.DomainSessionMoments
import ai.argvid.gen0.session.PermissionCoordinator
import ai.argvid.gen0.session.SessionRoute
import ai.argvid.gen0.session.SessionViewModel
import ai.argvid.gen0.media.catalog.ContentResolverAssetVerifier
import ai.argvid.gen0.media.catalog.MediaStoreChangeObserver
import ai.argvid.gen0.media.catalog.RoomTodayMomentStore
import ai.argvid.gen0.media.catalog.TodayRepository
import ai.argvid.gen0.media.delete.AppPrivateStagingStore
import ai.argvid.gen0.media.delete.LocalDeletionCoordinator
import ai.argvid.gen0.media.delete.MediaStoreLocalMediaDeleter
import ai.argvid.gen0.media.delete.RoomLocalDeletionStore
import ai.argvid.gen0.media.store.ContentResolverMediaStoreClient
import ai.argvid.gen0.today.CoordinatorLocalMomentDeletion
import ai.argvid.gen0.today.MomentPlayerSurface
import ai.argvid.gen0.today.RepositoryTodaySource
import ai.argvid.gen0.today.TodayScreen
import ai.argvid.gen0.today.TodayViewModel
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.provider.MediaStore
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@Composable
fun Gen0App() {
    val context = LocalContext.current
    val application = context.applicationContext as Gen0Application
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val runtimeOwner: AppRuntimeViewModel = viewModel(factory = remember(application) {
        viewModelFactory { initializer { AppRuntimeViewModel(application) } }
    })
    val runtime = runtimeOwner.runtime
    val todayPlayer = runtimeOwner.player
    val todayRepository = remember {
        TodayRepository(
            RoomTodayMomentStore(application.database.momentDao()),
            ContentResolverAssetVerifier(context.contentResolver),
        )
    }
    val localDeletion = remember {
        CoordinatorLocalMomentDeletion(
            LocalDeletionCoordinator(
                store = RoomLocalDeletionStore(application.database.momentDao()),
                media = MediaStoreLocalMediaDeleter(
                    ContentResolverMediaStoreClient(context.contentResolver),
                ),
                staging = AppPrivateStagingStore(File(context.cacheDir, "rescued-moments")),
            ),
        )
    }
    val preview = remember { PreviewView(context) }
    val factory = remember(runtime) {
        viewModelFactory {
            initializer {
                SessionViewModel(
                    capture = DomainSessionCapture(runtime.capture),
                    moments = DomainSessionMoments(runtime.moments),
                    gimbal = DomainSessionGimbal(runtime.gimbal),
                    permissionCoordinator = PermissionCoordinator(),
                    clock = ai.argvid.gen0.domain.time.MonotonicClock { System.nanoTime() / 1_000 },
                )
            }
        }
    }
    val sessionViewModel: SessionViewModel = viewModel(factory = factory)
    val todayFactory = remember(todayRepository, todayPlayer, localDeletion) {
        viewModelFactory {
            initializer {
                TodayViewModel(
                    RepositoryTodaySource(todayRepository),
                    todayPlayer,
                    deletion = localDeletion,
                )
            }
        }
    }
    val todayViewModel: TodayViewModel = viewModel(key = "today", factory = todayFactory)
    val todayState by todayViewModel.state.collectAsState()
    val deletionState by todayViewModel.deletionState.collectAsState()
    var destination by remember { mutableStateOf(AppDestination.Session) }
    var pendingPermission by remember { mutableStateOf<AppPermission?>(null) }
    var cameraStartJob by remember { mutableStateOf<Job?>(null) }
    val completeCameraRequest: (AppPermission, Boolean) -> Unit = { permission, granted ->
        cameraStartJob = scope.launch {
            val ready = if (granted && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                runCatching { runtime.startCamera(lifecycleOwner, preview.surfaceProvider) }.isSuccess
            } else false
            sessionViewModel.onPermissionResult(permission, ready)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val permission = pendingPermission ?: return@rememberLauncherForActivityResult
        pendingPermission = null
        completeCameraRequest(permission, results.values.isNotEmpty() && results.values.all { it })
    }

    DisposableEffect(lifecycleOwner, sessionViewModel, todayViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                cameraStartJob?.cancel()
                pendingPermission = null
                sessionViewModel.onAppStopped()
                todayViewModel.onStop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(runtime, sessionViewModel) {
        runtime.warmupDurationUs.collect(sessionViewModel::onWarmupProgress)
    }
    LaunchedEffect(runtime, sessionViewModel) {
        runtime.gimbal.link.motion.collect(sessionViewModel::onMotion)
    }
    DisposableEffect(todayViewModel) {
        val observer = MediaStoreChangeObserver(
            context.contentResolver,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ) { todayViewModel.retry() }
        observer.start()
        onDispose { observer.stop() }
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = destination == AppDestination.Session,
                        onClick = {
                            todayViewModel.onStop()
                            destination = AppDestination.Session
                        },
                        icon = { Text("●") },
                        label = { Text("Session") },
                    )
                    NavigationBarItem(
                        selected = destination == AppDestination.Today,
                        onClick = {
                            cameraStartJob?.cancel()
                            pendingPermission = null
                            sessionViewModel.onAppStopped()
                            destination = AppDestination.Today
                        },
                        icon = { Text("■") },
                        label = { Text("Today") },
                    )
                }
            },
        ) { innerPadding ->
            when (destination) {
                AppDestination.Session -> SessionRoute(
                    viewModel = sessionViewModel,
                    modifier = Modifier.padding(innerPadding),
                    onPermissionRequest = { permission ->
                        val requested = permission.runtimePermissions()
                        if (requested.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                            completeCameraRequest(permission, true)
                        } else {
                            pendingPermission = permission
                            permissionLauncher.launch(requested)
                        }
                    },
                    previewContent = {
                        AndroidView(factory = { preview })
                    },
                )
                AppDestination.Today -> TodayScreen(
                    state = todayState,
                    deletionState = deletionState,
                    onPlay = todayViewModel::play,
                    onRetry = todayViewModel::retry,
                    onDeleteLocal = todayViewModel::requestLocalDeletion,
                    onConfirmDelete = todayViewModel::confirmLocalDeletion,
                    onDismissDelete = todayViewModel::dismissLocalDeletion,
                    onRetryDelete = todayViewModel::retryLocalDeletion,
                    onClearRecord = todayViewModel::clearLocalRecord,
                    deleteEnabled = true,
                    modifier = Modifier.padding(innerPadding),
                    playerContent = { MomentPlayerSurface(todayPlayer, Modifier.fillMaxSize()) },
                )
            }
        }
    }
}

private enum class AppDestination {
    Session,
    Today,
}

private fun AppPermission.runtimePermissions(): Array<String> = when (this) {
    AppPermission.Camera -> arrayOf(Manifest.permission.CAMERA)
}
