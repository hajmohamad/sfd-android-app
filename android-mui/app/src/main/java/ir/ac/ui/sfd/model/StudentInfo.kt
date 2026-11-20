package ir.ac.ui.sfd.model

data class StudentInfo(
    val name: String = "نامشخص",
    val studentId: String = "نامشخص",
    val selfCredit: String = "۰ ریال",
    val restaurantCredit: String = "۰ ریال",
    val weekRange: String = "نامشخص",
    val selfCreditAmount: Long = 0L,
    val restaurantCreditAmount: Long = 0L
)
