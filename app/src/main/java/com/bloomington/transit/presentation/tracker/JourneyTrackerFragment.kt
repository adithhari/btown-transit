package com.bloomington.transit.presentation.tracker

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bloomington.transit.data.local.GtfsStaticCache
import com.bloomington.transit.data.local.PreferencesManager
import com.bloomington.transit.data.repository.TransitRepositoryImpl
import com.bloomington.transit.databinding.FragmentJourneyTrackerBinding
import com.bloomington.transit.domain.usecase.GetTripUpdatesUseCase
import com.bloomington.transit.domain.util.ArrivalTimeCalculator
import com.bloomington.transit.notification.ArrivalNotificationManager
import com.bloomington.transit.tracking.BusTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class JourneyTrackerFragment : Fragment() {

    private var _binding: FragmentJourneyTrackerBinding? = null
    private val binding get() = _binding!!

    private val standardMilestones = listOf(30, 20, 15, 10, 5, 2, 0)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentJourneyTrackerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tripId          = arguments?.getString("tripId") ?: ""
        val boardingStopId  = arguments?.getString("boardingStopId") ?: ""
        val destStopId      = arguments?.getString("destStopId") ?: ""
        val destNickname    = arguments?.getString("destNickname") ?: "Destination"
        val routeName       = arguments?.getString("routeName") ?: "Bus"
        val boardingStopName= GtfsStaticCache.stops[boardingStopId]?.name ?: boardingStopId

        binding.tvRouteLabel.text  = routeName
        binding.tvDestination.text = destNickname
        binding.tvBoardingStopName.text =
            GtfsStaticCache.stops[boardingStopId]?.name ?: boardingStopId

        binding.btnStopTracking.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val prefs = PreferencesManager(requireContext())
                prefs.clearTracking()
                BusTrackingService.stop(requireContext())
                ArrivalNotificationManager(requireContext()).cancelJourneyUpdate()
                requireActivity().onBackPressed()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val repository = TransitRepositoryImpl()
                val getTripUpdates = GetTripUpdatesUseCase(repository)
                while (isActive) {
                    try {
                        val updates     = getTripUpdates()
                        val tripStops   = GtfsStaticCache.stopTimesByTrip[tripId] ?: emptyList()
                        val boardingSt  = tripStops.find { it.stopId == boardingStopId }
                        val destSt      = tripStops.find { it.stopId == destStopId }

                        if (boardingSt != null) {
                            val bStu        = updates.find { it.tripId == tripId }
                                ?.stopTimeUpdates?.find { it.stopId == boardingStopId }
                            val boardSec    = ArrivalTimeCalculator.resolvedArrivalSec(boardingSt, bStu)
                            val minToBoard  = ((boardSec - System.currentTimeMillis() / 1000L) / 60L).toInt()

                            if (minToBoard >= 0) {
                                // Phase 1: approaching boarding stop
                                binding.tvBigEta.text   = "$minToBoard"
                                binding.tvPhaseLabel.text = "MIN UNTIL BUS"
                                binding.tvBoardingStopName.text = boardingStopName
                                updateTimeline(minToBoard, phase = 1, boardingStopName = boardingStopName)
                            } else if (destSt != null) {
                                // Phase 2: on the bus, heading to destination
                                val dStu       = updates.find { it.tripId == tripId }
                                    ?.stopTimeUpdates?.find { it.stopId == destStopId }
                                val destSec    = ArrivalTimeCalculator.resolvedArrivalSec(destSt, dStu)
                                val minToDest  = ((destSec - System.currentTimeMillis() / 1000L) / 60L).toInt()
                                    .coerceAtLeast(0)
                                binding.tvBigEta.text   = "$minToDest"
                                binding.tvPhaseLabel.text = "MIN TO DESTINATION"
                                binding.tvBoardingStopName.text = destNickname
                                updateTimeline(minToDest, phase = 2)
                            }
                        }
                    } catch (_: Exception) {}
                    delay(20_000)
                }
            }
        }
    }

    private fun updateTimeline(minutesAway: Int, phase: Int, boardingStopName: String = "") {
        val ll = binding.llTimeline
        ll.removeAllViews()
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        // Dynamic milestones: actual ETA + standard checkpoints below it
        val milestones = (listOf(minutesAway) +
            standardMilestones.filter { it < minutesAway }).distinct()

        milestones.forEachIndexed { idx, milestone ->
            val isPast    = minutesAway < milestone || (minutesAway == 0 && idx == milestones.lastIndex)
            val isCurrent = !isPast && (idx == milestones.indexOfFirst { minutesAway >= it })

            val labelText = when {
                idx == 0 && phase == 1 ->
                    if (boardingStopName.isNotEmpty()) "$milestone min · Board at $boardingStopName"
                    else "$milestone min away"
                idx == 0 && phase == 2 -> "$milestone min to destination"
                idx == milestones.lastIndex && phase == 1 -> "Bus arrived!"
                idx == milestones.lastIndex && phase == 2 -> "Arrived!"
                else -> "$milestone min away"
            }

            // Row
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            }

            // Left column: dot + line
            val leftCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val dot = TextView(ctx).apply {
                val dotSize = (if (isCurrent) 18 else 12) * dp
                layoutParams = LinearLayout.LayoutParams(dotSize.toInt(), dotSize.toInt()).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                gravity = Gravity.CENTER
                val dotColor = when {
                    isPast    -> Color.parseColor("#4CAF50")
                    isCurrent -> Color.parseColor("#1565C0")
                    else      -> Color.parseColor("#BDBDBD")
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(dotColor)
                }
                text = if (isPast) "✓" else ""
                setTextColor(Color.WHITE)
                textSize = 9f
            }
            leftCol.addView(dot)

            // Connector line (not for last item)
            if (idx < milestones.lastIndex) {
                val line = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams((2 * dp).toInt(), (28 * dp).toInt()).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    setBackgroundColor(
                        if (isPast) Color.parseColor("#4CAF50") else Color.parseColor("#E0E0E0")
                    )
                }
                leftCol.addView(line)
            }
            row.addView(leftCol)

            // Label
            val label = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * dp).toInt()
                }
                text = labelText
                textSize = if (isCurrent) 16f else 14f
                setTextColor(
                    when {
                        isCurrent -> Color.parseColor("#1565C0")
                        isPast    -> Color.parseColor("#9E9E9E")
                        else      -> Color.parseColor("#212121")
                    }
                )
                if (isCurrent) setTypeface(null, android.graphics.Typeface.BOLD)
            }
            row.addView(label)
            ll.addView(row)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
