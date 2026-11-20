package ir.ac.ui.sfd.model

data class CafeteriaItem(
    val name: String,
    val priceInt: Long,
    val priceStr: String,
    val remaining: Int,
    val isAvailable: Boolean,
    val isReserved: Boolean,
    val chkName: String,
    val countName: String,
    var rank: Int = 999,
    var matchedPriority: String? = null
)
