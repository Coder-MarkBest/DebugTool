package com.debugtools.perfmon.view

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout
import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.TimedValue
import com.debugtools.perfmon.presenter.PerfView
import com.debugtools.perfmon.presenter.ProcessRow

@SuppressLint("ViewConstructor")
class PerfRootView(
    context: Context,
    config: Config,
    onSelect: (String) -> Unit
) : LinearLayout(context), PerfView {

    private val listView = ProcessListView(context, config).also { it.onSelect = onSelect }
    private val detailView = ProcessDetailView(context, config)

    init {
        orientation = HORIZONTAL
        addView(listView, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))
        addView(detailView, LayoutParams(0, LayoutParams.MATCH_PARENT, 7f))
    }

    override fun showList(rows: List<ProcessRow>) {
        listView.submit(rows)
    }

    override fun showDetail(detail: ProcessDetail?, cpuSeries: List<TimedValue<ProcessSample>>) {
        detailView.update(detail, cpuSeries)
    }
}
