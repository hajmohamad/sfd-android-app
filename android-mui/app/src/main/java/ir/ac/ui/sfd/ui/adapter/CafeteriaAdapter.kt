package ir.ac.ui.sfd.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ir.ac.ui.sfd.databinding.ItemCafeteriaBinding
import ir.ac.ui.sfd.model.CafeteriaItem

class CafeteriaAdapter(
    private val onOrderClicked: (CafeteriaItem) -> Unit
) : RecyclerView.Adapter<CafeteriaAdapter.CafeteriaViewHolder>() {

    private val items = mutableListOf<CafeteriaItem>()
    private var hasAnyReserved = false
    private var reservedItemName = ""

    fun submitList(newItems: List<CafeteriaItem>) {
        items.clear()
        items.addAll(newItems)
        val reservedItem = items.firstOrNull { it.isReserved }
        hasAnyReserved = reservedItem != null
        reservedItemName = reservedItem?.name ?: ""
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CafeteriaViewHolder {
        val binding = ItemCafeteriaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CafeteriaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CafeteriaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class CafeteriaViewHolder(private val binding: ItemCafeteriaBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CafeteriaItem) {
            binding.tvFoodName.text = item.name
            binding.tvPrice.text = item.priceStr

            if (item.isReserved) {
                binding.tvRemainingBadge.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_reserved)
                binding.tvRemainingBadge.setTextColor(0xFF047857.toInt())
                binding.tvRemainingBadge.text = "سفارش شما در این وعده ✓"
                binding.btnOrder.isEnabled = false
                binding.btnOrder.text = "سفارش ثبت شده"
                binding.btnOrder.setBackgroundColor(0xFFCBD5E1.toInt())
                binding.btnOrder.setTextColor(0xFF475569.toInt())
                binding.btnOrder.setOnClickListener(null)
            } else if (hasAnyReserved) {
                // Another food in this meal is already reserved! Disallow ordering multiple foods per meal
                binding.tvRemainingBadge.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_warning)
                binding.tvRemainingBadge.setTextColor(0xFFB45309.toInt())
                binding.tvRemainingBadge.text = "سقف ۱ غذا در هر وعده"
                binding.btnOrder.isEnabled = false
                binding.btnOrder.text = "سفارش دیگری ثبت شده"
                binding.btnOrder.setBackgroundColor(0xFFF1F5F9.toInt())
                binding.btnOrder.setTextColor(0xFF94A3B8.toInt())
                binding.btnOrder.setOnClickListener(null)
            } else if (!item.isAvailable || item.remaining <= 0) {
                binding.tvRemainingBadge.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_danger)
                binding.tvRemainingBadge.setTextColor(0xFFB91C1C.toInt())
                binding.tvRemainingBadge.text = "تکمیل ظرفیت"
                binding.btnOrder.isEnabled = false
                binding.btnOrder.text = "اتمام ظرفیت"
                binding.btnOrder.setBackgroundColor(0xFFE2E8F0.toInt())
                binding.btnOrder.setTextColor(0xFF94A3B8.toInt())
                binding.btnOrder.setOnClickListener(null)
            } else if (item.remaining in 1..5) {
                binding.tvRemainingBadge.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_warning)
                binding.tvRemainingBadge.setTextColor(0xFFB45309.toInt())
                binding.tvRemainingBadge.text = "تنها ${item.remaining} پرس باقیمانده!"
                binding.btnOrder.isEnabled = true
                binding.btnOrder.text = "سفارش و پرداخت"
                binding.btnOrder.setBackgroundColor(0xFF0284C7.toInt())
                binding.btnOrder.setTextColor(0xFFFFFFFF.toInt())
                binding.btnOrder.setOnClickListener {
                    onOrderClicked(item)
                }
            } else {
                binding.tvRemainingBadge.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_capacity)
                binding.tvRemainingBadge.setTextColor(0xFF1D4ED8.toInt())
                binding.tvRemainingBadge.text = "ظرفیت: ${item.remaining} پرس"
                binding.btnOrder.isEnabled = true
                binding.btnOrder.text = "سفارش و پرداخت"
                binding.btnOrder.setBackgroundColor(0xFF0284C7.toInt())
                binding.btnOrder.setTextColor(0xFFFFFFFF.toInt())
                binding.btnOrder.setOnClickListener {
                    onOrderClicked(item)
                }
            }
        }
    }
}
