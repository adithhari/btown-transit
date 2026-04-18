package com.bloomington.transit.presentation.favorites

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.model.GtfsStop
import com.bloomington.transit.databinding.FragmentFavoritesBinding
import com.bloomington.transit.databinding.ItemFavoriteStopBinding
import com.bloomington.transit.domain.usecase.ScheduleEntry
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: FavoritesViewModel
    private lateinit var acAdapter: ArrayAdapter<String>
    private var currentAllStops: List<GtfsStop> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this, FavoritesViewModelFactory(requireContext()))
            .get(FavoritesViewModel::class.java)

        val adapter = FavoritesAdapter(
            onRemove   = { stopId -> viewModel.removeFavorite(stopId) },
            onNickname = { info  -> showNicknameDialog(info) }
        )
        binding.rvFavorites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFavorites.adapter = adapter

        acAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, mutableListOf())
        binding.acAddStop.setAdapter(acAdapter)
        binding.acAddStop.threshold = 2
        binding.acAddStop.setOnItemClickListener { _, _, pos, _ ->
            if (pos < currentAllStops.size) {
                viewModel.addFavorite(currentAllStops[pos].stopId)
                binding.acAddStop.text.clear()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.favorites)
                    binding.tvEmpty.visibility =
                        if (state.favorites.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvFavorites.visibility =
                        if (state.favorites.isEmpty()) View.GONE else View.VISIBLE

                    if (state.allStops.size != currentAllStops.size) {
                        currentAllStops = state.allStops
                        acAdapter.clear()
                        acAdapter.addAll(currentAllStops.map { "${it.name} (${it.stopId})" })
                        acAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun showNicknameDialog(info: FavoriteStopInfo) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val suggestions = listOf("🏠 Home", "💼 Work", "💼 Work 2", "👥 Friends Home",
            "🏫 School", "🏋 Gym", "☕ Cafe", "🛍 Mall", "🏥 Hospital", "🌳 Park")

        val input = EditText(ctx).apply {
            hint = "Nickname (e.g. Home, Work)"
            setText(info.nickname)
            setSelectAllOnFocus(true)
            val pad = (12 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (16 * dp).toInt()
            setPadding(pad, 0, pad, pad)
        }
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }

        suggestions.forEach { label ->
            val chip = TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 40 * dp
                    setColor(Color.parseColor("#1565C0"))
                }
                val h = (8 * dp).toInt(); val v = (4 * dp).toInt()
                setPadding(h * 2, v, h * 2, v)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * dp).toInt() }
                layoutParams = lp
                setOnClickListener { input.setText(label.substringAfter(" ")) }
            }
            chipRow.addView(chip)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(scroll)
        }

        AlertDialog.Builder(ctx)
            .setTitle("Nickname for ${info.stop.name}")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                viewModel.setNickname(info.stop.stopId, input.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                viewModel.setNickname(info.stop.stopId, "")
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ---------------------------------------------------------------------------
// Adapter
// ---------------------------------------------------------------------------

class FavoritesAdapter(
    private val onRemove: (String) -> Unit,
    private val onNickname: (FavoriteStopInfo) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.VH>() {

    private var items: List<FavoriteStopInfo> = emptyList()

    fun submitList(list: List<FavoriteStopInfo>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemFavoriteStopBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFavoriteStopBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val info = items[position]
        val inflater = LayoutInflater.from(holder.itemView.context)
        with(holder.binding) {
            tvStopName.text = info.stop.name
            tvNickname.text = info.nickname.ifEmpty { "Tap ✏ to add nickname" }
            tvNickname.alpha = if (info.nickname.isNotEmpty()) 1f else 0.5f
            btnNickname.setOnClickListener { onNickname(info) }
            btnRemove.setOnClickListener { onRemove(info.stop.stopId) }

            llArrivalChips.removeAllViews()
            if (info.nextArrivals.isEmpty()) {
                val chip = inflater.inflate(
                    com.bloomington.transit.R.layout.item_arrival_chip, llArrivalChips, false
                ) as TextView
                chip.text = "No upcoming"
                chip.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
                llArrivalChips.addView(chip)
            } else {
                info.nextArrivals.forEach { entry ->
                    val chip = inflater.inflate(
                        com.bloomington.transit.R.layout.item_arrival_chip, llArrivalChips, false
                    ) as TextView
                    chip.text = "Rt ${entry.routeShortName}  ${entry.etaLabel}"
                    val routeColor = GtfsStaticCache.routes[entry.routeId]?.color
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { runCatching { Color.parseColor("#$it") }.getOrNull() }
                        ?: Color.parseColor("#1565C0")
                    chip.backgroundTintList = ColorStateList.valueOf(routeColor)
                    llArrivalChips.addView(chip)
                }
            }
        }
    }
}

class FavoritesViewModelFactory(private val context: android.content.Context) :
    androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return FavoritesViewModel(context) as T
    }
}
