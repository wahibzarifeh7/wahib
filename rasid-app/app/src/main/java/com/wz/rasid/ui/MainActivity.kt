package com.wz.rasid.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch

private enum class Screen(val route: String, val title: String) {
    TRANSFER("transfer", "الواجهة الرئيسية"),
    HISTORY("history", "سجل التحويل"),
    BALANCES("balances", "الأرصدة"),
    CUSTOMERS("customers", "سجل الزبائن"),
    SHIFTS("shifts", "سجل الورديات"),
    CATEGORIES("categories", "فئات الرصيد"),
    SETTINGS("settings", "الإعدادات"),
}

private const val CUSTOMER_ROUTE = "customer/{id}"

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { !it }) vm.toast("يلزم منح إذن إجراء المكالمات وقراءة حالة الهاتف لتشغيل USSD.")
    }

    private fun askUssdPermissions() {
        permissions.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && vm.missingPermissions().isNotEmpty()) askUssdPermissions()
        setContent {
            RasidTheme { AppUi(vm, ::askUssdPermissions) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUi(vm: MainViewModel, requestPermissions: () -> Unit) {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val current = Screen.entries.firstOrNull { route?.startsWith(it.route) == true }
    val onDetail = route == CUSTOMER_ROUTE

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    fun go(screen: Screen) {
        nav.navigate(screen.route) {
            popUpTo(Screen.TRANSFER.route)
            launchSingleTop = true
        }
        scope.launch { drawer.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !onDetail,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "رصيد — نقطة بيع الوحدات",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp),
                )
                Screen.entries.forEach { s ->
                    NavigationDrawerItem(
                        label = { Text(s.title) },
                        selected = s == current && !onDetail,
                        onClick = { go(s) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (onDetail) "كشف الحساب" else current?.title ?: "") },
                    navigationIcon = {
                        if (onDetail) {
                            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                        } else {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "فتح القائمة") }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(nav, startDestination = Screen.TRANSFER.route) {
                    composable(Screen.TRANSFER.route) { TransferScreen(vm) }
                    composable(
                        Screen.HISTORY.route + "?shift={shift}",
                        arguments = listOf(navArgument("shift") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { e ->
                        HistoryScreen(vm, e.arguments?.getString("shift")?.toLongOrNull())
                    }
                    composable(Screen.BALANCES.route) { BalancesScreen(vm) }
                    composable(Screen.CUSTOMERS.route) { CustomersScreen(vm) { id -> nav.navigate("customer/$id") } }
                    composable(CUSTOMER_ROUTE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                        CustomerDetailScreen(vm, e.arguments?.getLong("id") ?: 0L) { nav.popBackStack() }
                    }
                    composable(Screen.SHIFTS.route) {
                        ShiftsScreen(vm) { id -> nav.navigate(Screen.HISTORY.route + "?shift=$id") }
                    }
                    composable(Screen.CATEGORIES.route) { CategoriesScreen(vm) }
                    composable(Screen.SETTINGS.route) { SettingsScreen(vm, requestPermissions) }
                }
            }
        }
    }

    UssdDialogHost(vm)
}
