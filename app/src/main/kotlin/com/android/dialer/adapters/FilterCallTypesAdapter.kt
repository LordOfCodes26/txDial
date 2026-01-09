package com.android.dialer.adapters

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.databinding.ItemFilterContactSourceBinding

data class CallTypeFilter(
    val type: Int?,
    val name: String,
    var count: Int = -1
)

class FilterCallTypesAdapter(
    val activity: SimpleActivity,
    private val callTypes: List<CallTypeFilter>,
    private val selectedCallType: Int?
) : RecyclerView.Adapter<FilterCallTypesAdapter.ViewHolder>() {

    private var selectedType: Int? = selectedCallType

    fun getSelectedCallType(): Int? = selectedType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFilterContactSourceBinding.inflate(activity.layoutInflater, parent, false)
        return ViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val callType = callTypes[position]
        holder.bindView(callType)
    }

    override fun getItemCount() = callTypes.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bindView(callType: CallTypeFilter): View {
            val isSelected = selectedType == callType.type
            ItemFilterContactSourceBinding.bind(itemView).apply {
                filterContactSourceCheckbox.isChecked = isSelected
                filterContactSourceCheckbox.setColors(
                    activity.getProperTextColor(),
                    activity.getProperPrimaryColor(),
                    activity.getProperBackgroundColor()
                )
                val countText = if (callType.count >= 0) " (${callType.count})" else ""
                val displayName = "${callType.name}$countText"
                filterContactSourceCheckbox.text = displayName
                filterContactSourceHolder.setOnClickListener { viewClicked(callType) }

                return root
            }
        }

        private fun viewClicked(callType: CallTypeFilter) {
            selectedType = if (selectedType == callType.type) null else callType.type
            notifyDataSetChanged()
        }
    }
}
