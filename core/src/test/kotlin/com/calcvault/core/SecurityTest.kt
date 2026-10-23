package com.calcvault.core

import org.junit.Test

class SecurityTest {
    @Test fun adversarialCryptoAndDocumentedLimits() = SecurityChecks.runAll()
}
