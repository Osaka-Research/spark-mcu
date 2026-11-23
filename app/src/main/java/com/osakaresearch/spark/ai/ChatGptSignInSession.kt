package com.osakaresearch.spark.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Keep browser sign-in independent of the AI screen's composition/activity lifetime. */
object ChatGptSignInSession {
    data class State(val busy: Boolean = false, val error: String = "", val revision: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun start(context: Context, store: ChatGptPlanStore) {
        if (state.value.busy) return
        val appContext = context.applicationContext
        run { ChatGptPlanAuth.signIn(appContext, store) }
    }

    fun finish(store: ChatGptPlanStore, callbackUrl: String) {
        // Reject a pasted link before stopping a working listener.
        try {
            val pending = store.pendingSignIn() ?: error("Sign-in expired. Start again.")
            parseCallback(callbackUrl, pending.redirectUri, pending.state)
        } catch (e: Exception) {
            mutableState.value = state.value.copy(error = e.message ?: "Invalid callback link")
            return
        }
        run { ChatGptPlanAuth.completeSignIn(store, callbackUrl) }
    }

    private fun run(action: suspend () -> Unit) {
        val previous = job
        val revision = state.value.revision + 1
        mutableState.value = State(busy = true, revision = revision)
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                action()
                mutableState.value = State(revision = revision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = State(error = e.message ?: "ChatGPT sign-in failed", revision = revision)
            }
        }
    }

    fun cancel() {
        val previous = job
        val revision = state.value.revision + 1
        mutableState.value = State(busy = true, revision = revision)
        job = scope.launch {
            previous?.cancelAndJoin()
            mutableState.value = State(revision = revision)
        }
    }
}
