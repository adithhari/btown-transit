package com.bloomington.transit.presentation.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.databinding.SheetReadyToGoBinding
import com.bloomington.transit.databinding.ItemFavouriteDestBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class FavDestItem(
    val stopId: String,
    val nickname: String,
    val stopName: String
)

class ReadyToGoSheet(
    private val onDestinationSelected: (stopId: String, nickname: String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: SheetReadyToGoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetReadyToGoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = PreferencesManager(requireContext())

        val adapter = FavDestAdapter { item ->
            dismiss()
            onDestinationSelected(item.stopId, item.nickname.ifEmpty { item.stopName })
        }
        binding.rvDestinations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDestinations.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            combine(prefs.favoriteStopIds, prefs.favoriteNicknames) { ids, nicknames ->
                ids.mapNotNull { stopId ->
                    val stop = GtfsStaticCache.stops[stopId] ?: return@mapNotNull null
                    FavDestItem(
                        stopId   = stopId,
                        nickname = nicknames[stopId] ?: "",
                        stopName = stop.name
                    )
                }.sortedBy { it.nickname.ifEmpty { it.stopName } }
            }.collect { items ->
                adapter.submitList(items)
                binding.tvEmptyFavs.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.rvDestinations.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ---------------------------------------------------------------------------
// Adapter
// ---------------------------------------------------------------------------

private val NICKNAME_ICONS = mapOf(
    "home"    to "🏠", "house"  to "🏠",
    "work"    to "💼", "office" to "💼",
    "gym"     to "🏋", "school" to "🏫",
    "friend"  to "👥", "family" to "👨‍👩‍👧",
    "shop"    to "🛍", "mall"   to "🛍",
    "park"    to "🌳", "cafe"   to "☕",
    "hospital" to "🏥", "doctor" to "🏥"
)

private fun iconForNickname(nickname: String): String {
    val lower = nickname.lowercase()
    return NICKNAME_ICONS.entries.firstOrNull { lower.contains(it.key) }?.value ?: "📍"
}

class FavDestAdapter(
    private val onClick: (FavDestItem) -> Unit
) : RecyclerView.Adapter<FavDestAdapter.VH>() {

    private var items: List<FavDestItem> = emptyList()

    fun submitList(list: List<FavDestItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemFavouriteDestBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFavouriteDestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.b) {
            val displayName = item.nickname.ifEmpty { item.stopName }
            tvIcon.text     = iconForNickname(displayName)
            tvNickname.text = displayName
            tvStopName.text = if (item.nickname.isNotEmpty()) item.stopName else ""
            root.setOnClickListener { onClick(item) }
        }
    }
}
