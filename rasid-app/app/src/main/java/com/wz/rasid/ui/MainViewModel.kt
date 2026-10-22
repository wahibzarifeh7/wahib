package com.wz.rasid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wz.rasid.RasidApp
import com.wz.rasid.core.Carrier
import com.wz.rasid.core.Channel
import com.wz.rasid.core.MissingCodesException
import com.wz.rasid.core.TxStatus
import com.wz.rasid.core.UssdCodes
import com.wz.rasid.core.UssdResponse
import com.wz.rasid.data.AppSettings
import com.wz.rasid.data.Repository
import com.wz.rasid.data.TransactionEntity
import com.wz.rasid.data.TransferDraft
import com.wz.rasid.ussd.ReplyOrigin
import com.wz.rasid.ussd.UssdOutcome
import com.wz.rasid.ussd.UssdUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the USSD dialog currently shows. */
sealed interface UssdDialog {
    /** A transfer awaiting the user's check of the masked code before it is sent. */
    data class Confirm(val draft: TransferDraft, val code: String, val masked: String) : UssdDialog

    data class Running(val title: String) : UssdDialog

    data class Result(
        val title: String,
        val message: String,
        val status: TxStatus? = null,
        val balanceChannel: Channel? = null,
        val extractedBalance: Double? = null,
    ) : UssdDialog
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo: Repository = (app as RasidApp).repository
    private val ussd = (app as RasidApp).ussd

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    val settings = repo.settings.state(AppSettings())
    val balances = repo.balances.state(emptyList())
    val categories = repo.categories.state(emptyList())
    val customers = repo.customers.state(emptyList())
    val customerBalances = repo.customerBalances.state(emptyMap())
    val knownNumbers = repo.knownNumbers.state(emptyList())
    val shifts = repo.shifts.state(emptyList())
    val openShift = repo.openShift.state(null)
    val shiftTransactions: StateFlow<List<TransactionEntity>> =
        openShift.flatMapLatest { s -> s?.let { repo.shiftTransactions(it.id) } ?: flowOf(emptyList()) }.state(emptyList())

    val dialog = MutableStateFlow<UssdDialog?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    fun missingPermissions(): List<String> = ussd.missingPermissions()

    /** Builds the USSD string and asks for confirmation; reports missing settings instead of sending. */
    fun prepareTransfer(draft: TransferDraft) {
        val s = settings.value
        try {
            val code = UssdCodes.transfer(draft.channel, draft.target, draft.amount, s.mtn, s.syriatel)
            dialog.value = UssdDialog.Confirm(draft, code, UssdCodes.mask(code, s.mtn, s.syriatel))
        } catch (e: MissingCodesException) {
            toast(e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "الرجاء التأكد من صحة المدخلات")
        }
    }

    fun executeTransfer(confirm: UssdDialog.Confirm, viaDialer: Boolean) {
        val draft = confirm.draft
        dialog.value = UssdDialog.Running("تحويل ${draft.channel.label}")
        viewModelScope.launch {
            val outcome = send(confirm.code, draft.channel.carrier, viaDialer) ?: return@launch
            // Without a readable reply the transfer may still have gone through, so it is flagged for review.
            val status = if (outcome.delivered) UssdResponse.classify(outcome.message) else TxStatus.REVIEW
            repo.record(draft, status, confirm.masked, outcome.message)
            dialog.value = UssdDialog.Result(
                title = "${draft.channel.label} — ${draft.target}",
                message = outcome.message,
                status = status,
            )
        }
    }

    /** Logs a sale made outside the app ("إضافة للسجل بدون تحويل فعلي"). */
    fun recordWithoutTransfer(draft: TransferDraft) {
        viewModelScope.launch {
            repo.record(draft, TxStatus.RECORDED, null, null)
            toast("تم الحفظ")
        }
    }

    fun balanceInquiry(channel: Channel) {
        val code: String?
        try {
            code = UssdCodes.balanceInquiry(channel, settings.value.syriatel)
        } catch (e: MissingCodesException) {
            toast(e.message.orEmpty())
            return
        }
        if (code == null) {
            toast("لا يوجد كود استعلام لهذا الرصيد")
            return
        }
        inquiry("استعلام رصيد ${channel.label}", code, channel.carrier, channel)
    }

    fun lastTransfers(carrier: Carrier) {
        val code = try {
            UssdCodes.lastTransfers(carrier, settings.value.syriatel)
        } catch (e: MissingCodesException) {
            toast(e.message.orEmpty())
            return
        }
        inquiry("آخر تحويلات ${carrier.label}", code, carrier, null)
    }

    private fun inquiry(title: String, code: String, carrier: Carrier, channel: Channel?) {
        dialog.value = UssdDialog.Running(title)
        viewModelScope.launch {
            val outcome = send(code, carrier, viaDialer = false) ?: return@launch
            dialog.value = UssdDialog.Result(
                title = title,
                message = outcome.message,
                balanceChannel = channel,
                extractedBalance = if (outcome.delivered && channel != null) UssdResponse.extractBalance(outcome.message) else null,
            )
        }
    }

    private suspend fun send(code: String, carrier: Carrier, viaDialer: Boolean): UssdOutcome? = try {
        val layout = settings.value.simLayout
        if (viaDialer) ussd.runViaDialer(code, carrier, layout) else ussd.run(code, carrier, layout)
    } catch (e: UssdUnavailableException) {
        dialog.value = UssdDialog.Result("تعذر تنفيذ الطلب", e.message.orEmpty())
        null
    } catch (e: SecurityException) {
        dialog.value = UssdDialog.Result("تعذر تنفيذ الطلب", "الإذن مطلوب: ${e.message.orEmpty()}")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UssdOutcome(false, "حدث خطأ غير متوقع: ${e.message.orEmpty()}", ReplyOrigin.API)
    }

    fun dismissDialog() {
        dialog.value = null
    }

    /** Runs [action] only after the shift/accounting password checks out. */
    fun withPassword(password: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            if (repo.checkPassword(password)) action() else toast("كلمة السر غير صحيحة")
        }
    }

    fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
