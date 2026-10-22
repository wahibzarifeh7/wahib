package com.wz.rasid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UssdCodesTest {
    private val mtn = MtnCodes(pin = "1234", cashPin = "5678", dslCode = "99", dslAreaCode = "011")
    private val syr = SyriatelCodes(distributorCode = "4444", secretCode = "7777")

    @Test
    fun normalizesMobileNumbers() {
        assertEquals("0944123456", UssdCodes.normalizeMobile("0944 123 456"))
        assertEquals("0944123456", UssdCodes.normalizeMobile("+963944123456"))
        assertEquals("0944123456", UssdCodes.normalizeMobile("00963944123456"))
        assertEquals("0944123456", UssdCodes.normalizeMobile("944123456"))
        assertTrue(UssdCodes.isValidMobile("0933-111-222"))
        assertFalse(UssdCodes.isValidMobile("0113334444"))
        assertFalse(UssdCodes.isValidMobile("09441"))
    }

    @Test
    fun buildsMtnTransfers() {
        assertEquals("*150*1234*0944123456*5000#", UssdCodes.transfer(Channel.MTN_PREPAID, "0944123456", 5000.0, mtn, syr))
        assertEquals("*154*1234*0944123456*20000#", UssdCodes.transfer(Channel.MTN_POSTPAID, "0944123456", 20000.0, mtn, syr))
        assertEquals("*151*1*5678*0944123456*15000#", UssdCodes.transfer(Channel.MTN_CASH, "0944123456", 15000.0, mtn, syr))
    }

    @Test
    fun buildsSyriatelTransfersWithRepeatedNumber() {
        assertEquals("*150*1*7777*1*1000*0933111222*0933111222#", UssdCodes.transfer(Channel.SYRIATEL_PREPAID, "0933111222", 1000.0, mtn, syr))
        assertEquals("*150*1*7777*5*2500*0933111222*0933111222#", UssdCodes.transfer(Channel.SYRIATEL_POSTPAID, "0933111222", 2500.0, mtn, syr))
        assertEquals("*150*10*7777*1*30000*0933111222*0933111222#", UssdCodes.transfer(Channel.SYRIATEL_CASH, "0933111222", 30000.0, mtn, syr))
    }

    @Test
    fun buildsDslBillsWithCountryAndAreaCode() {
        assertEquals("*160*99*963112223333*4000#", UssdCodes.transfer(Channel.MTN_DSL, "2223333", 4000.0, mtn, syr))
        assertEquals("*160*99*963212223333*4000#", UssdCodes.transfer(Channel.MTN_DSL, "021-2223333", 4000.0, mtn, syr))
        assertEquals("*160*99*963312223333*4000#", UssdCodes.transfer(Channel.MTN_DSL, "0312223333", 4000.0, mtn, syr))
    }

    @Test
    fun formatsFractionalAmounts() {
        assertEquals("1500", UssdCodes.formatAmount(1500.0))
        assertEquals("12.5", UssdCodes.formatAmount(12.5))
    }

    @Test
    fun inquiriesUseDistributorCodes() {
        assertEquals("*155*1#", UssdCodes.balanceInquiry(Channel.MTN_PREPAID, syr))
        assertEquals("*151*3#", UssdCodes.balanceInquiry(Channel.MTN_CASH, syr))
        assertNull(UssdCodes.balanceInquiry(Channel.MTN_POSTPAID, syr))
        assertEquals("*150*2*4444*7777*1#", UssdCodes.balanceInquiry(Channel.SYRIATEL_PREPAID, syr))
        assertEquals("*150*2*4444*7777*5#", UssdCodes.balanceInquiry(Channel.SYRIATEL_POSTPAID, syr))
        assertEquals("*150*12*4444*7777*3#", UssdCodes.balanceInquiry(Channel.SYRIATEL_CASH, syr))
        assertEquals("*155*3#", UssdCodes.lastTransfers(Carrier.MTN, syr))
        assertEquals("*150*6*4444*7777*0#", UssdCodes.lastTransfers(Carrier.SYRIATEL, syr))
    }

    @Test
    fun rejectsMissingCodesAndBadInput() {
        val e = assertThrows(MissingCodesException::class.java) {
            UssdCodes.transfer(Channel.SYRIATEL_PREPAID, "0933111222", 1000.0, mtn, SyriatelCodes())
        }
        assertEquals(UssdCodes.SYRIATEL_SPC_MISSING, e.message)
        assertThrows(IllegalArgumentException::class.java) {
            UssdCodes.transfer(Channel.MTN_PREPAID, "123", 1000.0, mtn, syr)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UssdCodes.transfer(Channel.MTN_PREPAID, "0944123456", 0.0, mtn, syr)
        }
        assertThrows(MissingCodesException::class.java) {
            UssdCodes.transfer(Channel.MTN_DSL, "2223333", 1000.0, mtn.copy(dslAreaCode = ""), syr)
        }
    }

    @Test
    fun masksSecretsForDisplay() {
        val code = UssdCodes.transfer(Channel.MTN_PREPAID, "0944123456", 5000.0, mtn, syr)
        assertEquals("*150*••••*0944123456*5000#", UssdCodes.mask(code, mtn, syr))
        val s = UssdCodes.transfer(Channel.SYRIATEL_PREPAID, "0933111222", 1000.0, mtn, syr)
        assertEquals("*150*1*••••*1*1000*0933111222*0933111222#", UssdCodes.mask(s, mtn, syr))
    }
}
