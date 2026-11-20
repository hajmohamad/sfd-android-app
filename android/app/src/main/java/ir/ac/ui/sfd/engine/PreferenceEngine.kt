package ir.ac.ui.sfd.engine

import ir.ac.ui.sfd.model.CafeteriaItem
import ir.ac.ui.sfd.model.FoodOption
import ir.ac.ui.sfd.model.UserPreferences
import kotlin.math.ceil
import kotlin.math.max

object PreferenceEngine {

    fun normalizePersian(text: String): String {
        return text
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('ة', 'ه')
            .replace('\u200c', ' ') // Half-space
            .replace("[\u064B-\u065F]".toRegex(), "") // Arabic diacritics
            .replace("[\\s\\t\\n\\r]+".toRegex(), " ")
            .trim()
    }

    fun normalizeSpelling(text: String): String {
        var s = normalizePersian(text)
        val replacements = listOf(
            "خورشت" to "خورش",
            "قورمه" to "قرمه",
            "شنیسل" to "شنسل",
            "اسپاگتی" to "ماکارونی",
            "همبرگر" to "برگر"
        )
        for ((k, v) in replacements) {
            s = s.replace(k, v)
        }
        return s
    }

    private fun matchesSearchTerm(candidateName: String, term: String): Boolean {
        val normCandidate = normalizeSpelling(candidateName)
        val normTerm = normalizeSpelling(term)

        if (normTerm in normCandidate) return true

        val termWords = normTerm.split(" ").filter { it.length > 2 && it !in listOf("چلو", "پلو", "خورش") }
        if (termWords.isNotEmpty() && termWords.all { it in normCandidate }) {
            return true
        }
        return false
    }

    fun isBlacklisted(foodName: String, blacklist: List<String>): Boolean {
        for (item in blacklist) {
            if (item.isNotBlank() && matchesSearchTerm(foodName, item)) {
                return true
            }
        }
        return false
    }

    fun rankFoodOptions(
        options: List<FoodOption>,
        mealName: String,
        prefs: UserPreferences
    ): Triple<List<FoodOption>, List<FoodOption>, List<FoodOption>> {
        val priorityList = if (mealName == "شام") prefs.dinnerPriority else prefs.lunchPriority

        val matched = mutableListOf<FoodOption>()
        val unranked = mutableListOf<FoodOption>()
        val blacklisted = mutableListOf<FoodOption>()

        for (opt in options) {
            if (isBlacklisted(opt.name, prefs.blacklist)) {
                blacklisted.add(opt)
                continue
            }

            var foundRank = -1
            var matchedTerm: String? = null
            for (idx in priorityList.indices) {
                val term = priorityList[idx]
                if (matchesSearchTerm(opt.name, term)) {
                    foundRank = idx + 1
                    matchedTerm = term
                    break
                }
            }

            if (foundRank != -1) {
                opt.rank = foundRank
                opt.matchedPriority = matchedTerm
                matched.add(opt)
            } else {
                opt.rank = 999
                opt.matchedPriority = null
                unranked.add(opt)
            }
        }

        matched.sortBy { it.rank }
        return Triple(matched, unranked, blacklisted)
    }

    fun rankCafeteriaOptions(
        options: List<CafeteriaItem>,
        mealName: String,
        prefs: UserPreferences
    ): Triple<List<CafeteriaItem>, List<CafeteriaItem>, List<CafeteriaItem>> {
        val priorityList = if (mealName == "شام") prefs.dinnerPriority else prefs.lunchPriority

        val matched = mutableListOf<CafeteriaItem>()
        val unranked = mutableListOf<CafeteriaItem>()
        val blacklisted = mutableListOf<CafeteriaItem>()

        for (opt in options) {
            if (isBlacklisted(opt.name, prefs.blacklist)) {
                blacklisted.add(opt)
                continue
            }

            var foundRank = -1
            var matchedTerm: String? = null
            for (idx in priorityList.indices) {
                val term = priorityList[idx]
                if (matchesSearchTerm(opt.name, term)) {
                    foundRank = idx + 1
                    matchedTerm = term
                    break
                }
            }

            if (foundRank != -1) {
                opt.rank = foundRank
                opt.matchedPriority = matchedTerm
                matched.add(opt)
            } else {
                opt.rank = 999
                opt.matchedPriority = null
                unranked.add(opt)
            }
        }

        matched.sortBy { it.rank }
        return Triple(matched, unranked, blacklisted)
    }

    fun calculateSmartTopup(deficit: Long, roundStep: Long = 500000L, defaultTopup: Long = 1000000L): Long {
        if (deficit <= 0L) return defaultTopup
        val rounded = (ceil(deficit.toDouble() / roundStep.toDouble()) * roundStep).toLong()
        return max(rounded, max(defaultTopup, 100000L))
    }
}
