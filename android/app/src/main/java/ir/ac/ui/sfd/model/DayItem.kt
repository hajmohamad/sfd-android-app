package ir.ac.ui.sfd.model

data class SelfOption(
    val id: String,
    val title: String
)

data class DayItem(
    val dayDate: String,
    val isDisabled: Boolean,
    val isReserved: Boolean,
    val reservedFood: String,
    val chkName: String,
    val selfCtlName: String,
    val currentSelf: String,
    val foodCtlName: String,
    val countCtlName: String,
    val selfOptions: List<SelfOption>,
    val foods: List<FoodOption>,
    var selectedFoodIndex: Int = 0,
    var selectedSelfId: String? = null,
    var reservedSelfTitle: String = ""
)
