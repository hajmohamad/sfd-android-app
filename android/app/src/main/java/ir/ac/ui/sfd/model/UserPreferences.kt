package ir.ac.ui.sfd.model

data class UserPreferences(
    var skipIfNoPreferenceMatch: Boolean = true,
    var lunchPriority: MutableList<String> = mutableListOf(
        "چلو کباب کوبیده",
        "زرشک پلو با مرغ",
        "چلو جوجه کباب",
        "چلو خورشت قورمه سبزی",
        "چلو خورشت قیمه"
    ),
    var dinnerPriority: MutableList<String> = mutableListOf(
        "شنیسل مرغ",
        "پیتزا",
        "خوراک مرغ",
        "ساندویچ ماکارونی"
    ),
    var blacklist: MutableList<String> = mutableListOf(
        "خوراک لوبیا",
        "کوکو سبزی"
    ),
    var defaultLunchSelf: String = "اختران (برادران)",
    var defaultDinnerSelf: String = "شهید فهمیده",
    var defaultBreakfastSelf: String = "شهدا",
    var defaultRestaurant: String = "رستوران یاس یک 1 *",
    var defaultRestaurantId: String = "13",
    var defaultTopupAmount: Long = 1000000L,
    var autoReserveScheduled: Boolean = false,
    var autoReserveDay: String = "چهارشنبه",
    var autoReserveHour: Int = 11,
    var autoReserveMinute: Int = 0,
    var notifyOnDeficit: Boolean = true
)
