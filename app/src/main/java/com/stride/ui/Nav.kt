package com.stride.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Tab(val label: String) { HOME("Home"), ROUTES("Maps"), RECORD("Record"), PROGRESS("Progress"), YOU("You") }

sealed interface Screen {
    data object Tabs : Screen
    data class Record(val routeId: String? = null) : Screen
    data object SaveRecording : Screen
    data class ActivityDetail(val id: String) : Screen
    data class EditActivity(val id: String) : Screen
    data class ShareActivity(val id: String) : Screen
    data class CreateSegment(val activityId: String) : Screen
    data class SegmentDetail(val id: String) : Screen
    data class RouteDetail(val id: String) : Screen
    data object RouteBuilder : Screen
}

/** Tiny back-stack navigator; no extra library needed. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Tabs)
    var tab by mutableStateOf(Tab.HOME)

    val top: Screen get() = stack.last()
    fun push(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
    fun home(tab: Tab = this.tab) { while (stack.size > 1) stack.removeAt(stack.lastIndex); this.tab = tab }
}

/** Short-lived messages shown in the snackbar. */
object Toasts {
    val queue = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun show(msg: String) { queue.tryEmit(msg) }
}
