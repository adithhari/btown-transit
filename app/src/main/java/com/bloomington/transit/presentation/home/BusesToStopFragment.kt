package com.bloomington.transit.presentation.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bloomington.transit.R
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.databinding.FragmentBusesToStopBinding
import com.bloomington.transit.databinding.ItemBusToStopBinding
import com.bloomington.transit.tracking.BusTrackingService
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch

class BusesToStopFragment : Fragment() {

    private var _binding: FragmentBusesToStopBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: BusesToStopViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBusesToStopBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val destStopId = arguments?.getString("stopId") ?: ""
        val nickname   = arguments?.getString("nickname") ?: ""

        viewModel = ViewModelProvider(this, BusesToStopViewModelFactory(requireContext(), destStopId))
            .get(BusesToStopViewModel::class.java)

        binding.tvDestTitle.text = "Getting to $nickname"

        val adapter = BusToStopAdapter { option ->
            viewLifecycleOwner.lifecycleScope.launch {
                val prefs = PreferencesManager(requireContext())
                prefs.setJourneyTracking(option.tripId, option.boardingStopId, destStopId)
                BusTrackingService.start(requireContext())
                findNavController().navigate(
                    R.id.action_busesToStopFragment_to_journeyTrackerFragment,
                    bundleOf(
                        "tripId"        to option.tripId,
                        "boardingStopId" to option.boardingStopId,
                        "destStopId"    to destStopId,
                        "destNickname"  to nickname,
                        "routeName"     to "Route ${option.routeShortName}"
                    )
                )
            }
        }
        binding.rvBusOptions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBusOptions.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val hasOptions = state.options.isNotEmpty()
                    binding.rvBusOptions.visibility = if (hasOptions) View.VISIBLE else View.GONE
                    binding.tvEmpty.visibility       = if (!hasOptions && !state.isLoading) View.VISIBLE else View.GONE

                    if (state.boardingStopName.isNotEmpty()) {
                        binding.tvBoardingInfo.text = "Board near: ${state.boardingStopName}"
                    }
                    adapter.submitList(state.options)
                }
            }
        }

        fetchLocationAndLoad()
    }

    private fun fetchLocationAndLoad() {
        val fine   = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            // Fallback: use Bloomington city center
            viewModel.load(39.1653, -86.5264)
            return
        }
        LocationServices.getFusedLocationProviderClient(requireActivity()).lastLocation
            .addOnSuccessListener { loc ->
                val lat = loc?.latitude ?: 39.1653
                val lon = loc?.longitude ?: -86.5264
                viewModel.load(lat, lon)
            }
            .addOnFailureListener { viewModel.load(39.1653, -86.5264) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ---------------------------------------------------------------------------
// Adapter
// ---------------------------------------------------------------------------

class BusToStopAdapter(
    private val onTrack: (BusToStopOption) -> Unit
) : RecyclerView.Adapter<BusToStopAdapter.VH>() {

    private var items: List<BusToStopOption> = emptyList()
    fun submitList(list: List<BusToStopOption>) { items = list; notifyDataSetChanged() }

    inner class VH(val b: ItemBusToStopBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBusToStopBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val opt = items[position]
        with(holder.b) {
            val tint = android.content.res.ColorStateList.valueOf(opt.routeColor)
            tvRouteBadge.text = opt.routeShortName
            tvRouteBadge.backgroundTintList = tint
            tvHeadsign.text = "Route ${opt.routeShortName} → ${opt.headsign}"
            val dist = if (opt.boardingDistanceMeters < 100) "nearby"
                       else "${opt.boardingDistanceMeters.toInt()}m walk"
            tvBoardingStop.text = "Board: ${opt.boardingStopName} · $dist"
            tvDeparts.text = if (opt.departureMinutes == 0) "Due now" else "Departs ${opt.departureMinutes} min"
            tvDeparts.backgroundTintList = tint
            tvArrivesDest.text = "Arrives in ~${opt.arrivalAtDestMinutes} min"
            btnTrack.setOnClickListener { onTrack(opt) }
        }
    }
}

class BusesToStopViewModelFactory(
    private val context: android.content.Context,
    private val destStopId: String
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return BusesToStopViewModel(context, destStopId) as T
    }
}
