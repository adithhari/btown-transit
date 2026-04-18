package com.bloomington.transit.presentation.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.databinding.SheetStopInfoBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class StopInfoSheet : BottomSheetDialogFragment() {

    private var _binding: SheetStopInfoBinding? = null
    private val binding get() = _binding!!

    var onViewSchedule: (() -> Unit)? = null

    companion object {
        fun newInstance(stopId: String) = StopInfoSheet().apply {
            arguments = Bundle().also { it.putString("stopId", stopId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetStopInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val stopId = arguments?.getString("stopId") ?: return
        val stop = GtfsStaticCache.stops[stopId]
        val prefs = PreferencesManager(requireContext())

        binding.tvStopName.text = stop?.name ?: stopId
        binding.tvStopId.text = "Stop ID: $stopId"

        // Set favorite button state
        lifecycleScope.launch {
            val isFav = prefs.favoriteStopIds.first().contains(stopId)
            binding.btnFavorite.text = if (isFav) "★ REMOVE FAVORITE" else "★ ADD TO FAVORITES"
        }

        binding.btnClose.setOnClickListener { dismiss() }

        binding.btnViewSchedule.setOnClickListener {
            dismiss()
            onViewSchedule?.invoke()
        }

        binding.btnFavorite.setOnClickListener {
            lifecycleScope.launch {
                val isFav = prefs.favoriteStopIds.first().contains(stopId)
                if (isFav) {
                    prefs.removeFavoriteStop(stopId)
                    binding.btnFavorite.text = "★ ADD TO FAVORITES"
                    Toast.makeText(requireContext(), "Removed from favorites", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.addFavoriteStop(stopId)
                    binding.btnFavorite.text = "★ REMOVE FAVORITE"
                    Toast.makeText(requireContext(), "${stop?.name ?: stopId} added to favorites", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
