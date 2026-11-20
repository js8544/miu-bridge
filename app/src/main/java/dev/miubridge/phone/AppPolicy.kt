package dev.miubridge.phone

/** Apps agents may not open on the background display; the owner uses them via phone_handoff or by hand. */
object AppPolicy {
    /** Package names or prefixes (matched at a `.` boundary) of payment, banking and brokerage apps. */
    val BLOCKED = listOf(
        "com.eg.android.AlipayGphone", // Alipay
        "com.alipay",
        "com.unionpay", // UnionPay / 云闪付
        "com.huawei.wallet",
        "com.google.android.apps.walletnfcrel",
        "com.paypal",
        "com.icbc", // ICBC
        "com.chinamworld", // CCB, Bank of China
        "com.android.bankabc", // Agricultural Bank of China
        "cmb.pb", // China Merchants Bank
        "com.cmbchina",
        "com.bankcomm",
        "com.ecitic.bank",
        "com.cib",
        "com.spdb",
        "com.webank",
        "com.mybank",
        "com.pingan.paces",
        "com.hexin.plat.android", // brokerage
        "com.eastmoney",
    )

    fun isBlocked(pkg: String): Boolean = BLOCKED.any { pkg == it || pkg.startsWith("$it.") }
}
