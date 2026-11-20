package ir.ac.ui.sfd.model

data class FoodOption(
    val id: String,
    val name: String,
    val fullName: String,
    val priceInt: Long,
    val priceStr: String,
    val isAvailable: Boolean = true,
    var rank: Int = 999,
    var matchedPriority: String? = null
)
