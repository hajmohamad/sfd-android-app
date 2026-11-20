package ir.ac.ui.sfd.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.RecyclerView
import ir.ac.ui.sfd.databinding.ItemDayScheduleBinding
import ir.ac.ui.sfd.model.DayItem
import ir.ac.ui.sfd.model.FoodOption

class WeeklyDayAdapter(
    private val context: Context,
    private val onReserveClicked: (DayItem, FoodOption) -> Unit,
    private val onCancelClicked: (DayItem) -> Unit
) : RecyclerView.Adapter<WeeklyDayAdapter.DayViewHolder>() {

    private val items = mutableListOf<DayItem>()
    private var globalSelectedSelfId: String? = null
    private var globalSelectedSelfTitle: String? = null

    fun submitList(newItems: List<DayItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setGlobalSelf(selfId: String?, selfTitle: String?) {
        globalSelectedSelfId = selfId
        globalSelectedSelfTitle = selfTitle
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val binding = ItemDayScheduleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DayViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DayViewHolder(private val binding: ItemDayScheduleBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DayItem) {
            binding.tvDayTitle.text = item.dayDate

            // Determine active self and its display title
            val activeSelfId = item.selectedSelfId ?: item.currentSelf
            val activeSelfOption = item.selfOptions.find { it.id == activeSelfId }
            val activeSelfTitle = activeSelfOption?.title
                ?: item.reservedSelfTitle.ifBlank { globalSelectedSelfTitle ?: "سلف عادی" }

            binding.tvDaySelfIndicator.text = "محل تحویل: $activeSelfTitle"

            // Check if this day's self is different from the global selected self
            val hasDifferentSelf = !globalSelectedSelfId.isNullOrBlank() &&
                    !activeSelfId.isNullOrBlank() &&
                    activeSelfId != "-1" &&
                    activeSelfId != globalSelectedSelfId
            binding.tvDifferentSelfNotice.visibility = if (hasDifferentSelf) View.VISIBLE else View.GONE

            if (item.isDisabled) {
                binding.tvStatusBadge.text = "مهلت پایان یافته"
                binding.tvStatusBadge.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_expired)
                binding.tvStatusBadge.setTextColor(0xFF64748B.toInt())
                binding.selectionContainer.visibility = View.GONE

                if (item.isReserved) {
                    binding.reservedContainer.visibility = View.VISIBLE
                    binding.tvReservedFood.text = "غذای رزرو شده: ${item.reservedFood.ifBlank { "ثبت شده" }}"
                    binding.tvReservedSelf.text = "محل تحویل: $activeSelfTitle"
                    binding.btnCancelReserve.visibility = View.GONE
                } else {
                    binding.reservedContainer.visibility = View.GONE
                }
                return
            }

            if (item.isReserved) {
                binding.tvStatusBadge.text = "رزرو شده"
                binding.tvStatusBadge.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_reserved)
                binding.tvStatusBadge.setTextColor(0xFF047857.toInt())
                binding.reservedContainer.visibility = View.VISIBLE
                binding.btnCancelReserve.visibility = View.VISIBLE
                binding.tvReservedFood.text = "غذای رزرو شده: ${item.reservedFood.ifBlank { "ثبت شده" }}"
                binding.tvReservedSelf.text = "محل تحویل: $activeSelfTitle"

                binding.btnCancelReserve.setOnClickListener {
                    onCancelClicked(item)
                }

                binding.selectionContainer.visibility = View.GONE
            } else {
                binding.tvStatusBadge.text = "آماده رزرو"
                binding.tvStatusBadge.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_capacity)
                binding.tvStatusBadge.setTextColor(0xFF1D4ED8.toInt())
                binding.reservedContainer.visibility = View.GONE
                binding.selectionContainer.visibility = View.VISIBLE

                // Day-specific self selector
                if (item.selfOptions.size > 1) {
                    binding.llDaySelfSelector.visibility = View.VISIBLE
                    val selfTitles = item.selfOptions.map { it.title }
                    val selfAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, selfTitles)
                    selfAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spDaySelf.adapter = selfAdapter

                    val currentIdx = item.selfOptions.indexOfFirst { it.id == item.selectedSelfId }.takeIf { it >= 0 } ?: 0
                    binding.spDaySelf.setSelection(currentIdx)

                    binding.spDaySelf.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                            val chosen = item.selfOptions[pos]
                            item.selectedSelfId = chosen.id
                            binding.tvDaySelfIndicator.text = "محل تحویل: ${chosen.title}"
                            val diff = !globalSelectedSelfId.isNullOrBlank() && chosen.id != globalSelectedSelfId
                            binding.tvDifferentSelfNotice.visibility = if (diff) View.VISIBLE else View.GONE
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                } else {
                    binding.llDaySelfSelector.visibility = View.GONE
                }

                // Food options
                if (item.foods.isNotEmpty()) {
                    val foodLabels = item.foods.map { "${it.name} (${it.priceStr})" }
                    val spinnerAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, foodLabels)
                    spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spFoods.adapter = spinnerAdapter
                    binding.spFoods.setSelection(item.selectedFoodIndex.coerceIn(0, item.foods.size - 1))

                    binding.spFoods.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                            item.selectedFoodIndex = pos
                            val chosenFood = item.foods[pos]
                            binding.tvPrice.text = "قیمت: ${chosenFood.priceStr}"
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }

                    val initialFood = item.foods[item.selectedFoodIndex.coerceIn(0, item.foods.size - 1)]
                    binding.tvPrice.text = "قیمت: ${initialFood.priceStr}"

                    binding.btnReserve.setOnClickListener {
                        val food = item.foods[item.selectedFoodIndex.coerceIn(0, item.foods.size - 1)]
                        onReserveClicked(item, food)
                    }
                } else {
                    binding.selectionContainer.visibility = View.GONE
                }
            }
        }
    }
}
