package com.bloomington.transit.presentation.planner

import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import android.graphics.Color
import com.bloomington.transit.R
import com.bloomington.transit.databinding.FragmentTripPlannerBinding
import com.bloomington.transit.databinding.ItemJourneyLegBinding
import com.bloomington.transit.databinding.ItemTransferArrowBinding
import com.bloomington.transit.databinding.ItemTripOptionBinding
import com.bloomington.transit.domain.usecase.JourneyPlan
import kotlinx.coroutines.launch
import java.util.Calendar

class TripPlannerFragment : Fragment() {

    private var _binding: FragmentTripPlannerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TripPlannerViewModel by viewModels()

    private lateinit var originAdapter: PlaceAdapter
    private lateinit var destAdapter: PlaceAdapter
    private var originPredictions: List<PlacePrediction> = emptyList()
    private var destPredictions: List<PlacePrediction> = emptyList()

    // Suppress text-change callbacks while we programmatically set text after selection
    private var suppressOriginWatch = false
    private var suppressDestWatch = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTripPlannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tripAdapter = TripOptionAdapter { journey ->
            val statusMsg = viewModel.uiState.value.statusMsg
            // Extract origin/dest names from status message "From: X  →  To: Y"
            val parts = statusMsg.removePrefix("From: ").split("  →  To: ")
            val originName = parts.getOrElse(0) { "" }.substringBefore("  (")
            val destName = parts.getOrElse(1) { "" }.substringBefore("  (")
            val saved = viewModel.saveJourney(journey, originName, destName)
            val msg = if (saved) "Route saved!" else "Already saved"
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
        }
        binding.rvTripOptions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTripOptions.adapter = tripAdapter

        originAdapter = PlaceAdapter(requireContext(), mutableListOf())
        destAdapter = PlaceAdapter(requireContext(), mutableListOf())

        binding.acOrigin.setAdapter(originAdapter)
        binding.acDest.setAdapter(destAdapter)
        binding.acOrigin.threshold = 2
        binding.acDest.threshold = 2

        // Rounded dropdown background
        val dropdownBg = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_dropdown)
        binding.acOrigin.setDropDownBackgroundDrawable(dropdownBg)
        binding.acDest.setDropDownBackgroundDrawable(dropdownBg)

        binding.acOrigin.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppressOriginWatch) viewModel.fetchOriginPredictions(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.acDest.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppressDestWatch) viewModel.fetchDestPredictions(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.acOrigin.setOnItemClickListener { parent, _, pos, _ ->
            val selectedText = parent.getItemAtPosition(pos) as? String ?: return@setOnItemClickListener
            val pred = originPredictions.firstOrNull { it.description == selectedText } ?: return@setOnItemClickListener
            viewModel.selectOrigin(pred)
            suppressOriginWatch = true
            binding.acOrigin.setText(pred.description)
            suppressOriginWatch = false
            binding.acOrigin.dismissDropDown()
            hideKeyboard()
        }

        binding.acDest.setOnItemClickListener { parent, _, pos, _ ->
            val selectedText = parent.getItemAtPosition(pos) as? String ?: return@setOnItemClickListener
            val pred = destPredictions.firstOrNull { it.description == selectedText } ?: return@setOnItemClickListener
            viewModel.selectDest(pred)
            suppressDestWatch = true
            binding.acDest.setText(pred.description)
            suppressDestWatch = false
            binding.acDest.dismissDropDown()
            hideKeyboard()
        }

        // Swap origin ↔ destination
        binding.btnSwap.setOnClickListener {
            val (newOrigin, newDest) = viewModel.swapOriginDest()
            suppressOriginWatch = true
            suppressDestWatch = true
            binding.acOrigin.setText(newOrigin)
            binding.acDest.setText(newDest)
            suppressOriginWatch = false
            suppressDestWatch = false
        }

        // Time mode toggle
        binding.toggleTimeMode.check(R.id.btn_leave_at)
        binding.toggleTimeMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                viewModel.setTimeMode(
                    if (checkedId == R.id.btn_leave_at) TimeMode.LEAVE_AT else TimeMode.ARRIVE_BY
                )
            }
        }

        // Time picker button
        binding.btnPickTime.setOnClickListener {
            val cal = Calendar.getInstance()
            TimePickerDialog(
                requireContext(),
                { _, hour, minute -> viewModel.setTime(hour, minute) },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                false
            ).show()
        }
        binding.btnPickTime.setOnLongClickListener {
            viewModel.clearTime()
            true
        }

        binding.btnSearch.setOnClickListener {
            hideKeyboard()
            viewModel.search()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        binding.progressBar.visibility = if (state.isSearching) View.VISIBLE else View.GONE
                        binding.tvNoResults.visibility = if (state.noResults) View.VISIBLE else View.GONE
                        binding.tvStatusMsg.visibility = if (state.statusMsg.isNotEmpty()) View.VISIBLE else View.GONE
                        binding.tvStatusMsg.text = state.statusMsg
                        binding.btnPickTime.text = state.timeLabel
                        tripAdapter.submitList(state.journeys)
                    }
                }
                launch {
                    viewModel.originPredictions.collect { preds ->
                        originPredictions = preds
                        originAdapter.clear()
                        originAdapter.addAll(preds.map { it.description })
                        originAdapter.notifyDataSetChanged()
                        if (preds.isNotEmpty() && binding.acOrigin.hasFocus()) {
                            binding.acOrigin.showDropDown()
                        }
                    }
                }
                launch {
                    viewModel.destPredictions.collect { preds ->
                        destPredictions = preds
                        destAdapter.clear()
                        destAdapter.addAll(preds.map { it.description })
                        destAdapter.notifyDataSetChanged()
                        if (preds.isNotEmpty() && binding.acDest.hasFocus()) {
                            binding.acDest.showDropDown()
                        }
                    }
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.acOrigin.clearFocus()
        binding.acDest.clearFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class PlaceAdapter(context: Context, items: MutableList<String>) :
    ArrayAdapter<String>(context, R.layout.item_place_suggestion, R.id.tv_place_name, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val full = getItem(position) ?: ""
        val commaIdx = full.indexOf(',')
        val name = if (commaIdx > 0) full.substring(0, commaIdx).trim() else full
        val address = if (commaIdx > 0) full.substring(commaIdx + 1).trim() else ""
        view.findViewById<TextView>(R.id.tv_place_name)?.text = name
        view.findViewById<TextView>(R.id.tv_place_address)?.apply {
            text = address
            visibility = if (address.isNotEmpty()) View.VISIBLE else View.GONE
        }
        return view
    }

    override fun getFilter() = object : android.widget.Filter() {
        override fun performFiltering(c: CharSequence?) = FilterResults().apply {
            values = (0 until count).map { getItem(it) }
            count = this@PlaceAdapter.count
        }
        override fun publishResults(c: CharSequence?, r: FilterResults?) = notifyDataSetChanged()
    }
}

class TripOptionAdapter(
    private val onSave: (JourneyPlan) -> Unit
) : RecyclerView.Adapter<TripOptionAdapter.VH>() {

    private var items: List<JourneyPlan> = emptyList()
    private val savedPositions = mutableSetOf<Int>()

    fun submitList(list: List<JourneyPlan>) {
        items = list
        savedPositions.clear()
        notifyDataSetChanged()
    }

    fun markSaved(position: Int) {
        savedPositions.add(position)
        notifyItemChanged(position)
    }

    inner class VH(val binding: ItemTripOptionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTripOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val journey = items[position]
        val inflater = LayoutInflater.from(holder.itemView.context)
        with(holder.binding) {
            val dur = journey.totalDurationMin
            val transferLabel = when (journey.transferCount) {
                0 -> "Direct"
                1 -> "1 transfer"
                else -> "${journey.transferCount} transfers"
            }
            tvDuration.text = "${dur} min · $transferLabel"

            // Show "Leaves in X min" if departing soon
            val nowSec = System.currentTimeMillis() / 1000L
            val cal = java.util.Calendar.getInstance()
            val todayMidnightSec = cal.apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000L
            val nowMidSec = nowSec - todayMidnightSec
            val depSec = journey.legs.first().departureSec
            val minsToDepart = ((depSec - nowMidSec) / 60).toInt()
            tvTimeRange.text = when {
                minsToDepart in 0..9 -> "⚡ Leaves in ${minsToDepart}m  ·  ${journey.departureStr} → ${journey.arrivalStr}"
                else -> "${journey.departureStr} → ${journey.arrivalStr}"
            }

            llLegs.removeAllViews()
            journey.legs.forEachIndexed { i, leg ->
                // Transfer arrow between legs
                if (i > 0) {
                    val arrowBinding = ItemTransferArrowBinding.inflate(inflater, llLegs, false)
                    val prevLeg = journey.legs[i - 1]
                    val waitMin = ((leg.departureSec - prevLeg.arrivalSec) / 60).toInt().coerceAtLeast(0)
                    arrowBinding.tvTransferNote.text =
                        "↕  Transfer at ${prevLeg.alightStopName}  ·  ${waitMin}m wait"
                    llLegs.addView(arrowBinding.root)
                }

                val legBinding = ItemJourneyLegBinding.inflate(inflater, llLegs, false)
                legBinding.tvRouteBadge.text = leg.routeShortName

                val badgeColor = try {
                    Color.parseColor("#${leg.color.ifEmpty { "1565C0" }}")
                } catch (_: Exception) { Color.parseColor("#1565C0") }
                legBinding.tvRouteBadge.backgroundTintList = ColorStateList.valueOf(badgeColor)

                legBinding.tvBoardInfo.text = "▲ ${leg.boardStopName}  ·  ${leg.departureTimeStr}"
                legBinding.tvAlightInfo.text = "▼ ${leg.alightStopName}  ·  ${leg.arrivalTimeStr}"

                llLegs.addView(legBinding.root)
            }

            val saved = position in savedPositions
            btnSaveRoute.setIconResource(
                if (saved) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            btnSaveRoute.setOnClickListener {
                if (position !in savedPositions) {
                    onSave(journey)
                    markSaved(position)
                }
            }
        }
    }
}
