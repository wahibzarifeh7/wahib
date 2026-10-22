package com.wz.rasid.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.wz.rasid.core.Carrier
import com.wz.rasid.data.SimLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where a reply came from: the telephony API callback or the dialer window read by accessibility. */
enum class ReplyOrigin { API, DIALER }

data class UssdOutcome(val delivered: Boolean, val message: String, val origin: ReplyOrigin)

class UssdUnavailableException(message: String) : IllegalStateException(message)

/** Runs one USSD request at a time on the SIM that belongs to the requested carrier. */
class UssdExecutor(private val context: Context) {
    private val lock = Mutex()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun missingPermissions(): List<String> =
        listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Sends the request through TelephonyManager and waits for the network reply. */
    @SuppressLint("MissingPermission")
    suspend fun run(code: String, carrier: Carrier, layout: SimLayout): UssdOutcome = exclusive {
        val tm = telephonyFor(carrier, layout)
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                tm.sendUssdRequest(
                    code,
                    object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) {
                            if (cont.isActive) cont.resume(UssdOutcome(true, response.toString(), ReplyOrigin.API))
                        }

                        override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                            val reason = if (failureCode == TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL) {
                                "خدمة USSD غير متاحة على هذه الشريحة حالياً."
                            } else {
                                "لم يستطع التطبيق قراءة الرد. قد تكون العملية نجحت، تأكد من الرسائل قبل إعادة المحاولة."
                            }
                            if (cont.isActive) cont.resume(UssdOutcome(false, reason, ReplyOrigin.API))
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
        } ?: UssdOutcome(false, "لم يتم التقاط رد الشبكة ضمن المهلة المحددة.", ReplyOrigin.API)
    }

    /**
     * Dials the code through the phone app on the carrier's SIM. The reply dialog is read by
     * [UssdCaptureService] when the user has enabled it; otherwise the user reads it on screen.
     */
    @SuppressLint("MissingPermission")
    suspend fun runViaDialer(code: String, carrier: Carrier, layout: SimLayout): UssdOutcome = exclusive {
        val sub = subscriptionFor(carrier, layout)
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(code)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (sub != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val telecom = context.getSystemService(TelecomManager::class.java)
            val tm = context.getSystemService(TelephonyManager::class.java)
            telecom.callCapablePhoneAccounts
                .firstOrNull { tm.getSubscriptionId(it) == sub.subscriptionId }
                ?.let { intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        val capture = UssdCaptureService.isEnabled(context)
        if (capture) UssdCaptureService.arm()
        try {
            context.startActivity(intent)
            if (!capture) {
                return@exclusive UssdOutcome(false, "تم إرسال الطلب عبر الاتصال. فعّل خدمة إمكانية الوصول ليقرأ التطبيق الرد تلقائياً.", ReplyOrigin.DIALER)
            }
            val reply = withTimeoutOrNull(TIMEOUT_MS) { UssdCaptureService.replies.first() }
            if (reply != null) UssdOutcome(true, reply, ReplyOrigin.DIALER)
            else UssdOutcome(false, "لم يتم التقاط رد الشبكة ضمن المهلة المحددة.", ReplyOrigin.DIALER)
        } finally {
            UssdCaptureService.disarm()
        }
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T {
        if (lock.isLocked) throw UssdUnavailableException("يوجد طلب USSD قيد التنفيذ، يرجى انتظار انتهاء العملية الحالية.")
        return lock.withLock {
            _busy.value = true
            try {
                missingPermissions().takeIf { it.isNotEmpty() }?.let {
                    throw UssdUnavailableException("يلزم منح إذن إجراء المكالمات وقراءة حالة الهاتف لتشغيل USSD.")
                }
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun telephonyFor(carrier: Carrier, layout: SimLayout): TelephonyManager {
        val base = context.getSystemService(TelephonyManager::class.java)
        val sub = subscriptionFor(carrier, layout)
            ?: throw UssdUnavailableException("تعذر العثور على شريحة SIM نشطة لشبكة ${carrier.label}. تحقق من ترتيب الشرائح في الإعدادات.")
        return base.createForSubscriptionId(sub.subscriptionId)
    }

    /** Matches the SIM by Syrian network code, then by carrier name, then by the configured slot order. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun subscriptionFor(carrier: Carrier, layout: SimLayout): SubscriptionInfo? {
        val subs = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        if (subs.isEmpty()) return null
        val mnc = if (carrier == Carrier.MTN) MNC_MTN else MNC_SYRIATEL
        subs.firstOrNull {
            val (subMcc, subMnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.mccString?.toIntOrNull() to it.mncString?.toIntOrNull()
            } else {
                it.mcc to it.mnc
            }
            subMcc == MCC_SYRIA && subMnc == mnc
        }?.let { return it }
        val names = if (carrier == Carrier.MTN) listOf("mtn") else listOf("syriatel", "سيرياتيل")
        subs.firstOrNull { s ->
            val name = "${s.carrierName} ${s.displayName}".lowercase()
            names.any { name.contains(it) }
        }?.let { return it }
        val slot = when (layout) {
            SimLayout.MTN_FIRST -> if (carrier == Carrier.MTN) 0 else 1
            SimLayout.SYRIATEL_FIRST -> if (carrier == Carrier.MTN) 1 else 0
            SimLayout.AUTO -> return subs.singleOrNull()
        }
        return subs.firstOrNull { it.simSlotIndex == slot }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val MCC_SYRIA = 417
        const val MNC_SYRIATEL = 1
        const val MNC_MTN = 2
    }
}
