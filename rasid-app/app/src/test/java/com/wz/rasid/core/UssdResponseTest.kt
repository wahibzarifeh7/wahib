package com.wz.rasid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UssdResponseTest {
    @Test
    fun classifiesCarrierReplies() {
        assertEquals(TxStatus.SUCCESS, UssdResponse.classify("تم تحويل 5000 وحدة إلى الرقم 0944123456 بنجاح"))
        assertEquals(TxStatus.SUCCESS, UssdResponse.classify("تمت العملية"))
        assertEquals(TxStatus.FAILED, UssdResponse.classify("لم يتم التحويل، الرصيد غير كاف"))
        assertEquals(TxStatus.FAILED, UssdResponse.classify("رمز غير صحيح"))
        assertEquals(TxStatus.FAILED, UssdResponse.classify("PIN غير صحيح"))
        assertEquals(TxStatus.PENDING, UssdResponse.classify("طلبك قيد المعالجة"))
        assertEquals(TxStatus.REVIEW, UssdResponse.classify("أهلاً بك"))
        assertEquals(TxStatus.REVIEW, UssdResponse.classify(null))
    }

    @Test
    fun extractsBalanceFromInquiry() {
        assertEquals(1250000.0, UssdResponse.extractBalance("رصيدك الحالي 1,250,000 ل.س")!!, 0.0)
        assertEquals(3500.5, UssdResponse.extractBalance("الرصيد: ٣٥٠٠٫٥")!!, 0.0)
        assertEquals(800.0, UssdResponse.extractBalance("Your account 12 has 800")!!, 0.0)
    }
}
