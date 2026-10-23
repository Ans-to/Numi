package com.calcvault

import com.calcvault.core.Calculator
import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorUnitTest {
    @Test fun calculatorModuleIsLinkedToApp() { assertEquals("0.3", Calculator.evaluate("0.1+0.2")) }
}
