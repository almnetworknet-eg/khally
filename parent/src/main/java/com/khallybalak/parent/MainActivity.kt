package com.khallybalak.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.khallybalak.parent.ui.AuthScreen
import com.khallybalak.parent.ui.ChildScreen
import com.khallybalak.parent.ui.HomeScreen
import com.khallybalak.parent.ui.LiveScreen
import com.khallybalak.shared.KhallyTheme

sealed interface Screen {
    data object Home : Screen
    data class Child(val id: String) : Screen
    data class Live(val id: String, val name: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Session.init(this)
        com.khallybalak.shared.ServerConfig.init(this)
        setContent { KhallyTheme { ParentApp() } }
    }
}

@Composable
fun ParentApp() {
    var loggedIn by remember { mutableStateOf(Session.token != null) }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }

    if (!loggedIn) {
        AuthScreen(onDone = { loggedIn = true; stack = listOf(Screen.Home) })
        return
    }

    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
    val pop = { stack = stack.dropLast(1) }

    when (val top = stack.last()) {
        Screen.Home -> HomeScreen(
            onOpen = { stack = stack + Screen.Child(it) },
            onLogout = { ParentRepo.logout(); loggedIn = false },
        )
        is Screen.Child -> ChildScreen(
            id = top.id,
            onBack = pop,
            onLive = { name -> stack = stack + Screen.Live(top.id, name) },
        )
        is Screen.Live -> LiveScreen(top.id, top.name, onBack = pop)
    }
}
