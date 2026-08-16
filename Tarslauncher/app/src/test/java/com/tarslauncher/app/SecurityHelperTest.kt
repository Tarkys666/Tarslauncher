package com.tarslauncher.app

import com.tarslauncher.app.utils.SecurityHelper
import org.junit.Assert.*
import org.junit.Test

class SecurityHelperTest {

    @Test
    fun testValidatePackageNameValid() {
        assertTrue(SecurityHelper.validatePackageName("com.example.app"))
        assertTrue(SecurityHelper.validatePackageName("com.tarslauncher.app"))
        assertTrue(SecurityHelper.validatePackageName("org.mozilla.firefox"))
        assertTrue(SecurityHelper.validatePackageName("com.google.android.youtube"))
    }

    @Test
    fun testValidatePackageNameInvalid() {
        assertFalse(SecurityHelper.validatePackageName(""))
        assertFalse(SecurityHelper.validatePackageName("   "))
        assertFalse(SecurityHelper.validatePackageName("singleword"))
        assertFalse(SecurityHelper.validatePackageName("com.example..doubleDot"))
        assertFalse(SecurityHelper.validatePackageName("123com.starts.with.number"))
        assertFalse(SecurityHelper.validatePackageName("com.example.app with spaces"))
    }
}
