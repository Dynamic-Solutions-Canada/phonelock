package home.phonelock.parent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Play build: the Play Store updates this app, and Play forbids updating it any other way, so this does
 * nothing and Settings shows no update section. The GitHub build's version updates from the releases.
 */
object AppUpdate {
    const val SUPPORTED = false

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Play updates this app itself, so it never asks to move to another (AppMove). */
    val newAppPublished: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    fun init(c: Context) {}

    fun checkIfDue() {}

    fun check(asked: Boolean) {}

    fun install() {}
}
