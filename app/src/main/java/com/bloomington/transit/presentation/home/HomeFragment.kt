package com.bloomington.transit.presentation.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
import com.bloomington.transit.databinding.FragmentHomeBinding
import com.bloomington.transit.databinding.ItemNearbyBusBinding
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HomeViewModel
    private lateinit var adapter: NearbyBusAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            fetchLocation()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this, HomeViewModelFactory(requireContext()))
            .get(HomeViewModel::class.java)

        binding.tvGreeting.text = greeting()

        adapter = NearbyBusAdapter { item ->
            findNavController().navigate(
                R.id.action_homeFragment_to_busTrackerFragment,
                bundleOf("vehicleId" to item.tripId)
            )
        }
        binding.rvNearbyBuses.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNearbyBuses.adapter = adapter

        binding.fabReadyToGo.setOnClickListener {
            ReadyToGoSheet { stopId, nickname ->
                findNavController().navigate(
                    R.id.action_homeFragment_to_busesToStopFragment,
                    bundleOf("stopId" to stopId, "nickname" to nickname)
                )
            }.show(parentFragmentManager, "ready_to_go")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Collect new bus data from ViewModel (network fetches every 15s)
                launch {
                    viewModel.uiState.collect { state ->
                        val hasData = state.nearbyBuses.isNotEmpty()
                        binding.rvNearbyBuses.visibility = if (hasData) View.VISIBLE else View.GONE
                        binding.tvEmpty.visibility = if (!hasData && !state.isLoading) View.VISIBLE else View.GONE

                        binding.tvSubtitle.text = when {
                            state.isLoading            -> "Finding buses near you…"
                            state.nearbyBuses.isEmpty() -> "No buses found within 600m"
                            else -> "${state.nearbyBuses.size} buses nearby • live"
                        }
                        adapter.submitList(state.nearbyBuses)
                    }
                }

                // 2-second UI ticker — recomputes ETAs from stored timestamps (no network call)
                launch {
                    while (isActive) {
                        adapter.notifyDataSetChanged()
                        delay(2_000)
                    }
                }
            }
        }

        checkLocationAndFetch()
    }

    private fun checkLocationAndFetch() {
        val fine   = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            fetchLocation()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun fetchLocation() {
        val fine   = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return

        LocationServices.getFusedLocationProviderClient(requireActivity()).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) viewModel.updateLocation(loc.latitude, loc.longitude)
            }
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11  -> "Good morning!"
        in 12..16 -> "Good afternoon!"
        in 17..20 -> "Good evening!"
        else      -> "Good night!"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ---------------------------------------------------------------------------
// Adapter — ETAs computed live from liveArrivalSec on every bind
// ---------------------------------------------------------------------------

class NearbyBusAdapter(
    private val onClick: (NearbyBusItem) -> Unit
) : RecyclerView.Adapter<NearbyBusAdapter.VH>() {

    private var items: List<NearbyBusItem> = emptyList()

    fun submitList(list: List<NearbyBusItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemNearbyBusBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemNearbyBusBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item   = items[position]
        val tint   = android.content.res.ColorStateList.valueOf(item.routeColor)
        val nowSec = System.currentTimeMillis() / 1000L
        val minAway= ((item.liveArrivalSec - nowSec) / 60L).toInt().coerceAtLeast(0)

        with(holder.b) {
            tvRouteBadge.text = item.routeShortName
            tvRouteBadge.backgroundTintList = tint
            tvHeadsign.text = "Route ${item.routeShortName} → ${item.headsign}"
            val dist = if (item.distanceMeters < 100) "nearby"
                       else "${item.distanceMeters.toInt()}m away"
            tvStopInfo.text = "${item.boardingStopName} · $dist"
            tvEta.text = if (minAway == 0) "Due" else "$minAway min"
            tvEta.backgroundTintList = tint
            root.setOnClickListener { onClick(item) }
        }
    }
}

class HomeViewModelFactory(private val context: android.content.Context) :
    androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return HomeViewModel(context) as T
    }
}
