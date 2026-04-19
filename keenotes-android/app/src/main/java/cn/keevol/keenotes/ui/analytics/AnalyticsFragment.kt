package cn.keevol.keenotes.ui.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentAnalyticsBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        loadData()
    }

    private fun loadData() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.chartCard.visibility = View.GONE
        binding.emptyText.visibility = View.GONE

        val app = requireActivity().application as KeeNotesApp

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = app.database.noteDao().getNotesCountByYear()

                if (_binding == null) return@launch

                binding.loadingIndicator.visibility = View.GONE

                if (data.isEmpty()) {
                    binding.emptyText.visibility = View.VISIBLE
                } else {
                    binding.chartCard.visibility = View.VISIBLE
                    buildChart(data.map { it.year to it.count })
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (_binding != null) {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.emptyText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun buildChart(data: List<Pair<Int, Int>>) {
        val container = binding.chartContainer
        container.removeAllViews()

        val maxCount = data.maxOf { it.second }.coerceAtLeast(1)
        val inflater = LayoutInflater.from(requireContext())
        val numberFormat = NumberFormat.getNumberInstance()

        for ((year, count) in data) {
            val row = inflater.inflate(R.layout.item_year_bar, container, false)

            row.findViewById<TextView>(R.id.yearLabel).text = year.toString()
            row.findViewById<TextView>(R.id.countLabel).text = formatCount(count, numberFormat)

            // Set bar width proportionally after layout
            val bar = row.findViewById<View>(R.id.bar)
            bar.post {
                val parent = bar.parent as FrameLayout
                val parentWidth = parent.width
                val barWidth = ((count.toFloat() / maxCount) * parentWidth).toInt().coerceAtLeast(dpToPx(4))
                val lp = bar.layoutParams
                lp.width = barWidth
                bar.layoutParams = lp
            }

            container.addView(row)
        }
    }

    private fun formatCount(count: Int, numberFormat: NumberFormat): String {
        return if (count >= 10_000) {
            String.format("%.1fk", count / 1_000.0)
        } else {
            numberFormat.format(count)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
