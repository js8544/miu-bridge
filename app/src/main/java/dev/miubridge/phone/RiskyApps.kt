package dev.miubridge.phone

/**
 * Apps that move money, hold identity papers, or keep passwords. They start on the block list, so an
 * agent cannot open them until the owner takes them off it. Each package is put on the list once: an
 * app the owner took off stays off, and a package added to this list in a later version is blocked on
 * the next start. Packages that are not installed cost nothing.
 */
object RiskyApps {
    val PACKAGES: Set<String> = setOf(
        // Banks
        "com.icbc", "com.chinamworld.main", "com.android.bankabc", "com.chinamworld.bocmbci", "cmb.pb",
        "com.bankcomm.Bankcomm", "com.yitong.mbank.psbc", "cn.com.spdb.mobilebank.per", "com.ecitic.bank.mobile",
        "com.cebbank.mobile.cemb", "cn.com.cmbc.newmbank", "com.pingan.paces.ccms", "com.cgbchina.xpt",
        "com.ccb.companybank", "com.cmb.ubank.UBUI",
        "hk.com.hsbc.hsbchkmobilebanking", "cn.com.hsbc.hsbcchina",
        "com.chase.sig.android", "com.infonow.bofa", "com.wf.wellsfargomobile", "com.revolut.revolut",
        "com.transferwise.android",
        // Payment and wallets
        "com.eg.android.AlipayGphone", "com.unionpay", "com.jd.jrapp", "com.paypal.android.p2pmobile",
        "com.venmo", "com.squareup.cash", "com.google.android.apps.walletnfcrel",
        // Brokers and crypto
        "cn.futu.trader", "com.tigerbrokers.stock", "com.hexin.plat.android", "com.eastmoney.android.berlin",
        "org.dayup.stocks", "com.robinhood.android", "com.coinbase.android", "com.binance.dev", "com.okinc.okex.gp",
        // Tax and social insurance
        "cn.gov.tax.its", "cn.gov.chinatax.gt4.app", "cn.hsa.app",
        // Passwords and sign-in codes
        "com.x8bit.bitwarden", "com.onepassword.android", "com.lastpass.lpandroid",
        "com.google.android.apps.authenticator2", "com.azure.authenticator",
    )

    /**
     * The block list after putting on it every risky package not put there before, and the packages put
     * there so far. Returns null when there is nothing new.
     */
    fun addNew(blocked: Set<String>, offered: Set<String>, risky: Set<String> = PACKAGES): Pair<Set<String>, Set<String>>? {
        val fresh = risky - offered
        if (fresh.isEmpty()) return null
        return (blocked + fresh) to (offered + fresh)
    }
}
