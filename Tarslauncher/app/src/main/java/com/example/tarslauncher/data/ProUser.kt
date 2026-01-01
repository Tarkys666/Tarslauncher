package com.example.tarslauncher.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Pro User data model for Firestore
 */
data class ProUser(
    @DocumentId
    val id: String = "",
    
    // Google Account Info
    val googleAccountId: String = "",
    val email: String = "",
    val displayName: String = "",
    
    // Purchase Details
    val purchaseToken: String = "",
    val orderId: String = "",
    val productId: String = "tarslauncher_pro_version",
    val purchaseTime: Long = 0L,
    val purchaseState: Int = 0, // 0 = UNSPECIFIED, 1 = PURCHASED, 2 = PENDING
    
    // Registration Info
    @ServerTimestamp
    val registrationTime: Date? = null,
    val isActive: Boolean = true,
    
    // Device Info
    val deviceModel: String = "",
    val androidVersion: String = "",
    val appVersion: String = "",
    
    // Statistics
    val lastLoginTime: Long = 0L,
    val loginCount: Int = 0,
    
) {
    // Firestore requires a no-argument constructor
    constructor() : this(
        id = "",
        googleAccountId = "",
        email = "",
        displayName = "",
        purchaseToken = "",
        orderId = "",
        productId = "tarslauncher_pro_version",
        purchaseTime = 0L,
        purchaseState = 0,
        registrationTime = null,
        isActive = true,
        deviceModel = "",
        androidVersion = "",
        appVersion = "",
        lastLoginTime = 0L,
        loginCount = 0
    )
    
    /**
     * Convert to Map for Firestore operations
     */
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "googleAccountId" to googleAccountId,
            "email" to email,
            "displayName" to displayName,
            "purchaseToken" to purchaseToken,
            "orderId" to orderId,
            "productId" to productId,
            "purchaseTime" to purchaseTime,
            "purchaseState" to purchaseState,
            "registrationTime" to (registrationTime ?: Date()),
            "isActive" to isActive,
            "deviceModel" to deviceModel,
            "androidVersion" to androidVersion,
            "appVersion" to appVersion,
            "lastLoginTime" to lastLoginTime,
            "loginCount" to loginCount
        )
    }
}

