package androidx.activity

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Small compatibility implementation for the Compose back handler used by Miit.
 * It delegates to ComponentActivity's OnBackPressedDispatcher without requiring
 * the optional activity-compose BackHandler symbol.
 */
@Composable
fun BackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity

    DisposableEffect(activity, enabled, onBack) {
        if (activity == null) return@DisposableEffect onDispose { }

        val callback = object : androidx.activity.OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() = onBack()
        }
        activity.onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}
