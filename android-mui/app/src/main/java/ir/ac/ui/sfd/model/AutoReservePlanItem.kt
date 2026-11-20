package ir.ac.ui.sfd.model

data class AutoReservePlanItem(
    val mealName: String,
    val dayItem: DayItem,
    val availableFoods: List<FoodOption>,
    val availableSelfs: List<SelfOption>,
    var selectedFoodIndex: Int = 0,
    var selectedSelfId: String? = null,
    var selectedSelfTitle: String? = null,
    var isSelectedForReservation: Boolean = true
) {
    val currentSelectedFood: FoodOption?
        get() = if (availableFoods.isNotEmpty()) {
            availableFoods[selectedFoodIndex.coerceIn(0, availableFoods.size - 1)]
        } else null

    val priceInt: Long
        get() = if (isSelectedForReservation) currentSelectedFood?.priceInt ?: 0L else 0L
}
