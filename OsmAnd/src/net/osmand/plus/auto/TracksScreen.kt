package net.osmand.plus.auto

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.model.*
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import net.osmand.plus.R
import net.osmand.plus.configmap.tracks.TrackItem
import net.osmand.plus.configmap.tracks.TrackTab
import net.osmand.plus.configmap.tracks.TrackTabType
import net.osmand.plus.track.data.GPXInfo
import net.osmand.plus.track.helpers.GPXDatabase.GpxDataItem
import net.osmand.plus.track.helpers.GpxDbHelper
import net.osmand.search.core.ObjectType
import net.osmand.search.core.SearchResult
import net.osmand.util.Algorithms
import net.osmand.util.MapUtils

class TracksScreen(
    carContext: CarContext,
    private val settingsAction: Action,
    private val surfaceRenderer: SurfaceRenderer,
    private val trackTab: TrackTab
) : BaseOsmAndAndroidAutoScreen(carContext) {
    val gpxDbHelper: GpxDbHelper = app.gpxDbHelper

    init {
        prepareTrackItems()
    }

    override fun onGetTemplate(): Template {
        val templateBuilder = PlaceListNavigationTemplate.Builder()
        val title = if (trackTab.type == TrackTabType.ALL) {
            app.getString(R.string.sort_last_modified)
        } else {
            trackTab.getName(app, false)
        }
        templateBuilder.setLoading(false)
        setupTracks(templateBuilder)

        return templateBuilder
            .setTitle(title)
            .setActionStrip(ActionStrip.Builder().addAction(settingsAction).build())
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun prepareTrackItems() {
        for (track in trackTab.trackItems) {
            track.file?.let { file ->
                val item = gpxDbHelper.getItem(file) { updateTrack(track, it) }
                if (item != null) {
                    track.dataItem = item
                }
            }
        }
    }

    private fun updateTrack(trackItem: TrackItem, dataItem: GpxDataItem?) {
        trackItem.dataItem = dataItem
        invalidate()
    }

    private fun setupTracks(templateBuilder: PlaceListNavigationTemplate.Builder) {
        val latLon = app.mapViewTrackingUtilities.defaultLocation
        val listBuilder = ItemList.Builder()
        var itemsCount = 0
        for (track in trackTab.trackItems) {
            if (itemsCount == contentLimit) {
                break
            }
            val title = track.name
            val icon = CarIcon.Builder(
                IconCompat.createWithResource(app, R.drawable.ic_action_polygom_dark))
                .setTint(
                    CarColor.createCustom(
                        app.getColor(R.color.icon_color_default_light),
                        app.getColor(R.color.icon_color_default_dark)))
                .build()
            var description = ""
            var dist = 0f
            track.dataItem?.let { dataItem ->
                description = dataItem.nearestCityName ?: ""
                dist = if (dataItem.analysis == null) {
                    0f
                } else {
                    MapUtils.getDistance(latLon, dataItem.analysis?.latLonStart).toFloat()
                }
            }
            val address =
                SpannableString(if (Algorithms.isEmpty(description)) " " else "  • $description")
            val distanceSpan = DistanceSpan.create(TripHelper.getDistance(app, dist.toDouble()))
            address.setSpan(distanceSpan, 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            listBuilder.addItem(Row.Builder()
                .setTitle(title)
                .setImage(icon)
                .addText(address)
                .setOnClickListener { onClickTrack(track) }
                .build())
            itemsCount++
        }
        templateBuilder.setItemList(listBuilder.build())
    }

    private fun onClickTrack(trackItem: TrackItem) {
        val result = SearchResult()
        result.objectType = ObjectType.GPX_TRACK
        result.`object` = trackItem
        result.relatedObject = GPXInfo(trackItem.name, trackItem.file)
        openRoutePreview(settingsAction, surfaceRenderer, result)
    }
}