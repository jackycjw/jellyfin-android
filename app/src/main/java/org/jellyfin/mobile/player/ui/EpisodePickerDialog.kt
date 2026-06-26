package org.jellyfin.mobile.player.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import timber.log.Timber
import java.util.UUID

class EpisodePickerDialog(
    private val currentItem: BaseItemDto,
    private val onEpisodeSelected: (PlayOptions) -> Unit,
) : DialogFragment(), KoinComponent {

    private val apiClient: ApiClient = get()
    private var seasons: List<BaseItemDto> = emptyList()
    private var episodes: List<BaseItemDto> = emptyList()
    private var selectedSeasonIndex = 0
    private var showTitles = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Display at bottom, full width, ~70% height
            val displayMetrics = resources.displayMetrics
            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = (displayMetrics.heightPixels * 0.7).toInt()
                gravity = Gravity.BOTTOM
            }
            window.attributes = params
            window.setLayout(params.width, params.height)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.6f)
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Root: full-screen transparent, clicking outside dismisses
        val root = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }
        // Inner: actual bottom sheet content
        val inner = inflater.inflate(R.layout.bottom_sheet_episode_picker, root, false)
        val innerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        root.addView(inner, innerParams)
        // Don't dismiss when clicking content
        inner.setOnClickListener { /* swallow */ }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val closeButton: View = view.findViewById(R.id.episode_picker_close)
        closeButton.setOnClickListener { dismiss() }

        val toggleButton: TextView = view.findViewById(R.id.show_titles_toggle)
        toggleButton.setOnClickListener {
            showTitles = !showTitles
            updateEpisodeList(view)
        }

        lifecycleScope.launch {
            loadSeasons()
            if (seasons.isNotEmpty()) {
                selectSeason(selectedSeasonIndex, view)
            } else {
                val seriesId = currentItem.seriesId
                if (seriesId != null) loadEpisodes(view, seriesId)
            }
        }
    }

    private suspend fun loadSeasons() {
        val seriesId = currentItem.seriesId ?: return
        seasons = withContext(Dispatchers.IO) {
            try {
                apiClient.tvShowsApi.getSeasons(seriesId).content.items.orEmpty()
                    .filter { it.indexNumber != null }
                    .sortedBy { it.indexNumber }
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load seasons")
                emptyList()
            }
        }
        if (seasons.isNotEmpty()) {
            selectedSeasonIndex = seasons.indexOfFirst { it.id == currentItem.seasonId }
                .takeIf { it >= 0 } ?: seasons.indexOfFirst {
                    it.indexNumber == currentItem.parentIndexNumber
                }.takeIf { it >= 0 } ?: 0
        }
    }

    private fun selectSeason(index: Int, view: View) {
        selectedSeasonIndex = index
        val season = seasons.getOrNull(index) ?: return
        val seasonId = season.id

        val tabsContainer: ViewGroup = view.findViewById(R.id.season_tabs_container)
        tabsContainer.removeAllViews()
        seasons.forEachIndexed { i, s ->
            val tab = layoutInflater.inflate(R.layout.item_season_tab, tabsContainer, false) as TextView
            tab.text = s.name ?: "S${s.indexNumber}"
            tab.isSelected = i == index
            tab.background = resources.getDrawable(R.drawable.season_tab_bg, null)
            tab.setTextColor(
                if (i == index) android.graphics.Color.parseColor("#00A4DC")
                else android.graphics.Color.WHITE
            )
            tab.setOnClickListener { selectSeason(i, view) }
            tabsContainer.addView(tab)
        }

        lifecycleScope.launch {
            loadEpisodes(view, seasonId)
        }
    }

    private suspend fun loadEpisodes(view: View, parentId: UUID) {
        episodes = withContext(Dispatchers.IO) {
            try {
                apiClient.itemsApi.getItems(
                    parentId = parentId,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    sortBy = listOf(ItemSortBy.INDEX_NUMBER),
                    sortOrder = listOf(SortOrder.ASCENDING),
                ).content.items.orEmpty()
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load episodes")
                emptyList()
            }
        }
        updateEpisodeList(view)
    }

    private fun updateEpisodeList(view: View) {
        val list: RecyclerView = view.findViewById(R.id.episode_list)
        val title: TextView = view.findViewById(R.id.episode_picker_title)
        title.text = currentItem.seriesName ?: getString(R.string.episode_picker_title)
        if (episodes.isEmpty()) return

        if (showTitles) {
            list.layoutManager = LinearLayoutManager(requireContext())
            list.adapter = EpisodeListAdapter(episodes, currentItem.id) { episode ->
                playEpisode(episode)
                dismiss()
            }
        } else {
            list.layoutManager = GridLayoutManager(requireContext(), 5)
            list.adapter = EpisodeGridAdapter(episodes, currentItem.id) { episode ->
                playEpisode(episode)
                dismiss()
            }
        }
    }

    private fun playEpisode(episode: BaseItemDto) {
        val itemId = episode.id
        val itemIds = episodes.mapNotNull { it.id }
        val startIndex = itemIds.indexOf(itemId).coerceAtLeast(0)
        onEpisodeSelected(
            PlayOptions(
                ids = itemIds,
                mediaSourceId = null,
                startIndex = startIndex,
                startPosition = null,
                audioStreamIndex = null,
                subtitleStreamIndex = null,
                playFromDownloads = false,
                maxStreamingBitrate = null,
            )
        )
    }

    companion object {
        const val TAG = "EpisodePickerDialog"
    }
}

private class EpisodeGridAdapter(
    private val episodes: List<BaseItemDto>,
    private val currentEpisodeId: UUID,
    private val onClick: (BaseItemDto) -> Unit,
) : RecyclerView.Adapter<EpisodeGridAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_button, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = episodes[position]
        val isSelected = episode.id == currentEpisodeId
        holder.textView.text = episode.indexNumber?.toString() ?: "?"
        holder.textView.isSelected = isSelected
        holder.textView.setTextColor(
            if (isSelected) android.graphics.Color.parseColor("#00A4DC")
            else android.graphics.Color.WHITE
        )
        holder.textView.setOnClickListener { onClick(episode) }
    }

    override fun getItemCount(): Int = episodes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.episode_number)
    }
}

private class EpisodeListAdapter(
    private val episodes: List<BaseItemDto>,
    private val currentEpisodeId: UUID,
    private val onClick: (BaseItemDto) -> Unit,
) : RecyclerView.Adapter<EpisodeListAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_list_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = episodes[position]
        val isSelected = episode.id == currentEpisodeId
        holder.number.text = "第${episode.indexNumber ?: "?"}集"
        holder.number.isSelected = isSelected
        holder.number.setTextColor(
            if (isSelected) android.graphics.Color.parseColor("#00A4DC")
            else android.graphics.Color.WHITE
        )
        holder.title.text = episode.name ?: ""
        holder.title.setTextColor(
            if (isSelected) android.graphics.Color.WHITE
            else android.graphics.Color.parseColor("#CCFFFFFF")
        )
        holder.root.setOnClickListener { onClick(episode) }
    }

    override fun getItemCount(): Int = episodes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: ViewGroup = view.findViewById(R.id.episode_row_root)
        val number: TextView = view.findViewById(R.id.episode_row_number)
        val title: TextView = view.findViewById(R.id.episode_row_title)
    }
}