package com.bloomington.transit.presentation.map

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.notification.ArrivalNotificationManager
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.databinding.ItemStopScheduleRowBinding
import com.bloomington.transit.databinding.SheetStopScheduleBinding
import com.bloomington.transit.domain.usecase.GetScheduleForStopUseCase
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.usecase.ScheduleEntry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StopScheduleSheet : BottomSheetDialogFragment() {

    private var _binding: SheetStopScheduleBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance(stopId: String, filterRouteId: String = "") =
            StopScheduleSheet().apply {
                arguments = Bundle().also {
                    it.putString("stopId", stopId)
                    it.putString("filterRouteId", filterRouteId)
                }
            }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetStopScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val stopId = arguments?.getString("stopId") ?: return
        val filterRouteId = arguments?.getString("filterRouteId") ?: ""
        val stop = GtfsStaticCache.stops[stopId]
        val prefs = PreferencesManager(requireContext())

        val routeLabel = if (filterRouteId.isNotEmpty()) {
            val r = GtfsStaticCache.routes[filterRouteId]
            " — Route ${r?.shortName ?: filterRouteId}"
        } else ""
        binding.tvScheduleTitle.text = "${stop?.name ?: stopId} Schedule$routeLabel"
        binding.btnBack.setOnClickListener { dismiss() }

        val notifManager = ArrivalNotificationManager(requireContext())
        val adapter = StopScheduleAdapter { entry ->
            lifecycleScope.launch {
                prefs.setTrackedTrip(entry.tripId, stopId)
                val stopName = stop?.name ?: stopId
                notifManager.notifyTrackingStarted(entry.routeShortName, stopName)
                Toast.makeText(
                    requireContext(),
                    "Tracking Route ${entry.routeShortName} — you'll be notified when approaching",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val layoutManager = LinearLayoutManager(requireContext())
        binding.rvStopSchedule.layoutManager = layoutManager
        binding.rvStopSchedule.adapter = adapter

        lifecycleScope.launch {
            val entries = withContext(Dispatchers.Default) {
                try {
                    val updates = GetTripUpdatesUseCase(TransitRepositoryImpl())()
                    GetScheduleForStopUseCase()(stopId, updates, filterRouteId)
                } catch (_: Exception) {
                    GetScheduleForStopUseCase()(stopId, emptyList(), filterRouteId)
                }
            }
            adapter.submitList(entries)

            // Scroll to first upcoming (non-past) entry
            val firstUpcomingIdx = entries.indexOfFirst { !it.isPast }
            if (firstUpcomingIdx > 0) {
                binding.rvStopSchedule.post {
                    layoutManager.scrollToPositionWithOffset(firstUpcomingIdx, 0)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class StopScheduleAdapter(
    private val onTrack: (ScheduleEntry) -> Unit
) : RecyclerView.Adapter<StopScheduleAdapter.VH>() {

    private var items: List<ScheduleEntry> = emptyList()

    fun submitList(list: List<ScheduleEntry>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemStopScheduleRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemStopScheduleRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.binding.tvRowRoute.text = "${entry.routeShortName} ${entry.headsign}"
        holder.binding.tvRowTime.text = entry.etaLabel

        if (entry.isPast) {
            holder.itemView.alpha = 0.38f
            holder.binding.btnTrack.visibility = View.GONE
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        } else {
            holder.itemView.alpha = 1f
            holder.binding.btnTrack.visibility = View.VISIBLE
            holder.binding.btnTrack.setOnClickListener { onTrack(entry) }
            val firstUpcomingPos = items.indexOfFirst { !it.isPast }
            holder.itemView.setBackgroundColor(
                if (position == firstUpcomingPos) Color.parseColor("#FFF3E0")
                else Color.TRANSPARENT
            )
        }
    }
}
