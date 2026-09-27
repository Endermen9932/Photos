package app.lumen.photos.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.lumen.photos.ui.LocalNavAnimatedScope
import app.lumen.photos.ui.LocalSharedTransitionScope
import app.lumen.photos.ui.screens.backup.BackupScreen
import app.lumen.photos.ui.screens.collection.CollectionScreen
import app.lumen.photos.ui.screens.duplicates.DuplicatesScreen
import app.lumen.photos.ui.screens.editor.EditorScreen
import app.lumen.photos.ui.screens.home.HomeScreen
import app.lumen.photos.ui.screens.models.ModelsScreen
import app.lumen.photos.ui.screens.onboarding.OnboardingScreen
import app.lumen.photos.ui.screens.optimize.OptimizeScreen
import app.lumen.photos.ui.screens.settings.SettingsScreen
import app.lumen.photos.ui.screens.viewer.ExternalViewerScreen
import app.lumen.photos.ui.screens.viewer.ViewerScreen
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object OnboardingRoute
@Serializable data class ViewerRoute(val source: String, val id: Long)
@Serializable data class ExternalViewerRoute(val uri: String, val mime: String?)
@Serializable data class CollectionRoute(val source: String, val title: String)
@Serializable object OptimizeRoute
@Serializable object DuplicatesRoute
@Serializable object ModelsRoute
@Serializable object SettingsRoute
@Serializable data class EditorRoute(val id: Long)
@Serializable object BackupRoute

class Navigator(private val controller: NavHostController) {
    fun back() {
        controller.popBackStack()
    }

    fun viewer(source: String, id: Long) = controller.navigate(ViewerRoute(source, id)) { launchSingleTop = true }
    fun collection(source: String, title: String) = controller.navigate(CollectionRoute(source, title))
    fun optimize() = controller.navigate(OptimizeRoute) { launchSingleTop = true }
    fun duplicates() = controller.navigate(DuplicatesRoute) { launchSingleTop = true }
    fun models() = controller.navigate(ModelsRoute) { launchSingleTop = true }
    fun settings() = controller.navigate(SettingsRoute) { launchSingleTop = true }
    fun editor(id: Long) = controller.navigate(EditorRoute(id))
    fun backup() = controller.navigate(BackupRoute) { launchSingleTop = true }
    fun external(uri: String, mime: String?) = controller.navigate(ExternalViewerRoute(uri, mime))
    fun finishOnboarding() = controller.navigate(HomeRoute) {
        popUpTo(OnboardingRoute) { inclusive = true }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

private val slideIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(380)) { it / 4 } + fadeIn(tween(300))
}
private val slideOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(380)) { -it / 6 } + fadeOut(tween(250))
}
private val popIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(380)) { -it / 6 } + fadeIn(tween(300))
}
private val popOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(380)) { it / 4 } + fadeOut(tween(250))
}

@Composable
fun LumenNavHost(startOnboarding: Boolean, onNavigatorReady: (Navigator) -> Unit) {
    val controller = rememberNavController()
    val navigator = remember(controller) { Navigator(controller) }
    androidx.compose.runtime.LaunchedEffect(navigator) { onNavigatorReady(navigator) }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalNavigator provides navigator) {
            NavHost(
                navController = controller,
                startDestination = if (startOnboarding) OnboardingRoute else HomeRoute,
                enterTransition = slideIn,
                exitTransition = slideOut,
                popEnterTransition = popIn,
                popExitTransition = popOut,
            ) {
                composable<OnboardingRoute> {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { OnboardingScreen() }
                }
                composable<HomeRoute>(
                    exitTransition = { fadeOut(tween(300)) },
                    popEnterTransition = { fadeIn(tween(300)) },
                ) {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { HomeScreen() }
                }
                composable<ViewerRoute>(
                    enterTransition = { fadeIn(tween(250)) },
                    exitTransition = { fadeOut(tween(250)) },
                    popEnterTransition = { fadeIn(tween(250)) },
                    popExitTransition = { fadeOut(tween(300)) },
                ) { entry ->
                    val route = entry.toRoute<ViewerRoute>()
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { ViewerScreen(route.source, route.id) }
                }
                composable<ExternalViewerRoute>(
                    enterTransition = { fadeIn() + scaleIn(initialScale = 0.92f) },
                    popExitTransition = { fadeOut() + scaleOut(targetScale = 0.92f) },
                ) { entry ->
                    val route = entry.toRoute<ExternalViewerRoute>()
                    ExternalViewerScreen(route.uri, route.mime)
                }
                composable<CollectionRoute> { entry ->
                    val route = entry.toRoute<CollectionRoute>()
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { CollectionScreen(route.source, route.title) }
                }
                composable<OptimizeRoute> { OptimizeScreen() }
                composable<DuplicatesRoute> {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { DuplicatesScreen() }
                }
                composable<ModelsRoute> { ModelsScreen() }
                composable<SettingsRoute> { SettingsScreen() }
                composable<BackupRoute> { BackupScreen() }
                composable<EditorRoute>(
                    enterTransition = { fadeIn() + scaleIn(initialScale = 0.94f) },
                    popExitTransition = { fadeOut() + scaleOut(targetScale = 0.94f) },
                ) { entry -> EditorScreen(entry.toRoute<EditorRoute>().id) }
            }
        }
    }
}
