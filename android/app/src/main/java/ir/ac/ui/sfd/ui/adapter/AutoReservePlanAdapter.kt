package ir.ac.ui.sfd.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.RecyclerView
import ir.ac.ui.sfd.databinding.ItemAutoReservePlanBinding
import ir.ac.ui.sfd.model.AutoReservePlanItem

class AutoReservePlanAdapter(
    private val context: Context,
    private val items: List<AutoReservePlanItem>,
    private val onPlanChanged: () -> Unit
) : RecyclerView.Adapter<AutoReservePlanAdapter.PlanViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanViewHolder {
        val binding = ItemAutoReservePlanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlanViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlanViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PlanViewHolder(private val binding: ItemAutoReservePlanBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AutoReservePlanItem) {
            binding.tvPlanDayMeal.text = "${item.dayItem.dayDate} (${item.mealName})"
            binding.cbIncludeMeal.isChecked = item.isSelectedForReservation
            updatePriceBadge(item)

            binding.llPlanDropdowns.alpha = if (item.isSelectedForReservation) 1.0f else 0.4f
            binding.spPlanFood.isEnabled = item.isSelectedForReservation
            binding.spPlanSelf.isEnabled = item.isSelectedForReservation

            binding.cbIncludeMeal.setOnCheckedChangeListener { _, isChecked ->
                item.isSelectedForReservation = isChecked
                binding.llPlanDropdowns.alpha = if (isChecked) 1.0f else 0.4f
                binding.spPlanFood.isEnabled = isChecked
                binding.spPlanSelf.isEnabled = isChecked
                updatePriceBadge(item)
                onPlanChanged()
            }

            // Food Spinner
            if (item.availableFoods.isNotEmpty()) {
                val foodLabels = item.availableFoods.map { "${it.name} (${it.priceStr})" }
                val foodAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, foodLabels)
                foodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spPlanFood.adapter = foodAdapter
                binding.spPlanFood.setSelection(item.selectedFoodIndex.coerceIn(0, item.availableFoods.size - 1))

                binding.spPlanFood.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        item.selectedFoodIndex = pos
                        updatePriceBadge(item)
                        onPlanChanged()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            // Self Spinner
            if (item.availableSelfs.isNotEmpty()) {
                val selfLabels = item.availableSelfs.map { it.title }
                val selfAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, selfLabels)
                selfAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spPlanSelf.adapter = selfAdapter

                val currentIdx = item.availableSelfs.indexOfFirst { it.id == item.selectedSelfId }.takeIf { it >= 0 } ?: 0
                binding.spPlanSelf.setSelection(currentIdx)

                binding.spPlanSelf.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        val chosen = item.availableSelfs[pos]
                        item.selectedSelfId = chosen.id
                        item.selectedSelfTitle = chosen.title
                        onPlanChanged()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            } else {
                binding.spPlanSelf.visibility = View.GONE
            }
        }

        private fun updatePriceBadge(item: AutoReservePlanItem) {
            val food = item.currentSelectedFood
            if (item.isSelectedForReservation && food != null) {
                binding.tvPlanPrice.text = food.priceStr
                binding.tvPlanPrice.visibility = View.VISIBLE
            } else if (!item.isSelectedForReservation) {
                binding.tvPlanPrice.text = "رزرو نمی‌شود"
                binding.tvPlanPrice.visibility = View.VISIBLE
            } else {
                binding.tvPlanPrice.visibility = View.GONE
            }
        }
    }
}
