package ai.argvid.gen0

import ai.argvid.gen0.today.Media3MomentPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shares one runtime and player with the Activity's retained feature ViewModels. */
class AppRuntimeViewModel(application: Gen0Application) : ViewModel() {
    val runtime = SessionRuntime(application, viewModelScope)
    val player = Media3MomentPlayer(application)

    init {
        viewModelScope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { runtime.close() }
            }
        }
    }

    override fun onCleared() {
        player.release()
    }
}
